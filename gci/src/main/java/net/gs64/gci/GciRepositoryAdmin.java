package net.gs64.gci;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Repository administration over a {@link GciSession}: space, garbage collection, instance
 * counts, sessions, backups. For tools and test databases; production stones usually schedule
 * garbage collection on the server.
 *
 * <p>{@link #markForCollection()}, {@link #stopSession} and {@link #fullBackup} need privileges
 * (GarbageCollection, SessionAccess, FileControl …) that ordinary users should not have; without
 * them the server answers with an error. The long-running calls have {@code …Async} variants that
 * leave other sessions of the process free to work.
 */
public final class GciRepositoryAdmin {
    private static final String MARK_SOURCE =
            "[SystemRepository markForCollection. 'markForCollection completed'] on: Warning do: [:w | w return: w messageText]";
    private static final Pattern COLLECTION_REPORT =
            Pattern.compile("found (\\d+) live objects, (\\d+) dead objects\\s*\\(occupying approx (\\d+) bytes\\)");
    // One flat Array, 13 values per session: no locale-dependent printing, one round trip.
    private static final String SESSIONS_SOURCE = """
            | out me |
            me := System session.
            out := Array new.
            System currentSessions do: [:id | | d |
              d := System descriptionOfSession: id.
              out add: id; add: (d at: 9); add: ((d at: 1) ifNotNil: [:u | u userId]);
                add: (d at: 3) asString; add: (d at: 2); add: (d at: 11) asString; add: (d at: 7);
                add: (d at: 8); add: (d at: 16); add: (d at: 5); add: (d at: 15); add: (d at: 17); add: id = me].
            out
            """;

    private final GciSession session;

    public GciRepositoryAdmin(GciSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** Size and free space of the repository. */
    public GciRepositorySpace space() {
        var t = session.executeTraverse("{SystemRepository fileSize. SystemRepository freeSpace}", 1, null);
        var v = t.toList(t.result());
        return new GciRepositorySpace(toLong(v.get(0)), toLong(v.get(1)));
    }

    /**
     * Marks the repository for garbage collection ({@code SystemRepository markForCollection}):
     * finds the objects no longer reachable; gc gems then reclaim their space in the background,
     * once every other session has committed or aborted.
     *
     * <p>Aborts this session's transaction, so it must have no uncommitted changes (commit a
     * deletion first, then collect). Waits up to two minutes if another collection is still
     * running, then fails. A soft break cannot interrupt it.
     *
     * @throws IllegalStateException the session has uncommitted changes
     */
    public GciGarbageCollection markForCollection() {
        throwIfUncommitted();
        return parseCollection(session.evaluate(MARK_SOURCE, String.class));
    }

    /** {@link #markForCollection()} without blocking the GCI. */
    public GciFuture<GciGarbageCollection> markForCollectionAsync() {
        throwIfUncommitted();
        return session.evaluateAsync(MARK_SOURCE, String.class).map(GciRepositoryAdmin::parseCollection);
    }

    private void throwIfUncommitted() {
        if (session.needsCommit())
            throw new IllegalStateException("The session has uncommitted changes: commit or abort first (this operation aborts the transaction).");
    }

    static GciGarbageCollection parseCollection(String message) {
        var m = COLLECTION_REPORT.matcher(message);
        if (!m.find()) return new GciGarbageCollection(null, null, null, message);
        return new GciGarbageCollection(Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3)), message);
    }

    /**
     * Instances of the named classes in the repository ({@code SystemRepository countInstances:}),
     * in the same order. Scans the whole repository; counts dead objects that are not reclaimed
     * yet. Only committed objects. Aborts the session's transaction, like markForCollection.
     *
     * @throws IllegalStateException the session has uncommitted changes
     */
    public List<Long> countInstances(String... classNames) {
        var source = countSource(classNames);
        throwIfUncommitted();
        var t = session.executeTraverse(source, 1, null);
        return toCounts(classNames, t.toList(t.result()));
    }

    /** {@link #countInstances} without blocking the GCI. */
    public GciFuture<List<Long>> countInstancesAsync(String... classNames) {
        var source = countSource(classNames);
        throwIfUncommitted();
        return session.executeAsync(source).map(result -> {
            try {
                return toCounts(classNames, session.toList(result));
            } finally {
                session.release(result);
            }
        });
    }

    private static String countSource(String[] classNames) {
        for (var name : classNames)
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("'" + name + "' is not a class name.");
        return "SystemRepository countInstances: {" + String.join(". ", classNames) + "}";
    }

    private static List<Long> toCounts(String[] classNames, List<Object> values) {
        if (values.size() != classNames.length)
            throw new GciException("countInstances: answered " + values.size() + " counts for " + classNames.length + " classes.");
        return values.stream().map(GciRepositoryAdmin::toLong).toList();
    }

    /** The sessions logged in to the stone, including system sessions (gc gems …). */
    public List<GciSessionInfo> sessions() {
        var t = session.executeTraverse(SESSIONS_SOURCE, 2, null);
        var v = t.toList(t.result());
        var sessions = new ArrayList<GciSessionInfo>(v.size() / 13);
        for (var i = 0; i + 12 < v.size(); i += 13) {
            var tx = toLong(v.get(i + 6));
            sessions.add(new GciSessionInfo(toLong(v.get(i)), toLong(v.get(i + 1)), v.get(i + 2) instanceof String u ? u : null,
                    v.get(i + 3) instanceof String h ? h : "", toLong(v.get(i + 4)), v.get(i + 5) instanceof String a ? a : "",
                    tx < 0 ? null : tx != 0, Boolean.TRUE.equals(v.get(i + 7)), toLong(v.get(i + 8)),
                    Instant.ofEpochSecond(toLong(v.get(i + 9))), Instant.ofEpochSecond(toLong(v.get(i + 10))),
                    v.get(i + 11) instanceof String d && !d.isEmpty() ? d : null, Boolean.TRUE.equals(v.get(i + 12))));
        }
        return sessions;
    }

    /**
     * Ends another session ({@code System stopSession:}); its uncommitted work is lost.
     *
     * @throws IllegalArgumentException the id is this session's own
     */
    public void stopSession(long sessionId) {
        if (sessionId == session.gemSessionId()) throw new IllegalArgumentException("This is the session's own id; log out instead.");
        session.execute("System stopSession: " + sessionId + ". true");
    }

    /**
     * Writes a full backup to {@code serverPath} ({@code SystemRepository fullBackupTo:} or
     * {@code fullBackupCompressedTo:}): a path on the <b>stone's</b> host, not the client's.
     */
    public void fullBackup(String serverPath, boolean compressed) {
        session.execute(backupSource(serverPath, compressed));
    }

    /** {@link #fullBackup} without blocking the GCI. */
    public GciFuture<Void> fullBackupAsync(String serverPath, boolean compressed) {
        return session.executeAsync(backupSource(serverPath, compressed)).map(r -> {
            session.release(r);
            return null;
        });
    }

    private static String backupSource(String serverPath, boolean compressed) {
        if (serverPath == null || serverPath.isEmpty()) throw new IllegalArgumentException("serverPath must not be empty.");
        return "SystemRepository " + (compressed ? "fullBackupCompressedTo:" : "fullBackupTo:") + " '" + serverPath.replace("'", "''") + "'. true";
    }

    private static long toLong(Object value) {
        if (value instanceof Long l) return l;
        if (value == null) return 0;
        throw new GciException("Expected an integer, got " + value + ".");
    }
}
