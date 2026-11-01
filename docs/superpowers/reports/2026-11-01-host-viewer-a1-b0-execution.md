# Nghiệm thu C0 và A1–B0

Ngày 2026-11-01. Phạm vi: G0 → C0 → A1 → A2 → A3 → A4 → B0. Tất cả gate từng task đã đạt; review toàn đợt đang chờ kết luận. Code cuối: `84a4682a93fae5f41320e549e252bfd3a872d57e`, branch `feat/wave3-4-desktop-v1`. Chưa push/merge.

## Kết quả và bằng chứng độc lập

Coordinator chạy `mvnw.cmd -q clean verify` trên từng SHA sau bàn giao; reviewer chỉ đọc và không chạy Maven. Mỗi dòng dưới là kết quả coordinator, không chỉ là worker tự báo.

| Stage | Commit | Test đạt | Review |
| --- | --- | ---: | --- |
| G0, hợp đồng tài liệu | `3f3775e` | Docs-only; baseline 613 | Checklist coordinator |
| C0, clock kích hoạt trong khóa | `7d0e680` | 619 sau sửa baseline VF0 riêng | PASS / APPROVED |
| VF0, sửa test scheduled snapshot | `2eb1880` | 619 | PASS / APPROVED |
| A1, port và ControlSide | `d84c28f` | 636 | PASS / APPROVED |
| A2, ControlledSide và PING | `fe3c11f` | 648 | PASS / APPROVED |
| A3, PacketRules v1 | `93dc123` | 846 | PASS / APPROVED |
| A4, package viewer/host | `0b0eceb` | 846 | PASS / APPROVED |
| B0, barrier trả slot | `84a4682` | 910 | PASS / APPROVED |

Mỗi lượt trên đều có 0 failures/errors/skipped. Lượt C0 đầu bị chặn bởi test baseline SnapshotPump chưa đổi; không retry để lấy xanh. V0 chẩn đoán rồi VF0 sửa riêng giả định 1000 poll/10 giây không thuộc hợp đồng fixed-delay và assertion không nguyên tử. Test manual 1000 poll vẫn giữ. Nguyên nhân môi trường gây lượt chậm ban đầu chưa được xác định.

Coverage branch ở lượt coordinator cuối:

| Lớp | Nhánh phủ | Tỷ lệ |
| --- | ---: | ---: |
| ServerSession | 78/83 | 93,98% |
| HandshakeHandler | 24/24 | 100% |
| host.ControlledSide | 10/10 | 100% |
| viewer.ControlSide | 69/78 | 88,46% |
| ClientSession | 77/83 | 92,77% |

JaCoCo CLASS/BRANCH/COVEREDRATIO ≥0.80 và Enforcer đều đạt. B0 có ba ca latch, mỗi ca đúng 20 lượt: identity của inputReleased, giữ nhả input, giữ worker. Ba ca lỗi nguồn release cũng đạt; tổng SlotReleaseIntegrationTest là 63 lượt. Chín golden-wire test đạt, literal trace giữ nguyên.

## Provider và cách điều phối

Run Orca: `run_c4af214b4a8d`. Sau chỉ dẫn mới của người dùng, mọi worker/reviewer mới dùng **Codex `gpt-6.1-sol`**, receipt `launch.effective` đã kiểm. G0/C0 đã giao Claude trước chỉ dẫn; review C0 và các dispatch sau đó đều là Codex.

| Việc | Effort implementer / reviewer |
| --- | --- |
| A1, A2, B0 | xhigh / xhigh |
| A3 | high / high |
| A4 | high / medium |
| Review toàn đợt | — / xhigh |

Một editor và một chủ sở hữu Maven/target tại mỗi thời điểm. Review chỉ đọc và coordinator verify chạy song song sau commit. Không tạo worker con; không dùng collaboration agent thay Orca. Các terminal người dùng đã tiếp quản được giữ nguyên; các worker đã settle còn lại được release theo receipt.

## Những điểm đã chốt khi thực thi

- Không có public LifecyclePort.wrap; SideTx chỉ tạo trong port.atomically. tryActivate kiểm owner và lifetime trước khi gọi shell. Callback lồng chỉ chạy sau tx ngoài cùng.
- C0 là commit hành vi riêng trước refactor: maybeActivate đọc clock trong tx. A1–A4 giữ hành vi của baseline sau C0/VF0.
- SessionSide.bridgeClosing có overload Supplier để seam biến đổi stage nguồn, giữ nguyên target inputReleased tạo trong constructor.
- A2 giữ nguyên input/assertion của hai test PING khi chuyển lớp. Commit body có lỗi thuật ngữ nhỏ “19 test bodies”: thực tế là 16 method tạo 19 execution; report đã đính chính, không amend.
- A4 giữ FrameReceiverTest và FrameReceiverIntegrationTest ở server vì cần native shell package-private. Helper DelegatingImageReader sang viewer; chỉ mở visibility cần thiết, không mở native lifecycle.
- Cả 40 file move đều được Git nhận là rename. Năm file ngắn có similarity dưới 90%: DisplayPort 88%, RobotPortFactory 84%, SubmitResult 88%, ControlView 87%, InputOutcome 86%. Audit độc lập của reviewer xác nhận cả 62 file Java có thân code bằng nhau sau chuẩn hóa package/import, một tên kiểu đầy đủ và chín visibility đã chốt.
- GoldenWireTraceTest chưa có awaitTrue như plan giả định; B0 thêm helper chờ có hạn với WAIT_S cũ, đổi đúng ba assertion chờ slot. Hai test seam/handshake khác cũng chỉ đổi assertion slot tức thời sang helper có sẵn.

## Giới hạn và việc tiếp theo

**Vẫn là wire v1.** Chưa lật Host/Viewer trong AppRuntime/UI, chưa có bằng chứng v2. B1/B2 chờ Phần 3 UI/CLI và Phần 4 tài liệu/H2. `viewer.ControlView → server.ServerPhase` còn tạm đến B1. PIT, 500 vòng race và H2 hai máy vẫn là gate trước merge, chưa chạy trong đợt này.

**PENDING_TICKER_REGISTRATION, có từ baseline:** ServerCore đăng ký pending tickable nhưng không giữ handle gỡ; Ticker.close dừng executor nhưng để tickables trong list. Core mặc định có thể còn báo một registration sau shutdown. Fixture B0 tắt pending ticker, vẫn đăng ký/gỡ ticker của phiên và kiểm thread/port; nó chứng minh cleanup phiên, không chứng minh bộ đếm core mặc định về 0. Đây chưa phải bằng chứng ticker thread còn chạy hay socket chưa đóng. Follow-up nên sở hữu/gỡ pending Registration hoặc định nghĩa cleanup của Ticker.close, kèm regression test cấu hình mặc định.

Bằng chứng thô gồm ledger, launch/release receipts, báo cáo worker/reviewer, log verify và CSV JaCoCo thuộc workspace SDD của Run. Khi hoàn tất review cuối, coordinator lưu chúng vào `.superpowers/evidence/run_c4af214b4a8d/` trước khi dọn workspace tạm. Lịch sử commit và báo cáo này là bản ghi trong Git.

## Coordinator rulings (exhaustive ledger extract)

Ordered ledger entries; later user instructions supersede historical routing.

- Ruling: use configured Codex model gpt-6.1-sol explicitly to preserve the user's existing model while setting per-task effort from the accepted routing (high/xhigh/medium); do not inherit local max for every task. Local Codex CLI 0.166.0 and models_cache confirm gpt-6.1-sol supports these efforts. Do not change global Codex config. Verify actual launch.effective for every dispatch.
- Ruling: use the exact existing feature checkout, one editor only — user approved a roadmap explicitly using this workspace, and version-matched Orca placement prefers existing workspace unless a concrete conflict requires isolation — wrong ruling costs recoverable local commits, no shared-branch side effect.
- Ruling: ownership fix removes public LifecyclePort.wrap; SideTx created only inside port.atomically — closes the native-tx relabeling hole without changing SessionLifecycle — costs a few nested re-entrant callbacks.
- Ruling: C0 is a separate correctness commit before A1; maybeActivate(tx) reads current clock under session lock — avoids silently changing a race during a refactor — changes activation outcome at reserve boundary deliberately.
- Ruling: user accepted Sonnet task routing; use Sonnet high for G0/A3/A4, xhigh for C0/A1/A2/B0 and final reviewer, medium for A4 reviewer — user routing overrides generic most-expensive-review defaults — risk covered by independent gates.
- Ruling: Orca lifecycle authority takes precedence over generic SDD instructions to resume/reuse an implementer after settlement. Release settled terminals promptly; create a new fix Dispatch for later review findings, never emulate Orca via collaboration tools.
- Ruling: helper task-brief accepts numeric headings only. Use explicit artifact briefs for G0/C0; extract numbered A1–B0 tasks with the helper after G0 updates the original plan. No skill files are modified.
- Ruling: G0 is docs-only and must not run Maven; coordinator may run the fresh baseline verification while it edits docs. For production stages, target has exactly one owner at a time.
- Ruling: injected fault-test WARNING logs already in baseline are expected evidence, not new findings by themselves. New unexplained warnings are investigated; do not change production logging outside task scope solely to silence deliberate fault tests.
- Ruling: accept G0's bridgeClosing(Supplier<CompletableFuture<Void>>, target) overload, with SessionSide overload delegating — B0 can transform only the source while sharing one exception-safe bridge — no decorator class or duplicated bridge logic needed.
- Ruling: open a separate V0 diagnostic incident for unchanged SnapshotPump behavior, not a C0 code-review fix round. Codex diagnoses root cause with targeted evidence; no source changes or broad re-runs until a supported hypothesis exists. Systematic-debugging skill and fix-loop reference have been read.
- Ruling: approve a separate test-only VF0 correction before A1. Keep manual 1000-poll check; scheduled test checks two ordered answers at 20 ms period, independently awaits completed poll count, retains 10s timeout. This removes an unsupported throughput requirement and non-atomic assertion, not a product behavior requirement. It is an explicit baseline-test repair outside A1–A4 mechanical-only refactors, with fresh independent review.
- Ruling: original slowdown attribution remains unknown; do not claim proven Windows timer change or C0 regression. Source contract plus saved failure and timing evidence establish the test defect; do not collect more broad timing data before this bounded correction.
- Ruling: A2 has no additional repeat requirement; generic dispatch phrase required repeats means only counts explicitly stated by that task. Twenty repeats are B0 only, 500 race later. Replied msg_c40ea652320e after verifying its A2 IDs; A2 baseline ordering characterization already green. Cost avoided: unrequired repeated tests.
- A3 implemented at 93dc123f3ca388ff60c665721cd156fa109c14a3, exactly eight expected files, clean checkout, worker verify 846/0, 192 matrix combinations plus immutable/reader union tests. Validated completion delivery_17ce80cfea6a, released implementer, acked. Ruling: report status SUCCEEDED is semantically DONE (explicit completed implementation/no concerns), consistent with succeeded lifecycle; no metadata-only repair needed. Coordinator owns target for acceptance.
- Ruling: inspected FrameReceiverTest native-shell constructor/port/controlSide/lifecycle use; keep it and FrameReceiverIntegrationTest in server under the explicit shell-private-access exception. Move DelegatingImageReader to viewer per helper list, exposing TEST fixture ctor/counters as necessary; FrameReceiver class/ctor and FrameDecoder(Supplier) become public without body changes. Do not widen native-shell seams. Replied A4 question msg_95c8fcfddbe8. Cost: public decoder injection ctor, narrower than exposing session lifecycle; tests remain mechanically identical.
- Ruling: accept five small-file similarity exceptions to the A4 >=90% heuristic (DisplayPort 88%, RobotPortFactory 84%, SubmitResult 88%, ControlView 87%, InputOutcome 86%) when all 40 moves are still detected and normalized bodies are unchanged apart from approved visibility. Short package/import edits dominate tiny files; padding would harm the mechanical-only invariant. Require saved normalized-body audit and explicit path/similarity list in report/commit. Replied msg_f9cbab241728; heartbeat in same delivery validated.
- Ruling: GoldenWireTraceTest has no awaitTrue despite brief wording; inspected it and FrameReceiverIntegrationTest. Add the same local bounded BooleanSupplier/LockSupport helper using unchanged WAIT_S; replace only three immediate slot-empty assertions, preserve every golden literal/other assertion. Explicitly enumerate helper and method names in commit/report. No artificial race rerun needed. Replied B0 msg_3b90274665a0; both same-delivery heartbeats IDs validated.
- Ruling: final broad review uses execution baseline 30d160450105f1fd1f0be77e75c081bb4b1061f0 through final accepted implementation SHA, per roadmap checkpoint's fixed baseline. Earlier v1/A0 branch history is context only for concrete risks, not a new review target. Cost: preexisting out-of-scope defects are not certified by this review.
- Ruling: coordinator may update execution status/provider routing and create the final acceptance report in a separate docs-only commit after B0 gates, despite task-worker docs prohibition. This is concrete handover metadata, not a production/task fix; source/test/pom must remain identical to accepted B0. Cost if wrong: documentation wording needs a follow-up, without changing verified code. Final broad review includes that docs commit.
