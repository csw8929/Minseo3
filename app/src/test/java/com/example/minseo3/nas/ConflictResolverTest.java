package com.example.minseo3.nas;

import static com.example.minseo3.nas.NasSyncConstants.SAME_SESSION_OFFSET_DIFF;
import static com.example.minseo3.nas.NasSyncConstants.SAME_SESSION_WINDOW_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConflictResolverTest {

    private static final String THIS_DEVICE  = "device-A";
    private static final String OTHER_DEVICE = "device-B";

    @Test
    public void resolve_nasNull_keepsLocal() {
        ConflictOutcome out = ConflictResolver.resolve(100, 1000L, THIS_DEVICE, null);
        assertFalse(out.needsDialog);
        assertEquals(100, out.resolvedOffset);
    }

    @Test
    public void resolve_noLocalHistory_jumpsToNas() {
        RemotePosition nas = pos(500, 2000L, OTHER_DEVICE);
        ConflictOutcome out = ConflictResolver.resolve(0, 0L, THIS_DEVICE, nas);
        assertFalse(out.needsDialog);
        assertEquals(500, out.resolvedOffset);
    }

    @Test
    public void resolve_withinSameSession_keepsLocal() {
        // same device wrote the NAS record just 1 minute ago, charOffset differ by 100.
        long now = System.currentTimeMillis();
        RemotePosition nas = pos(1_100, now, THIS_DEVICE);
        ConflictOutcome out = ConflictResolver.resolve(1_000, now - 60_000L, THIS_DEVICE, nas);
        assertFalse(out.needsDialog);
        assertEquals(1_000, out.resolvedOffset);
    }

    @Test
    public void resolve_nasNewer_differentDevice_smallDiff_jumpsToNas() {
        // tablet wrote 30s ago, charOffset drift 50 -> within same-session window.
        // Should still keep local because drift is small. Edge case for the inclusion rule.
        long now = System.currentTimeMillis();
        RemotePosition nas = pos(1_050, now, OTHER_DEVICE);
        ConflictOutcome out = ConflictResolver.resolve(1_000, now - 30_000L, THIS_DEVICE, nas);
        assertFalse(out.needsDialog);
        assertEquals(1_000, out.resolvedOffset);
    }

    @Test
    public void resolve_nasNewerByHours_jumpsToNasSilently() {
        long nowLocal = System.currentTimeMillis() - 3 * 60 * 60 * 1000L;
        long nowNas   = System.currentTimeMillis();
        RemotePosition nas = pos(50_000, nowNas, OTHER_DEVICE);
        ConflictOutcome out = ConflictResolver.resolve(10_000, nowLocal, THIS_DEVICE, nas);
        assertFalse(out.needsDialog);
        assertEquals(50_000, out.resolvedOffset);
    }

    @Test
    public void resolve_localNewer_keepsLocal() {
        long nowNas   = System.currentTimeMillis() - 3 * 60 * 60 * 1000L;
        long nowLocal = System.currentTimeMillis();
        RemotePosition nas = pos(10_000, nowNas, OTHER_DEVICE);
        ConflictOutcome out = ConflictResolver.resolve(50_000, nowLocal, THIS_DEVICE, nas);
        assertFalse(out.needsDialog);
        assertEquals(50_000, out.resolvedOffset);
    }

    @Test
    public void resolve_bothRecent_differentDevices_bigDiff_needsDialog() {
        // Two devices both updated within the 5-minute window, offset diff > 500 -> ambiguous.
        long now = System.currentTimeMillis();
        RemotePosition nas = pos(10_000, now, OTHER_DEVICE);
        ConflictOutcome out = ConflictResolver.resolve(20_000, now - 60_000L, THIS_DEVICE, nas);
        assertTrue(out.needsDialog);
        assertEquals(20_000, out.resolvedOffset); // dialog fallback = local
    }

    @Test
    public void constants_areSane() {
        assertEquals(5 * 60 * 1000L, SAME_SESSION_WINDOW_MS);
        assertEquals(500, SAME_SESSION_OFFSET_DIFF);
    }

    private static RemotePosition pos(int charOffset, long epoch, String deviceId) {
        return new RemotePosition("a.txt", 1234L, charOffset, 380_000, deviceId, epoch);
    }
}
