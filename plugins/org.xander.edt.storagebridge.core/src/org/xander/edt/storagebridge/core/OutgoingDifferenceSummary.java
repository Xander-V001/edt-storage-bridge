package org.xander.edt.storagebridge.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * User-facing bounded, read-only report from two ACTUAL EDT file trees.
 * Only candidates: native comparison is the authority for semantic metadata
 * differences and no part of this class permits a storage write.
 */
public final class OutgoingDifferenceSummary {
    private final int added;
    private final int changed;
    private final int removed;
    private final Map<String, List<OutgoingDeltaScanner.Change>> byOwner;

    private OutgoingDifferenceSummary(int added, int changed, int removed,
            Map<String, List<OutgoingDeltaScanner.Change>> byOwner) {
        this.added = added;
        this.changed = changed;
        this.removed = removed;
        this.byOwner = Map.copyOf(byOwner);
    }

    public static OutgoingDifferenceSummary from(OutgoingDeltaScanner.Delta delta) {
        Objects.requireNonNull(delta);
        var grouped = new java.util.TreeMap<String,
            List<OutgoingDeltaScanner.Change>>();
        int added = 0, changed = 0, removed = 0;
        for (var file : delta.files()) {
            switch (file.kind()) {
                case ADDED -> added++;
                case MODIFIED -> changed++;
                case DELETED -> removed++;
            }
            grouped.computeIfAbsent(file.edtOwner(),
                unused -> new ArrayList<>()).add(file);
        }
        var immutable = new LinkedHashMap<String,
            List<OutgoingDeltaScanner.Change>>();
        grouped.forEach((key,value) -> immutable.put(key, List.copyOf(value)));
        return new OutgoingDifferenceSummary(added, changed, removed, immutable);
    }

    public int added() { return added; }
    public int changed() { return changed; }
    public int removed() { return removed; }
    public int ownerCount() { return byOwner.size(); }
    public int fileCount() { return added + changed + removed; }

    public Map<String, List<OutgoingDeltaScanner.Change>> byOwner() {
        return byOwner;
    }

    /**
     * Does not include code contents or local absolute paths. Large deltas are
     * bounded for SWT dialogs and the complete delta stays in memory only.
     */
    public String describe(int maxOwners) {
        if (maxOwners < 1 || maxOwners > 128) {
            throw new IllegalArgumentException("Display limit out of range");
        }
        if (fileCount() == 0) {
            return "Различий в исходных файлах EDT не обнаружено.\n"
                + "Для окончательной проверки модели можно открыть "
                + "штатное сравнение EDT.";
        }
        StringBuilder message = new StringBuilder(300);
        message.append("Обнаружены кандидаты на отправку:\n")
            .append("Объектов: ").append(ownerCount())
            .append(", файлов: ").append(fileCount())
            .append(".\nДобавлено в EDT: ").append(added)
            .append(", изменено: ").append(changed)
            .append(", отсутствует в EDT: ").append(removed)
            .append(".\n\n");
        int shown = 0;
        for (String owner : byOwner.keySet().stream().sorted().toList()) {
            if (shown++ >= maxOwners) break;
            var changes = byOwner.get(owner);
            long localAdds = changes.stream()
                .filter(x -> x.kind() == OutgoingDeltaScanner.Kind.ADDED).count();
            long localChanges = changes.stream()
                .filter(x -> x.kind() == OutgoingDeltaScanner.Kind.MODIFIED).count();
            long localRemovals = changes.stream()
                .filter(x -> x.kind() == OutgoingDeltaScanner.Kind.DELETED).count();
            message.append("• ").append(owner).append(" (")
                .append("+").append(localAdds)
                .append(", ~").append(localChanges)
                .append(", -").append(localRemovals).append(")\n");
        }
        if (ownerCount() > maxOwners) {
            message.append("…ещё ")
                .append(ownerCount() - maxOwners)
                .append(" объектов; они доступны в полном сравнении EDT.\n");
        }
        if (removed > 0) {
            message.append("\nВНИМАНИЕ: отсутствие файлов в EDT "
                + "может означать удаление метаданных; оно не подтверждено.");
        }
        return message.toString();
    }
}
