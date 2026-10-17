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
            Files.delete(stage.resolve(
                "src/Documents/НачислениеПоСреднему/ManagerModule.bsl"));
            assertFalse(EdtSelectedResourceVerifier.isIndependentlyApplied(
                live, stage, "Documents/НачислениеПоСреднему",
                "Document.НачислениеПоСреднему.ManagerModule"));
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
