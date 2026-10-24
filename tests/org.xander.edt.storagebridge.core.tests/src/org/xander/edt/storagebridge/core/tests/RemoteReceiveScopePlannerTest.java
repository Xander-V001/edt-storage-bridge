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
    public void removedUnversionedFormIsDetectedEvenWithEmptyNativeChanges()
            throws Exception {
        // Real ConfigDumpInfo may carry child Metadata with no
        // independent configVersion. A deleted form is still a deletion.
        Path old = inventory("""
            <Metadata name="Document.Order" id="o" configVersion="1"/>
            <Metadata name="Document.Order.Form.Main" id="f"/>
            <Metadata name="Document.Order.Form.Main.Form" id="ff"/>""");
        Path now = inventory("""
            <Metadata name="Document.Order" id="o" configVersion="2"/>""");
        try {
            var delta = RemoteReceiveScopePlanner.reconcileNative(
                old, now, ChangedObjectsProbe.parseNativeText(""));
            assertEquals(Set.of("Document.Order.Form.Main",
                "Document.Order.Form.Main.Form"), delta.removed());
            assertTrue(delta.requiresFullNativeComparison());
            var full = RemoteReceiveScopePlanner.fullInventory(old, now);
            assertEquals(delta.removed(), full.removed());
            assertTrue(full.requiresFullNativeComparison());
        } finally {
            Files.deleteIfExists(old);
            Files.deleteIfExists(now);
        }
    }

    @Test
    public void nativeDeletedFormIsNotClassifiedAsUnindexedPresentResource()
            throws Exception {
        Path old = inventory("""
            <Metadata name="DataProcessor.Assistant" id="a" configVersion="1"/>
            <Metadata name="DataProcessor.Assistant.Form.Main"
                id="f" configVersion="1"/>
            <Metadata name="DataProcessor.Assistant.Form.Main.Form"
                id="p" configVersion="1"/>""");
        Path now = inventory("""
            <Metadata name="DataProcessor.Assistant" id="a" configVersion="2"/>""");
        try {
            var delta = RemoteReceiveScopePlanner.reconcileNative(old, now,
                ChangedObjectsProbe.parseNativeText(
                    "Modified:DataProcessor.Assistant.Form.Main\n"
                    + "Modified:DataProcessor.Assistant.Form.Main.Form\n"));
            assertTrue(delta.requiresFullNativeComparison());
            assertTrue(delta.removed().contains(
                "DataProcessor.Assistant.Form.Main"));
            assertTrue(delta.removed().contains(
                "DataProcessor.Assistant.Form.Main.Form"));
            assertFalse(delta.unindexed().contains(
                "DataProcessor.Assistant.Form.Main"));
            assertFalse(delta.unindexed().contains(
                "DataProcessor.Assistant.Form.Main.Form"));
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
            assertEquals(Set.of(), full.pendingIdentities());
            assertTrue(full.requiresFullNativeComparison());
            assertEquals(Set.of(),
                RemoteReceiveScopePlanner.fullInventory(old, now).pendingIdentities());
        } finally {
            Files.deleteIfExists(old);
            Files.deleteIfExists(now);
        }
    }

    @Test
    public void fullReviewPreservesDeletedObjectAsPendingDebt() throws Exception {
        Path old = inventory("""
            <Metadata name="Document.Kept" id="k" configVersion="1"/>
            <Metadata name="Document.Gone" id="g" configVersion="1"/>""");
        Path now = inventory("""
            <Metadata name="Document.Kept" id="k" configVersion="1"/>""");
        try {
            var full = RemoteReceiveScopePlanner.fullInventory(old, now);
            assertEquals(Set.of("Document.Gone"), full.removed());
            assertEquals(Set.of("Document.Gone"), full.pendingIdentities());
            assertTrue(full.requiresFullNativeComparison());
        } finally {
            Files.deleteIfExists(old);
            Files.deleteIfExists(now);
        }
    }

    @Test
    public void initialFullComparisonDoesNotCreateFakePendingObjects()
            throws Exception {
        Path now = inventory(
            "<Metadata name=\"Document.One\" id=\"1\" configVersion=\"1\"/>"
            + "<Metadata name=\"Document.Two\" id=\"2\" configVersion=\"1\"/>");
        try {
            var review = RemoteReceiveScopePlanner.fullInventory(null, now);
            assertTrue(review.requiresFullNativeComparison());
            assertTrue(review.pendingIdentities().isEmpty());
        } finally {
            Files.deleteIfExists(now);
        }
    }

    @Test
    public void nativeUnversionedPropertyCanBeReviewedSelectivelyWithoutLosingPendingIdentity()
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
            assertFalse(delta.requiresFullNativeComparison());
        } finally {
            Files.deleteIfExists(old);
            Files.deleteIfExists(now);
        }
    }
}
