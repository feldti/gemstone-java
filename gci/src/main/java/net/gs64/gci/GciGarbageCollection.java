package net.gs64.gci;

/**
 * Result of {@link GciRepositoryAdmin#markForCollection()}.
 *
 * @param liveObjects objects reachable from the roots
 * @param deadObjects unreachable objects, reclaimed in the background
 * @param deadBytes approximate bytes the dead objects occupy
 * @param message the server's report (the numbers are null if it has another form)
 */
public record GciGarbageCollection(Long liveObjects, Long deadObjects, Long deadBytes, String message) {
}
