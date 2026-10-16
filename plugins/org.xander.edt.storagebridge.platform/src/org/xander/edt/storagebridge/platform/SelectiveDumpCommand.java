package org.xander.edt.storagebridge.platform;

import java.nio.file.Path;
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
        return List.of(request.executable().toString(), "DESIGNER",
            request.infobase().switchName(), request.infobase().address(),
            "/DisableStartupDialogs",
            "/DumpConfigToFiles", outputDirectory.toString(),
            "-listFile", objectList.toString(),
            // Hierarchical is the documented default. Explicit -Format is
            // unnecessary and can violate -listFile combination constraints.
            "/Out", serviceLog.toString());
    }
}
