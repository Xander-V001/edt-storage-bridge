package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.ChangedObjectsProbe;
import org.xander.edt.storagebridge.platform.RemoteReceiveScopePlanner;

/** Unit only: 1C supplies change names, never Java token comparisons. */
public final class RemoteReceiveScopePlannerTest {
    private static Path inventory(String contents) throws IOException {
        Path file = Files.createTempFile("native-version-index-", ".xml");
        Files.writeString(file, "<ConfigDumpInfo format=\"Hierarchical\" version=\"2.17\">"
            + "<ConfigVersions>" + contents
            + "</ConfigVersions></ConfigDumpInfo>");
        return file;
    }

    @Test
    public void nativeModuleOnlyDoesNotInventDocumentParent() throws Exception {
        Path old = inventory("""
            <Metadata name="Document.Payroll" id="p" configVersion="1"/>
            <Metadata name="Document.Payroll.ManagerModule"
                id="m" configVersion="1"/>""");
        Path now = inventory("""
            <Metadata name="Document.Payroll" id="p" configVersion="1"/>
            <Metadata name="Document.Payroll.ManagerModule"
                id="m" configVersion="2"/>""");
        try {
            var delta = RemoteReceiveScopePlanner.reconcileNative(old, now,
                ChangedObjectsProbe.parseNativeText(
                    "Modified:Document.Payroll.ManagerModule\n"));
            assertEquals(Set.of("Document.Payroll.ManagerModule"),
                delta.pendingIdentities());
            assertFalse(delta.requiresFullNativeComparison());
        } finally {
            Files.deleteIfExists(old);
            Files.deleteIfExists(now);
        }
    }

    @Test
    public void opaqueConfigVersionAndIdNeverDetermineChanges() throws Exception {
        Path old = inventory(
            "<Metadata name=\"Document.Payroll\" id=\"old\" configVersion=\"1\"/>");
        Path now = inventory(
            "<Metadata name=\"Document.Payroll\" id=\"new\" configVersion=\"2\"/>");
        try {
            var delta = RemoteReceiveScopePlanner.reconcileNative(old, now,
                ChangedObjectsProbe.parseNativeText(""));
            assertTrue(delta.pendingIdentities().isEmpty());
            assertFalse(delta.requiresFullNativeComparison());
        } finally {
            Files.deleteIfExists(old);
            Files.deleteIfExists(now);
        }
    }

    @Test
    public void disappearedPlatformNameIsPreservedAndRequiresGlobalComparison()
            throws Exception {
        Path old = inventory("""
            <Metadata name="Document.Kept" id="k" configVersion="1"/>
            <Metadata name="Document.Deleted" id="d" configVersion="1"/>""");
        Path now = inventory("""
            <Metadata name="Document.Kept" id="k" configVersion="2"/>""");
        try {
            var delta = RemoteReceiveScopePlanner.reconcileNative(old, now,
                ChangedObjectsProbe.parseNativeText("Modified:Document.Kept\n"));
            assertEquals(Set.of("Document.Deleted"), delta.removed());
            assertEquals(Set.of("Document.Deleted", "Document.Kept"),
                delta.pendingIdentities());
            assertTrue(delta.requiresFullNativeComparison());
            assertEquals(Set.of("Document.Kept"),
                RemoteReceiveScopePlanner.availableInFullReview(
                    delta.pendingIdentities(), Set.of("Document.Kept")));
        } finally {
            Files.deleteIfExists(old);
            Files.deleteIfExists(now);
        }
    }

    @Test
    public void configurationLanguageAndNativeFullDumpRequireCompleteReview()
            throws Exception {
        Path old = inventory("""
            <Metadata name="Configuration" id="config" configVersion="1"/>
            <Metadata name="Language.Русский" id="lang" configVersion="1"/>""");
        Path now = inventory("""
            <Metadata name="Configuration" id="config" configVersion="2"/>
            <Metadata name="Language.Русский" id="lang" configVersion="2"/>""");
        try {
            var changes = RemoteReceiveScopePlanner.reconcileNative(old, now,
                ChangedObjectsProbe.parseNativeText(
                    "Modified:Configuration\nModified:Language.Русский\n"));
            assertEquals(Set.of("Configuration", "Language.Русский"),
                changes.pendingIdentities());
            assertTrue(changes.requiresFullNativeComparison());
            var full = RemoteReceiveScopePlanner.reconcileNative(old, now,
                ChangedObjectsProbe.parseNativeText("FullDump"));
            assertTrue(full.requiresFullNativeComparison());
            assertEquals(Set.of("Configuration", "Language.Русский"),
                full.pendingIdentities());
            assertEquals(full.pendingIdentities(),
                RemoteReceiveScopePlanner.fullInventory(now).pendingIdentities());
        } finally {
            Files.deleteIfExists(old);
            Files.deleteIfExists(now);
        }
    }

    @Test
    public void nativeUnversionedPropertyMustNotBeSilentlyDiscarded()
            throws Exception {
        Path old = inventory(
            "<Metadata name=\"Document.Payroll\" id=\"p\" configVersion=\"1\"/>");
        Path now = inventory(
            "<Metadata name=\"Document.Payroll\" id=\"p\" configVersion=\"1\"/>");
        try {
            var delta = RemoteReceiveScopePlanner.reconcileNative(old, now,
                ChangedObjectsProbe.parseNativeText(
                    "Modified:Document.Payroll.Attribute.NewField\n"));
            assertEquals(Set.of("Document.Payroll.Attribute.NewField"),
                delta.unindexed());
            assertEquals(delta.unindexed(), delta.pendingIdentities());
            assertTrue(delta.requiresFullNativeComparison());
        } finally {
            Files.deleteIfExists(old);
            Files.deleteIfExists(now);
        }
    }
}
