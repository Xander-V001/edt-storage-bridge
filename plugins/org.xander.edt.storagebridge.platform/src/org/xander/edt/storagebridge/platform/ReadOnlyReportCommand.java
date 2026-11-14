package org.xander.edt.storagebridge.platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The sole allowed Designer operation in this adapter is the documented
 * /ConfigurationRepositoryReport. No arbitrary arguments or mutating switches.
 *
 * <p>Authentication is injected by ReadOnlyReportRunner, not by this
 * argument-only builder. Never write command-line credentials to diagnostics.
 * The native TXT report parser expects Russian headings, so the platform
 * language and interface language are pinned to ru.
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
        // 1C-Company/GitConverter explicitly sets /L ru /VL ru for native
        // Designer batch operations. Without these switches, our strict
        // Russian repository TXT report parser depends on user-local defaults.
        args.add("/L");
        args.add("ru");
        args.add("/VL");
        args.add("ru");
        args.add("/ConfigurationRepositoryReport");
        args.add(reportFile.toString());
        args.add("-NBegin");
        args.add(Long.toString(request.beginVersion()));
        if (request.endVersion() != null) {
            args.add("-NEnd");
            args.add(Long.toString(request.endVersion()));
        }
        // Match the interactive "По версиям хранилища" report with
        // "Не выводить строки комментария, начинающиеся с //": unchecked.
        // Without this switch, native Designer silently omits task comments.
        args.add("-IncludeCommentLinesWithDoubleSlash");
        args.add("-ReportFormat");
        args.add("txt");
        args.add("/Out");
        args.add(serviceMessages.toString());
        return List.copyOf(args);
    }
}
