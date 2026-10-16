package org.xander.edt.storagebridge.platform;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Fetch a classical storage revision without using an EDT-connected infobase.
 * The caller provides classical repository credentials, not IB credentials.
 */
public record StorageSnapshotRequest(Path executable, String storageAddress,
        String storageUser, String storagePassword, Long revision, Duration timeout) {

    public StorageSnapshotRequest {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(storageAddress, "storageAddress");
        Objects.requireNonNull(storageUser, "storageUser");
        Objects.requireNonNull(storagePassword, "storagePassword");
        Objects.requireNonNull(timeout, "timeout");
        if (!executable.isAbsolute()) {
            throw new IllegalArgumentException("Absolute path to 1cv8 is required");
        }
        String name = Objects.requireNonNull(executable.getFileName()).toString();
        if (!name.equalsIgnoreCase("1cv8.exe") && !name.equalsIgnoreCase("1cv8c.exe")
                && !name.equals("1cv8") && !name.equals("1cv8c")) {
            throw new IllegalArgumentException("Expected 1cv8 platform executable");
        }
        storageAddress = storageAddress.trim();
        storageUser = storageUser.trim();
        if (storageAddress.isBlank() || storageUser.isBlank()
                || bad(storageAddress) || bad(storageUser) || bad(storagePassword)) {
            throw new IllegalArgumentException("Invalid classical storage credentials");
        }
        if (revision != null && revision < 1) {
            throw new IllegalArgumentException("Repository revision must be positive");
        }
        if (timeout.compareTo(Duration.ofSeconds(10)) < 0
                || timeout.compareTo(Duration.ofMinutes(60)) > 0) {
            throw new IllegalArgumentException("Timeout must be 10 seconds to 60 minutes");
        }
    }

    private static boolean bad(String s) {
        return s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0
            || s.indexOf('\0') >= 0;
    }

    @Override
    public String toString() {
        return "StorageSnapshotRequest[repository=REDACTED, user=REDACTED,"
            + " password=REDACTED, revision=" + revision + "]";
    }
}
