package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the ACTUAL native Russian TXT report "Отчет по версиям хранилища"
 * exported by the classic 1C Designer (verified against an authentic 2026-10-17
 * report containing versions 29400..29404).
 *
 * Every version contains its repository number, configuration version, user,
 * creation time, possibly multiline comment, and counted metadata changes.
 * Do not confuse "Версия конфигурации" with the repository revision.
 * Count/shape mismatches fail closed, rather than writing a false checkpoint.
 */
public final class RepositoryVersionReportParser {
    private static final Pattern VERSION =
        Pattern.compile("^Версия:[ \t]*([1-9][0-9]*)[ \t]*$");
    private static final Pattern GROUP =
        Pattern.compile("^[ \t]*(Добавлены|Изменены|Удалены)[ \t]+([0-9]+)[ \t]*$");
    private static final DateTimeFormatter CREATED = DateTimeFormatter
        .ofPattern("dd.MM.uuuu HH:mm:ss")
        .withResolverStyle(ResolverStyle.STRICT);

    private RepositoryVersionReportParser() {}

    /** Immutable per-revision native history; these are 1C names, not EDT FQNs. */
    public record Entry(
            long number, String configurationVersion, String username,
            LocalDateTime created, String comment,
            List<String> added, List<String> modified, List<String> deleted) {
        public Entry {
            if (number < 1) {
                throw new IllegalArgumentException("Invalid repository revision");
            }
            configurationVersion = Objects.requireNonNull(configurationVersion);
            username = Objects.requireNonNull(username);
            created = Objects.requireNonNull(created);
            comment = Objects.requireNonNull(comment);
            added = List.copyOf(added);
            modified = List.copyOf(modified);
            deleted = List.copyOf(deleted);
        }
    }

    public static List<Entry> parse(String source) throws IOException {
        Objects.requireNonNull(source);
        String[] lines = source.replaceFirst("^\uFEFF", "").split("\\R", -1);
        if (lines.length < 6
                || !lines[0].startsWith("Отчет по версиям хранилища:")
                || !lines[1].startsWith("Дата отчета:")
                || !lines[2].startsWith("Время отчета:")) {
            throw new IOException("Not a native 1C repository versions TXT report");
        }
        int cursor = 3;
        List<Entry> versions = new ArrayList<>();
        long previous = 0;
        while (cursor < lines.length) {
            while (cursor < lines.length && lines[cursor].isBlank()) {
                cursor++;
            }
            if (cursor == lines.length) {
                break;
            }
            Matcher header = VERSION.matcher(lines[cursor]);
            if (!header.matches()) {
                throw new IOException("Unexpected native repository report structure");
            }
            long number = positiveLong(header.group(1));
            if (number <= previous) {
                throw new IOException("Repository history is not strictly increasing");
            }
            cursor++;
            String configurationVersion = field(lines, cursor++, "Версия конфигурации:");
            String username = field(lines, cursor++, "Пользователь:");
            String date = field(lines, cursor++, "Дата создания:");
            String time = field(lines, cursor++, "Время создания:");
            if (cursor >= lines.length || !lines[cursor++].strip().equals("Комментарий:")) {
                throw new IOException("Repository revision comment heading missing");
            }

            LocalDateTime created;
            try {
                created = LocalDateTime.parse(date + " " + time, CREATED);
            } catch (DateTimeParseException ex) {
                throw new IOException("Invalid native repository revision timestamp", ex);
            }

            StringBuilder comment = new StringBuilder();
            while (cursor < lines.length
                    && !VERSION.matcher(lines[cursor]).matches()
                    && !GROUP.matcher(lines[cursor]).matches()) {
                if (!comment.isEmpty()) {
                    comment.append('\n');
                }
                comment.append(lines[cursor++]);
            }
            List<String> added = List.of();
            List<String> modified = List.of();
            List<String> deleted = List.of();
            HashSet<String> seenGroups = new HashSet<>();
            while (cursor < lines.length) {
                if (lines[cursor].isBlank()) {
                    cursor++;
                    continue;
                }
                if (VERSION.matcher(lines[cursor]).matches()) {
                    break;
                }
                Matcher group = GROUP.matcher(lines[cursor]);
                if (!group.matches()) {
                    throw new IOException("Unexpected native repository changes section");
                }
                if (!seenGroups.add(group.group(1))) {
                    throw new IOException("Duplicate native repository changes section");
                }
                long longCount;
                try {
                    longCount = Long.parseLong(group.group(2));
                } catch (NumberFormatException ex) {
                    throw new IOException("Invalid repository section count", ex);
                }
                if (longCount > lines.length - cursor - 1) {
                    throw new IOException("Truncated native repository changes list");
                }
                int count = (int) longCount;
                cursor++;
                List<String> names = new ArrayList<>(count);
                HashSet<String> unique = new HashSet<>();
                for (int i = 0; i < count; i++) {
                    if (cursor >= lines.length || !lines[cursor].startsWith("\t")) {
                        throw new IOException("Invalid native repository metadata line");
                    }
                    String name = lines[cursor++].substring(1).strip();
                    if (name.isBlank() || !unique.add(name)) {
                        throw new IOException("Invalid or duplicate repository metadata item");
                    }
                    names.add(name);
                }
                switch (group.group(1)) {
                    case "Добавлены" -> added = names;
                    case "Изменены" -> modified = names;
                    case "Удалены" -> deleted = names;
                    default -> throw new IOException("Unexpected native repository section");
                }
            }
            if (seenGroups.isEmpty()) {
                throw new IOException("Native repository revision has no parsed changes");
            }
            versions.add(new Entry(number, configurationVersion, username,
                created, comment.toString().strip(), added, modified, deleted));
            previous = number;
        }
        if (versions.isEmpty()) {
            throw new IOException("Native repository versions report is empty");
        }
        return List.copyOf(versions);
    }

    public static long latest(String text) throws IOException {
        List<Entry> entries = parse(text);
        return entries.get(entries.size() - 1).number();
    }

    private static long positiveLong(String number) throws IOException {
        try {
            long result = Long.parseLong(number);
            if (result >= 1) {
                return result;
            }
        } catch (NumberFormatException ignored) {
            // Do not silently truncate overflowing native version numbers.
        }
        throw new IOException("Invalid native repository revision number");
    }

    private static String field(String[] lines, int index, String label)
            throws IOException {
        if (index >= lines.length || !lines[index].startsWith(label)) {
            throw new IOException("Missing repository history field");
        }
        String value = lines[index].substring(label.length()).strip();
        if (value.isBlank()) {
            throw new IOException("Empty required repository history field");
        }
        return value;
    }
}
