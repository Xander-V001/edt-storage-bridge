package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.Test;
import org.xander.edt.storagebridge.edt.EdtSelectiveReviewService;

/** Exercise the same owner mapping that the native EDT comparison scope uses. */
public class EdtSelectiveReviewServiceTest {
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
        assertNull(EdtSelectiveReviewService.metadataOwnerForPath(Path.of(
            "src/Configuration/Configuration.mdo/Languages/Русский")));
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
