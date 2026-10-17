package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.SelectiveDumpPlanner;

public final class SelectiveDumpPlannerTest {
    @Test
    public void standaloneConfigurationRootMustNotBreakOtherSelectiveObjects() {
        var plan = SelectiveDumpPlanner.plan(List.of(
            "Configuration", "Document.Doc1", "Report.Form14",
            "Configuration.Main.SessionModule"));
        assertEquals(List.of("Document.Doc1", "Report.Form14",
            "Configuration.Main.SessionModule"), plan.exportable());
        assertEquals(List.of("Configuration"), plan.deferred());
    }

    @Test
    public void unexportableRootEntriesMustStayPending() {
        var plan = SelectiveDumpPlanner.plan(List.of(
            "Language", "Configuration", "Language.Russian"));
        assertEquals(List.of("Language.Russian"), plan.exportable());
        assertEquals(List.of("Language", "Configuration"), plan.deferred());
    }

    @Test
    public void nestedCommandsUseTheirOwnersWithoutLosingIndependentForms() {
        var plan = SelectiveDumpPlanner.plan(List.of(
            "Catalog.Б24_УП_НастройкиПодключения.Command.НастройкиПодключения",
            "Catalog.Б24_УП_НастройкиПодключения",
            "Document.Doc1.Command.Run",
            "Document.Doc1.Form.MainForm",
            "CommonCommand.OpenPanel.CommandModule"));
        assertEquals(List.of(
            "Catalog.Б24_УП_НастройкиПодключения",
            "Document.Doc1",
            "Document.Doc1.Form.MainForm",
            "CommonCommand.OpenPanel.CommandModule"), plan.exportable());
        assertTrue(plan.deferred().isEmpty());
    }

    @Test
    public void changedCommandAloneStillRequestsItsOwner() {
        var plan = SelectiveDumpPlanner.plan(List.of(
            "Catalog.Items.Command.Pick",
            "DataProcessor.Import.Command.Load"));
        assertEquals(List.of("Catalog.Items", "DataProcessor.Import"),
            plan.exportable());
        assertTrue(plan.deferred().isEmpty());
    }

    @Test
    public void normalMetadataUsesUnmodifiedNativeFullNames() {
        var plan = SelectiveDumpPlanner.plan(List.of(
            "Catalog.Товары.ObjectModule", "Document.Заказ",
            "Document.Заказ.Form.Основная"));
        assertEquals(List.of("Catalog.Товары.ObjectModule", "Document.Заказ",
            "Document.Заказ.Form.Основная"), plan.exportable());
        assertTrue(plan.deferred().isEmpty());
    }
}
