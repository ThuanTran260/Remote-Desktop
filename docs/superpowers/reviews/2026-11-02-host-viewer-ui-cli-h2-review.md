# Lịch sử review thiết kế UI/CLI/H2 Host/Viewer v2

Kết quả mới nhất: vòng sửa 1 PASS / APPROVED, cả hai finding đã được đóng. Đây là đủ điều kiện trình thiết kế, chưa thay phê duyệt người dùng hoặc chứng nhận v2. Các tên artifact dưới đây thuộc workspace/evidence của Run run_2b84ac203dc6; bản review ban đầu được giữ để thấy rõ điểm bị chặn.

## Re-review vòng sửa 1 (mới nhất)

- **Finding 1 — ControlView phase migration belongs in B1: ADDRESSED.** `ui-cli-h2-design-r1.md:109` places the change from `server.ServerPhase` to `client.ClientPhase` in B1. `:174` explicitly moves the producer from the listener to `client.ClientSession.controlView()`, changes runtime/snapshot/UI consumers and constructor/import/phase/generation/gate tests together, and prohibits compatibility mappings or temporary abstractions. `:298` and `:305` agree: B2 only renames the type/package to `viewer.ViewerPhase` and corresponding references/tests, preserving B1 semantics. This resolves the contradiction against approved `docs/superpowers/specs/2026-11-01-host-viewer-design.md:221`; it requires no renewed policy decision. The appended report agrees at `ui-cli-h2-report-r1.md:79`.
- **Finding 2 — Explicit pre-ACTIVE physical network-cut coverage: ADDRESSED.** `ui-cli-h2-design-r1.md:257`–`:260` separates H2-11a (cut while entering the code before connecting), H2-11b (AUTH OK, still RESERVED/ATTACHING), and the retained ACTIVE input case. H2-11a explicitly has no session timer while IDLE, then tests connection while the network remains cut; applicable connect/auth/accepted-handshake deadlines retain their original anchors. H2-11b retains reserve 5 s from reservedAt, activation 7 s from AUTH OK, image connect 3 s, and post-handover heartbeat rules, allowing earlier I/O/EOF closure. These match HV `:87`–`:92`, without restarting deadlines at the cut or applying the ACTIVE 15 s gate to IDLE. `:270` requires both directions, actual endpoint phases and timing, cleanup of sockets/pending/threads/slot/input, successful reconnect after restoration, and the three Host cleanup futures before slot release. `:259` rejects an ACTIVE-before-cut run as proof of the post-AUTH case; `:270` and `:285` retain CHƯA CHẠY for missing phases/evidence. Cancel, shutdown, and harness evidence cannot substitute for physical two-machine cuts. `ui-cli-h2-report-r1.md:80` accurately describes these requirements without claiming execution.

## New Breakage in the Fix Diff

- **None.** All changed design ranges and the report append were checked. The amendments preserve the existing ownership/activation/cleanup rules and pending approval status; no new Critical, Important, or Minor defect was found in this fix scope.

## Out-of-Scope Observations

- **None newly raised.** The untouched original report's historical “no builder review” statements (`ui-cli-h2-report-r1.md:68`) are explicitly contextualized by the append at `:77`; they are nonblocking historical text, not an open fix-round finding. Untouched implementation and Task 1 were not re-reviewed.

## Checks and Scope

- **PASS — immutable endpoints verified.** All four r0/r1 snapshot byte sizes and SHA-256 values match `task-2-fix-r1-hashes.json`. The live design/report exactly match their r1 snapshots; the r1 report preserves the complete 14,056-byte r0 prefix.
- **PASS — amendment coverage checked.** Read the supplied fix diff once and inspected the changed ranges against the immutable endpoints: design r1 lines 3, 109, 173–174, 257–260, 269–270, 284–285, 298, 305; report r1 lines 74–86. Read findings 1/2, `task-2-fix-r1-report.md`, and the appended report section; checked the relevant approved HV deadline, B1 boundary, and H2 clauses. No invented FIX_BASE/HEAD commits or replacement git diff were used.
- **Check limitation:** the implementer's reported document assertions are consistency checks, not covering product-test runs. Their claims agree with the amendments and independently checked hashes/prefix; no test/build/runtime PASS is inferred. No tests, Maven, GUI, Robot, H2, helpers, model changes, or source/docs/index/HEAD/target edits were performed. Only this assigned review report was written.

## Verdict

- **Fix round 1: PASS / APPROVED — both findings ADDRESSED; no new breakage in the fix diff.** No finding remains open in this scoped round. This approves the draft corrections for coordinator integration/presentation only: the design remains **Đề xuất, chờ duyệt**, user approval remains pending, and v2 remains unimplemented with runtime/H2 validation unperformed. It does not authorize B1/B2 or certify v2 readiness.
- **Review provenance:** Task `task_de81bae272a8`, Dispatch `ctx_2ff268cc85e0`; 2026-11-02.

---

## Review ban đầu (lịch sử FAIL / NEEDS_FIXES)

# Review độc lập Task 2 — builder/spec/clean boundary

Ngày: 2026-11-02. Task `task_4074090f32c9`, Dispatch `ctx_42bc7bfad23a`, terminal `term_23ad466f-708d-4f70-b89c-e6d16576407a`.

**Verdict: FAIL / NEEDS_FIXES** — một mâu thuẫn material về ranh giới B1/B2 cần sửa trong draft trước khi tích hợp/trình duyệt. Đây là verdict về tính nhất quán của đề xuất, không phải thất bại của lần chạy sản phẩm, không phải từ chối những lựa chọn còn chờ người dùng, và không phải chứng nhận readiness v2.

## Phạm vi và phương pháp

Đã đọc builder-check-prompt, code-reviewer prompt, review-contract, toàn bộ `ui-cli-h2-design.md`, `ui-cli-h2-report.md`, Global Constraints và Task 2 của plan chuẩn bị; đối chiếu HV mục 1/1.8, 2.2–2.3, 3 và ranh giới B1/B2 ở 2.5. Các tham chiếu `ui-cli-h2-design.md`/`ui-cli-h2-report.md` bên dưới nằm cùng thư mục với báo cáo này; HV là `docs/superpowers/specs/2026-11-01-host-viewer-design.md`.

Chỉ đọc source cho ba nghi vấn cụ thể, toàn bộ bằng `git show 369440779658424e631b4d0a965f870dde5ea7b9:path` (S), không lấy source của checkout đang có công việc song song làm bằng chứng:

| Nghi vấn cần giải quyết | Phạm vi đọc hẹp và kết luận |
| --- | --- |
| Bảng B2 có đang trì hoãn một thay đổi kiểu dữ liệu cần cho B1? | `viewer/ControlView.java:3,12`, đoạn `ServerSession.controlView()` và các nơi `AppRuntime` đọc ControlView/phase. Có: view nhận phase thực từ lifecycle của vỏ listener v1; đây không chỉ là import trang trí. Không đọc lại toàn bộ session/runtime để review code. |
| Shutdown report có thể mặc định coi exit hiện tại là chứng cứ nhả input, hoặc cần EDT cho Ctrl+C? | `ui/GuiApp.java` ở đường tạo runtime/exit, `Launcher.java`, record `ShutdownReport`, các đoạn shutdown/report của `AppRuntime`, budget trong `AppConfig`; đối chiếu spec v1 mục 4.5. `GuiApp.java:95–96` hiện bỏ report rồi exit; draft đã yêu cầu xử lý report thật. Hook JVM là đường nền riêng đã được spec v1 quy định, không phụ thuộc EDT. Đây là việc B1 phải thực hiện/giữ, không chứng nhận baseline đã đạt Ctrl+C. |
| H2 có tự đổi mục tiêu FPS/latency thành gate hoặc bỏ ca cắt mạng cũ? | Chỉ spec v1 mục 5.2, 4.5 và mẫu `2026-10-20-wave4-acceptance-results.md`. 10–12 FPS/p95 là mục tiêu đo, 30 phút/50 vòng là gate thật; draft phân biệt đúng. Ca cắt mạng trước ACTIVE cần hiện rõ hơn trong ma trận, xem finding Minor. |

Source paths rút gọn trong bảng thuộc `src/main/java/com/ltm/rd/`; spec v1 là `docs/superpowers/specs/2026-10-19-remote-desktop-design.md` tại S. Không chạy Maven/test/GUI/Robot/hai JVM/hai máy, không đọc/sửa target, không tạo helper, không đổi model, không sửa source/docs tracked/index/HEAD. HEAD quan sát là `e58afc77a41870d110cfb456a536f6253644cd94`; không suy kết quả Task 1 từ SHA này. Chỉ ghi báo cáo được giao.

## Điểm đạt

- CLI migration đủ cụ thể: từ chối cả `--host IP` không role và các cờ v1 trộn cờ v2; không ánh xạ IP máy điều khiển cũ thành IP Host mới; duplicate/help/exit/role/port/preset có kết quả xác định, không DNS/I/O ở parser, không nhận hay echo mã nhạy cảm (`ui-cli-h2-design.md:59`, `:63`, `:72`, `:87`).
- UI chuyển ownership theo năng lực thật: Host có SharingBar/InputExecutor/adapter; Viewer có ControlFrame/InputCapture; UI chỉ Controller/Snapshot, pump nền và mọi thao tác Swing trên EDT (`:101`, `:175`). Không tạo session engine thứ hai hay API dự phòng.
- Hai thứ tự prepare/ATTACH và hai thứ tự SESSION_ACTIVE/handover ảnh đều giữ gate, deadline và activate tối đa một lần; cancel/late callback không hồi sinh phiên; input release lỗi giữ BUSY, không dùng timer cấp slot (`:120`, `:143`, `:158`, `:182`, `:188`). Phù hợp HV 1/1.8/2.3.
- H2 nêu hai máy Windows thật và hai hướng, jar digest/SHA/môi trường, quan sát input thật, 30 phút/50 vòng, cleanup/thread/port, cùng artifact bắt buộc. Fault cần latch/injection không được giả là đã tái hiện bằng GUI; hai JVM/headless không thay Robot/DPI/focus/latency (`:220`, `:234`, `:240`, `:281`, `:292`).
- Trạng thái chưa chạy trung thực ở cả design và report; S chỉ là nguồn v1, chưa có SHA/jar/measurement v2. Không tuyên bố Task 1 đã được review, không soạn plan B1 hay coi đề xuất đã được người dùng duyệt (`ui-cli-h2-report.md:66`).

## Findings xếp theo mức quan trọng

### 1. Important — ma trận kiểm thử đặt việc bỏ phụ thuộc ControlView vào sai bước

**Vị trí:** `ui-cli-h2-design.md:289`, cột B2; liên quan cam kết B2 cơ học tại `:296`. **Đối chiếu:** HV `:221` quy định rõ phụ thuộc `viewer.ControlView → server.ServerPhase` chỉ tạm đến B1 và B1 đổi kiểu này.

**Trigger:** builder làm theo cột B2 của dòng Runtime/session, giữ ControlView phụ thuộc ServerPhase qua commit B1 khi ControlSide đã chuyển sang vỏ connector. Tại S, `ControlView.java:12` nhận `ServerPhase`, còn `ServerSession.controlView()` truyền trực tiếp `tx.phase()`; AppRuntime cũng dùng phase đó để chiếu trạng thái. Vì vậy vỏ Viewer mới không thể tiếp tục biểu diễn phase có thẩm quyền bằng cùng đường dữ liệu cũ mà không thêm phép quy đổi/vỏ tương thích.

**Impact:** builder phải chọn giữa làm trái bảng giao phẩm B2, thêm mapping tạm không cần thiết ở B1, hoặc dồn thay đổi hợp đồng phase/producer/consumer vào commit được gọi là rename cơ học. Điều này vi phạm ranh giới đã duyệt và làm sai phạm vi kiểm chứng state/snapshot/UI. Đây là mâu thuẫn trong proposal; source tại S chỉ xác nhận khoản nợ chuyển tiếp đã biết, không phải phát hiện regression của Task 1.

**Correction:** chuyển rõ việc đổi hợp đồng phase của ControlView cùng producer/consumer và test sang cột B1; ở B2 chỉ còn rename package/ClientPhase→ViewerPhase tương ứng nếu áp dụng. Dẫn HV 2.2 và không thêm abstraction chỉ để giữ kiểu phase của vỏ cũ. Không cần xin người dùng quyết định lại, vì thời điểm B1 đã được duyệt.

### 2. Minor — ledger H2 chưa truy vết tường minh các lần cắt mạng trước ACTIVE

**Vị trí:** `ui-cli-h2-design.md:254` (H2-11), `:262` (H2-19). **Đối chiếu:** spec v1 tại S `docs/superpowers/specs/2026-10-19-remote-desktop-design.md:691`; HV `:479` yêu cầu giữ ca cũ ngoài ca v2 mới.

**Trigger:** người chạy chỉ làm các thao tác được ghi trong ma trận: H2-11 cắt mạng lúc giữ Ctrl/kéo chuột, H2-19 đóng form/Ctrl+C ở từng phase. Chưa có dòng/subcase yêu cầu cắt mạng thật lúc nhập mã và ngay sau xác thực; shutdown chủ động hoặc Hủy không tương đương việc đó.

**Impact:** dễ bỏ sót hai subcase cũ khi tổng hợp ledger theo ID H2-01..19. Các deadline/cleanup tương ứng đã có trong spec và draft, nên không còn quyết định chính sách hay câu hỏi kiến trúc mới; đây là thiếu truy vết nghiệm thu, không tự nó chặn builder plan.

**Correction:** thêm subcase H2-11 cho cắt mạng trước ACTIVE, ghi phase bắt được, kết quả theo deadline tương ứng, cleanup và reconnect trên cả hai hướng; phase không bắt được giữ CHƯA CHẠY hoặc ghi bằng chứng harness bổ sung, không thay lượt mạng thật bằng nút Hủy.

**Critical:** không có finding trong phạm vi đã đọc. Không khẳng định không còn bug.

## Câu hỏi builder còn cần làm rõ

1. **Bảng giao phẩm đang thật sự yêu cầu bỏ `ControlView → ServerPhase` ở B1 hay B2?** Hai chỉ dẫn hiện mâu thuẫn; trả lời sai đổi ranh giới module, snapshot/UI và phạm vi kiểm thử của cả hai commit. Câu trả lời bắt buộc đã có trong HV 2.2: **B1**; cần sửa draft cho nhất quán, không hỏi lại tác giả/người dùng về một lựa chọn đã chốt.

Ngoài mâu thuẫn trên, **nothing important left** về hợp đồng triển khai sau khi phương án được duyệt. Kiểu cleanup projection cụ thể, cấu trúc helper trình bày thuần, cách bố trí widget và nhãn subcase ledger là quyết định builder có thể tự làm trong các invariant đã nêu.

## Lựa chọn phê duyệt có chủ ý, không phải defect

- Breaking CLI `--role host|viewer`/`--address` so với cờ ngắn; việc bỏ alias v1 đã có một phương án đề xuất hoàn chỉnh để duyệt, không phải parser contract bị bỏ trống.
- Hai form, Host tự start listener, tên nút/cửa sổ và cách đóng ControlFrame so với form chính đều được đề xuất rõ. Đồng ý bằng đưa mã đã được duyệt, không hỏi lại.
- Hai máy/người chạy/lịch và phân bố trọn 30 phút/50 vòng mỗi hướng là lựa chọn nghiệm thu cần người dùng xác nhận; môi trường chưa có không phải bằng chứng design sai hoặc lý do đánh dấu PASS.
- Nhãn “Đề xuất, chờ duyệt” là đúng gate. APPROVED của reviewer nếu có cũng chỉ là đủ điều kiện trình duyệt, không thay phê duyệt người dùng và không cho phép B1/B2 tự động.

## Các hành vi đã xét và declined, kèm lý do

- **Review lại pending ticker/Task 1, volatile publication, red/green XML hoặc releaseResources:** ngoài seat Task 2; không đọc lại code đó, không lấy test count hoặc HEAD làm acceptance.
- **Đòi code v2, chạy verify/PIT/500 race/Robot hoặc hai máy để cho verdict draft:** ngoài task và bị cấm trong dispatch; yêu cầu/evidence tương lai đủ để đánh giá thiết kế, mọi lượt chưa chạy vẫn chưa chạy.
- **Đánh lỗi vì đề xuất CLI breaking hoặc không giữ alias cũ:** lựa chọn có chủ ý, có trade-off và migration không đổi nhầm máy; chờ quyết định người dùng.
- **Đòi User approval ngay để làm tiếp review:** không cần; review được giao chính là bước tạo giao phẩm cụ thể trước trình duyệt.
- **Thêm Host consent dialog, auto reconnect, ghép lại kênh ảnh, listener toggle, FRAME_ACK/freshness, dependency/seam mới hoặc polish:** ngoài phạm vi đã chốt, không là blocker.
- **Bắt GUI chờ future Robot của Host trên Viewer, ép trả slot sau timeout hoặc retry input FAULTED:** trái ownership/safety đã duyệt; draft đã cấm rõ nên không mở finding.
- **Yêu cầu mọi mất ảnh đóng ở 3 giây hoặc DISCONNECT luôn tới peer:** sai hợp đồng; draft phân biệt stall, frame timeout, EOF/write watchdog và best-effort reason đúng.
- **Áp ngưỡng FPS/p95 bắt buộc mới hoặc tự đặt số heap/FPS dao động cho H2:** spec cũ coi FPS/latency là mục tiêu đo; duration/count/input/cleanup/artefact có yêu cầu kiểm được. Người chạy vẫn phải lưu mẫu/đồ thị, không dùng “ổn định” để bỏ bằng chứng hoặc miễn lỗi resource.
- **Coi thiếu JVM shutdown hook/việc GUI bỏ ShutdownReport tại S là regression mới:** đây là khoảng cách baseline cần B1 xử lý theo hợp đồng kế thừa; draft yêu cầu shutdown/report thật và H2 Ctrl+C, không tuyên bố đã có. Builder có thể lập hook dùng cùng future, bounded wait ngoài EDT từ spec v1 4.5; không cần hỏi tác giả. Kết thúc GUI bình thường và JVM hook không có cùng giả định EDT.
- **Bắt mọi fault Robot/native treo được tái hiện trên hai máy:** không an toàn/không tất định và không cần thiết; latch/fault injection kiểm invariant, lỗi native thật ghi riêng, không biến test giả thành PASS H2.

## Kết luận và bước còn lại

**FAIL / NEEDS_FIXES** cho draft hiện tại vì finding Important #1; sửa scoped một dòng/bảng và đồng bộ report nếu cần, rồi re-review đúng ranh giới đó trước tích hợp. Finding #2 là cải thiện truy vết H2 nên làm cùng wave; không cần mở thêm quyết định người dùng. Phần còn lại đáp ứng Task 2/clean boundaries và có thể trình các lựa chọn phê duyệt sau khi đóng finding material. Không có kết quả chạy sản phẩm mới, không có chứng nhận v2 merge readiness.

Kiểm tra phạm vi trước ghi report: tracked/index diff rỗng, HEAD như trên; hai file untracked `docs/superpowers/reports/2026-11-02-host-viewer-v2-preparation.md` và `docs/superpowers/reviews/2026-11-02-pending-ticker-review.md` đã có từ công việc song song, không đọc/sửa/nhận sở hữu. Chỉ báo cáo này thuộc phần ghi của reviewer.
