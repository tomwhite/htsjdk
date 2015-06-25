package htsjdk.variant.variantcontext.filter;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.GenotypeBuilder;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class HetrozygosityFilterTest {

    Allele refA = Allele.create("A", true);
    Allele G    = Allele.create("G", false);

    @DataProvider(name = "Hets")
    public Iterator<Object[]> hetsProvider() {

        VariantContextBuilder vc_builder = new VariantContextBuilder("testCode", "chr1", 1, 1, Arrays.asList(refA, G));
        GenotypeBuilder gt_builder = new GenotypeBuilder("test");
        List<Object[]> hets = new ArrayList<Object[]>(10);

        hets.add(new Object[]{vc_builder.genotypes(gt_builder.alleles(Arrays.asList(refA, G)).make()).make(), null, true});
        hets.add(new Object[]{vc_builder.genotypes(gt_builder.alleles(Arrays.asList(refA, G)).make()).make(), "test", true});

        //non-variant
        hets.add(new Object[]{vc_builder.genotypes(gt_builder.alleles(Collections.singletonList(refA)).make()).make(), "test", false});
        hets.add(new Object[]{vc_builder.genotypes(gt_builder.alleles(Collections.singletonList(refA)).make()).make(), null, false});

        return hets.iterator();
    }

    @Test(dataProvider = "Hets")
    public void testHetFilter(VariantContext vc, String sample, boolean shouldPass) {
        final HetrozygosityFilter hf;
        if (sample == null) {
            hf = new HetrozygosityFilter(shouldPass);
        } else {
            hf = new HetrozygosityFilter(shouldPass, sample);
        }

        Assert.assertFalse(hf.filterOut(vc));
    }

    @DataProvider(name = "badSamplesProvider")
    public Iterator<Object[]> badSamplesProvider() {

        VariantContextBuilder vc_builder = new VariantContextBuilder("testCode", "chr1", 1, 1, Arrays.asList(refA, G));
        GenotypeBuilder gt_builder = new GenotypeBuilder();
        List<Object[]> hets = new ArrayList<Object[]>(10);

        hets.add(new Object[]{vc_builder.genotypes(Arrays.asList(gt_builder.name("test1").make(), gt_builder.name("test2").make())).make(), null});
        hets.add(new Object[]{vc_builder.genotypes(Arrays.asList(gt_builder.name("test1").make(), gt_builder.name("test2").make())).make(), "notNull"});
        hets.add(new Object[]{vc_builder.genotypes(Collections.singleton(gt_builder.name("This").make())).make(), "That"});

        return hets.iterator();
    }

    @Test(dataProvider = "badSamplesProvider", expectedExceptions = IllegalArgumentException.class)
    public void testbadSample(VariantContext vc, String sample) {
        final HetrozygosityFilter hf;
        if (sample == null) {
            hf = new HetrozygosityFilter(true);
        } else {
            hf = new HetrozygosityFilter(true, sample);
        }

        //should fail since no sample name in filter, but two samples in vc
        hf.filterOut(vc);

    }

    @DataProvider(name = "variantsProvider")
    public Object[][] variantsProvider() {

        VariantContextBuilder vc_builder = new VariantContextBuilder("testCode", "chr1", 1, 1, Arrays.asList(refA, G));
        GenotypeBuilder gt_builder = new GenotypeBuilder("test");
        List<VariantContext> vcs = new ArrayList<VariantContext>(10);

        //hets:
        vcs.add(vc_builder.genotypes(gt_builder.alleles(Arrays.asList(refA, G)).make()).make());
        vcs.add(vc_builder.loc("chr1", 10, 10).genotypes(gt_builder.alleles(Arrays.asList(refA, G)).make()).make());

        //non-variant:
        vcs.add(vc_builder.loc("chr1", 20, 20).genotypes(gt_builder.alleles(Collections.singletonList(refA)).make()).make());
        vcs.add(vc_builder.loc("chr1", 30, 30).genotypes(gt_builder.alleles(Collections.singletonList(refA)).make()).make());

        return new Object[][]{new Object[]{vcs.iterator(), new int[]{1, 10}}};
    }


    @Test(dataProvider = "variantsProvider")
    public void testFilteringIterator(Iterator<VariantContext> vcs, int[] passingPositions) {
        Iterator<VariantContext> filteringIterator = new FilteringIterator(vcs, new HetrozygosityFilter(true, "test"));

        int i = 0;
        while (filteringIterator.hasNext()) {
            VariantContext vc = filteringIterator.next();
            Assert.assertTrue(i < passingPositions.length);
            Assert.assertEquals(vc.getStart(), passingPositions[i++]);
        }
    }
}