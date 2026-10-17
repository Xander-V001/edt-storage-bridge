package org.xander.edt.storagebridge.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Fail-closed verification after native EDT merge, never before it.
 * Never infer success from a checked checkbox or a clean Git worktree.
 */
public final class EdtOwnerContentVerifier {
    private EdtOwnerContentVerifier() {}

    public static boolean isExactlyApplied(
            Path projectRoot, Path stage, String edtOwner) throws IOException {
        if (edtOwner == null || !edtOwner.matches("[^/\\\\]+/[^/\\\\]+")
                || edtOwner.contains("..")) {
            throw new IOException("Invalid metadata owner path");
        }
        Path project = projectRoot.toRealPath();
        Path incoming = stage.toRealPath();
        if (project.startsWith(incoming) || incoming.startsWith(project)) {
            throw new IOException("Cannot verify content against overlapping trees");
        }

        // Configuration's metadata file is src/Configuration/Configuration.mdo,
        // but its modules and other root resources are sibling files under
        // src/Configuration/. Verify ALL of them in both directions, otherwise
        // root changes could be incorrectly marked fully applied.
        if ("Configuration/Configuration".equals(edtOwner)) {
            Path left = incoming.resolve("src/Configuration");
            Path right = project.resolve("src/Configuration");
            return Files.isDirectory(left, LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(right, LinkOption.NOFOLLOW_LINKS)
                && equalTrees(left, right);
        }

        Path stageDir = incoming.resolve("src").resolve(edtOwner);
        Path projectDir = project.resolve("src").resolve(edtOwner);
        boolean hasStageDir = Files.isDirectory(stageDir, LinkOption.NOFOLLOW_LINKS);
        boolean hasProjectDir = Files.isDirectory(projectDir, LinkOption.NOFOLLOW_LINKS);
        if (hasStageDir != hasProjectDir) {
            return false;
        }

        boolean sawData = false;
        if (hasStageDir) {
            if (!equalTrees(stageDir, projectDir)) {
                return false;
            }
            try (Stream<Path> paths = Files.walk(stageDir)) {
                sawData = paths.anyMatch(p -> Files.isRegularFile(
                    p, LinkOption.NOFOLLOW_LINKS));
            }
        }

        Path stageMdo = incoming.resolve("src").resolve(edtOwner + ".mdo");
        Path projectMdo = project.resolve("src").resolve(edtOwner + ".mdo");
        boolean stageFlat = Files.isRegularFile(stageMdo, LinkOption.NOFOLLOW_LINKS);
        boolean projectFlat = Files.isRegularFile(projectMdo, LinkOption.NOFOLLOW_LINKS);
        if (stageFlat != projectFlat) {
            return false;
        }
        if (stageFlat) {
            sawData = true;
            if (!equalFile(stageMdo, projectMdo)) {
                return false;
            }
        }
        return sawData;
    }

    private static boolean equalTrees(Path left, Path right) throws IOException {
        try (Stream<Path> leftTree = Files.walk(left);
             Stream<Path> rightTree = Files.walk(right)) {
            Set<Path> a = new LinkedHashSet<>();
            Set<Path> b = new LinkedHashSet<>();
            for (Path p : leftTree.toList()) {
                if (Files.isSymbolicLink(p)) return false;
                a.add(left.relativize(p));
            }
            for (Path p : rightTree.toList()) {
                if (Files.isSymbolicLink(p)) return false;
                b.add(right.relativize(p));
            }
            if (!a.equals(b)) return false;
            for (Path rel : a) {
                Path first = left.resolve(rel), second = right.resolve(rel);
                boolean file = Files.isRegularFile(first, LinkOption.NOFOLLOW_LINKS);
                if (file != Files.isRegularFile(second, LinkOption.NOFOLLOW_LINKS)) {
                    return false;
                }
                if (file) {
                    if (!equalFile(first, second)) return false;
                } else if (!Files.isDirectory(first, LinkOption.NOFOLLOW_LINKS)
                        || !Files.isDirectory(second, LinkOption.NOFOLLOW_LINKS)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static boolean equalFile(Path left, Path right) throws IOException {
        return !Files.isSymbolicLink(left) && !Files.isSymbolicLink(right)
            && Files.size(left) == Files.size(right)
            && Files.mismatch(left, right) == -1L;
    }
}
