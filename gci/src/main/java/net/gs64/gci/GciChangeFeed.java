package net.gs64.gci;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A persistent change feed in the stone: writers publish the objects they changed, in the same
 * transaction as the change, and ring a doorbell after the commit; each registered consumer takes
 * the objects published since its last take. Nothing gets lost while a consumer is not running,
 * and parallel writers do not conflict.
 *
 * <p>On the server the feed is an instance of the Smalltalk class {@code GciChangeFeed}, which
 * {@link #install} creates. Each consumer has its own RcQueue (many writers, one reader, no
 * conflicts); {@code publish:} adds one entry to every consumer's queue. Entries hold the objects
 * themselves, so they stay alive until every consumer has taken them. The doorbell is a session
 * signal to each consumer's listener session; signals are not persistent, so a consumer also takes
 * now and then without one.
 *
 * <p>Smalltalk writers use the same object: {@code feed publish: changedObjects. System
 * commitTransaction. feed ring}. Without listing the changed objects:
 * {@code feed commitPublishingWrittenObjects: [:o | o isModelObject]} publishes what the
 * transaction has written (the dirty list, {@code System _writtenObjects}), commits and rings.
 *
 * <p>A consumer that is no longer needed must be unregistered, otherwise its queue keeps growing.
 */
public final class GciChangeFeed {
    /** Signal value sent by {@link #ring()}. */
    public static final long SIGNAL_VALUE = 7301;
    /** Signal text sent by {@link #ring()}. */
    public static final String SIGNAL_MESSAGE = "GciChangeFeed";
    private static final String CLASS_NAME = "GciChangeFeed";

    private static final String[] METHODS = {
        """
        initialize
            consumers := RcKeyValueDictionary new""",
        """
        publish: objects
            "Add the changed objects to every consumer's queue; call it in the writer's transaction, before the commit."
            | entry |
            objects isEmpty ifTrue: [^self].
            entry := objects asArray.
            consumers do: [:e | (e at: 1) add: entry]""",
        """
        publishWrittenObjects: aBlock
            "Publish the persistent objects this transaction has written so far (System _writtenObjects,
             the dirty list), those for which aBlock answers true; nil publishes all of them.
             Call it right before the commit. Objects created in this transaction are not in the
             dirty list; they reach consumers through the written object that references them.
             Adding to an RC collection writes only its internal parts, not the collection itself."
            | written |
            written := System _writtenObjects.
            aBlock notNil ifTrue: [written := written select: aBlock].
            self publish: written.
            ^written size""",
        """
        commitPublishingWrittenObjects: aBlock
            "publishWrittenObjects:, commit and ring. Answers false on a commit conflict (abort and
             retry; the abort also takes back the publish)."
            self publishWrittenObjects: aBlock.
            System commitTransaction ifFalse: [^false].
            self ring.
            ^true""",
        """
        ring
            "After the commit: wake up the consumers' listener sessions."
            consumers do: [:e | | sid |
                sid := GsSession sessionIdOfSerial: (e at: 2).
                (sid notNil and: [sid > 0]) ifTrue: [
                    [System sendSignal: 7301 to: sid withMessage: 'GciChangeFeed'] on: Error do: [:x | x return: nil]]]""",
        """
        register: key serial: serial
            "Create the consumer's queue (or keep it) and remember the serial number of its listener session."
            | e |
            e := consumers at: key ifAbsent: [nil].
            e isNil
                ifTrue: [consumers at: key put: (Array with: RcQueue new with: serial)]
                ifFalse: [e at: 2 put: serial]""",
        """
        unregister: key
            consumers removeKey: key ifAbsent: [nil]""",
        """
        take: key
            "Remove and answer everything published for this consumer (each object once)."
            | e result |
            e := consumers at: key ifAbsent: [^#()].
            result := IdentitySet new.
            (e at: 1) removeAll do: [:entry | result addAll: entry].
            ^result asArray""",
        """
        pendingFor: key
            "Number of entries (publishes) waiting for this consumer."
            | e |
            e := consumers at: key ifAbsent: [^0].
            ^(e at: 1) size""",
        """
        consumerKeys
            ^consumers keys asArray""",
    };

    private final GciSession session;
    private final String name;
    private final Oop feed;

    /**
     * Uses the feed stored under {@code name} (see {@link #install}).
     *
     * @throws IllegalArgumentException there is no such feed in the session's symbol list
     */
    public GciChangeFeed(GciSession session, String name) {
        this.session = Objects.requireNonNull(session, "session");
        this.name = Objects.requireNonNull(name, "name");
        var oop = session.tryResolveSymbol(name, null);
        if (oop == null) throw new IllegalArgumentException("No change feed '" + name + "' in the symbol list; install it with GciChangeFeed.install.");
        this.feed = oop;
    }

    /** Name of the feed (its key in the symbol dictionary). */
    public String name() {
        return name;
    }

    /** The session this object works with. */
    public GciSession session() {
        return session;
    }

    /** The feed object in the stone. */
    public Oop object() {
        return feed;
    }

    /** {@link #install(GciSession, String, String)} into UserGlobals. */
    public static GciChangeFeed install(GciSession session, String name) {
        return install(session, name, "UserGlobals");
    }

    /**
     * Creates (or updates) the class {@code GciChangeFeed} and a feed named {@code name} in
     * {@code dictionary}, if it does not exist yet. Methods are compiled every time, so an existing
     * installation gets the current version. Commit afterwards.
     */
    public static GciChangeFeed install(GciSession session, String name, String dictionary) {
        checkIdentifier(name);
        checkIdentifier(dictionary);
        session.release(session.execute("""
                | dict cls |
                dict := System myUserProfile symbolList objectNamed: #%1$s.
                cls := dict at: #%2$s ifAbsent: [nil].
                cls isNil ifTrue: [
                    cls := Object subclass: '%2$s' instVarNames: #(#consumers)
                        classVars: #() classInstVars: #() poolDictionaries: #() inDictionary: dict].
                nil
                """.formatted(dictionary, CLASS_NAME)));
        var compile = new StringBuilder();
        for (var m : METHODS)
            compile.append("cls compileMethod: '").append(m.replace("'", "''"))
                    .append("' dictionaries: symbols category: 'GciChangeFeed' environmentId: 0.\n");
        session.release(session.execute("""
                | cls symbols |
                symbols := System myUserProfile symbolList.
                cls := (symbols objectNamed: #%s) at: #%s.
                %snil
                """.formatted(dictionary, CLASS_NAME, compile)));
        session.release(session.execute("""
                | dict |
                dict := System myUserProfile symbolList objectNamed: #%1$s.
                (dict includesKey: #%2$s) ifFalse: [dict at: #%2$s put: (%3$s new initialize; yourself)].
                nil
                """.formatted(dictionary, name, CLASS_NAME)));
        return new GciChangeFeed(session, name);
    }

    /**
     * Publishes changed objects to every registered consumer. Call it in the writer's transaction,
     * before the commit, then {@link #ring()} after the commit.
     */
    public void publish(Oop... objects) {
        if (objects.length == 0) return;
        var array = session.newObject(KnownClasses.ARRAY, objects.length);
        try {
            session.storeVaryingOops(array, 1, objects);
            session.release(session.perform(feed, "publish:", array));
        } finally {
            session.release(array);
        }
    }

    /** {@link #publish(Oop...)} for a collection. */
    public void publish(java.util.Collection<Oop> objects) {
        publish(objects.toArray(Oop[]::new));
    }

    /** After the commit: sends the doorbell signal to every consumer's listener session. */
    public void ring() {
        session.release(session.perform(feed, "ring"));
    }

    /**
     * Registers a consumer (or updates the listener session of an existing one). Its queue starts
     * to collect from this transaction's commit on. Commit afterwards.
     *
     * @param consumer key of the consumer, stable across restarts
     * @param listenerSessionId session id of the consumer's {@link GciSignalListener}, which gets the doorbell
     */
    public void register(String consumer, long listenerSessionId) {
        session.send(feed, "register:serial:", consumer, session.evaluate("GsSession serialOfSession: " + listenerSessionId, Long.class));
    }

    /** Removes a consumer and its queue. Commit afterwards. */
    public void unregister(String consumer) {
        session.send(feed, "unregister:", consumer);
    }

    /**
     * Removes and answers everything published for {@code consumer} that this session's view
     * contains (abort first), each object once. The removal becomes permanent with the next
     * commit; abort instead to get the objects again.
     */
    public Oop[] take(String consumer) {
        var key = session.fromValue(consumer);
        var array = Oop.NIL;
        try {
            array = session.perform(feed, "take:", key);
            return session.fetchVaryingOops(array);
        } finally {
            session.release(key, array);
        }
    }

    /** Number of publishes waiting for {@code consumer} in this session's view. */
    public long pending(String consumer) {
        return session.send(Long.class, feed, "pendingFor:", consumer);
    }

    /** Keys of the registered consumers. */
    public List<String> consumers() {
        var array = session.perform(feed, "consumerKeys");
        try {
            var result = new ArrayList<String>();
            for (var k : session.toList(array)) result.add((String) k);
            return result;
        } finally {
            session.release(array);
        }
    }

    private static void checkIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("'" + value + "' is not a Smalltalk identifier.");
    }
}
