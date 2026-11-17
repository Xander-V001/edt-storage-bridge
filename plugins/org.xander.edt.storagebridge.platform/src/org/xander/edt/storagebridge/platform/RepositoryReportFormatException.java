package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Safe, non-sensitive diagnostics for native Designer TXT report parsing.
 * Native text (including repository addresses and usernames) is NEVER stored
 * inside an exception message or automatically written to the Eclipse log.
 */
public final class RepositoryReportFormatException extends IOException {
    public enum Reason {
        UNKNOWN_REPORT_HEADER, MISSING_VERSION_BLOCK,
        INVALID_OR_DUPLICATE_VERSION, UNKNOWN_ENCODING
    }

    private final Reason reason;
    private final Path localReport;

    public RepositoryReportFormatException(Reason reason) {
        this(reason, null);
    }

    public RepositoryReportFormatException(Reason reason, Path localReport) {
        super("Native 1C repository report format: " + reason.name());
        this.reason = reason;
        this.localReport = localReport;
    }

    public Reason reason() { return reason; }
    public Path localReport() { return localReport; }

    public RepositoryReportFormatException withLocalReport(Path path) {
        return new RepositoryReportFormatException(reason, path);
    }
}
