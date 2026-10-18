package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import java.io.StringReader;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.xml.sax.InputSource;
import org.xander.edt.storagebridge.platform.OutgoingStorageObjectsXml;

public final class OutgoingStorageObjectsXmlTest {
    @Test
    public void explicitObjectsDoNotTurnIntoWholeConfigurationCapture()
            throws Exception {
        String xml = OutgoingStorageObjectsXml.create(
            Set.of("Документ.НачислениеПоСреднему",
                "ОбщийМодуль.СлужебныеПроцедуры"));
        assertTrue(xml.contains(
            "http://v8.1c.ru/8.3/config/objects"));
        assertFalse(xml.contains("<Configuration"));
        assertFalse(xml.contains("includeChildObjects=\"true\""));
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var root = factory.newDocumentBuilder().parse(
            new InputSource(new StringReader(xml))).getDocumentElement();
        assertEquals("Objects", root.getLocalName());
        assertEquals(2, root.getElementsByTagNameNS(
            "http://v8.1c.ru/8.3/config/objects", "Object").getLength());
    }

    @Test
    public void wholeObjectScopeExplicitlyIncludesSubordinates() {
        String xml = OutgoingStorageObjectsXml.createWithSubordinates(
            Set.of("Document.Order", "AccumulationRegister.Balance"));
        assertEquals(2, xml.split("includeChildObjects=").length - 1);
        assertFalse(xml.contains("includeChildObjects=\\\"false\\\""));
    }

    @Test
    public void recursiveSelectionCannotAccidentallyLockConfigurationRoot() {
        assertThrows(IllegalArgumentException.class, () ->
            OutgoingStorageObjectsXml.createWithSubordinates(
                Set.of("Configuration")));
        assertThrows(IllegalArgumentException.class, () ->
            OutgoingStorageObjectsXml.createWithSubordinates(
                Set.of("Configuration.Configuration")));
    }

    @Test
    public void absentSelectionCanNeverBecomeLockEverything() {
        assertThrows(IllegalArgumentException.class,
            () -> OutgoingStorageObjectsXml.create(Set.of()));
    }

    @Test
    public void rootAndMalformedMetadataNamesAreAlwaysRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> OutgoingStorageObjectsXml.create(
                Set.of("Configuration.Configuration")));
        assertThrows(IllegalArgumentException.class,
            () -> OutgoingStorageObjectsXml.create(
                Set.of("Catalog.Goods\" includeChildObjects=\"true")));
        assertThrows(IllegalArgumentException.class,
            () -> OutgoingStorageObjectsXml.create(
                Set.of("Catalog")));
    }
}
