package net.gs64.gci;

/**
 * Options for traversals.
 *
 * @param bufferSize body size of the traversal buffer. The default of 64 KB is deliberate: with
 *     128 KB or more, a traversal whose answer fills the buffer often stalls for about 40 ms per
 *     buffer (measured with 3.6.5 and 3.7.6 on one machine); up to 96 KB no stall was seen.
 * @param keepResult keep the execution result of {@link GciSession#executeTraverse} /
 *     {@link GciSession#performTraverse} in the export set (release it yourself)
 * @param clampSpec a ClampSpecification (GciClampedTrav), or null for no clamps
 */
public record GciTraversalOptions(int bufferSize, boolean keepResult, Oop clampSpec) {
    /** 64 KB, see {@link #bufferSize()}. */
    public static final int DEFAULT_BUFFER_SIZE = 64 * 1024;

    public static final GciTraversalOptions DEFAULT = new GciTraversalOptions(DEFAULT_BUFFER_SIZE, false, null);

    public GciTraversalOptions withBufferSize(int size) {
        return new GciTraversalOptions(size, keepResult, clampSpec);
    }

    public GciTraversalOptions withKeepResult(boolean keep) {
        return new GciTraversalOptions(bufferSize, keep, clampSpec);
    }

    public GciTraversalOptions withClampSpec(Oop spec) {
        return new GciTraversalOptions(bufferSize, keepResult, spec);
    }
}
