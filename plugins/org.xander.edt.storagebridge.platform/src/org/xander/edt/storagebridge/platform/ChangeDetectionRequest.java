package org.xander.edt.storagebridge.platform;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Probe configuration changes before an object list exists.
 * No mutation, EDT project or Git checkout is involved.
 */
public record ChangeDetectionRequest(Path executable, InfobaseLocation infobase,
        Duration timeout, String infobaseUsername, String infobasePassword) {
    public ChangeDetectionRequest(Path executable, InfobaseLocation infobase,
            Duration timeout) {
        this(executable, infobase, timeout, "", "");
    }

    public ChangeDetectionRequest {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(infobase, "infobase");
        Objects.requireNonNull(timeout, "timeout");
        DesignerInfobaseAuth.validate(infobaseUsername, infobasePassword);
        if (!executable.isAbsolute()) {
            throw new IllegalArgumentException("Absolute executable required");
        }
        String exeName = Objects.requireNonNull(executable.getFileName()).toString();
        if (!exeName.equalsIgnoreCase("1cv8.exe")
                && !exeName.equalsIgnoreCase("1cv8c.exe")
                && !exeName.equals("1cv8") && !exeName.equals("1cv8c")) {
            throw new IllegalArgumentException("Expected 1C Designer executable");
        }
        if (timeout.compareTo(Duration.ofSeconds(10)) < 0
                || timeout.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("Invalid timeout");
        }
    }

    @Override
    public String toString() {
        return "ChangeDetectionRequest[infobase=REDACTED]";
    }
}
