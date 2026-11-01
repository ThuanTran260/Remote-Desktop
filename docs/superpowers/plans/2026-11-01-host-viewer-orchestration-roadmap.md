# Host/Viewer C0 + A1–B0 — Review và lộ trình Orca

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans for implementation, and the version-matched `orchestration` skill for supervised workers. Do not substitute a non-Orca subagent tool for an Orca Dispatch. Steps use checkbox syntax for tracking.

**Goal:** Đóng hai điểm review trước A1, rồi triển khai năm task A1–B0 bằng worker độc lập, kiểm thử và review trước task kế tiếp. Chưa triển khai B1–B2.

**Architecture:** Một coordinator, một checkout hiện có, tối đa một worker sửa file tại một thời điểm. Reviewer chỉ đọc SHA đã commit; coordinator giữ quyền chạy Maven trong lượt nghiệm thu. Các task chia sẻ session/port/test nên không fan-out code.

**Tech Stack:** Java 21 target, Maven wrapper, JUnit 5, JaCoCo 0.8.16; Orca local runtime 1.4.244 và Claude Code 2.1.316 đã được đọc trực tiếp ngày 2026-11-01.

**Spec:** `docs/superpowers/specs/2026-11-01-host-viewer-design.md`. Plan triển khai: `docs/superpowers/plans/2026-11-01-host-viewer-a1-b0.md` (bản gốc ở commit `30d1604`, được G0 đính chính cùng commit tài liệu với roadmap này). **Trạng thái duyệt:** lộ trình, định tuyến model/effort và việc thực thi đã được người dùng duyệt theo yêu cầu thực thi mới nhất (2026-11-01). Đây chỉ là ủy quyền thực thi tài liệu/lộ trình; không có nghĩa code hay test v2 đã tồn tại.

## Global Constraints

- Không đổi wire format hay `Protocol.VERSION=1`. A1–A4 là refactor; B0 chỉ đổi điều kiện trả slot như spec. Sửa thời điểm quyết định kích hoạt nằm trong C0 riêng, trước refactor, không giấu trong A1.
- Không bắt đầu A1 trước khi đóng G0/C0. Không bắt đầu task sau khi gate task trước chưa đạt.
- Test cũ chỉ sửa cơ học theo spec; hai test PING được chuyển nguyên input/assertion sang `HeartbeatTest`, phải có bảng truy vết tên cũ → mới. B0 chỉ sửa thêm các giả định trả slot tức thời và liệt kê trong commit body.
- Golden trace literal không đổi; thay import ở A4, thay chờ slot ở B0 theo đúng plan gốc.
- Không Robot/GUI spike, không push/merge/rebase/amend, không dọn Java process hàng loạt. PIT/500 vòng race/H2 là gate trước merge, chưa chứng nhận ở A1–B0.
- Một bên giữ quyền ghi `target/`: lúc worker thực hiện, coordinator/reviewer không chạy Maven; lúc coordinator nghiệm thu, worker đã settle, reviewer không chạy Maven.
- Model/effort trong bảng là định tuyến người dùng đã chấp nhận (không phải bằng chứng đã chạy). Chỉ xác nhận đã dùng model/effort khi receipt `launch.effective` chứng minh; không suy từ cờ requested. Không tự đổi model khi launch thất bại.

## Review Focus và kết luận tại 30d1604

1. **[Trung bình] `wrap(tx)` chưa chứng minh tx thuộc port** (mô tả lịch sử của API đã bị G0 loại bỏ). Plan gốc tại `30d1604`, dòng 108/141, cho phép tạo view từ native tx rồi chỉ kiểm view thuộc port khi `tryActivate`. `portA.wrap(txB)` có thể gắn nhãn A cho tx của B; test hiện tại chỉ kiểm `portB.tryActivate(stxA)`, chưa chặn đường này. `SessionLifecycle.Tx.check()` kiểm lifetime/thread giữ khóa, không kiểm port (code dòng 148–152).
2. **[Trung bình] Thời điểm đọc clock thay đổi trong một task hứa giữ nguyên hành vi.** Plan gốc dòng 119 đổi đường SCREEN_INFO sang clock đọc trong tx, trong khi v1 `ServerSession.dispatch` đọc `now` trước lấy khóa (dòng 244) và các đường attach còn truyền `now` cũ. Fake clock có thể được tiến trong lúc thread đợi khóa; câu “giá trị now không đổi” không phải bất biến. Cần thống nhất chính sách và ghim biên deadline bằng test.
3. `LifecyclePort<P>` dùng chung, thêm `SessionPort.isClosed()` và chuyển `FrameObserver` vào `ControlSide` đều phù hợp. `ControlView` giữ `ServerPhase` tạm được, nhưng vẫn còn phụ thuộc `viewer → server` cho tới B1; không báo đã tách sạch package ở A4.
4. Chuyển hai test PING giữ nguyên input/assertion là thay đổi vị trí test hợp lệ vì nhánh PING chuyển lên vỏ. Không dùng việc chuyển này để nới assertion.
5. Giữ B1/B2 ngoài plan hiện tại là đúng. UI/CLI và tài liệu/H2 phải được thiết kế trước B1.

## Bằng chứng baseline đã kiểm

- HEAD: `30d160450105f1fd1f0be77e75c081bb4b1061f0`; trước khi viết roadmap, working tree sạch.
- Reviewer chạy mới `./mvnw.cmd -B -ntp verify`: exit 0, **613 test, 0 failures/errors/skipped**. Log cục bộ: `target/review-a1-b0/verify.log`.
- Coverage lượt này: ServerSession **155/172 = 90,12%**; ClientSession **81/86 = 94,19%**; HandshakeHandler **24/24 = 100%**. Đây là một lượt xác nhận mới, không phải tự chạy lại ba lượt nghiệm thu A0.
- Orca `status --json`: runtime local ready/reachable; `worktree current --json` trỏ đúng checkout hiện tại và HEAD trên. Claude `--help` liệt kê `low/medium/high/xhigh/max` và alias `sonnet`; chưa có launch receipt vì chưa giao worker nào trong lượt review này.

## Các đính chính chốt ở G0 (người dùng đã duyệt thực thi)

**Quyết định cho ownership:** bỏ `public LifecyclePort.wrap(Tx)` khỏi interface sản xuất. Chỉ `LifecyclePort.atomically` được tạo `SideTx` nội bộ từ lifecycle của chính port. Vỏ đang giữ native tx gọi `port.atomically(stx -> …)` khi cần hook; lock re-entrant và `after` vẫn chỉ chạy sau tx ngoài cùng, đã có test ghim trong plan gốc. Không cần sửa `SessionLifecycle` để thêm một cơ chế nhận diện owner nữa.

`tryActivate(stx)` phải kiểm view nội bộ thuộc đúng port, rồi gọi một method native tx để kiểm lifetime trước khi giao cho Shell, kể cả Shell giả không đụng tx. Bổ sung test `tryActivateWithExpiredSideTxThrows`; giữ `tryActivateRejectsSideTxOfAnotherPort`, và kiểm sau callback cho các method SideTx có tác dụng phụ. Test/vỏ/Heartbeat chuyển lời gọi `port.wrap(tx)` thành callback `port.atomically`; không đổi input/assertion.

**Quyết định cho clock:** giữ hướng clock đọc trong tx của spec, nhưng tách thay đổi hành vi thành commit C0 trước A1. C0 thống nhất `ServerSession.maybeActivate(tx)` tự đọc clock trong tx; các đường SCREEN_INFO, command attach và image attach đều gọi hàm đó. Không đổi timestamp heartbeat hay timeout đóng phiên khác. Sau C0, A1–A4 tiếp tục là refactor thuần trên baseline mới; không thêm field timestamp tạm chỉ để giữ một race cũ.

G0 cập nhật plan/spec cho đúng ngoại lệ C0 và chữ ký mới, không để worker phải chọn giữa hai văn bản mâu thuẫn. Các quyết định này là hợp đồng tài liệu đã duyệt để thực thi, không phải code đã sửa hoặc đã được nghiệm thu. Ngoài ra G0 chốt: chuẩn bị adapter v1 (trước connect) giữ nguyên đến hết B0 và `rd-host-prepare` chỉ thuộc B1; `inputReleased` là target ổn định tạo từ constructor và `inputReleaseSeam` (B0) biến đổi stage nguồn trước bridge; `viewer.ControlView → server.ServerPhase` còn tạm đến B1.

## Định tuyến model và effort ban đầu (lịch sử)

Bảng dưới là định tuyến Sonnet ban đầu, trước chỉ dẫn mới nhất của người dùng. **Chỉ dẫn hiện hành: mọi worker/reviewer mới dùng Codex `gpt-6.1-sol`, không giao thêm Claude.** A1/A2/B0: xhigh/xhigh; A3: high/high; A4: high/medium; review toàn đợt: xhigh. G0/C0 đã được giao trước chỉ dẫn; C0 review và mọi dispatch sau đó đều là Codex. Receipt `launch.effective` đã được kiểm cho từng dispatch mới; không đổi global config.

| Task | Worker thực hiện | Reviewer độc lập | Lý do |
| --- | --- | --- | --- |
| G0: đính chính tài liệu | Sonnet high | Coordinator kiểm checklist | Chỉ khóa hợp đồng và phạm vi, không sửa source |
| C0: thời gian quyết định kích hoạt | Sonnet xhigh | Sonnet xhigh | Test ép thứ tự khóa/deadline, thay đổi hành vi có chủ đích |
| A1: port + ControlSide | Sonnet xhigh | Sonnet xhigh | Interface nền, lifetime tx, gate/epoch và deferred callback |
| A2: ControlledSide | Sonnet xhigh | Sonnet xhigh | Input begin/end, future release, thứ tự activated/sender |
| A3: PacketRules | Sonnet high | Sonnet high | Bảng literal đầy đủ, lỗi wire/phase đã có test ghim |
| A4: chuyển package | Sonnet high | Sonnet medium | Thực hiện qua nhiều file; review chỉ cơ học, có compiler/gate/diff assertion |
| B0: trả slot | Sonnet xhigh | Sonnet xhigh | Ba future, BUSY trong lúc dọn, lỗi release và phiên cũ |
| Review toàn A1–B0 | — | Sonnet xhigh | Đối chiếu toàn bộ thay đổi, tìm lỗi giữa các task |

A3 hạ từ xhigh xuống high; A4 giữ high ở implementer nhưng giảm effort reviewer. Không dùng max cho rename hay bảng gói. Nếu task lộ thay đổi state machine ngoài phạm vi, dừng gate và chia fix cụ thể trước khi tăng effort.

## Thứ tự và quyền sở hữu

`G0 → C0 → A1 → A2 → A3 → A4 → B0 → review toàn nhánh → checkpoint trước B1`.

Đây là các stage do coordinator điều khiển, không phải một cây subagent lồng nhau. Worker không tự tạo worker con. Không tạo các nhánh DAG song song giả khi hai task cùng sửa session hoặc dùng chung target.

Sau mỗi commit, **review chỉ đọc SHA và coordinator verify có thể chạy song song**. Hai hoạt động đều phải đạt trước stage kế tiếp. Nếu reviewer cần chạy thêm test, yêu cầu coordinator chạy tuần tự, không mở Maven thứ hai.

### Task G0: sửa hợp đồng trong tài liệu

**Target/Ownership:** đúng ba tài liệu: spec, plan A1–B0 và chính roadmap này (file untracked được đưa vào commit tài liệu); coordinator phát brief riêng, không cho sửa source/pom/test và không chạy Maven.

**Change:** áp dụng hai quyết định ở trên; bỏ chữ ký public wrap và các lời gọi phụ thuộc; thêm C0 với baseline rõ ràng; làm rõ phụ thuộc ServerPhase tạm; cập nhật effort theo bảng (người dùng đã chọn lộ trình này). Co-author phải phản ánh agent thực sự sử dụng, không ép một số phiên bản model chưa được receipt chứng minh.

- [x] Đối chiếu từng chữ ký được consume/produce giữa C0, A1 và A2–B0 (kết quả trong `G0-report.md`).
- [ ] Đọc diff tài liệu: không đổi wire, gate, close reason hay phạm vi B1 một cách ngầm định.
- [ ] Commit tài liệu riêng và báo SHA; coordinator chỉ cho qua nếu không còn mâu thuẫn spec/plan.

### Task C0: ghim thời gian kích hoạt trước refactor

**Files:** `src/main/java/com/ltm/rd/server/ServerSession.java` (private `maybeActivate` và các callsite); `src/test/java/com/ltm/rd/server/ServerSessionProtocolEdgesTest.java`.

**Produces:** `private void maybeActivate(SessionLifecycle.Tx<ServerPhase> tx)`; tự đọc `env.clock()` khi giữ tx, dùng thời điểm đó cho reserve check và `onActive`. Không thay heartbeat, AUTH/ATTACH token/CAS hay close reason. A1 Shell gọi chữ ký này.

- [ ] Thêm `screenInfoWaitingForSessionLockCannotActivateAfterReserveDeadline`: dùng seam/fake clock/latch, ép dispatch đã đọc timestamp cũ nhưng đang đợi khóa; tiến clock qua reserve, rồi thả khóa. Không được ACTIVE hay xếp SESSION_ACTIVE; tick sau đó đóng TIMEOUT như v1.
- [ ] Thêm/giữ ca còn hạn thì ACTIVE, đúng mốc deadline thì không kích hoạt; các đường gọi cùng dùng chính sách thời gian trên.
- [ ] Chạy test mới trên baseline, chứng minh ca race đỏ; sửa tối thiểu để xanh.
- [ ] Chạy `./mvnw.cmd -q verify`; golden trace literal không đổi, không sửa assertion cũ để hợp thức hóa hành vi mới.
- [ ] Commit fix riêng, lưu SHA làm baseline A1. Coordinator và reviewer kiểm clock chỉ đọc trong tx, không lấy timestamp trước thao tác có thể chặn.

### Tasks A1–B0: thực hiện theo plan gốc đã đính chính

| Task | Target/ownership | Sản phẩm và acceptance bắt buộc |
| --- | --- | --- |
| A1 | common port/SideTx/SessionSide; ServerSession/ControlSide/FrameReceiver; test và coverage targets liên quan; baseline = SHA của C0 | Không public wrap (`SideTx` chỉ tạo trong `atomically`); lifetime/foreign-port/`tryActivateWithExpiredSideTxThrows`/nested-after có test; giữ snapshot controlView nguyên tử; verify và trace đạt |
| A2 | ClientSession/ControlledSide/ScreenSender/CommandReceiver; Heartbeat/bridgeClosing; test/targets liên quan | Giữ future final (`inputReleased` target ổn định từ constructor), giữ chuẩn bị v1 trước connect, activated trước sender, failure không báo activated thành công; input/close reason cũ không đổi; hai test PING truy vết đủ |
| A3 | PacketRules và callsite/test liệt kê ở Task 3 plan gốc | Bảng mọi PacketType × Shell × Lane × Stage; tách lỗi tầng đọc/phase; thông điệp v1 giữ nguyên; verify/trace đạt |
| A4 | Các lớp/test chuyển package theo bảng spec và Task 4 plan gốc | Chỉ rename/import/visibility/tên liên quan; coverage targets đổi đúng; `ControlView → ServerPhase` còn tạm đến B1 (không báo đã tách sạch phụ thuộc ngược), ghi khoản nợ vào commit body; verify đạt |
| B0 | ServerSession/ServerCore/SessionManager; seam và SlotReleaseIntegrationTest | `allOf(socketsClosed, terminated, inputReleased)` chỉ trả slot khi thành công và đúng current; `inputReleaseSeam` biến đổi stage nguồn trước bridge, không thay/hoàn tất sớm target ổn định; BUSY khi worker/release còn chạy; lỗi release giữ slot; test lặp 20 lần và verify đạt |

Mỗi brief ghi task number, SHA baseline đã nghiệm thu, đường dẫn spec/plan/roadmap, danh sách file được sửa, acceptance, file bị cấm và quyền commit. Không giao “làm các bước còn lại”.

## Vòng coordinator dùng Orca thật

Executable đã resolve cho phiên khảo sát: `C:/Users/Admin/AppData/Local/Programs/orca/resources/bin/orca.exe`. Reuse đúng executable này; không dùng bare `orca` trỏ sang bản khác giữa Run. Skill nguồn: `C:/Users/Admin/.agents/skills/orchestration/SKILL.md`; phần vận hành phải đọc lại qua `skills get orchestration` đúng binary khi bắt đầu lượt thực hiện.

- [ ] Preflight: status ready, working tree đúng SHA/tình trạng đã bàn giao, không có worker khác đang sửa checkout hoặc Maven khác đang chạy. Dùng workspace hiện có đã được `worktree current` xác minh; không tạo worktree mới cho từng task khi không có xung đột cụ thể.
- [ ] Tạo một Run cho phạm vi đã duyệt, hoặc tiếp tục đúng Run do người dùng bàn giao; không takeover Run của agent khác. Lưu `run_id`, `task_id`, `dispatch_id`, SHA và quyền ghi target vào sổ điều phối.
- [x] Tạo Task/Dispatch cho stage sẵn sàng bằng `orchestration worker-start --task <id> --worktree <exact-selector> --agent codex --model gpt-6.1-sol --effort <level> --json`. Exact selector đã quan sát: `identity:wt2:local:419fd186-c5a0-42ac-b8d6-0e1be9f95658`; đã kiểm đúng workspace trong receipt.
- [ ] Kiểm `launch.requested`/`launch.effective`; worker nhận self-contained brief và live preamble. Không khẳng định effort chỉ từ CLI hỗ trợ cờ.
- [ ] Worker tuân thủ heartbeat/check/ask/worker_done trong live preamble. Coordinator trả lời question bằng reply của Orca; khi chỉ timeout thì chưa được coi worker đã chết hay retry.
- [ ] Chấp nhận `worker_done` chỉ sau khi đối chiếu active Dispatch, outcome, SHA, files và log. Task settled không có nghĩa stage đã nghiệm thu; stage còn chờ review + verify.
- [ ] Worker đã settle và không có follow-up ngay thì `worker-release --dispatch <id> --json`. Không giữ worker sống chỉ để đọc output. Nếu cần fix sau review, giao Fix Task mới với findings và SHA, giữ quyền sửa ở implementer.
- [ ] Khởi chạy reviewer độc lập chỉ đọc SHA; đồng thời coordinator chạy một `clean verify`. Reviewer không sửa file hoặc chạy Maven trên cùng target; mọi đề nghị fix được định tuyến lại worker.
- [ ] Xử lý mọi message trước ack delivery; không đánh dấu task completed bằng tay sau worker_done. Lưu kết quả test/coverage trước lượt clean tiếp theo, ngoài target nếu cần giữ lâu.
- [ ] Finding Critical/High chặn stage; Medium thuộc ownership, lifetime, release, wire hay assertion cũng chặn. Medium khác phải có quyết định xử lý cụ thể; không tự bỏ qua chỉ vì dưới High.
- [ ] Trước kết thúc Run, mọi Dispatch phải settled và không còn terminal reclaimable chưa release/retained đúng yêu cầu. Không dùng terminal close thay cho worker-release. Lỗi launch/release phải đọc đúng recovery receipt/reference, không khởi chạy trùng.

**Song song được phép:** reviewer đọc commit đã cố định và coordinator nghiệm thu, với quyền ghi target chỉ thuộc coordinator. **Không song song:** hai editor, hai Maven, reviewer đọc working tree đang bị sửa, hoặc worker con triển khai task kế tiếp trước gate.

## Checkpoint sau B0 và phần việc tiếp theo

- [ ] Review toàn diff từ baseline đã chốt; đọc cả source lẫn các thay đổi test/trace/coverage.
- [ ] Đủ bằng chứng năm lớp mục tiêu ≥ 0.80; các test slot có latch 20 lượt đạt; không còn rò thread/port/ticker trong cleanup.
- [ ] Báo rõ vẫn là v1, ControlView còn dùng ServerPhase tạm thời, chưa phải v2 đã chạy.
- [ ] Trình bày/chốt Phần 3 UI/CLI và Phần 4 tài liệu/H2, rồi viết plan riêng B1/B2. Effort đề xuất theo provider hiện hành: B1 Codex xhigh + reviewer xhigh; B2 Codex high + reviewer medium. Giai đoạn này chưa bắt đầu.
- [ ] Sau B2 mới chạy PIT trên SHA code/test cuối và 500 vòng race; H2 chạy trên hai máy theo spec. Xử lý finding trước merge. Không lấy kết quả A0 hoặc A1–B0 thay bằng chứng cho v2.

## Tình trạng hiện tại

**G0, C0 và A1–B0 đã hoàn tất**, mỗi task có review và verify. SHA code cuối `84a4682a93fae5f41320e549e252bfd3a872d57e`: coordinator `clean verify` đạt **910 test, 0 failures/errors/skipped**, cả năm gate ≥0.80; ba ca latch B0 đều đạt 20 lượt. Review toàn đợt đang chờ kết luận. [Báo cáo nghiệm thu, ngoại lệ và bằng chứng](../reports/2026-11-01-host-viewer-a1-b0-execution.md).

**Vẫn là wire v1.** B1/B2, Phần 3–4, PIT/500 vòng race/H2 chưa được chứng nhận. Phụ thuộc `viewer.ControlView → server.ServerPhase` còn tạm đến B1. Không push/merge/rebase/amend.

Cleanup B0 chứng minh session registrations về 0 trong fixture tắt pending ticker, đồng thời kiểm thread và port. Core mặc định có giới hạn bộ đếm pending registration tồn tại từ baseline: `Ticker.close` dừng executor nhưng không xóa tickables. Cần follow-up riêng; không diễn giải test này thành đã chứng minh bộ đếm core mặc định về 0.

Các mục "Bằng chứng baseline đã kiểm" và "Review Focus và kết luận tại 30d1604" là **bằng chứng lịch sử** tại `30d1604`, không thay thế nghiệm thu SHA cuối. Checkbox cũ là checklist thiết kế; trạng thái thực thi có thẩm quyền nằm trong báo cáo nghiệm thu và ledger của Run `run_c4af214b4a8d`.
