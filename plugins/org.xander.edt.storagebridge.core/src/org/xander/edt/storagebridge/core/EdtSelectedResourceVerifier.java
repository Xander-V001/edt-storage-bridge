package org.xander.edt.storagebridge.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Read-only proof for independently versioned 1C metadata resources.
 *
 * A selectively dumped Document may contain unrelated Forms/ and Modules.
 * Failure to merge ONE form must not conceal a correctly applied manager
 * module. Never infer a change from a checkbox or from owner-level equality
 * of an incomplete selective stage.
 *
 * Only file/layout mappings proved by the normal EDT project structure are
 * independently acknowledged. Unknown kinds remain pending until their
 * COMPLETE owner matches exactly (EdtOwnerContentVerifier).
 */
public final class EdtSelectedResourceVerifier {
    private EdtSelectedResourceVerifier() {}

    /**
     * @return true ONLY if both EDT sources contain identical native resource
     * content, and the selected classical name maps to that resource without
     * ambiguity. False includes unknown resource layout or missing files.
     */
    public static boolean isIndependentlyApplied(
            Path projectRoot, Path stageRoot, String edtOwner,
            String classicalName) throws IOException {
        if (!validOwner(edtOwner) || classicalName == null
                || !classicalName.matches(
                    "[\\p{L}\\p{N}_]+(\\.[\\p{L}\\p{N}_]+)+")) {
            return false;
        }
        String[] parts = classicalName.split("\\.");
        if (parts.length < 3 || !edtOwner.endsWith("/" + parts[1])) {
            return false;
        }
        Path project = projectRoot.toRealPath();
        Path incoming = stageRoot.toRealPath();
        if (project.startsWith(incoming) || incoming.startsWith(project)) {
            throw new IOException("Overlapping native EDT verification roots");
        }
        Path relativeOwner = Path.of("src").resolve(edtOwner);
        Path from = incoming.resolve(relativeOwner);
        Path to = project.resolve(relativeOwner);
        // Same name is NOT enough: native matching can fall back to names.
        // Without proving the parent's primary metadata descriptor equal,
        // we cannot assert an isolated module belongs to the same identity.
        if (!samePrimaryDescriptor(incoming, project, relativeOwner)) {
            return false;
        }

        // No hardcoded list of "ManagerModule", "ObjectModule",
        // "RecordSetModule" etc. In the native hierarchical EDT layout
        // independently versioned BSL resources are represented by the
        // actual source file of the same leaf identity. Resolve THAT file
        // in the staged EDT tree; never assume a 1C object category.
        //
        // Examples (handled identically):
        //   Catalog.X.ManagerModule -> Catalogs/X/ManagerModule.bsl
        //   Document.X.ObjectModule -> Documents/X/ObjectModule.bsl
        //   CommonModule.X.Module -> CommonModules/X/Module.bsl
        //   InformationRegister.X.RecordSetModule -> .../RecordSetModule.bsl
        //
        // Only a direct, native top-owner child can qualify. An embedded
        // Attribute.X or Form.Y.Module must not be guessed as a direct .bsl.
        if (parts.length == 3) {
            Path stagedBsl = from.resolve(parts[2] + ".bsl");
            if (Files.isRegularFile(stagedBsl, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(stagedBsl)) {
                return sameRegularFile(stagedBsl,
                    to.resolve(parts[2] + ".bsl"));
            }
        }

        // A selected Form (or a Form's embedded property) is accepted only
        // after its ENTIRE native form subtree matches, including Form.form,
        // Module.bsl and any associated resources. Do not normalize Form.form:
        // element ordering, UUIDs and property values can be significant.
        if (parts.length >= 4 && "Form".equals(parts[2])) {
            Path formFrom = from.resolve("Forms").resolve(parts[3]);
            Path formTo = to.resolve("Forms").resolve(parts[3]);
            if (!Files.isRegularFile(formFrom.resolve("Form.form"),
                    LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(formTo.resolve("Form.form"),
                        LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            return equalResourceTrees(formFrom, formTo);
        }
        return false;
    }

    private static boolean validOwner(String owner) {
        return owner != null && owner.matches(
            "[\\p{L}\\p{N}_]+/[\\p{L}\\p{N}_]+");
    }

    private static boolean samePrimaryDescriptor(Path sourceRoot,
            Path liveRoot, Path owner) throws IOException {
        Path name = owner.getFileName();
        Path relativeNested = owner.resolve(name + ".mdo");
        Path relativeFlat = Path.of(owner + ".mdo");
        if (Files.isRegularFile(sourceRoot.resolve(relativeNested),
                LinkOption.NOFOLLOW_LINKS)) {
            return sameRegularFile(sourceRoot.resolve(relativeNested),
                liveRoot.resolve(relativeNested));
        }
        if (Files.isRegularFile(sourceRoot.resolve(relativeFlat),
                LinkOption.NOFOLLOW_LINKS)) {
            return sameRegularFile(sourceRoot.resolve(relativeFlat),
                liveRoot.resolve(relativeFlat));
        }
        return false;
    }

    private static boolean sameRegularFile(Path from, Path to)
            throws IOException {
        return Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)
            && Files.isRegularFile(to, LinkOption.NOFOLLOW_LINKS)
            && !Files.isSymbolicLink(from) && !Files.isSymbolicLink(to)
            && Files.size(from) == Files.size(to)
            && Files.mismatch(from, to) == -1L;
    }

    private static boolean equalResourceTrees(Path from, Path to)
            throws IOException {
        if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(to, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(from) || Files.isSymbolicLink(to)) {
            return false;
        }
        Set<Path> fromPaths = new HashSet<>();
        Set<Path> toPaths = new HashSet<>();
        try (Stream<Path> tree = Files.walk(from)) {
            for (Path path : tree.toList()) {
                if (Files.isSymbolicLink(path)) return false;
                fromPaths.add(from.relativize(path));
            }
        }
        try (Stream<Path> tree = Files.walk(to)) {
            for (Path path : tree.toList()) {
                if (Files.isSymbolicLink(path)) return false;
                toPaths.add(to.relativize(path));
            }
        }
        if (!fromPaths.equals(toPaths)) return false;
        for (Path rel : fromPaths) {
            Path a = from.resolve(rel), b = to.resolve(rel);
            if (Files.isRegularFile(a, LinkOption.NOFOLLOW_LINKS)) {
                if (!sameRegularFile(a, b)) return false;
            } else if (!Files.isDirectory(a, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(b, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
        }
        return true;
    }
}
