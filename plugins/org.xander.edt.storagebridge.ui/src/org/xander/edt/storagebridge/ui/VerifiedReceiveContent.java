package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.core.resources.IProject;
import org.osgi.service.prefs.BackingStoreException;
import org.xander.edt.storagebridge.edt.EdtSelectiveReviewService;

/**
 * Conservative post-merge verification. A checked object in EDT's comparison
 * editor does not prove the remote object was applied. Only byte-for-byte
 * equality of every staged file of that object allows clearing its pending
 * marker. A manually combined object remains pending until independently
 * reconciled; this is intentionally conservative and avoids false sync status.
 */
public final class VerifiedReceiveContent {
    private VerifiedReceiveContent() {}

    public static Set<String> clearMatchingSelected(IProject project,
            String repositoryAddress, String branch, String intermediaryIdentity,
            Set<String> selectedRoots, Set<String> selectedPendingNames,
            Path convertedEdt) throws IOException, BackingStoreException {
        if (project.getLocation() == null || !project.isAccessible()) {
            throw new IOException("EDT project is not available for verification");
        }
        Path projectRoot = project.getLocation().toFile().toPath().toRealPath();
        Path stage = convertedEdt.toRealPath();
        if (stage.startsWith(projectRoot) || projectRoot.startsWith(stage)) {
            throw new IOException("Invalid receive staging root");
        }
        Set<String> verifiedNames = new LinkedHashSet<>();
        for (String root : selectedRoots) {
            if (isExactlyApplied(projectRoot, stage, root)) {
                for (String pendingName : selectedPendingNames) {
                    if (ReceiveChangesCoordinator.topObject(pendingName).equals(root)) {
                        verifiedNames.add(pendingName);
                    }
                }
            }
        }
        if (!verifiedNames.isEmpty()) {
            ReceivePendingStore.clearVerified(
                project, repositoryAddress, branch, intermediaryIdentity,
                verifiedNames);
        }
        return Set.copyOf(verifiedNames);
    }

    private static boolean isExactlyApplied(Path projectRoot, Path stage,
            String classicalObject) throws IOException {
        String owner = EdtSelectiveReviewService.edtOwner(classicalObject);
        Path source = stage.resolve("src").resolve(owner);
        Path target = projectRoot.resolve("src").resolve(owner);
        // Compare BOTH directions. Checking only staged -> project files could
        // erroneously clear a pending object when a locally changed module or
        // additional form remains in the EDT project.
        boolean stageDir = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
        boolean projectDir = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS);
        if (stageDir != projectDir) {
            return false;
        }
        boolean sawData = false;
        if (stageDir) {
            if (!equalFileTrees(source, target)) {
                return false;
            }
            try (Stream<Path> files = Files.walk(source)) {
                sawData = files.anyMatch(p ->
                    Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS));
            }
        }
        // Some EDT objects have a flat src/<type>/<name>.mdo while others
        // also include a directory src/<type>/<name>/.
        Path flatStage = stage.resolve("src").resolve(owner + ".mdo");
        Path flatProject = projectRoot.resolve("src").resolve(owner + ".mdo");
        boolean stageFlat = Files.isRegularFile(flatStage,
            LinkOption.NOFOLLOW_LINKS);
        boolean projectFlat = Files.isRegularFile(flatProject,
            LinkOption.NOFOLLOW_LINKS);
        if (stageFlat != projectFlat) {
            return false;
        }
        if (stageFlat) {
            sawData = true;
            if (!equalFile(flatStage, flatProject)) {
                return false;
            }
        }
        return sawData;
    }

    private static boolean equalFileTrees(Path left, Path right)
            throws IOException {
        try (Stream<Path> leftTree = Files.walk(left);
             Stream<Path> rightTree = Files.walk(right)) {
            Set<Path> a = new LinkedHashSet<>();
            Set<Path> b = new LinkedHashSet<>();
            for (Path file : leftTree.toList()) {
                if (Files.isSymbolicLink(file)) {
                    return false;
                }
                a.add(left.relativize(file));
            }
            for (Path file : rightTree.toList()) {
                if (Files.isSymbolicLink(file)) {
                    return false;
                }
                b.add(right.relativize(file));
            }
            if (!a.equals(b)) {
                return false;
            }
            for (Path rel : a) {
                Path first = left.resolve(rel);
                Path second = right.resolve(rel);
                boolean file = Files.isRegularFile(first,
                    LinkOption.NOFOLLOW_LINKS);
                if (file != Files.isRegularFile(second,
                        LinkOption.NOFOLLOW_LINKS)) {
                    return false;
                }
                if (file && !equalFile(first, second)) {
                    return false;
                }
                if (!file && (!Files.isDirectory(first, LinkOption.NOFOLLOW_LINKS)
                        || !Files.isDirectory(second, LinkOption.NOFOLLOW_LINKS))) {
                    return false;
                }
            }
            return true;
        }
    }

    private static boolean equalFile(Path a, Path b) throws IOException {
        return !Files.isSymbolicLink(a) && !Files.isSymbolicLink(b)
            && Files.size(a) == Files.size(b)
            && Files.mismatch(a, b) == -1L;
    }
}
