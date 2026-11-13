package net.gs64.gci;

/**
 * Commit failed because of concurrency conflicts with another session. The session keeps its
 * uncommitted changes; GemStone expects an {@link GciSession#abort()} before the work is retried.
 */
public class GciTransactionConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String conflictReport;

    public GciTransactionConflictException(String conflictReport) {
        super("Commit failed due to transaction conflicts."
                + (conflictReport == null || conflictReport.isEmpty() ? "" : " " + conflictReport));
        this.conflictReport = conflictReport;
    }

    /** {@code System transactionConflicts printString}, or null if it could not be fetched. */
    public String conflictReport() {
        return conflictReport;
    }
}
