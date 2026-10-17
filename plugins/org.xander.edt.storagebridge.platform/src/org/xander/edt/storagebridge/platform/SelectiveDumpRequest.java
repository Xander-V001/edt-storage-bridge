package org.xander.edt.storagebridge.platform;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Explicit set of changed metadata objects from the existing storage-bound IB.
 * This request cannot mean "export entire configuration".
 */
public record SelectiveDumpRequest(
        Path executable, InfobaseLocation infobase,
        List<String> objectNames, Duration timeout,
        String infobaseUsername, String infobasePassword)
        implements XmlDumpConnection {

    public SelectiveDumpRequest(Path executable, InfobaseLocation infobase,
            List<String> objectNames, Duration timeout) {
        this(executable, infobase, objectNames, timeout, "", "");
    }

    public SelectiveDumpRequest {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(infobase, "infobase");
        Objects.requireNonNull(objectNames, "objectNames");
        Objects.requireNonNull(timeout, "timeout");
        DesignerInfobaseAuth.validate(infobaseUsername, infobasePassword);
        if (!executable.isAbsolute()) {
            throw new IllegalArgumentException("Executable path must be absolute");
        }
        String name = Objects.requireNonNull(executable.getFileName()).toString();
        if (!(name.equalsIgnoreCase("1cv8.exe") || name.equalsIgnoreCase("1cv8c.exe")
                || name.equals("1cv8") || name.equals("1cv8c"))) {
            throw new IllegalArgumentException("Expected 1cv8 platform executable");
        }
        objectNames = List.copyOf(objectNames);
        if (objectNames.isEmpty() || objectNames.size() > 100_000) {
            throw new IllegalArgumentException("Explicit changed object list required");
        }
        for (String nameOfObject : objectNames) {
            if (nameOfObject == null || nameOfObject.length() > 1024
                    || "Configuration".equals(nameOfObject)
                    || "Language".equals(nameOfObject)
                    || nameOfObject.isBlank()
                    || (!nameOfObject.contains(".")
                        && !"Configuration".equals(nameOfObject)
                        && !"Language".equals(nameOfObject))
                    || !nameOfObject.matches("[\\p{L}\\p{N}_.]+")) {
                throw new IllegalArgumentException("Invalid 1C metadata object name");
            }
        }
        if (objectNames.stream().distinct().count() != objectNames.size()) {
            throw new IllegalArgumentException("Duplicate objects are not accepted");
        }
        if (timeout.compareTo(Duration.ofSeconds(10)) < 0
                || timeout.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalArgumentException("Invalid timeout");
        }
    }

    @Override
    public String toString() {
        return "SelectiveDumpRequest[infobase=REDACTED, objects="
            + objectNames.size() + "]";
    }
}
