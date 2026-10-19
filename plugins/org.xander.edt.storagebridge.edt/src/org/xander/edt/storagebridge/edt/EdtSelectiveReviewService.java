package org.xander.edt.storagebridge.edt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.charset.StandardCharsets;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
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
        Map.entry("ГруппаКоманд", "CommandGroups"),
        Map.entry("CommandGroup", "CommandGroups"),
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
        Map.entry("ХранилищеНастроек", "SettingsStorages"),
        Map.entry("SettingsStorage", "SettingsStorages"),
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
        Map.entry("СервисИнтеграции", "IntegrationServices"),
        Map.entry("IntegrationService", "IntegrationServices"),
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
            List<String> explicitlySelectedObjects, boolean initialFullReview,
            IProgressMonitor monitor)
            throws IOException {
        Objects.requireNonNull(project);
        Objects.requireNonNull(monitor);
        Objects.requireNonNull(explicitlySelectedObjects);
        if (!project.isAccessible() || project.getLocation() == null
                || (!initialFullReview && explicitlySelectedObjects.isEmpty())
                || monitor.isCanceled()) {
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
        // Full conversion has no manual metadata type allowlist. Let the
        // native 1C:EDT importer decide what its current version supports.
        // A partial comparison still requires a known mapping to validate
        // explicitly selected owners; it never widens scope on failure.
        if (!initialFullReview) {
            requireSupportedObjectTypes(explicitlySelectedObjects);
        }
        IStatus status;
        // In a selective object dump, the top-level Ext directory belongs
        // to Configuration, NOT to the requested Catalogs/Documents/etc.
        // EDT 2026.2 attempts to import these special root resources and
        // fails resolving their path for the detached target folder.
        // Park them OUTSIDE the importer input only while converting other
        // objects. The original XML is restored on every exit path.
        if (initialFullReview) {
            // Full Designer XML contains the configuration root/Ext context;
            // do not suppress its resources or claim root comparison succeeded.
            status = importer.work(project, xml, target, monitor);
        } else {
            try (var ignored = EdtRootXmlGuard.hideUnselectedRootExt(
                    xml, explicitlySelectedObjects)) {
                status = importer.work(project, xml, target, monitor);
            }
        }
        if (monitor.isCanceled()) {
            throw new java.io.InterruptedIOException("EDT XML import cancelled");
        }
        if (status == null || !status.isOK()) {
            throw NativeEdtImportFailure.from(status, initialFullReview);
        }
        var main = new V8ProjectComparisonDataSourceDescriptor(active);
        var delta = new FileSystemComparisonDataSourceDescriptor(
            target, project.getName());
        final ComparisonScope comparisonScope;
        if (initialFullReview) {
            // The supported native comparison session interprets NO scope
            // objects on EITHER side as GLOBAL comparison. This is the only
            // correct full-review path: EDT itself enumerates both complete
            // models, including nested forms, subsystems, pictures, deletions
            // and objects unknown to our own type/filename mappings.
            // Do not call exactScope(), requireEmbeddedLanguages(), or a
            // home-grown EDT descriptor inventory for a full review.
            comparisonScope = globalScope();
        } else {
            List<String> requestedScope = exactScope(target, explicitlySelectedObjects);
            requireEmbeddedLanguages(target, explicitlySelectedObjects);
            comparisonScope = new ComparisonScope(requestedScope, requestedScope);
        }
        var handle = new ComparisonProcessHandle(main, delta, comparisonScope);
        return new CompareMergeProcessDescriptor(handle, incomingMergeSettings());
    }


    /**
     * Keep EDT's native compare/merge editor fully writable (noMerge=false in
     * EdtDeltaComparisonService). IMPORTANT: method-structure merge takes
     * individual procedures and can leave differences outside those methods
     * in the manager module even after "Take right" was selected.
     * Compare/merge BSL as ONE text resource instead, so the standard EDT
     * "Take right" applies to the complete selected module. This is the
     * documented standard setting, not a custom BSL copying workaround.
     *
     * Deletion of main-side objects is enabled only as a user-selectable EDT
     * merge action: it is NEVER an automatic delete.
     */
    public static ComparisonProcessSettings incomingMergeSettings() {
        return ComparisonProcessSettings.builder(MatchingStrategy.UUID_THEN_NAME)
            .parseBslModuleStructure(false)
            .mainSideObjectsDeletionAllowed(true)
            .build();
    }

    /**
     * Native importer failures are not the same as missing .mdo owners.
     * Preserve the native IStatus tree in a local temporary diagnostics file.
     * It may contain project paths or metadata names: never upload/send/log it
     * automatically or include native exception strings in an EDT popup.
     */
    public static final class NativeEdtImportFailure extends IOException {
        private final int severity;
        private final int code;
        private final int descendants;
        private final Path diagnosticsFile;
        private final boolean fullReview;

        private NativeEdtImportFailure(int severity, int code, int descendants,
                Path diagnosticsFile, boolean fullReview) {
            super("Native EDT importer returned non-OK status");
            this.severity = severity;
            this.code = code;
            this.descendants = descendants;
            this.diagnosticsFile = diagnosticsFile;
            this.fullReview = fullReview;
        }

        public int severity() { return severity; }
        public int code() { return code; }
        public int descendants() { return descendants; }
        public Path diagnosticsFile() { return diagnosticsFile; }
        public boolean fullReview() { return fullReview; }

        public static NativeEdtImportFailure from(IStatus status,
                boolean fullReview) {
            int severity = status == null ? -1 : status.getSeverity();
            int code = status == null ? -1 : status.getCode();
            int count = countDescendants(status, 0);
            Path diagnostic = null;
            try {
                diagnostic = Files.createTempFile("edt-storage-import-error-", ".txt");
                StringBuilder report = new StringBuilder(8192);
                report.append("EDT native XML importer status tree\n")
                    .append("Potentially confidential: review before sharing.\n")
                    .append("Mode: ").append(fullReview ? "full" : "selective").append('\n');
                appendStatus(report, status, 0);
                // Bound retained diagnostics so a huge MultiStatus does not
                // consume memory or fill the system temp directory.
                if (report.length() > 1_000_000) {
                    report.setLength(1_000_000);
                    report.append("\n[truncated]\n");
                }
                Files.writeString(diagnostic, report.toString(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                if (diagnostic != null) {
                    try { Files.deleteIfExists(diagnostic); }
                    catch (IOException ignored) { /* best effort */ }
                }
                diagnostic = null;
            }
            return new NativeEdtImportFailure(severity, code, count,
                diagnostic, fullReview);
        }

        private static int countDescendants(IStatus status, int depth) {
            if (status == null || depth >= 12) {
                return 0;
            }
            int count = 0;
            for (IStatus child : status.getChildren()) {
                count++;
                count += countDescendants(child, depth + 1);
                if (count >= 2000) {
                    return 2000;
                }
            }
            return count;
        }

        private static void appendStatus(StringBuilder report, IStatus status,
                int depth) {
            if (report.length() > 900_000 || depth > 12) {
                return;
            }
            if (status == null) {
                report.append("Native EDT IImportService.work() returned NULL\n");
                return;
            }
            report.append("  ".repeat(depth))
                .append("severity=").append(status.getSeverity())
                .append(" code=").append(status.getCode())
                .append(" plugin=").append(status.getPlugin())
                .append(" message=").append(status.getMessage())
                .append('\n');
            if (status.getException() != null) {
                Throwable ex = status.getException();
                for (int i = 0; ex != null && i < 8; i++) {
                    report.append("  ".repeat(depth + 1)).append("cause=")
                        .append(ex.getClass().getName()).append(": ")
                        .append(ex.getMessage()).append('\n');
                    for (StackTraceElement frame : ex.getStackTrace()) {
                        if (report.length() > 900_000) { break; }
                        report.append("  ".repeat(depth + 1))
                            .append("  at ").append(frame).append('\n');
                    }
                    ex = ex.getCause();
                }
            }
            int childCount = 0;
            for (IStatus child : status.getChildren()) {
                if (childCount++ > 2000 || report.length() > 900_000) {
                    report.append("[children truncated]\n");
                    break;
                }
                appendStatus(report, child, depth + 1);
            }
        }
    }

    /** Report a lost embedded language without leaking its metadata name. */
    public static final class MissingEmbeddedLanguagesException extends IOException {
        private final int missingCount;

        public MissingEmbeddedLanguagesException(int missingCount) {
            super("Requested embedded EDT languages are missing");
            this.missingCount = missingCount;
        }

        public int missingCount() { return missingCount; }
    }

    /**
     * Language.<name> has no standalone EDT metadata descriptor. Native EDT
     * places it inside Configuration.mdo as a direct <languages> element.
     * Verify the imported name(s), rather than trusting only a root .mdo
     * file's existence. A missing language must never be marked received.
     */
    public static void requireEmbeddedLanguages(
            Path edtRoot, List<String> selectedObjects) throws IOException {
        Objects.requireNonNull(edtRoot);
        Objects.requireNonNull(selectedObjects);
        Set<String> required = new LinkedHashSet<>();
        for (String entry : selectedObjects) {
            if (entry == null) {
                throw new IOException("Null selected metadata name");
            }
            if (entry.startsWith("Language.") || entry.startsWith("Язык.")) {
                String[] parts = entry.split("\\.");
                if (parts.length < 2 || parts[1].isBlank()) {
                    throw new IOException("Incomplete language metadata name");
                }
                required.add(parts[1]);
            }
        }
        if (required.isEmpty()) {
            return;
        }
        Path descriptor = edtRoot.resolve("src/Configuration/Configuration.mdo");
        if (!Files.isRegularFile(descriptor, LinkOption.NOFOLLOW_LINKS)) {
            throw new MissingEmbeddedLanguagesException(required.size());
        }

        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var xml = factory.newDocumentBuilder().parse(descriptor.toFile());
            Node root = xml.getDocumentElement();
            Set<String> materialized = new LinkedHashSet<>();
            for (Node language = root.getFirstChild(); language != null;
                    language = language.getNextSibling()) {
                if (language.getNodeType() != Node.ELEMENT_NODE
                        || !"languages".equals(xmlLocalName(language))) {
                    continue;
                }
                for (Node property = language.getFirstChild(); property != null;
                        property = property.getNextSibling()) {
                    if (property.getNodeType() == Node.ELEMENT_NODE
                            && "name".equals(xmlLocalName(property))) {
                        materialized.add(property.getTextContent().trim());
                    }
                }
            }
            required.removeAll(materialized);
        } catch (ParserConfigurationException | SAXException ex) {
            throw new IOException(
                "Cannot validate embedded languages in EDT configuration", ex);
        }
        if (!required.isEmpty()) {
            throw new MissingEmbeddedLanguagesException(required.size());
        }
    }

    private static String xmlLocalName(Node node) {
        return node.getLocalName() == null ? node.getNodeName()
            : node.getLocalName();
    }

    /** Missing metadata type names only; never expose native object names. */
    public static final class UnsupportedMetadataTypesException extends IOException {
        private final List<String> types;

        public UnsupportedMetadataTypesException(Set<String> unknownTypes) {
            super("Unsupported storage metadata types");
            this.types = List.copyOf(unknownTypes);
        }

        public List<String> types() {
            return types;
        }
    }

    /** Preflight all requested object types; do not import until they map. */
    public static void requireSupportedObjectTypes(List<String> objectNames)
            throws IOException {
        Objects.requireNonNull(objectNames);
        Set<String> missing = new LinkedHashSet<>();
        for (String object : objectNames) {
            if (object == null || object.isBlank()) {
                throw new IOException("Empty storage metadata name");
            }
            if ("Configuration".equals(object)
                    || object.startsWith("Configuration.")) {
                continue;
            }
            String[] parts = object.split("\\.");
            if (parts.length < 2 || parts[1].isBlank()) {
                throw new IOException("Incomplete storage metadata name");
            }
            if (!SOURCE_GROUPS.containsKey(parts[0])) {
                // Only a metadata TYPE may appear in a user-facing error.
                missing.add(parts[0].matches("[A-Za-z]{1,64}")
                    ? parts[0] : "UNKNOWN");
            }
        }
        if (!missing.isEmpty()) {
            throw new UnsupportedMetadataTypesException(missing);
        }
    }

    /** The exact scope is incomplete; no change may be marked as applied. */
    public static final class MissingConvertedOwnersException extends IOException {
        private final int expectedOwners;
        private final int matchedOwners;
        private final int primaryDescriptors;
        private final Map<String, Integer> missingTypes;

        public MissingConvertedOwnersException(int expectedOwners, int matchedOwners,
                int primaryDescriptors, Map<String, Integer> missingTypes) {
            super("EDT conversion did not materialize all requested owners");
            this.expectedOwners = expectedOwners;
            this.matchedOwners = matchedOwners;
            this.primaryDescriptors = primaryDescriptors;
            this.missingTypes = Map.copyOf(missingTypes);
        }

        public int expectedOwners() { return expectedOwners; }
        public int matchedOwners() { return matchedOwners; }
        public int primaryDescriptors() { return primaryDescriptors; }
        public Map<String, Integer> missingTypes() { return missingTypes; }
    }

    /**
     * Produce a safe grouped report without logging confidential metadata
     * object names. All actual names stay in the existing pending journal.
     */
    public static Map<String, Integer> missingOwnersByType(
            Set<String> requested, Set<String> seen) {
        var missing = new java.util.TreeMap<String, Integer>();
        for (String name : requested) {
            if (!seen.contains(name)) {
                String group = name.substring(0, name.indexOf('/'));
                missing.merge(group, 1, Integer::sum);
            }
        }
        return Map.copyOf(missing);
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
        // EDT embeds Language objects directly in
        // src/Configuration/Configuration.mdo as <languages> elements.
        // There is no src/Languages/<name>.mdo owner descriptor. Native
        // comparison therefore uses the configuration-root FQN.
        if ("Language".equals(parts[0]) || "Язык".equals(parts[0])) {
            return "Configuration/Configuration";
        }
        String folder = SOURCE_GROUPS.get(parts[0]);
        if (folder == null) {
            throw new UnsupportedMetadataTypesException(Set.of(
                parts[0].matches("[A-Za-z]{1,64}") ? parts[0] : "UNKNOWN"));
        }
        if (parts[1].isBlank()) {
            throw new IOException("Incomplete storage metadata name");
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

    /**
     * Full verification compares the UNION of local and repository owners.
     * The right side contains every imported remote owner; the left side
     * contains the same owners plus local-only objects so EDT can offer
     * deletion in its standard two-sided merge editor.
     *
     * Paths are relative to each respective EDT source root. The mapping
     * to native comparison symbolic links happens through EDT's own path
     * converter and never through guessed names.
     */
    /** Native EDT's exact, official no-restrictions comparison scope. */
    public static ComparisonScope globalScope() {
        return ComparisonScope.EMPTY_SCOPE;
    }

    public record FullPrimaryScopes(List<Path> main, List<Path> other) {
        public FullPrimaryScopes {
            main = List.copyOf(main);
            other = List.copyOf(other);
        }
    }

    public static FullPrimaryScopes fullPrimaryScopes(
            Path projectRoot, Path convertedEdtRoot) throws IOException {
        var local = primaryDescriptors(projectRoot);
        var remote = primaryDescriptors(convertedEdtRoot);
        if (remote.isEmpty()) {
            throw new IOException("Full EDT import has no primary metadata descriptors");
        }
        var main = new java.util.LinkedHashMap<String, Path>(remote);
        local.forEach(main::putIfAbsent);
        return new FullPrimaryScopes(List.copyOf(main.values()),
            List.copyOf(remote.values()));
    }

    /**
     * Collect only real top-level .mdo descriptors, not nested form/module
     * resources. Unlike the ConfigDumpInfo list, this covers owners without
     * version tokens and local-only/deleted-from-storage metadata.
     */
    public static Map<String, Path> primaryDescriptors(Path edtRoot)
            throws IOException {
        Path root = edtRoot.toRealPath();
        Path src = root.resolve("src");
        if (!Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(src)) {
            throw new IOException("EDT source root is missing or unsafe");
        }
        var found = new java.util.LinkedHashMap<String, Path>();
        try (Stream<Path> files = Files.walk(src)) {
            for (Path file : files.toList()) {
                Path relative = root.relativize(file);
                String owner = metadataOwnerForPath(relative);
                if (owner == null) {
                    continue;
                }
                if (Files.isSymbolicLink(file)) {
                    throw new IOException("Symbolic metadata descriptor in EDT source");
                }
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (found.putIfAbsent(owner, relative) != null) {
                    throw new IOException("Ambiguous primary metadata owner in EDT source");
                }
            }
        }
        return java.util.Collections.unmodifiableMap(found);
    }

    private List<String> toNativeFqns(List<Path> relativeFiles) throws IOException {
        var fqnNames = new LinkedHashSet<String>();
        for (Path relative : relativeFiles) {
            var fqn = paths.getFqn(new org.eclipse.core.runtime.Path(
                relative.toString().replace('\\', '/')));
            if (fqn == null || fqn.toString().isBlank()) {
                throw new IOException("EDT cannot resolve native comparison owner");
            }
            fqnNames.add(fqn.toString());
        }
        return List.copyOf(fqnNames);
    }

    private List<String> exactScope(Path edtRoot,
            List<String> selection) throws IOException {
        Set<String> requested = new LinkedHashSet<>();
        for (String entry : selection) {
            requested.add(edtOwner(entry));
        }
        Set<String> seen = new LinkedHashSet<>();
        Set<String> symlinks = new LinkedHashSet<>();
        int[] totalDescriptors = {0};
        try (Stream<Path> files = Files.walk(edtRoot)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = edtRoot.relativize(file);
                // Only a TOP-LEVEL metadata descriptor may define a merge
                // scope. A nested Form.form/rights file is not an owner and
                // may map to a different FQN or no FQN at all.
                String owner = metadataOwnerForPath(relative);
                if (owner != null) {
                    totalDescriptors[0]++;
                }
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
            throw new MissingConvertedOwnersException(
                requested.size(), seen.size(), totalDescriptors[0],
                missingOwnersByType(requested, seen));
        }
        return new ArrayList<>(symlinks);
    }
}
