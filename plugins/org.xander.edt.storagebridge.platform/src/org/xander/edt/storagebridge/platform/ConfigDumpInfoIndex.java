package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Discover exportable metadata from ConfigDumpInfo.xml for an INITIAL EDT
 * synchronization. Until the EDT project has a receive checkpoint, comparing
 * a connected infobase only to its last update misses old repository changes.
 *
 * The initial pass must consider ALL versioned objects (and keep rejected
 * ones pending). Subsequent passes use the last-scanned snapshot plus pending.
 */
public final class ConfigDumpInfoIndex {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int MAX_OBJECTS = 500_000;

    private ConfigDumpInfoIndex() {}

    public static Set<String> exportableNames(Path configDumpInfo)
            throws IOException {
        if (configDumpInfo == null || !Files.isRegularFile(configDumpInfo)
                || Files.size(configDumpInfo) < 30
                || Files.size(configDumpInfo) > MAX_BYTES) {
            throw new IOException("ConfigDumpInfo.xml missing or too large");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Element root = factory.newDocumentBuilder().parse(
                configDumpInfo.toFile()).getDocumentElement();
            if (root == null || !"ConfigDumpInfo".equals(root.getLocalName())
                    || !"Hierarchical".equals(root.getAttribute("format"))
                    || root.getAttribute("version").isBlank()) {
                throw new IOException("Unknown ConfigDumpInfo.xml schema");
            }
            NodeList versions = root.getElementsByTagNameNS("*", "ConfigVersions");
            if (versions.getLength() != 1) {
                throw new IOException("ConfigDumpInfo contains no unique version list");
            }
            NodeList metadata = ((Element) versions.item(0))
                .getElementsByTagNameNS("*", "Metadata");
            var names = new LinkedHashSet<String>();
            for (int i = 0; i < metadata.getLength(); i++) {
                Element item = (Element) metadata.item(i);
                if (item.getAttribute("configVersion").isBlank()) {
                    continue;
                }
                String name = item.getAttribute("name");
                if (!name.matches("[\\p{L}\\p{N}_]+(\\.[\\p{L}\\p{N}_]+)*")) {
                    throw new IOException("Invalid metadata name in ConfigDumpInfo");
                }
                if (names.size() >= MAX_OBJECTS) {
                    throw new IOException("ConfigDumpInfo object limit exceeded");
                }
                names.add(name);
            }
            if (names.isEmpty()) {
                throw new IOException("No exportable metadata in ConfigDumpInfo");
            }
            return Set.copyOf(names);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot parse ConfigDumpInfo version list", e);
        }
    }
}
