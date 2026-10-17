# Selective EDT merge: independent pending identities

## Observed failure (2026-10-17)

For Document.НачислениеПоСреднему, native selective comparison displayed
ManagerModule and both Forms. The user took selected changes and closed
the editor. The private post-merge audit reported 1 checked owner,
0 exactly identical owners, 3 still-pending native identities, and the first
difference as:

    Documents/НачислениеПоСреднему — различается содержимое:
        Forms/ФормаДокумента/Form.form

This evidence does NOT prove that ManagerModule.bsl was not transferred.
The old verifier mapped all native version identities to their one parent
EDT directory and would acknowledge NONE unless EVERY descendant byte
matched. Thus an independently versioned module remained pending because
another Form differed.

## Implementation

- Full receive remains unchanged: the entire imported source and EDT project
  must match strictly for a full applied-revision checkpoint.
- Selective receive first performs the same complete owner comparison. If
  exact, all selected pending identities of that owner are acknowledged.
- If the owner differs, verify only independently versioned resources with
  unambiguous native EDT paths:
  - Document.X.ManagerModule -> src/Documents/X/ManagerModule.bsl
  - Document.X.ObjectModule -> src/Documents/X/ObjectModule.bsl
  - selected Document.X.Form.Y -> complete src/Documents/X/Forms/Y subtree
    (requiring the Form.form descriptor, including its Module.bsl and others)
  - same paths apply to supported primary owner families provided by EDT.
- Before acknowledging a separately matched resource, BOTH primary owner
  metadata descriptors MUST exist and be identical. Names alone are not
  proof of metadata identity.
- Unknown/nonindependent objects, root changes and resources with unproved
  mappings remain pending until exact whole-owner verification. No fallback
  to assumed success based on checkboxes, merge-editor closure or log counts.
- A selective partial acceptance does NOT advance the native repository
  revision. This still requires zero pending changes and exact whole-owner
  verification.
- The private audit identifies independently verified versus unverified
  native names, plus the first mismatch byte offset and source/EDT file sizes;
  no XML or BSL content is written to the error log.

## Outstanding native Form.form mismatch

The user-provided audit has the FIRST differing path only, not the two actual
Form.form files. Therefore it is impossible to conclude whether the discrepancy
is caused by user merge selections, native EDT serialization/normalization,
an EDT save race, or true missed form-element changes. This change must NOT
treat byte-different forms as semantically equal.

To diagnose semantic form differences, compare both actual Form.form resources
from the imported staging and the EDT project (or use an independently verified
native EDT model comparison). No fabricated replacement files and no implicit
overwrite of the user's current EDT project.

## Validation policy

Three dedicated offline unit tests exercise independent module matching while
Form.form differs, independent form matching while the manager module differs,
and absent/unsupported resources plus primary descriptor mismatch. Java21
isolated compilation and a minimal runtime reproduction passed in the assistant
container. A full Tycho/OSGi CI build and actual EDT 2026.2 smoke test have
NOT been run after these changes: the project's GitHub Actions workflow runs
only with manual approval. Do not claim the form itself is resolved.
