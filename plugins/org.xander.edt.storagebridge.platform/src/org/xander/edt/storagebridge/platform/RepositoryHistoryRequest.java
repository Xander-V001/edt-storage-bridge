package org.xander.edt.storagebridge.platform;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * A strictly read-only history report query.
 *
 * <p>Latest means -NBegin -1. A bounded query accepts an inclusive version
 * range. This object does not contain or accept credentials.
 */
public record RepositoryHistoryRequest(
        Path executable,
        InfobaseLocation infobase,
        long beginVersion,
        Long endVersion,
        Duration timeout) {

    private static final Duration MIN_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(30);

    public RepositoryHistoryRequest {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(infobase, "infobase");
        Objects.requireNonNull(timeout, "timeout");
        if (!executable.isAbsolute()) {
            throw new IllegalArgumentException("1C executable path must be absolute");
        }
        String name = Objects.requireNonNull(executable.getFileName()).toString();
        if (!name.equalsIgnoreCase("1cv8.exe")
                && !name.equals("1cv8")) {
            throw new IllegalArgumentException("Expected 1C platform executable (1cv8)");
        }
        if (beginVersion != -1 && beginVersion < 1) {
            throw new IllegalArgumentException("Begin revision must be -1 or positive");
        }
        if (endVersion != null
                && (beginVersion == -1 || endVersion < beginVersion)) {
            throw new IllegalArgumentException("Invalid repository history range");
        }
        if (timeout.compareTo(MIN_TIMEOUT) < 0 || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("Timeout must be 2 seconds to 30 minutes");
        }
    }

    public static RepositoryHistoryRequest latest(Path executable,
            InfobaseLocation infobase, Duration timeout) {
        return new RepositoryHistoryRequest(executable, infobase, -1, null, timeout);
    }

    public static RepositoryHistoryRequest range(Path executable,
            InfobaseLocation infobase, long beginVersion, long endVersion,
            Duration timeout) {
        if (beginVersion < 1) {
            throw new IllegalArgumentException("Range must start at version 1 or later");
        }
        return new RepositoryHistoryRequest(executable, infobase, beginVersion,
            endVersion, timeout);
    }

    @Override
    public String toString() {
        return "RepositoryHistoryRequest[paths=REDACTED, beginVersion="
            + beginVersion + ", endVersion=" + endVersion + "]";
    }
}
