package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.xander.edt.storagebridge.core.ReceiveGitStatePolicy;

/** Check an EDT task branch without changing Git repository state. */
public final class GitReceiveGuard {

    private GitReceiveGuard() {
    }

    public record Baseline(String branch, String head) {}

    /** Vetted Git-only text, safe to show without exposing repository paths. */
    public static final class PostMergeGitMismatch extends IOException {
        private static final long serialVersionUID = 1L;

        public PostMergeGitMismatch(String message) {
            super(message);
        }
    }

    /**
     * EDT may change files, and a developer may commit during a long native
     * merge. Preserve the same task BRANCH and require the original HEAD to
     * remain in the new commit's ancestry. A reset/rebase or branch switch
     * is an actual safety conflict. This check NEVER proves EDT acceptance.
     */
    public static Baseline requireSafePostMergeGit(IProject project,
            Baseline before) throws IOException, InterruptedException {
        if (before == null || project == null || project.getLocation() == null) {
            throw new PostMergeGitMismatch(
                "Не удалось подтвердить исходную ветку Git проекта EDT.");
        }
        Path root = Path.of(project.getLocation().toOSString());
        String branch = call(root, "branch", "--show-current").trim();
        String head = call(root, "rev-parse", "HEAD").trim();
        boolean ancestor = false;
        if (before.branch().equals(branch)
                && !before.head().equalsIgnoreCase(head)
                && head.matches("(?i)[0-9a-f]{40}([0-9a-f]{24})?")) {
            ancestor = isAncestor(root, before.head(), head);
        }
        final ReceiveGitStatePolicy.Result decision;
        try {
            decision = ReceiveGitStatePolicy.assess(
                before.branch(), before.head(), branch, head, ancestor);
        } catch (IllegalArgumentException ex) {
            throw new PostMergeGitMismatch(
                "Git не вернул достоверную ветку и HEAD после Merge EDT.");
        }
        switch (decision) {
            case SWITCHED_BRANCH -> throw new PostMergeGitMismatch(
                "Во время Merge EDT изменилась ветка Git. "
                + "Результат нельзя записывать в журнал другой ветки.");
            case REWRITTEN_HISTORY -> throw new PostMergeGitMismatch(
                "Во время Merge EDT история Git была переписана "
                + "(reset, rebase либо непродолжение прежнего HEAD). "
                + "Результат не подтверждён.");
            case UNCHANGED, ADVANCED_ON_SAME_BRANCH -> { }
        }
        return new Baseline(branch, head);
    }

    /** Branch lookup only; never require a clean working copy to show status. */
    public static String currentBranch(IProject project)
            throws IOException, InterruptedException {
        if (project == null || project.getLocation() == null) {
            throw new IOException("Project directory not available");
        }
        String branch = call(Path.of(project.getLocation().toOSString()),
            "branch", "--show-current").trim();
        if (branch.isBlank()) {
            throw new IOException("Git branch not available");
        }
        return branch;
    }

    public static Baseline requireCleanBranch(IProject project)
            throws IOException, InterruptedException {
        if (project.getLocation() == null) {
            throw new IOException("У проекта отсутствует локальный каталог.");
        }
        Path root = Path.of(project.getLocation().toOSString());
        if (!"true".equals(call(root, "rev-parse", "--is-inside-work-tree").trim())) {
            throw new IOException("Проект не находится под управлением локального Git.");
        }
        String branch = call(root, "branch", "--show-current").trim();
        if (branch.isBlank()) {
            throw new IOException("Git находится в detached HEAD; выберите ветку задачи.");
        }
        String head = call(root, "rev-parse", "HEAD").trim();
        if (!head.matches("[a-fA-F0-9]{40,64}")) {
            throw new IOException("Не удалось определить HEAD проекта.");
        }
        if (!call(root, "status", "--porcelain=v1", "--untracked-files=normal").isBlank()) {
            throw new IOException("В Git есть незакоммиченные файлы. "
                + "Сначала сохраните изменения и создайте коммит.");
        }
        return new Baseline(branch, head);
    }

    /**
     * Verify branch and HEAD after the native EDT merge. The worktree is
     * intentionally NOT required to be clean: an accepted merge modifies it.
     * A branch switch during interactive compare must not acknowledge objects
     * against an unrelated task branch's pending journal.
     */
    public static void requireSameBranchAndHead(IProject project, Baseline before)
            throws IOException, InterruptedException {
        if (before == null || project == null || project.getLocation() == null) {
            throw new IOException("Git receive identity is missing");
        }
        Path root = Path.of(project.getLocation().toOSString());
        String branch = call(root, "branch", "--show-current").trim();
        String head = call(root, "rev-parse", "HEAD").trim();
        if (!before.branch().equals(branch)) {
            throw new PostMergeGitMismatch(
                "Ветка Git сменилась после проверки результата EDT. "
                + "Принятие изменений не подтверждено.");
        }
        if (!before.head().equalsIgnoreCase(head)) {
            throw new PostMergeGitMismatch(
                "Git HEAD изменился во время проверки результата EDT. "
                + "Сведения о принятии изменений не записаны.");
        }
    }

    /**
     * Diagnostic only: count Git-tracked/untracked changes left by EDT merge
     * since the clean preflight. No filenames or source code are logged.
     * A successful native merge with zero changes is not proof of failure
     * (e.g. merge chose local values), but it explains why a text difference
     * could remain at the next comparison.
     */
    public static int workingTreeChangeCount(IProject project)
            throws IOException, InterruptedException {
        if (project == null || project.getLocation() == null) {
            throw new IOException("EDT worktree unavailable");
        }
        String porcelain = call(Path.of(project.getLocation().toOSString()),
            "status", "--porcelain=v1", "--untracked-files=normal");
        if (porcelain.isBlank()) return 0;
        return (int) porcelain.lines().filter(x -> !x.isBlank()).count();
    }

    private static boolean isAncestor(Path root, String ancestor, String descendant)
            throws IOException, InterruptedException {
        Path output = Files.createTempFile("edt-storage-git-ancestry-", ".txt");
        try {
            Process process = new ProcessBuilder("git", "-C", root.toString(),
                    "merge-base", "--is-ancestor", ancestor, descendant)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                    throw new IOException("Истекло время проверки истории Git.");
                }
                return switch (process.exitValue()) {
                    case 0 -> true;
                    case 1 -> false;
                    default -> throw new IOException(
                        "Не удалось проверить непрерывность истории Git.");
                };
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }

    public static void requireUnchanged(IProject project, Baseline before)
            throws IOException, InterruptedException {
        Baseline after = requireCleanBranch(project);
        if (!before.equals(after)) {
            throw new IOException("В ходе обновления изменилась ветка или HEAD Git. "
                + "Импорт EDT остановлен.");
        }
    }

    private static String call(Path project, String... arguments)
            throws IOException, InterruptedException {
        Path output = Files.createTempFile("edt-storage-git-", ".txt");
        try {
            java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
            cmd.add("git");
            cmd.add("-C");
            cmd.add(project.toString());
            java.util.Collections.addAll(cmd, arguments);
            Process process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                    throw new IOException("Истекло время проверки Git.");
                }
                if (process.exitValue() != 0) {
                    throw new IOException("Не удалось проверить Git. "
                        + "Убедитесь, что Git установлен и каталог проекта верный.");
                }
                if (Files.size(output) > 1024 * 1024) {
                    throw new IOException("Слишком большой вывод проверки Git.");
                }
                return Files.readString(output, StandardCharsets.UTF_8);
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
