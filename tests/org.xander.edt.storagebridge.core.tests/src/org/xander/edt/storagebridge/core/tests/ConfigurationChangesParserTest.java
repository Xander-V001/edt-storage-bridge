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
            "Modified: Configuration\\nModified: Language\\n");
        assertEquals(List.of("Configuration", "Language"),
            changes.objectsToExport());
    }

    @Test
    public void independentFormsAndTemplatesAreExplicitlyExported() throws Exception {
        var changes = ConfigurationChangesParser.parse(
            "Modified: Document.Doc1.Form.MainForm.Module\\n"
            + "Modified: Document.Doc1.Template.Print\\n"
            + "Modified: Document.Doc2.Attribute.SerialNumber\\n");
        assertEquals(List.of(
            "Document.Doc1",
            "Document.Doc1.Form.MainForm",
            "Document.Doc1.Template.Print",
            "Document.Doc2"), changes.objectsToExport());
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
