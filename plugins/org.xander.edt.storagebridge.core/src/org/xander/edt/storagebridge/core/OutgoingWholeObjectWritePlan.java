package org.xander.edt.storagebridge.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.xander.edt.storagebridge.platform.OutgoingStorageObjectsXml;

/**
 * A conservative candidate for TEST-ONLY whole-object native round-trip.
 *
 * Not tied to a list of metadata TYPES: source owner identity is resolved
 * by the EDT model; the platform export's actual files are independently
 * validated. Only root owner XML goes in Designer listFile; subordinate
 * resources stay in the export tree for the platform to load.
 *
 * A top-level addition/deletion uses the Configuration parent XML.
 * Root membership is loaded by the platform itself, not edited as text.
 */
public final class OutgoingWholeObjectWritePlan {
    private final List<String> edtOwners;
    private final List<String> nativeNames;
    // Native EDT exports the entire object tree. Designer loads each
    // specified root and reads its children from that same directory.
    private final List<String> exportFiles;
    private final List<String> loadFiles;
    private final List<String> lockNames;
    private final boolean recursiveRoot;

    private OutgoingWholeObjectWritePlan(List<String> edtOwners,
            List<String> nativeNames, List<String> exportFiles,
            List<String> loadFiles, List<String> lockNames,
            boolean recursiveRoot) {
        this.lockNames = List.copyOf(lockNames);
        this.recursiveRoot = recursiveRoot;
        this.edtOwners = List.copyOf(edtOwners);
        this.nativeNames = List.copyOf(nativeNames);
        this.exportFiles = List.copyOf(exportFiles);
        this.loadFiles = List.copyOf(loadFiles);
    }

    public static OutgoingWholeObjectWritePlan qualify(
            OutgoingMetadataObjectPlan delta,
            List<String> exportedOwners, List<String> exportedNativeNames,
            List<String> files) throws IOException {
        if (delta == null || exportedOwners == null || exportedNativeNames == null
                || files == null || exportedOwners.isEmpty()
                || exportedOwners.size() != exportedNativeNames.size()
                || files.isEmpty() || files.size() > 500_000) {
            throw new IOException("Incomplete whole-object export");
        }
        if (!new LinkedHashSet<>(exportedOwners).equals(
                new LinkedHashSet<>(delta.exportableOwners()))
                || exportedOwners.size() != delta.exportableOwners().size()) {
            throw new IOException("Native export did not cover all current owners and parent");
        }
        var roots = new LinkedHashMap<String, String>();
        var expectedNames = new LinkedHashSet<String>();
        for (int i = 0; i < exportedOwners.size(); i++) {
            String owner = exportedOwners.get(i);
            String name = exportedNativeNames.get(i);
            if ("Configuration/Configuration".equals(owner)) {
                if (!"Configuration".equals(name)) {
                    throw new IOException("EDT root identity differs from 1C");
                }
                expectedNames.add("Configuration");
                roots.put(owner, "Configuration.xml");
                continue;
            }
            String[] parts = owner.split("/", -1);
            if (parts.length != 2 || !owner.matches(
                    "[\\p{L}_][\\p{L}\\p{N}_]*/[\\p{L}_][\\p{L}\\p{N}_]*")
                    || name == null
                    || !name.matches(
                        "[\\p{L}_][\\p{L}\\p{N}_]*\\.[\\p{L}_][\\p{L}\\p{N}_]*")
                    || !name.endsWith("." + parts[1])
                    || !expectedNames.add(name)) {
                throw new IOException("Unverified native owner identity");
            }
            roots.put(owner, owner + ".xml");
        }
        // The -Objects XML serializer independently validates native names.
        OutgoingStorageObjectsXml.createWithRoot(Set.copyOf(expectedNames),
            delta.removesTopLevelOwners());
        var uniqueFiles = new LinkedHashSet<String>();
        var mainFiles = new LinkedHashSet<String>();
        for (String path : files) {
            if (path == null || path.isBlank() || path.indexOf('\\') >= 0
                    || path.startsWith("/") || path.startsWith(".")
                    || path.contains("..") || path.indexOf('\n') >= 0
                    || path.indexOf('\r') >= 0
                    || !uniqueFiles.add(path)) {
                throw new IOException("Unsafe or duplicate native exported artifact");
            }
            if ("ConfigDumpInfo.xml".equals(path)) continue;
            boolean matched = false;
            for (var entry : roots.entrySet()) {
                String nativeRoot = entry.getKey();
                if (path.equals(entry.getValue())) {
                    mainFiles.add(path);
                    matched = true;
                    break;
                }
                if (path.startsWith(nativeRoot + "/")) {
                    matched = true;
                    break;
                }
            }
            // A native Configuration export can contain unrelated source
            // resources. They remain on disk but are NOT in -listFile.
            if (!matched && !expectedNames.contains("Configuration")) {
                throw new IOException("Native export includes objects outside chosen scope");
            }
        }
        if (!mainFiles.containsAll(roots.values())) {
            throw new IOException("Whole-object XML descriptors are missing");
        }
        // Official 1C partial import: specifying the owner XML imports
        // this owner with its subordinate objects from the export directory.
        // Do not request every module/form/resource as a separate operation.
        var capture = new LinkedHashSet<String>();
        if (expectedNames.contains("Configuration")) capture.add("Configuration");
        for (var entry : delta.owners().entrySet()) {
            if (entry.getValue().added()) continue; // No such object in storage yet.
            String fullName = null;
            for (int i = 0; i < exportedOwners.size(); i++) {
                if (entry.getKey().equals(exportedOwners.get(i))) {
                    fullName = exportedNativeNames.get(i);
                    break;
                }
            }
            if (fullName != null) capture.add(fullName);
        }
        if (delta.removesTopLevelOwners()) {
            // Capture all children of Configuration so deleted children
            // are captured before their parent XML removes them.
            capture.add("Configuration");
        }
        return new OutgoingWholeObjectWritePlan(exportedOwners,
            exportedNativeNames, new ArrayList<>(uniqueFiles),
            new ArrayList<>(roots.values()), new ArrayList<>(capture),
            delta.removesTopLevelOwners());
    }

    public List<String> edtOwners() { return edtOwners; }
    public List<String> nativeNames() { return nativeNames; }
    public List<String> loadFiles() { return loadFiles; }
    public Set<String> lockOwners() {
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(lockNames));
    }

    public boolean capturesAllRootChildren() { return recursiveRoot; }

    public Set<String> classicOwners() {
        return java.util.Collections.unmodifiableSet(
            new LinkedHashSet<>(nativeNames));
    }

    public void writeScopes(Path lockObjects, Path commitObjects,
            Path loadList) throws IOException {
        if (lockObjects == null || commitObjects == null || loadList == null
                || !lockObjects.isAbsolute() || !commitObjects.isAbsolute()
                || !loadList.isAbsolute()
                || lockObjects.equals(loadList) || commitObjects.equals(loadList)
                || lockObjects.equals(commitObjects) || loadFiles.isEmpty()) {
            throw new IOException("Invalid native Lock / Load / Commit scope");
        }
        Files.writeString(lockObjects,
            OutgoingStorageObjectsXml.createWithRoot(lockOwners(), recursiveRoot),
            StandardCharsets.UTF_8);
        Files.writeString(commitObjects,
            OutgoingStorageObjectsXml.createWithRoot(classicOwners(), recursiveRoot),
            StandardCharsets.UTF_8);
        // Designer consistently recognizes UTF-8 BOM on Windows even for
        // metadata whose names are Cyrillic; keep CRLF like native dump lists.
        byte[] body = (String.join("\r\n", loadFiles) + "\r\n")
            .getBytes(StandardCharsets.UTF_8);
        byte[] utf8Bom = new byte[body.length + 3];
        utf8Bom[0] = (byte) 0xEF;
        utf8Bom[1] = (byte) 0xBB;
        utf8Bom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, utf8Bom, 3, body.length);
        Files.write(loadList, utf8Bom);
    }

    /** All export artifacts are verified before passing to Designer. */
    public void verifyFiles(Path exportRoot) throws IOException {
        if (exportRoot == null || !Files.isDirectory(exportRoot,
                LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(exportRoot)) {
            throw new IOException("Whole-object export directory unavailable");
        }
        for (String name : exportFiles) {
            Path target = exportRoot;
            for (String segment : name.split("/")) {
                target = target.resolve(segment);
                if (Files.isSymbolicLink(target)) {
                    throw new IOException("Symlink in whole-object native export");
                }
            }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Expected native whole-object artifact missing");
            }
        }
    }
}
