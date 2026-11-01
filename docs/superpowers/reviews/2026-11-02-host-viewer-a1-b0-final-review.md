# Final broad review — C0 and A1–B0

Reviewed 2026-11-01/02 by Codex, **gpt-6.1-sol, xhigh**, as recorded in `final-review-launch.json` (`launch.effective`); continued the same Task/Dispatch after the coordinator's resume message. No replacement reviewer or helper was launched.

- Base: `30d160450105f1fd1f0be77e75c081bb4b1061f0`.
- Head: `d64b4d23bd16c7b32d650af11eb683180cfc7ccb`.
- Verified code SHA: `84a4682a93fae5f41320e549e252bfd3a872d57e`; a read-only comparison confirms no `src/` or `pom.xml` difference through Head.
- Scope: the supplied execution diff, spec, plan, constraints, coordinator rulings and archived evidence. This is the v1 checkpoint before B1, not certification of the earlier whole branch or completed v2.

## Strengths

The extraction preserves the existing synchronization boundary rather than inventing a second owner for role state. The deadline correction is isolated before the refactors, and the release barrier composes the actual lifecycle proofs. Tests exercise observable wire messages, real AUTH responses, input release, worker accounting and JPEG decoding, including deliberately held cleanup and exceptional completion.

## Spec and integration checks

Paths below are relative to the repository; abbreviated class names refer to their `src/main/java/com/ltm/rd/` paths.

| Area | Evidence and judgment |
| --- | --- |
| C0 activation deadline | `server/ServerSession.java:313` checks prerequisites and phase, then reads the clock once inside the transaction at `:316`; the same value governs expiration and initializes the side. All three activation paths call this method. Equality expires the reserve; the later tick retains TIMEOUT / `attach timeout`. The added latch test forces the stale pre-lock timestamp, and archived `C0-logs/red-final.log` shows its expected RESERVED-versus-ACTIVE failure before correction. |
| SideTx provenance and lifetime | `common/LifecyclePort.java:34` alone creates the private view from its own lifecycle. `:56` rejects a different port; `:82` checks native lifetime/thread ownership even if the shell ignores its argument. All view operations validate through the native transaction. There is no public wrap/native-tx factory and no production SideTx escape. Contract tests cover expired views, wrong port, wrong thread and nested after ordering. |
| Reentrant callbacks and snapshots | Nested port calls share the lifecycle lock and deferred queue. Native transactions expire per callback; actions run only after the outermost unlock. `server/ServerSession.java:343` holds that lock throughout phase/gate/epoch/frame/count assembly, including the side getters. `viewer/ControlSide.java:99` keeps closed/active/generation checks and frame publication in one transaction; gate/epoch checking and input offer are still atomic. Observer notifications remain ordered and run outside the lock. |
| Input begin/end and activation ordering | `client/ClientSession.java:415` retains the two start conditions, phase/closed checks and once-only flag. Its activation-completion callback at `:420` precedes the side's deferred sender start. `host/ControlledSide.java:69` begins input under the session-to-executor lock order, records successful ownership, and defers the sender; `:93` reads that ownership under the port and calls endSession outside the transaction. Native close is placed first in the deferred queue, so refused begin/exception cannot report successful activation. |
| Stable release source/target | Client target at `client/ClientSession.java:64` and server target at `server/ServerSession.java:90` remain constructor-owned final futures. Server close at `:116` transforms only the side's source. `common/SessionSide.java:21` waits for that source and bridges null, synchronous throw and exceptional completion into the existing target. It neither replaces the target nor completes it at reservation time. |
| PacketRules and v1 errors | `common/PacketRules.java:17` has the required twelve immutable rows; reader unions preserve the original never-valid-type error layer. Shell phase checks and the separate SCREEN_INFO/SESSION_ACTIVE/ATTACH once-only, token, payload and generation checks remain. Tests cover every 192 type/shell/lane/stage combination, immutable sets and reader unions, plus `protocol violation`, `unexpected SCREEN_INFO`, `unexpected SESSION_ACTIVE` and `input before session active`. PENDING input pass-through is intentional. |
| A4 package boundaries | Six viewer and twenty host production classes move as specified, including SubmitResult. FrameReceiver/ScreenSender depend on the neutral port; CommandReceiver accepts SideTx. The production reverse-import inventory contains only `viewer/ControlView.java:3` → server.ServerPhase, the expressly permitted B1 debt. Native shell test seams remain package-private. The saved strict body audit covers all 62 changed Java files without a mismatch; visible changes agree with the allowed imports, type references and visibility additions. |
| B0 slot, identity and PIN | `server/ServerCore.java:261` releases only after all three futures complete normally. `server/SessionManager.java:110` checks current identity and atomically releases/rotates the PIN, suppressing rotation during shutdown. Held release/worker tests observe the old identity, unchanged PIN and real AUTH/BUSY, then release, a changed PIN and AUTH/OK. Failed/null/throwing source tests retain BUSY, including repeated close; stale release cannot affect a new session. |
| Locking of release callbacks | Lifecycle socket/termination futures complete outside its lock, as does the closing bridge. Thus the new callback does not take the manager lock while holding the session lock. Constructor hook registration concerns a fresh, unpublished lifecycle; it creates no opposing lock edge with an existing session. No new I/O, future wait or direct worker start occurs inside a production transaction. |

## Old tests, exceptions and evidence

The test diff preserves old assertions, expected values, inputs, timeouts and close reasons except for the explicitly authorized VF0 scheduled test and B0 slot waits. The two PING tests move to HeartbeatTest with the same inputs/assertions, capacities, sequence values, phase and wait budget; only their call boundary changes. Commit bodies enumerate old-test changes and the PING mapping.

- **VF0:** the separate commit changes only the scheduled newest-answer test from a 1000-poll throughput demand to two ordered scheduled answers at 20 ms, independently awaiting completed polls. The manual 1000-publication test and both 1000 assertions remain; WAIT_S remains 10. SnapshotPump uses fixed delay and publishes latest before incrementing polls, supporting both corrections. The original slowdown's environmental cause remains unknown.
- **A4 exceptions:** keeping both shell-coupled FrameReceiver tests in server avoids exposing native lifecycle access. The public reader/decoder seams are limited and body-preserving. All 40 moves remain renames; the five short-file similarity exceptions are DisplayPort 88%, RobotPortFactory 84%, SubmitResult 88%, ControlView 87%, InputOutcome 86%. Strict body equality is more meaningful than these ratios.
- **B0 old waits:** only `reserveExpiredTrace`, `clientDisconnectDuringActiveTrace`, `serverDisconnectDuringActiveTrace`, `readerStartFailureClosesSession` and `reservedWithoutAttachTimesOutAndCodeChanges` gain bounded slot waiting. The golden file's local BooleanSupplier/LockSupport helper uses unchanged WAIT_S. Golden literals, other trace assertions and RecordingRelay remain intact.
- **Independent coordinator evidence:** `B0-coordinator-tests.json` records code SHA 84a4682, exit 0, 910 tests and zero failures/errors/skips. Independently parsed CSV counters are ServerSession 78/83, HandshakeHandler 24/24, ControlledSide 10/10, ControlSide 69/78 and ClientSession 77/83; all exceed 0.80. Both coverage target lists contain the correct moved names and retain the existing CLASS/BRANCH minimum.
- **Additional archived evidence:** independently summed `task-5-full-suites.json` gives 66 suites / 910 executions / 0 failures/errors/skips. Parsed `task-5-final-slot.xml` gives 63 executions: exactly 20 each for identity, held release and held worker, plus the three failure cases, all successful. This XML is worker evidence; the coordinator's separate x20 result is corroborated by its ledger/review record, not a newly parsed coordinator XML here. Deliberate hook/ticker/worker/Robot-fault warning origins remain consistent with baseline fault tests; no new unexplained warning was identified.

## Issues and deferred cleanup triage

**In-range findings: none. Critical: 0; Important: 0; Minor: 0.** No additional focused experiment is needed to resolve an in-scope functional doubt.

**PENDING_TICKER_REGISTRATION — Minor inherited cleanup/observability debt; nonblocking for this checkpoint.** `src/main/java/com/ltm/rd/server/ServerCore.java:151` discards the pending tickable's Registration, and `src/main/java/com/ltm/rd/common/Ticker.java:56` shuts down the executor without removing tickables. Its count at `:51` can therefore remain one after a normal core closes. Base/head Ticker and PendingConnections blobs are identical, and the base ServerCore has the same pending registration and shutdown calls: this is not introduced by C0–B0.

The B0 fixture adjustment is acceptable evidence for **session** cleanup: it disables only the pending sweep, retains real session ticker registration/removal, releases every hold, joins fixture threads, closes cores/peers, rebinds both ports and checks that no rd-* thread remains. It does **not** prove a default-config core's registration count reaches zero. The retained entry alone proves neither a running ticker thread nor an open socket; core shutdown still closes pending connections and stops the ticker executor.

**Follow-up is required before later merge certification of the spec's normal-configuration cleanup requirement.** Retain and close the pending Registration during core shutdown, or define equivalent Ticker.close removal semantics, and add a default-config regression checking zero registrations, stopped threads and reusable ports. Keep this as a separate owned follow-up; do not count the seam-disabled fixture as resolving it. No production fix is requested inside this read-only checkpoint review.

## Behaviors considered and declined as checkpoint defects

Every behavior set aside during this review is recorded here for the coordinator's ruling:

- Stale caller timestamps can still admit an image/command handover: preserved admission/CAS semantics outside C0's activation correction; the fresh in-lock decision prevents ACTIVE and the ordinary timeout closes the reservation.
- Heartbeat packet timestamps remain pre-lock: explicitly preserved by C0; changing liveness policy would be a separate behavior change.
- PENDING input reaches CommandReceiver and then closes: required to retain the v1 input-before-active message, not an authorization bypass.
- The v1 SCREEN_INFO offer result remains ignored: preserved extraction behavior at the initial empty-writer handover; stricter prepared/offer handling belongs to B1's changed handshake.
- Release failure or an uninterruptible worker can hold BUSY indefinitely: intentional safety behavior, preventing unsafe reuse; timeout-based forced slot release would violate the spec.
- A server-side close does not acquire a new input-release shutdown wait: the current v1 server side has no local input and its production closing source is already complete; future host/runtime shutdown ownership must be assessed with B1.
- The held decoder fixture starts a lifecycle-owned receiver without a complete active image handshake: it deliberately isolates the termination barrier, while still performing real JPEG read/disposal; existing integration/golden tests cover handshake/wire behavior.
- The default pending registration does not reach zero: explicitly retained and triaged above, rather than treated as solved or silently dropped.
- VF0 removes the scheduled 1000-within-10s demand: no supported throughput contract was lost, and the unchanged manual test retains bulk-publication coverage; no claim is made about the original platform slowdown.
- A4 public test seams, the two retained server tests and five low similarity ratios: authorized, narrowly scoped and body-preserving; no assertion weakening or native shell exposure was found.
- The temporary ControlView → ServerPhase dependency and renamed FrameObserver type: authorized transition boundaries, not a completed v2/package-independence claim.
- B0's bounded old slot waits: accommodate completion-callback scheduling after termination while preserving the actual slot/PIN assertions and golden literals.
- Existing injected-fault warnings: expected baseline behavior with identifiable test origins, not evidence of new product failures.
- Historical G0 status/model wording and checklist boxes: current plan/report status and explicit Codex override govern; the report correctly leaves final review pending at the pinned Head.
- Earlier v1/A0 behavior beyond a named integration risk, B1/B2, role/UI/CLI swap, rd-host-prepare, PIT, 500 race iterations and two-machine H2: outside this executed checkpoint; none is certified or silently waived.

## Assessment

**Spec compliance: PASS. Code quality: APPROVED.** The executed C0 + A1–B0 checkpoint is ready for coordinator acceptance, with no in-range blocking findings.

**Ready for full v2 merge: No.** B1/B2 and their separately approved UI/CLI/documentation work, final-SHA PIT and 500 race iterations, two-machine H2, and the normal-config cleanup follow-up remain. This verdict authorizes no merge or push.

This was a read-only review using the supplied diff in passes and archived execution evidence. No Maven, tests, coverage generation, formatter, agents, source/docs/index/HEAD changes or target mutations were performed; only this report was written. Outside-diff source inspection was limited to named risks: SessionLifecycle native-tx lifetime/deferred ordering and future-completion lock ownership; Ticker/ServerCore shutdown and matching base snippets for pending-registration ownership; SnapshotPump publication/count ordering and its unchanged manual test/wait budget for VF0. Historical test evidence is not a fresh reviewer execution, and no live GUI/Robot or two-machine behavior is claimed.
