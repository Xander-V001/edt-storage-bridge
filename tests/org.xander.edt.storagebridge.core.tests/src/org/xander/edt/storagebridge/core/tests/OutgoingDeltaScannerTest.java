package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.Test;
import org.xander.edt.storagebridge.core.OutgoingDeltaScanner;

public final class OutgoingDeltaScannerTest {
    @Test
    public void outgoingPreviewTracksModificationsAdditionsAndDeletions()
            throws Exception {
        Path remote = Files.createTempDirectory("outgoing-baseline-");
        Path active = Files.createTempDirectory("outgoing-project-");
        try {
            file(remote, "src/Documents/Order/Order.mdo", "same");
            file(active, "src/Documents/Order/Order.mdo", "same");
            file(remote, "src/Documents/Order/ManagerModule.bsl", "before");
            file(active, "src/Documents/Order/ManagerModule.bsl", "after");
            file(remote, "src/CommonModules/Unused/Module.bsl", "removed");
            file(active, "src/Catalogs/Products/Products.mdo", "new metadata");
            var delta = OutgoingDeltaScanner.compare(remote, active);
            assertEquals(3, delta.files().size());
            assertEquals(Set.of("Documents/Order",
                "CommonModules/Unused", "Catalogs/Products"),
                delta.affectedEdtOwners());
            assertTrue(delta.files().stream().anyMatch(x ->
                x.kind() == OutgoingDeltaScanner.Kind.MODIFIED
                && x.relativePath().toString().endsWith("ManagerModule.bsl")));
            assertTrue(delta.files().stream().anyMatch(x ->
                x.kind() == OutgoingDeltaScanner.Kind.DELETED));
            assertTrue(delta.files().stream().anyMatch(x ->
                x.kind() == OutgoingDeltaScanner.Kind.ADDED));
        } finally { remove(remote); remove(active); }
    }

    @Test
    public void sameContentsWithNoDiffAreNotSelectedForCommit()
            throws Exception {
        Path remote = Files.createTempDirectory("outgoing-baseline-");
        Path active = Files.createTempDirectory("outgoing-project-");
        try {
            file(remote, "src/CommonModules/Util/Module.bsl", "same");
            file(active, "src/CommonModules/Util/Module.bsl", "same");
            var delta = OutgoingDeltaScanner.compare(remote, active);
            assertTrue(delta.files().isEmpty());
            assertTrue(delta.affectedEdtOwners().isEmpty());
        } finally { remove(remote); remove(active); }
    }

    @Test
    public void unexpectedFileNeverDisappearsSilentlyFromOutgoingDiff()
            throws Exception {
        Path remote = Files.createTempDirectory("outgoing-baseline-");
        Path active = Files.createTempDirectory("outgoing-project-");
        try {
            Files.createDirectories(remote.resolve("src"));
            file(active, "src/Unknown.txt", "not metadata");
            assertThrows(IOException.class,
                () -> OutgoingDeltaScanner.compare(remote, active));
        } finally { remove(remote); remove(active); }
    }

    @Test
    public void cannotCompareProjectToItsOwnChildAsBaseline()
            throws Exception {
        Path active = Files.createTempDirectory("outgoing-project-");
        try {
            Path child = Files.createDirectories(active.resolve("baseline/src"));
            Files.createDirectories(active.resolve("src"));
            assertThrows(IOException.class, () ->
                OutgoingDeltaScanner.compare(child.getParent(), active));
        } finally { remove(active); }
    }

    private static void file(Path root, String relative, String value)
            throws IOException {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, value);
    }

    private static void remove(Path root) throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path item : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}
