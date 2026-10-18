package org.xander.edt.storagebridge.edt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.naming.QualifiedName;

import com._1c.g5.v8.bm.core.BmPlatform;
import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.export.IExportOperationFactory;
import com._1c.g5.v8.dt.export.IExportStrategy;
import org.xander.edt.storagebridge.platform.OutgoingBslPreviewPlan;

/**
 * Explicit native EDT export of selected top objects into a separate directory.
 * No Git mutation, no Designer process, no storage Lock/Load/Commit.
 * A whole-owner export is NOT the partial load list.
 */
public final class EdtNativeOutgoingExportService {
    private final IV8ProjectManager projects;
    private final IBmModelManager models;
    private final IQualifiedNameFilePathConverter converter;
    private final IExportOperationFactory exports;

    public record Preview(Path directory, List<String> exportedOwners,
            List<String> platformLoadCandidates) {
        public Preview {
            exportedOwners = List.copyOf(exportedOwners);
            platformLoadCandidates = List.copyOf(platformLoadCandidates);
        }
    }

    public EdtNativeOutgoingExportService(IV8ProjectManager projects,
            IBmModelManager models, IQualifiedNameFilePathConverter converter,
            IExportOperationFactory exports) {
        this.projects = Objects.requireNonNull(projects);
        this.models = Objects.requireNonNull(models);
        this.converter = Objects.requireNonNull(converter);
        this.exports = Objects.requireNonNull(exports);
    }

    /**
     * Export WHOLE top-level EDT metadata owners of arbitrary collections.
     * The native EDT FQN (not a Russian/English type-name guess) resolves
     * the object. Forms, role rights, registers, pictures, templates, unknown
     * external resources etc. are delegated to EDT's export strategy.
     *
     * This is a diagnostic / read-only export. Its file inventory is NOT
     * an approved Designer -listFile. New objects, deletions, configuration
     * root and dependencies still require dedicated native round-trip proof.
     */
    public record MetadataPreview(Path directory, List<String> edtOwners,
            List<String> nativeQualifiedNames, List<String> outputFiles) {
        public MetadataPreview {
            edtOwners = List.copyOf(edtOwners);
            nativeQualifiedNames = List.copyOf(nativeQualifiedNames);
            outputFiles = List.copyOf(outputFiles);
        }
    }

    public MetadataPreview previewMetadataOwners(IProject project,
            List<String> ownerPaths, Path destination, IProgressMonitor monitor)
            throws IOException {
        Objects.requireNonNull(project);
        Objects.requireNonNull(ownerPaths);
        Objects.requireNonNull(monitor);
        if (ownerPaths.isEmpty() || ownerPaths.size() > 10_000
                || !project.isAccessible() || monitor.isCanceled()
                || project.getLocation() == null) {
            throw new IOException("No available project or metadata owners");
        }
        var v8Project = projects.getProject(project);
        if (v8Project == null) {
            throw new IOException("V8 project model is unavailable");
        }
        Path active = project.getLocation().toFile().toPath().toRealPath();
        Path out = Objects.requireNonNull(destination).toRealPath();
        if (!Files.isDirectory(out, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(out) || out.startsWith(active)
                || active.startsWith(out)) {
            throw new IOException("Unsafe EDT export directory");
        }
        try (Stream<Path> files = Files.list(out)) {
            if (files.findAny().isPresent()) {
                throw new IOException("Native export destination is not empty");
            }
        }

        var names = new LinkedHashMap<String, String>();
        var uniquePaths = new java.util.LinkedHashSet<String>();
        for (String ownerPath : ownerPaths) {
            if (ownerPath == null || !uniquePaths.add(ownerPath)
                    || !ownerPath.matches(
                        "[\\p{L}_][\\p{L}\\p{N}_]*/[\\p{L}_][\\p{L}\\p{N}_]*")) {
                throw new IOException("Invalid or repeated EDT top-level owner");
            }
            String[] pair = ownerPath.split("/");
            String base = "src/" + pair[0] + "/" + pair[1];
            IFile nested = project.getFile(new org.eclipse.core.runtime.Path(
                base + "/" + pair[1] + ".mdo"));
            IFile flat = project.getFile(new org.eclipse.core.runtime.Path(
                base + ".mdo"));
            if (nested.exists() == flat.exists()) {
                throw new IOException("Top-level EDT descriptor is absent or ambiguous");
            }
            IFile descriptor = nested.exists() ? nested : flat;
            if (descriptor.isLinked() || descriptor.getLocation() == null) {
                throw new IOException("Metadata descriptor is linked or absent");
            }
            Path file = descriptor.getLocation().toFile().toPath();
            if (Files.isSymbolicLink(file)
                    || !file.toRealPath().startsWith(active)) {
                throw new IOException("Metadata descriptor escapes EDT project");
            }
            QualifiedName qualified = converter.getFqn(descriptor);
            if (qualified == null || qualified.getSegmentCount() == 0
                    || !pair[1].equals(qualified.getLastSegment())) {
                // Configuration's root FQN may be a single "Configuration".
                // Never infer a metadata FQN from a filesystem path.
                throw new IOException("Native EDT metadata FQN unavailable or mismatched");
            }
            if (names.putIfAbsent(ownerPath, qualified.toString()) != null) {
                throw new IOException("Duplicate metadata owner");
            }
        }

        BmPlatform bm = models.getBmPlatform();
        IBmNamespace ns = models.getBmNamespace(project);
        if (bm == null || ns == null) throw new IOException("EDT model is unavailable");
        IBmPlatformTransaction txn = bm.beginReadOnlyTransaction(true);
        var resolved = new LinkedHashMap<String, EObject>();
        try {
            for (var item : names.entrySet()) {
                if (monitor.isCanceled()) {
                    throw new IOException("EDT metadata export cancelled");
                }
                EObject object = (EObject) txn.getTopObjectByFqn(
                    ns, item.getValue());
                if (object == null) {
                    throw new IOException("Selected native EDT top-level object not found");
                }
                resolved.put(item.getKey(), object);
            }
        } finally {
            txn.commit();
        }
        if (monitor.isCanceled()) {
            throw new IOException("EDT metadata export cancelled");
        }

        EObject[] objects = resolved.values().toArray(EObject[]::new);
        IStatus status = exports.createExportOperation(
            out, v8Project.getVersion(), IExportStrategy.DEFAULT, objects)
            .run(monitor);
        if (status == null || !status.isOK() || monitor.isCanceled()) {
            throw new IOException("Native EDT whole-object export failed");
        }

        var output = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(out)) {
            for (Path file : files.toList()) {
                if (Files.isSymbolicLink(file)) {
                    throw new IOException("Symlink found in native export");
                }
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unexpected native export entry");
                }
                output.add(out.relativize(file).toString().replace('\\', '/'));
                if (output.size() > 500_000) {
                    throw new IOException("Export file limit exceeded");
                }
            }
        }
        if (output.isEmpty()) {
            throw new IOException("Native EDT exporter wrote no files");
        }
        output.sort(String::compareTo);
        return new MetadataPreview(out, new ArrayList<>(names.keySet()),
            new ArrayList<>(names.values()), output);
    }

    public Preview preview(IProject project, List<String> selectedFiles,
            Path destination, IProgressMonitor monitor) throws IOException {
        Objects.requireNonNull(project);
        Objects.requireNonNull(monitor);
        if (!project.isAccessible() || monitor.isCanceled())
            throw new IOException("EDT project unavailable or operation cancelled");
        var plan = OutgoingBslPreviewPlan.of(selectedFiles);
        var v8Project = projects.getProject(project);
        if (v8Project == null)
            throw new IOException("V8 project model unavailable");
        if (project.getLocation() == null)
            throw new IOException("EDT project location unavailable");
        Path root = project.getLocation().toFile().toPath().toRealPath();
        Path target = Objects.requireNonNull(destination).toRealPath();
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(target)
                || target.startsWith(root) || root.startsWith(target))
            throw new IOException("Export directory overlaps project or is unsafe");
        try (Stream<Path> entries = Files.list(target)) {
            if (entries.findAny().isPresent())
                throw new IOException("Native export directory must be empty");
        }
        var owners = new LinkedHashMap<String, EObject>();
        for (var selected : plan.items()) {
            IFile resource = project.getFile(
                new org.eclipse.core.runtime.Path(selected.edtPath()));
            if (!resource.exists() || resource.isLinked()
                    || resource.getLocation() == null)
                throw new IOException("Selected workspace file missing or linked");
            Path disk = resource.getLocation().toFile().toPath();
            if (Files.isSymbolicLink(disk) || !disk.toRealPath().startsWith(root))
                throw new IOException("Selected file escapes EDT project");
            QualifiedName fqn = converter.getFqn(resource);
            if (fqn == null || fqn.getSegmentCount() < 2)
                throw new IOException("EDT FQN for selected resource unavailable");
            String owner = fqn.skipLast(fqn.getSegmentCount() - 2).toString();
            if (!owner.equals(selected.classicOwner()))
                throw new IOException("EDT FQN differs from expected module owner");
            owners.putIfAbsent(owner, null);
        }

        BmPlatform platform = models.getBmPlatform();
        IBmNamespace namespace = models.getBmNamespace(project);
        if (platform == null || namespace == null)
            throw new IOException("EDT BM namespace unavailable");
        IBmPlatformTransaction transaction = platform.beginReadOnlyTransaction(true);
        try {
            for (String owner : new ArrayList<>(owners.keySet())) {
                if (monitor.isCanceled())
                    throw new IOException("Native export cancelled");
                EObject obj = (EObject) transaction.getTopObjectByFqn(namespace, owner);
                if (obj == null)
                    throw new IOException("EDT top-level metadata object not found");
                owners.put(owner, obj);
            }
        } finally {
            transaction.commit();
        }
        if (monitor.isCanceled())
            throw new IOException("Native export cancelled");

        // Native EDT factory exports subordinate / external / unknown objects.
        // Do not feed the full output to partial Designer Load.
        EObject[] objects = owners.values().toArray(EObject[]::new);
        IStatus status = exports.createExportOperation(
            target, v8Project.getVersion(), IExportStrategy.DEFAULT, objects)
            .run(monitor);
        if (status == null || !status.isOK() || monitor.isCanceled())
            throw new IOException("Native EDT export returned non-OK status");
        plan.verifyExport(target);
        return new Preview(target, new ArrayList<>(owners.keySet()),
            plan.platformFiles());
    }
}
