package org.xander.edt.storagebridge.edt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;

import com._1c.g5.v8.dt.compare.core.CompareMergeProcessDescriptor;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessSettings;
import com._1c.g5.v8.dt.compare.core.ComparisonScope;
import com._1c.g5.v8.dt.compare.datasource.FileSystemComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.compare.datasource.GitComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.compare.datasource.V8ProjectComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.compare.matching.MatchingStrategy;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.import_.IImportService;
import com._1c.g5.v8.dt.import_.IImportServiceRegistry;
import com._1c.g5.v8.dt.compare.ui.editor.IDtComparisonEditorInput;
import com._1c.g5.v8.dt.compare.ui.editor.IDtComparisonEditorInputFactory;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import com.google.inject.Inject;

/**
 * Prepare native EDT THREE-WAY comparison without writing any incoming object
 * into the active EDT project. The infobase bound to classical storage is
 * NOT connected to EDT and is never accessed by this class.
 *
 * This is the conversion/comparison stage of selective receive, NOT an
 * end-to-end receive command. Callers own the staged directories and MUST keep
 * them alive for the entire lifetime of the EDT comparison editor.
 */
public final class EdtDeltaComparisonService {
    private final IV8ProjectManager projectManager;
    private final IImportServiceRegistry importRegistry;
    private final IDtComparisonEditorInputFactory inputFactory;

    @Inject
    public EdtDeltaComparisonService(IV8ProjectManager projectManager,
            IImportServiceRegistry importRegistry,
            IDtComparisonEditorInputFactory inputFactory) {
        this.projectManager = Objects.requireNonNull(projectManager);
        this.importRegistry = Objects.requireNonNull(importRegistry);
        this.inputFactory = Objects.requireNonNull(inputFactory);
    }

    /**
     * Convert ONLY selected platform XML objects into a separate EDT folder.
     *
     * @param importer official EDT import implementation selected by the
     *                 platform integration, NOT an internal class constructed here
     * @param objectSymlinks EDT symbolic links of the changed MD objects.
     *                       The caller must resolve the mapping; 1C Russian
     *                       object names must NOT be guessed to be EDT symlinks.
     * @param baselineSha immutable Git SHA corresponding to the source storage
     *                    revision before remote changes
     * @param projectRelativePath path to project directory INSIDE Git repository
     * @return comparison descriptor for the native EDT editor (open later on
     *         SWT UI thread). This method never starts merge or writes project files.
     */
    public CompareMergeProcessDescriptor prepare(IProject project,
            Path platformXmlDirectory, Path externalEdtDeltaDirectory,
            Path gitRepositoryDirectory, Path projectRelativePath,
            String baselineSha, List<String> objectSymlinks,
            IProgressMonitor monitor)
            throws IOException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(monitor, "monitor");

        IV8Project edtProject = projectManager.getProject(project);
        if (!project.isAccessible() || edtProject == null) {
            throw new IllegalStateException("This must be an opened existing EDT project");
        }
        if (monitor.isCanceled()) {
            throw new IllegalStateException("EDT comparison preparation cancelled");
        }
        validateGitBaseline(gitRepositoryDirectory, projectRelativePath, baselineSha);
        validateSymlinks(objectSymlinks);

        Path xml = Objects.requireNonNull(platformXmlDirectory).toRealPath();
        Path root = Objects.requireNonNull(gitRepositoryDirectory).toRealPath();
        Path output = Objects.requireNonNull(externalEdtDeltaDirectory)
            .toAbsolutePath().normalize();
        Path projectRoot = project.getLocation().toFile().toPath().toRealPath();
        if (!Files.isDirectory(xml) || !Files.isDirectory(root)
                || !Files.isDirectory(projectRoot)) {
            throw new IllegalArgumentException("Invalid source or Git directory");
        }
        // Resolve the *parent* first: a symbolic link in the output path
        // must never redirect an external staging write into the Git checkout.
        Path parent = output.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IllegalArgumentException(
                "Staging parent directory must exist outside Git");
        }
        Path realParent = parent.toRealPath();
        if (realParent.startsWith(projectRoot) || realParent.startsWith(root)
                || realParent.startsWith(xml)
                || xml.startsWith(realParent.resolve(output.getFileName()))) {
            throw new IllegalArgumentException(
                "Staging destination must be independent of EDT, Git and input XML");
        }
        if (Files.isSymbolicLink(output)) {
            throw new IllegalArgumentException("Symlink destination not allowed");
        }
        if (Files.exists(output)) {
            try (Stream<Path> children = Files.list(output)) {
                if (!Files.isDirectory(output) || children.findAny().isPresent()) {
                    throw new IllegalArgumentException(
                        "Target staging directory must be empty");
                }
            }
        } else {
            Files.createDirectory(output);
        }
        Path canonicalOutput = output.toRealPath();
        if (canonicalOutput.startsWith(projectRoot)
                || canonicalOutput.startsWith(root) || canonicalOutput.startsWith(xml)) {
            throw new IllegalArgumentException("Unsafe staging location");
        }

        // This FOUR-argument overload writes to an external directory.
        // The THREE-argument overload would modify the existing EDT project;
        // it is deliberately NOT called anywhere in this service.
        // Resolve the importer matching the CURRENT project's 1C runtime
        // version. Never guess based on plugin/EDT version or use an unrelated IB.
        IImportService importer;
        try {
            importer = importRegistry.getImportService(edtProject.getVersion());
        } catch (Exception ex) {
            throw new IOException("Cannot resolve XML importer for this EDT project", ex);
        }
        IStatus status = importer.work(project, xml, output, monitor);
        if (status == null || !status.isOK() || monitor.isCanceled()) {
            throw new IllegalStateException("EDT external XML conversion failed or cancelled");
        }
        try (Stream<Path> produced = Files.walk(output)) {
            if (produced.noneMatch(Files::isRegularFile)) {
                throw new IllegalStateException("No EDT files were produced");
            }
        }

        var current = new V8ProjectComparisonDataSourceDescriptor(edtProject);
        var incomingDelta = new FileSystemComparisonDataSourceDescriptor(
            output, project.getName());
        var ancestor = new GitComparisonDataSourceDescriptor(root,
            baselineSha, projectRelativePath);
        List<String> scope = List.copyOf(objectSymlinks);
        var handle = new ComparisonProcessHandle(current, incomingDelta,
            ancestor, new ComparisonScope(scope, scope, scope));
        var settings = ComparisonProcessSettings
            .builder(MatchingStrategy.UUID_THEN_NAME).build();
        var descriptor = new CompareMergeProcessDescriptor(handle, settings);

        return descriptor;
    }

    /**
     * Opens native EDT comparison via its exported editor-input factory and
     * the standard Eclipse workbench editor extension registry.
     *
     * The editor implementation lives in a non-exported vendor package. Never
     * import or instantiate it directly: Eclipse owns its lifecycle. The
     * registered editor ID is resolved from the extension instead of guessed.
     * External staging must remain available until the editor is closed.
     *
     * Must run on the SWT UI thread. Preparation itself remains read-only;
     * a merge is possible only through the native EDT editor interaction.
     */
    public IEditorPart openEditor(CompareMergeProcessDescriptor prepared)
            throws PartInitException {
        return openEditor(prepared, inputFactory);
    }

    /**
     * Variant for short-lived public EDT service access outside Guice.
     * Both variants use exactly the same native editor implementation.
     */
    public static IEditorPart openEditor(CompareMergeProcessDescriptor prepared,
            IDtComparisonEditorInputFactory inputFactory)
            throws PartInitException {
        Objects.requireNonNull(inputFactory, "inputFactory");
        Objects.requireNonNull(prepared, "prepared");
        if (Display.getCurrent() == null) {
            throw new IllegalStateException(
                "Native EDT comparison editor requires SWT UI thread");
        }

        // Resolve the editor extension BEFORE creating the editor input or
        // triggering comparison. A missing extension is a hard failure.
        String editorId = findNativeComparisonEditorId();
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null || window.getActivePage() == null) {
            throw new PartInitException("No active Eclipse workbench page");
        }

        IDtComparisonEditorInput input = inputFactory.create(
            List.of(prepared), false,
            "Текущий проект EDT", "Изменения из хранилища 1С");
        input.setRunInitialComparison(true);
        // EDT's native TWO-WAY comparison preselects differences for
        // 'Take right' by default. A manually selected review scope alone
        // is NOT a protection against overwriting committed local edits.
        input.setMergeConfirmationMessage(
            "ВНИМАНИЕ: при двустороннем сравнении EDT по умолчанию "
            + "предлагает взять отличающиеся значения второго источника. "
            + "Перед объединением проверьте ВСЕ отмеченные узлы, снимите "
            + "флажки с локальных изменений, которые нужно сохранить. "
            + "Восстановление из хранилища Git автоматически НЕ выполняется.");
        return window.getActivePage().openEditor(input, editorId);
    }

    /**
     * The implementation is intentionally identified by Eclipse's own
     * org.eclipse.ui.editors declaration, not by a Java package import.
     * Reject ambiguous/missing registrations; never choose a generic editor.
     */
    private static String findNativeComparisonEditorId() throws PartInitException {
        String result = null;
        for (IConfigurationElement extension : Platform.getExtensionRegistry()
                .getConfigurationElementsFor("org.eclipse.ui.editors")) {
            if (!"editor".equals(extension.getName())) {
                continue;
            }
            String implementation = extension.getAttribute("class");
            if (implementation == null
                    || !implementation.contains("DtComparisonEditor")) {
                continue;
            }
            String id = extension.getAttribute("id");
            if (id == null || id.isBlank()) {
                continue;
            }
            if (result != null && !result.equals(id)) {
                throw new PartInitException(
                    "Ambiguous native EDT comparison editor registrations");
            }
            result = id;
        }
        if (result == null) {
            throw new PartInitException(
                "Native EDT comparison editor not registered in this EDT installation");
        }
        return result;
    }

    private static void validateGitBaseline(Path root, Path relativeProject,
            String baselineSha) {
        Objects.requireNonNull(root, "gitRepositoryDirectory");
        Objects.requireNonNull(relativeProject, "projectRelativePath");
        if (!root.isAbsolute() || relativeProject.isAbsolute()
                || relativeProject.normalize().startsWith("..")) {
            throw new IllegalArgumentException("Invalid Git project path");
        }
        if (baselineSha == null
                || !baselineSha.matches("[a-fA-F0-9]{40}")) {
            throw new IllegalArgumentException(
                "Verified 40-character Git baseline SHA is mandatory");
        }
        // Existence and correspondence of this SHA to a CLASSICAL STORAGE
        // revision must be verified by the caller; never infer from HEAD.
    }

    private static void validateSymlinks(List<String> symlinks) {
        Objects.requireNonNull(symlinks, "objectSymlinks");
        if (symlinks.isEmpty() || symlinks.size() > 100_000
                || symlinks.stream().anyMatch(x -> x == null || x.isBlank())
                || symlinks.stream().distinct().count() != symlinks.size()) {
            throw new IllegalArgumentException(
                "Explicit unique changed-object EDT symlinks required");
        }
    }
}
