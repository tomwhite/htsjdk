package htsjdk.samtools;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;

public final class SamFilesTest {
    private static final String TEST_DATA = "testdata/htsjdk/samtools/BAMFileIndexTest/";
    private static final File BAM_FILE = new File(TEST_DATA + "index_test.bam");

    @DataProvider(name = "filesAndIndicies")
    public Object[][] getFilesAndIndicies() throws IOException {

        final File REAL_INDEX_FILE = new File(BAM_FILE + ".bai"); //test regular file
        final File SYMLINKED_BAM_WITH_SYMLINKED_INDEX = new File(TEST_DATA, "symlink_with_index.bam");

        return new Object[][]{
                {BAM_FILE, REAL_INDEX_FILE},
                {SYMLINKED_BAM_WITH_SYMLINKED_INDEX, new File(SYMLINKED_BAM_WITH_SYMLINKED_INDEX + ".bai")},
                {new File(TEST_DATA, "symlink_without_linked_index.bam"), REAL_INDEX_FILE.getCanonicalFile()},
                {new File(TEST_DATA, "FileThatDoesntExist"), null}
        };
    }

    @Test(dataProvider ="filesAndIndicies")
    public void testIndexSymlinking(File bam, File expected_index) {
        Assert.assertEquals(SamFiles.findIndex(bam), expected_index);
    }

}