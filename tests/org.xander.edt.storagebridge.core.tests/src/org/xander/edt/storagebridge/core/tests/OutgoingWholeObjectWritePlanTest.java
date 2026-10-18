package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.xander.edt.storagebridge.core.OutgoingDeltaScanner;
import org.xander.edt.storagebridge.core.OutgoingMetadataObjectPlan;
import org.xander.edt.storagebridge.core.OutgoingWholeObjectWritePlan;

public final class OutgoingWholeObjectWritePlanTest {
    @Test
    public void supportsStructuresFormsRightsRegistersAndTemplatesTogether()
            throws Exception {
        var changes = List.of(
            modified("src/Documents/Order/Order.mdo", "Documents/Order"),
            modified("src/Documents/Order/Forms/Main/Form.form", "Documents/Order"),
            modified("src/Roles/Manager/Rights.xml", "Roles/Manager"),
            modified("src/AccumulationRegisters/Balance/Balance.mdo",
                "AccumulationRegisters/Balance"),
            modified("src/CommonTemplates/Print/Print.mdo",
                "CommonTemplates/Print"));
        var plan = selected(changes);
        var publish = OutgoingWholeObjectWritePlan.qualify(plan,
            List.of("Documents/Order", "Roles/Manager",
                "AccumulationRegisters/Balance", "CommonTemplates/Print"),
            List.of("Document.Order", "Role.Manager",
                "AccumulationRegister.Balance", "CommonTemplate.Print"),
            List.of("Documents/Order.xml", "Documents/Order/Forms/Main.xml",
                "Roles/Manager.xml", "Roles/Manager/Ext/Rights.xml",
                "AccumulationRegisters/Balance.xml",
                "CommonTemplates/Print.xml", "CommonTemplates/Print/Ext/Template.mxl"));
        assertEquals(7, publish.loadFiles().size());
        Path scopeDir = Files.createTempDirectory("edt-object-scopes-");
        try {
            Path lockList = scopeDir.resolve("selected-objects.xml");
            Path loadList = scopeDir.resolve("selected-files.txt");
            publish.writeScopes(lockList, loadList);
            String locks = Files.readString(lockList);
            assertTrue(locks.contains("includeChildObjects=\"true\""));
            assertTrue(locks.contains("fullName=\"AccumulationRegister.Balance\""));
            byte[] bytes = Files.readAllBytes(loadList);
            assertEquals((byte) 0xEF, bytes[0]);
            assertEquals((byte) 0xBB, bytes[1]);
            assertEquals((byte) 0xBF, bytes[2]);
            String list = new String(bytes,
                java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(list.contains("Documents/Order/Forms/Main.xml"));
            assertTrue(list.contains("Roles/Manager/Ext/Rights.xml"));
        } finally {
            try (var paths = Files.walk(scopeDir)) {
                for (Path file : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(file);
            }
        }
        assertEquals(Set.of("Document.Order", "Role.Manager",
            "AccumulationRegister.Balance", "CommonTemplate.Print"),
            publish.classicOwners());
    }

    @Test
    public void rejectsMissingOwnerAndOutOfScopeNativeArtifacts() throws Exception {
        var plan = selected(List.of(
            modified("src/Catalogs/People/People.mdo", "Catalogs/People")));
        assertThrows(IOException.class, () ->
            OutgoingWholeObjectWritePlan.qualify(plan,
                List.of("Catalogs/People"), List.of("Catalog.People"),
                List.of("Catalogs/People/Ext/ObjectModule.bsl")));
        assertThrows(IOException.class, () ->
            OutgoingWholeObjectWritePlan.qualify(plan,
                List.of("Catalogs/People"), List.of("Catalog.People"),
                List.of("Catalogs/People.xml", "Documents/Unrelated.xml")));
    }

    @Test
    public void rejectsNewAndRemovedObjectsForUnsafeBlindWrite() throws Exception {
        var newObject = selected(List.of(new OutgoingDeltaScanner.Change(
            Path.of("src/Documents/New/New.mdo"), "Documents/New",
            OutgoingDeltaScanner.Kind.ADDED)));
        assertThrows(IOException.class, () ->
            OutgoingWholeObjectWritePlan.qualify(newObject,
                List.of("Documents/New"), List.of("Document.New"),
                List.of("Documents/New.xml")));
        var removed = selected(List.of(new OutgoingDeltaScanner.Change(
            Path.of("src/Catalogs/Old/Old.mdo"), "Catalogs/Old",
            OutgoingDeltaScanner.Kind.DELETED)));
        assertThrows(IOException.class, () ->
            OutgoingWholeObjectWritePlan.qualify(removed,
                List.of("Catalogs/Old"), List.of("Catalog.Old"),
                List.of("Catalogs/Old.xml")));
    }

    private static OutgoingDeltaScanner.Change modified(String file, String owner) {
        return new OutgoingDeltaScanner.Change(Path.of(file), owner,
            OutgoingDeltaScanner.Kind.MODIFIED);
    }
    private static OutgoingMetadataObjectPlan selected(
            List<OutgoingDeltaScanner.Change> files) throws IOException {
        return OutgoingMetadataObjectPlan.fromDelta(
            new OutgoingDeltaScanner.Delta(files,
                files.stream().map(OutgoingDeltaScanner.Change::edtOwner)
                    .collect(java.util.stream.Collectors.toSet())));
    }
}
