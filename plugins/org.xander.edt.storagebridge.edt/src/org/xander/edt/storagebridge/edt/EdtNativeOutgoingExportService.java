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
