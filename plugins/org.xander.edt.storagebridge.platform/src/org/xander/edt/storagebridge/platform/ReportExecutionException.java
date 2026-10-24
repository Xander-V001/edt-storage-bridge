package org.xander.edt.storagebridge.platform;

import java.io.IOException;

/**
 * Safe, non-sensitive error surface. Service logs may contain base paths,
 * authors and other personal information, and are not placed in exceptions.
 */
public final class ReportExecutionException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        INVALID_EXECUTABLE, EXIT_CODE, TIMEOUT, CANCELLED,
        OVERSIZED_OUTPUT, MISSING_REPORT
    }

    private final Reason reason;
    private final Integer exitCode;

    public ReportExecutionException(Reason reason, String message, Integer exitCode) {
        super(message);
        this.reason = reason;
        this.exitCode = exitCode;
    }

    public Reason reason() {
        return reason;
    }

    public Integer exitCode() {
        return exitCode;
    }
}
