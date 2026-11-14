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
        assertEquals(4, publish.loadFiles().size());
        Path scopeDir = Files.createTempDirectory("edt-object-scopes-");
        try {
            Path lockList = scopeDir.resolve("lock-objects.xml");
            Path commitList = scopeDir.resolve("commit-objects.xml");
            Path loadList = scopeDir.resolve("selected-files.txt");
            publish.writeScopes(lockList, commitList, loadList);
            String locks = Files.readString(lockList);
            assertTrue(locks.contains("includeChildObjects=\"true\""));
            assertTrue(locks.contains("fullName=\"AccumulationRegister.Balance\""));
            byte[] bytes = Files.readAllBytes(loadList);
            assertEquals((byte) 0xEF, bytes[0]);
            assertEquals((byte) 0xBB, bytes[1]);
            assertEquals((byte) 0xBF, bytes[2]);
            String list = new String(bytes,
                java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(list.contains("Documents/Order.xml"));
            assertTrue(list.contains("Roles/Manager.xml"));
            assertFalse(list.contains("Documents/Order/Forms/Main.xml"));
            assertFalse(list.contains("Roles/Manager/Ext/Rights.xml"));
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
    public void objectModuleChangeLoadsWholeCatalogByItsRootXmlOnly()
            throws Exception {
        var plan = selected(List.of(modified(
            "src/Catalogs/Products/Ext/ObjectModule.bsl",
            "Catalogs/Products")));
        var publish = OutgoingWholeObjectWritePlan.qualify(plan,
            List.of("Catalogs/Products"), List.of("Catalog.Products"),
            List.of("Catalogs/Products.xml",
                "Catalogs/Products/Ext/ObjectModule.bsl",
                "Catalogs/Products/Forms/List.xml",
                "Catalogs/Products/Forms/List/Ext/Form.xml"));
        assertEquals(List.of("Catalogs/Products.xml"), publish.loadFiles());
        assertEquals(Set.of("Catalog.Products"), publish.classicOwners());
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
    public void addedOwnerImportsItAndConfigurationParent() throws Exception {
        var newObject = selected(List.of(new OutgoingDeltaScanner.Change(
            Path.of("src/Documents/New/New.mdo"), "Documents/New",
            OutgoingDeltaScanner.Kind.ADDED)));
        var publish = OutgoingWholeObjectWritePlan.qualify(newObject,
            List.of("Configuration/Configuration", "Documents/New"),
            List.of("Configuration", "Document.New"),
            List.of("Configuration.xml", "Documents/New.xml"));
        assertEquals(List.of("Configuration.xml", "Documents/New.xml"),
            publish.loadFiles());
        assertEquals(Set.of("Configuration"), publish.lockOwners());
        assertEquals(Set.of("Configuration", "Document.New"),
            publish.classicOwners());
        Path root = Files.createTempDirectory("new-metadata-scope-");
        try {
            Path lock = root.resolve("lock.xml");
            Path commit = root.resolve("commit.xml");
            Path files = root.resolve("files.txt");
            publish.writeScopes(lock, commit, files);
            assertFalse(Files.readString(lock).contains("Document.New"));
            assertTrue(Files.readString(commit).contains("Document.New"));
            assertTrue(Files.readString(lock).contains(
                "<Configuration includeChildObjects=\"false\"/>"));
        } finally {
            try (var walk = Files.walk(root)) {
                for (var path : walk.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }

    @Test
    public void deletedOwnerLoadsConfigurationParentWithoutDeletedXml()
            throws Exception {
        var removed = selected(List.of(new OutgoingDeltaScanner.Change(
            Path.of("src/Catalogs/Old/Old.mdo"), "Catalogs/Old",
            OutgoingDeltaScanner.Kind.DELETED)));
        var publish = OutgoingWholeObjectWritePlan.qualify(removed,
            List.of("Configuration/Configuration"),
            List.of("Configuration"), List.of("Configuration.xml"));
        assertEquals(List.of("Configuration.xml"), publish.loadFiles());
        assertTrue(publish.capturesAllRootChildren());
        Path root = Files.createTempDirectory("removed-metadata-scope-");
        try {
            Path lock = root.resolve("lock.xml");
            Path commit = root.resolve("commit.xml");
            publish.writeScopes(lock, commit, root.resolve("files.txt"));
            assertTrue(Files.readString(lock).contains(
                "<Configuration includeChildObjects=\"true\"/>"));
            assertTrue(Files.readString(commit).contains(
                "<Configuration includeChildObjects=\"true\"/>"));
        } finally {
            try (var walk = Files.walk(root)) {
                for (var path : walk.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }

    @Test
    public void modifiedConfigurationRootLoadsNativeConfigurationXml()
            throws Exception {
        var plan = selected(List.of(modified(
            "src/Configuration/Configuration.mdo",
            "Configuration/Configuration")));
        var publish = OutgoingWholeObjectWritePlan.qualify(plan,
            List.of("Configuration/Configuration"),
            List.of("Configuration"),
            List.of("Configuration.xml"));
        assertEquals(List.of("Configuration.xml"), publish.loadFiles());
        assertFalse(publish.capturesAllRootChildren());
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
