package org.xander.edt.storagebridge.platform;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Mutating, explicitly user-approved receive into a pre-existing connected
 * 1C infobase. Repository credentials are optional and must never be logged.\n * The native Designer command line is visible to privileged local OS users;\n * the caller must warn the user before passing a repository password.
 */
public record RepositoryUpdateRequest(Path executable, InfobaseLocation infobase,
        Duration timeout, String repositoryAddress, String repositoryUsername,
        String repositoryPassword) {

    /** No explicit repository authentication (e.g. bound base uses saved auth). */
    public RepositoryUpdateRequest(Path executable, InfobaseLocation infobase,
            Duration timeout) {
        this(executable, infobase, timeout, "", "", "");
    }

    public RepositoryUpdateRequest {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(infobase, "infobase");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(repositoryAddress, "repositoryAddress");
        Objects.requireNonNull(repositoryUsername, "repositoryUsername");
        Objects.requireNonNull(repositoryPassword, "repositoryPassword");
        if ((repositoryAddress.isBlank() && !repositoryUsername.isBlank())
                || (repositoryUsername.isBlank() && !repositoryPassword.isEmpty())) {
            throw new IllegalArgumentException(
                "Repository credentials require repository path and username");
        }
        if (repositoryAddress.indexOf('\n') >= 0
                || repositoryUsername.indexOf('\n') >= 0
                || repositoryPassword.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Multiline credentials are unsupported");
        }
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
