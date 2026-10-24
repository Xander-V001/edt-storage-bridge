package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.Test;
import org.xander.edt.storagebridge.core.EdtOwnerContentVerifier;
import org.xander.edt.storagebridge.core.EdtSelectedResourceVerifier;

public final class EdtSelectedResourceVerifierTest {
    @Test
    public void managerModuleIsVerifiedEvenIfTheDocumentFormStillDiffers()
            throws Exception {
        Path live = Files.createTempDirectory("edt-partial-live-");
        Path stage = Files.createTempDirectory("edt-partial-stage-");
        try {
            create(live, "new module", "locally merged form");
            create(stage, "new module", "source form");
            assertFalse(EdtOwnerContentVerifier.isExactlyApplied(
                live, stage, "Documents/НачислениеПоСреднему"));
            assertTrue(EdtSelectedResourceVerifier.isIndependentlyApplied(
                live, stage, "Documents/НачислениеПоСреднему",
                "Document.НачислениеПоСреднему.ManagerModule"));
            assertFalse(EdtSelectedResourceVerifier.isIndependentlyApplied(
                live, stage, "Documents/НачислениеПоСреднему",
                "Document.НачислениеПоСреднему.Form.ФормаДокумента"));
            assertFalse(EdtSelectedResourceVerifier.isIndependentlyApplied(
                live, stage, "Documents/НачислениеПоСреднему",
                "Document.НачислениеПоСреднему"));
        } finally { remove(live); remove(stage); }
    }

    @Test
    public void formCanBeVerifiedSeparatelyButEveryFormResourceMustMatch()
            throws Exception {
        Path live = Files.createTempDirectory("edt-form-live-");
        Path stage = Files.createTempDirectory("edt-form-stage-");
        try {
            create(live, "old manager", "new form");
            create(stage, "new manager", "new form");
            assertTrue(EdtSelectedResourceVerifier.isIndependentlyApplied(
                live, stage, "Documents/НачислениеПоСреднему",
                "Document.НачислениеПоСреднему.Form.ФормаДокумента"));
            assertFalse(EdtSelectedResourceVerifier.isIndependentlyApplied(
                live, stage, "Documents/НачислениеПоСреднему",
                "Document.НачислениеПоСреднему.ManagerModule"));
            Files.writeString(live.resolve(
                "src/Documents/НачислениеПоСреднему/Forms/ФормаДокумента/Module.bsl"),
                "local handler");
            assertFalse(EdtSelectedResourceVerifier.isIndependentlyApplied(
                live, stage, "Documents/НачислениеПоСреднему",
                "Document.НачислениеПоСреднему.Form.ФормаДокумента.Form.Module"));
        } finally { remove(live); remove(stage); }
    }

    @Test
    public void missingAndUnmappedFilesNeverClearPendingRecords()
            throws Exception {
        Path live = Files.createTempDirectory("edt-missing-live-");
        Path stage = Files.createTempDirectory("edt-missing-stage-");
        try {
            create(live, "old manager", "new form");
            create(stage, "old manager", "new form");
            assertFalse(EdtSelectedResourceVerifier.isIndependentlyApplied(
                live, stage, "Documents/НачислениеПоСреднему",
                "Document.Другое.ManagerModule"));
            assertFalse(EdtSelectedResourceVerifier.isIndependentlyApplied(
                live, stage, "Documents/НачислениеПоСреднему",
                "Document.НачислениеПоСреднему.Attribute.Количество"));
            assertFalse(EdtSelectedResourceVerifier.isIndependentlyApplied(
                live, stage, "Documents/НачислениеПоСреднему",
                "Document.НачислениеПоСреднему.Form.ФормаПомощника"));
            // Different owner primary descriptor could even mean another
            // metadata UUID with the same human-readable name.
            Files.writeString(stage.resolve(
                "src/Documents/НачислениеПоСреднему/НачислениеПоСреднему.mdo"),
                "different metadata identity");
            assertFalse(EdtSelectedResourceVerifier.isIndependentlyApplied(
                live, stage, "Documents/НачислениеПоСреднему",
                "Document.НачислениеПоСреднему.ManagerModule"));
            Files.writeString(stage.resolve(
                "src/Documents/НачислениеПоСреднему/НачислениеПоСреднему.mdo"),
                "document metadata");
            Files.delete(stage.resolve(
                "src/Documents/НачислениеПоСреднему/ManagerModule.bsl"));
            assertFalse(EdtSelectedResourceVerifier.isIndependentlyApplied(
                live, stage, "Documents/НачислениеПоСреднему",
                "Document.НачислениеПоСреднему.ManagerModule"));
        } finally { remove(live); remove(stage); }
    }

    @Test
    public void resolvesAnyStagedTopLevelBslWithoutTypeSpecificModuleAllowlist()
            throws Exception {
        // All types go through the EXACT SAME code branch, with no Java
        // enumeration of 1C module kinds or metadata owner categories.
        String[][] cases = {
            {"Documents", "Document", "Order", "ObjectModule"},
            {"Documents", "Document", "Order", "ManagerModule"},
            {"Catalogs", "Catalog", "Products", "ManagerModule"},
            {"Catalogs", "Catalog", "Products", "ObjectModule"},
            {"CommonModules", "CommonModule", "Utilities", "Module"},
            {"InformationRegisters", "InformationRegister", "Costs", "RecordSetModule"},
            {"DataProcessors", "DataProcessor", "Load", "ObjectModule"},
            {"CommonCommands", "CommonCommand", "OpenPanel", "CommandModule"}
        };
        for (String[] entry : cases) {
            Path live = Files.createTempDirectory("edt-any-module-live-");
            Path stage = Files.createTempDirectory("edt-any-module-stage-");
            try {
                String collection = entry[0], type = entry[1],
                    name = entry[2], module = entry[3];
                Path root = Path.of("src").resolve(collection).resolve(name);
                for (Path source : new Path[] {live, stage}) {
                    Path owner = Files.createDirectories(source.resolve(root));
                    Files.writeString(owner.resolve(name + ".mdo"), "same-id");
                    Files.writeString(owner.resolve(module + ".bsl"), "remote module");
                }
                String ownerId = collection + "/" + name;
                String versionedName = type + "." + name + "." + module;
                assertTrue("Should resolve " + versionedName,
                    EdtSelectedResourceVerifier.isIndependentlyApplied(
                        live, stage, ownerId, versionedName));
                Files.writeString(live.resolve(root).resolve(module + ".bsl"),
                    "local module");
                assertFalse("Should reject unequal " + versionedName,
                    EdtSelectedResourceVerifier.isIndependentlyApplied(
                        live, stage, ownerId, versionedName));
                Files.delete(live.resolve(root).resolve(module + ".bsl"));
                assertFalse("Should reject missing " + versionedName,
                    EdtSelectedResourceVerifier.isIndependentlyApplied(
                        live, stage, ownerId, versionedName));
            } finally { remove(live); remove(stage); }
        }
    }

    @Test
    public void standaloneCommonModuleCanBeAcknowledgedAsCompleteOwner()
            throws Exception {
        Path live = Files.createTempDirectory("edt-common-live-");
        Path stage = Files.createTempDirectory("edt-common-stage-");
        try {
            for (Path root : new Path[] {live, stage}) {
                Path module = Files.createDirectories(
                    root.resolve("src/CommonModules/Utilities"));
                Files.writeString(module.resolve("Utilities.mdo"), "same-id");
                Files.writeString(module.resolve("Module.bsl"), "new source");
            }
            assertTrue(EdtOwnerContentVerifier.isExactlyApplied(
                live, stage, "CommonModules/Utilities"));
            // CommonModule.X is a first-class top-level owner identity,
            // NOT a named subresource that must be resolved via a suffix.
            assertFalse(EdtSelectedResourceVerifier.isIndependentlyApplied(
                live, stage, "CommonModules/Utilities",
                "CommonModule.Utilities"));
            Files.writeString(live.resolve(
                "src/CommonModules/Utilities/Module.bsl"), "old source");
            assertFalse(EdtOwnerContentVerifier.isExactlyApplied(
                live, stage, "CommonModules/Utilities"));
        } finally { remove(live); remove(stage); }
    }

    private static void create(Path root, String module, String form) throws Exception {
        Path document = Files.createDirectories(
            root.resolve("src/Documents/НачислениеПоСреднему"));
        Files.writeString(document.resolve("НачислениеПоСреднему.mdo"),
            "document metadata");
        Files.writeString(document.resolve("ManagerModule.bsl"), module);
        Path formDir = Files.createDirectories(
            document.resolve("Forms/ФормаДокумента"));
        Files.writeString(formDir.resolve("Form.form"), form);
        Files.writeString(formDir.resolve("Module.bsl"), "same handler");
    }

    private static void remove(Path root) throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
