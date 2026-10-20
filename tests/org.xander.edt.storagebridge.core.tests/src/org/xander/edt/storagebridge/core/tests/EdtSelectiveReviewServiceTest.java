package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;
import org.xander.edt.storagebridge.edt.EdtSelectiveReviewService;

/** Exercise the same owner mapping that the native EDT comparison scope uses. */
public class EdtSelectiveReviewServiceTest {
    @Test
    public void nativeImportFailureKeepsRealStatusInLocalDiagnosticOnly()
            throws Exception {
        var status = new org.eclipse.core.runtime.MultiStatus("edt-native", 77,
            "Native importer failed", null);
        status.add(new org.eclipse.core.runtime.Status(
            org.eclipse.core.runtime.IStatus.ERROR, "edt-native-child", 43,
            "Some temporary XML resource could not be imported", null));
        var failed = EdtSelectiveReviewService.NativeEdtImportFailure.from(
            status, false);
        assertEquals(org.eclipse.core.runtime.IStatus.ERROR, failed.severity());
        assertEquals(77, failed.code());
        assertEquals(1, failed.descendants());
        org.junit.Assert.assertFalse(failed.fullReview());
        Path report = failed.diagnosticsFile();
        org.junit.Assert.assertNotNull(report);
        try {
            String text = Files.readString(report);
            org.junit.Assert.assertTrue(text.contains("edt-native-child"));
            org.junit.Assert.assertTrue(text.contains("43"));
            org.junit.Assert.assertFalse(failed.getMessage().contains(
                "temporary XML resource"));
        } finally {
            Files.deleteIfExists(report);
        }
    }

    @Test
    public void configurationRootAndItsSeparatelyVersionedModulesHaveOneEdtOwner()
            throws Exception {
        assertEquals("Configuration/Configuration",
            EdtSelectiveReviewService.edtOwner("Configuration"));
        assertEquals("Configuration/Configuration",
            EdtSelectiveReviewService.edtOwner("Configuration.Зарплата"));
        assertEquals("Configuration/Configuration",
            EdtSelectiveReviewService.edtOwner(
                "Configuration.Зарплата.SessionModule"));
        assertEquals("Configuration/Configuration",
            EdtSelectiveReviewService.edtOwner(
                "Configuration.Зарплата.ManagedApplicationModule"));
    }

    @Test
    public void languageIsEmbeddedInConfigurationMdoNotSeparateLanguagesMdo()
            throws Exception {
        assertEquals("Configuration/Configuration",
            EdtSelectiveReviewService.edtOwner("Language.Русский"));
        assertEquals("Configuration/Configuration",
            EdtSelectiveReviewService.edtOwner("Language.English"));
        assertEquals("Configuration/Configuration",
            EdtSelectiveReviewService.edtOwner("Язык.Русский"));
        assertThrows(IOException.class,
            () -> EdtSelectiveReviewService.edtOwner("Language"));
    }

    @Test
    public void embeddedLanguagesMustReallyExistInConvertedRoot() throws Exception {
        Path root = Files.createTempDirectory("edt-language-check-");
        try {
            Path folder = Files.createDirectories(root.resolve("src/Configuration"));
            Path mdo = folder.resolve("Configuration.mdo");
            Files.writeString(mdo, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mdclass:Configuration xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
                  <name>Payroll</name>
                  <languages uuid="1"><name>Русский</name><languageCode>ru</languageCode></languages>
                  <languages uuid="2"><name>English</name><languageCode>en</languageCode></languages>
                </mdclass:Configuration>
                """);
            EdtSelectiveReviewService.requireEmbeddedLanguages(root, List.of(
                "Language.Русский", "Language.English"));
            var missing = assertThrows(
                EdtSelectiveReviewService.MissingEmbeddedLanguagesException.class,
                () -> EdtSelectiveReviewService.requireEmbeddedLanguages(
                    root, List.of("Language.Русский", "Language.French")));
            assertEquals(1, missing.missingCount());
            org.junit.Assert.assertFalse(missing.getMessage().contains("French"));
            Files.writeString(mdo, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE x [<!ENTITY external SYSTEM "file:///unavailable">]>
                <mdclass:Configuration xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass">
                    <languages><name>&external;</name></languages>
                </mdclass:Configuration>
                """);
            assertThrows(IOException.class, () ->
                EdtSelectiveReviewService.requireEmbeddedLanguages(
                    root, List.of("Language.Русский")));
        } finally {
            Files.deleteIfExists(root.resolve("src/Configuration/Configuration.mdo"));
            Files.deleteIfExists(root.resolve("src/Configuration"));
            Files.deleteIfExists(root.resolve("src"));
            Files.deleteIfExists(root);
        }
    }

    @Test
    public void formAndModuleChangesBelongToTheirParentMetadata()
            throws Exception {
        assertEquals("Documents/Документ1",
            EdtSelectiveReviewService.edtOwner(
                "Document.Документ1.Form.Основная.Form"));
        assertEquals("Catalogs/Номенклатура",
            EdtSelectiveReviewService.edtOwner(
                "Catalog.Номенклатура.ObjectModule"));
        assertEquals("CommonModules/Утилиты",
            EdtSelectiveReviewService.edtOwner(
                "CommonModule.Утилиты.Module"));
    }

    @Test
    public void incomingMergePreservesProcedureLevelDiffAndAllowsDeletions() {
        // Procedure-level native compare/merge is the user-requested
        // granularity; it cannot be disabled to mask a repeated delta.
        var settings = EdtSelectiveReviewService.incomingMergeSettings();
        org.junit.Assert.assertTrue(settings.isParseBslModuleStructure());
        org.junit.Assert.assertTrue(settings.isMainSideObjectsDeletionAllowed());
    }

    @Test
    public void nativeMergeScopeUsesOnlyPrimaryMetadataDescriptors() {
        assertEquals("Documents/Документ1",
            EdtSelectiveReviewService.metadataOwnerForPath(Path.of(
                "src/Documents/Документ1/Документ1.mdo")));
        assertEquals("Configuration/Configuration",
            EdtSelectiveReviewService.metadataOwnerForPath(Path.of(
                "src/Configuration/Configuration.mdo")));
        assertEquals("Constants/Флаг",
            EdtSelectiveReviewService.metadataOwnerForPath(Path.of(
                "src/Constants/Флаг.mdo")));
        assertNull(EdtSelectiveReviewService.metadataOwnerForPath(Path.of(
            "src/Documents/Документ1/Forms/Основная/Form.form")));
        assertNull(EdtSelectiveReviewService.metadataOwnerForPath(Path.of(
            "src/Documents/Документ1/Forms/Основная/Основная.mdo")));
        assertNull(EdtSelectiveReviewService.metadataOwnerForPath(Path.of(
            "src/Roles/Администратор/Rights.rights")));
        assertNull(EdtSelectiveReviewService.metadataOwnerForPath(Path.of(
            "src/CommonModules/Утилиты/Module.bsl")));
        assertNull(EdtSelectiveReviewService.metadataOwnerForPath(Path.of(
            "src/Documents/Документ1/Other.mdo")));
        assertNull(EdtSelectiveReviewService.metadataOwnerForPath(Path.of(
            "../other/src/Documents/Документ1.mdo")));
    }


    @Test
    public void fullReviewUsesTheOfficialGlobalScopeWithoutAnyExclusions() {
        var scope = EdtSelectiveReviewService.globalScope();
        // Use the published native empty-scope constant rather than
        // asserting internal accessors whose signatures differ by EDT SDK
        // version. Identity proves the full path does not construct its own
        // restricted object list.
        org.junit.Assert.assertSame(
            com._1c.g5.v8.dt.compare.core.ComparisonScope.EMPTY_SCOPE,
            scope);
    }

    @Test
    public void postMergeVerificationMustUseNestedEdtProjectRoot()
            throws Exception {
        Path stage = Files.createTempDirectory("edt-post-merge-root-");
        try {
            Path descriptor = stage.resolve(
                "edt/src/Documents/Документ1/Документ1.mdo");
            Files.createDirectories(descriptor.getParent());
            Files.writeString(descriptor, "test metadata");

            // The outer disposable directory is not an EDT project:
            // <stage>/src does not exist. The imported project is <stage>/edt.
            assertThrows(IOException.class, () ->
                EdtSelectiveReviewService.primaryDescriptors(stage));
            assertEquals(List.of("Documents/Документ1"),
                EdtSelectiveReviewService
                    .primaryDescriptors(stage.resolve("edt"))
                    .keySet().stream().toList());
        } finally {
            try (var files = Files.walk(stage)) {
                for (Path entry : files.sorted(
                        java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    @Test
    public void fullScopeIncludesAllRemoteAndLocalOnlyTopLevelOwners()
            throws Exception {
        Path local = Files.createTempDirectory("edt-full-local-");
        Path remote = Files.createTempDirectory("edt-full-remote-");
        try {
            Files.createDirectories(local.resolve("src/Documents/Old"));
            Files.writeString(local.resolve("src/Documents/Old/Old.mdo"), "old");
            Files.createDirectories(local.resolve("src/Documents/Shared"));
            Files.writeString(local.resolve("src/Documents/Shared/Shared.mdo"), "local");

            Files.createDirectories(remote.resolve("src/Documents/Shared"));
            Files.writeString(remote.resolve("src/Documents/Shared/Shared.mdo"), "remote");
            Files.createDirectories(remote.resolve("src/Catalogs/New"));
            Files.writeString(remote.resolve("src/Catalogs/New/New.mdo"), "new");
            Files.createDirectories(remote.resolve("src/Configuration"));
            Files.writeString(remote.resolve("src/Configuration/Configuration.mdo"), "root");
            Files.createDirectories(remote.resolve(
                "src/Documents/Shared/Forms/Extra"));
            Files.writeString(remote.resolve(
                "src/Documents/Shared/Forms/Extra/Extra.mdo"), "subordinate");
            Files.writeString(remote.resolve(
                "src/Documents/Shared/Forms/Extra/Form.form"), "form");

            var full = EdtSelectiveReviewService.fullPrimaryScopes(local, remote);
            var main = full.main().stream().map(Path::toString).toList();
            var other = full.other().stream().map(Path::toString).toList();
            org.junit.Assert.assertTrue(main.contains(
                "src/Documents/Old/Old.mdo"));
            org.junit.Assert.assertFalse(other.contains(
                "src/Documents/Old/Old.mdo"));
            org.junit.Assert.assertTrue(main.contains(
                "src/Catalogs/New/New.mdo"));
            org.junit.Assert.assertTrue(other.contains(
                "src/Catalogs/New/New.mdo"));
            org.junit.Assert.assertTrue(other.contains(
                "src/Configuration/Configuration.mdo"));
            org.junit.Assert.assertEquals(4, main.size());
            org.junit.Assert.assertEquals(3, other.size());
            org.junit.Assert.assertFalse(main.contains(
                "src/Documents/Shared/Forms/Extra/Extra.mdo"));
        } finally {
            try (var files = Files.walk(local)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
            try (var files = Files.walk(remote)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    @Test
    public void fullScopeRefusesAbsentRemoteConfigurationInsteadOfFakingSuccess()
            throws Exception {
        Path local = Files.createTempDirectory("edt-full-local-");
        Path remote = Files.createTempDirectory("edt-full-remote-");
        try {
            Files.createDirectories(local.resolve("src/Documents/Only"));
            Files.writeString(local.resolve("src/Documents/Only/Only.mdo"), "local");
            Files.createDirectories(remote.resolve("src"));
            assertThrows(IOException.class,
                () -> EdtSelectiveReviewService.fullPrimaryScopes(local, remote));
        } finally {
            try (var paths = Files.walk(local)) {
                for (Path item : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(item);
                }
            }
            try (var paths = Files.walk(remote)) {
                for (Path item : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(item);
                }
            }
        }
    }

    @Test
    public void realPayrollConfigurationTypesMapToNativeEdtFolders() throws Exception {
        assertEquals("SettingsStorages/НастройкиПользователей",
            EdtSelectiveReviewService.edtOwner(
                "SettingsStorage.НастройкиПользователей.ManagerModule"));
        assertEquals("CommandGroups/Печать",
            EdtSelectiveReviewService.edtOwner("CommandGroup.Печать"));
        assertEquals("IntegrationServices/ОбменСообщениями",
            EdtSelectiveReviewService.edtOwner(
                "IntegrationService.ОбменСообщениями.Module"));
        assertEquals("SettingsStorages/Настройки",
            EdtSelectiveReviewService.edtOwner("ХранилищеНастроек.Настройки"));
        assertEquals("CommandGroups/Печать",
            EdtSelectiveReviewService.edtOwner("ГруппаКоманд.Печать"));
        assertEquals("IntegrationServices/Обмен",
            EdtSelectiveReviewService.edtOwner("СервисИнтеграции.Обмен"));
    }

    @Test
    public void everyMetadataCategoryFromTheUserExportIsSupported() throws Exception {
        // Distinct prefixes from a real 16,945-line Designer object list.
        var categories = java.util.List.of(
            "CommonModule", "CommonPicture", "Document", "DataProcessor",
            "InformationRegister", "Catalog", "Report", "Constant",
            "CommonForm", "Enum", "Role", "CommonCommand", "StyleItem",
            "Subsystem", "EventSubscription", "FunctionalOption",
            "DefinedType", "ScheduledJob", "XDTOPackage",
            "AccumulationRegister", "ExchangePlan", "SessionParameter",
            "CommonTemplate", "DocumentJournal", "WebService",
            "ChartOfCharacteristicTypes", "SettingsStorage",
            "BusinessProcess", "Task", "CommandGroup", "HTTPService",
            "FunctionalOptionsParameter", "CommonAttribute",
            "CalculationRegister", "FilterCriterion",
            "ChartOfCalculationTypes", "IntegrationService",
            "Language", "WSReference");
        var objects = categories.stream().map(x -> x + ".Example").toList();
        EdtSelectiveReviewService.requireSupportedObjectTypes(objects);
        for (String category : categories) {
            org.junit.Assert.assertFalse(
                EdtSelectiveReviewService.edtOwner(category + ".Example").isBlank());
        }
    }

    @Test
    public void unknownCategoriesFailBeforeXmlConversionAndShowOnlyTypeNames() {
        var failure = assertThrows(
            EdtSelectiveReviewService.UnsupportedMetadataTypesException.class,
            () -> EdtSelectiveReviewService.requireSupportedObjectTypes(
                java.util.List.of("UnknownKind.Secret",
                    "OtherKind.AnotherSecret", "UnknownKind.Third")));
        assertEquals(java.util.List.of("UnknownKind", "OtherKind"),
            failure.types());
        org.junit.Assert.assertFalse(
            failure.getMessage().contains("Secret"));
    }

    @Test
    public void missingOwnersAreGroupedWithoutExposingPrivateObjectNames() {
        var requested = java.util.Set.of(
            "Documents/SecretOne", "Documents/SecretTwo",
            "Catalogs/PrivateItems", "CommonModules/Utilities");
        var seen = java.util.Set.of("CommonModules/Utilities");
        assertEquals(java.util.Map.of("Documents", 2, "Catalogs", 1),
            EdtSelectiveReviewService.missingOwnersByType(requested, seen));
    }

    @Test
    public void unsupportedMetadataMustFailInsteadOfWideningMergeScope() {
        assertThrows(IOException.class,
            () -> EdtSelectiveReviewService.edtOwner("ImaginaryType.Test"));
        assertThrows(IOException.class,
            () -> EdtSelectiveReviewService.edtOwner(null));
    }
}
