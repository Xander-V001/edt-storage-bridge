package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Strict parser for 1C Designer -getChanges file.
 *
 * Unknown changes must NEVER be treated as "not changed": that could erase a
 * committed EDT change or miss a deletion. FullDump is an explicit refusal,
 * not an invitation to export/import the full configuration.
 */
public final class ConfigurationChangesParser {
    public record ChangeSet(List<String> newObjects, List<String> modifiedObjects) {
        public ChangeSet {
            newObjects = List.copyOf(newObjects);
            modifiedObjects = List.copyOf(modifiedObjects);
        }

        /**
         * Metadata names for Designer -listFile.
         *
         * Independently-developed subordinate objects (for example forms and
         * templates) are NOT included in a parent object dump. Export both
         * the parent and the exact separately-developed child when required.
         * A metadata field such as an attribute is covered by its parent.
         */
        public List<String> objectsToExport() {
            var names = new LinkedHashSet<String>();
            for (String object : newObjects) {
                addExportNames(names, object);
            }
            for (String object : modifiedObjects) {
                addExportNames(names, object);
            }
            return List.copyOf(names);
        }

        private static void addExportNames(Set<String> export, String object) {
            String root = topObject(object);
            export.add(root);
            String[] parts = object.split("\\.");
            // Configuration.<actual name> has its own development files;
            // shortening a versioned child to "Configuration.Module" would
            // produce a nonexistent -listFile item.
            if ("Configuration".equals(parts[0]) && parts.length >= 3) {
                export.add(object);
                return;
            }
            // Some *top-level* objects have a separately-versioned primary
            // payload: CommonForm.X.Form, CommonTemplate.X.Template, etc.
            if (parts.length == 3 && (
                    ("CommonForm".equals(parts[0]) && "Form".equals(parts[2]))
                    || ("CommonTemplate".equals(parts[0]) && "Template".equals(parts[2]))
                    || ("CommonPicture".equals(parts[0]) && "Picture".equals(parts[2])))) {
                export.add(object);
                return;
            }
            // Preserve the native independent resources under the parent.
            // Nested subsystem paths can be arbitrarily deep; truncating
            // at the first child silently loses descendants' XML files.
            StringBuilder ownerPath = new StringBuilder(root);
            descendants:
            for (int index = 2; index < parts.length; index++) {
                String segment = parts[index];
                switch (segment) {
                    case "Subsystem", "Подсистема" -> {
                        if (index + 1 < parts.length) {
                            ownerPath.append('.').append(segment)
                                .append('.').append(parts[++index]);
                            export.add(ownerPath.toString());
                        }
                    }
                    case "Form", "Форма", "Template", "Макет",
                         "Recalculation", "Перерасчет" -> {
                        if (index + 1 < parts.length) {
                            ownerPath.append('.').append(segment)
                                .append('.').append(parts[index + 1]);
                            export.add(ownerPath.toString());
                        }
                        // Payload suffixes like Form.Main.Form.Module are
                        // NOT independently exported native metadata names.
                        break descendants;
                    }
                    case "ManagerModule", "ObjectModule",
                         "RecordSetModule", "ValueManagerModule",
                         "Module", "CommandModule", "Rights", "Help",
                         "CommandInterface", "Picture", "ExtInfo" ->
                        export.add(ownerPath + "." + segment);
                    default -> {
                        // Attributes, Commands and other embedded properties
                        // are serialized inside their enclosing metadata
                        // owner, not individually supported by -listFile.
                    }
                }
            }
        }
    }

    private ConfigurationChangesParser() {
    }

    public static ChangeSet read(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.isRegularFile(path) || Files.size(path) > 8L * 1024 * 1024) {
            throw new IOException("Invalid or oversized 1C changes file");
        }
        byte[] bytes = Files.readAllBytes(path);
        int start = bytes.length >= 3
                && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf ? 3 : 0;
        return parse(new String(bytes, start, bytes.length - start,
            StandardCharsets.UTF_8));
    }

    public static ChangeSet parse(String contents) throws IOException {
        Objects.requireNonNull(contents, "contents");
        Set<String> newObjects = new LinkedHashSet<>();
        Set<String> modified = new LinkedHashSet<>();
        for (String line : contents.replace("\r", "").split("\n")) {
            String entry = line.trim();
            if (entry.isEmpty()) {
                continue;
            }
            if ("FullDump".equalsIgnoreCase(entry)) {
                throw new IOException("1C reports FullDump; full export is forbidden");
            }
            int colon = entry.indexOf(':');
            if (colon < 1 || colon >= entry.length() - 1) {
                throw new IOException("Unknown 1C change record; refusing unsafe import");
            }
            String kind = entry.substring(0, colon);
            String name = entry.substring(colon + 1).trim();
            try {
                topObject(name);
            } catch (IllegalArgumentException ex) {
                throw new IOException("Malformed metadata name from 1C", ex);
            }
            // Deliberately fail for rename/delete events until the exact
            // platform semantics and EDT deletion merge have been tested.
            switch (kind) {
                case "New" -> newObjects.add(name);
                case "Modified" -> modified.add(name);
                default -> throw new IOException(
                    "Unsupported metadata change type: " + kind);
            }
        }
        return new ChangeSet(List.copyOf(newObjects), List.copyOf(modified));
    }

    private static String topObject(String fqName) {
        // "Configuration" and "Language" are valid top-level metadata
        // objects in the 1C increment/edtExport exchange format.
        if ("Configuration".equals(fqName) || "Language".equals(fqName)) {
            return fqName;
        }
        if (fqName == null
                || !fqName.matches("[\\p{L}\\p{N}_]+(\\.[\\p{L}\\p{N}_]+)+")) {
            throw new IllegalArgumentException("Invalid metadata object name");
        }
        String[] parts = fqName.split("\\.");
        if ("Configuration".equals(parts[0])) {
            return "Configuration";
        }
        // Each Language.<name> is an independent metadata object.
        // Collapsing all languages to "Language" drops real changes.
        return parts[0] + "." + parts[1];
    }
}
