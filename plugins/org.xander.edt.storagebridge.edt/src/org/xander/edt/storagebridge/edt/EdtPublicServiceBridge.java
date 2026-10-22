package org.xander.edt.storagebridge.edt;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.FrameworkUtil;
import com.google.inject.Injector;
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

    /** Read-only whole-owner export for ALL native EDT metadata kinds. */
    public static EdtNativeOutgoingExportService.MetadataPreview
            previewNativeMetadataExport(IProject project, List<String> owners,
            Path target, IProgressMonitor monitor) throws IOException {
        // Project/model/path services are public OSGi services. Export's
        // IExportOperationFactory is different: it is wired by the native
        // EDT export bundle's Guice injector, not published via ServiceAccess.
        // Never create a second injector or fall back to an unrelated exporter.
        var projects = requiredExportService(IV8ProjectManager.class);
        var models = requiredExportService(IBmModelManager.class);
        var paths = requiredExportService(IQualifiedNameFilePathConverter.class);
        var exports = nativeExportFactory();
        return new EdtNativeOutgoingExportService(
            projects, models, paths, exports)
            .previewMetadataOwners(project, owners, target, monitor);
    }

    public static final class ExportServiceUnavailable extends IOException {
        private final String type;

        private ExportServiceUnavailable(String type, Throwable cause) {
            super("Native EDT export service unavailable: " + type, cause);
            this.type = type;
        }

        public String type() { return type; }
    }

    private static <T> T requiredExportService(Class<T> type) throws IOException {
        try {
            return ServiceAccess.get(type);
        } catch (com._1c.g5.wiring.ServiceUnavailableException ex) {
            // No third-party exception text or model identity in diagnostic file.
            throw new ExportServiceUnavailable(type.getSimpleName(), ex);
        }
    }

    /**
     * Resolve the factory from EDT's OWN export plug-in, whose Injector holds
     * the ExportRuntimeModule bindings and native export dependencies.
     *
     * ExportPlugin resides in an internal OSGi package which is not part of
     * EDT's guaranteed package-export contract. Loading the class using the
     * bundle that owns the PUBLIC IExportOperationFactory API allows us to
     * invoke its documented public getDefault()/getInjector() methods without
     * adding an unsatisfiable Import-Package for a private implementation.
     *
     * This does not instantiate a new injector, activate untrusted extensions,
     * reflect over the BM model or run a separate export strategy. If the
     * native export bundle cannot provide its factory, fail BEFORE touching 1C.
     */
    private static IExportOperationFactory nativeExportFactory()
            throws ExportServiceUnavailable {
        try {
            Bundle exporter = FrameworkUtil.getBundle(IExportOperationFactory.class);
            if (exporter == null) {
                throw new IllegalStateException("EDT export API has no OSGi bundle");
            }
            // A package import alone need not start a lazily activated bundle.
            // Start the existing bundle, do not create a separate Guice injector.
            if (exporter.getState() != Bundle.ACTIVE) {
                exporter.start(Bundle.START_TRANSIENT);
            }
            Class<?> pluginClass = exporter.loadClass(
                "com._1c.g5.v8.dt.internal.export.ExportPlugin");
            Object plugin = pluginClass.getMethod("getDefault").invoke(null);
            if (plugin == null) {
                throw new IllegalStateException("Native export bundle not activated");
            }
            Object supplied = pluginClass.getMethod("getInjector").invoke(plugin);
            if (!(supplied instanceof Injector injector)) {
                throw new IllegalStateException("Native export Guice injector unavailable");
            }
            // Guice's binding is owned and managed by EDT's export plug-in.
            IExportOperationFactory factory =
                injector.getInstance(IExportOperationFactory.class);
            if (factory == null) {
                throw new IllegalStateException("Native export factory unavailable");
            }
            return factory;
        } catch (ReflectiveOperationException | BundleException
                | RuntimeException | LinkageError ex) {
            throw new ExportServiceUnavailable(
                IExportOperationFactory.class.getSimpleName(), ex);
        }
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

    /** Prove that deleted top-level metadata actually disappeared after EDT Merge. */
    public static java.util.Set<String> confirmedRemovedTopLevel(
            IProject project, java.util.Set<String> missingFromRepository)
            throws IOException {
        if (project == null || !project.isAccessible()
                || project.getLocation() == null) {
            throw new IOException("EDT project unavailable for deletion verification");
        }
        return EdtSelectiveReviewService.confirmedRemovedTopLevel(
            project.getLocation().toFile().toPath(), missingFromRepository);
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

    /**
     * Register BEFORE opening the editor so merge-finished events are not
     * lost when EDT disposes a comparison session on editor close.
     */
    public static NativeEdtMergeWitness observeIncomingMerge(
            CompareMergeProcessDescriptor descriptor) {
        try {
            return NativeEdtMergeWitness.observe(
                ServiceAccess.get(IComparisonManager.class),
                descriptor.getHandle());
        } catch (RuntimeException ex) {
            return NativeEdtMergeWitness.unavailable();
        }
    }

    /** Must run on SWT UI thread; the public EDT editor factory is resolved on demand. */
    public static IEditorPart openEditor(CompareMergeProcessDescriptor descriptor)
            throws PartInitException {
        return EdtDeltaComparisonService.openEditor(
            descriptor, ServiceAccess.get(IDtComparisonEditorInputFactory.class));
    }
}
