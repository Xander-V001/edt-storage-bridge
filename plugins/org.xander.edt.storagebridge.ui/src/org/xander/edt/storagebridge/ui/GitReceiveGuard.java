package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;

/** Check an EDT task branch without changing Git repository state. */
public final class GitReceiveGuard {

    private GitReceiveGuard() {
    }

    public record Baseline(String branch, String head) {}

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
        if (!before.branch().equals(branch) || !before.head().equals(head)) {
            throw new IOException("Git branch or HEAD changed during EDT merge;"
                + " pending remote objects are preserved.");
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
