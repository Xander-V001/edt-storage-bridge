package org.xander.edt.storagebridge.platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The sole allowed Designer operation in this adapter is the documented
 * /ConfigurationRepositoryReport. No arbitrary arguments or mutating switches.
 *
 * <p>Command-line credentials are deliberately unsupported: exposing passwords
 * in a process command line or logs is unacceptable. Initial prototype relies
 * on an already connected infobase whose credentials are available to 1C.
 */
public final class ReadOnlyReportCommand {

    private ReadOnlyReportCommand() {
    }

    public static List<String> arguments(RepositoryHistoryRequest request,
            Path reportFile, Path serviceMessages) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(reportFile, "reportFile");
        Objects.requireNonNull(serviceMessages, "serviceMessages");
        if (!reportFile.isAbsolute() || !serviceMessages.isAbsolute()) {
            throw new IllegalArgumentException("Report/output paths must be absolute");
        }
        ArrayList<String> args = new ArrayList<>();
        args.add(request.executable().toString());
        args.add("DESIGNER");
        args.add(request.infobase().switchName());
        args.add(request.infobase().address());
        args.add("/DisableStartupDialogs");
        args.add("/ConfigurationRepositoryReport");
        args.add(reportFile.toString());
        args.add("-NBegin");
        args.add(Long.toString(request.beginVersion()));
        if (request.endVersion() != null) {
            args.add("-NEnd");
            args.add(Long.toString(request.endVersion()));
        }
        args.add("-ReportFormat");
        args.add("txt");
        args.add("/Out");
        args.add(serviceMessages.toString());
        return List.copyOf(args);
    }
}
