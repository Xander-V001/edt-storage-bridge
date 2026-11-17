package org.xander.edt.storagebridge.platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Allowlisted native Designer arguments for controlled TEST repository writes.
 * -Objects XML for Lock/Commit is NOT the UTF-8 paths -listFile for Load.
 * Every operation explicitly names its finite scope; never -force/-NoCheck.
 * Returned argv contains credentials: NEVER log or print it.
 */
public final class OutgoingDesignerCommands {
    public enum Stage { LOCK, LOAD, COMMIT }
    public record Connection(Path executable, InfobaseLocation infobase,
            String infobaseUsername, String infobasePassword,
            String repositoryAddress, String repositoryUsername,
            String repositoryPassword) {
        public Connection {
            Objects.requireNonNull(executable);
            Objects.requireNonNull(infobase);
            DesignerInfobaseAuth.validate(infobaseUsername, infobasePassword);
            Objects.requireNonNull(repositoryAddress);
            Objects.requireNonNull(repositoryUsername);
            Objects.requireNonNull(repositoryPassword);
            if (!executable.isAbsolute() || repositoryAddress.isBlank()
                    || repositoryUsername.isBlank()
                    || repositoryAddress.contains("\n")
                    || repositoryAddress.contains("\r")
                    || repositoryUsername.contains("\n")
                    || repositoryUsername.contains("\r")
                    || repositoryPassword.contains("\n")
                    || repositoryPassword.contains("\r")) {
                throw new IllegalArgumentException("Unidentified repository or unsafe auth");
            }
        }
        @Override public String toString() {
            return "OutgoingDesignerConnection[credentials=REDACTED]";
        }
    }

    private OutgoingDesignerCommands() {}

    /**
     * Append a per-project tag to the Git commit subject. Preserve the
     * remaining Git message (blank separator and body) unchanged.
     */
    public static String withAdditionalComment(String gitMessage, String suffix) {
        Objects.requireNonNull(gitMessage, "gitMessage");
        Objects.requireNonNull(suffix, "suffix");
        if (gitMessage.isBlank()) {
            throw new IllegalArgumentException("Git commit description is empty");
        }
        String additional = suffix.trim();
        if (additional.isEmpty()) return gitMessage;
        if (additional.indexOf('\n') >= 0
                || additional.indexOf('\r') >= 0
                || additional.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                "Additional repository comment must be a single line");
        }
        int separator = gitMessage.indexOf('\n');
        String subject = separator < 0
            ? gitMessage : gitMessage.substring(0, separator);
        if (subject.isBlank()) {
            throw new IllegalArgumentException("Git commit subject is empty");
        }
        String body = separator < 0 ? "" : gitMessage.substring(separator);
        return subject.stripTrailing() + " " + additional + body;
    }

    public static List<String> arguments(Connection c, Stage stage,
            Path selectedObjectsXml, Path exportRoot, Path selectedFilesList,
            Path outputLog, String comment) {
        Objects.requireNonNull(c);
        Objects.requireNonNull(stage);
        requirePath(selectedObjectsXml);
        requirePath(outputLog);
        if (stage == Stage.LOAD) {
            requirePath(exportRoot);
            requirePath(selectedFilesList);
        }
        if (stage == Stage.COMMIT && (comment == null || comment.isBlank()
                || comment.indexOf('\0') >= 0)) {
            throw new IllegalArgumentException("Nonempty safe commit comment required");
        }
        var cmd = new ArrayList<String>();
        cmd.add(c.executable().toString());
        cmd.add("DESIGNER");
        cmd.add(c.infobase().switchName());
        cmd.add(c.infobase().address());
        DesignerInfobaseAuth.addTo(cmd, c.infobaseUsername(), c.infobasePassword());
        cmd.add("/DisableStartupDialogs");
        cmd.add("/DisableStartupMessages");
        cmd.add("/DisableUnrecoverableErrorMessage");
        cmd.add("/ConfigurationRepositoryF");
        cmd.add(c.repositoryAddress());
        cmd.add("/ConfigurationRepositoryN");
        cmd.add(c.repositoryUsername());
        if (!c.repositoryPassword().isEmpty()) {
            cmd.add("/ConfigurationRepositoryP");
            cmd.add(c.repositoryPassword());
        }
        switch (stage) {
            case LOCK -> {
                cmd.add("/ConfigurationRepositoryLock");
                cmd.add("-Objects");
                cmd.add(selectedObjectsXml.toString());
            }
            case LOAD -> {
                cmd.add("/LoadConfigFromFiles");
                cmd.add(exportRoot.toString());
                cmd.add("-Format");
                cmd.add("Hierarchical");
                cmd.add("-listFile");
                cmd.add(selectedFilesList.toString());
                }
            case COMMIT -> {
                cmd.add("/ConfigurationRepositoryCommit");
                cmd.add("-Objects");
                cmd.add(selectedObjectsXml.toString());
                // Official 1C Designer supports multiple -comment arguments:
                // one per line, preserving the Git HEAD commit description.
                // ProcessBuilder passes each argument directly (no shell).
                for (String line : comment.stripTrailing().lines().toList()) {
                    cmd.add("-comment");
                    cmd.add(line);
                }
            }
        }
        cmd.add("/Out");
        cmd.add(outputLog.toString());
        return List.copyOf(cmd);
    }

    private static void requirePath(Path path) {
        if (path == null || !path.isAbsolute()) {
            throw new IllegalArgumentException("Absolute path is required");
        }
    }
}
