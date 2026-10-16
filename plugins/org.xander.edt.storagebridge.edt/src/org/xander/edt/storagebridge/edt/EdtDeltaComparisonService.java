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
import com._1c.g5.v8.dt.compare.ui.editor.IDtComparisonEditorInput;
import com._1c.g5.v8.dt.compare.ui.editor.IDtComparisonEditorInputFactory;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.import_.IImportService;
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
    private final IDtComparisonEditorInputFactory editorInputs;

    @Inject
    public EdtDeltaComparisonService(IV8ProjectManager projectManager,
            IDtComparisonEditorInputFactory editorInputs) {
        this.projectManager = Objects.requireNonNull(projectManager);
        this.editorInputs = Objects.requireNonNull(editorInputs);
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
     * @return editor input to open in EDT's existing compare/merge editor.
     *         This method never starts merge or writes project files.
     */
    public IDtComparisonEditorInput prepare(IProject project,
            Path platformXmlDirectory, Path externalEdtDeltaDirectory,
            Path gitRepositoryDirectory, Path projectRelativePath,
            String baselineSha, List<String> objectSymlinks,
            IImportService importer, IProgressMonitor monitor)
            throws IOException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(importer, "importer");
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
        if (output.equals(projectRoot) || output.startsWith(projectRoot)
                || output.startsWith(root) || output.equals(root)
                || output.equals(xml) || output.startsWith(xml)) {
            throw new IllegalArgumentException(
                "Converted EDT delta must stay outside the project, Git and input XML");
        }
        if (Files.exists(output)) {
            try (Stream<Path> children = Files.list(output)) {
                if (!Files.isDirectory(output) || children.findAny().isPresent()) {
                    throw new IllegalArgumentException(
                        "Target staging directory must be empty");
                }
            }
        } else {
            Files.createDirectories(output);
        }

        // This FOUR-argument overload writes to an external directory.
        // The THREE-argument overload would modify the existing EDT project;
        // it is deliberately NOT called anywhere in this service.
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

        IDtComparisonEditorInput editor = editorInputs.create(
            List.of(descriptor), false, "Текущий проект EDT",
            "Изменения из хранилища 1С");
        editor.setRunInitialComparison(true);
        editor.setMergeConfirmationMessage(
            "Применить только отмеченные изменения? "
            + "Непринятые объекты, включая локально изменённые, останутся без изменений.");
        return editor;
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
