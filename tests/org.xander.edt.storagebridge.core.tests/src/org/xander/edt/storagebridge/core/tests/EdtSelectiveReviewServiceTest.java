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
    public void unsupportedMetadataMustFailInsteadOfWideningMergeScope() {
        assertThrows(IOException.class,
            () -> EdtSelectiveReviewService.edtOwner("ImaginaryType.Test"));
        assertThrows(IOException.class,
            () -> EdtSelectiveReviewService.edtOwner(null));
    }
}
