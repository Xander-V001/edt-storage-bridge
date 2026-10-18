package org.xander.edt.storagebridge.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Read-only EDT file-level candidate diff against a separately PROVEN
 * repository-content baseline.
 *
 * This scanner deliberately does NOT choose storage locks, XML -listFile
 * entries or commit objects. File differences are candidate changes, not
 * proof that native EDT/1C models differ semantically. The baseline must be
 * supplied by a future trusted snapshot/reconstruction stage; Git HEAD~1
 * is NOT such a baseline.
 */
public final class OutgoingDeltaScanner {
    private static final int MAX_FILES = 500_000;

    public enum Kind { ADDED, MODIFIED, DELETED }

    public record Change(Path relativePath, String edtOwner, Kind kind) {
        public Change {
            if (relativePath == null || relativePath.isAbsolute()
                    || relativePath.normalize().startsWith("..")
                    || edtOwner == null || edtOwner.isBlank()
                    || kind == null) {
                throw new IllegalArgumentException("Unsafe EDT candidate change");
            }
        }
    }

    public record Delta(List<Change> files, Set<String> affectedEdtOwners) {
        public Delta {
            files = List.copyOf(files);
            affectedEdtOwners = Set.copyOf(affectedEdtOwners);
        }
    }

    private OutgoingDeltaScanner() {}

    public static Delta compare(Path verifiedBaseline, Path activeProject)
            throws IOException {
        Path baseline = verifiedBaseline.toRealPath();
        Path active = activeProject.toRealPath();
        if (baseline.startsWith(active) || active.startsWith(baseline)) {
            throw new IOException("Overlapping outbound source and baseline");
        }
        var before = enumerateFiles(baseline);
        var after = enumerateFiles(active);
        var names = new LinkedHashSet<Path>(before.keySet());
        names.addAll(after.keySet());
        if (names.size() > MAX_FILES) {
            throw new IOException("EDT file inventory exceeds safe maximum");
        }
        var changes = new ArrayList<Change>();
        var affected = new LinkedHashSet<String>();
        for (Path name : names) {
            Path original = before.get(name);
            Path current = after.get(name);
            final Kind kind;
            if (original == null) {
                kind = Kind.ADDED;
            } else if (current == null) {
                kind = Kind.DELETED;
            } else if (Files.size(original) != Files.size(current)
                    || Files.mismatch(original, current) != -1L) {
                kind = Kind.MODIFIED;
            } else {
                continue;
            }
            String owner = EdtPathObjectMapper.topObject(name);
            if (owner == null) {
                // An unknown file must not simply be silently skipped from
                // the outgoing list. Fail closed until its semantics are known.
                throw new IOException("Unclassifiable outgoing EDT resource");
            }
            changes.add(new Change(name, owner, kind));
            affected.add(owner);
        }
        return new Delta(changes, affected);
    }

    private static Map<Path, Path> enumerateFiles(Path edtRoot)
            throws IOException {
        Path src = edtRoot.resolve("src");
        if (!Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(src)) {
            throw new IOException("Missing or unsafe EDT src tree");
        }
        var files = new LinkedHashMap<Path, Path>();
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path absolute : walk.toList()) {
                if (Files.isSymbolicLink(absolute)) {
                    throw new IOException("Symbolic link in outbound EDT source");
                }
                if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Non-regular EDT source entry");
                    }
                    continue;
                }
                Path relative = edtRoot.relativize(absolute);
                // Check ALL file names (not only modified ones) for safe,
                // stable classification between baseline and active project.
                if (EdtPathObjectMapper.topObject(relative) == null) {
                    throw new IOException("Unknown EDT source file layout");
                }
                if (files.put(relative, absolute) != null) {
                    throw new IOException("Ambiguous EDT resource path");
                }
                if (files.size() > MAX_FILES) {
                    throw new IOException("EDT file inventory exceeds safe maximum");
                }
            }
        }
        return files;
    }
}
