package net.gs64.gci;

import java.time.Instant;

/**
 * A session logged in to the stone ({@code System descriptionOfSession:}).
 *
 * @param sessionId session id, for {@link GciRepositoryAdmin#stopSession}
 * @param serialNumber serial number (unique while the stone runs)
 * @param userId user, or null (logging in, system sessions)
 * @param gemHost host of the gem
 * @param gemProcessId process id of the gem (0 if none)
 * @param clientAddress address of the GCI client as the gem sees it
 * @param inTransaction true in a transaction, false outside, null in transactionless mode
 * @param referencesOldestCommitRecord the session holds the oldest view: until it commits or
 *     aborts, the space of objects that died since then is not reclaimed
 * @param commitsSinceView commits by others since the session's view began
 * @param lastTransaction last begin, commit or abort
 * @param loggedIn login time
 * @param systemDescription description of a system session (gc gem …), otherwise null
 * @param isCurrent the session of this {@link GciSession}
 */
public record GciSessionInfo(long sessionId, long serialNumber, String userId, String gemHost, long gemProcessId, String clientAddress,
                             Boolean inTransaction, boolean referencesOldestCommitRecord, long commitsSinceView,
                             Instant lastTransaction, Instant loggedIn, String systemDescription, boolean isCurrent) {
}
