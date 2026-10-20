package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.SelectiveDumpPlanner;

public final class SelectiveDumpPlannerTest {
    @Test
    public void unfinishedFormPlusNewNativeModuleUsesSelectiveOwnersOnly() {
        // A developer previously postponed Document.Order.Form.Main.
        // A later repository revision modifies CommonModule.Tools.Module.
        // Both can be transported by documented native -listFile without
        // enumerating or exporting the entire 1C configuration.
        var plan = SelectiveDumpPlanner.planIncludingRelated(List.of(
            "Document.Order.Form.Main", "CommonModule.Tools.Module"),
            List.of("Document.Order", "Document.Order.Form.Main",
                "Document.Order.Form.Print", "Document.Order.ObjectModule",
                "CommonModule.Tools", "CommonModule.Tools.Module",
                "Document.Unrelated", "Document.Unrelated.Form.Main"));
        assertEquals(List.of("Document.Order", "Document.Order.Form.Main",
                "CommonModule.Tools", "CommonModule.Tools.Module",
                "Document.Order.Form.Print", "Document.Order.ObjectModule"),
            plan.exportable());
        assertTrue(plan.deferred().isEmpty());
    }

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
        assertEquals(List.of("Catalog.Products", "Document.Order",
            "Document.Order.Form.Main"),
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
            "Document.Doc1", "Document.Doc1.Form.MainForm",
            "CommonCommand.OpenPanel", "CommonCommand.OpenPanel.CommandModule"),
            plan.exportable());
        assertTrue(plan.deferred().isEmpty());
    }

    @Test
    public void nestedFormsTemplatesModulesAndHelpUseCompleteOwners() {
        var plan = SelectiveDumpPlanner.plan(List.of(
            "Document.Order.Form.Main", "Document.Order.Template.Print",
            "Document.Order.ManagerModule", "Document.Order.Help",
            "Catalog.Products.Form.Item", "CommonModule.Utilities.Module",
            "Report.Sales.DataCompositionSchema"));
        assertEquals(List.of("Document.Order", "Document.Order.Form.Main",
            "Document.Order.Template.Print", "Document.Order.ManagerModule",
            "Document.Order.Help", "Catalog.Products",
            "Catalog.Products.Form.Item", "CommonModule.Utilities",
            "CommonModule.Utilities.Module", "Report.Sales"), plan.exportable());
        assertTrue(plan.deferred().isEmpty());
    }

    @Test
    public void childChangesAreNotCollapsedEvenWhenParentAlreadyRequested() {
        var plan = SelectiveDumpPlanner.plan(List.of(
            "Document.Invoice",
            "Document.Invoice.Form.Main.Form",
            "Document.Invoice.ObjectModule",
            "Document.Invoice.Template.Print",
            "Role.Accountant.Rights",
            "CommonForm.Setup.Form",
            "CommonTemplate.Print.Template"));
        assertEquals(List.of(
            "Document.Invoice",
            "Document.Invoice.Form.Main",
            "Document.Invoice.ObjectModule",
            "Document.Invoice.Template.Print",
            "Role.Accountant", "Role.Accountant.Rights",
            "CommonForm.Setup", "CommonForm.Setup.Form",
            "CommonTemplate.Print", "CommonTemplate.Print.Template"),
            plan.exportable());
        assertTrue(plan.deferred().isEmpty());
    }

    @Test
    public void selectiveReceiveIncludesALLIndependentlyVersionedOwnerResources() {
        var plan = SelectiveDumpPlanner.planIncludingRelated(
            List.of("Document.Invoice.ObjectModule"),
            List.of("Document.Invoice", "Document.Invoice.Form.Main",
                "Document.Invoice.Form.Print",
                "Document.Invoice.Template.InvoiceMxl",
                "Document.Invoice.ManagerModule",
                "Document.Invoice.Command.Launch",
                "Document.Invoice.ObjectModule",
                "Document.Other.Form.Unrelated"));
        assertEquals(List.of(
            "Document.Invoice", "Document.Invoice.ObjectModule",
            "Document.Invoice.Form.Main", "Document.Invoice.Form.Print",
            "Document.Invoice.Template.InvoiceMxl",
            "Document.Invoice.ManagerModule"), plan.exportable());
        assertTrue(plan.deferred().isEmpty());
    }

    @Test
    public void nestedSubsystemsRetainTheirEntireDevelopmentPath() {
        var plan = SelectiveDumpPlanner.planIncludingRelated(
            List.of("Subsystem.Кадры"),
            List.of("Subsystem.Кадры.Subsystem.Интеграция1СКС",
                "Subsystem.Кадры.Subsystem.Интеграция1СКС.Subsystem.Вложенная",
                "Subsystem.Зарплата.Subsystem.Другая"));
        assertEquals(List.of(
            "Subsystem.Кадры",
            "Subsystem.Кадры.Subsystem.Интеграция1СКС",
            "Subsystem.Кадры.Subsystem.Интеграция1СКС.Subsystem.Вложенная"),
            plan.exportable());
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
        assertEquals(List.of("Catalog.Товары", "Catalog.Товары.ObjectModule",
            "Document.Заказ", "Document.Заказ.Form.Основная"),
            plan.exportable());
        assertTrue(plan.deferred().isEmpty());
    }
}
