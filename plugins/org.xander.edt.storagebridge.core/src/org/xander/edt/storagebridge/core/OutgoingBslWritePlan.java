package org.xander.edt.storagebridge.core;

import org.xander.edt.storagebridge.platform.OutgoingBslPreviewPlan;
import org.xander.edt.storagebridge.platform.OutgoingStorageObjectsXml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;


/**
 * An intentionally narrow qualified candidate: ONLY modified standalone BSL
 * resources. Never convert whole owner XML, form or structural changes into a
 * silent BSL-only publication. Those require their own qualified round-trip.
 */
public final class OutgoingBslWritePlan {
    private final OutgoingBslPreviewPlan selected;

    private OutgoingBslWritePlan(OutgoingBslPreviewPlan selected) {
        this.selected = selected;
    }

    public static OutgoingBslWritePlan fromDelta(OutgoingDeltaScanner.Delta delta)
            throws IOException {
        Objects.requireNonNull(delta);
        if (delta.files().isEmpty())
            throw new IOException("No changes to publish");
        for (var change : delta.files()) {
            if (change.kind() != OutgoingDeltaScanner.Kind.MODIFIED) {
                throw new IOException("Added/deleted metadata needs separate review");
            }
        }
        try {
            return new OutgoingBslWritePlan(OutgoingBslPreviewPlan.of(
                delta.files().stream().map(x ->
                    x.relativePath().toString().replace('\\', '/')).toList()));
        } catch (IllegalArgumentException ex) {
            throw new IOException("Structural/form/unsupported changes require "
                + "a proven native EDT to Designer round trip", ex);
        }
    }

    public List<String> sourceFiles() {
        return selected.items().stream().map(x -> x.edtPath()).toList();
    }

    public List<String> platformFiles() { return selected.platformFiles(); }
    public Set<String> classicOwners() { return selected.classicOwners(); }

    public void verifyExport(Path nativeExport) throws IOException {
        selected.verifyExport(nativeExport);
    }

    /**
     * Two distinct allowlisted files. The XML and load list MUST both be
     * explicit and nonempty; neither command may default to all objects.
     */
    public void writeScopes(Path xmlObjects, Path loadFiles) throws IOException {
        if (xmlObjects == null || loadFiles == null
                || !xmlObjects.isAbsolute() || !loadFiles.isAbsolute()
                || xmlObjects.equals(loadFiles))
            throw new IOException("Separate absolute scope paths required");
        String xml = OutgoingStorageObjectsXml.create(classicOwners());
        List<String> names = platformFiles();
        if (names.isEmpty()) throw new IOException("No load files");
        // Load -listFile expects relative paths in the export root, UTF-8.
        Files.writeString(xmlObjects, xml, StandardCharsets.UTF_8);
        Files.writeString(loadFiles, String.join("\r\n", names) + "\r\n",
            StandardCharsets.UTF_8);
        if (Files.size(xmlObjects) == 0 || Files.size(loadFiles) == 0)
            throw new IOException("Empty native outgoing scopes");
    }
}
