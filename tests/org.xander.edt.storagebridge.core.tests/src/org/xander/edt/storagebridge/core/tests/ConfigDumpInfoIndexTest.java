package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.ConfigDumpInfoIndex;
import org.xander.edt.storagebridge.platform.ConfigurationChangesParser;

/**
 * Real ConfigDumpInfo structure: versioned metadata plus unversioned fields.
 * First inventory must include every exportable versioned object, not just
 * objects changed by the most recent update of the intermediary infobase.
 */
public final class ConfigDumpInfoIndexTest {
    private static Path fixture(String contents) throws Exception {
        Path file = Files.createTempFile("edt-storage-info-", ".xml");
        Files.writeString(file, contents, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    public void initialInventoryContainsAllRemoteObjectsAndSeparatelyVersionedForms()
            throws Exception {
        Path info = fixture("""
            <?xml version="1.0" encoding="UTF-8"?>
            <ConfigDumpInfo xmlns="http://v8.1c.ru/8.3/xcf/dumpinfo"
                format="Hierarchical" version="2.17">
              <ConfigVersions>
                <Metadata name="Configuration" id="a" configVersion="1"/>
                <Metadata name="Document.Заказ" id="b" configVersion="2">
                  <Metadata name="Document.Заказ.Attribute.Номер" id="c"/>
                  <Metadata name="Document.Заказ.Form.Основная" id="d"
                      configVersion="3"/>
                </Metadata>
                <Metadata name="Document.Акт" id="e" configVersion="4"/>
                <Metadata name="Language.Русский" id="f" configVersion="5"/>
                <Metadata name="Language.Английский" id="g" configVersion="6"/>
              </ConfigVersions>
            </ConfigDumpInfo>
            """);
        Set<String> inventory = ConfigDumpInfoIndex.exportableNames(info);
        assertEquals(6, inventory.size());
        assertTrue(inventory.contains("Document.Заказ"));
        assertTrue(inventory.contains("Document.Акт"));
        assertTrue(inventory.contains("Document.Заказ.Form.Основная"));
        assertFalse(inventory.contains("Document.Заказ.Attribute.Номер"));
        // Native unversioned Metadata is excluded from incremental tokens,
        // but mandatory for a FULL source completeness check.
        var all = ConfigDumpInfoIndex.allMetadataNames(info);
        assertEquals(7, all.size());
        assertTrue(all.contains("Document.Заказ.Attribute.Номер"));
        assertTrue(all.containsAll(inventory));

        var exports = new ConfigurationChangesParser.ChangeSet(
            List.copyOf(inventory), List.of()).objectsToExport();
        assertTrue(exports.contains("Document.Заказ"));
        assertTrue(exports.contains("Document.Акт"));
        assertTrue(exports.contains("Document.Заказ.Form.Основная"));
        assertTrue(exports.contains("Configuration"));
        assertTrue(exports.contains("Language.Русский"));
        assertTrue(exports.contains("Language.Английский"));
        assertFalse(exports.contains("Language"));
    }

    @Test
    public void officialNamespaceFreeConfigDumpInfoIsAccepted() throws Exception {
        // 1C official incremental XML export sample is namespace-free.
        Path info = fixture("""
            <ConfigDumpInfo format="Hierarchical" version="2.1">
              <ConfigVersions>
                <Metadata name="CommonAttribute.DataAreaMainData"
                    id="5BDAD865-F2C5-434B-8041-BA4AAD3B6687"
                    configVersion="0x230b7fe18c916f0cf161734d63ba5cfc6bda9cfc"/>
                <Metadata name="Role.SystemAdministrator.Rights"
                    id="5BDAD865-F2C5-434B-8041-BA4AAD3B6689.1"
                    configVersion="0xf543960146ba71301564ab0331f5f4a754fd450"/>
              </ConfigVersions>
            </ConfigDumpInfo>
            """);
        assertEquals(Set.of("CommonAttribute.DataAreaMainData",
                "Role.SystemAdministrator.Rights"),
            ConfigDumpInfoIndex.exportableNames(info));
    }

    @Test
    public void unknownSchemaCannotBeMistakenForEmptyRemoteInventory() throws Exception {
        Path malformed = fixture("<ConfigDumpInfo version=\"2.17\"/>");
        assertThrows(IOException.class, () -> ConfigDumpInfoIndex.exportableNames(malformed));
        Path wrongFormat = fixture("""
            <ConfigDumpInfo format="Plain" version="2.17">
              <ConfigVersions><Metadata name="Document.Заказ"
                  configVersion="x"/></ConfigVersions>
            </ConfigDumpInfo>
            """);
        assertThrows(IOException.class, () -> ConfigDumpInfoIndex.exportableNames(wrongFormat));
    }

    @Test
    public void maliciousDoctypeMustNotBeParsed() throws Exception {
        Path xml = fixture("""
            <!DOCTYPE foo [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
            <ConfigDumpInfo format="Hierarchical" version="2.17">
              <ConfigVersions>
                <Metadata name="Document.Заказ" configVersion="&secret;"/>
              </ConfigVersions>
            </ConfigDumpInfo>
            """);
        assertThrows(IOException.class, () -> ConfigDumpInfoIndex.exportableNames(xml));
    }

    @Test
    public void duplicateMetadataNamesAreRejected() throws Exception {
        Path duplicate = fixture("""
            <ConfigDumpInfo xmlns="http://v8.1c.ru/8.3/xcf/dumpinfo"
                format="Hierarchical" version="2.17">
              <ConfigVersions>
                <Metadata name="Document.First" id="a" configVersion="v"/>
                <Metadata name="Document.First" id="b" configVersion="v"/>
              </ConfigVersions>
            </ConfigDumpInfo>
            """);
        assertThrows(IOException.class, () -> ConfigDumpInfoIndex.exportableNames(duplicate));
        assertThrows(IOException.class, () -> ConfigDumpInfoIndex.allMetadataNames(duplicate));
    }

    @Test
    public void changedLanguageMustRemainIndependent() throws Exception {
        var changed = ConfigurationChangesParser.parse("""
            Modified:Language.Русский
            Modified:Language.Английский
            """).objectsToExport();
        assertEquals(Set.of("Language.Русский", "Language.Английский"),
            Set.copyOf(changed));
    }
}
