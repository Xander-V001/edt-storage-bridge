package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.ChangedObjectsProbe;

/** Native 1C output parser only: no 1C process, Git, or EDT launched. */
public final class ChangedObjectsProbeNativeTest {
    @Test
    public void nativeNewAndModifiedKeepExactNamesWithoutInventingParents()
            throws Exception {
        var report = ChangedObjectsProbe.parseNativeText(
            "Modified:Document.Payroll.ManagerModule\n"
            + "New:Document.Payroll.Form.Main\n");
        assertFalse(report.fullDump());
        assertEquals(Set.of("Document.Payroll.ManagerModule",
            "Document.Payroll.Form.Main"), report.exactNames());
        assertFalse(report.exactNames().contains("Document.Payroll"));
    }

    @Test
    public void fullDumpIsAnExplicitPlatformDecisionNotAnEmptyDelta()
            throws Exception {
        var report = ChangedObjectsProbe.parseNativeText("FullDump\r\n");
        assertTrue(report.fullDump());
        assertTrue(report.exactNames().isEmpty());
    }

    @Test
    public void unknownNativeChangeTypeFailsInsteadOfBeingIgnored() {
        assertThrows(IOException.class, () ->
            ChangedObjectsProbe.parseNativeText(
                "Mystery:Document.Payroll\n"));
    }

    @Test
    public void bomPrefixedNativeOutputUsesUtf8() throws Exception {
        Path file = Files.createTempFile("native-1c-changes-", ".txt");
        try {
            byte[] payload = "Modified:Document.Расчёт.ManagerModule\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] bom = new byte[payload.length + 3];
            bom[0] = (byte) 0xEF;
            bom[1] = (byte) 0xBB;
            bom[2] = (byte) 0xBF;
            System.arraycopy(payload, 0, bom, 3, payload.length);
            Files.write(file, bom);
            assertEquals(Set.of("Document.Расчёт.ManagerModule"),
                ChangedObjectsProbe.parseNativeFile(file).exactNames());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void nativeIncrementalSourceRemainsOwnedUntilComparisonCloses()
            throws Exception {
        Path stageRoot = Files.createTempDirectory("1c-retained-increment-");
        Path xmlRoot = Files.createDirectory(stageRoot.resolve("inspection"));
        Path nativeXml = xmlRoot.resolve("Document.xml");
        Files.writeString(nativeXml, "<Document/>");
        var report = ChangedObjectsProbe.parseNativeText(
            "Modified:Document.Order\n");
        var retained = new ChangedObjectsProbe.NativeExport(
            stageRoot, xmlRoot, report);
        try {
            assertTrue(Files.isRegularFile(nativeXml));
            assertEquals(Set.of("Document.Order"), retained.changes().exactNames());
            assertEquals(xmlRoot, retained.xmlDirectory());
        } finally {
            retained.close();
        }
        assertFalse(Files.exists(stageRoot));
        assertFalse(Files.exists(nativeXml));
    }

    @Test
    public void nativeEmptyChangeReportWithoutVersionManifestIsNotAccepted()
            throws Exception {
        Path root = Files.createTempDirectory("1c-native-empty-delta-");
        try {
            var noChanges = ChangedObjectsProbe.parseNativeText("");
            assertFalse(noChanges.fullDump());
            assertTrue(noChanges.exactNames().isEmpty());
            var ex = assertThrows(
                ChangedObjectsProbe.NativeStageIncomplete.class,
                () -> ChangedObjectsProbe.validateNativeStage(noChanges, root));
            assertEquals(0, ex.reportedChanges());
            assertTrue(ex.nativeChanges().exactNames().isEmpty());
            assertFalse(ex.manifestPresent());
            assertFalse(ex.metadataPresent());
            assertTrue(ex.emptyDeltaMissingOnlyManifest());
        } finally {
            Files.deleteIfExists(root);
        }
    }

    @Test
    public void changedNativeItemsWithoutManifestRemainUnsafeToImport()
            throws Exception {
        Path root = Files.createTempDirectory("1c-native-missing-index-");
        try {
            Files.writeString(root.resolve("Document.xml"), "<Document/>");
            var changed = ChangedObjectsProbe.parseNativeText(
                "Modified:Document.Payroll.Form.Main\n");
            var ex = assertThrows(ChangedObjectsProbe.NativeStageIncomplete.class,
                () -> ChangedObjectsProbe.validateNativeStage(changed, root));
            assertEquals(1, ex.reportedChanges());
            assertEquals(Set.of("Document.Payroll.Form.Main"),
                ex.nativeChanges().exactNames());
            assertFalse(ex.manifestPresent());
            assertTrue(ex.metadataPresent());
            assertFalse(ex.emptyDeltaMissingOnlyManifest());
        } finally {
            Files.deleteIfExists(root.resolve("Document.xml"));
            Files.deleteIfExists(root);
        }
    }

    @Test
    public void emptyReportWithUnexpectedXmlIsNotSilentlyAccepted()
            throws Exception {
        Path root = Files.createTempDirectory("1c-native-contradictory-");
        try {
            Files.writeString(root.resolve("Document.xml"), "<Document/>");
            var noChanges = ChangedObjectsProbe.parseNativeText("");
            var ex = assertThrows(
                ChangedObjectsProbe.NativeStageIncomplete.class,
                () -> ChangedObjectsProbe.validateNativeStage(noChanges, root));
            assertTrue(ex.metadataPresent());
            assertFalse(ex.emptyDeltaMissingOnlyManifest());
        } finally {
            Files.deleteIfExists(root.resolve("Document.xml"));
            Files.deleteIfExists(root);
        }
    }

    @Test
    public void fullDumpNeverRequiresPartialXmlManifest()
            throws Exception {
        Path root = Files.createTempDirectory("1c-native-fulldump-");
        try {
            var fullDump = ChangedObjectsProbe.parseNativeText("FullDump\n");
            ChangedObjectsProbe.validateNativeStage(fullDump, root);
        } finally {
            Files.deleteIfExists(root);
        }
    }

    @Test
    public void prepareNativeUpdateKeepsItsDumpTargetEmptyAndBaselineUntouched()
            throws Exception {
        Path work = Files.createTempDirectory("native-update-stage-");
        Path original = Files.createTempFile("1c-native-source-", ".xml");
        try {
            Files.writeString(original, "unchanged source from 1C");
            var nativeStage = ChangedObjectsProbe.prepareWorkspace(work, original);
            assertEquals("unchanged source from 1C",
                Files.readString(nativeStage.baselineCopy()));
            try (var files = Files.list(nativeStage.inspectionDirectory())) {
                assertEquals(0L, files.count());
            }
            assertFalse(nativeStage.baselineCopy()
                .startsWith(nativeStage.inspectionDirectory()));
            assertEquals("unchanged source from 1C", Files.readString(original));
        } finally {
            try (var entries = Files.walk(work)) {
                for (Path entry : entries.sorted(
                        java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(entry);
                }
            }
            Files.deleteIfExists(original);
        }
    }
}
