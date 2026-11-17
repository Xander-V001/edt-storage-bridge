package org.xander.edt.storagebridge.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Maps committed Git source changes to EDT top-level owner CANDIDATES.
 * Received/merged remote content must still be excluded before publication.
 * The scanner NEVER authorizes a repository write.
 */
public final class OutgoingGitCandidateScanner {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CHANGES = 100_000;

    public enum Kind { ADDED, MODIFIED, DELETED }
    public record ChangedFile(String path, String edtOwner, Kind kind) {}
    public record Inventory(List<ChangedFile> files,
            Map<String, List<ChangedFile>> ownerChanges) {
        public Inventory {
            files = List.copyOf(files);
            var copy = new LinkedHashMap<String, List<ChangedFile>>();
            ownerChanges.forEach((owner, values) ->
                copy.put(owner, List.copyOf(values)));
            ownerChanges = java.util.Collections.unmodifiableMap(copy);
        }
    }

    private OutgoingGitCandidateScanner() {}

    /** Parse git diff --name-status --no-renames -z BASE HEAD -- src. */
    public static Inventory parse(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes);
        if (bytes.length > MAX_BYTES)
            throw new IOException("Git outgoing inventory too large");
        var changed = new ArrayList<ChangedFile>();
        var owners = new LinkedHashMap<String, List<ChangedFile>>();
        int pos = 0;
        while (pos < bytes.length) {
            int statusEnd = nul(bytes, pos);
            String status = new String(bytes, pos, statusEnd - pos,
                StandardCharsets.US_ASCII);
            pos = statusEnd + 1;
            if (pos >= bytes.length)
                throw new IOException("Incomplete Git change record");
            int pathEnd = nul(bytes, pos);
            String text = new String(bytes, pos, pathEnd - pos,
                StandardCharsets.UTF_8);
            pos = pathEnd + 1;
            Kind kind = switch (status) {
                case "A" -> Kind.ADDED;
                case "M", "T" -> Kind.MODIFIED;
                case "D" -> Kind.DELETED;
                default -> throw new IOException("Unsupported Git diff status");
            };
            if (text.isBlank() || text.startsWith("/")
                    || text.contains("//") || text.indexOf('\\') >= 0
                    || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)
                throw new IOException("Unsafe outgoing Git path");
            final Path path;
            try {
                path = Path.of(text);
            } catch (RuntimeException ex) {
                throw new IOException("Invalid Git path", ex);
            }
            if (path.isAbsolute() || path.getNameCount() < 3
                    || !path.normalize().equals(path)
                    || !"src".equals(path.getName(0).toString()))
                throw new IOException("Git change is not a valid EDT source");
            String owner = EdtPathObjectMapper.topObject(path);
            if (owner == null)
                throw new IOException("Cannot map changed path to EDT metadata");
            var entry = new ChangedFile(text, owner, kind);
            changed.add(entry);
            owners.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(entry);
            if (changed.size() > MAX_CHANGES)
                throw new IOException("Too many changed EDT resources");
        }
        return new Inventory(changed, owners);
    }

    private static int nul(byte[] bytes, int start) throws IOException {
        for (int i = start; i < bytes.length; i++)
            if (bytes[i] == 0) return i;
        throw new IOException("Malformed NUL-delimited Git change record");
    }
}
