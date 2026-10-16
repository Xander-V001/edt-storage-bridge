package org.xander.edt.storagebridge.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Allowlisted Designer command. -force confirms additions/removals in the
 * connected infobase. -revised is intentionally forbidden (could overwrite
 * repository captures/local Designer changes).
 */
public final class RepositoryUpdateCommand {
    private RepositoryUpdateCommand() {
    }

    public static List<String> arguments(RepositoryUpdateRequest request, Path outputFile) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(outputFile, "outputFile");
        if (!outputFile.isAbsolute()) {
            throw new IllegalArgumentException("Output file must be absolute");
        }
        return List.of(
            request.executable().toString(),
            "DESIGNER",
            request.infobase().switchName(),
            request.infobase().address(),
            "/DisableStartupDialogs",
            "/ConfigurationRepositoryUpdateCfg",
            "-force",
            "/Out",
            outputFile.toString());
    }
}
