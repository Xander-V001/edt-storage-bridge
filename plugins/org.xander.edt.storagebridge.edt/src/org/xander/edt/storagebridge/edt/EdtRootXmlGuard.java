package org.xander.edt.storagebridge.edt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Hide ONLY the top-level Configuration/Ext XML resources from the native
 * EDT importer when converting an unrelated selective-object dump.
 *
 * The source XML belongs to this plugin's disposable Designer export, not
 * to the user's project or infobase. Restoring is mandatory. Root changes
 * themselves must stay pending until a fully validated EDT import exists.
 */
public final class EdtRootXmlGuard implements AutoCloseable {
    private final Path original;
    private final Path holdingFolder;
    private final Path moved;

    private EdtRootXmlGuard(Path original, Path holdingFolder, Path moved) {
        this.original = original;
        this.holdingFolder = holdingFolder;
        this.moved = moved;
    }

    public static EdtRootXmlGuard hideUnselectedRootExt(
            Path xmlRoot, List<String> selectedObjects) throws IOException {
        Objects.requireNonNull(xmlRoot, "xmlRoot");
        Objects.requireNonNull(selectedObjects, "selectedObjects");
        // Never hide data that the user explicitly selected for this review.
        for (String object : selectedObjects) {
            if ("Configuration".equals(object)
                    || (object != null && object.startsWith("Configuration."))) {
                throw new IOException(
                    "Configuration root cannot be converted without validated "
                    + "native EDT paths; root changes remain pending");
            }
        }
        Path root = xmlRoot.toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Expected existing native XML dump directory");
        }
        Path ext = root.resolve("Ext");
        if (!Files.exists(ext, LinkOption.NOFOLLOW_LINKS)) {
            return new EdtRootXmlGuard(null, null, null);
        }
        if (!Files.isDirectory(ext, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unexpected Configuration Ext XML path");
        }
        Path holding = Files.createTempDirectory(root.getParent(),
            "edt-storage-root-xml-");
        Path detached = holding.resolve("Ext");
        try {
            Files.move(ext, detached);
        } catch (IOException ex) {
            Files.deleteIfExists(holding);
            throw ex;
        }
        return new EdtRootXmlGuard(ext, holding, detached);
    }

    @Override
    public void close() throws IOException {
        if (moved != null) {
            try {
                Files.move(moved, original);
            } finally {
                // Do not delete the only remaining XML source if restoration
                // failed; the enclosing disposable staging is still safe.
                if (!Files.exists(moved, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(holdingFolder);
                }
            }
        }
    }
}
