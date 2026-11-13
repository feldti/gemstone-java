package net.gs64.gci.mapping;

/**
 * Shared boxes for frequent small values kept in snapshots: Java caches Integer/Long boxes only
 * from -128 to 127, a context with hundreds of thousands of tracked objects otherwise holds one
 * box per field.
 */
final class BoxCache {
    private static final int MIN = -128, MAX = 1023;
    private static final Long[] LONGS = new Long[MAX - MIN + 1];
    private static final Integer[] INTS = new Integer[MAX - MIN + 1];

    static {
        for (var i = MIN; i <= MAX; i++) {
            LONGS[i - MIN] = (long) i;
            INTS[i - MIN] = i;
        }
    }

    private BoxCache() {
    }

    static Object intern(Object value) {
        if (value instanceof Long l && l >= MIN && l <= MAX) return LONGS[(int) (l - MIN)];
        if (value instanceof Integer i && i >= MIN && i <= MAX) return INTS[i - MIN];
        return value; // Boolean.valueOf already shares
    }
}
