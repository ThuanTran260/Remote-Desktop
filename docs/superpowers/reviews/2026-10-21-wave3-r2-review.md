# Review cuối đợt 3 (R2) và nghiệm thu tự động — 2026-10-21

- Reviewer: Claude Sonnet 5.5 (`claude-sonnet-5-5`), worker độc lập do Orca điều phối; không sửa production code.
- Commit được review: `7273d178673f18bfe33597bbeb492668ff350ad1` (`7273d17`), branch `feat/wave3-4-desktop-v1`, khoảng diff `3158e5b..HEAD`.
- Ngày: 2026-10-21.
- Phạm vi: Step 7 [REVIEW] của [plan đợt 3](../plans/2026-10-20-wave3-gui-and-robot.md) (dòng 726), điều kiện đạt ở dòng 728. Review `2026-10-21-wave3-acceptance-review.md` của coordinator được đọc để đối chiếu, mọi finding của nó đã tự xác minh lại (mục 4).

## 1. Kết luận

**Đợt 3 chưa đạt điều kiện ở dòng 728.** Phần tự động xanh, phần review không có finding mức High, nhưng cổng HUMAN H1/H2 (spike 3.1, thử chuột 3.4, thử bàn phím 3.5, thử giao diện 3.6) **vẫn "chưa chạy"** nên chưa ai điều khiển được hai máy thật. Review này cũng có 3 finding Medium mở (M1–M3, mục 3) cần xử lý hoặc chấp nhận có chủ đích trước khi đánh dấu đợt 3 đạt.

## 2. Nghiệm thu tự động

Môi trường: Windows 11 Pro 10.0.26200, `java -version` = 24.0.2 trên PATH (`JAVA_HOME` rỗng); `pom.xml` đặt `maven.compiler.release` = 21 nên bytecode vẫn nhắm Java 21, nhưng test chạy trên JDK 24, chưa chạy trên JDK 21 thật như CI.

1. Lệnh: `./mvnw.cmd -q verify` (chạy trong Git Bash, stdout/stderr ghi vào file log, lấy `$?` ngay sau lệnh).
   - Exit code thật: **0**.
   - Surefire (cộng từ 53 file `target/surefire-reports/TEST-*.xml`, tạo lúc 2026-10-21 07:32): **tests=524, failures=0, errors=0, skipped=0**.
   - Log chỉ có stack trace `IllegalStateException: boom` do `TickerTest.tickerKeepsRunningAfterTickableThrows` chủ ý ném và in; không phải lỗi.
2. Lệnh: `java -jar target/remote-desktop.jar --help`.
   - Exit code thật: **0**; in đúng `LaunchOptions.USAGE` (7 dòng: `--server`, `--client`, `--cmd-port`, `--img-port`, `--host`, `--help`).
   - Lưu ý: `target/remote-desktop.jar` có mtime 2026-10-20 23:30:39, trước commit `7273d17` (23:32:45) nhưng sau lần compile cuối; `SharingBar.class` trong jar giống byte-by-byte với `target/classes` (`cmp` báo giống), nên jar khớp mã nguồn HEAD. `verify` không đóng gói lại jar vì còn mới.
3. Không chạy Robot spike, không mở GUI, không smoke test jar (đã có trong review của coordinator, không lặp lại). Không process Java nào bị dừng.
4. Thực nghiệm phụ (ngoài repo, thư mục scratchpad, headless): mô phỏng khởi động kiểu `GuiApp` để xác minh M2 (mục 3).

## 3. Finding, xếp theo mức độ

Không có finding High. Chưa có bằng chứng nào cho thấy lỗi mất an toàn input hay deadlock trong phần code tự động; các invariant của 3.2 (mục 5) đều đứng vững.

### Medium

**M1 — `SharingBar` nhận focus; phím Robot đi vào thanh chia sẻ** (xác nhận finding M1 của coordinator, chỉ bằng đọc code, chưa tái hiện trên màn hình).
- Vị trí: `ui/SharingBar.java:21-38` (constructor không gọi `setFocusableWindowState(false)`/`setAutoRequestFocus(false)`), `ui/SharingBar.java:46` (`setVisible(true)`), nút *Dừng* tại `ui/SharingBar.java:29-35` là component focusable duy nhất.
- Kịch bản: Client bấm *Kết nối*, phiên ACTIVE, `SharingBar` hiện và (Windows cho phép vì tiến trình đang ở foreground) thành cửa sổ active với focus ở nút *Dừng*. Người dùng Server gõ Space/Enter ngay, chưa click vào ứng dụng nào trên máy Client: phím Robot vào `SharingBar`, kích hoạt *Dừng* → `controller.disconnect()` → phiên đóng. Các phím khác bị nuốt.
- Sửa đề xuất: trong constructor gọi `setFocusableWindowState(false)` và `setAutoRequestFocus(false)`; chuột vẫn bấm được nút. Cần ca riêng trong H2 ("gõ ngay sau khi kết nối, trước khi click").

**M2 — Có thể thoát âm thầm khi khởi động chậm (AWT auto-shutdown); đã tái hiện bằng mô phỏng headless.**
- Vị trí: `ui/GuiApp.java:21` (`invokeLater(start)` rồi `main` return), `ui/GuiApp.java:44-61` (luồng `rd-gui-init`, `setDaemon(true)` ở dòng 60), `ui/GuiApp.java:33-35` (`chooser[0].dispose()` trước khi cửa sổ vai trò mở).
- Kịch bản: với `--server`/`--client`, EDT chạy `start` rồi rảnh trong khi `rd-gui-init` (daemon) tạo `AppRuntime`/`RobotAdapterFactory`/`LocalAddresses.ipv4()`. Chưa có cửa sổ nào hiển thị và `main` đã kết thúc, nên khi EDT rảnh quá khoảng 1 giây, AWT tự tắt EDT và JVM thoát với exit 0, không thông báo. Ở chế độ chooser, cùng khoảng trống xuất hiện sau `dispose()` của chooser. `NetworkInterface` có nhiều adapter ảo (VPN, WSL, Hyper-V) hoặc Robot khởi tạo chậm đủ để chạm ngưỡng.
- Bằng chứng: chương trình thử trong scratchpad (`EventQueue.invokeLater` → luồng daemon ngủ N ms → `invokeLater` in dòng): N=300 ms in cả hai dòng, exit 0; N=3000 ms **không in gì**, exit 0 (JVM thoát trước). Đây là mô phỏng headless, chưa tái hiện với app thật; ngưỡng cụ thể phụ thuộc máy.
- Sửa đề xuất: `init.setDaemon(false)` (giữ JVM sống tới khi cửa sổ hiện), hoặc hiện cửa sổ trước rồi điền địa chỉ IP bất đồng bộ; thêm bắt `Throwable` ở `start` để lỗi trên EDT hiện hộp thoại thay vì im lặng.

**M3 — Chưa có bằng chứng thiết bị thật cho đường nhả input ở cấp cửa sổ Swing; code của 3.1 tích hợp trước cổng G0b.**
- Vị trí: toàn bộ `ui/ControlFrame.java:57-65`, `ui/ServerFrame.java:56-63`, `ui/ClientFrame.java:60-68`, `ui/GuiApp.java:81-83`; `InputCapture.java:40` (`DEFAULT_REPEAT_POLICY = SEND_ALL`, tự ghi chú "chưa kiểm chứng").
- Thực trạng: không test nào tạo `ControlFrame`/`ServerFrame`/`ClientFrame`/`SharingBar`/`GuiApp` (grep `src/test` chỉ thấy `ClientFormStateTest` nhắc `SharingBar` trong tên ca). Đây là ràng buộc của plan (headless), nhưng nghĩa là việc `windowClosing` → `disconnecting()`, `windowDeactivated` → release, đóng từng cửa sổ giữa phiên, chỉ được chứng minh qua `InputCapture` + `AppRuntime` (các test tích hợp xanh), không qua cửa sổ thật. Ngoài ra plan (dòng 23, 253) cấm tích hợp `RobotAdapterFactory` vào GUI trước gate G0b; `GuiApp.java:52` đã nối Robot thật khi spike chưa chạy (checkpoint ghi người dùng đã đồng ý).
- Hệ quả: các ca H2 6 (đóng từng cửa sổ), Alt+Tab khi giữ Ctrl, rút mạng khi giữ Ctrl là bắt buộc, không thay bằng kết quả headless.
- Sửa đề xuất: ngoài việc chạy H1/H2, tách phần nối sự kiện cửa sổ (close → disconnecting + disconnect; deactivate → windowDeactivated) thành lớp nhỏ không-Swing để test được bằng `FakeController`; ghi quyết định chấp nhận việc tích hợp sớm vào file kết quả H2.

### Low

**L1 — `Snapshot` mới mỗi lần poll nên UI áp dụng ở 20 Hz kể cả khi không đổi gì** (mở rộng L1 của coordinator, xác nhận).
- Vị trí gốc: `ui/AppRuntime.java:493-551` (mỗi `snapshot()` tạo `new Snapshot`), `ui/SnapshotPump.java:40-43` (`set` đối tượng mới mỗi 50 ms), `ui/SnapshotApplier.java:22` (chỉ so tham chiếu nên luôn khác). Hệ quả: `ui/RemotePanel.java:54` gọi `repaint()` vô điều kiện → vẽ lại ảnh 1920×1080 với nội suy bilinear 20 lần/giây trên EDT dù ảnh không đổi; `ui/ClientFrame.java:95-96` → `SharingBar.showFor` (`ui/SharingBar.java:43-46`: `pack()`, `getMaximumWindowBounds()`, `setLocation`) 20 lần/giây suốt phiên.
- Sửa đề xuất: `RemotePanel.apply` chỉ `repaint()` khi `frame` (tham chiếu), kích thước remote hoặc gate đổi; `ClientFrame` chỉ gọi `showFor` khi text đổi hoặc lần đầu hiện; hoặc để `SnapshotApplier` so sánh theo giá trị (`record` đã có `equals` trừ `frame`).

**L2 — `ServerFrame` đóng giữa phiên không gọi `capture.disconnecting()`** (finding L2 của coordinator: **đúng về sự kiện, rủi ro thực tế gần như bằng 0**).
- Vị trí: `ui/ServerFrame.java:58-62` (`closeControl()` rồi `dispose()`), `ui/ServerFrame.java:91-96`, `ui/ServerFrame.java:52` (*Ngắt* chỉ gọi `controller.disconnect()`).
- Lý do rủi ro thấp: ngay sau đó `onExit` → `shutdownAsync()` → `ServerCore.close()` đóng phiên với `SHUTDOWN` (`ServerCore.java:251-253`, gửi DISCONNECT); Client xử lý bằng `ClientSession.onClosing` → `input.endSession(generation)` (`client/ClientSession.java:446-456`) nhả phím/nút. Trường hợp *Ngắt* cũng đi qua cùng đường. Việc phía Server không có phím cục bộ nào cần nhả.
- Bất nhất còn lại: hai đường này không đi qua `InputCapture` nên bộ đếm `controlReleasesSent` không tăng; không có ảnh hưởng chức năng. Xem thêm L3.

**L3 — `RELEASE_ALL` của `disconnecting()` bị hàng đợi xóa trước khi gửi.**
- Vị trí: `ui/ControlFrame.java:58-59` (`capture.disconnecting()` rồi `controller.disconnect()`), `common/CommandWriter.java:138` (`closeGracefully` gọi `queue.clear()`), `server/ServerSession.java:449-466` (đã tăng `controlReleasesSent`).
- Kịch bản: đóng cửa sổ điều khiển khi đang giữ Ctrl: `releaseControlInput()` nhận `ACCEPTED` và tăng `controlReleasesSent`, rồi `disconnect()` xóa gói chưa ghi; Client chỉ thấy DISCONNECT(USER) và vẫn nhả đúng nhờ `endSession`. Bộ đếm Server lệch bộ đếm Client `releaseAllReceived()` trên đường này; test `releaseAllCountersAgreeAcrossEnds` chỉ phủ đường mất focus.
- Sửa đề xuất: ghi chú trong javadoc của `disconnecting()` rằng việc nhả thật do `endSession`, hoặc bỏ gọi `releaseControlInput` khi sắp `disconnect()` để bộ đếm phản ánh đúng.

**L4 — Phím vẫn đang giữ được nhấn lại khi gate/focus trở lại nhờ auto-repeat của hệ điều hành.**
- Vị trí: `ui/InputCapture.java:215-225` (`keyPressed` chấp nhận mọi sự kiện lặp khi `acceptingInput` và có gate), `ui/InputCapture.java:279-286`, `ui/InputCapture.java:74-81`.
- Kịch bản: người dùng Server giữ Ctrl qua một lần ảnh gián đoạn (epoch đổi, `held` bị xóa, Client đã nhận `RELEASE_ALL`) hoặc Alt+Tab rồi quay lại khi vẫn giữ Ctrl: sự kiện lặp tiếp theo gửi `KEY_PRESS 17` mới, Ctrl lại giữ trên Client. Plan 3.5 nói "không khôi phục phím đã giữ khi gate hoặc focus trở lại"; với chuột đã có `swallowed`, với phím thì chưa. `FIRST_PRESS_ONLY` cũng không chặn vì `heldKeys` đã xóa.
- Sửa đề xuất: giữ tập `ignoredUntilRelease` cho phím nhấn khi `loseInput`/đổi epoch, chỉ bỏ khi nhận `keyReleased` của phím đó. Hành vi hiện tại chấp nhận được nếu chủ ý; cần ghi quyết định.

**L5 — `GuiApp` bỏ qua `ShutdownReport` khi thoát.**
- Vị trí: `ui/GuiApp.java:82` (`whenComplete((report, error) -> System.exit(0))`), `ui/AppRuntime.java:734-737`.
- Kịch bản: nếu nhả input quá ngân sách 5 giây (`inputRelease == UNCONFIRMED` hoặc `FAILED`), tiến trình vẫn thoát mà không log; phím giả lập bằng Robot có thể còn ở trạng thái nhấn trong hệ điều hành sau khi JVM thoát. Hiếm, nhưng là đúng tình huống "kẹt phím" mà đợt 3 phải tránh.
- Sửa đề xuất: log loại kết quả (không log nội dung) và/hoặc hiển thị `Messages.INPUT_RELEASE_FAILED` trước khi thoát khi `report.inputRelease()` ∈ {FAILED, UNCONFIRMED}.

**L6 — Shutdown của Server không bị giới hạn bởi `shutdownBudget` tổng.**
- Vị trí: `ui/AppRuntime.java:693-708` (`c.close()` không dùng `deadline`), `server/ServerCore.java:36,243-275` (`CLOSE_WAIT_MS = 5000`), cộng với `app.awaitTermination` ở `ui/AppRuntime.java:687`.
- Kịch bản: `rd-app` kẹt trong bind + `ServerCore.close()` chờ hết 5 giây → tiến trình có thể còn sống tới khoảng 10 giây sau khi cửa sổ đã biến mất, vì `System.exit` đợi `shutdownFuture`. Phạm vi 4.1 dự kiến hoàn thiện; ghi lại để không bị sót.

**L7 — Thời gian giữ phím khi mất mạng được giới hạn bởi liveness, không phải tức thì.**
- Vị trí: `common/Timeouts.java:26-43` (`liveness` = 8 s, `closeGrace` = 500 ms), `client/ClientSession.java:446-456`.
- Đường nhả có tồn tại và đúng (liveness timeout → `close` → `onClosing` → `endSession`), nhưng khi rút mạng lúc đang giữ Ctrl, Ctrl còn nhấn trên Client tới khoảng 8–9 giây. Phù hợp thiết kế; cần H2 đo và ghi giá trị thực vào file kết quả bàn phím (ca 4).

### Thông tin (không cần sửa)

- `ui/InputCapture.java:157-167`: `mouseMoved` không kiểm tra `focusOwner`/`acceptingInput`, nên con trỏ Client vẫn di chuyển khi Server rê chuột lên cửa sổ điều khiển đang không active. Đúng với plan (chỉ wheel yêu cầu focus); lưu ý khi thử H2.
- `SharingBar` đặt giữa mép trên màn hình Client; người điều khiển có thể bấm chính nút *Dừng* của Client qua Robot. Chấp nhận được vì người dùng Client luôn có quyền dừng.

## 4. Đối chiếu finding của review coordinator

- **M1 (SharingBar focusable)**: xác nhận bằng đọc code → M1 ở đây, mức Medium. Chưa tái hiện trên màn hình (quyền foreground của Windows có thể làm cửa sổ không nhận focus khi launcher chạy ngầm), nên H2 phải kiểm.
- **L1 (`pack()`/`setLocation` mỗi 50 ms)**: xác nhận; nguyên nhân gốc rộng hơn: toàn bộ UI áp dụng ở 20 Hz vì `Snapshot` luôn là đối tượng mới → L1 ở đây.
- **L2 (đóng `ServerFrame` không gọi `disconnecting()`)**: sự kiện đúng, nhưng rủi ro bị bác bỏ ở mức thực tế (Client nhả theo `endSession` khi phiên đóng) → L2/L3 ở đây.
- Mục "Không thấy vấn đề với" của coordinator: đã tự kiểm lại mã thoát `Launcher` (0/1/2/4/`STAY_RUNNING`, `Launcher.java:25-56`), `RateSampler` chia theo thời gian thực (`RateSampler.java:36-45`), reset theo generation (`:32`), `ClosingWatch`, `LocalAddresses`: đúng. Riêng "init ngoài EDT trong `GuiApp`" đúng về EDT nhưng dẫn tới M2 (JVM có thể thoát khi init chậm).

## 5. Checklist theo Step 7 và Step 6 của Task 3.2

| Hạng mục | Kết quả | Finding |
| --- | --- | --- |
| Spec so với hành vi GUI/Robot: vai trò, `--host` chỉ điền sẵn, cổng qua `AppConfig`, mã thoát | Đạt về code (`GuiApp.java:43-61`, `ClientFrame.java:35`, `AppConfig.java:23-26`, `Launcher.java:25-56`) | M2 (thoát âm thầm) |
| Spec: thanh chia sẻ luôn thấy khi ACTIVE, đóng cửa sổ chính ≡ Dừng, chấp thuận bằng nút Kết nối | Đạt (`ClientFormState.java:28`, `ClientFrame.java:62-67`); tính năng focus của thanh có lỗi | M1 |
| Robot: hai Robot riêng, tọa độ tuyệt đối không cộng đôi, mask nút, `refresh` giữ port cũ khi lỗi, headless → `AdapterException` | Đạt (`RobotAdapterFactory.java:46-80`, `RobotInputDevice.java:96-104`, `AwtRobotPort.java:214-218`) | M3 (chưa có số đo thiết bị thật) |
| Ownership tài nguyên: `AppRuntime` sở hữu ticker, scheduler, cleanup, `InputExecutor`, `rd-app`, `ServerCore`; Swing không sở hữu | Đạt (`AppRuntime.java:139-163,223-279`, `UiBoundaryTest`) | — |
| Ownership: `forClient` lỗi adapter không để luồng mồ côi; `startServer` gán `core` trước `start()` | Đạt (`AppRuntime.java:140,254-261`) | — |
| Không side effect dưới `AppRuntime.lock` | Đạt: mọi gọi `ServerCore`/`ClientSession`/executor đều sau `synchronized` (`AppRuntime.java:330-347,375-397,427-445`) | — |
| Generation: so theo đối tượng phiên, đọc một lần | Đạt (`AppRuntime.java:301-304,317-319`) | — |
| Epoch: gate → epoch → offer trong một `atomically`; overflow đóng phiên | Đạt (`ServerSession.java:417-436`); `STALE_EPOCH` sau hồi phục | — |
| Epoch/gate trên UI: gate mang (generation, epoch) của frame đang hiện; đổi epoch xóa held | Đạt (`RemotePanel.java:42-55`, `InputCapture.java:74-81`) | L4 (nhấn lại phím đang giữ) |
| Callback phiên cũ không ghi đè phiên mới (snapshot suy ra từ đối tượng hiện tại; `watchClose` so `observedSession`) | Đạt (`AppRuntime.java:557-571`) | — |
| Đường future nhả input: không `orTimeout`/complete giả; reconnect chỉ khi terminated + released | Đạt (grep không có `orTimeout`/`completeOnTimeout`; `AppRuntime.java:360-368`; `ClientSession.java:140-143,446-456`) | — |
| Đường nhả: mất focus | Đạt: `loseInput` một lần, không phụ thuộc gate ảnh (`InputCapture.java:259-286`, `ServerSession.java:449-466`) | — |
| Đường nhả: window deactivate (Alt+Tab) | Đạt trong `InputCapture`; nối cửa sổ ở `ControlFrame.java:63-65` chưa được thử | M3 |
| Đường nhả: Ngắt/đóng cửa sổ điều khiển | Đạt (Client nhả theo `endSession`); `RELEASE_ALL` bị xóa nhưng không ảnh hưởng nhả | L3 |
| Đường nhả: mất mạng/timeout | Đạt về cơ chế (liveness → close → `endSession`); độ trễ ≈ 8 s | L7 |
| Đường nhả: tràn hàng đợi | Đạt (`ServerSession.java:421-424,449-466` → `OVERFLOW`; Client nhả khi đóng) | — |
| Đường nhả: shutdown ứng dụng | Đạt về thứ tự (đóng phiên, chờ `inputReleased`, rồi `InputExecutor.shutdownAsync`, `AppRuntime.java:714-733`); bỏ qua kết quả khi thoát | L5, L6 |
| Đường nhả: đóng từng cửa sổ (ServerFrame, ControlFrame, ClientFrame, SharingBar, chooser) | Đạt theo code: ServerFrame/ClientFrame/chooser thoát qua `exitAfterShutdown`; `SharingBar` `DO_NOTHING_ON_CLOSE`; ControlFrame ≡ Ngắt | L2, M3 |
| EDT: không I/O, không chờ future, không `Thread.sleep`, không Robot/decode/`snapshot()` trên EDT | Đạt: grep `sleep|.get()|.join(|invokeAndWait|await` trong `*Frame`, `SharingBar`, `GuiApp`, `InputCapture`, `RemotePanel` không có gọi chặn; EDT chỉ đọc `SnapshotPump.latest()` | L1 (công việc lặp vô ích) |
| EDT: các hàm `Controller` thay đổi trạng thái gọi từ EDT không chặn | Đạt: `disconnect`/`cancel` chạy hook đóng ngắn, `closeGracefully` trả future (`SessionLifecycle.java:371-390`, `CommandWriter.java:128-147`) | — |
| Task 3.2 Step 6: atomicity gate/epoch/offer | Đạt (`ServerSession.java:417-436`, test `epochCheckGateAndOfferAreOneTransaction`) | — |
| Task 3.2 Step 6: callback sau close | Đạt (như trên; `staleSessionCompletionDoesNotOverwriteNewSlot`, `oldSessionObserverNotificationDoesNotChangeNewSnapshot`) | — |
| Task 3.2 Step 6: bằng chứng review 3.2 đã chạy | Chỉ có dòng trong checkpoint ("Review R1 đã chạy", sửa ở `7eab22f`); **không có file báo cáo R1 trong repo** nên không kiểm được "không còn finding mở" bằng tài liệu | M3 (thiếu hồ sơ) |
| Log: không in mã/token/phím/ảnh; `Snapshot.toString` | Đạt (`AppRuntime.java:276,404,642` chỉ log loại exception; test `snapshotToStringNeverContainsCodeOrPeer`) | — |
| Giao diện chỉ dùng `Messages`, không đưa `CloseInfo.message` lên UI | Đạt (`AppRuntime.java:536,610,626`, `ServerFormState.java`, `ClientFormState.java`) | — |

## 6. Trạng thái điều kiện đạt (plan dòng 728)

1. `./mvnw.cmd -q verify` xanh: **đạt** (exit 0; 524 tests, 0/0/0).
2. Kết quả HUMAN 3.1, 3.4, 3.5, 3.6 đã điền: **chưa**. Đã đọc cả 4 file `docs/superpowers/reviews/2026-10-20-wave3-{spike-robot,mouse,keyboard,gui}-trial-results.md`: toàn bộ ca còn "chưa chạy" (đếm dòng: 10, 9, 6, 9); mục "Quyết định chính sách repeat" ghi "Chưa quyết định". **H1 và H2 vẫn chưa chạy**; reviewer này không điền, không suy ra kết quả.
3. Review 3.2 và 3.6 không còn finding mở: **chưa**. Review 3.6 (cả của coordinator lẫn R2 này) còn M1–M3 mở; review 3.2 không có hồ sơ trong repo.

**Verdict: đợt 3 CHƯA đạt.** Việc cần làm tiếp theo thứ tự: (a) sửa M1 và M2 (nhỏ, vài dòng), cân nhắc L1; (b) người dùng chạy H1 rồi H2 (thêm hai ca: gõ ngay sau khi kết nối; khởi động trên máy có nhiều adapter mạng ảo); (c) điền 4 file kết quả, đặt lại `DEFAULT_REPEAT_POLICY` nếu số đo yêu cầu; (d) review lại phần đã sửa.

## 7. Còn lại và giới hạn của review này

- Chỉ đọc code và chạy `verify`/`--help`; chưa mở giao diện nào, chưa chạy jar, chưa chạy Robot. M1 và M2 chưa tái hiện trên app thật; M2 chỉ có mô phỏng headless.
- Test chạy trên JDK 24 (PATH), không phải JDK 21 như CI; chưa kiểm `./mvnw -B -ntp verify` trên JDK 21.
- Không đánh giá đợt 4 (4.1–4.3 chưa bắt đầu theo checkpoint). `stash@{0}` cũ vẫn còn trong repo (không động tới).
