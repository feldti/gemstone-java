package net.gs64.gci;

import java.util.Objects;

/**
 * Network parameters passed to {@code GciSetNet}.
 *
 * @param stoneName stone name or NRS, e.g. {@code "gs64stone"} or {@code "!@host!gs64stone"}
 * @param hostUserId OS user on the gem host; empty if the NetLDI runs with {@code -a -g}
 * @param hostPassword OS password on the gem host; empty if not required
 * @param gemService gem service NRS, usually {@code "gemnetobject"} or
 *     {@code "!@host#netldi:port!gemnetobject"}
 */
public record GciNetParameters(String stoneName, String hostUserId, String hostPassword, String gemService) {
    public GciNetParameters {
        Objects.requireNonNull(stoneName, "stoneName");
        hostUserId = hostUserId == null ? "" : hostUserId;
        hostPassword = hostPassword == null ? "" : hostPassword;
        gemService = gemService == null || gemService.isEmpty() ? "gemnetobject" : gemService;
    }

    public GciNetParameters(String stoneName, String gemService) {
        this(stoneName, "", "", gemService);
    }

    public GciNetParameters(String stoneName) {
        this(stoneName, "", "", "gemnetobject");
    }
}
