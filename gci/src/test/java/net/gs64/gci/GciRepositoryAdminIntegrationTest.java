package net.gs64.gci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Repository administration. Commits a class and instances under a unique key in UserGlobals and
 * removes them again; runs one markForCollection on the test stone.
 */
class GciRepositoryAdminIntegrationTest {
    @Test
    void theCollectionReportIsParsed() {
        var gc = GciRepositoryAdmin.parseCollection(
                "markForCollection found 3039506 live objects, 12264 dead objects(occupying approx 1103760 bytes), 0 possibleDeadSymbols");
        assertEquals(3039506L, gc.liveObjects());
        assertEquals(12264L, gc.deadObjects());
        assertEquals(1103760L, gc.deadBytes());
        var other = GciRepositoryAdmin.parseCollection("something else");
        assertNull(other.liveObjects());
        assertEquals("something else", other.message());
    }

    @EnabledIfEnvironmentVariable(named = "GS_TEST_STONE", matches = ".+")
    @org.junit.jupiter.api.Nested
    class WithStone {
        private GciSession s;
        private final String key = "GemStoneGciTest_" + UUID.randomUUID().toString().replace("-", "");
        private final String cls = "GciAdminTestItem" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        @BeforeEach
        void login() {
            s = StoneEnv.login();
        }

        @AfterEach
        void cleanUp() {
            if (s.isLoggedIn()) {
                s.abort();
                s.execute("UserGlobals removeKey: #" + key + " ifAbsent: [nil]; removeKey: #" + cls + " ifAbsent: [nil]. true");
                s.commit();
            }
            s.close();
        }

        private GciRepositoryAdmin admin() {
            return new GciRepositoryAdmin(s);
        }

        @StoneTest
        void spaceOfTheRepository() {
            var space = admin().space();
            assertTrue(space.fileSize() > 0);
            assertTrue(space.freeSpace() >= 0 && space.freeSpace() <= space.fileSize());
        }

        @StoneTest
        void countInstancesCountsCommittedObjectsOnly() throws Exception {
            s.execute("""
                    | c | c := Object subclass: '%s' instVarNames: #() classVars: #() classInstVars: #()
                        poolDictionaries: #() inDictionary: UserGlobals.
                    UserGlobals at: #%s put: {c new. c new. c new}.
                    true
                    """.formatted(cls, key));
            // Counting aborts: with uncommitted changes it refuses instead of losing them.
            assertThrows(IllegalStateException.class, () -> admin().countInstances(cls));
            s.commit();
            assertEquals(List.of(3L, 1L), admin().countInstances(cls, "UserProfileSet"));
            assertEquals(List.of(3L), admin().countInstancesAsync(cls).get());
            assertThrows(IllegalArgumentException.class, () -> admin().countInstances("Object new"));
        }

        @StoneTest
        void markForCollectionFindsDeadObjects() throws Exception {
            // Commit objects, then make them unreachable and commit again: they are dead.
            s.execute("UserGlobals at: #" + key + " put: ((1 to: 100) collect: [:i | Array with: i]). true");
            s.commit();
            s.execute("UserGlobals removeKey: #" + key + ". true");
            s.commit();
            s.execute("UserGlobals at: #" + key + " put: 1. true"); // uncommitted: the collection would abort it
            assertThrows(IllegalStateException.class, () -> admin().markForCollectionAsync());
            s.abort();
            var gc = admin().markForCollectionAsync().get();
            assertTrue(gc.liveObjects() != null && gc.liveObjects() > 0, gc.message());
            assertTrue(gc.deadObjects() >= 101, gc.message());
            assertTrue(gc.deadBytes() > 0, gc.message());
        }

        @StoneTest
        void sessionsListsThisAndOtherSessionsAndStopsOne() throws Exception {
            try (var other = StoneEnv.login()) {
                var otherId = other.gemSessionId();
                var sessions = admin().sessions();
                var me = sessions.stream().filter(GciSessionInfo::isCurrent).toList();
                assertEquals(1, me.size());
                assertEquals(s.gemSessionId(), me.get(0).sessionId());
                assertEquals(StoneEnv.user(), me.get(0).userId());
                assertTrue(me.get(0).loggedIn().isAfter(Instant.now().minus(Duration.ofDays(1))) && !me.get(0).loggedIn().isAfter(Instant.now().plusSeconds(60)));
                var them = sessions.stream().filter(x -> x.sessionId() == otherId).findFirst().orElseThrow();
                assertFalse(them.isCurrent());
                assertTrue(me.get(0).serialNumber() != them.serialNumber());
                assertThrows(IllegalArgumentException.class, () -> admin().stopSession(me.get(0).sessionId()));
                admin().stopSession(otherId);
                assertTrue(pollUntil(() -> admin().sessions().stream().noneMatch(x -> x.sessionId() == otherId && x.serialNumber() == them.serialNumber())));
                assertThrows(GciException.class, () -> other.evaluate("3 + 4"));
            }
        }

        @StoneTest
        void aBackupToAMissingDirectoryFails() {
            assertThrows(GciException.class, () -> admin().fullBackup("/no/such/directory/gci-test.bak", false));
            var e = assertThrows(ExecutionException.class, () -> admin().fullBackupAsync("/no/such/directory/gci-test.bak", true).get());
            assertInstanceOf(GciException.class, e.getCause());
            assertTrue(s.isLoggedIn());
        }

        private boolean pollUntil(BooleanSupplier condition) throws InterruptedException {
            var until = System.nanoTime() + 10_000_000_000L;
            while (System.nanoTime() < until) {
                if (condition.getAsBoolean()) return true;
                Thread.sleep(50);
            }
            return condition.getAsBoolean();
        }
    }
}
