package org.xander.edt.storagebridge.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * All metadata types are eligible for OBJECT-LEVEL review. This plan is
 * deliberately not a file list for Designer Load or a RepositoryLock list.
 * BSL, forms, templates, role rights, catalog/document/register descriptions,
 * and currently unknown EDT collections follow the same owner discovery.
 *
 * Deletion of a whole owner cannot be expressed by exporting an EObject:
 * retain it as an explicit deletion candidate, never silently skip it.
 */
public final class OutgoingMetadataObjectPlan {
    public record Owner(String path, List<OutgoingDeltaScanner.Change> changes,
            boolean added, boolean removed, boolean descriptorChanged) {
        public Owner {
            if (path == null || path.isBlank() || changes == null || changes.isEmpty())
                throw new IllegalArgumentException("Invalid outgoing owner");
            changes = List.copyOf(changes);
        }
        public boolean exportable() { return !removed; }
    }

    private final Map<String, Owner> owners;

    private OutgoingMetadataObjectPlan(Map<String, Owner> owners) {
        this.owners = Collections.unmodifiableMap(new LinkedHashMap<>(owners));
    }

    public static OutgoingMetadataObjectPlan fromDelta(
            OutgoingDeltaScanner.Delta delta) throws IOException {
        Objects.requireNonNull(delta);
        TreeMap<String, List<OutgoingDeltaScanner.Change>> grouped =
            new TreeMap<>();
        for (var change : delta.files()) {
            String detected = EdtPathObjectMapper.topObject(change.relativePath());
            if (detected == null || !detected.equals(change.edtOwner())) {
                throw new IOException("Outgoing owner classification mismatch");
            }
            grouped.computeIfAbsent(detected, key -> new ArrayList<>())
                .add(change);
        }
        var result = new LinkedHashMap<String, Owner>();
        for (var entry : grouped.entrySet()) {
            String owner = entry.getKey();
            var changes = entry.getValue();
            boolean added = false, removed = false, descriptorChanged = false;
            for (var change : changes) {
                if (isPrimaryDescriptor(change.relativePath(), owner)) {
                    descriptorChanged = true;
                    if (change.kind() == OutgoingDeltaScanner.Kind.ADDED) added = true;
                    if (change.kind() == OutgoingDeltaScanner.Kind.DELETED) removed = true;
                }
            }
            if (added && removed) {
                throw new IOException("Contradictory outgoing primary descriptor changes");
            }
            result.put(owner, new Owner(owner, changes,
                added, removed, descriptorChanged));
        }
        return new OutgoingMetadataObjectPlan(result);
    }

    public Map<String, Owner> owners() { return owners; }

    public List<String> exportableOwners() {
        return owners.values().stream().filter(Owner::exportable)
            .map(Owner::path).toList();
    }

    public List<String> removedOwners() {
        return owners.values().stream().filter(Owner::removed)
            .map(Owner::path).toList();
    }

    public int changedResourceCount() {
        return owners.values().stream().mapToInt(x -> x.changes().size()).sum();
    }

    /**
     * EDT primary metadata descriptors:
     * src/<collection>/<name>.mdo
     * src/<collection>/<name>/<name>.mdo
     * Both represent the SAME object, not children/form descriptors.
     */
    public static boolean isPrimaryDescriptor(Path resource, String owner) {
        if (resource == null || owner == null) return false;
        String[] segments = owner.split("/", -1);
        if (segments.length != 2) return false;
        Path flat = Path.of("src", segments[0], segments[1] + ".mdo");
        Path nested = Path.of("src", segments[0], segments[1],
            segments[1] + ".mdo");
        return resource.equals(flat) || resource.equals(nested);
    }
}
