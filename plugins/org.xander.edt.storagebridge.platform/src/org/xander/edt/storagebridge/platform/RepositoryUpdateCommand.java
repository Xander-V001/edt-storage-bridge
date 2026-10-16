package org.xander.edt.storagebridge.platform;

import java.nio.file.Path;
import java.util.ArrayList;
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
        var args = new ArrayList<String>();
        args.add(request.executable().toString());
        args.add("DESIGNER");
        args.add(request.infobase().switchName());
        args.add(request.infobase().address());
        args.add("/DisableStartupDialogs");
        // These are documented repository access parameters, not IB login.
        // Never emit the constructed command or its password into EDT logs.
        if (!request.repositoryAddress().isBlank()) {
            args.add("/ConfigurationRepositoryF");
            args.add(request.repositoryAddress());
        }
        if (!request.repositoryUsername().isBlank()) {
            args.add("/ConfigurationRepositoryN");
            args.add(request.repositoryUsername());
            if (!request.repositoryPassword().isEmpty()) {
                args.add("/ConfigurationRepositoryP");
                args.add(request.repositoryPassword());
            }
        }
        args.add("/ConfigurationRepositoryUpdateCfg");
        args.add("-force");
        args.add("/Out");
        args.add(outputFile.toString());
        return List.copyOf(args);
    }
}
