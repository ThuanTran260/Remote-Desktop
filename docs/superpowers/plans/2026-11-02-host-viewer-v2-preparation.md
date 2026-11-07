# Host/Viewer v2 Preparation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development for implementation and the version-matched orchestration skill for supervised workers. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Đóng lỗi pending ticker của cấu hình mặc định và chuẩn bị thiết kế UI/CLI, tài liệu, nghiệm thu H2 đủ rõ để duyệt trước B1/B2.

**Architecture:** Giữ quyền sở hữu registration tại `ServerCore`; không đổi ngữ nghĩa dùng chung của `Ticker`. Chạy một worker sửa code và một worker soạn thiết kế độc lập. Chỉ worker code sở hữu source/index/HEAD và Maven; worker thiết kế chỉ ghi bản nháp trong workspace SDD riêng của plan. Coordinator tích hợp tài liệu sau bàn giao.

**Tech Stack:** Java 21 target, Swing/Robot, Maven wrapper, JUnit 5, JaCoCo 0.8.16; Orca, Codex Sol và Astra.

**Spec:** `docs/superpowers/specs/2026-11-01-host-viewer-design.md` (mục 1, 2, 3.5 và gate Phần 3–4); `docs/superpowers/reviews/2026-11-02-host-viewer-a1-b0-final-review.md` (pending registration follow-up). Thiết kế UI/CLI/H2 đã được người dùng duyệt để thực thi B1/B2 ngày 2026-11-02; đợt chuẩn bị này chỉ chứng nhận phạm vi Task 1/2.

**Nghiệm thu đợt chuẩn bị (2026-11-02):** Task 1 và Task 2 đã hoàn tất; review cuối Codex `gpt-6-astra` high **PASS / APPROVED**, không có Critical/Important hoặc blocker mới, hai Minor metadata/evidence đã công khai. Code `e58afc7` có một lượt coordinator `clean verify` 912/0/0/0; source không đổi sau đó. [Báo cáo và giới hạn](../reports/2026-11-02-host-viewer-v2-preparation.md). **Người dùng đã duyệt Phần 3–4 để tiếp tục [B1/B2](2026-11-02-host-viewer-b1-b2.md), ghi ở `25b6a53`.**

**Mốc đọc ngày 2026-11-04:** Phần 3–4 và thực thi B1/B2 đã được duyệt; code/refactor đã được nhận theo từng đợt. Trạng thái theo SHA, bằng chứng và các gate còn thiếu nằm ở [báo cáo gate v2 tập trung](../reviews/2026-11-02-v2-automated-gates.md); hướng dẫn chạy hiện hành nằm ở [README](../../../README.md). Không suy từ phê duyệt thiết kế hoặc lịch sử v1 thành nghiệm thu đủ GĐ2.

## Global Constraints

- Baseline: `369440779658424e631b4d0a965f870dde5ea7b9`, checkout `E:/luyentaphe/LapTrinhMang/DoAn-LTM`, branch `feat/wave3-4-desktop-v1`. Người dùng đã yêu cầu tiến hành hai việc được đề xuất; không push/merge/rebase/amend.
- Source vẫn là wire v1, `Protocol.VERSION = 1`; không đổi gói, lý do/thông điệp đóng, deadline, chiều kết nối, UI hoặc CLI trong bản sửa cleanup.
- Không đổi hành vi `Ticker.close`, không thêm khóa hoặc test seam mới nếu seam hiện có đủ ép thứ tự. Giữ public API và thứ tự shutdown; không nhả slot trước ba future thành công.
- Không giảm assertion, timeout, input hoặc số lượt lặp hiện có; không `@Disabled`, không `Thread.sleep` mới. Test phải chứng minh hành vi người dùng hoặc hợp đồng tài nguyên, không chỉ kiểm dòng code.
- Code/comment/commit bằng tiếng Anh; tài liệu bằng tiếng Việt. Giữ phong cách lân cận, tên rõ nghĩa, một chủ sở hữu cho mỗi tài nguyên; không refactor ngoài phạm vi, không thêm dependency, lớp chung hoặc API dự phòng.
- Worker thực thi dùng `gpt-6.1-sol`, effort theo task; review cuối `gpt-6-astra`. Không dùng Luna, model rẻ hơn hoặc Claude. Kiểm `launch.effective`, không đổi config toàn cục.
- Không tạo helper agent. Chỉ dùng lifecycle Orca, xác thực Task/Dispatch trước xử lý báo cáo và release trước ack. Mọi claim phải gắn với SHA và bằng chứng.
- B1/B2 chỉ bắt đầu khi Phần 3–4 được trình bày và duyệt. H2 cần hai máy thật; không đánh dấu PASS bằng test headless. PIT/500 race thuộc SHA v2 cuối, chưa chạy trong đợt chuẩn bị này.
- Chỉ dẫn người dùng mới nhất bỏ ngưỡng dừng 5%: tiếp tục công việc được giao bất kể tỷ lệ hiển thị, lưu artifact tăng dần; dùng Codex `gpt-6.1-sol` với effort theo độ khó và `gpt-6-astra` cho review cuối, không đổi sang Luna/model rẻ hơn/Claude. Nếu provider thực sự từ chối thì ghi nguyên nhân và trạng thái chưa kiểm chứng trung thực. **Cập nhật 2026-11-07:** riêng phần đóng gói (closeout), người dùng duyệt worker thực thi là Claude `sonnet`; review cuối vẫn là `gpt-6-astra`, chưa chạy ([báo cáo gate v2](../reviews/2026-11-02-v2-automated-gates.md)).

## Review Focus

1. Core cấu hình mặc định đóng bình thường vẫn giữ pending registration: Task 1 kiểm số đăng ký 1 trước đóng, 0 sau đóng, thread dừng và hai port dùng lại được.
2. `close()` chạy trước khi `start()` công bố registration: Task 1 bổ sung assertion 0 vào hai ca race có seam hiện hữu; không tạo race bằng may rủi.
3. Đóng lặp và đóng trước start: Task 1 kiểm idempotence và không tạo tài nguyên muộn.
4. B0 cleanup chỉ xanh vì tắt pending sweep: Task 1 bật lại pending sweep trong fixture slot, giữ mọi kiểm BUSY/PIN/input/thread/port.
5. `--host IP` cũ nhập nhằng với vai Host mới, hoặc tài liệu chạy sai máy: Task 2 phải có bảng migration CLI, đối số hợp lệ/không hợp lệ và hướng dẫn H2 theo đúng vai v2.

### Task 1: Own and release the default pending ticker registration

**Files:**
- Modify: `src/main/java/com/ltm/rd/server/ServerCore.java`.
- Create: `src/test/java/com/ltm/rd/server/ServerCoreCleanupTest.java`.
- Modify: `src/test/java/com/ltm/rd/server/ServerCoreCloseRaceTest.java`.
- Modify: `src/test/java/com/ltm/rd/server/SlotReleaseIntegrationTest.java` (hai chỗ tạo Seams đang tắt pending tick).

**Interfaces:**
- Consumes: `Ticker.register(Tickable): Ticker.Registration`, `Registration.close(): void`, `ServerCore.start/close`, `tickerRegistrations()`, seam `beforeResources`.
- Produces: public API không đổi; một field `private volatile Ticker.Registration pendingRegistration` thuộc core, công bố cùng setup và gỡ trong `releaseResources`. Cả đường close bình thường và late cleanup của start dùng cùng handle; close handle có tính idempotent.

- [x] Đọc luồng start/close và test liên quan, ghi giả thuyết: ticker còn đếm vì registration không có owner đóng, không phải bằng chứng thread còn chạy.
- [x] Thêm test `defaultConfigurationReleasesPendingRegistrationAndPorts`: core dùng constructor public, cổng ephemeral, pending tick bật; assert 1 trước close, 0 sau close và close lần hai; dùng lại hai port; chờ có hạn các rd-thread mới dừng. Luôn đóng core trong finally.
- [x] Thêm test `closeBeforeStartDoesNotCreateResources`: close lặp an toàn, registrations 0, start bị từ chối.
- [x] Thêm `assertEquals(0, core.tickerRegistrations())` vào helper race hiện hữu sau khi start/closer đã xong; giữ 10 lượt mỗi ca và mọi assertion cũ.
- [x] Đổi đúng hai `pendingTickDisabled=true` trong fixture `SlotReleaseIntegrationTest` thành false; không bỏ seam input/worker hay thay kỳ vọng BUSY/PIN.
- [x] Chạy test mới/race để thấy đỏ đúng vì registration còn 1; lưu exit code và assertion. Không sửa production trước khi có bằng chứng đỏ.
- [x] Giữ handle trả về từ đăng ký pending và gỡ nó trong `releaseResources` trước khi đóng ticker; không đặt lại field làm mất handle giữa hai luồng cleanup.
- [x] Chạy `./mvnw.cmd -q -Dtest=ServerCoreCleanupTest,ServerCoreCloseRaceTest,ServerCoreLateBindTest,SlotReleaseIntegrationTest,TickerTest test`; lưu XML và kết quả. Không tự chạy lại để lấy xanh khi có lỗi.
- [x] Commit riêng, chỉ bốn file đã nêu, liệt kê test/fixture thay đổi trong body. Worker báo root cause, red/green, SHA, test count và giới hạn. Coordinator giữ gate review + full verify trước nghiệm thu.

### Task 2: Draft UI/CLI and H2 design without product changes

**Files:**
- Worker writes only: `<workspace>/ui-cli-h2-design.md`, `<workspace>/ui-cli-h2-report.md`.
- Coordinator integrates after review: `docs/superpowers/specs/2026-11-02-host-viewer-ui-cli-h2-design.md`; cập nhật trạng thái/link trong spec `2026-11-01-host-viewer-design.md`.

**Interfaces:**
- Consumes: mục 1–2 của spec đã duyệt; API UI/CLI hiện tại và yêu cầu H2 cũ. Đọc source tại baseline đã ghim, không dựa vào working tree đang sửa.
- Produces: thiết kế cụ thể để người dùng duyệt; trạng thái "Đề xuất, chờ duyệt", chưa có code v2.

- [x] Đối chiếu `LaunchOptions`, `Launcher`, `GuiApp`, `RoleChooserFrame`, `ServerFrame`, `ClientFrame`, `ControlFrame`, `SharingBar`, `AppRuntime`, `Controller`, `Messages`, test UI và README; ghi path:line cho phát hiện về hiện trạng.
- [x] Nêu các cách ánh xạ UI/CLI thực tế, trade-off và một phương án đề xuất. Phân biệt quyết định người dùng đã chốt (Host bị điều khiển, Viewer điều khiển) với đề xuất mới.
- [x] Chốt trong đề xuất: tên cửa sổ/nút/cờ CLI; tên lớp cũ→mới ở B1/B2; xử lý `--host IP` cũ, flags trùng/mâu thuẫn/sai role, help và exit code; không đưa PIN lên command line/log.
- [x] Ghi bảng trạng thái UI và thao tác được phép, gồm chuẩn bị adapter chậm/lỗi, ATTACH theo thứ tự bất kỳ, cancel/reconnect, BUSY/LOCKED/sai mã, hết hạn, mất một kênh, disconnect/shutdown, thất bại nhả input. Bám hợp đồng phần 1/1.8, không tự thay deadline hoặc cấp quyền.
- [x] Xác định owner InputExecutor/adapter/cửa sổ và nơi cập nhật UI trên EDT; tránh nhân đôi logic session hoặc Robot trong UI.
- [x] Viết nội dung Phần 4: danh sách tài liệu cần cập nhật tại B1/B2; lệnh build/chạy v2 dự kiến, hai hướng H2 đổi máy Host/Viewer, DPI/focus/minimize/key repeat, mất mạng khi giữ input, 30 phút/50 lần kết nối theo gate hiện hữu; bảng lưu SHA/môi trường/kết quả và tiêu chí PASS/FAIL/chưa chạy. Không bịa số đo.
- [x] Liệt kê kiểm thử tự động B1/B2 vs kiểm thử hai máy, ranh giới commit B1 nguyên khối và B2 cơ học; không viết implementation plan B1 trước khi thiết kế được duyệt.
- [x] Tự review consistency, khoảng trống và user decisions; báo cáo dẫn nguồn local, không sửa tracked files, không chạy test/Maven, không commit.

## Gates and execution ownership

- Wave đầu: Task 1 Sol high và Task 2 Sol high chạy song song, ghi file không chồng nhau.
- Sau Task 1: reviewer Sol xhigh chỉ đọc fixed diff; coordinator chạy một `clean verify` trên SHA code đã commit. Hai gate đều đạt mới nghiệm thu. Một người sở hữu target; không lặp suite khi chưa có thay đổi/lỗi mới.
- Sau Task 2: builder/reviewer Sol high kiểm tính đủ để triển khai, spec compliance và clean boundaries; không triển khai UI. Sửa mọi điểm material bằng một Dispatch sửa draft trước khi đưa người dùng.
- Coordinator tích hợp docs đã review, đồng bộ trạng thái C0–B0 và provider mới, ghi rõ trạng thái chờ duyệt tại bàn giao Task 2 là lịch sử; quyết định duyệt sau đó nằm ở phần nghiệm thu đầu tài liệu. Không sửa source thay worker.
- Final reviewer Astra high xét toàn range và giới hạn nghiệm thu. Nếu có finding material: một fix wave và một re-review scoped; không tự waive finding vì model hoặc test count.
- Cuối đợt: lưu receipt/report/log/coverage và ledger, báo kết quả từng task; giữ branch. Không lặp thao tác xóa scratch đã bị automatic approval review chặn ở đợt trước.
