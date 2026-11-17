package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.xander.edt.storagebridge.core.OutgoingGitCandidateScanner;

/**
 * Cumulative Git diff from confirmed successful-publication SHA to HEAD.
 * Never selects one last commit, checks out branches or modifies local Git.
 */
public final class OutgoingGitDiffReader {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private OutgoingGitDiffReader() {}

    public static OutgoingGitCandidateScanner.Inventory inspect(Path project,
            String publishedHead, String expectedHead, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        String base = sha(publishedHead);
        String head = sha(expectedHead);
        Path root = project.toRealPath();
        String gitRoot = new String(run(root, cancelled,
            "rev-parse", "--show-toplevel"),
            java.nio.charset.StandardCharsets.UTF_8).trim();
        if (!root.equals(Path.of(gitRoot).toRealPath()))
            throw new IOException("EDT project must be Git repository root");
        ensureHead(root, head, cancelled);
        run(root, cancelled, "merge-base", "--is-ancestor", base, head);
        var data = run(root, cancelled, "diff", "--name-status",
            "--no-ext-diff", "--no-renames", "-z", base, head, "--", "src");
        var result = OutgoingGitCandidateScanner.parse(data);
        ensureHead(root, head, cancelled);
        return result;
    }

    private static void ensureHead(Path root, String head,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        String actual = new String(run(root, cancelled,
            "rev-parse", "HEAD"), java.nio.charset.StandardCharsets.UTF_8).trim();
        if (!head.equalsIgnoreCase(actual))
            throw new IOException("Git HEAD changed during outgoing preparation");
    }

    private static String sha(String text) throws IOException {
        if (text == null || !text.matches("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}"))
            throw new IOException("No valid confirmed Git SHA");
        return text;
    }

    private static byte[] run(Path root, BooleanSupplier cancelled,
            String... args) throws IOException, InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        Path output = Files.createTempFile("edt-storage-git-outgoing-", ".bin");
        Process process = null;
        try {
            var cmd = new ArrayList<String>();
            cmd.add("git");
            cmd.add("-C");
            cmd.add(root.toString());
            java.util.Collections.addAll(cmd, args);
            process = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)
                    || cancelled.getAsBoolean()) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                throw new IOException("Git inventory timeout or cancellation");
            }
            if (process.exitValue() != 0)
                throw new IOException("Git ancestry or diff verification failed");
            if (Files.size(output) > MAX_BYTES)
                throw new IOException("Git outgoing change list too large");
            return Files.readAllBytes(output);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }
}
