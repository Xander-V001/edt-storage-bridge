package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The 1C native history report supplies independently named changed metadata
 * for a proven revision interval. It is a CONSERVATIVE candidate set:
 * an object edited and reverted between two revisions can remain present.
 *
 * The native /CompareCfg report is the semantic net comparison. Its TXT file
 * is not an object-list API and must not be scraped to make exact XML scopes.
 * History candidates are instead given to Designer's documented -listFile
 * export, using the most recent intermediary configuration.
 */
public final class RepositoryRevisionHistoryDelta {
    private RepositoryRevisionHistoryDelta() {}

    public record Candidates(long baseline, long head, Set<String> touched) {
        public Candidates {
            if (baseline < 1 || head <= baseline) {
                throw new IllegalArgumentException("Invalid repository comparison versions");
            }
            touched = Set.copyOf(touched);
        }
    }

    /**
     * Every revision in (baseline, head] MUST be in the native report. A
     * truncated report, a grouped-by-object report, invalid timestamps or
     * an unsupported report encoding cannot supply an authoritative scope.
     */
    public static Candidates fromNativeReport(byte[] raw, long baseline, long head)
            throws IOException {
        Objects.requireNonNull(raw);
        if (baseline < 1 || head <= baseline) {
            throw new IllegalArgumentException("Invalid native revision range");
        }
        List<RepositoryVersionReportParser.Entry> versions = parseReport(raw);
        long expected = baseline + 1;
        Set<String> touched = new LinkedHashSet<>();
        for (var version : versions) {
            if (version.number() != expected || expected > head) {
                throw new IOException("Incomplete or unexpected native revision range");
            }
            touched.addAll(version.added());
            touched.addAll(version.modified());
            touched.addAll(version.deleted());
            expected++;
        }
        if (expected != head + 1) {
            throw new IOException("Repository history omits a revision");
        }
        return new Candidates(baseline, head, touched);
    }

    /** Convert native revision HISTORY to a conservative changed-name scope. */
    public static ChangedObjectsProbe.NativeChanges forCurrentInventory(
            Candidates candidates, Set<String> oldNames, Set<String> currentNames) {
        Objects.requireNonNull(candidates);
        Objects.requireNonNull(oldNames);
        Objects.requireNonNull(currentNames);
        Set<String> present = new LinkedHashSet<>(candidates.touched());
        present.retainAll(currentNames);
        // Native child names may have no own configVersion entry. Keep those
        // for the native selective export rather than guessing an owner.
        for (String name : candidates.touched()) {
            if (!present.contains(name) && !oldNames.contains(name)
                    && !currentNames.contains(name)
                    && name != null && !name.isBlank()) {
                // Potential unindexed metadata or addition/deletion. It must
                // NEVER be silently discarded by the optimizer.
                present.add(name);
            }
        }
        return new ChangedObjectsProbe.NativeChanges(false,
            new ConfigurationChangesParser.ChangeSet(List.of(),
                new ArrayList<>(present)));
    }

    public static Candidates read(Path executable, InfobaseLocation intermediary,
            String infobaseUsername, String infobasePassword,
            String repositoryAddress, String repositoryUsername,
            String repositoryPassword, long baseline, long head,
            Duration timeout, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (baseline < 1 || head <= baseline || head == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid native revision interval");
        }
        // Exclude the baseline revision itself. A change in that version has
        // already been reviewed and must not be sent again.
        var report = new ReadOnlyReportRunner().readHistory(
            RepositoryHistoryRequest.range(executable, intermediary,
                baseline + 1, head, timeout), cancelled,
            infobaseUsername, infobasePassword, repositoryAddress,
            repositoryUsername, repositoryPassword);
        return fromNativeReport(report.bytes(), baseline, head);
    }

    private static List<RepositoryVersionReportParser.Entry> parseReport(byte[] raw)
            throws IOException {
        IOException last = null;
        for (Charset encoding : List.of(StandardCharsets.UTF_8,
                StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE,
                Charset.forName("windows-1251"))) {
            try {
                String txt = encoding.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString()
                    .replaceFirst("^\uFEFF", "");
                return RepositoryVersionReportParser.parse(txt);
            } catch (CharacterCodingException ex) {
                last = ex;
            } catch (IOException ex) {
                last = ex;
            }
        }
        throw new IOException("Native 1C revision history format is unsupported", last);
    }
}
