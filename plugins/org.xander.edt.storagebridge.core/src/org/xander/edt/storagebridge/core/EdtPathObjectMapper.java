package org.xander.edt.storagebridge.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Conservatively maps changed EDT source files to top-level metadata objects.
 * Unclassifiable files are UNKNOWN and must block automatic merge decisions.
 */
public final class EdtPathObjectMapper {
    private EdtPathObjectMapper() {
    }

    public static String topObject(Path changedPath) {
        Objects.requireNonNull(changedPath, "changedPath");
        Path path = changedPath.normalize();
        if (path.isAbsolute() || path.startsWith("..") || path.getNameCount() < 3
                || !"src".equals(path.getName(0).toString())) {
            return null;
        }
        String group = path.getName(1).toString();
        if ("Configuration".equals(group)) {
            return "Configuration";
        }
        String element = path.getName(2).toString();
        if (element.isBlank() || element.startsWith(".")) {
            return null;
        }
        if (element.endsWith(".mdo")) {
            element = element.substring(0, element.length() - 4);
        } else if (element.contains(".") && path.getNameCount() == 3) {
            // Direct files outside object directories are not metadata objects.
            return null;
        }
        // Preserve native EDT collection names. Mapping Russian classic-storage
        // names to these identifiers must be verified, not guessed.
        return group + "/" + element;
    }
}
