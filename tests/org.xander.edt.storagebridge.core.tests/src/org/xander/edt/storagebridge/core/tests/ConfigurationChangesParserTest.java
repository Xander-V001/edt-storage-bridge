package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.List;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.ConfigurationChangesParser;

public class ConfigurationChangesParserTest {
    @Test
    public void changedFieldsAreGroupedIntoSingleTopLevelObject() throws Exception {
        var changes = ConfigurationChangesParser.parse(
            "New: Документ.Документ2\n"
            + "Modified: Документ.Документ1.Реквизит.Номер\n"
            + "Modified: Документ.Документ1\n");
        assertEquals(List.of("Документ.Документ2", "Документ.Документ1"),
            changes.objectsToExport());
    }

    @Test
    public void recognizesConfigurationAndLanguageRootObjects() throws Exception {
        var changes = ConfigurationChangesParser.parse(
            "Modified: Configuration\nModified: Language\n");
        assertEquals(List.of("Configuration", "Language"),
            changes.objectsToExport());
    }

    @Test
    public void independentFormsAndTemplatesAreExplicitlyExported() throws Exception {
        var changes = ConfigurationChangesParser.parse(
            "Modified: Document.Doc1.Form.MainForm.Module\n"
            + "Modified: Document.Doc1.Template.Print\n"
            + "Modified: Document.Doc2.Attribute.SerialNumber\n");
        assertEquals(List.of(
            "Document.Doc1",
            "Document.Doc1.Form.MainForm",
            "Document.Doc1.Template.Print",
            "Document.Doc2"), changes.objectsToExport());
    }

    @Test
    public void separatelyVersionedModulesAndRightsMustBeExported() throws Exception {
        var changes = ConfigurationChangesParser.parse(
            "Modified: Catalog.Товары.ObjectModule\n"
            + "Modified: Role.Бухгалтер.Rights\n"
            + "Modified: CommonModule.Утилиты.Module\n"
            + "Modified: Report.ФормаП4.Form.Основная.Form\n");
        assertEquals(List.of(
            "Catalog.Товары", "Catalog.Товары.ObjectModule",
            "Role.Бухгалтер", "Role.Бухгалтер.Rights",
            "CommonModule.Утилиты", "CommonModule.Утилиты.Module",
            "Report.ФормаП4", "Report.ФормаП4.Form.Основная"),
            changes.objectsToExport());
    }

    @Test
    public void configurationAndTopLevelIndependentFilesKeepValidNames()
            throws Exception {
        var changes = ConfigurationChangesParser.parse(
            "Modified: Configuration.Основная.SessionModule\n"
            + "Modified: CommonForm.ФормаП.Form\n"
            + "Modified: CommonTemplate.Бланк.Template\n"
            + "Modified: CommonPicture.Эмблема.Picture\n"
            + "Modified: Catalog.Товары.Command.Подбор.CommandModule\n");
        assertEquals(List.of(
            "Configuration", "Configuration.Основная.SessionModule",
            "CommonForm.ФормаП", "CommonForm.ФормаП.Form",
            "CommonTemplate.Бланк", "CommonTemplate.Бланк.Template",
            "CommonPicture.Эмблема", "CommonPicture.Эмблема.Picture",
            "Catalog.Товары"
        ), changes.objectsToExport());
    }

    @Test
    public void embeddedCommandsUseOwnerInChangesParser() throws Exception {
        var changes = ConfigurationChangesParser.parse(
            "Modified: Catalog.Б24_УП_НастройкиПодключения.Command.НастройкиПодключения\n"
            + "Modified: Document.Doc1.Form.Main\n"
            + "Modified: CommonCommand.OpenPanel.CommandModule\n");
        assertEquals(List.of(
            "Catalog.Б24_УП_НастройкиПодключения",
            "Document.Doc1",
            "Document.Doc1.Form.Main",
            "CommonCommand.OpenPanel",
            "CommonCommand.OpenPanel.CommandModule"),
            changes.objectsToExport());
    }

    @Test
    public void nestedSubsystemsAreNotTruncatedToTheirFirstChild() throws Exception {
        var delta = ConfigurationChangesParser.parse(
            "Modified: Subsystem.Кадры.Subsystem.Интеграция1СКС"
            + ".Subsystem.Вложенная.Подсистема.ЕщёОдна\n"
            + "Modified: Document.Order.Form.Main.Form.Module\n");
        assertEquals(List.of(
            "Subsystem.Кадры",
            "Subsystem.Кадры.Subsystem.Интеграция1СКС",
            "Subsystem.Кадры.Subsystem.Интеграция1СКС.Subsystem.Вложенная",
            "Subsystem.Кадры.Subsystem.Интеграция1СКС.Subsystem.Вложенная.Подсистема.ЕщёОдна",
            "Document.Order", "Document.Order.Form.Main"),
            delta.objectsToExport());
    }

    @Test
    public void emptyResultMeansNoRemoteChanges() throws Exception {
        assertTrue(ConfigurationChangesParser.parse("").objectsToExport().isEmpty());
    }

    @Test
    public void rejectsFullDumpAndUnknownDeletionOrRename() {
        assertThrows(IOException.class,
            () -> ConfigurationChangesParser.parse("FullDump\n"));
        assertThrows(IOException.class,
            () -> ConfigurationChangesParser.parse("Deleted: Документ.Документ1"));
        assertThrows(IOException.class,
            () -> ConfigurationChangesParser.parse("Renamed: Документ.Документ1"));
        assertThrows(IOException.class,
            () -> ConfigurationChangesParser.parse("some unknown garbage"));
    }
}
