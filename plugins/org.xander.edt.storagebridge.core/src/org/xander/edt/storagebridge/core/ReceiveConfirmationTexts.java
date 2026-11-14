package org.xander.edt.storagebridge.core;

import java.util.Objects;

/** Human-facing 1C receive confirmations, with no Eclipse or Designer calls. */
public final class ReceiveConfirmationTexts {
    private ReceiveConfirmationTexts() {}

    /** File path and tcp:// repository address: show the last name only. */
    public static String displayName(String address) {
        Objects.requireNonNull(address);
        String value = address.strip().replace('\r', ' ')
            .replace('\n', ' ').replace('\t', ' ');
        while (value.endsWith("/") || value.endsWith("\\")) {
            value = value.substring(0, value.length() - 1);
        }
        int separator = Math.max(value.lastIndexOf('/'),
            value.lastIndexOf('\\'));
        String name = separator >= 0 && separator + 1 < value.length()
            ? value.substring(separator + 1) : value;
        if (name.isBlank()) return "не указано";
        return name.length() > 96 ? name.substring(0, 95) + "…" : name;
    }

    private static String source(String repository, String infobase) {
        return "Хранилище: " + displayName(repository)
            + "\nБаза для получения: " + displayName(infobase);
    }

    public static String freshReceive(String repository, String infobase,
            boolean fullReview, boolean forcedFullReview) {
        String scope = forcedFullReview
            ? "Полная сверка: будет выгружена вся конфигурация. "
                + "Это может занять время.\n\n"
            : fullReview
                ? "Первая сверка: потребуется выгрузить всю конфигурацию. "
                    + "Это может занять время.\n\n" : "";
        return "Плагин обновит указанную базу 1С из хранилища и откроет "
            + "изменения для сравнения в EDT.\n\n"
            + source(repository, infobase) + "\n\n" + scope
            + "Проект EDT не изменяется автоматически. "
            + "Ты сам выберешь изменения в штатном объединении.\n\n"
            + "Внимание: изменения в этой базе, не помещённые в хранилище, "
            + "могут потеряться при обновлении.";
    }

    public static String pendingChoices(String repository,
            String infobase, boolean fullReview) {
        return "Есть ранее полученные изменения, ожидающие сравнения в EDT.\n\n"
            + source(repository, infobase) + "\n\n"
            + "«Обновить и сравнить» — получить актуальную версию "
            + "хранилища в базу и затем сравнить с EDT.\n"
            + "«Повторно сравнить старое» — открыть прежний снимок "
            + "БЕЗ обновления базы; новая версия хранилища НЕ загружается."
            + (fullReview ? "\nДля полной сверки будет выгружена "
                + "вся конфигурация." : "")
            + "\n\nПроект EDT не изменится без твоего объединения.\n"
            + "Внимание: при обновлении базы её изменения, не помещённые "
            + "в хранилище, могут быть потеряны.";
    }

    public static String changedIntermediary(String repository,
            String infobase) {
        return "База изменилась после прошлого получения. "
            + "Использовать старый список объектов небезопасно.\n\n"
            + source(repository, infobase) + "\n\n"
            + "Для продолжения нужно заново обновить базу из хранилища "
            + "и затем сравнить изменения в EDT.\n\n"
            + "Проект EDT не изменится автоматически.\n"
            + "Внимание: изменения в базе, не помещённые в хранилище, "
            + "могут потеряться.";
    }
}
