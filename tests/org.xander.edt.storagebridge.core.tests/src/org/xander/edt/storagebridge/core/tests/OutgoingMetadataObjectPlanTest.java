package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import org.junit.Test;
import org.xander.edt.storagebridge.core.OutgoingDeltaScanner;
import org.xander.edt.storagebridge.core.OutgoingMetadataObjectPlan;

public final class OutgoingMetadataObjectPlanTest {
    @Test
    public void groupsDifferentMetadataTypesWithoutBslWhitelist() throws Exception {
        Path old = Files.createTempDirectory("storage-original-");
        Path edt = Files.createTempDirectory("storage-active-");
        try {
            changed(old, edt, "src/Documents/Order/Order.mdo", "before", "after");
            changed(old, edt, "src/Documents/Order/Forms/Editor/Form.form", "old", "new");
            changed(old, edt, "src/AccumulationRegisters/Balance/Balance.mdo", "old", "new");
            changed(old, edt, "src/Roles/Manager/Rights.rights", "old", "new");
            changed(old, edt, "src/CommonTemplates/Print/Template.mxl", "old", "new");
            changed(old, edt, "src/HTTPServices/Api/Api.mdo", "old", "new");
            var plan = OutgoingMetadataObjectPlan.fromDelta(
                OutgoingDeltaScanner.compare(old, edt));
            assertEquals(5, plan.owners().size());
            assertEquals(6, plan.changedResourceCount());
            assertTrue(plan.exportableOwners().containsAll(Set.of(
                "Documents/Order", "AccumulationRegisters/Balance",
                "Roles/Manager", "CommonTemplates/Print",
                "HTTPServices/Api")));
            assertTrue(plan.owners().get("Documents/Order").descriptorChanged());
            assertFalse(plan.owners().get("Roles/Manager").descriptorChanged());
        } finally {
            delete(old);
            delete(edt);
        }
    }

    @Test
    public void removedWholeMetadataObjectCannotSilentlyBeExported() throws Exception {
        Path old = Files.createTempDirectory("storage-original-");
        Path edt = Files.createTempDirectory("storage-active-");
        try {
            write(old, "src/Catalogs/Obsolete/Obsolete.mdo", "old");
            write(old, "src/Catalogs/Obsolete/Ext/ObjectModule.bsl", "code");
            write(edt, "src/Documents/New/New.mdo", "new");
            var plan = OutgoingMetadataObjectPlan.fromDelta(
                OutgoingDeltaScanner.compare(old, edt));
            assertEquals(Set.of("Catalogs/Obsolete"),
                Set.copyOf(plan.removedOwners()));
            assertEquals(Set.of("Configuration/Configuration", "Documents/New"),
                Set.copyOf(plan.exportableOwners()));
            assertTrue(plan.removesTopLevelOwners());
            assertTrue(plan.owners().get("Documents/New").added());
        } finally {
            delete(old);
            delete(edt);
        }
    }

    @Test
    public void rootConfigurationOwnerUsesConsistentName() throws Exception {
        Path old = Files.createTempDirectory("storage-original-");
        Path edt = Files.createTempDirectory("storage-active-");
        try {
            changed(old, edt, "src/Configuration/Configuration.mdo", "old", "new");
            var plan = OutgoingMetadataObjectPlan.fromDelta(
                OutgoingDeltaScanner.compare(old, edt));
            assertEquals(Set.of("Configuration/Configuration"),
                plan.owners().keySet());
        } finally {
            delete(old);
            delete(edt);
        }
    }

    private static void changed(Path before, Path after, String path,
            String left, String right) throws IOException {
        write(before, path, left);
        write(after, path, right);
    }

    private static void write(Path root, String path, String content)
            throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void delete(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for (var path : stream.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }
}
