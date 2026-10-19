package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Read ONLY platform-created ConfigDumpInfo.xml metadata identity NAMES.
 *
 * The 1C internal id and configVersion values are opaque. They may be parsed
 * for structural validation, but MUST NOT be used to calculate which objects
 * changed. The only authoritative incremental diff is native -getChanges.
 *
 * This class never modifies or synthesizes a ConfigDumpInfo.xml file.
 */
public final class ConfigDumpInfoIndex {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int MAX_OBJECTS = 500_000;
    private static final String DUMPINFO_NS = "http://v8.1c.ru/8.3/xcf/dumpinfo";

    public record Version(String id, String token) {
        public Version {
            if (id == null || id.isBlank() || token == null
                    || token.isBlank()) {
                throw new IllegalArgumentException("Missing 1C metadata identity/version");
            }
        }
    }

    private ConfigDumpInfoIndex() {}

    public static Set<String> exportableNames(Path configDumpInfo)
            throws IOException {
        return Set.copyOf(readVersions(configDumpInfo).keySet());
    }

    /** Full object names and their opaque native 1C identity/version pair. */
    public static Map<String, Version> readVersions(Path configDumpInfo)
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
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Element root = factory.newDocumentBuilder().parse(
                configDumpInfo.toFile()).getDocumentElement();
            // The OFFICIAL 1C ConfigDumpInfo.xml example has no XML
            // namespace at all. Some 1C-format producers use the explicit
            // dumpinfo namespace. Accept only those two forms and require
            // the descendants to use the SAME namespace as the root.
            String ns = root == null ? null : root.getNamespaceURI();
            if (root == null || !"ConfigDumpInfo".equals(root.getLocalName())
                    || (ns != null && !ns.isEmpty() && !DUMPINFO_NS.equals(ns))
                    || !"Hierarchical".equals(root.getAttribute("format"))
                    || root.getAttribute("version").isBlank()) {
                throw new IOException("Unknown ConfigDumpInfo.xml schema");
            }
            NodeList versions = ns == null || ns.isEmpty()
                ? root.getElementsByTagName("ConfigVersions")
                : root.getElementsByTagNameNS(ns, "ConfigVersions");
            if (versions.getLength() != 1
                    || versions.item(0).getParentNode() != root) {
                throw new IOException("ConfigDumpInfo contains no unique version list");
            }
            NodeList metadata = ns == null || ns.isEmpty()
                ? ((Element) versions.item(0)).getElementsByTagName("Metadata")
                : ((Element) versions.item(0)).getElementsByTagNameNS(ns, "Metadata");
            Map<String, Version> result = new LinkedHashMap<>();
            for (int i = 0; i < metadata.getLength(); i++) {
                Element item = (Element) metadata.item(i);
                String token = item.getAttribute("configVersion");
                if (token.isBlank()) {
                    continue;
                }
                String name = item.getAttribute("name");
                if (!name.matches("[\\p{L}\\p{N}_]+(\\.[\\p{L}\\p{N}_]+)*")) {
                    throw new IOException("Invalid metadata name in ConfigDumpInfo");
                }
                if (result.size() >= MAX_OBJECTS) {
                    throw new IOException("ConfigDumpInfo object limit exceeded");
                }
                if (result.putIfAbsent(name,
                        new Version(item.getAttribute("id"), token)) != null) {
                    throw new IOException("Duplicated metadata name in ConfigDumpInfo");
                }
            }
            if (result.isEmpty()) {
                throw new IOException("No exportable metadata in ConfigDumpInfo");
            }
            return Map.copyOf(result);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot parse ConfigDumpInfo version list", e);
        }
    }
}
