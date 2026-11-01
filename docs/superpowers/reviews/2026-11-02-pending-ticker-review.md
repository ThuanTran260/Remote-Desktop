# Review Task 1 — quyền sở hữu pending ticker registration

**Spec: PASS. Clean code: PASS, có hai ghi nhận Minor bên dưới. Quyết định: APPROVED cho gate review Task 1.** Không có finding Critical hoặc Important; không yêu cầu sửa production. Đây không phải chứng nhận merge hay nghiệm thu v2; gate full `clean verify` do coordinator sở hữu vẫn phải được ghi nhận riêng trên đúng SHA.

- Base: `c48bfda61fa8813e577c49563a21cbf9dc4b3fae`.
- Head: `e58afc77a41870d110cfb456a536f6253644cd94`.
- Task review: `task_0e924712d95e`; Dispatch: `ctx_a837c85ea088`; terminal: `term_7c4b85fc-d276-4ea0-a6a4-880110b9b174`.
- Phạm vi: Task 1, Global Constraints và Review Focus 1–4 của `docs/superpowers/plans/2026-11-02-host-viewer-v2-preparation.md`; `review-contract.md`, `task-1-report.md`, package `review-c48bfda..e58afc7.diff`, source/test liên quan và bằng chứng đã lưu. Không review thiết kế Task 2.

## Điểm mạnh và đối chiếu spec

| Yêu cầu | Đánh giá và bằng chứng tại Head |
| --- | --- |
| Registration có đúng owner | `src/main/java/com/ltm/rd/server/ServerCore.java:85` giữ handle private volatile; `:152` giữ đúng giá trị trả về của `t.register(pending)`; `:308`–`:311` đóng handle trước ticker trong owner hiện hữu. Không sửa getter để giả lập count 0. |
| Cấu hình production thật | `src/test/java/com/ltm/rd/server/ServerCoreCleanupTest.java:22` dùng constructor public, loopback, hai cổng 0 và `Timeouts.DEFAULTS`. Constructor đi qua `ServerCore.java:92` và `Seams.none()` tại `:67`, nơi pending sweep bật. Test `:35`–`:46` kiểm 1→0→0, bind lại đồng thời cả hai cổng, chờ có hạn thread mới dừng, và luôn close trong finally. Đây là kiểm hợp đồng tài nguyên, không chỉ getter. |
| Close lặp và close trước start | `ServerCoreCleanupTest.java:51`–`:64` kiểm hai lần close, registrations 0, start bị từ chối, không có rd-thread mới và finally cleanup. `ServerCore.java:127` từ chối trước bind/setup; CAS tại `:274` giữ idempotence của close. |
| Hai race có ép thứ tự | `src/test/java/com/ltm/rd/server/ServerCoreCloseRaceTest.java:65`–`:88` giữ nguyên seam/latch; assertion mới `:89` chạy sau start và join closer. Mỗi ca vẫn 10 lượt (`:115`, `:120`), còn nguyên assertion thread dừng, start thất bại và hai port dùng lại (`:99`–`:103`). Không thêm seam hay race dựa vào may rủi. |
| Khôi phục pending sweep trong slot fixture | `src/test/java/com/ltm/rd/server/SlotReleaseIntegrationTest.java:125` và `:243` là đúng hai cờ true→false. Phần còn lại của file không đổi: cleanup registrations/ports/threads tại `:53`–`:70`; ba repeated test vẫn 20 lượt; giữ nhận dạng future, nguồn/đích input release, BUSY, PIN chưa đổi/đổi sau release, worker thực sự bị giữ, reader/dispose và ba ca release lỗi/null/throw. |
| Giữ hợp đồng shutdown và slot | `ServerCore.java:262` vẫn dùng `allOf(socketsClosed, terminated, inputReleased)` rồi mới release. Không thay `Ticker.close()`, API public, gói tin, wire version, deadline, lý do/thông điệp đóng, UI/CLI, dependency hoặc lock. Các collaborator production liên quan không đổi trong fixed range. |
| Phạm vi commit | Diff thật có đúng bốn path được giao; body commit liệt kê các test/fixture và giới hạn verify. Saved `task-1-final-diff.patch` khớp diff base→head sau chuẩn hóa CRLF/BOM của file artifact; source hiện tại của bốn path khớp Head. `git diff --check` của fixed range không có lỗi. |

Tài nguyên được giữ và gỡ ở nơi tạo nó, không mở rộng trách nhiệm của shared `Ticker`. Tên `pendingRegistration` và local `registration` rõ nghĩa, thay đổi production nhỏ và cùng một trách nhiệm. Không thấy smell nào đủ tác động để yêu cầu abstraction hoặc refactor mới.

## Phân tích publication và hai đường cleanup

1. **Close bình thường sau setup:** handle được công bố volatile tại `ServerCore.java:152`; `releaseResources` đọc nó tại `:308` và gỡ trước `ticker.close()`. `Ticker.register` tạo entry có identity riêng và trả handle remove entry đó (`src/main/java/com/ltm/rd/common/Ticker.java:43`–`:47`); sửa này không đụng registration của session.
2. **Close trước publication:** close CAS `closed=true` trước khi đọc resource fields. Nếu nó đọc `pendingRegistration=null`, lần kiểm cuối start tại `ServerCore.java:161` thấy closed và gọi `releaseResources(null, ...)` tại `:164`, sau khi start đã gán handle. Các ca beforeResources hiện hữu ép trường hợp close hoàn tất trước khi resources được tạo; cả hai phải kết thúc với count 0.
3. **Close đã thấy ticker nhưng chưa thấy handle:** start có thể bị dừng giữa `ticker=t` tại `:151` và việc trả về/công bố registration tại `:152`, kể cả sau khi entry đã được thêm trong `Ticker.register`. Close có thể dừng ticker trong khoảng này. `Ticker.register` hiện không từ chối ticker đã đóng, nên start vẫn nhận và công bố handle; kiểm closed cuối start bắt buộc chạy late cleanup và gỡ entry đó. Không thể suy ra mất handle chỉ từ việc ticker đã bị shutdown. Các test hiện tại không ép riêng cửa sổ hẹp này; kết luận ở đây dựa trên thứ tự code/volatile, không gán cho chúng độ phủ mà chúng không có.
4. **Start đọc closed=false ở lần kiểm cuối:** mọi publication resource của start đứng trước lần đọc đó. CAS thành công của close phải đứng sau lần đọc false trong thứ tự đồng bộ; các lần đọc volatile của close không bỏ sót handle đã công bố. Nếu CAS đứng trước, start thấy true và đi qua nhánh late cleanup ở trên.
5. **Hai cleanup cùng chạy:** chúng có thể cùng đọc và đóng cùng handle. `CopyOnWriteArrayList.remove(entry)` an toàn khi gọi lặp/đồng thời; lần sau chỉ không còn gì để gỡ. Field không bị đặt lại null hoặc thay bằng handle khác; start chỉ được thực hiện một lần qua CAS. Không có thao tác chờ mới hoặc lock mới trong diff.

Lỗi pending registration tồn tại ở base: `ServerCore.start` bỏ handle, còn `Ticker.close` chỉ shutdown executor. Thay đổi này xử lý đúng khoản cleanup đã được hoãn ở review trước; bản thân count 1 không chứng minh socket hoặc thread còn sống. Không phát hiện regression tài nguyên/slot do diff Task 1.

## Kiểm tra bằng chứng đã lưu, không chạy lại test

Tôi đã parse từng XML và đối chiếu testcase thực tế, không chỉ tin summary JSON hoặc tổng kết console Surefire.

| Bằng chứng | Kết quả kiểm độc lập từ artifact |
| --- | --- |
| RED: `task-1-red-exit.txt`, `task-1-red.log`, hai `task-1-red-TEST-*.xml` | Exit lưu là 1; 22 executions, 21 failures, 0 errors/skips. Default cleanup thất bại đúng `expected: <0> but was: <1>`; hai race có 10 lỗi count tương tự mỗi ca. Close-before-start xanh. Không phải lỗi compile, setup hoặc timeout. |
| GREEN: `task-1-green-exit.txt`, năm `task-1-green-TEST-*.xml` | Exit lưu là 0; 89 executions, 0 failures/errors/skips: Cleanup 2, CloseRace 20, LateBind 1, SlotRelease 63, Ticker 3. Slot gồm 20 lượt mỗi test identity/held input/held worker và ba test lỗi; không bị skip hoặc giảm số lượt. |
| Warning trong `task-1-green.log` | `TickerTest.tickerKeepsRunningAfterTickableThrows` cố ý ném `IllegalStateException("boom")` tại `src/test/java/com/ltm/rd/common/TickerTest.java:22`. PowerShell wrapper NativeCommandError/RemoteException không biến nó thành lỗi JUnit/Maven; XML không có error. |
| Gắn với code | Source giữa baseline `3694407...` và review base không đổi; saved final patch khớp fixed range và các path đang đọc khớp Head. Bằng chứng worker ghi Java 24.0.2/Windows, compiler release 21/headless; không suy diễn thành kiểm thực tế trên mọi JDK/OS. |

Trình tự RED trước sửa production được implementer báo cáo và phù hợp với lỗi/assertion của base. Snapshot diff production rỗng được dẫn không hiện diện thành file, nên không tuyên bố đã kiểm độc lập được snapshot lịch sử đó; xem Minor 2. Không chạy Maven, test, coverage, mutation test hoặc experiment khác; không có vấn đề còn mơ hồ cần một experiment mới. Full clean verify và coverage trên chính Head là gate riêng của coordinator, không được chứng nhận bằng 89 focused executions này.

## Findings theo mức độ

**Critical: 0. Important: 0. Minor: 2.**

1. **Minor — Standards: subject commit có U+FEFF trước `fix:`.** Vị trí: `README.md:85`; `.superpowers/sdd/2026-11-02-host-viewer-v2-preparation/task-1-commit-message.txt:1`; disclosure tại `task-1-report.md:56`. Đọc raw commit object xác nhận byte đầu message là `EF BB BF 66 69 78 3A`, không chỉ lỗi hiển thị terminal. Prefix không khớp sạch quy ước Conventional Commits của README; parser yêu cầu type ngay đầu chuỗi có thể không nhận diện commit. Nội dung vẫn bằng tiếng Anh và không ảnh hưởng source, runtime, test hoặc tree; không thấy gate commit-message trong CI/build/hook đang dùng để khẳng định nó đã gây lỗi pipeline. **Không chặn approval code Task 1.** Global Constraints tại plan `:15` cấm amend/rebase: giữ SHA này và ghi rõ ngoại lệ nhỏ, không yêu cầu sửa lịch sử hay tạo commit rỗng để giả sửa message cũ. Các message tiếp theo nên được ghi bằng UTF-8 không BOM.

2. **Minor — Handoff: hai file rỗng được dẫn không có trong package hiện tại.** Vị trí: `.superpowers/sdd/2026-11-02-host-viewer-v2-preparation/task-1-report.md:28` và `:48`. `task-1-red-production-diff.txt` và `task-1-final-status.txt` đều không tồn tại khi kiểm tra; report nói chúng đã được lưu rỗng. Điều này làm người đọc không thể mở đúng witness được dẫn để kiểm trạng thái production ở thời điểm RED hoặc status lịch sử. Log/exit/XML RED vẫn có đủ failure đúng nguyên nhân, GREEN và diff cuối vẫn có đủ, status hiện tại sạch; không có bằng chứng sửa production trước RED và không coi thiếu file là bằng chứng như vậy. **Không chặn approval code Task 1.** Coordinator nên diễn đạt chính xác là output rỗng không được lưu thành file, hoặc khôi phục artifact từ bản gốc nếu có; không tạo lại một file trống rồi coi nó là chứng cứ lịch sử, và không rerun suite chỉ để chữa liên kết báo cáo.

## Các hành vi đã cân nhắc nhưng không yêu cầu thay đổi

- Cho `Ticker.close()` xóa mọi registration hoặc cấm register sau shutdown: trái Global Constraints và mở rộng semantics của shared component; owner core đã đủ xử lý lỗi này.
- Đặt `pendingRegistration=null` sau close hoặc thêm lock/seam để ép riêng cửa sổ ticker-visible/handle-unpublished: không cần cho proof ở trên, có thể làm mất handle giữa hai cleanup hoặc tăng surface chỉ vì test. Không yêu cầu.
- Count hoặc resources xuất hiện tạm thời khi start vẫn đang chạy sau close: cơ chế cleanup muộn đã có ở base và được plan chấp nhận; tiêu chí race được kiểm sau khi start/closer hoàn tất. Không thay thành close phải đợi một start chưa công bố resources.
- Một callback pending sweep đã lấy snapshot trước khi unregister: semantics COW/Ticker hiện hữu không hứa cancel callback đang chạy. `PendingConnections` dùng concurrent set và đóng pending idempotent; shutdown vẫn dừng executor. Không phát hiện tác động acceptance cần mở rộng fix.
- Trích helper kiểm rd-thread dùng chung giữa hai test: có lặp idiom nhỏ nhưng giữ test tự chứa, phù hợp style và lệnh không thêm lớp chung/refactor ngoài phạm vi. Không phải blocker clean code.
- Các ngách khởi tạo thất bại sau bind với cấu hình timeout không hợp lệ hoặc lỗi tạo thread: cấu trúc exception handling đã có nguyên ở base, diff không mở rộng nó; không có bằng chứng chúng ảnh hưởng đường default hợp lệ được giao. Không chứng nhận exception safety tổng quát cho mọi cấu hình/lỗi môi trường.
- Chạy lại focused tests, full verify, PIT hoặc 500 race ở reviewer: không được giao quyền Maven/target; bằng chứng focused đã có, coordinator sở hữu một full clean verify, PIT/500 race thuộc SHA v2 cuối.
- UI/CLI v2, Robot thật, Windows DPI/latency, H2 hai máy và merge readiness: ngoài Task 1, chưa được chứng minh bằng test headless. Không review hoặc đánh dấu PASS cho các khả năng đó.
- Tạo branch/PR, push/merge, amend/rebase để sửa BOM: không được ủy quyền; checkout feature và các ràng buộc phiên này ghi đè workflow branch chung của README.

## Ranh giới bàn giao

Không còn câu hỏi implementation material cho Task 1. Coordinator nhận gate review **PASS / APPROVED**, hai ghi nhận Minor và gate full clean verify riêng; không cần fix wave production từ review này. Chỉ file report này được ghi; không sửa source, tracked docs, index, HEAD hoặc target, không tạo helper. Review dựa trên đọc code/diff và artifact đã lưu; không tuyên bố không còn bug hoặc v2 sẵn sàng merge.
