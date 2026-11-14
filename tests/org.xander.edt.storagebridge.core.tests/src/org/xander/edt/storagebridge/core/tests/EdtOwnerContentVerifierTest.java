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
            assertTrue(mismatch.contains("первое отличие, байт"));
            assertTrue(mismatch.contains("источник"));
            assertTrue(mismatch.contains("EDT"));
        } finally {
            remove(project); remove(stage);
        }
    }

    @Test
    public void deletedNestedFormIsVerifiedOnlyWhenEntireParentMatches()
            throws Exception {
        Path project = Files.createTempDirectory("edt-deleted-form-live-");
        Path stage = Files.createTempDirectory("edt-deleted-form-remote-");
        try {
            for (Path root : new Path[]{project, stage}) {
                Files.createDirectories(root.resolve("src/Documents/Order"));
                Files.writeString(root.resolve(
                    "src/Documents/Order/Order.mdo"), "same");
            }
            // The repository removed a form: no form exists on the
            // imported right side. A left-side form still exists.
            Path leftover = Files.createDirectories(project.resolve(
                "src/Documents/Order/Forms/Removed"));
            Files.writeString(leftover.resolve("Form.form"), "local");
            assertFalse(EdtOwnerContentVerifier.isExactlyApplied(
                project, stage, "Documents/Order"));
            remove(project.resolve("src/Documents/Order/Forms"));
            assertTrue(EdtOwnerContentVerifier.isExactlyApplied(
                project, stage, "Documents/Order"));
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

    @Test
    public void deletedTopLevelOwnerMustBeAbsentOnBothSides()
            throws Exception {
        Path project = Files.createTempDirectory("edt-delete-local-");
        Path stage = Files.createTempDirectory("edt-delete-stage-");
        try {
            Files.createDirectories(project.resolve("src/Documents/Old"));
            Files.writeString(project.resolve("src/Documents/Old/Old.mdo"), "old");
            Files.createDirectories(stage.resolve("src"));
            assertFalse(EdtOwnerContentVerifier.isTopLevelDeletionApplied(
                project, stage, "Documents/Old"));
            remove(project.resolve("src/Documents/Old"));
            assertTrue(EdtOwnerContentVerifier.isTopLevelDeletionApplied(
                project, stage, "Documents/Old"));
            Files.createDirectories(stage.resolve("src/Documents"));
            Files.writeString(stage.resolve("src/Documents/Old.mdo"), "incoming");
            assertFalse(EdtOwnerContentVerifier.isTopLevelDeletionApplied(
                project, stage, "Documents/Old"));
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
