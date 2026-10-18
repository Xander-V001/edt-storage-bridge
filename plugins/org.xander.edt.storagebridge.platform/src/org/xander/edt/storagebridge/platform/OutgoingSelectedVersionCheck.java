package org.xander.edt.storagebridge.platform;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fast, read-only detection of remote changes in ONLY the metadata identities
 * chosen for outgoing publication. Uses native 1C ConfigDumpInfo opaque
 * ID/token pairs, no XML object dump, EDT import or full model comparison.
 *
 * The reference inventory MUST have been captured for the same repository,
 * selected objects and a proven previously known revision. This pure checker
 * does not itself establish that provenance or authorize a Designer commit.
 *
 * Unrelated 1C metadata changes are deliberately irrelevant here.
 */
public final class OutgoingSelectedVersionCheck {
    public enum State {
        /** The native ID AND opaque content version are equal. */
        UNCHANGED,
        /** Same native identity, newer/different remote content version. */
        REMOTE_CHANGED,
        /** Object identity changed, e.g. delete/recreate with same name. */
        REPLACED,
        /** Selected object did not exist in earlier known state. */
        NO_BASELINE,
        /** Previously known object no longer exists in current storage. */
        REMOVED
    }

    public record Result(Map<String, State> selected) {
        public Result {
            selected = Map.copyOf(Objects.requireNonNull(selected));
        }

        /** Indicates whether ALL selected objects have matching native tokens. */
        public boolean hasNoDetectedRemoteChange() {
            return !selected.isEmpty()
                && selected.values().stream().allMatch(State.UNCHANGED::equals);
        }
    }

    private OutgoingSelectedVersionCheck() {}

    public static Result compare(
            Map<String, ConfigDumpInfoIndex.Version> known,
            Map<String, ConfigDumpInfoIndex.Version> current,
            Set<String> selectedNames) {
        Objects.requireNonNull(known, "known native version inventory");
        Objects.requireNonNull(current, "current native version inventory");
        Objects.requireNonNull(selectedNames, "selected storage metadata names");
        if (selectedNames.isEmpty()) {
            throw new IllegalArgumentException("Select at least one metadata object");
        }
        var statuses = new LinkedHashMap<String, State>();
        for (String name : selectedNames) {
            if (name == null || !name.matches(
                    "[\\p{L}_][\\p{L}\\p{N}_]*"
                    + "(\\.[\\p{L}_][\\p{L}\\p{N}_]*)+")) {
                throw new IllegalArgumentException("Invalid selected metadata identity");
            }
            var previous = known.get(name);
            var latest = current.get(name);
            final State state;
            if (previous == null) {
                state = State.NO_BASELINE;
            } else if (latest == null) {
                state = State.REMOVED;
            } else if (!previous.id().equals(latest.id())) {
                state = State.REPLACED;
            } else if (!previous.token().equals(latest.token())) {
                state = State.REMOTE_CHANGED;
            } else {
                state = State.UNCHANGED;
            }
            statuses.put(name, state);
        }
        return new Result(statuses);
    }
}
