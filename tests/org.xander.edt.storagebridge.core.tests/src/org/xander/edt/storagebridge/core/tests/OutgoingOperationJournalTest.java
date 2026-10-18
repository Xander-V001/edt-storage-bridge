package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.OutgoingOperationJournal;

public final class OutgoingOperationJournalTest {
    private static final String SHA = "a".repeat(40);

    @Test
    public void preventsUncertainLockRetryAndPreservesDiagnostics() throws Exception {
        Path folder = Files.createTempDirectory("outgoing-journal-test-");
        try {
            var run = OutgoingOperationJournal.begin(folder, "test-source", SHA, 123);
            run.before("LOCK");
            assertThrows(IOException.class, () ->
                OutgoingOperationJournal.begin(folder, "test-source", SHA, 123));
            assertTrue(Files.readString(run.directory().resolve("events.log"))
                .contains("BEFORE_LOCK"));
        } finally { clean(folder); }
    }

    @Test
    public void cleanAbortAndVerifiedReleaseAreDifferent() throws Exception {
        Path folder = Files.createTempDirectory("outgoing-journal-test-");
        try {
            var before = OutgoingOperationJournal.begin(folder, "test-source", SHA, 100);
            before.abortBeforeMutation();
            var after = OutgoingOperationJournal.begin(folder, "test-source", SHA, 101);
            after.before("LOCK");
            assertThrows(IOException.class, after::abortBeforeMutation);
            after.after("LOCK", 0);
            after.before("LOAD");
            after.after("LOAD", 0);
            after.before("COMMIT");
            after.after("COMMIT", 0);
            after.verified(102);
            var next = OutgoingOperationJournal.begin(folder, "test-source", SHA, 102);
            next.abortBeforeMutation();
        } finally { clean(folder); }
    }

    private static void clean(Path root) throws Exception {
        try (var entries = Files.walk(root)) {
            for (var entry : entries.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(entry);
        }
    }
}
