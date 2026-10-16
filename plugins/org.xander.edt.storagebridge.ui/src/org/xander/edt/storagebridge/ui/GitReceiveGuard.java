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

    public static void requireCleanBranch(IProject project)
            throws IOException, InterruptedException {
        if (project.getLocation() == null) {
            throw new IOException("У проекта отсутствует локальный каталог.");
        }
        Path root = Path.of(project.getLocation().toOSString());
        if (!"true".equals(call(root, "rev-parse", "--is-inside-work-tree").trim())) {
            throw new IOException("Проект не находится под управлением локального Git.");
        }
        if (call(root, "branch", "--show-current").isBlank()) {
            throw new IOException("Git находится в detached HEAD; выберите ветку задачи.");
        }
        if (!call(root, "status", "--porcelain=v1", "--untracked-files=normal").isBlank()) {
            throw new IOException("В Git есть незакоммиченные файлы. "
                + "Сначала сохраните изменения и создайте коммит.");
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
