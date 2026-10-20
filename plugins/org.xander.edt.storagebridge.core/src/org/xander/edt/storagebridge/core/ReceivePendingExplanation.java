package org.xander.edt.storagebridge.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Explain the EXACT pending metadata identities instead of presenting a
 * meaningless count. This is a description of the plugin's review scope,
 * not a statement that the developer forgot to select an EDT checkbox.
 */
public final class ReceivePendingExplanation {
    // Keep modal dialogs readable on first full scans (20k+ identities).
    private static final int MAX_SHOWN = 5;

    private ReceivePendingExplanation() {}

    public static String format(Set<String> remaining, Set<String> deferred,
            Set<String> missingFromSource) {
        Objects.requireNonNull(remaining);
        Objects.requireNonNull(deferred);
        Objects.requireNonNull(missingFromSource);
        if (remaining.isEmpty()) return "";

        List<String> sorted = new ArrayList<>(remaining);
        sorted.sort(String::compareTo);
        StringBuilder result = new StringBuilder(
            "\nНе обработано плагином: " + remaining.size()
            + " (это НЕ пропущенные галочки EDT).");
        for (int i = 0; i < Math.min(MAX_SHOWN, sorted.size()); i++) {
            String name = sorted.get(i);
            result.append("\n  • ").append(name).append(" — ");
            if (missingFromSource.contains(name)) {
                result.append("отсутствует в текущей выгрузке 1С; "
                    + "удаление требует отдельного разбора");
            } else if (deferred.contains(name)) {
                result.append("корневой объект или язык; "
                    + "не входил в выборочное сравнение");
            } else {
                result.append("не подтверждено в этом сравнении");
            }
        }
        if (sorted.size() > MAX_SHOWN) {
            result.append("\n  … и ещё ").append(sorted.size() - MAX_SHOWN);
        }
        if (sorted.stream().anyMatch(deferred::contains)) {
            result.append("\nЧтобы проверить изменения корня и языков, "
                + "нужна полная сверка: выборочный экспорт их не поддерживает.");
        }
        if (sorted.stream().anyMatch(missingFromSource::contains)) {
            result.append("\nУдалённые или переименованные объекты нельзя "
                + "считать принятыми только по отметке полного сравнения.");
        }
        return result.toString();
    }
}
