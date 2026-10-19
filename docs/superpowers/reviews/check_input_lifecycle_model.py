"""Bounded design model, NOT a test of a Java implementation or the JVM memory model.

Run: python docs/superpowers/reviews/check_input_lifecycle_model.py
Each transition models one short atomic metadata operation from design section 3.5.
Two generations, queue capacity two, one worker, normal successful releases only.
"""
from dataclasses import dataclass, replace


@dataclass(frozen=True)
class State:
    phase: str = "IDLE"
    gen: int = 0
    last: int = 0
    queued: int = 0
    inflight: int = 0
    held: int = 0
    released: frozenset = frozenset()


def transitions(s):
    for g in (1, 2):
        if s.phase == "IDLE" and g > s.last:
            yield f"begin({g})", replace(s, phase="OPEN", gen=g, last=g)
        if s.phase == "OPEN" and s.gen == g:
            if s.queued < 2:
                yield f"submit({g})", replace(s, queued=s.queued + 1)
            else:
                # Caller reacts to OVERFLOW by closing the same generation.
                yield "overflow/end", replace(s, phase="ENDING", queued=0)
            yield f"end({g})", replace(s, phase="ENDING", queued=0)
        # Stale/repeated end is a no-op, including after the next begin.
        else:
            yield f"stale_or_repeat_end({g})", s
    if s.phase == "OPEN" and s.queued and not s.inflight:
        yield "claim_command", replace(s, queued=s.queued - 1, inflight=s.gen)
    if s.inflight:
        assert s.inflight not in s.released, "old command after completed END"
        yield "finish_command", replace(s, held=s.inflight, inflight=0)
    if s.phase == "ENDING" and not s.inflight:
        yield "release_success", replace(
            s, phase="IDLE", gen=0, held=0, released=s.released | {s.gen}
        )
        yield "release_failure", replace(s, phase="FAULTED")


def check(s):
    assert 0 <= s.queued <= 2
    assert not (s.phase == "IDLE" and (s.held or s.inflight or s.queued))
    assert not (s.phase == "ENDING" and s.queued)
    assert not (s.held and s.gen and s.held != s.gen)
    assert not (s.inflight and s.inflight in s.released)
    assert not (s.phase == "OPEN" and s.gen in s.released)


def witness_original_defects():
    # Original begin only queued the write to activeGen. Immediate submit is lost.
    active = None
    pending_begin = 1
    accepted = active == 1
    assert not accepted
    # Original end sees NONE, so pending begin later resurrects the ended generation.
    if active == 1:
        active = None
    active = pending_begin
    assert active == 1
    return ["first submit rejected before queued begin", "queued begin reopens ended generation"]


def take(s, name):
    options = dict(transitions(s))
    assert name in options, (s, name)
    result = options[name]
    check(result)
    return result


def targeted_traces():
    s = take(State(), "begin(1)")
    s = take(s, "submit(1)")  # accepted immediately, no worker BEGIN needed
    s = take(s, "claim_command")
    s = take(s, "end(1)")
    assert "begin(2)" not in dict(transitions(s))
    s = take(s, "finish_command")
    s = take(s, "release_success")
    s = take(s, "begin(2)")
    assert take(s, "stale_or_repeat_end(1)") == s
    s = take(s, "submit(2)")
    s = take(s, "submit(2)")
    s = take(s, "overflow/end")  # full command queue cannot block lifecycle END
    s = take(s, "release_failure")
    assert not any(name.startswith("begin") for name, _ in transitions(s))


def main():
    defects = witness_original_defects()
    targeted_traces()
    seen = {State()}
    frontier = [State()]
    edges = 0
    while frontier:
        s = frontier.pop()
        check(s)
        for _, nxt in transitions(s):
            edges += 1
            check(nxt)
            if nxt not in seen:
                seen.add(nxt)
                frontier.append(nxt)
    print(f"Original design counterexamples: {len(defects)}")
    for defect in defects:
        print(f"  - {defect}")
    print(f"Revised bounded model: {len(seen)} states, {edges} transitions; invariants PASS")
    print("Targeted traces: immediate submit, close during command, stale end, full queue, release failure PASS")
    print("Scope: abstract lifecycle only; Java concurrency, Robot and network remain unverified.")


if __name__ == "__main__":
    main()
