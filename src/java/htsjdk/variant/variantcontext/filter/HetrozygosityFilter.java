package htsjdk.variant.variantcontext.filter;

import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;

/**
 * Created by farjoun on 6/25/15.
 */
public class HetrozygosityFilter implements VariantContextFilter {

    private String sample;
    private boolean keepHets;

    /**
     * Constructor
     *
     * @param keepHets determine whether to keep the het sites ( true ) or filter them out ( false )
     * @param sample the name of the sample in the variant context whose genotype should be examined.
     */

    public HetrozygosityFilter(boolean keepHets, String sample) {
        this.keepHets = keepHets;
        this.sample = sample;
    }

    HetrozygosityFilter(boolean keepHets) {
        this(keepHets, null);
    }


    /* @return true if the VariantContext not be kept, otherwise false
    * Assumes that <sample> is a sample in the vcf. */
    @Override
    public boolean filterOut(final VariantContext record) {

        final Genotype gt;
        if (sample == null) {
            gt = record.getGenotype(0);
        } else {
            gt = record.getGenotype(sample);
        }
        return gt.isHet() ^ keepHets;
    }
}
