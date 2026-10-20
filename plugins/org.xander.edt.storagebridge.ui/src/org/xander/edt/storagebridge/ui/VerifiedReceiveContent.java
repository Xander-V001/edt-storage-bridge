package org.xander.edt.storagebridge.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.core.resources.IProject;
import org.osgi.service.prefs.BackingStoreException;
import org.xander.edt.storagebridge.core.EdtOwnerContentVerifier;
import org.xander.edt.storagebridge.core.EdtSelectedResourceVerifier;
import org.xander.edt.storagebridge.core.IncomingPictureAudit;
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
        return verifyAndClear(project, repositoryAddress, branch,
            intermediaryIdentity, selectedRoots, selectedPendingNames,
            absentRemoteNames, fullImportedOwners, convertedEdt, null);
    }

    public static Result verifyAndClear(IProject project,
            String repositoryAddress, String branch, String intermediaryIdentity,
            Set<String> selectedRoots, Set<String> selectedPendingNames,
            Set<String> absentRemoteNames, Set<String> fullImportedOwners,
            Path convertedEdt, Path originalDesignerXml)
            throws IOException, BackingStoreException {
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
        // Full baseline can contain tens of thousands of independently
        // versioned names. Map them to EDT owners ONCE, not once per owner.
        Map<String, Set<String>> identitiesByOwner = new HashMap<>();
        for (String pendingName : selectedPendingNames) {
            String owner = EdtSelectiveReviewService.edtOwner(pendingName);
            identitiesByOwner.computeIfAbsent(owner, ignored -> new LinkedHashSet<>())
                .add(pendingName);
        }
        // Full mode verifies ALL imported owners, not only versioned names
        // in ConfigDumpInfo.xml. Selective mode stays limited to its actual
        // selected owners; imported dependencies cannot widen acceptance.
        Set<String> owners = new LinkedHashSet<>(fullImportedOwners);
        if (!fullImportedOwners.isEmpty()) {
            // Global native comparison includes local-only owners as well.
            // If the user leaves such a local object in EDT, do NOT claim a
            // byte-identical mirror or advance the entire storage revision.
            // This detects unmatched additions/deletions in EITHER direction.
            owners.addAll(EdtSelectiveReviewService
                .primaryDescriptors(projectRoot).keySet());
        }
        for (String root : selectedRoots) {
            owners.add(EdtSelectiveReviewService.edtOwner(root));
        }
        for (String owner : owners) {
            if (EdtOwnerContentVerifier.isExactlyApplied(
                    projectRoot, stage, owner)) {
                matched++;
                verifiedNames.addAll(identitiesByOwner.getOrDefault(owner, Set.of()));
            } else {
                unresolvedOwners++;
                if (fullImportedOwners.isEmpty()) {
                    // SELECTIVE receive: each independently versioned
                    // classical identity is its own pending obligation.
                    // A single unmatched Form.form must NOT keep an otherwise
                    // byte-identical ManagerModule.bsl pending forever.
                    //
                    // Full receive keeps strict owner-wide equality: this
                    // branch MUST NOT invent a partially applied global HEAD.
                    for (String pendingName : identitiesByOwner
                            .getOrDefault(owner, Set.of())) {
                        if (EdtSelectedResourceVerifier.isIndependentlyApplied(
                                projectRoot, stage, owner, pendingName)) {
                            verifiedNames.add(pendingName);
                            if (differences.size() < 500) {
                                differences.add("  Подтверждено отдельно: "
                                    + pendingName);
                            }
                        } else if (differences.size() < 500) {
                            differences.add("  Не подтверждено отдельно: "
                                + pendingName);
                        }
                    }
                }
                if (differences.size() < 500) {
                    String mismatch = EdtOwnerContentVerifier.firstMismatch(
                        projectRoot, stage, owner);
                    differences.add(owner + " — " + (mismatch == null
                        ? "не удалось установить первую разницу" : mismatch));
                    // Classify picture loss at the exact conversion boundary.
                    // This is diagnostic only; never copy or auto-accept a
                    // binary that the user's native merge left unchanged.
                    if (originalDesignerXml != null
                            && owner.startsWith("CommonPictures/")) {
                        try {
                            differences.add("  1C XML → временный EDT → рабочий EDT: "
                                + IncomingPictureAudit.inspect(originalDesignerXml,
                                    stage, projectRoot, owner));
                        } catch (IOException ex) {
                            differences.add("  Проверка этапов переноса изображения "
                                + "не завершена: " + ex.getClass().getSimpleName());
                        }
                    }
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
            // A removed independently developed child (Form/Template/
            // Module) can have a still-existing parent. The deletion is
            // proven only if the WHOLE resulting parent owner now matches
            // the native repository source. Missing an owner is separately
            // checked on both sides. Never infer deletion from the absence
            // of an individual ConfigDumpInfo name alone.
            boolean parentMatches = EdtOwnerContentVerifier.isExactlyApplied(
                projectRoot, stage, owner);
            boolean ownerDeleted = !parentMatches
                && EdtOwnerContentVerifier.isTopLevelDeletionApplied(
                    projectRoot, stage, owner);
            if (parentMatches || ownerDeleted) {
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
        Set<String> remainingNames = ReceivePendingStore.get(
            project, repositoryAddress, branch, intermediaryIdentity);
        int remaining = remainingNames.size();
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
                    .append("Verified pending identities (including individually "
                        + "verified selective resources): ")
                    .append(verifiedNames.size())
                    .append('\n')
                    .append("Still pending identities: ").append(remaining)
                    .append("\n\nFirst difference for up to 500 owners:\n");
                detail.append("\nStill pending classical metadata identities "
                    + "(up to 500):\n");
                int listed = 0;
                for (String name : new java.util.TreeSet<>(remainingNames)) {
                    if (listed++ >= 500) break;
                    detail.append("  ").append(name).append('\n');
                }
                if (remaining > 500) {
                    detail.append("... omitted ").append(remaining - 500)
                        .append(" remaining identities\n");
                }
                detail.append("\nDetailed owner/resource differences:\n");
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
