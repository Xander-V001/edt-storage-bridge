package org.xander.edt.storagebridge.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Fail-closed read-only native export proof for a limited set of BSL modules. */
public final class OutgoingBslPreviewPlan {
    private static final Pattern ALLOWED = Pattern.compile(
        "^src/(CommonModules|Documents)/([\\p{L}_][\\p{L}\\p{N}_]*)/"
            + "(Module|ManagerModule|ObjectModule)\\.bsl$");
    public record Item(String edtPath, String platformPath, String classicOwner) {}
    private final List<Item> items;

    private OutgoingBslPreviewPlan(List<Item> items) {
        this.items = List.copyOf(items);
    }

    public static OutgoingBslPreviewPlan of(List<String> selected) {
        Objects.requireNonNull(selected, "selected EDT files");
        if (selected.isEmpty() || selected.size() > 128)
            throw new IllegalArgumentException("Select 1-128 BSL modules");
        var unique = new LinkedHashSet<String>();
        var items = new ArrayList<Item>();
        for (String edt : selected) {
            if (edt == null || !unique.add(edt))
                throw new IllegalArgumentException("Duplicate or null EDT path");
            var match = ALLOWED.matcher(edt);
            if (!match.matches())
                throw new IllegalArgumentException("Unsupported EDT module location");
            String group = match.group(1);
            String name = match.group(2);
            String module = match.group(3);
            if (("CommonModules".equals(group) && !"Module".equals(module))
                    || ("Documents".equals(group) && "Module".equals(module)))
                throw new IllegalArgumentException("Unsupported EDT BSL module type");
            items.add(new Item(edt,
                group + "/" + name + "/Ext/" + module + ".bsl",
                ("CommonModules".equals(group) ? "CommonModule." : "Document.") + name));
        }
        return new OutgoingBslPreviewPlan(items);
    }

    public List<Item> items() { return items; }

    public Set<String> classicOwners() {
        var names = new LinkedHashSet<String>();
        for (Item i : items) names.add(i.classicOwner());
        return java.util.Collections.unmodifiableSet(names);
    }

    public List<String> platformFiles() {
        return items.stream().map(Item::platformPath).toList();
    }

    /** Verify actual native export, NEVER use the parent XML as a fallback. */
    public void verifyExport(Path root) throws IOException {
        Objects.requireNonNull(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root))
            throw new IOException("Missing/linked native export directory");
        for (Item i : items) {
            Path file = root;
            for (String part : i.platformPath().split("/")) {
                file = file.resolve(part);
                if (Files.isSymbolicLink(file))
                    throw new IOException("Symlink in native export");
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Required native BSL artifact not exported");
        }
    }
}
