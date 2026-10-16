package org.xander.edt.storagebridge.platform;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Mutating, explicitly user-approved receive into a pre-existing connected
 * 1C infobase. This request never contains user credentials or shell options.
 */
public record RepositoryUpdateRequest(Path executable, InfobaseLocation infobase,
        Duration timeout) {

    public RepositoryUpdateRequest {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(infobase, "infobase");
        Objects.requireNonNull(timeout, "timeout");
        if (!executable.isAbsolute()) {
            throw new IllegalArgumentException("1C executable must have an absolute path");
        }
        String fileName = Objects.requireNonNull(executable.getFileName()).toString();
        if (!fileName.equalsIgnoreCase("1cv8.exe") && !fileName.equalsIgnoreCase("1cv8c.exe")
            && !fileName.equals("1cv8") && !fileName.equals("1cv8c")) {
            throw new IllegalArgumentException("Expected installed 1C Designer executable");
        }
        if (timeout.compareTo(Duration.ofSeconds(10)) < 0
                || timeout.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("Timeout out of supported range");
        }
    }

    @Override
    public String toString() {
        return "RepositoryUpdateRequest[connection=REDACTED, timeout=" + timeout + "]";
    }
}
