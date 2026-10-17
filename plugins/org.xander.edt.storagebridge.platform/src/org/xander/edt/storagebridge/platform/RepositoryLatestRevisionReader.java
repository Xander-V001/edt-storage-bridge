package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Obtain the last native 1C repository revision from a *read-only* report.
 *
 * A version checkpoint is NOT a detailed change-history import. The old
 * implementation ran the STRICT all-objects history parser merely to read a
 * single version number. Normal native TXT reports with absent/truncated
 * detail lists or formatting variations were wrongly rejected. This reader
 * validates the report title and complete native version metadata header,
 * but does not depend on section counts, comments or object descriptions.
 *
 * Complete version history with object counts is still parsed independently
 * by RepositoryVersionReportParser and retains its stricter semantics.
 */
public final class RepositoryLatestRevisionReader {
    private static final Pattern TITLE = Pattern.compile(
        "(?iu)^Отч[её]т по версиям хранилища\\b.*$");
    private static final Pattern VERSION = Pattern.compile(
        "(?iu)^Версия\\s*:\\s*([1-9][0-9]{0,15})\\s*$");
    private static final Pattern CONFIG_VERSION = Pattern.compile(
        "(?iu)^Версия конфигурации\\s*:.*$");
    private static final Pattern USER = Pattern.compile(
        "(?iu)^Пользователь\\s*:.*$");
    private static final Pattern DATE = Pattern.compile(
        "(?iu)^Дата создания\\s*:\\s*(\\d{2}\\.\\d{2}\\.\\d{4})\\s*$");
    private static final Pattern TIME = Pattern.compile(
        "(?iu)^Время создания\\s*:\\s*(\\d{2}:\\d{2}:\\d{2})\\s*$");
    private static final DateTimeFormatter CREATED_DATE = DateTimeFormatter
        .ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter CREATED_TIME = DateTimeFormatter
        .ofPattern("HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    private RepositoryLatestRevisionReader() {}

    public static long parseLatest(String report) throws IOException {
        Objects.requireNonNull(report, "report");
        String[] lines = report.replaceFirst("^\\uFEFF", "").split("\\R", -1);
        boolean header = false;
        // The authentic report begins with title, creation date/time, a
        // blank separator, and version records. We allow whitespace and
        // additional harmless header lines, not a different report type.
        for (int i = 0; i < Math.min(lines.length, 10); i++) {
            if (TITLE.matcher(lines[i].strip()).matches()) {
                header = true;
                break;
            }
        }
        if (!header) {
            throw new RepositoryReportFormatException(
                RepositoryReportFormatException.Reason.UNKNOWN_REPORT_HEADER);
        }
        Set<Long> revisions = new HashSet<>();
        long latest = -1;
        boolean malformed = false;
        for (int i = 0; i < lines.length; i++) {
            Matcher version = VERSION.matcher(lines[i].strip());
            if (!version.matches()) {
                continue;
            }
            // A line saying "Версия: 42" within a free-form comment must
            // never be accepted without the complete *adjacent* native
            // metadata header immediately following it.
            if (i + 4 >= lines.length
                    || !CONFIG_VERSION.matcher(lines[i + 1].strip()).matches()
                    || !USER.matcher(lines[i + 2].strip()).matches()) {
                continue;
            }
            Matcher date = DATE.matcher(lines[i + 3].strip());
            Matcher time = TIME.matcher(lines[i + 4].strip());
            if (!date.matches() || !time.matches()) {
                malformed = true;
                continue;
            }
            try {
                LocalDate.parse(date.group(1), CREATED_DATE);
                LocalTime.parse(time.group(1), CREATED_TIME);
                long number = Long.parseLong(version.group(1));
                if (!revisions.add(number)) {
                    throw new RepositoryReportFormatException(
                        RepositoryReportFormatException.Reason.INVALID_OR_DUPLICATE_VERSION);
                }
                latest = Math.max(latest, number);
            } catch (DateTimeParseException | NumberFormatException ex) {
                malformed = true;
            }
        }
        if (malformed) {
            throw new RepositoryReportFormatException(
                RepositoryReportFormatException.Reason.INVALID_OR_DUPLICATE_VERSION);
        }
        if (latest < 1) {
            throw new RepositoryReportFormatException(
                RepositoryReportFormatException.Reason.MISSING_VERSION_BLOCK);
        }
        return latest;
    }

    /**
     * Native batch reports may be UTF-8, UTF-16 (with or without BOM) or
     * Windows-1251. A BOM is authoritative. Without one, try strict decoding
     * followed by report header and native version-record validation.
     */
    public static long parseLatest(byte[] raw) throws IOException {
        Objects.requireNonNull(raw, "raw");
        if (raw.length == 0) {
            throw new RepositoryReportFormatException(
                RepositoryReportFormatException.Reason.UNKNOWN_ENCODING);
        }
        if (startsWith(raw, 0xEF, 0xBB, 0xBF)
                || startsWith(raw, 0xFF, 0xFE)
                || startsWith(raw, 0xFE, 0xFF)) {
            try {
                return parseLatest(TextReportDecoder.decode(raw));
            } catch (CharacterCodingException ex) {
                throw new RepositoryReportFormatException(
                    RepositoryReportFormatException.Reason.UNKNOWN_ENCODING);
            }
        }
        Long result = null;
        RepositoryReportFormatException failure = null;
        for (Charset charset : new Charset[] {
                StandardCharsets.UTF_8, StandardCharsets.UTF_16LE,
                StandardCharsets.UTF_16BE, Charset.forName("windows-1251") }) {
            try {
                String text = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
                long parsed = parseLatest(text);
                if (result != null && result.longValue() != parsed) {
                    throw new RepositoryReportFormatException(
                        RepositoryReportFormatException.Reason.INVALID_OR_DUPLICATE_VERSION);
                }
                result = parsed;
            } catch (CharacterCodingException ignored) {
                // Not this encoding.
            } catch (RepositoryReportFormatException ex) {
                // Prefer a structural failure over a generic unknown codec.
                if (ex.reason() == RepositoryReportFormatException.Reason.INVALID_OR_DUPLICATE_VERSION
                        && result != null) {
                    throw ex;
                }
                if (failure == null
                        || failure.reason() == RepositoryReportFormatException.Reason.UNKNOWN_REPORT_HEADER) {
                    failure = ex;
                }
            }
        }
        if (result != null) {
            return result;
        }
        throw failure != null ? failure : new RepositoryReportFormatException(
            RepositoryReportFormatException.Reason.UNKNOWN_ENCODING);
    }

    private static boolean startsWith(byte[] bytes, int a, int b) {
        return bytes.length >= 2 && (bytes[0] & 255) == a
            && (bytes[1] & 255) == b;
    }

    private static boolean startsWith(byte[] bytes, int a, int b, int c) {
        return bytes.length >= 3 && (bytes[0] & 255) == a
            && (bytes[1] & 255) == b && (bytes[2] & 255) == c;
    }

    public static long latest(RepositoryHistoryRequest request, String ibUsername,
            String ibPassword, String repositoryAddress, String repositoryUsername,
            String repositoryPassword, BooleanSupplier canceled)
            throws IOException, InterruptedException {
        Objects.requireNonNull(request);
        if (request.beginVersion() != -1 || request.endVersion() != null) {
            throw new IllegalArgumentException("Latest report request required");
        }
        var report = new ReadOnlyReportRunner()
            .readHistory(request, canceled, ibUsername, ibPassword,
                repositoryAddress, repositoryUsername, repositoryPassword);
        try {
            return parseLatest(report.bytes());
        } catch (RepositoryReportFormatException ex) {
            // The read-only runner deletes its private temporary files on
            // success. Preserve ONLY an unparsable native report for the
            // user's own inspection. Never commit/upload/log its contents:
            // history TXT can contain server addresses, usernames, comments.
            Path copy = null;
            try {
                copy = Files.createTempFile("edt-storage-version-report-", ".txt");
                Files.write(copy, report.bytes());
            } catch (IOException failedWrite) {
                if (copy != null) {
                    try { Files.deleteIfExists(copy); }
                    catch (IOException ignored) { /* best effort */ }
                }
                copy = null;
            }
            throw ex.withLocalReport(copy);
        }
    }
}
