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
        assertEquals(List.of("Document.Doc1", "Report.Form14"),
            plan.exportable());
        assertEquals(List.of("Configuration",
            "Configuration.Main.SessionModule"), plan.deferred());
    }

    @Test
    public void configurationExtensionFilesCannotBeSilentlyTreatedAsReceived() {
        var plan = SelectiveDumpPlanner.plan(List.of(
            "Configuration.Payroll.HomePageWorkArea",
            "Configuration.Payroll.ClientApplicationInterface",
            "Configuration.Payroll.ManagedApplicationModule",
            "Catalog.Products", "Document.Order.Form.Main"));
        assertEquals(List.of("Catalog.Products", "Document.Order"),
            plan.exportable());
        assertEquals(List.of(
            "Configuration.Payroll.HomePageWorkArea",
            "Configuration.Payroll.ClientApplicationInterface",
            "Configuration.Payroll.ManagedApplicationModule"),
            plan.deferred());
    }

    @Test
    public void unexportableRootEntriesMustStayPending() {
        var plan = SelectiveDumpPlanner.plan(List.of(
            "Language", "Configuration", "Language.Russian"));
        assertTrue(plan.exportable().isEmpty());
        assertEquals(List.of("Language", "Configuration",
            "Language.Russian"), plan.deferred());
    }

    @Test
    public void namedLanguageCannotBeExportedAsIndependentEdtObject() {
        var plan = SelectiveDumpPlanner.plan(List.of(
            "Language.Русский", "Язык.Английский", "Document.Заказ"));
        assertEquals(List.of("Document.Заказ"), plan.exportable());
        assertEquals(List.of("Language.Русский", "Язык.Английский"),
            plan.deferred());
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
            "CommonCommand.OpenPanel"), plan.exportable());
        assertTrue(plan.deferred().isEmpty());
    }

    @Test
    public void nestedFormsTemplatesModulesAndHelpUseCompleteOwners() {
        var plan = SelectiveDumpPlanner.plan(List.of(
            "Document.Order.Form.Main", "Document.Order.Template.Print",
            "Document.Order.ManagerModule", "Document.Order.Help",
            "Catalog.Products.Form.Item", "CommonModule.Utilities.Module",
            "Report.Sales.DataCompositionSchema"));
        assertEquals(List.of("Document.Order", "Catalog.Products",
            "CommonModule.Utilities", "Report.Sales"), plan.exportable());
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
    public void normalMetadataUsesOwnersAsNativeExportUnits() {
        var plan = SelectiveDumpPlanner.plan(List.of(
            "Catalog.Товары.ObjectModule", "Document.Заказ",
            "Document.Заказ.Form.Основная"));
        assertEquals(List.of("Catalog.Товары", "Document.Заказ"),
            plan.exportable());
        assertTrue(plan.deferred().isEmpty());
    }
}
