package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.xander.edt.storagebridge.core.OutgoingGitCandidateScanner;

public final class OutgoingGitCandidateScannerTest {
    private static byte[] encoded(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void includesFormsStructuralEditsAndDeletedModules() throws Exception {
        var result = OutgoingGitCandidateScanner.parse(encoded(
            "M\0src/Documents/Заказ/Forms/Основная/Module.bsl\0"
            + "A\0src/Catalogs/Люди/Люди.mdo\0"
            + "D\0src/CommonModules/Общий/Module.bsl\0"));
        assertEquals(3, result.files().size());
        assertEquals(3, result.ownerChanges().size());
        assertTrue(result.ownerChanges().containsKey("Documents/Заказ"));
        assertTrue(result.ownerChanges().containsKey("Catalogs/Люди"));
        assertEquals(OutgoingGitCandidateScanner.Kind.DELETED,
            result.ownerChanges().get("CommonModules/Общий").get(0).kind());
    }

    @Test
    public void unsafeOrUnknownGitPathsAreRejected() {
        for (String line : new String[] {
            "M\0src/Documents/Заказ",
            "R100\0src/Documents/Заказ/Module.bsl\0",
            "M\0src/../../credentials\0",
            "M\0src/Documents/../Catalogs/X.mdo\0",
            "M\0other/resource.bsl\0",
            "M\0src/not-an-object.txt\0"
        }) {
            assertThrows(IOException.class,
                () -> OutgoingGitCandidateScanner.parse(encoded(line)));
        }
    }

    @Test
    public void emptyRangeIsEmptyNotPublishPermission() throws Exception {
        var result = OutgoingGitCandidateScanner.parse(new byte[0]);
        assertTrue(result.files().isEmpty());
        assertTrue(result.ownerChanges().isEmpty());
    }
}
