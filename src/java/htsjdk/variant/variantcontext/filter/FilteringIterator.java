package htsjdk.variant.variantcontext.filter;

import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.CloserUtil;
import htsjdk.samtools.util.PeekableIterator;
import htsjdk.variant.variantcontext.VariantContext;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A simple filtering iterator for VariantContexts that takes a base iterator
 * and a VariantContextFilter. Similiar in idea to the SAMRecordFilter. Different from
 * Picard's VariantFilter in that it actually filters the
 */
public class FilteringIterator implements CloseableIterator<VariantContext> {
    private final PeekableIterator<VariantContext> iterator;
    private final VariantContextFilter filter;
    private VariantContext next = null;

    /**
     * Constructor
     *
     * @param iterator the backing iterator
     * @param filter   the filter (which may be a FilterAggregator)
     */
    public FilteringIterator(final Iterator<VariantContext> iterator, final VariantContextFilter filter) {
        this.iterator = new PeekableIterator<VariantContext>(iterator);
        this.filter = filter;
        next = getNextRecord();
    }
    @Override
    public void close() {
        CloserUtil.close(iterator);
    }

    /**
     * Returns true if the iteration has more elements.
     *
     * @return true if the iteration has more elements.  Otherwise returns false.
     */
    @Override
    public boolean hasNext() {
        return next != null;
    }

    /**
     * Returns the next element in the iteration.
     *
     * @return the next element in the iteration
     * @throws java.util.NoSuchElementException
     *
     */
    @Override
    public VariantContext next() {
        if (next == null) {
            throw new NoSuchElementException("Iterator has no more elements.");
        }
        final VariantContext result = next;
        next = getNextRecord();
        return result;
    }

    /**
     * Required method for Iterator API.
     *
     * @throws UnsupportedOperationException
     */
    @Override
    public void remove() {
        throw new UnsupportedOperationException("Remove() not supported by FilteringIterator");
    }

    /**
     * Gets the next record from the underlying iterator that passes the filter
     *
     * @return SAMRecord the next filter-passing record
     */
    private VariantContext getNextRecord() {

        while (iterator.hasNext()) {
            final VariantContext record = iterator.next();

            if (!filter.filterOut(record)) {
                return record;
            }
        }
        return null;
    }

}
