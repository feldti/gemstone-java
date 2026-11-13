package net.gs64.gci;

/**
 * Size of the repository (all extents) on the server.
 *
 * @param fileSize bytes the extents occupy
 * @param freeSpace bytes free inside the extents (reused before the extents grow)
 */
public record GciRepositorySpace(long fileSize, long freeSpace) {
}
