package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.xander.edt.storagebridge.core.RepositoryHeadAttestation;

/** No Designer, clock waits, Git or user's EDT project. */
public final class RepositoryHeadAttestationTest {
    @Test
    public void temporarilyUnavailableNativeReportCanBeConfirmedLater()
            throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger pauses = new AtomicInteger();
        var result = RepositoryHeadAttestation.confirm(29405L,
            () -> reads.incrementAndGet() < 3 ? -1L : 29405L,
            () -> false, delay -> pauses.incrementAndGet());
        assertEquals(RepositoryHeadAttestation.State.CONFIRMED, result.state());
        assertEquals(29405L, result.observed());
        assertEquals(3, result.attempts());
        assertEquals(2, pauses.get());
    }

    @Test
    public void changedRealNativeHeadIsNeverAcceptedEvenAfterRetry()
            throws Exception {
        AtomicInteger reads = new AtomicInteger();
        var result = RepositoryHeadAttestation.confirm(29405L,
            () -> reads.incrementAndGet() == 1 ? -1L : 29406L,
            () -> false, delay -> {});
        assertEquals(RepositoryHeadAttestation.State.CHANGED, result.state());
        assertEquals(29406L, result.observed());
        assertEquals(2, result.attempts());
        assertEquals(2, reads.get());
    }

    @Test
    public void allUnavailableReportsRemainUnverified()
            throws Exception {
        var result = RepositoryHeadAttestation.confirm(29405L,
            () -> -1L, () -> false, delay -> {});
        assertEquals(RepositoryHeadAttestation.State.UNAVAILABLE, result.state());
        assertEquals(-1L, result.observed());
        assertEquals(4, result.attempts());
    }

    @Test
    public void cancelBeforeTheProbeNeverReadsNativeHead()
            throws Exception {
        AtomicInteger reads = new AtomicInteger();
        var result = RepositoryHeadAttestation.confirm(29405L,
            () -> { reads.incrementAndGet(); return 29405L; },
            () -> true, delay -> {});
        assertEquals(RepositoryHeadAttestation.State.CANCELLED, result.state());
        assertEquals(0, result.attempts());
        assertEquals(0, reads.get());
    }

    @Test
    public void rejectUnknownExpectedRevisionAndDoNotFakeCheckpoint() {
        assertThrows(IllegalArgumentException.class,
            () -> RepositoryHeadAttestation.confirm(-1L, () -> 29405L,
                () -> false, delay -> {}));
    }
}
