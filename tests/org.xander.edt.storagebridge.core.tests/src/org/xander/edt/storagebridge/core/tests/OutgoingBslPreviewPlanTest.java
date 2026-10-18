package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.OutgoingBslPreviewPlan;

public final class OutgoingBslPreviewPlanTest {
    @Test
    public void mapsOnlyExplicitSupportedModules() {
        var plan = OutgoingBslPreviewPlan.of(List.of(
            "src/CommonModules/МойОбщийМодуль/Module.bsl",
            "src/Documents/Накладная/ManagerModule.bsl",
            "src/Documents/Накладная/ObjectModule.bsl"));
        assertEquals(List.of(
            "CommonModules/МойОбщийМодуль/Ext/Module.bsl",
            "Documents/Накладная/Ext/ManagerModule.bsl",
            "Documents/Накладная/Ext/ObjectModule.bsl"), plan.platformFiles());
        assertEquals(2, plan.classicOwners().size());
        assertTrue(plan.classicOwners().contains("CommonModule.МойОбщийМодуль"));
        assertTrue(plan.classicOwners().contains("Document.Накладная"));
    }

    @Test
    public void refusesUnknownLocationsRootsAndDuplicates() {
        for (String name : List.of(
                "src/Documents/D/Forms/F/Module.bsl",
                "src/Documents/D/D.mdo",
                "src/Configuration/Configuration.mdo",
                "src/CommonModules/X/ManagerModule.bsl",
                "src/Documents/X/Ext/ObjectModule.bsl",
                "src/Documents/X/../Y/ObjectModule.bsl")) {
            assertThrows(IllegalArgumentException.class,
                () -> OutgoingBslPreviewPlan.of(List.of(name)));
        }
        assertThrows(IllegalArgumentException.class,
            () -> OutgoingBslPreviewPlan.of(List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> OutgoingBslPreviewPlan.of(List.of(
                "src/Documents/X/ObjectModule.bsl",
                "src/Documents/X/ObjectModule.bsl")));
    }

    @Test
    public void requiresNativeBslAndNeverSubstitutesParentXml() throws Exception {
        var directory = Files.createTempDirectory("edt-outbound-test-");
        try {
            var plan = OutgoingBslPreviewPlan.of(
                List.of("src/Documents/Заказ/ObjectModule.bsl"));
            Files.createDirectories(directory.resolve("Documents/Заказ"));
            Files.writeString(directory.resolve("Documents/Заказ.xml"), "XML");
            assertThrows(java.io.IOException.class,
                () -> plan.verifyExport(directory));
            var target = directory.resolve("Documents/Заказ/Ext/ObjectModule.bsl");
            Files.createDirectories(target.getParent());
            Files.writeString(target, "Процедура Проверка() КонецПроцедуры");
            plan.verifyExport(directory);
        } finally {
            try (var walk = Files.walk(directory)) {
                for (var path : walk.sorted(
                        java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }
}
