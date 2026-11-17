package org.xander.edt.storagebridge.platform;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * One-time full XML export ONLY for initial comparison. No metadata list and
 * no fallback from a failed incremental receive. Does not mutate the IB.
 */
public record InitialFullDumpRequest(
        Path executable, InfobaseLocation infobase, Duration timeout,
        String infobaseUsername, String infobasePassword)
        implements XmlDumpConnection {
    public InitialFullDumpRequest {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(infobase, "infobase");
        Objects.requireNonNull(timeout, "timeout");
        DesignerInfobaseAuth.validate(infobaseUsername, infobasePassword);
        if (!executable.isAbsolute()) {
            throw new IllegalArgumentException("Executable path must be absolute");
        }
        String name = Objects.requireNonNull(executable.getFileName()).toString();
        if (!(name.equalsIgnoreCase("1cv8.exe")
                || name.equalsIgnoreCase("1cv8c.exe")
                || name.equals("1cv8") || name.equals("1cv8c"))) {
            throw new IllegalArgumentException("Expected 1cv8 platform executable");
        }
        if (timeout.compareTo(Duration.ofSeconds(10)) < 0
                || timeout.compareTo(Duration.ofHours(2)) > 0) {
            throw new IllegalArgumentException("Invalid initial dump timeout");
        }
    }

    @Override
    public String toString() {
        return "InitialFullDumpRequest[credentials=REDACTED]";
    }
}
