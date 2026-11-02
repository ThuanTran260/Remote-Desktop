# Review cuối toàn đợt chuẩn bị Host/Viewer v2

Ngày: 2026-11-02. Task `task_4515d13f1ba1`, Dispatch `ctx_c1a587518156`, terminal `term_e26c6399-1db3-4b1a-885a-4eb2b39f5e0a`. Người dùng đã nói “continue” sau checkpoint quota; review này là lượt tiếp tục được ủy quyền, không phải tự chạy sau reset.

**Spec: PASS. Clean-code/integration: PASS. Quyết định toàn đợt chuẩn bị: APPROVED.** Không có finding Critical/Important hoặc blocker mới; hai Minor đã công khai được phân xử bên dưới. Đây là nghiệm thu cleanup và chất lượng đề xuất để trình duyệt, **không phải phê duyệt UI/CLI/H2 của người dùng, không phải v2 đã triển khai, không phải sẵn sàng merge v2**.

## Phạm vi và phương pháp

- Fixed base: `369440779658424e631b4d0a965f870dde5ea7b9`.
- Fixed head và HEAD quan sát: `26b049139ef67ba5c365143dc0cb3e3be1810387`.
- Code SHA có bằng chứng verify: `e58afc77a41870d110cfb456a536f6253644cd94`.
- Đã đọc brief, review-contract, senior reviewer contract, ledger/rulings và clarification; đọc gói `review-3694407..11258cd.diff` đúng một lần theo các pass metadata/plan, lịch sử review, đề xuất, source/test; sau đó đọc delta `review-11258cd..26b0491-metadata.diff`. Không xây lại gói hay giao lại các task đã nghiệm thu.
- SHA-256 gói lớn: `F774FB3A12C285BB01E3D70A9CAA46B2969F59B6D6FD1C3001D7B128E9AA9276`; delta: `168055F0596B8A43FF1B5986D45E8A1C9366275A3C61417803797CCE7DA958E7`.
- Các lần đọc thêm có mục tiêu: kiểm cửa sổ ticker-visible/handle-unpublished và hai caller cleanup; xác nhận cấu trúc lỗi setup đã có tại base; đối chiếu deadline, input/slot và B1 phase với HV đã duyệt; kiểm README về standards; kiểm artifact XML/CSV/exit đã lưu. Không mở lại review rộng A1–B0 hoặc nghiên cứu baseline.
- Standards: README dòng 85–87, Global Constraints của plan và review-contract; quyền người dùng giữ checkout/no-push/no-merge/no-amend ghi đè workflow PR chung. Xét ownership, exception safety, idempotence, lock, tên/trách nhiệm, duplication và abstraction theo tác động thực tế; không tìm thấy vấn đề clean-code material mới.
- Chỉ ghi báo cáo này. Không chạy test/Maven/GUI/Robot/hai JVM/hai máy, không helper, không đổi source/docs tracked/index/HEAD/target/config/model. Kiểm đọc mới: HEAD đúng, working tree/index sạch; diff `e58afc7..26b0491` trên source/build rỗng, `git diff --check` toàn range không báo lỗi; `Protocol.java:8` vẫn VERSION 1.

## Điểm đạt và tính nhất quán toàn đợt

| Trục | Bằng chứng và kết luận |
| --- | --- |
| Owner pending registration | `src/main/java/com/ltm/rd/server/ServerCore.java:85`, `:152`, `:308`: core giữ đúng handle volatile và đóng trước ticker. Không đổi getter để làm count đẹp, không đổi shared `Ticker.close`, không thêm lock/seam/dependency hay API. Tên và trách nhiệm đúng nơi tài nguyên được tạo. |
| Cleanup/race | `ServerCore.java:127`, `:161`, `:274`, `:292`: close-before-start bị chặn trước bind; close lặp qua CAS; late setup dùng cùng releaseResources. Proof cụ thể bên dưới đủ cho cửa sổ publication chưa được ép riêng bằng test. |
| Regression thực | `src/test/java/com/ltm/rd/server/ServerCoreCleanupTest.java:22`, `:35`, `:46`, `:51`: constructor public, DEFAULTS/pending sweep, count 1→0→0, bind lại đồng thời hai port, chờ có hạn rd-thread mới dừng, finally cleanup. Không chỉ kiểm getter. |
| Slot/PIN/future không suy yếu | `ServerCoreCloseRaceTest.java:89` chỉ thêm count sau start/closer; hai ca vẫn 10 lượt. `SlotReleaseIntegrationTest.java:125`, `:243` bật lại pending sweep, giữ phần còn lại nguyên vẹn gồm ba ca 20 lượt và ba ca lỗi. `ServerCore.java:262` vẫn đòi cả socketsClosed/terminated/inputReleased thành công trước trả slot; failure giữ BUSY. Không sửa PIN, deadline, reason, wire hoặc input semantics. |
| CLI migration/secret | `docs/superpowers/specs/2026-11-02-host-viewer-ui-cli-h2-design.md:46`, `:63`, `:72`, `:87`: cú pháp role/address tường minh, không alias hay tự đảo IP của script v1; có duplicate/mixed/help/port/exit contract, parse không DNS/I/O, preset không connect, diagnostic không echo argv/token/value tùy ý hoặc PIN. Đây là breaking proposal có hướng migration, chưa áp vào jar hiện tại. |
| UI/runtime boundary | Cùng design `:95`, `:120`, `:143`, `:175`, `:177`: Host giữ SharingBar/adapter/InputExecutor, Viewer giữ ControlFrame/capture. UI qua Controller/Snapshot; Swing trên EDT, snapshot đọc nền, không wait/future/Robot trong UI hay khóa runtime. Một executor Host sống qua nhiều phiên; prepare theo phiên, kết quả muộn không hồi sinh phiên. |
| Lifecycle và lỗi | Design `:158`, `:166`, `:182`, `:188` khớp HV handshake/ownership: prepared + ATTACH bất kỳ thứ tự, SCREEN_INFO trước SESSION_ACTIVE, imageChannelReady trước Viewer ACTIVE, input sau paint, deadline giữ mốc gốc; cancel/reconnect phân biệt cleanup cục bộ Viewer với slot Host. Release fail/native treo không được đổi thành thành công, không timer ép slot, không tự retry FAULTED. Shutdown report không đồng nghĩa process exit. |
| B1/B2 đã sửa đúng | Design `:109`, `:174`, `:298`, `:305` đồng nhất: B1 đổi ControlView thành client.ClientPhase cùng producer client.ClientSession và consumer runtime/snapshot/UI/tests/generation/gate; không giữ mapping ServerPhase tạm. B2 chỉ rename sang viewer.ViewerPhase và package/import/test. Phù hợp HV dòng 223 ở HEAD (dòng 221 tại nguồn S trước phần metadata thêm hai dòng). Finding Important cũ đã đóng thực chất, không dời sang B2 dưới tên rename. |
| H2 thật và hai hướng | Design `:220`, `:234`, `:240`, `:257`, `:270`, `:289`: cùng SHA/digest, hai Windows desktop, đổi Host/Viewer, 30 phút/50 vòng mỗi hướng là đề xuất rõ. H2-11a/b/c yêu cầu mạng thật trước connect, sau AUTH trước ACTIVE và ACTIVE giữ input, deadline đúng phase/mốc; phase không bắt được vẫn CHƯA CHẠY. Hai JVM/latch/raw-peer không thay H2 thật, thiếu artifact/DPI/thời gian không thành PASS. |
| Trạng thái tài liệu | Design `:3`, `:309`; HV bảng đầu; report chuẩn bị `:34`, `:40`, `:46`: reviewer APPROVED chỉ cho presentation. Metadata pause/chưa launch ở fixed HEAD là snapshot lịch sử; live preamble/ledger và báo cáo này mới xác định lượt review đang hoàn tất. Không đọc chữ lịch sử đó thành lỗi runtime hoặc user approval. |

### Proof publication và cleanup

1. Nếu start đã công bố handle, close đọc volatile và remove đúng registration trước shutdown ticker. `Ticker.java:43`–`:47` tạo entry có identity riêng; handle remove khỏi CopyOnWriteArrayList, nên gọi lặp/đồng thời là an toàn và không gỡ đăng ký của phiên khác.
2. Nếu close đã đặt closed nhưng chưa thấy handle, start vẫn công bố handle rồi kiểm closed tại `ServerCore.java:161`; nhánh cleanup muộn tại `:164` đọc handle và gỡ. Điều này bao gồm close đã thấy ticker tại `:151` nhưng start đang giữa register hoặc chưa gán handle tại `:152`: Ticker hiện cho register sau shutdown, nên late cleanup vẫn lấy được handle. Không cần thay shared semantics.
3. Nếu lần kiểm cuối start thấy false, publication của handle đã đứng trước lần đọc đó; CAS của close phải ở phía sau trong thứ tự đồng bộ, trước các lần đọc resource của close. Close không thể bỏ sót handle đã công bố theo ordering này. Hai cleanup có thể cùng đóng handle, nhưng field không bị xóa/null hóa và start chỉ một lần.
4. Proof áp dụng khi setup hoàn tất đến kiểm cuối. Exception sau bind/đang tạo resource là giới hạn cấu trúc đã tồn tại ở fixed base, không phải regression do assignment/handle close này. Không chứng nhận exception safety tổng quát cho lỗi tạo thread/OOM hoặc cấu hình ngoài đường default hợp lệ.

Test race hiện hữu ép close hoàn tất trước resources, không ép riêng từng instruction giữa ticker và handle. Báo cáo tích hợp nói đúng giới hạn đó; không gán khả năng kiểm chứng lịch xen kẽ này cho 20 executions hoặc cho 912 tests.

## Bằng chứng đã lưu và giới hạn

Đã parse XML độc lập trong lượt review này; đây là kiểm artifact, **không phải chạy test mới**:

| Artifact | Kết quả |
| --- | --- |
| Hai `task-1-red-TEST-*.xml`, exit | 22 executions, 21 failures, 0 errors/skips, exit lưu 1. Phân nhóm failure: một default cleanup và 20 late cleanup đều AssertionFailedError expected 0 / actual 1; không phải compile/timeout. |
| Năm `task-1-green-TEST-*.xml`, exit | 89 executions, 0 failures/errors/skips, exit lưu 0. |
| 67 `coordinator-TEST-*.xml` | Tổng 912 executions, 0 failures/errors/skips, khớp `coordinator-tests.json` gắn e58afc7/exit 0 và log clean verify đã lưu. |
| `coordinator-jacoco.csv` | ServerSession 76/83; HandshakeHandler 24/24; ControlledSide 10/10; ControlSide 70/78; ClientSession 77/83. Cả năm đạt >=0.80 và đúng lớp, không dùng tổng coverage che thiếu một gate. |
| Môi trường từ XML coordinator | Windows 11, Oracle Java 24.0.2, `java.awt.headless=true`; target compiler 21 không phải bằng chứng chạy trên mọi JDK 21/OS. |

Coordinator có một clean verify trên code SHA này; source/build không đổi tới review head nên không có lý do chạy lại. Không coi warning fault injection trong log là failure Maven khi exit/XML đã phân biệt. Không chứng nhận lại toàn bộ old A1–B0, các coverage negative experiments cũ, PIT, 500 races hoặc trải nghiệm GUI thật.

## Findings xếp hạng và phân xử

**Critical: 0. Important: 0. Minor: 2 đã công khai từ Task 1; không thêm finding mới.**

1. **Minor — Conventional Commits có BOM đầu subject; giữ ngoại lệ có điều kiện.** Vị trí: `.superpowers/sdd/2026-11-02-host-viewer-v2-preparation/task-1-commit-message.txt:1`, `task-1-report.md:56`; quy tắc `README.md:85`; disclosure tracked `docs/superpowers/reports/2026-11-02-host-viewer-v2-preparation.md:24`. Raw-prefix evidence đã lưu là `EF BB BF 66 69 78 3A`. Trigger: công cụ đòi type ngay ký tự đầu subject có thể không nhận `fix:`. Impact chỉ metadata/parser, không source/runtime; không có bằng chứng pipeline hiện bị hỏng. Correction/ruling: giữ SHA theo lệnh cấm amend/rebase, message sau dùng UTF-8 không BOM; nếu publication sau này đòi sửa lịch sử phải xử lý bằng thẩm quyền mới, không tạo empty commit để giả sửa subject. **Không chặn preparation approval; không tuyên bố standards tuyệt đối không ngoại lệ.**
2. **Minor — Hai witness lịch sử được dẫn không tồn tại; đã đính chính, không thể phục dựng chứng cứ.** Vị trí: `.superpowers/sdd/2026-11-02-host-viewer-v2-preparation/task-1-report.md:28`, `:48`; correction `task-1-evidence-clarification.md:5`; disclosure tracked report chuẩn bị `:25`. Kiểm Test-Path mới vẫn false cho `task-1-red-production-diff.txt` và `task-1-final-status.txt`. Trigger: người đọc mở link để kiểm production/status tại timestamp cũ sẽ không có file. Impact: mất đúng hai snapshot lịch sử, không phủ định log/exit/XML RED/GREEN hiện có. Correction đã làm đúng: giữ report gốc cùng clarification công khai; không tạo file rỗng mới, không lấy clean status hiện tại làm proof cho thời điểm RED. **Clarified, nonblocking; giới hạn historical proof vẫn còn.**

Hai finding Task 2 cũ không còn mở: B1 phase contract đã đồng bộ toàn bộ producer/consumer/tests; pre-ACTIVE physical network cuts đã thành H2-11a/b riêng, giữ yêu cầu hai hướng và CHƯA CHẠY khi không bắt được phase. Không yêu cầu fix wave từ review cuối này.

## Rulings được xét lại ở cấp toàn đợt

| Ruling theo thứ tự ledger | Phân xử |
| --- | --- |
| Giữ checkout feature, tách owner source và draft | Chấp nhận theo ủy quyền; không có thay đổi source/index ngoài phạm vi trong range. Không yêu cầu worktree/PR mới cho review. |
| Core giữ/đóng registration, không sửa Ticker chung | Chấp nhận theo proof trên; không có nhu cầu thêm lock/seam chỉ cho test. |
| Cleanup làm ngay, UI/CLI/H2 vẫn proposal | Chấp nhận; user start hai task và quota resume không phải duyệt B1/B2. |
| Sol theo task, Astra cho final, không model rẻ/Claude | Giữ ràng buộc đã ghi; không fallback hoặc tạo helper trong lượt này. |
| Kế thừa baseline như lịch sử, một verify mới cho code đổi | Chấp nhận; XML/CSV/current source equality đủ cho gate preparation, không mở lại baseline suite. |
| Giữ SHA có BOM theo no-amend | Chấp nhận với Minor #1 và parser risk còn nguyên. |
| Đính chính witness, không manufacture/rerun | Chấp nhận với Minor #2; không chuyển status hiện tại thành lịch sử. |
| Fresh authoritative fix Dispatch sau release | Chấp nhận như lifecycle correction, không phải duplicate reviewer/source owner; chỉ dùng live IDs trong lượt này. |
| r0/r1 immutable artifact + diff thay commit giả | Chấp nhận provenance document-only đã được scoped review xác nhận; final package chứa bản r1, không coi consistency checks là test sản phẩm. |

## Những hành vi đã xét nhưng không yêu cầu làm trong review này

- Xóa mọi registration hoặc từ chối register sau `Ticker.close`: đổi shared semantics ngoài fix; core owner đã đủ.
- Null hóa handle, thêm lock hoặc seam ép riêng publication window: không cần cho proof; tăng surface/rủi ro mất handle giữa hai cleanup.
- Bắt close đợi start chưa công bố tài nguyên: late cleanup là hợp đồng có sẵn; acceptance race xét sau cả start/closer hoàn tất, không hứa zero resources tức thì khi start còn chạy.
- Hủy callback pending sweep đã giữ COW snapshot: unregister không hứa cancel callback đang chạy; pending set concurrent và close-if-pending idempotent, không có acceptance regression cần đổi semantics.
- Tổng quát hóa helper dò rd-thread giữa các test: lặp nhỏ, style hiện hữu và self-contained test hợp lý; không thêm abstraction/refactor ngoài phạm vi.
- Sửa exception path khởi tạo bất kỳ sau bind, OOM/tạo thread lỗi: cấu trúc tồn tại tại base; chưa có nghi vấn cụ thể làm hỏng đường default được giao. Giới hạn đã ghi, không chứng nhận mọi lỗi môi trường.
- Chạy lại focused/full tests, Maven, coverage negatives hoặc experiment mới: bị cấm, cùng code đã có evidence; không có doubt chưa giải quyết cần một focused experiment.
- Đánh fail đề xuất chỉ vì CLI breaking/không alias: lựa chọn được mô tả đủ, có migration đúng vai/IP; người dùng còn quyền chọn trước B1.
- Yêu cầu approval người dùng trước khi hoàn tất review: review tạo giao phẩm để trình duyệt, không phải hành động triển khai B1/B2.
- Thêm consent dialog, auto reconnect, ghép lại kênh ảnh, listener toggle, FRAME_ACK/freshness, dependency/seam dự phòng hoặc polish: ngoài phạm vi đã chốt; không phải blocker.
- Dời phase contract sang B2 hoặc giữ mapping ServerPhase tạm: trái HV; draft đã sửa rõ ở B1 nên không mở lại finding đã đóng.
- Bắt Viewer đợi future Robot Host, retry FAULTED hoặc timeout trả slot: trái ownership/ba-future contract; draft đã cấm, không coi đó là tiện ích còn thiếu.
- Hứa mọi mất ảnh đóng ở 3 giây hoặc DISCONNECT luôn đến peer: trái phân biệt stall/EOF/write watchdog/frame timeout và best effort; proposal giữ đúng.
- Dùng Hủy/shutdown/harness thay physical cut trước ACTIVE: không chấp nhận phép thay; draft yêu cầu thật riêng và giữ CHƯA CHẠY nếu thiếu, chưa cần thực thi trong preparation review.
- Cố làm Robot native treo trên hai máy: không tất định và không cần để chấm design; latch/fault tests kiểm invariant, observation thật ghi riêng, không giả thành H2 PASS.
- Áp thêm ngưỡng FPS/p95/heap tùy ý: FPS/latency là mục tiêu đo được giữ, duration/count/input/resource/artifact là gate; không tự nâng mục tiêu thành policy mới hoặc miễn resource leak.
- Coi khoảng trống baseline về JVM hook/GUI bỏ shutdown report, lỗi pendingText hoặc README v1 cũ là regression của cleanup: draft chỉ rõ việc B1 phải làm; chưa có code v2 và không tuyên bố các khoảng trống đã được sửa.
- Đòi đồng bộ mọi tên/class/test/docs v2 trong preparation: B1 mới đổi hành vi nguyên khối, B2 mới rename; làm bây giờ vượt user approval gate.
- Coi metadata “paused/chưa launch/chờ review” tại pinned commit, raw report lịch sử hoặc routing Sonnet cũ là trạng thái live: snapshot đã có provenance, latest ledger/preamble ghi resume/provider hiện hành. Coordinator cập nhật trạng thái sau settlement, reviewer không sửa tracked docs hoặc lịch sử.
- Viết lại các trích review/ruling tiếng Anh thành báo cáo lịch sử khác: bảo toàn nội dung nguồn và provenance là hợp lý; tài liệu giải thích hiện tại bằng tiếng Việt, không làm mất quyết định hoặc che finding để sửa hình thức.
- Certify PIT/500 race/final-SHA coverage, hai JVM, H1 Robot, H2/DPI/focus/latency/30 phút/50 vòng: chưa chạy cho v2; headless 912 không thay các gate này.
- Tạo PR/branch mới, merge/push, amend/rebase, xóa scratch hoặc đổi config/model: không được giao quyền; không liên quan kết quả review preparation.

## Câu hỏi builder và bước còn lại

**Không còn câu hỏi implementation material chưa có đáp án trong contract sau r1.** Producer/consumer phase, ownership, deadline, cancel/close, CLI ambiguity và shutdown/error reporting đều có hướng bắt buộc đủ để lập plan khi được duyệt. Widget/helper trình bày/kiểu cleanup projection cụ thể là quyết định routine trong invariant đã ghi, không cần thêm abstraction hoặc hỏi lại các quyết định đã chốt.

Ba lựa chọn vẫn phải trình người dùng, đúng như design dòng 309–311: cú pháp breaking CLI; tên/nút/hành vi cửa sổ đề xuất; môi trường/người chạy/lịch và phân bố H2 hai hướng. Đây là approval choices đã có phương án cụ thể, không phải lỗ hổng builder. Sau settlement, coordinator ghi nhận final gate đã đạt và trình proposal; chưa tự viết plan hoặc triển khai B1/B2. Full v2 readiness vẫn cần implementation, review và toàn bộ gate final-SHA/H1/H2 thích hợp; review này không cấp quyền merge/push.

**Kết luận: PASS / APPROVED cho toàn đợt chuẩn bị tại fixed head nêu trên, với hai Minor đã phân xử và giới hạn evidence được giữ nguyên. Không chứng nhận zero bugs hoặc v2 merge readiness.**
