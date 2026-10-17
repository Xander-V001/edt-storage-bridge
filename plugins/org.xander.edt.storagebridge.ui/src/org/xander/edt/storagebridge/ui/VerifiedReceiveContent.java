package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.xander.edt.storagebridge.core.EdtOwnerContentVerifier;
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
            if (EdtOwnerContentVerifier.isExactlyApplied(
                    projectRoot, stage, EdtSelectiveReviewService.edtOwner(root))) {
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

}
