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

## Generic native BSL resource verification

The original `EdtSelectedResourceVerifier` made a mistake: it hardcoded
five leaf names (`ManagerModule`, `ObjectModule`, `RecordSetModule`,
`ValueManagerModule`, `Module`). That was not a scalable strategy for the
1C metadata model. The replacement does **not** enumerate the 1C metadata
object kind or BSL module kind. Instead, for an independently versioned
`<MetadataKind>.<Name>.<ResourceLeaf>` identity, it:

1. Converts its enclosing top owner through the EXISTING canonical
   1C-to-EDT owner resolver in the EDT integration bundle.
2. Checks that the staging and active EDT owner primary `.mdo` descriptor
   files are byte-identical (same actual owner; names alone are unsafe).
3. Checks that the staged owner ACTUALLY has a direct child resource
   `<ResourceLeaf>.bsl`. Never fabricate a BSL filename or use an object
   kind allowlist.
4. Compares that resource in the stage and active project exactly.
5. Records a pending identity as applied only when its real resource matched.
   Otherwise keeps it pending. The full native-revision checkpoint still
   requires whole-owner equality.

These rules cover `Document.X.ObjectModule`,
`Catalog.X.ManagerModule`, `CommonModule.X.Module`,
`InformationRegister.X.RecordSetModule`, `CommonCommand.X.CommandModule`
and other actual direct native BSL resource leaves identically.

`CommonModule.X` WITHOUT a leaf suffix is itself a top-level metadata
owner: it is verified by existing full-owner logic rather than guessing the
name of its internal BSL module.

**Important remaining architectural limitation**: The above is a
generic *BSL resource* verifier, not an all-objects semantic 1C change
verifier. Form metadata, non-BSL resources, versioned inline properties,
resources with nonstandard native layouts and objects whose primary
descriptors differ still require validated native EDT comparison-model
support. No unrecognized resource is automatically acknowledged. This
must not be marketed as a universal semantic-merge completion check.
