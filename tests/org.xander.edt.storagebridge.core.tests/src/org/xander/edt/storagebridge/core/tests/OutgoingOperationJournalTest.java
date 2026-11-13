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
    public void anUnfinishedRunAndLegacyActiveMarkerNeverBlockNextRun() throws Exception {
        Path folder = Files.createTempDirectory("outgoing-independent-runs-");
        try {
            var first = OutgoingOperationJournal.begin(folder, "test-source", SHA, 123);
            first.before("LOCK");
            Path oldMarker = first.directory().getParent().resolve("active.txt");
            Files.writeString(oldMarker, "legacy-unresolved-run");
            var second = OutgoingOperationJournal.begin(folder, "test-source", SHA, 123);
            assertNotEquals(first.id(), second.id());
            assertNotEquals(first.directory(), second.directory());
            assertEquals("PREPARED", Files.readString(
                second.directory().resolve("state.txt")).trim());
            assertEquals("LOCK_UNCERTAIN", Files.readString(
                first.directory().resolve("state.txt")).trim());
            assertTrue(Files.exists(oldMarker));
            second.abortBeforeMutation();
        } finally { clean(folder); }
    }

    @Test
    public void abortAndNativeCommitAffectOnlyOwnRun() throws Exception {
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
            after.completedAfterNativeCommit();
            assertEquals("COMPLETED", Files.readString(
                after.directory().resolve("state.txt")).trim());
            assertTrue(Files.readString(after.directory().resolve("events.log"))
                .contains("COMMIT_SUCCEEDED"));
            assertEquals("ABORTED", Files.readString(
                before.directory().resolve("state.txt")).trim());
            var next = OutgoingOperationJournal.begin(folder, "test-source", SHA, 102);
            next.abortBeforeMutation();
        } finally { clean(folder); }
    }

    @Test
    public void cannotAdvanceUnfinishedCurrentRun() throws Exception {
        Path root = Files.createTempDirectory("outgoing-stage-invariant-");
        try {
            var op = OutgoingOperationJournal.begin(root, "test-lock-state", SHA, 50);
            assertThrows(IOException.class, () -> op.before("COMMIT"));
            assertThrows(IOException.class, op::completedAfterNativeCommit);
            op.before("LOCK");
            assertThrows(IOException.class, () -> op.before("LOAD"));
            assertThrows(IOException.class, op::abortBeforeMutation);
            op.after("LOCK", 0);
            op.before("LOAD");
            assertThrows(IOException.class, op::completedAfterNativeCommit);
        } finally { clean(root); }
    }

    @Test
    public void failedCommitDoesNotCertifyCurrentRunOrPreventNewRun() throws Exception {
        Path folder = Files.createTempDirectory("outgoing-commit-failed-");
        try {
            var first = OutgoingOperationJournal.begin(folder, "failed-commit", SHA, 123);
            first.before("LOCK");
            first.after("LOCK", 0);
            first.before("LOAD");
            first.after("LOAD", 0);
            first.before("COMMIT");
            assertThrows(IOException.class, () -> first.after("COMMIT", 1));
            assertThrows(IOException.class, first::completedAfterNativeCommit);
            assertEquals("COMMIT_UNCERTAIN", Files.readString(
                first.directory().resolve("state.txt")).trim());

            var next = OutgoingOperationJournal.begin(folder, "failed-commit", SHA, 123);
            assertEquals("PREPARED", Files.readString(
                next.directory().resolve("state.txt")).trim());
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
