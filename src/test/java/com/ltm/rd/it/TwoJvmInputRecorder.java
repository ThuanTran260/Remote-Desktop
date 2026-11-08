package com.ltm.rd.it;

import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** EDT-confined recorder; completion never disarms it before the final cleanup audit. */
final class TwoJvmInputRecorder {
    static final int PROBE_KEY = KeyEvent.VK_F24;
    private final List<String> keys = new ArrayList<>();
    private final Set<Integer> held = new HashSet<>();
    private CompletableFuture<Void> probe = new CompletableFuture<>();
    private boolean armed;
    private boolean probeExpected;
    private boolean validated;
    private int probePresses;
    private int probeReleases;
    private TwoJvmDiagnostics.Reason failure;

    void arm() {
        keys.clear();
        held.clear();
        probe = new CompletableFuture<>();
        failure = null;
        probeExpected = false;
        validated = false;
        probePresses = 0;
        probeReleases = 0;
        armed = true;
    }

    void record(String kind, KeyEvent event) {
        if (!armed) return;
        if (validated || probeReleases > 0) reject(TwoJvmDiagnostics.Reason.LateNativeInput);
        int key = event.getKeyCode();
        if (event.getModifiersEx() != 0 || (key != KeyEvent.VK_A && key != KeyEvent.VK_B && key != PROBE_KEY)) {
            reject(TwoJvmDiagnostics.Reason.ModifiersOrUnexpectedKey);
        }
        if (kind.equals("P")) held.add(key);
        else if (kind.equals("R")) held.remove(key);
        else reject(TwoJvmDiagnostics.Reason.ModifiersOrUnexpectedKey);
        keys.add(kind + key);
        if (key == PROBE_KEY) {
            if (!probeExpected) reject(TwoJvmDiagnostics.Reason.InvalidProbeSequence);
            if (kind.equals("P")) probePresses++;
            else if (kind.equals("R")) probeReleases++;
            if (probePresses != 1 || probeReleases > 1) reject(TwoJvmDiagnostics.Reason.InvalidProbeSequence);
            if (probeReleases == 1) {
                if (failure == null) probe.complete(null);
                else probe.completeExceptionally(new IllegalStateException(failure.name()));
            }
        }
    }

    void reject(TwoJvmDiagnostics.Reason reason) {
        if (armed && failure == null) failure = reason;
    }

    boolean ready() { return keys.size() >= 100 || failure != null; }

    List<String> snapshot() { return List.copyOf(keys); }

    void prepareProbe() {
        if (!armed || probeExpected || validated) throw new IllegalStateException("InvalidProbeSequence");
        probeExpected = true; // Called on EDT only after the public input/session/runtime stop boundary.
    }

    void awaitProbe(Duration timeout) throws Exception {
        probe.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    List<String> finish() {
        validate();
        validated = true;
        return List.copyOf(keys);
    }

    void audit() {
        if (failure != null) throw new IllegalStateException(failure.name());
        if (validated) validate();
    }

    private void validate() {
        if (failure != null) throw new IllegalStateException(failure.name());
        if (!held.isEmpty()) throw new IllegalStateException("HeldNativeInput");
        if (!probeExpected || probePresses != 1 || probeReleases != 1 || !probe.isDone()) {
            throw new IllegalStateException("NativeProbeMissing");
        }
        List<String> expected = new ArrayList<>();
        for (int pair = 0; pair < 50; pair++) {
            int key = pair % 2 == 0 ? KeyEvent.VK_A : KeyEvent.VK_B;
            expected.add("P" + key);
            expected.add("R" + key);
        }
        expected.add("P" + PROBE_KEY);
        expected.add("R" + PROBE_KEY);
        if (!keys.equals(expected)) throw new IllegalStateException("UnexpectedNativeSequence");
    }

    boolean heldEmpty() { return held.isEmpty(); }

    void disarm() { armed = false; }
}
