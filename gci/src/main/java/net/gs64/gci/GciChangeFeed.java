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
 * <p>A consumer that is no longer needed must be unregistered, otherwise its queue keeps growing
 * (and keeps the published objects alive, also objects long removed from the database). For
 * consumers that do not come back (a crashed client), {@link #expireAbsentConsumers} removes those
 * whose listener session is gone; {@link net.gs64.gci.mapping.GsChangeSync} does that by itself.
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
            "Add the changed objects to every consumer's queue; call it in the writer's transaction, before the commit.
             A consumer registered with skipOwn: true gets nothing its own session publishes."
            | entry own |
            objects isEmpty ifTrue: [^self].
            entry := objects asArray.
            own := GsSession serialOfSession: System session.
            consumers do: [:e | (self skips: e serial: own) ifFalse: [(e at: 1) add: entry]]""",
        """
        skips: e serial: own
            ^e size >= 3 and: [(e at: 3) == true and: [(e at: 2) = own]]""",
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
            | own |
            own := GsSession serialOfSession: System session.
            consumers do: [:e | | sid |
                sid := (self skips: e serial: own) ifTrue: [nil] ifFalse: [GsSession sessionIdOfSerial: (e at: 2)].
                (sid notNil and: [sid > 0]) ifTrue: [
                    [System sendSignal: 7301 to: sid withMessage: 'GciChangeFeed'] on: Error do: [:x | x return: nil]]]""",
        """
        register: key serial: serial
            ^self register: key serial: serial skipOwn: false""",
        """
        register: key serial: serial skipOwn: aBoolean
            "Create the consumer's queue (or keep it) and remember the serial number of its listener session.
             skipOwn: true - publishes of that session itself do not go into this queue (and do not ring it):
             a consumer that is also a writer already has its own changes."
            | e |
            e := consumers at: key ifAbsent: [nil].
            e isNil ifTrue: [^consumers at: key put: (Array with: RcQueue new with: serial with: aBoolean with: System timeGmt)].
            e size < 4
                ifTrue: [consumers at: key put: (Array with: (e at: 1) with: serial with: aBoolean with: System timeGmt)]
                ifFalse: [e at: 2 put: serial; at: 3 put: aBoolean; at: 4 put: System timeGmt]""",
        """
        includesConsumer: key
            ^consumers includesKey: key""",
        """
        isListening: e
            "Whether the consumer's listener session is logged in."
            | sid |
            sid := GsSession sessionIdOfSerial: (e at: 2).
            ^sid notNil and: [sid > 0]""",
        """
        expireAbsentFor: seconds
            "Remove the consumers whose listener session is no longer logged in and that have neither
             registered nor taken anything for seconds (entries without a time count as old).
             Answer their keys. Their queues and the objects in them are released."
            | now gone |
            now := System timeGmt.
            gone := Array new.
            consumers keysAndValuesDo: [:k :e | | t |
                t := e size >= 4 ifTrue: [e at: 4] ifFalse: [0].
                (now - t >= seconds and: [(self isListening: e) not]) ifTrue: [gone add: k]].
            gone do: [:k | consumers removeKey: k ifAbsent: [nil]].
            ^gone""",
        """
        unregister: key
            consumers removeKey: key ifAbsent: [nil]""",
        """
        take: key
            "Remove and answer everything published for this consumer (each object once)."
            | e q result |
            e := consumers at: key ifAbsent: [^#()].
            q := e at: 1.
            q isEmpty ifTrue: [^#()].
            e size >= 4 ifTrue: [e at: 4 put: System timeGmt].
            result := IdentitySet new.
            q removeAll do: [:entry | result addAll: entry].
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
        register(consumer, listenerSessionId, false);
    }

    /**
     * Registers a consumer (or updates an existing one). Commit afterwards.
     *
     * @param consumer key of the consumer, stable across restarts
     * @param listenerSessionId session id of the session that gets the doorbell: the consumer's
     *     {@link GciSignalListener}, or the consumer's own session ({@link net.gs64.gci.mapping.GsChangeSync})
     * @param skipOwnPublishes what the session {@code listenerSessionId} publishes itself does not go
     *     into this consumer's queue and does not ring it: for a consumer that is also a writer and
     *     already has its own changes (one session per client)
     */
    public void register(String consumer, long listenerSessionId, boolean skipOwnPublishes) {
        session.send(feed, "register:serial:skipOwn:", consumer,
                session.evaluate("GsSession serialOfSession: " + listenerSessionId, Long.class), skipOwnPublishes);
    }

    /** Removes a consumer and its queue. Commit afterwards. */
    public void unregister(String consumer) {
        session.send(feed, "unregister:", consumer);
    }

    /** Whether {@code consumer} is registered (in this session's view). */
    public boolean isRegistered(String consumer) {
        return session.send(Boolean.class, feed, "includesConsumer:", consumer);
    }

    /**
     * Removes the consumers whose listener session is no longer logged in and that have neither
     * registered nor taken anything for {@code absentFor}, with their queues (the objects in them
     * are released). A running consumer is never removed. Commit afterwards. A removed key that
     * comes back registers anew and must reload.
     *
     * @return the removed keys
     */
    public List<String> expireAbsentConsumers(java.time.Duration absentFor) {
        if (absentFor.isNegative()) throw new IllegalArgumentException("absentFor must not be negative.");
        var array = session.perform(feed, "expireAbsentFor:", Oop.fromSmallInteger(absentFor.toSeconds()));
        try {
            var result = new ArrayList<String>();
            for (var k : session.toList(array)) result.add((String) k);
            return result;
        } finally {
            session.release(array);
        }
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
