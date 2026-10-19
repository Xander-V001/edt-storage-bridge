package org.xander.edt.storagebridge.edt;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PartInitException;

import com._1c.g5.v8.dt.compare.core.CompareMergeProcessDescriptor;
import com._1c.g5.v8.dt.compare.core.IComparisonManager;
import com._1c.g5.v8.dt.compare.ui.editor.IDtComparisonEditorInputFactory;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.export.IExportOperationFactory;
import com._1c.g5.v8.dt.import_.IImportServiceRegistry;
import com._1c.g5.wiring.ServiceAccess;

/**
 * Runtime EDT services without creation of a separate Guice injector.
 *
 * EDT documents ServiceAccess as the public OSGi service entry point outside
 * a Guice environment. Resolve only the services required for each operation;
 * do not keep service instances across a long-running receive or an editor's
 * lifetime. This avoids the ArrayStoreException raised at the previous
 * BridgeActivator.getInjector() call in EDT 2026.2.1.3.
 */
public final class EdtPublicServiceBridge {
    private EdtPublicServiceBridge() {
    }

    /**
     * Read-only EDT project model inspection. Keep the EDT service types in
     * the EDT integration bundle: the UI bundle does not import the internal
     * com._1c.g5.v8.dt.core.platform package and must not instantiate its
     * own Guice injector (which failed in EDT 2026.2).
     *
     * Resolve the service only when the background inspection job runs.
     */
    public static boolean hasConfigurationModel(IProject project) {
        return new EdtProjectInspector(
            ServiceAccess.get(IConfigurationProvider.class))
            .hasConfigurationModel(project);
    }

    /** Explicit read-only native BSL export preview; no Designer or storage writes. */
    public static EdtNativeOutgoingExportService.Preview previewNativeBslExport(
            IProject project, List<String> selectedFiles, Path target,
            IProgressMonitor monitor) throws IOException {
        return new EdtNativeOutgoingExportService(
            ServiceAccess.get(IV8ProjectManager.class),
            ServiceAccess.get(IBmModelManager.class),
            ServiceAccess.get(IQualifiedNameFilePathConverter.class),
            ServiceAccess.get(IExportOperationFactory.class))
            .preview(project, selectedFiles, target, monitor);
    }

    /** Read-only whole-owner export for ALL native EDT metadata kinds. */
    public static EdtNativeOutgoingExportService.MetadataPreview
            previewNativeMetadataExport(IProject project, List<String> owners,
            Path target, IProgressMonitor monitor) throws IOException {
        return new EdtNativeOutgoingExportService(
            ServiceAccess.get(IV8ProjectManager.class),
            ServiceAccess.get(IBmModelManager.class),
            ServiceAccess.get(IQualifiedNameFilePathConverter.class),
            ServiceAccess.get(IExportOperationFactory.class))
            .previewMetadataOwners(project, owners, target, monitor);
    }

    /** Prepare external comparison without writing to the open EDT project. */
    public static CompareMergeProcessDescriptor prepare(IProject project,
            Path platformXml, Path convertedEdtDestination,
            List<String> selectedObjects, boolean initialFullReview,
            IProgressMonitor monitor)
            throws IOException {
        // ServiceAccess.get is explicitly supported by the EDT SDK for
        // infrequent, short-lived calls outside Guice. Do not store its result.
        var projectManager = ServiceAccess.get(IV8ProjectManager.class);
        var imports = ServiceAccess.get(IImportServiceRegistry.class);
        var converter = ServiceAccess.get(IQualifiedNameFilePathConverter.class);
        return new EdtSelectiveReviewService(projectManager, imports, converter)
            .prepare(project, platformXml, convertedEdtDestination,
                selectedObjects, initialFullReview, monitor);
    }

    /** Read-only native comparison for outgoing review; merge disabled. */
    public static IEditorPart openOutgoingReadOnlyEditor(
            CompareMergeProcessDescriptor descriptor)
            throws PartInitException {
        return EdtDeltaComparisonService.openReadOnlyEditor(
            descriptor, ServiceAccess.get(IDtComparisonEditorInputFactory.class));
    }

    /**
     * Read the public EDT comparison manager's own process status for
     * diagnostics. A missing/evicted status is UNKNOWN, never evidence
     * of a successful or failed merge. This does not run a merge or compare
     * text: it only asks the EDT engine about its native job.
     */
    public static String incomingMergeStatus(
            CompareMergeProcessDescriptor descriptor) {
        if (descriptor == null) return "UNKNOWN";
        try {
            IComparisonManager manager = ServiceAccess.get(IComparisonManager.class);
            if (manager == null) return "UNAVAILABLE";
            var status = manager.getStatus(descriptor.getHandle());
            return status == null ? "UNKNOWN" : status.name();
        } catch (RuntimeException ex) {
            return "UNAVAILABLE";
        }
    }

    /** Must run on SWT UI thread; the public EDT editor factory is resolved on demand. */
    public static IEditorPart openEditor(CompareMergeProcessDescriptor descriptor)
            throws PartInitException {
        return EdtDeltaComparisonService.openEditor(
            descriptor, ServiceAccess.get(IDtComparisonEditorInputFactory.class));
    }
}
