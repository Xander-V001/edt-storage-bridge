package org.xander.edt.storagebridge.edt;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PartInitException;

import com._1c.g5.v8.dt.compare.core.CompareMergeProcessDescriptor;
import com._1c.g5.v8.dt.compare.ui.editor.IDtComparisonEditorInputFactory;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
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

    /** Must run on SWT UI thread; the public EDT editor factory is resolved on demand. */
    public static IEditorPart openEditor(CompareMergeProcessDescriptor descriptor)
            throws PartInitException {
        return EdtDeltaComparisonService.openEditor(
            descriptor, ServiceAccess.get(IDtComparisonEditorInputFactory.class));
    }
}
