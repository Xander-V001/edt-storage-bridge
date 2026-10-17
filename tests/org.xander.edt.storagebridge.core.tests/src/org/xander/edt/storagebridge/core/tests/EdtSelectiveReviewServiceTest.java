package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
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
    public void unsupportedMetadataMustFailInsteadOfWideningMergeScope() {
        assertThrows(IOException.class,
            () -> EdtSelectiveReviewService.edtOwner("ImaginaryType.Test"));
        assertThrows(IOException.class,
            () -> EdtSelectiveReviewService.edtOwner(null));
    }
}
