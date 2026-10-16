package org.xander.edt.storagebridge.platform;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable result: a classical storage CF and hierarchical platform XML.
 * Neither file is an EDT project, nor may it be copied directly into one.
 */
public record StorageSnapshot(Path cf, Path xmlDirectory, Long requestedRevision) {
    public StorageSnapshot {
        Objects.requireNonNull(cf, "cf");
        Objects.requireNonNull(xmlDirectory, "xmlDirectory");
    }
}
