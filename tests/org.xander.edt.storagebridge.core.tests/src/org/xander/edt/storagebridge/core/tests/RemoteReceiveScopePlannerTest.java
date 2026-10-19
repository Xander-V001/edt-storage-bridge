package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.RemoteReceiveScopePlanner;

/** Unit-only: never starts Designer, EDT or Git. */
public final class RemoteReceiveScopePlannerTest {
    private static Path inventory(String content) throws IOException {
        Path path = Files.createTempFile("edt-storage-review-inventory-", ".xml");
        Files.writeString(path, "<ConfigDumpInfo format=\"Hierarchical\" version=\"2.17\">"
            + "<ConfigVersions>" + content + "</ConfigVersions></ConfigDumpInfo>");
        return path;
    }

    @Test
    public void repeatFullReviewIncludesUnchangedObjectsAndConfiguration() throws Exception {
        Path old = inventory("""
            <Metadata name="Configuration" id="c" configVersion="1"/>
            <Metadata name="Document.First" id="a" configVersion="10"/>
            <Metadata name="Document.Second" id="b" configVersion="20"/>
            """);
        Path next = inventory("""
            <Metadata name="Configuration" id="c" configVersion="1"/>
            <Metadata name="Document.First" id="a" configVersion="10"/>
            <Metadata name="Document.Second" id="b" configVersion="21"/>
            """);
        try {
            assertEquals(Set.of("Document.Second"),
                RemoteReceiveScopePlanner.candidates(old, next, false));
            assertEquals(Set.of("Configuration", "Document.First", "Document.Second"),
                RemoteReceiveScopePlanner.candidates(old, next, true));
            assertEquals(Set.of("Configuration", "Document.First", "Document.Second"),
                RemoteReceiveScopePlanner.candidates(null, next, false));
        } finally {
            Files.deleteIfExists(old);
            Files.deleteIfExists(next);
        }
    }

    @Test
    public void forcedFullReviewCanInventoryDeletedObjectWithoutLosingDebt()
            throws Exception {
        Path old = inventory("""
            <Metadata name="Document.Deleted" id="deleted" configVersion="1"/>
            <Metadata name="Document.Kept" id="kept" configVersion="1"/>
            """);
        Path next = inventory("""
            <Metadata name="Document.Kept" id="kept" configVersion="2"/>
            """);
        try {
            assertEquals(Set.of("Document.Deleted", "Document.Kept"),
                RemoteReceiveScopePlanner.candidates(old, next, false));
            var incremental = RemoteReceiveScopePlanner.discover(old, next, false);
            assertEquals(Set.of("Document.Kept"), incremental.present());
            assertEquals(Set.of("Document.Deleted"), incremental.removed());
            assertTrue(incremental.requiresFullNativeComparison());
            assertEquals(Set.of("Document.Kept"),
                RemoteReceiveScopePlanner.candidates(old, next, true));
            assertEquals(Set.of("Document.Deleted"),
                RemoteReceiveScopePlanner.removedSince(old, next));
            assertEquals(Set.of(),
                RemoteReceiveScopePlanner.removedSince(null, next));
            var current = Set.of("Document.Kept");
            var pending = Set.of("Document.Deleted", "Document.Kept");
            var review = RemoteReceiveScopePlanner.availableInFullReview(
                pending, current);
            assertEquals(Set.of("Document.Kept"), review);
            assertTrue(pending.contains("Document.Deleted"));
        } finally {
            Files.deleteIfExists(old);
            Files.deleteIfExists(next);
        }
    }

    @Test
    public void moduleOnlyUpdateDoesNotInventAnExtraParentPendingIdentity()
            throws Exception {
        Path before = inventory("""
            <Metadata name="Document.Заказ" id="owner" configVersion="v1"/>
            <Metadata name="Document.Заказ.ManagerModule"
                id="manager" configVersion="old"/>
            <Metadata name="Document.Заказ.Form.Основная"
                id="form" configVersion="same"/>
            """);
        Path after = inventory("""
            <Metadata name="Document.Заказ" id="owner" configVersion="v1"/>
            <Metadata name="Document.Заказ.ManagerModule"
                id="manager" configVersion="new"/>
            <Metadata name="Document.Заказ.Form.Основная"
                id="form" configVersion="same"/>
            """);
        try {
            var delta = RemoteReceiveScopePlanner.discover(before, after, false);
            assertEquals(Set.of("Document.Заказ.ManagerModule"),
                delta.pendingIdentities());
            assertEquals(Set.of(), delta.removed());
            assertTrue(!delta.requiresFullNativeComparison());
        } finally {
            Files.deleteIfExists(before);
            Files.deleteIfExists(after);
        }
    }

    @Test
    public void configurationAndLanguagesTriggerFullWithoutBeingDropped()
            throws Exception {
        Path before = inventory("""
            <Metadata name="Configuration" id="config" configVersion="a"/>
            <Metadata name="Language.Русский" id="lang" configVersion="a"/>
            <Metadata name="Document.Order" id="doc" configVersion="a"/>
            """);
        Path after = inventory("""
            <Metadata name="Configuration" id="config" configVersion="b"/>
            <Metadata name="Language.Русский" id="lang" configVersion="b"/>
            <Metadata name="Document.Order" id="doc" configVersion="a"/>
            """);
        try {
            var delta = RemoteReceiveScopePlanner.discover(before, after, false);
            assertEquals(Set.of("Configuration", "Language.Русский"),
                delta.pendingIdentities());
            assertTrue(delta.requiresFullNativeComparison());
        } finally {
            Files.deleteIfExists(before);
            Files.deleteIfExists(after);
        }
    }

    @Test
    public void renameIsAFullNativeComparisonNotAnOrdinaryModify()
            throws Exception {
        Path before = inventory("""
            <Metadata name="Document.Old" id="same" configVersion="1"/>
            """);
        Path after = inventory("""
            <Metadata name="Document.New" id="same" configVersion="2"/>
            """);
        try {
            var delta = RemoteReceiveScopePlanner.discover(before, after, false);
            assertEquals(Set.of("Document.Old", "Document.New"),
                delta.pendingIdentities());
            assertEquals(Set.of("Document.Old"), delta.removed());
            assertEquals(Set.of("Document.Old -> Document.New"), delta.renamed());
            assertTrue(delta.requiresFullNativeComparison());
        } finally {
            Files.deleteIfExists(before);
            Files.deleteIfExists(after);
        }
    }

    @Test
    public void sameNameDifferentUuidRequiresFullMetadataComparison()
            throws Exception {
        Path before = inventory("""
            <Metadata name="Document.Order" id="old-id" configVersion="1"/>
            """);
        Path after = inventory("""
            <Metadata name="Document.Order" id="new-id" configVersion="1"/>
            """);
        try {
            var delta = RemoteReceiveScopePlanner.discover(before, after, false);
            assertEquals(Set.of("Document.Order"), delta.pendingIdentities());
            assertEquals(Set.of("Document.Order"), delta.replaced());
            assertTrue(delta.requiresFullNativeComparison());
        } finally {
            Files.deleteIfExists(before);
            Files.deleteIfExists(after);
        }
    }

}