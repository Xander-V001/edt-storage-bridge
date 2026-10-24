package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Connects incremental change detection and selective platform export for
 * the ONE existing infobase already bound to classical configuration storage.
 *
 * This operation is READ-ONLY. The intermediary is not related to EDT. No
 * attached EDT infobases, additional infobases, full dumps or Git branches
 * are involved. It does not change or import the active EDT project.
 *
 * The caller is responsible for updating the intermediary to the desired
 * classical storage revision and for verifying the provenance of the supplied
 * ConfigDumpInfo.xml. Never call with an assumed or fabricated baseline.
 */
public final class SelectiveReceiveStagingService {
    private final ChangedObjectsProbe changesProbe;
    private final SelectiveDumpRunner dumpRunner;

    public SelectiveReceiveStagingService() {
        this(new ChangedObjectsProbe(), new SelectiveDumpRunner());
    }

    public SelectiveReceiveStagingService(
            ChangedObjectsProbe changesProbe, SelectiveDumpRunner dumpRunner) {
        this.changesProbe = Objects.requireNonNull(changesProbe);
        this.dumpRunner = Objects.requireNonNull(dumpRunner);
    }

    /**
     * Returns an empty result with no export if there are no remote changes.
     * FullDump, unknown change records, errors and cancellation always fail
     * closed rather than silently performing a full import.
     */
    public StageResult prepare(ChangeDetectionRequest request,
            Path verifiedDumpInfo, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(verifiedDumpInfo, "verifiedDumpInfo");
        Objects.requireNonNull(cancelled, "cancelled");

        var changes = changesProbe.detect(request, verifiedDumpInfo, cancelled);
        if (cancelled.getAsBoolean()) {
            throw new IOException("Receive stage cancelled");
        }
        List<String> changedObjects = changes.objectsToExport();
        if (changedObjects.isEmpty()) {
            return new StageResult(changes, null);
        }
        // Deliberately reuse the SAME executable, SAME configured intermediary
        // and SAME timeout; never select an EDT-connected infobase.
        SelectiveDumpRequest selective = new SelectiveDumpRequest(
            request.executable(), request.infobase(), changedObjects,
            request.timeout());
        SelectiveDumpRunner.ExportResult export = dumpRunner.export(
            selective, cancelled);
        boolean success = false;
        try {
            if (cancelled.getAsBoolean()) {
                throw new IOException("Receive stage cancelled after export");
            }
            StageResult result = new StageResult(changes, export);
            success = true;
            return result;
        } finally {
            if (!success) {
                export.close();
            }
        }
    }

    /**
     * Holds the platform XML stage for subsequent native EDT conversion and
     * interactive compare. The caller MUST close only after the compare editor
     * has no references to this data (or on cancellation).
     */
    public record StageResult(
            ConfigurationChangesParser.ChangeSet changes,
            SelectiveDumpRunner.ExportResult export) implements AutoCloseable {
        public StageResult {
            Objects.requireNonNull(changes, "changes");
            if (changes.objectsToExport().isEmpty() != (export == null)) {
                throw new IllegalArgumentException(
                    "Nonempty change list must have an exported XML stage");
            }
        }

        public boolean hasChanges() {
            return export != null;
        }

        public Path xmlDirectory() {
            if (export == null) {
                throw new IllegalStateException("There are no remote changes");
            }
            return export.xmlDirectory();
        }

        @Override
        public void close() throws IOException {
            if (export != null) {
                export.close();
            }
        }
    }
}
