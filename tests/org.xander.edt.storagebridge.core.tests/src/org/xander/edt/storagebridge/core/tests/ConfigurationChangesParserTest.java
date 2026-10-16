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
