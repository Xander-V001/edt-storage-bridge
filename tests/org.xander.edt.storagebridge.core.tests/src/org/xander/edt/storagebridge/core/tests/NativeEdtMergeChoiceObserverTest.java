package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.xander.edt.storagebridge.edt.NativeEdtMergeChoiceObserver;

/** Pure decision tests: no Designer, EDT session or project modifications. */
public class NativeEdtMergeChoiceObserverTest {
    private static NativeEdtMergeChoiceObserver.NodeChoice choice(
            String owner, boolean checked, String rule,
            boolean nestedExcluded, boolean custom) {
        return new NativeEdtMergeChoiceObserver.NodeChoice(
            10L, owner, "NativeComparisonNode", checked, rule,
            false, nestedExcluded, custom, "COMPARISON_FINISHED");
    }

    private static NativeEdtMergeChoiceObserver.Snapshot snapshot(
            boolean complete, NativeEdtMergeChoiceObserver.NodeChoice... choices) {
        return new NativeEdtMergeChoiceObserver.Snapshot(
            List.of(choices), choices.length, 0, 0, 0, 0,
            true, complete, true);
    }

    @Test
    public void completeGlobalReviewClearsPriorKeepWhenDifferenceGone() {
        var review = snapshot(true,
            choice("Document.Unrelated", false, "DoNotMerge", false, false))
            .recheckKeptLocal(Set.of("Document.PreviouslyKept"));
        assertTrue(review.complete());
        assertTrue(review.remaining().isEmpty());
    }

    @Test
    public void completeGlobalReviewClearsPriorKeepWhenOtherSideAccepted() {
        var review = snapshot(true,
            choice("Document.X.Form.Y", true, "GetFromOther", false, false))
            .recheckKeptLocal(Set.of("Document.X.Form.Y"));
        assertTrue(review.remaining().isEmpty());
    }

    @Test
    public void changedUncheckedNodePreservesOnlyItsExistingKeptIdentity() {
        var review = snapshot(true,
            choice("Document.X.Form.Y", false, "DoNotMerge", false, false),
            choice("Document.NotInbound", false, "DoNotMerge", false, false))
            .recheckKeptLocal(Set.of("Document.X.Form.Y"));
        assertEquals(Set.of("Document.X.Form.Y"), review.remaining());
        assertFalse(review.remaining().contains("Document.NotInbound"));
    }

    @Test
    public void nestedExclusionAndCustomRulesDoNotErasePreviousKeep() {
        var nested = snapshot(true,
            choice("Document.X.Form.Y", true, "GetFromOther", true, false))
            .recheckKeptLocal(Set.of("Document.X.Form.Y"));
        assertEquals(Set.of("Document.X.Form.Y"), nested.remaining());
        var custom = snapshot(true,
            choice("Document.X.Form.Y", true, "GetFromOther", false, true))
            .recheckKeptLocal(Set.of("Document.X.Form.Y"));
        assertEquals(Set.of("Document.X.Form.Y"), custom.remaining());
    }

    @Test
    public void incompleteComparisonMustNeverClearPreviousKeep() {
        var before = Set.of("Document.X.Form.Y");
        var review = snapshot(false,
            choice("Document.X.Form.Y", true, "GetFromOther", false, false))
            .recheckKeptLocal(before);
        assertFalse(review.complete());
        assertEquals(before, review.remaining());
    }

    @Test
    public void emptyHistoryDoesNotCreateKeepsFromLocalOnlyDifferences() {
        var review = snapshot(true,
            choice("Document.LocalOnly", false, "DoNotMerge", false, false))
            .recheckKeptLocal(Set.of());
        assertTrue(review.remaining().isEmpty());
    }

    @Test
    public void selectiveKeepRequiresUncheckedDifferentNodeNotParentFlagAlone() {
        var selected = snapshot(true,
            choice("Document.X.Form.Y", true, "GetFromOther", true, false))
            .classifyPending(Set.of("Document.X.Form.Y"));
        assertEquals(Set.of("Document.X.Form.Y"), selected.accepted());
        assertTrue(selected.keptLocal().isEmpty());

        var retained = snapshot(true,
            choice("Document.X.Form.Y", false, "DoNotMerge", false, false))
            .classifyPending(Set.of("Document.X.Form.Y"));
        assertEquals(Set.of("Document.X.Form.Y"), retained.keptLocal());
    }

    @Test
    public void similarNamesDoNotGetConfused() {
        var selected = snapshot(true,
            choice("Document.XYZ", false, "DoNotMerge", false, false))
            .classifyPending(Set.of("Document.X"));
        assertTrue(selected.keptLocal().isEmpty());
        assertEquals(Set.of("Document.X"), selected.unclassified());
    }
}
