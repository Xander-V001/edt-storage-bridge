package org.xander.edt.storagebridge.core;

/**
 * Immutable, display-only snapshot of an Eclipse workspace project.
 * It intentionally contains no credentials or repository commands.
 */
public record ProjectSnapshot(String name, String location) {

    public ProjectSnapshot {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Project name must not be blank");
        }
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Project location must not be blank");
        }
    }
}
