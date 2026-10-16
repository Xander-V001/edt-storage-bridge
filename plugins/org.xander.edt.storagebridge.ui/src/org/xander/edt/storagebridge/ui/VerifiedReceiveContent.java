package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.file.Files;
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
            String repositoryAddress, String branch,
            Set<String> selected, Path convertedEdt)
            throws IOException, BackingStoreException {
        if (project.getLocation() == null || !project.isAccessible()) {
            throw new IOException("EDT project is not available for verification");
        }
        Path projectRoot = project.getLocation().toFile().toPath().toRealPath();
        Path stage = convertedEdt.toRealPath();
        if (stage.startsWith(projectRoot) || projectRoot.startsWith(stage)) {
            throw new IOException("Invalid receive staging root");
        }
        Set<String> verified = new LinkedHashSet<>();
        for (String object : selected) {
            if (isExactlyApplied(projectRoot, stage, object)) {
                verified.add(object);
            }
        }
        if (!verified.isEmpty()) {
            ReceivePendingStore.clearVerified(
                project, repositoryAddress, branch, verified);
        }
        return Set.copyOf(verified);
    }

    private static boolean isExactlyApplied(Path projectRoot, Path stage,
            String classicalObject) throws IOException {
        String owner = EdtSelectiveReviewService.edtOwner(classicalObject);
        Path stagedFolder = stage.resolve("src").resolve(owner);
        Path projectFolder = projectRoot.resolve("src").resolve(owner);
        boolean sawFile = false;
        if (Files.isDirectory(stagedFolder)) {
            try (Stream<Path> files = Files.walk(stagedFolder)) {
                for (Path stagedFile : files.filter(Files::isRegularFile).toList()) {
                    sawFile = true;
                    Path relative = stagedFolder.relativize(stagedFile);
                    Path projectFile = projectFolder.resolve(relative);
                    if (!Files.isRegularFile(projectFile)
                            || Files.size(projectFile) != Files.size(stagedFile)
                            || Files.mismatch(stagedFile, projectFile) != -1) {
                        return false;
                    }
                }
            }
        }
        // Some EDT objects use src/<group>/<name>.mdo directly, others use
        // src/<group>/<name>/<name>.mdo. Both must be verified if present.
        Path flat = stage.resolve("src").resolve(owner + ".mdo");
        if (Files.isRegularFile(flat)) {
            sawFile = true;
            Path local = projectRoot.resolve("src").resolve(owner + ".mdo");
            if (!Files.isRegularFile(local)
                    || Files.size(local) != Files.size(flat)
                    || Files.mismatch(flat, local) != -1) {
                return false;
            }
        }
        return sawFile;
    }
}
