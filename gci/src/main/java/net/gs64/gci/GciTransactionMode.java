package net.gs64.gci;

/** Transaction mode of a session ({@code System transactionMode}). */
public enum GciTransactionMode {
    /** {@code #autoBegin} (default): a new transaction starts after every commit or abort. */
    AUTO_BEGIN("autoBegin"),
    /** {@code #manualBegin}: outside a transaction until {@link GciSession#begin()}. */
    MANUAL_BEGIN("manualBegin"),
    /** {@code #transactionless}: never in a transaction, for read-mostly sessions. */
    TRANSACTIONLESS("transactionless");

    final String symbol;

    GciTransactionMode(String symbol) {
        this.symbol = symbol;
    }

    static GciTransactionMode of(String symbol) {
        for (var m : values()) if (m.symbol.equals(symbol)) return m;
        throw new IllegalStateException("Unknown transaction mode #" + symbol + ".");
    }
}
