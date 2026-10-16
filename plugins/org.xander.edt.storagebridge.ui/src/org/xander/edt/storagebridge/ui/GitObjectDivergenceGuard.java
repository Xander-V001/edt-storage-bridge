package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.eclipse.core.resources.IProject;
import org.xander.edt.storagebridge.core.EdtPathObjectMapper;

/**
 * Inspects ALL locally committed changes since a VERIFIED Git baseline,
 * not merely the Git working tree. Never changes Git state.
 */
public final class GitObjectDivergenceGuard {
    public record Result(Set<String> modifiedTopObjects, Set<String> unknownFiles) {
        public Result {
            modifiedTopObjects = Set.copyOf(modifiedTopObjects);
            unknownFiles = Set.copyOf(unknownFiles);
        }
        public boolean allowsUnattendedObjectMerge(String edtTopObject) {
            return unknownFiles.isEmpty()
                && !modifiedTopObjects.contains(edtTopObject)
                && !modifiedTopObjects.contains("Configuration");
        }
    }

    private GitObjectDivergenceGuard() {
    }

    public static Result changesSince(IProject project, String verifiedBaselineSha)
            throws IOException, InterruptedException {
        if (project == null || project.getLocation() == null
                || verifiedBaselineSha == null
                || !verifiedBaselineSha.matches("[a-fA-F0-9]{40}")) {
            throw new IOException("Verified Git baseline is required");
        }
        Path projectPath = Path.of(project.getLocation().toOSString()).toRealPath();
        // --relative makes paths relative to this EDT project even when the
        // Git repository root is above the project directory.
        byte[] names = gitDiff(projectPath, verifiedBaselineSha);
        Set<String> objects = new LinkedHashSet<>();
        Set<String> unknown = new LinkedHashSet<>();
        int start = 0;
        for (int i = 0; i < names.length; i++) {
            if (names[i] != 0) {
                continue;
            }
            if (i > start) {
                String name = new String(names, start, i - start,
                    java.nio.charset.StandardCharsets.UTF_8);
                Path relative = Path.of(name);
                String object = EdtPathObjectMapper.topObject(relative);
                if (object == null) {
                    unknown.add(name);
                } else {
                    objects.add(object);
                }
            }
            start = i + 1;
        }
        if (start != names.length) {
            throw new IOException("Truncated NUL-delimited Git diff");
        }
        return new Result(objects, unknown);
    }

    private static byte[] gitDiff(Path project, String sha)
            throws IOException, InterruptedException {
        Path temporary = Files.createTempFile("edt-storage-diff-", ".bin");
        try {
            var args = new ArrayList<String>();
            java.util.Collections.addAll(args, "git", "-C", project.toString(),
                "diff", "--relative", "--no-ext-diff", "--no-renames",
                "--name-only", "-z", sha, "HEAD", "--", ".");
            Process process = new ProcessBuilder(args)
                .redirectErrorStream(true)
                .redirectOutput(temporary.toFile())
                .start();
            try {
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                    throw new IOException("Git diff exceeded timeout");
                }
                if (process.exitValue() != 0 || Files.size(temporary) > 16L * 1024 * 1024) {
                    throw new IOException("Git baseline comparison failed");
                }
                return Files.readAllBytes(temporary);
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
