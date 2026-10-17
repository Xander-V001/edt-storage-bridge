package org.xander.edt.storagebridge.platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Explicit one-time full export; never a fallback after selective failure. */
public final class InitialFullDumpCommand {
    private InitialFullDumpCommand() {}

    public static List<String> arguments(InitialFullDumpRequest request,
            Path destination, Path serviceLog) {
        Objects.requireNonNull(request);
        if (!Objects.requireNonNull(destination).isAbsolute()
                || !Objects.requireNonNull(serviceLog).isAbsolute()) {
            throw new IllegalArgumentException("Absolute output paths required");
        }
        var args = new ArrayList<String>();
        args.add(request.executable().toString());
        args.add("DESIGNER");
        args.add(request.infobase().switchName());
        args.add(request.infobase().address());
        DesignerInfobaseAuth.addTo(args,
            request.infobaseUsername(), request.infobasePassword());
        args.add("/DisableStartupDialogs");
        args.add("/DisableStartupMessages");
        args.add("/DisableUnrecoverableErrorMessage");
        args.add("/DumpConfigToFiles");
        args.add(destination.toString());
        args.add("-Format");
        args.add("Hierarchical");
        args.add("/Out");
        args.add(serviceLog.toString());
        return List.copyOf(args);
    }
}
