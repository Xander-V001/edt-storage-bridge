package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.Test;
import org.xander.edt.storagebridge.core.EdtOwnerContentVerifier;

public final class EdtOwnerContentVerifierTest {
    @Test
    public void rootIsOnlyVerifiedWhenMetadataAndAllSiblingResourcesMatch()
            throws Exception {
        Path project = Files.createTempDirectory("edt-owner-active-");
        Path stage = Files.createTempDirectory("edt-owner-stage-");
        try {
            Files.createDirectories(project.resolve("src/Configuration"));
            Files.createDirectories(stage.resolve("src/Configuration"));
            Files.writeString(project.resolve("src/Configuration/Configuration.mdo"), "same");
            Files.writeString(stage.resolve("src/Configuration/Configuration.mdo"), "same");
            Files.writeString(project.resolve("src/Configuration/ManagedApplicationModule.bsl"), "local");
            Files.writeString(stage.resolve("src/Configuration/ManagedApplicationModule.bsl"), "remote");
            assertFalse(EdtOwnerContentVerifier.isExactlyApplied(
                project, stage, "Configuration/Configuration"));
            Files.writeString(stage.resolve("src/Configuration/ManagedApplicationModule.bsl"), "local");
            assertTrue(EdtOwnerContentVerifier.isExactlyApplied(
                project, stage, "Configuration/Configuration"));
            Files.writeString(project.resolve("src/Configuration/Extra.xml"), "not staged");
            assertFalse(EdtOwnerContentVerifier.isExactlyApplied(
                project, stage, "Configuration/Configuration"));
        } finally {
            remove(project); remove(stage);
        }
    }

    @Test
    public void nestedFormMismatchPreventsMarkingOwningDocumentAccepted()
            throws Exception {
        Path project = Files.createTempDirectory("edt-owner-active-");
        Path stage = Files.createTempDirectory("edt-owner-stage-");
        try {
            for (Path root : new Path[]{project, stage}) {
                Path doc = Files.createDirectories(
                    root.resolve("src/Documents/Order/Forms/Main"));
                Files.writeString(root.resolve("src/Documents/Order/Order.mdo"), "same");
                Files.writeString(doc.resolve("Form.form"), "same");
            }
            assertTrue(EdtOwnerContentVerifier.isExactlyApplied(
                project, stage, "Documents/Order"));
            Files.writeString(project.resolve(
                "src/Documents/Order/Forms/Main/Module.bsl"), "local");
            assertFalse(EdtOwnerContentVerifier.isExactlyApplied(
                project, stage, "Documents/Order"));
        } finally {
            remove(project); remove(stage);
        }
    }

    @Test
    public void diagnosticsPinpointDifferentFormResourceWithoutClearingAnything()
            throws Exception {
        Path project = Files.createTempDirectory("edt-owner-active-");
        Path stage = Files.createTempDirectory("edt-owner-stage-");
        try {
            for (Path root : new Path[]{project, stage}) {
                Path forms = Files.createDirectories(
                    root.resolve("src/Documents/Order/Forms/Main"));
                Files.writeString(root.resolve("src/Documents/Order/Order.mdo"),
                    "same");
                Files.writeString(forms.resolve("Form.form"), "old command");
            }
            Files.writeString(stage.resolve(
                "src/Documents/Order/Forms/Main/Form.form"), "new command");
            assertFalse(EdtOwnerContentVerifier.isExactlyApplied(
                project, stage, "Documents/Order"));
            String mismatch = EdtOwnerContentVerifier.firstMismatch(
                project, stage, "Documents/Order");
            assertTrue(mismatch.contains("различается содержимое"));
            assertTrue(mismatch.contains("Form.form"));
        } finally {
            remove(project); remove(stage);
        }
    }

    @Test
    public void diagnosticsNeverTreatMissingStageDescriptorAsApplied()
            throws Exception {
        Path project = Files.createTempDirectory("edt-owner-active-");
        Path stage = Files.createTempDirectory("edt-owner-stage-");
        try {
            assertFalse(EdtOwnerContentVerifier.isExactlyApplied(
                project, stage, "Documents/Order"));
            assertTrue(EdtOwnerContentVerifier.firstMismatch(
                project, stage, "Documents/Order")
                .contains("основной .mdo отсутствует"));
        } finally {
            remove(project); remove(stage);
        }
    }

    private static void remove(Path root) throws Exception {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
