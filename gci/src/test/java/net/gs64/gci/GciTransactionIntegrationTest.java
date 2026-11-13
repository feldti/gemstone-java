package net.gs64.gci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Writes to {@code UserGlobals} of the test user under a unique key and removes it afterwards. A
 * second session in the same process provides concurrent commits.
 */
@EnabledIfEnvironmentVariable(named = "GS_TEST_STONE", matches = ".+")
class GciTransactionIntegrationTest {
    private final String key = "GemStoneGciTest_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private GciSession s;
    private GciSession other;

    private String at() {
        return "UserGlobals at: #" + key + " ifAbsent: [nil]";
    }

    private String put(Object value) {
        return "UserGlobals at: #" + key + " put: " + value;
    }

    @BeforeEach
    void login() {
        s = StoneEnv.login();
        other = StoneEnv.login();
    }

    @AfterEach
    void cleanUp() {
        try {
            other.close();
            if (s.isLoggedIn()) {
                s.setTransactionMode(GciTransactionMode.AUTO_BEGIN);
                s.abort();
                s.execute("UserGlobals removeKey: #" + key + " ifAbsent: [nil]");
                s.commit();
            }
        } finally {
            s.close();
        }
    }

    @StoneTest
    void commitPersistsAndAbortDiscards() {
        s.abort();
        s.execute(put(1));
        assertTrue(s.needsCommit());
        s.commit();
        assertFalse(s.needsCommit());
        s.execute(put(2));
        assertEquals(2L, s.evaluate(at()));
        s.abort();
        assertEquals(1L, s.evaluate(at())); // back to the committed state
    }

    @StoneTest
    void changesBecomeVisibleToOtherSessionsOnlyAfterCommitAndAbort() {
        other.abort();
        s.execute(put(42));
        assertNull(other.evaluate(at())); // uncommitted: invisible
        s.commit();
        assertNull(other.evaluate(at())); // other still sees its old snapshot
        other.abort();
        assertEquals(42L, other.evaluate(at()));
    }

    @StoneTest
    void concurrentWriteIsAConflict() {
        s.abort();
        other.abort();
        other.execute(put(1));
        other.commit();
        s.execute(put(2)); // write the same dictionary
        assertFalse(s.tryCommit());
        s.execute(put(3));
        var ex = assertThrows(GciTransactionConflictException.class, s::commit);
        assertFalse(ex.conflictReport() == null || ex.conflictReport().isEmpty());
        s.abort();
        assertEquals(1L, s.evaluate(at())); // the other session's value won
    }

    @StoneTest
    void runInTransactionRetriesAfterConflict() {
        s.abort();
        other.abort();
        var attempts = new int[1];
        long result = s.runInTransaction(session -> {
            attempts[0]++;
            if (attempts[0] == 1) { // a concurrent commit makes the first attempt conflict
                other.execute(put(100));
                other.commit();
            }
            var current = session.evaluate(at(), Long.class);
            var next = (current == null ? 0 : current) + 1;
            session.execute(put(next));
            return next;
        });
        assertEquals(2, attempts[0]);
        assertEquals(101L, result);
        other.abort();
        assertEquals(101L, other.evaluate(at()));
    }

    @StoneTest
    void runInTransactionAbortsOnException() {
        s.abort();
        assertThrows(IllegalStateException.class, () -> s.doInTransaction(session -> {
            session.execute(put(5));
            throw new IllegalStateException("boom");
        }, 3));
        assertFalse(s.needsCommit());
        assertNull(s.evaluate(at()));
    }

    @StoneTest
    void manualBeginModeControlsTransactions() {
        assertEquals(GciTransactionMode.AUTO_BEGIN, s.transactionMode());
        s.setTransactionMode(GciTransactionMode.MANUAL_BEGIN);
        assertEquals(GciTransactionMode.MANUAL_BEGIN, s.transactionMode());
        assertEquals(GciTransactionMode.AUTO_BEGIN, other.transactionMode()); // per session
        s.abort();
        assertFalse(s.inTransaction());
        s.begin();
        assertTrue(s.inTransaction());
        s.execute(put(7));
        s.commit();
        assertFalse(s.inTransaction()); // manualBegin: commit ends the transaction
        s.begin();
        assertEquals(7L, s.evaluate(at()));
    }
}
