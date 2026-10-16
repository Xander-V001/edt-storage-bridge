package org.xander.edt.storagebridge.platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Documented 1C batch switches. No shell, binding, commit or update operations. */
public final class StorageSnapshotCommands {
    private StorageSnapshotCommands() {
    }

    public static List<String> createBase(StorageSnapshotRequest request,
            Path newBase, Path serviceLog) {
        requireAbsolute(newBase, serviceLog);
        return List.of(request.executable().toString(), "CREATEINFOBASE",
            "File=\"" + newBase + "\"", "/Out", serviceLog.toString());
    }

    public static List<String> dumpCf(StorageSnapshotRequest request,
            Path newBase, Path cf, Path serviceLog) {
        requireAbsolute(newBase, cf, serviceLog);
        List<String> cmd = designer(request, newBase);
        cmd.add("/ConfigurationRepositoryF");
        cmd.add(request.storageAddress());
        cmd.add("/ConfigurationRepositoryN");
        cmd.add(request.storageUser());
        // The platform's documented repository authentication requires argv.
        // Never log the command; warning and consent belong to the UI.
        if (!request.storagePassword().isEmpty()) {
            cmd.add("/ConfigurationRepositoryP");
            cmd.add(request.storagePassword());
        }
        cmd.add("/ConfigurationRepositoryDumpCfg");
        cmd.add(cf.toString());
        if (request.revision() != null) {
            cmd.add("-v");
            cmd.add(Long.toString(request.revision()));
        }
        cmd.add("/Out");
        cmd.add(serviceLog.toString());
        return List.copyOf(cmd);
    }

    public static List<String> loadCf(StorageSnapshotRequest request,
            Path newBase, Path cf, Path serviceLog) {
        requireAbsolute(newBase, cf, serviceLog);
        List<String> cmd = designer(request, newBase);
        cmd.add("/LoadCfg");
        cmd.add(cf.toString());
        cmd.add("/Out");
        cmd.add(serviceLog.toString());
        return List.copyOf(cmd);
    }

    public static List<String> dumpXml(StorageSnapshotRequest request,
            Path newBase, Path xmlDirectory, Path serviceLog) {
        requireAbsolute(newBase, xmlDirectory, serviceLog);
        List<String> cmd = designer(request, newBase);
        cmd.add("/DumpConfigToFiles");
        cmd.add(xmlDirectory.toString());
        cmd.add("-Format");
        cmd.add("Hierarchical");
        cmd.add("/Out");
        cmd.add(serviceLog.toString());
        return List.copyOf(cmd);
    }

    private static List<String> designer(StorageSnapshotRequest request, Path base) {
        ArrayList<String> cmd = new ArrayList<>();
        cmd.add(request.executable().toString());
        cmd.add("DESIGNER");
        cmd.add("/F");
        cmd.add(base.toString());
        cmd.add("/DisableStartupDialogs");
        return cmd;
    }

    private static void requireAbsolute(Path... paths) {
        for (Path p : paths) {
            if (!Objects.requireNonNull(p).isAbsolute()) {
                throw new IllegalArgumentException("Paths must be absolute");
            }
        }
    }
}
