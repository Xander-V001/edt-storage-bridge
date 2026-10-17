package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.core.resources.IProject;
import org.osgi.service.prefs.BackingStoreException;
import org.xander.edt.storagebridge.core.EdtOwnerContentVerifier;
import org.xander.edt.storagebridge.edt.EdtSelectiveReviewService;

/**
 * Read-only after-merge audit, followed by clearing ONLY proven identical
 * pending objects. A checked EDT merge option or editor close is never proof
 * that the entire imported content was applied.
 *
 * Native EDT may serialize imported content differently; such objects remain
 * pending with a precise local-only diagnostic. They cannot silently advance
 * the "fully applied revision", especially while form command mappings are
 * known to differ in real EDT->CF round trips.
 */
public final class VerifiedReceiveContent {
    private VerifiedReceiveContent() {}

    public record Result(int checkedOwners, int identicalOwners,
            int confirmedPending, int remainingPending, Path diagnosticFile) {
        public Result {
            if (checkedOwners < 0 || identicalOwners < 0
                    || confirmedPending < 0 || remainingPending < 0
                    || identicalOwners > checkedOwners) {
                throw new IllegalArgumentException("Invalid receive verification summary");
            }
        }
    }

    public static Result verifyAndClear(IProject project,
            String repositoryAddress, String branch, String intermediaryIdentity,
            Set<String> selectedRoots, Set<String> selectedPendingNames,
            Path convertedEdt) throws IOException, BackingStoreException {
        return verifyAndClear(project, repositoryAddress, branch,
            intermediaryIdentity, selectedRoots, selectedPendingNames,
            Set.of(), Set.of(), convertedEdt);
    }

    public static Result verifyAndClear(IProject project,
            String repositoryAddress, String branch, String intermediaryIdentity,
            Set<String> selectedRoots, Set<String> selectedPendingNames,
            Set<String> absentRemoteNames, Set<String> fullImportedOwners,
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
        List<String> differences = new ArrayList<>();
        int matched = 0;
        int unresolvedOwners = 0;
        // Full mode verifies ALL imported owners, not only versioned names
        // in ConfigDumpInfo.xml. Selective mode stays limited to its actual
        // selected owners; imported dependencies cannot widen acceptance.
        Set<String> owners = new LinkedHashSet<>(fullImportedOwners);
        for (String root : selectedRoots) {
            owners.add(EdtSelectiveReviewService.edtOwner(root));
        }
        for (String owner : owners) {
            if (EdtOwnerContentVerifier.isExactlyApplied(
                    projectRoot, stage, owner)) {
                matched++;
                for (String pendingName : selectedPendingNames) {
                    if (EdtSelectiveReviewService.edtOwner(pendingName)
                            .equals(owner)) {
                        verifiedNames.add(pendingName);
                    }
                }
            } else {
                unresolvedOwners++;
                if (differences.size() < 500) {
                    String mismatch = EdtOwnerContentVerifier.firstMismatch(
                        projectRoot, stage, owner);
                    differences.add(owner + " — " + (mismatch == null
                        ? "не удалось установить первую разницу" : mismatch));
                }
            }
        }
        // A previously known owner may have been removed altogether from
        // the remote inventory. Full comparison now exposes it on the main
        // side; clear its pending identity ONLY when both its remote stage
        // and actual EDT resources are absent after the native merge.
        // For a removed nested Form whose parent still exists, the regular
        // owner-equality check above is the required proof instead.
        for (String deletedName : absentRemoteNames) {
            if (verifiedNames.contains(deletedName)) {
                continue;
            }
            String owner = EdtSelectiveReviewService.edtOwner(deletedName);
            if ("Configuration/Configuration".equals(owner)) {
                continue; // embedded roots/languages are checked as a whole
            }
            if (EdtOwnerContentVerifier.isTopLevelDeletionApplied(
                    projectRoot, stage, owner)) {
                verifiedNames.add(deletedName);
            } else if (differences.size() < 500) {
                differences.add(owner + " — отсутствующий справа объект "
                    + "или его вложенный ресурс ещё не подтверждён как удалённый");
            }
        }
        if (!verifiedNames.isEmpty()) {
            ReceivePendingStore.clearVerified(
                project, repositoryAddress, branch, intermediaryIdentity,
                verifiedNames);
        }
        int remaining = ReceivePendingStore.get(
            project, repositoryAddress, branch, intermediaryIdentity).size();
        Path report = null;
        if (unresolvedOwners > 0 || remaining > 0) {
            // Private, ephemeral report. Metadata names and file paths can
            // reveal proprietary code: never log its contents automatically.
            try {
                report = Files.createTempFile("edt-storage-merge-review-", ".txt");
                StringBuilder detail = new StringBuilder(8192);
                detail.append("1C EDT Storage Bridge: post-merge audit\n")
                    .append("Potentially confidential: review before sharing.\n")
                    .append("Editor closed; this does not prove Merge was clicked.\n")
                    .append("Checked owners: ").append(owners.size()).append('\n')
                    .append("Exactly matching owners: ").append(matched).append('\n')
                    .append("Owner trees with differences: ").append(unresolvedOwners)
                    .append('\n')
                    .append("Removed pending identities: ").append(verifiedNames.size())
                    .append('\n')
                    .append("Still pending identities: ").append(remaining)
                    .append("\n\nFirst difference for up to 500 owners:\n");
                for (String item : differences) {
                    detail.append(item).append('\n');
                }
                if (unresolvedOwners > differences.size()) {
                    detail.append("... omitted ")
                        .append(unresolvedOwners - differences.size())
                        .append(" more mismatched owners\n");
                }
                Files.writeString(report, detail.toString(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                if (report != null) {
                    try { Files.deleteIfExists(report); }
                    catch (IOException ignored) { /* best effort */ }
                }
                report = null;
            }
        }
        return new Result(owners.size(), matched,
            verifiedNames.size(), remaining, report);
    }

    /** Compatibility bridge for existing callers; prefer Result diagnostics. */
    public static Set<String> clearMatchingSelected(IProject project,
            String repositoryAddress, String branch, String intermediaryIdentity,
            Set<String> selectedRoots, Set<String> selectedPendingNames,
            Path convertedEdt) throws IOException, BackingStoreException {
        Set<String> before = ReceivePendingStore.get(
            project, repositoryAddress, branch, intermediaryIdentity);
        verifyAndClear(project, repositoryAddress, branch,
            intermediaryIdentity, selectedRoots, selectedPendingNames, convertedEdt);
        Set<String> after = ReceivePendingStore.get(
            project, repositoryAddress, branch, intermediaryIdentity);
        var removed = new LinkedHashSet<>(before);
        removed.removeAll(after);
        return Set.copyOf(removed);
    }
}
