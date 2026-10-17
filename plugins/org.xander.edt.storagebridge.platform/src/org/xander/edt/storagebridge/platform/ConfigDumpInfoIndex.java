package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Native 1C ConfigDumpInfo.xml inventory and deterministic version delta.
 *
 * The first scan inventories ALL remote objects, never just the changes made
 * to an intermediary during its latest update. A later scan compares two
 * remote manifests recorded for the SAME EDT project and Git branch.
 *
 * The configVersion values are opaque: we only test equality, never derive
 * or manufacture them. Missing/renamed objects are not silently dropped.
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

    /**
     * Only NEW or MODIFIED versioned metadata is returned. Deletions, renames,
     * ambiguous identities and corrupt snapshots are explicit safety failures.
     * No absent remote object can be accidentally marked as already applied.
     */
    public static Set<String> changedSince(Path previous, Path current)
            throws IOException {
        Map<String, Version> before = readVersions(previous);
        Map<String, Version> after = readVersions(current);
        Map<String, String> oldNamesById = new LinkedHashMap<>();
        for (var entry : before.entrySet()) {
            String other = oldNamesById.putIfAbsent(entry.getValue().id(),
                entry.getKey());
            if (other != null && !other.equals(entry.getKey())) {
                throw new IOException("Ambiguous 1C metadata ID in old snapshot");
            }
        }
        Set<String> changed = new LinkedHashSet<>();
        for (var entry : after.entrySet()) {
            Version newVersion = entry.getValue();
            String oldName = oldNamesById.get(newVersion.id());
            if (oldName != null && !oldName.equals(entry.getKey())) {
                throw new IOException("1C metadata rename requires explicit merge");
            }
            Version oldVersion = before.get(entry.getKey());
            if (oldVersion != null
                    && !oldVersion.id().equals(newVersion.id())) {
                // Same human-readable name but DIFFERENT 1C UUID is a
                // deletion + new object, not a simple content modification.
                // MatchingStrategy.UUID_THEN_NAME could silently pair them by
                // name and overwrite unrelated locally committed metadata.
                throw new IOException(
                    "1C metadata object replacement requires explicit merge");
            }
            if (oldVersion == null || !oldVersion.equals(newVersion)) {
                changed.add(entry.getKey());
            }
        }
        for (String oldName : before.keySet()) {
            if (!after.containsKey(oldName)) {
                throw new IOException("1C metadata deletion requires explicit merge");
            }
        }
        return Set.copyOf(changed);
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
            if (root == null || !"ConfigDumpInfo".equals(root.getLocalName())
                    || !DUMPINFO_NS.equals(root.getNamespaceURI())
                    || !"Hierarchical".equals(root.getAttribute("format"))
                    || root.getAttribute("version").isBlank()) {
                throw new IOException("Unknown ConfigDumpInfo.xml schema");
            }
            NodeList versions = root.getElementsByTagNameNS(DUMPINFO_NS,
                "ConfigVersions");
            if (versions.getLength() != 1) {
                throw new IOException("ConfigDumpInfo contains no unique version list");
            }
            NodeList metadata = ((Element) versions.item(0))
                .getElementsByTagNameNS(DUMPINFO_NS, "Metadata");
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
