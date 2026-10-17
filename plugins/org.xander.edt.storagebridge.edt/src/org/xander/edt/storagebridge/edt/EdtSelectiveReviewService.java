package org.xander.edt.storagebridge.edt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessDescriptor;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessSettings;
import com._1c.g5.v8.dt.compare.core.ComparisonScope;
import com._1c.g5.v8.dt.compare.datasource.FileSystemComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.compare.datasource.V8ProjectComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.compare.matching.MatchingStrategy;
import com._1c.g5.v8.dt.core.filesystem.IQualifiedNameFilePathConverter;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.import_.IImportService;
import com._1c.g5.v8.dt.import_.IImportServiceRegistry;
import com.google.inject.Inject;

/**
 * Native EDT review of an explicitly selected subset from classic 1C storage.
 *
 * This mode deliberately does NOT claim to know a three-way storage/Git common
 * ancestor. That mapping doesn't exist for older projects. Instead selection
 * is mandatory in the caller, NOTHING is preselected, and only the explicitly
 * chosen objects enter the merge scope. This protects unselected committed
 * local objects (Document1) without inventing a Git baseline.
 *
 * For three-way conflict intelligence see EdtDeltaComparisonService, which
 * requires a separately verified Git-storage baseline.
 */
public final class EdtSelectiveReviewService {
    private final IV8ProjectManager projects;
    private final IImportServiceRegistry imports;
    private final IQualifiedNameFilePathConverter paths;

    private static final Map<String, String> SOURCE_GROUPS = Map.ofEntries(
        Map.entry("Справочник", "Catalogs"),
        Map.entry("Catalog", "Catalogs"),
        Map.entry("Документ", "Documents"),
        Map.entry("Document", "Documents"),
        Map.entry("ОбщийМодуль", "CommonModules"),
        Map.entry("CommonModule", "CommonModules"),
        Map.entry("Перечисление", "Enums"),
        Map.entry("Enum", "Enums"),
        Map.entry("Отчет", "Reports"),
        Map.entry("Report", "Reports"),
        Map.entry("Обработка", "DataProcessors"),
        Map.entry("DataProcessor", "DataProcessors"),
        Map.entry("РегистрСведений", "InformationRegisters"),
        Map.entry("InformationRegister", "InformationRegisters"),
        Map.entry("РегистрНакопления", "AccumulationRegisters"),
        Map.entry("AccumulationRegister", "AccumulationRegisters"),
        Map.entry("РегистрБухгалтерии", "AccountingRegisters"),
        Map.entry("AccountingRegister", "AccountingRegisters"),
        Map.entry("РегистрРасчета", "CalculationRegisters"),
        Map.entry("CalculationRegister", "CalculationRegisters"),
        Map.entry("ПланВидовХарактеристик", "ChartsOfCharacteristicTypes"),
        Map.entry("ChartOfCharacteristicTypes", "ChartsOfCharacteristicTypes"),
        Map.entry("ПланСчетов", "ChartsOfAccounts"),
        Map.entry("ChartOfAccounts", "ChartsOfAccounts"),
        Map.entry("ПланВидовРасчета", "ChartsOfCalculationTypes"),
        Map.entry("ChartOfCalculationTypes", "ChartsOfCalculationTypes"),
        Map.entry("Роль", "Roles"),
        Map.entry("Role", "Roles"),
        Map.entry("Подсистема", "Subsystems"),
        Map.entry("Subsystem", "Subsystems"),
        Map.entry("Константа", "Constants"),
        Map.entry("Constant", "Constants"),
        Map.entry("ОбщаяКоманда", "CommonCommands"),
        Map.entry("CommonCommand", "CommonCommands"),
        Map.entry("ОбщаяФорма", "CommonForms"),
        Map.entry("CommonForm", "CommonForms"),
        Map.entry("ОбщийМакет", "CommonTemplates"),
        Map.entry("CommonTemplate", "CommonTemplates"),
        Map.entry("ОбщаяКартинка", "CommonPictures"),
        Map.entry("CommonPicture", "CommonPictures"),
        Map.entry("ОпределяемыйТип", "DefinedTypes"),
        Map.entry("DefinedType", "DefinedTypes"),
        Map.entry("ПараметрСеанса", "SessionParameters"),
        Map.entry("SessionParameter", "SessionParameters"),
        Map.entry("ФункциональнаяОпция", "FunctionalOptions"),
        Map.entry("FunctionalOption", "FunctionalOptions"),
        Map.entry("ПараметрФункциональнойОпции", "FunctionalOptionsParameters"),
        Map.entry("FunctionalOptionsParameter", "FunctionalOptionsParameters"),
        Map.entry("РегламентноеЗадание", "ScheduledJobs"),
        Map.entry("ScheduledJob", "ScheduledJobs"),
        Map.entry("ПодпискаНаСобытие", "EventSubscriptions"),
        Map.entry("EventSubscription", "EventSubscriptions"),
        Map.entry("HTTPСервис", "HTTPServices"),
        Map.entry("HTTPService", "HTTPServices"),
        Map.entry("WebСервис", "WebServices"),
        Map.entry("WebService", "WebServices"),
        Map.entry("Язык", "Languages"),
        Map.entry("Language", "Languages"),
        Map.entry("ОбщийРеквизит", "CommonAttributes"),
        Map.entry("CommonAttribute", "CommonAttributes"),
        Map.entry("ЖурналДокументов", "DocumentJournals"),
        Map.entry("DocumentJournal", "DocumentJournals"),
        Map.entry("ПланОбмена", "ExchangePlans"),
        Map.entry("ExchangePlan", "ExchangePlans"),
        Map.entry("БизнесПроцесс", "BusinessProcesses"),
        Map.entry("BusinessProcess", "BusinessProcesses"),
        Map.entry("Задача", "Tasks"),
        Map.entry("Task", "Tasks"),
        Map.entry("Последовательность", "Sequences"),
        Map.entry("Sequence", "Sequences"),
        Map.entry("КритерийОтбора", "FilterCriteria"),
        Map.entry("FilterCriterion", "FilterCriteria"),
        Map.entry("НумераторДокументов", "DocumentNumerators"),
        Map.entry("DocumentNumerator", "DocumentNumerators"),
        Map.entry("ПакетXDTO", "XDTOPackages"),
        Map.entry("XDTOPackage", "XDTOPackages"),
        Map.entry("WSСсылка", "WSReferences"),
        Map.entry("WSReference", "WSReferences"),
        Map.entry("ВнешнийИсточникДанных", "ExternalDataSources"),
        Map.entry("ExternalDataSource", "ExternalDataSources"),
        Map.entry("ЭлементСтиля", "StyleItems"),
        Map.entry("StyleItem", "StyleItems"),
        Map.entry("Стиль", "Styles"),
        Map.entry("Style", "Styles")
    );

    @Inject
    public EdtSelectiveReviewService(IV8ProjectManager projects,
            IImportServiceRegistry imports,
            IQualifiedNameFilePathConverter paths) {
        this.projects = Objects.requireNonNull(projects);
        this.imports = Objects.requireNonNull(imports);
        this.paths = Objects.requireNonNull(paths);
    }

    /**
     * All paths outside the active project. The destination must be a fresh,
     * empty directory (the caller owns cleanup after native editor closure).
     * No project edits or Git commands are performed here.
     */
    public CompareMergeProcessDescriptor prepare(IProject project,
            Path platformXml, Path convertedEdtDestination,
            List<String> explicitlySelectedObjects, IProgressMonitor monitor)
            throws IOException {
        Objects.requireNonNull(project);
        Objects.requireNonNull(monitor);
        Objects.requireNonNull(explicitlySelectedObjects);
        if (!project.isAccessible() || project.getLocation() == null
                || explicitlySelectedObjects.isEmpty() || monitor.isCanceled()) {
            throw new IOException("No active EDT project or selected remote objects");
        }
        IV8Project active = projects.getProject(project);
        if (active == null) {
            throw new IOException("EDT project model is unavailable");
        }
        Path xml = Objects.requireNonNull(platformXml).toRealPath();
        Path target = Objects.requireNonNull(convertedEdtDestination).toRealPath();
        Path projectDirectory = project.getLocation().toFile().toPath().toRealPath();
        if (!Files.isDirectory(xml) || !Files.isDirectory(target)
                || target.startsWith(projectDirectory)
                || projectDirectory.startsWith(target)
                || target.startsWith(xml) || xml.startsWith(target)) {
            throw new IOException("Unsafe directory for EDT conversion");
        }
        try (Stream<Path> existing = Files.list(target)) {
            if (existing.findAny().isPresent()) {
                throw new IOException("EDT conversion target is not empty");
            }
        }
        IImportService importer;
        try {
            importer = imports.getImportService(active.getVersion());
        } catch (Exception ex) {
            throw new IOException("No EDT XML importer for this project's 1C version", ex);
        }
        IStatus status = importer.work(project, xml, target, monitor);
        if (status == null || !status.isOK() || monitor.isCanceled()) {
            throw new IOException("Selective external EDT conversion failed");
        }
        List<String> scope = exactScope(target, explicitlySelectedObjects);
        var main = new V8ProjectComparisonDataSourceDescriptor(active);
        var delta = new FileSystemComparisonDataSourceDescriptor(
            target, project.getName());
        var handle = new ComparisonProcessHandle(main, delta,
            new ComparisonScope(scope, scope));
        var settings = ComparisonProcessSettings.builder(
            MatchingStrategy.UUID_THEN_NAME)
            // Use EDT's native BSL syntax-aware merge rather than comparing
            // an entire modified module as one replaceable text block.
            // Two-way review still requires the developer's explicit choice.
            .parseBslModuleStructure(true)
            .mainSideObjectsDeletionAllowed(false)
            .build();
        return new CompareMergeProcessDescriptor(handle, settings);
    }

    /** Map only recognized classic metadata types to EDT source paths. */
    public static String edtOwner(String classicalObject) throws IOException {
        if (classicalObject == null) {
            throw new IOException("Unknown storage metadata object");
        }
        // Designer's Configuration.<real-name>.* version records refer to
        // external properties of the single EDT configuration root. 1C
        // explicitly allows the short "Configuration" spelling in -listFile.
        // Do not mistake these records for an unsupported metadata type.
        if ("Configuration".equals(classicalObject)
                || classicalObject.startsWith("Configuration.")) {
            return "Configuration/Configuration";
        }
        String[] parts = classicalObject.split("\\.");
        if (parts.length < 2) {
            throw new IOException("Incomplete metadata name: " + classicalObject);
        }
        String folder = SOURCE_GROUPS.get(parts[0]);
        if (folder == null || parts[1].isBlank()) {
            throw new IOException("Unsupported storage metadata type: " + classicalObject);
        }
        return folder + "/" + parts[1];
    }

    /**
     * Identify only the main .mdo file of a top-level EDT metadata object.
     * Accepted layouts are src/<collection>/<name>.mdo and
     * src/<collection>/<name>/<name>.mdo. Child forms/modules, even if
     * themselves represented by .mdo files, must never widen the scope.
     * The object's FQN is still obtained from EDT's converter, not inferred
     * from this file path.
     */
    public static String metadataOwnerForPath(Path relative) {
        if (relative == null || relative.isAbsolute()
                || relative.normalize().startsWith("..")) {
            return null;
        }
        int count = relative.getNameCount();
        if ((count != 3 && count != 4)
                || !"src".equals(relative.getName(0).toString())) {
            return null;
        }
        String collection = relative.getName(1).toString();
        String filename = relative.getFileName().toString();
        if (!filename.endsWith(".mdo") || filename.length() <= 4) {
            return null;
        }
        String name = filename.substring(0, filename.length() - 4);
        if (count == 4 && !name.equals(relative.getName(2).toString())) {
            return null;
        }
        return collection + "/" + name;
    }

    private List<String> exactScope(Path edtRoot,
            List<String> selection) throws IOException {
        Set<String> requested = new LinkedHashSet<>();
        for (String entry : selection) {
            requested.add(edtOwner(entry));
        }
        Set<String> seen = new LinkedHashSet<>();
        Set<String> symlinks = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(edtRoot)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = edtRoot.relativize(file);
                // Only a TOP-LEVEL metadata descriptor may define a merge
                // scope. A nested Form.form/rights file is not an owner and
                // may map to a different FQN or no FQN at all.
                String owner = metadataOwnerForPath(relative);
                if (owner == null || !requested.contains(owner)) {
                    // The conversion is not allowed to widen the comparison
                    // scope with unrelated objects, even if the importer
                    // materialized them to resolve references.
                    continue;
                }
                var fqn = paths.getFqn(new org.eclipse.core.runtime.Path(
                    relative.toString().replace('\\', '/')));
                if (fqn == null || fqn.toString().isBlank()) {
                    throw new IOException("Cannot obtain native EDT FQN for " + relative);
                }
                symlinks.add(fqn.toString());
                seen.add(owner);
            }
        }
        if (!seen.containsAll(requested) || symlinks.isEmpty()) {
            var missing = new LinkedHashSet<>(requested);
            missing.removeAll(seen);
            throw new IOException("Partial EDT conversion missed selected objects: "
                + missing);
        }
        return new ArrayList<>(symlinks);
    }
}
