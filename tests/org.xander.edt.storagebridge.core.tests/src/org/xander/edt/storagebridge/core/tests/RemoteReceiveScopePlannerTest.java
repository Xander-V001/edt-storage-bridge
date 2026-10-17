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
            assertThrows(IOException.class,
                () -> RemoteReceiveScopePlanner.candidates(old, next, false));
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
}
