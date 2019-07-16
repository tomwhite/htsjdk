/*
 * The MIT License
 *
 * Copyright (c) 2019 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package htsjdk.samtools;

import htsjdk.HtsjdkTest;
import htsjdk.samtools.cram.io.InputStreamUtils;
import htsjdk.samtools.seekablestream.ByteArraySeekableStream;
import htsjdk.samtools.seekablestream.SeekableStream;
import htsjdk.samtools.util.BlockCompressedStreamConstants;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.ProgressLoggerInterface;
import htsjdk.samtools.util.RuntimeIOException;
import htsjdk.utils.ValidationUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public class BAMMergerTest extends HtsjdkTest {

    private final static Path BAM_FILE = new File("src/test/resources/htsjdk/samtools/BAMFileIndexTest/index_test.bam").toPath();

    /**
     * Writes a <i>partitioned BAM</i>.
     *
     * A partitioned BAM is a directory containing the following files:
     * <ol>
     *     <li>A file named <i>header</i> containing all header bytes in BAM format.</li>
     *     <li>Zero or more files named <i>part-00000</i>, <i>part-00001</i>, ... etc, containing a list of alignments in BAM format.</li>
     *     <li>A file named <i>terminator</i> containing a BGZF end-of-file marker block.</li>
     * </ol>
     *
     * If an index is required, a BAM index can be generated for each (headerless) part file. These files
     * should be named <i>.part-00000.bai</i>, <i>.part-00001.bai</i>, ... etc. Note the leading <i>.</i> to make the files hidden.
     *
     * This format has the following properties:
     *
     * <ul>
     *     <li>Parts and their indexes may be written in parallel, since one part file can be written independently of the others.</li>
     *     <li>A BAM file can be created from a partitioned BAM file by concatenating all the non-hidden files (<i>header</i>, <i>part-00000</i>, <i>part-00001</i>, ..., <i>terminator</i>).</li>
     *     <li>A BAM index can be created from a partitioned BAM file by merging all of the hidden files with a <i>.bai</i> suffix. Note that this is <i>not</i> a simple file concatenation operation. See {@link BAMIndexMerger}.</li>
     * </ul>
     *
     * Note that this writer is only for single-threaded use. Consider using the implementation in Disq for a partitioned BAM writer
     * that works with multiple threads or in a distributed setting.
     */
    static class PartitionedBAMFileWriter implements SAMFileWriter {
        private final Path outputDir;
        private final SAMFileHeader header;
        private int recordsPerPart;
        private long recordCount = 0;
        private int partNumber = -1;
        private BAMStreamWriter samStreamWriter;
        private ProgressLoggerInterface progressLogger;

        public PartitionedBAMFileWriter(Path outputDir, SAMFileHeader header, int recordsPerPart) {
            this.outputDir = outputDir;
            this.header = header;
            this.recordsPerPart = recordsPerPart;
        }

        @Override
        public void addAlignment(SAMRecord alignment) {
            if (recordCount == 0) {
                // write header
                try (OutputStream out = Files.newOutputStream(outputDir.resolve("header"))) {
                    new BAMStreamWriter(out, null, null, -1, header).writeHeader(header);
                } catch (IOException e) {
                    throw new RuntimeIOException(e);
                }
            }
            if (recordCount % recordsPerPart == 0) {
                // start a new part
                try {
                    if (samStreamWriter != null) {
                        samStreamWriter.finish(false);
                    }
                    partNumber++;
                    String partName = String.format("part-%05d", partNumber);
                    OutputStream out = Files.newOutputStream(outputDir.resolve(partName));
                    OutputStream indexOut = Files.newOutputStream(outputDir.resolve("." + partName + BAMIndex.BAI_INDEX_SUFFIX));
                    OutputStream sbiOut = Files.newOutputStream(outputDir.resolve("." + partName + SBIIndex.FILE_EXTENSION));
                    long sbiGranularity = 1; // set to one so we can test merging
                    samStreamWriter = new BAMStreamWriter(out, indexOut, sbiOut, sbiGranularity, header);
                } catch (IOException e) {
                    throw new RuntimeIOException(e);
                }
            }
            recordCount++;
            samStreamWriter.writeAlignment(alignment);
            if (progressLogger != null) {
                progressLogger.record(alignment);
            }
        }

        @Override
        public SAMFileHeader getFileHeader() {
            return header;
        }

        @Override
        public void setProgressLogger(ProgressLoggerInterface progressLogger) {
            this.progressLogger = progressLogger;
        }

        @Override
        public void close() {
            if (samStreamWriter != null) {
                samStreamWriter.finish(false);
            }
            // write terminator
            try (OutputStream out = Files.newOutputStream(outputDir.resolve("terminator"))) {
                out.write(BlockCompressedStreamConstants.EMPTY_GZIP_BLOCK);
            } catch (IOException e) {
                throw new RuntimeIOException(e);
            }
        }
    }

    /**
     * Merge the files created by {@link PartitionedBAMFileWriter} into a single BAM file and index.
     */
    static class PartitionedBAMFileMerger {
        public void merge(Path dir, Path outputBam, Path outputBai, Path outputSbi) throws IOException {
            Path headerPath = dir.resolve("header");
            List<Path> bamParts = Files.list(dir)
                    .filter(path -> !path.toString().endsWith(BAMIndex.BAI_INDEX_SUFFIX) && !path.toString().endsWith(SBIIndex.FILE_EXTENSION)) // include header and terminator
                    .sorted()
                    .collect(Collectors.toList());
            List<Path> baiParts = Files.list(dir)
                    .filter(path -> path.toString().endsWith(BAMIndex.BAI_INDEX_SUFFIX))
                    .sorted()
                    .collect(Collectors.toList());
            List<Path> sbiParts = Files.list(dir)
                    .filter(path -> path.toString().endsWith(SBIIndex.FILE_EXTENSION))
                    .sorted()
                    .collect(Collectors.toList());

            ValidationUtils.validateArg(bamParts.size() - 2 == baiParts.size(), "Number of BAM part files does not match number of BAI files (" + baiParts.size() + ")");

            SAMFileHeader header = SamReaderFactory.makeDefault().open(headerPath).getFileHeader();

            // merge BAM parts
            try (OutputStream out = Files.newOutputStream(outputBam)) {
                for (Path bamPart : bamParts) {
                    Files.copy(bamPart, out);
                }
            }

            // merge index parts
            try (OutputStream out = Files.newOutputStream(outputBai)) {
                BAMIndexMerger bamIndexMerger = new BAMIndexMerger(out, Files.size(headerPath));
                int i = 1; // start from 1 since we ignore the header
                for (Path baiPart : baiParts) {
                    try (InputStream in = Files.newInputStream(baiPart)) {
                        // read all bytes into memory since AbstractBAMFileIndex reads lazily
                        byte[] bytes = InputStreamUtils.readFully(in);
                        SeekableStream allIn = new ByteArraySeekableStream(bytes);
                        AbstractBAMFileIndex index = new CachingBAMFileIndexOptimized(allIn, header.getSequenceDictionary());
                        bamIndexMerger.processIndex(index, Files.size(bamParts.get(i++)));
                    }
                }
                bamIndexMerger.finish(Files.size(outputBam));
            }

            // merge SBI index parts
            try (OutputStream out = Files.newOutputStream(outputSbi)) {
                SBIIndexMerger sbiIndexMerger = new SBIIndexMerger(out, Files.size(headerPath));
                int i = 1; // start from 1 since we ignore the header
                for (Path sbiPart : sbiParts) {
                    try (InputStream in = Files.newInputStream(sbiPart)) {
                        SBIIndex index = SBIIndex.load(in);
                        sbiIndexMerger.processIndex(index, Files.size(bamParts.get(i++)));
                    }
                }
                sbiIndexMerger.finish(Files.size(outputBam));
            }
        }
    }

    // index a BAM file
    private static Path indexBam(Path bam, Path bai) throws IOException {
        try (SamReader in =
                     SamReaderFactory.makeDefault()
                             .validationStringency(ValidationStringency.SILENT)
                             .enable(SamReaderFactory.Option.INCLUDE_SOURCE_IN_RECORDS)
                             .disable(SamReaderFactory.Option.VALIDATE_CRC_CHECKSUMS)
                             .open(SamInputResource.of(bam))) {

            final BAMIndexer indexer = new BAMIndexer(bai, in.getFileHeader());
            for (final SAMRecord rec : in) {
                indexer.processAlignment(rec);
            }
            indexer.finish();
        }
        BAMSBIIndexer.createIndex(bam, 1);
        textIndexBai(bai);
        return bai;
    }

    // create a human-readable BAI
    private static Path textIndexBai(Path bai) {
        Path textBai = bai.resolveSibling(bai.getFileName().toString() + ".txt");
        BAMIndexer.createAndWriteIndex(bai.toFile(), textBai.toFile(), true);
        return textBai;
    }

    @Test
    public void test() throws IOException {
        final Path outputDir = IOUtil.createTempDir(this.getClass().getSimpleName() + ".", ".tmp").toPath();
        IOUtil.deleteOnExit(outputDir);

        final Path outputBam = File.createTempFile(this.getClass().getSimpleName() + ".", ".bam").toPath();
        IOUtil.deleteOnExit(outputBam);

        final Path outputBai = IOUtil.addExtension(outputBam, BAMIndex.BAI_INDEX_SUFFIX);
        IOUtil.deleteOnExit(outputBai);

        final Path outputSbi = IOUtil.addExtension(outputBam, SBIIndex.FILE_EXTENSION);
        IOUtil.deleteOnExit(outputSbi);

        final Path outputBaiMerged = File.createTempFile(this.getClass().getSimpleName() + ".", BAMIndex.BAI_INDEX_SUFFIX).toPath();
        IOUtil.deleteOnExit(outputBaiMerged);

        final Path outputSbiMerged = File.createTempFile(this.getClass().getSimpleName() + ".", SBIIndex.FILE_EXTENSION).toPath();
        IOUtil.deleteOnExit(outputBaiMerged);

        // 1. Read an input BAM and write it out in partitioned form (header, parts, terminator)
        try (SamReader samReader = SamReaderFactory.makeDefault().open(BAM_FILE);
            PartitionedBAMFileWriter partitionedBAMFileWriter = new PartitionedBAMFileWriter(outputDir, samReader.getFileHeader(), 2500)) { // BAM file has 10000 reads
            for (SAMRecord samRecord : samReader) {
                partitionedBAMFileWriter.addAlignment(samRecord);
            }
        }

        // 2. Merge the partitioned BAM and index
        new PartitionedBAMFileMerger().merge(outputDir, outputBam, outputBaiMerged, outputSbiMerged);
        textIndexBai(outputBaiMerged); // for debugging

        // 3. Index the merged BAM (using regular indexing)
        indexBam(outputBam, outputBai);

        // 4. Assert that the merged index is the same as the index produced from the merged file
        // Check equality on object before comparing file contents to get a better indication
        // of the difference in case they are not equal.
        BaiEqualityChecker.assertEquals(outputBam, outputBai, outputBaiMerged);
        Assert.assertEquals(
                com.google.common.io.Files.toByteArray(outputBai.toFile()),
                com.google.common.io.Files.toByteArray(outputBaiMerged.toFile()));

        // 5. Assert that the merged SBI index is the same as the SBI index produced from the merged file
        Assert.assertEquals(
                com.google.common.io.Files.toByteArray(outputSbi.toFile()),
                com.google.common.io.Files.toByteArray(outputSbiMerged.toFile()));
    }
}
