package org.xander.edt.storagebridge.platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The documented -listFile limits the dump to explicitly listed metadata
 * objects. There is deliberately no full dump fallback or -force.
 */
public final class SelectiveDumpCommand {
    private SelectiveDumpCommand() {
    }

    public static List<String> arguments(SelectiveDumpRequest request,
            Path outputDirectory, Path objectList, Path serviceLog) {
        Objects.requireNonNull(request, "request");
        for (Path path : new Path[] {outputDirectory, objectList, serviceLog}) {
            if (!Objects.requireNonNull(path).isAbsolute()) {
                throw new IllegalArgumentException("Absolute output paths required");
            }
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
        args.add(outputDirectory.toString());
        args.add("-listFile");
        args.add(objectList.toString());
        // Hierarchical is the default; keep 1C selective mode.
        args.add("/Out");
        args.add(serviceLog.toString());
        return List.copyOf(args);
    }
}
