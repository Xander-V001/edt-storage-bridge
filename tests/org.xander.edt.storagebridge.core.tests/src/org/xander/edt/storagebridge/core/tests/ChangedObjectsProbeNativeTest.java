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
}
