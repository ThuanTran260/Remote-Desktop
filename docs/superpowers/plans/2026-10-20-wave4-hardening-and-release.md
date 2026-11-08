# Đợt 4 — Kiểm thử ổn định và đóng gói Remote Desktop v1

Ngày: 2026-10-20. Trạng thái: kế hoạch đã chi tiết hóa, chưa triển khai.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) hoặc superpowers:executing-plans để thực hiện từng task. Bước dùng cú pháp checkbox (`- [ ]`). Bước gắn **[HUMAN]** cần người ngồi trước hai máy Windows thật; agent không được tự đánh dấu hoàn thành và không được thay bằng test headless hay harness hai JVM.

Phụ thuộc: [đợt 3 — GUI và Robot](2026-10-20-wave3-gui-and-robot.md) đạt gate cuối đợt. Nguồn hợp đồng: [spec](../specs/2026-10-19-remote-desktop-design.md) (mục 4.5, 5.2) và [kế hoạch tổng](2026-10-19-remote-desktop-implementation.md). Các hợp đồng C1–C8 của đợt 3 (`AppRuntime`, `Controller`, `Snapshot`, `inputEpoch`, `releaseControlInput`, điều kiện kết nối lại) là đầu vào của đợt này và không bị đổi ở đây.

## Mục tiêu và cách thực hiện

Hoàn thiện shutdown, chứng minh vận hành ổn định trên hai máy Windows trong LAN và đóng gói một JAR sử dụng được ngoài IDE. Phạm vi và các seam giữ nguyên như đợt 3; không mở rộng tính năng trong đợt này.

Thứ tự: 4.1 → 4.2 → 4.3. Thực hiện Native; mỗi task một commit sau kiểm tra, không tự push/merge. Áp dụng codebase-design và TDD qua hành vi công khai như đợt 3. Mọi phát hiện làm thay đổi hợp đồng phải cập nhật spec, test và tài liệu cùng nhau.

## Global Constraints

Toàn bộ **Global Constraints của đợt 3 áp dụng nguyên** (EDT, không side effect dưới khóa, tên luồng `rd-*`, không log mã/token/phím/ảnh, CI headless, test phần cứng opt-in ngoài `mvn verify`, latch/barrier thay `sleep`). Bổ sung cho đợt 4:

- **Ngân sách shutdown là tổng, không theo bước:** `AppConfig.shutdownBudget` (mặc định 5 giây, test dùng giá trị ngắn) tính một lần từ lúc `shutdownAsync()` được gọi đầu tiên; mọi bước chờ dùng phần còn lại. Không bước nào cộng thêm 5 giây riêng; không chờ trên EDT.
- **Mọi lời gọi chờ có giới hạn.** `Future.get()` không timeout bị cấm trong mã chạy khi shutdown và trong harness test.
- **Trung thực:** hết ngân sách mà chưa có bằng chứng thì báo `InputRelease.UNCONFIRMED`, `workersStopped=false`; không bao giờ báo "đã nhả" từ timeout.
- **Harness hai JVM:** process con luôn bị hủy trong `finally`/`@AfterEach`; cổng test là cổng 0 trên `127.0.0.1`; mã một lần chỉ đi qua pipe stdout tới process cha, không ghi file và không đi qua `System.Logger`.
- **Thang đo thời gian trong test:** quyết định timeout ∈ `[limit, limit + 600 ms]`; socket đóng và worker dừng đo riêng (ba mốc của đợt 2).
- **Gate HUMAN:** kết quả ghi vào file mẫu trong `docs/superpowers/reviews/`; ca chưa chạy giữ nguyên "chưa chạy".
- **Review cuối:** reviewer riêng, model sonnet, độc lập với lượt tự rà của implementer; chỉ ghi nhận review đã thực sự chạy.

## Gate HUMAN và bước tự động

- **[HUMAN]:** 4.1 bước thử đóng ứng dụng thật; 4.2 tầng 3 (hai máy Windows: 30 phút, 50 lần kết nối/ngắt, rút mạng, sleep/wake, DPI, mạng chậm, đo hiệu năng); 4.3 chạy JAR trên cả hai máy.
- **[AUTO]:** test shutdown và race (4.1), harness hai JVM + vòng lặp kết nối lại 50 lần + mạng chậm giả lập (4.2 tầng 1–2), script smoke JAR (4.3).
- **[REVIEW]:** review cuối toàn branch (4.3).

## Task 4.1 — Shutdown và xử lý lỗi toàn ứng dụng

**Files:**
- Modify: `src/main/java/com/ltm/rd/ui/AppRuntime.java` — hoàn thiện `shutdownAsync()`, `shutdownHook()`, `onShutdown(Runnable)`, checkpoint start/shutdown, log; mở rộng `Seams`.
- Modify: `src/main/java/com/ltm/rd/server/ServerCore.java` — thêm `close(long deadlineNanos)`.
- Create: `src/main/java/com/ltm/rd/ui/UiResources.java`
- Modify: `src/main/java/com/ltm/rd/ui/GuiApp.java` — `windowClosing` → `requestExit`, đăng ký hook JVM, `disposeOnEdt`.
- Test: `src/test/java/com/ltm/rd/server/ServerCoreCloseTest.java`, `src/test/java/com/ltm/rd/ui/{AppRuntimeShutdownTest,ShutdownRaceTest,ShutdownHookTest,ErrorSurfaceTest,LoggingHygieneTest,GuiShutdownOrderTest}.java`

**Interfaces:**
- Consumes (đã đọc code):
  - `ServerCore.close()` hiện chặn tối đa khoảng 5 giây bằng `CLOSE_WAIT_MS = 5000` và các giới hạn con 1000/3000 ms; thứ tự trong code: `setShuttingDown` → đóng `ServerSocket` → đóng phiên `SHUTDOWN` → join acceptor → `pending.closeAll()` → `shutdownNow` pool → chờ `terminated()` → ticker/cleanup/scheduler. `close()` idempotent (CAS `closed`).
  - `SessionLifecycle.close(CloseInfo)`, `socketsClosed()`, `terminated()`; `ClientSession.cancel()`, `terminated()`, `inputReleased()`, `lifecycle()`; `InputExecutor.shutdownAsync(): CompletableFuture<Void>` (từ chối begin mới, kết thúc phiên rồi dừng worker), `state()`, `lastFault()`; `Ticker.close()`.
  - Từ 3.2: `AppRuntime.shutdownAsync(): CompletableFuture<ShutdownReport>`, `ShutdownReport(boolean socketsClosed, boolean workersStopped, InputRelease inputRelease)`, `InputRelease {NOT_NEEDED, RELEASED, FAILED, UNCONFIRMED}`, `Controller`, `Messages`, `AppConfig.shutdownBudget`, `AppRuntime.Seams(hooks, connector, imageOutputDecorator, beforeServerStart, afterServerStart)`.
- Produces:

```java
// server
public void close(long deadlineNanos);     // mốc tuyệt đối theo System.nanoTime(); mọi chờ bên trong dùng phần còn lại tới mốc này
// close() == close(System.nanoTime() + 5 s), giữ hành vi cũ

// ui
public final class AppRuntime implements Controller {
    public static final Duration DEFAULT_SHUTDOWN_BUDGET = Duration.ofSeconds(5);
    public Thread shutdownHook();               // trả Thread chưa đăng ký, tên "rd-shutdown-hook"; thân: shutdownAsync() rồi get(phần ngân sách còn lại), nuốt mọi exception
    public void onShutdown(Runnable stopStep);  // bước phụ trợ (vd. SnapshotPump::close); chạy ở bước 5, mỗi bước trong try/catch riêng
    // Seams (package-private) thêm: ExecutorService cleanupOverride, ScheduledExecutorService schedulerOverride (nullable)
}
public interface UiResources { void stopTimers(); void removeListeners(); void disposeWindows(); }
// GuiApp
static void requestExit(AppRuntime runtime, UiResources resources);   // không chặn EDT; sau khi future hoàn tất → disposeOnEdt
static void disposeOnEdt(UiResources resources);                      // invokeLater; thứ tự stopTimers → removeListeners → disposeWindows
```

**Thứ tự shutdown (chạy trên `rd-shutdown`, mọi chờ dùng phần còn lại của ngân sách):**
1. *Tại thời điểm gọi, trên luồng gọi:* CAS bật `shuttingDown`; từ đây `sendInput`/`releaseControlInput`/`connect`/`startServer` trả `SHUTTING_DOWN`/`false`/lỗi (khóa nhận input); lần gọi sau trả cùng future.
2. Đóng phiên và listener: Server — `ServerCore.close(deadline)` (đóng hai `ServerSocket` trước, rồi phiên với `SHUTDOWN`); Client — slot đang chuẩn bị bị đánh dấu hủy, phiên đang chạy `close(SHUTDOWN)`.
3. Chờ `socketsClosed()` rồi `terminated()` của phiên (có giới hạn).
4. Chờ `inputReleased()` của phiên Client: thành công → `RELEASED`; hoàn tất lỗi → `FAILED`; chưa xong tới hạn → `UNCONFIRMED`; phiên chưa từng begin input hoặc vai trò Server → `NOT_NEEDED`.
5. Dừng sampler/timer/executor: các `onShutdown` step, `InputExecutor.shutdownAsync()` (chờ trong phần còn lại để biết `workersStopped`), rồi `Ticker`, `rd-cleanup-N`, `rd-close-timer`, `rd-app`.
6. Hoàn tất future bằng `ShutdownReport` (không bao giờ hoàn tất lỗi; trễ tối đa ngân sách + 600 ms).
7. *Riêng GUI, sau khi future hoàn tất:* `disposeOnEdt` — dừng `Timer`, gỡ listener, `dispose()` cửa sổ. Hook JVM không thực hiện bước 7 (EDT có thể không còn chạy).

**Quy tắc sở hữu và race:**
- Runtime gán `ServerCore` vào trường **trước** `core.start()`; trên `rd-app` có hai checkpoint: trước `start()` (đã shutdown → không bind, `core.close()`, future `startServer` thất bại `IllegalStateException("server is shutting down")`) và sau `start()` (đã shutdown → `core.close()` ngay để đóng listener/worker tạo muộn).
- `connect` trên `rd-app` kiểm tra cờ hủy/shutdown dưới khóa ngắn sau khi tạo `ClientSession` và trước `start()`; phiên đã `start()` mà chạy đua shutdown thì bước 2 đóng nó.
- **Không** tạo `InputExecutor` thay thế để vượt worker cũ đang kẹt; phân biệt ba bằng chứng: socket đã đóng (`socketsClosed()`), worker phiên đã dừng (`terminated()`), input đã nhả (`inputReleased()` hoàn tất thành công).
- Log (`System.Logger`, tên `com.ltm.rd.ui.AppRuntime`): `"shutdown begin reason=<APP_EXIT|HOOK> phase=<UiPhase> generation=<n>"`, `"shutdown end elapsedMs=<n> socketsClosed=<b> workersStopped=<b> inputRelease=<enum>"`, `"session end reason=<DisconnectReason> phase=<UiPhase> generation=<n>"`; không có mã, token, mã phím, nội dung ảnh hay `CloseInfo.message`.

- [ ] **Step 1: Viết test thất bại [AUTO]** (dùng `FakeAdapterFactory`, `FakeInputDevice.blockOn/failOn`, `FakeScreenSource.blockCaptures()`, latch/barrier; cổng thử rebind bằng `new ServerSocket(port)`)

```java
// ServerCoreCloseTest (server/)
@Test void closeWithDeadlineRespectsAbsoluteDeadline()     // lifecycle.terminated() bị giữ; close(now + 600 ms) trả về trong [600, 1200] ms; hai cổng đã đóng
@Test void closeWithPastDeadlineStillClosesPortsAndSockets() // deadline đã qua: trả về < 500 ms; cổng rebind được; socket phiên đóng
@Test void closeWithoutArgumentKeepsPreviousBehaviour()    // phiên ACTIVE đóng bình thường, trả về < 3 s, terminated() xong
// AppRuntimeShutdownTest
@Test void shutdownWithNothingRunningCompletesQuickly()    // server IDLE và client IDLE: future xong < 500 ms; report (true, true, NOT_NEEDED)
@Test void repeatedShutdownReturnsSameFuture()             // f1 == f2 == f3 (cùng instance), kể cả sau khi đã hoàn tất
@Test void shuttingDownFlagIsImmediateAndBlocksNewWork()   // ngay sau lời gọi: connect()==SHUTTING_DOWN; startServer() thất bại; sendInput(...)==SHUTTING_DOWN; releaseControlInput(...)==false
@Test void budgetIsTotalNotPerStep()                       // shutdownBudget 800 ms; client ACTIVE; capture bị giữ (worker kẹt) và keyRelease:17 bị giữ: future xong trong [800, 1400] ms; report == (socketsClosed true, workersStopped false, UNCONFIRMED)
@Test void heldCaptureDoesNotBlockSocketClose()            // capture bị giữ: session.socketsClosed() hoàn tất; report.socketsClosed()==true
@Test void releaseTimeoutNeverReportsReleased()            // gate giữ keyRelease:17; hết ngân sách → UNCONFIRMED; mở gate sau đó: executor tự nhả (device log "keyRelease:17"), report đã trả vẫn UNCONFIRMED
@Test void releaseFailureReportsFailed()                   // device.failOn("keyRelease:17") → FAILED
@Test void releaseSuccessReportsReleased()                 // keyPress:17 đang giữ → RELEASED; InputExecutor.state()==STOPPED
@Test void inputExecutorIsNeverReplacedWhileWorkerStuck()  // adapters.inputDevice() gọi đúng 1 lần; refreshInputDevice() gọi 0 lần trong shutdown; khi chưa shutdown mà release kẹt, connect()==WAITING_RELEASE và refresh không được gọi
@Test void serverRoleReportsNotNeeded()
@Test void eachPhaseShutdownLeavesNoOrphans()              // tham số hóa: Server IDLE/STARTING/LISTENING/RESERVED/ACTIVE/CLOSING; Client IDLE/PREPARING/CONNECTING/AUTHENTICATING/ATTACHING/ACTIVE/CLOSING: sau future, report.socketsClosed()==true; hai cổng rebind được; không còn luồng "rd-*" (chờ có giới hạn 5 s theo điều kiện, loại trừ chính luồng test)
@Test void cleanupExecutorStopsOnlyAfterSocketsClosed()    // schedulerOverride/cleanupOverride ghi thời điểm shutdown: sau thời điểm session.socketsClosed() hoàn tất
@Test void rejectedSchedulerStillClosesSockets()           // schedulerOverride từ chối mọi tác vụ: shutdown vẫn đóng raw socket; report.socketsClosed()==true
// ShutdownRaceTest (latch ép từng thứ tự; vòng lặp ngẫu nhiên chỉ bổ sung)
@Test void shutdownDuringBindClosesLateListener()          // beforeServerStart chặn sau khi core được gán; shutdownAsync(); mở chặn → start() chạy rồi checkpoint sau start đóng core: hai cổng rebind được, core.tickerRegistrations()==0, không còn luồng "rd-accept-cmd"/"rd-accept-img"; future startServer thất bại IllegalStateException
@Test void shutdownBeforeStartTaskRunsNeverBinds()         // rd-app đang bận tác vụ chặn; startServer() rồi shutdownAsync() trước khi tác vụ chạy: cổng không bao giờ bị bind (rebind được suốt thời gian), core đã đóng
@Test void startServerAfterShutdownFails()
@Test void connectRacingShutdownWhilePreparingNeverStarts() // newScreenSource bị giữ; shutdownAsync(); mở giữ: ClientSession không start (senderRuns 0, không luồng "rd-connect")
@Test void connectRacingShutdownAfterStartCancelsSession()  // hook ở afterAttachFlushed; shutdown chen giữa: sau future, session.terminated() xong và inputBeginCount ≤ 1
@Test void shutdownRacingNaturalSessionCloseCompletesOnce() // 1000 vòng, barrier: disconnect() cùng lúc shutdownAsync(): future xong đúng một lần, không exception
// ShutdownHookTest
@Test void hookJoinsSameFutureWhenShutdownAlreadyStarted()  // shutdownAsync() với release bị giữ; chạy shutdownHook() trên luồng khác: luồng hook còn sống tới khi future xong hoặc hết ngân sách còn lại
@Test void hookRunsFullShutdownWhenNoneStarted()            // runtime ACTIVE: hook.run() → report ok; peer (runtime kia) thấy phiên đóng < 1 s (DISCONNECT best effort hoặc EOF)
@Test void hookDoesNotNeedEdt()                             // EDT bị chặn bằng invokeLater + latch: hook vẫn xong trong ngân sách
@Test void hookNeverThrows()                                // shutdown future hoàn tất lỗi giả → run() không ném
// ErrorSurfaceTest (message hiển thị đúng chuỗi của Messages, xuất hiện trong Snapshot.message)
@Test void connectToClosedPortShowsConnectMessage()         // "Không kết nối được tới máy chủ. Kiểm tra IP, cổng và tường lửa."
@Test void wrongCodeShowsWrongCodeMessage()                 // "Mã không đúng."
@Test void lockedAfterThreeWrongCodesShowsLockedMessage()   // lần thứ tư: "Bị khóa tạm thời do nhập sai nhiều lần. Thử lại sau 30 giây."
@Test void busyServerShowsBusyMessage()                     // "Máy chủ đang có phiên khác."
@Test void livenessTimeoutShowsTimeoutMessage()             // server im lặng quá liveness: "Hết thời gian chờ phản hồi."
@Test void screenChangeShowsScreenChangedMessage()          // FakeScreenSource.setGeometry(1600,900) giữa phiên: "Cấu hình màn hình đã thay đổi. Hãy kết nối lại."
@Test void robotUnavailableOnConnectShowsRobotMessage()     // "Không tạo được Robot. Kiểm tra quyền điều khiển màn hình của hệ điều hành."
@Test void releaseFailureShowsReleaseMessageAndBlocksReconnect() // Messages.INPUT_RELEASE_FAILED; connect()==INPUT_FAULTED
@Test void unknownThrowableShowsGenericMessageWithoutDetails()   // IOException("secret-123456") → message không chứa "123456"
// LoggingHygieneTest (handler JUL gắn vào logger "com.ltm.rd", level ALL)
@Test void logsContainReasonPhaseGenerationOnly()           // một phiên đầy đủ + shutdown: có bản ghi chứa "phase=" và "generation="
@Test void logsNeverContainCodeTokenKeysOrImages()          // không bản ghi nào chứa mã 6 số đã dùng, token (hex/base64), "keyPress", "keyRelease", mã phím, "FRAME" payload
// GuiShutdownOrderTest
@Test void disposeRunsOnEdtInFixedOrder()                   // UiResources giả ghi (tên, isEventDispatchThread): [stopTimers, removeListeners, disposeWindows], tất cả true
@Test void disposeWaitsForShutdownFuture()                  // future giữ bằng latch: disposeOnEdt chưa chạy; hoàn tất → chạy
@Test void requestExitDoesNotBlockEdt()                     // gọi từ EDT khi future chưa xong: trả về < 100 ms
```

- [ ] **Step 2: Chạy, xác nhận thất bại** — `./mvnw.cmd -q test -Dtest='ServerCoreCloseTest,AppRuntimeShutdownTest,ShutdownRaceTest,ShutdownHookTest,ErrorSurfaceTest,LoggingHygieneTest,GuiShutdownOrderTest'`, Expected: COMPILATION ERROR.
- [ ] **Step 3: Implement theo Interfaces và thứ tự shutdown.**
- [ ] **Step 4: Chạy `ShutdownRaceTest` và `AppRuntimeShutdownTest` 5 lần liên tiếp + `./mvnw.cmd -q verify`** — Expected: luôn PASS.
- [ ] **Step 5: Commit** — `feat(ui): bounded idempotent shutdown with ownership checkpoints`
- [ ] **Step 6 [HUMAN] — Đóng ứng dụng thật:** trên hai máy, đóng cửa sổ chính và nhấn Ctrl+C trong console ở từng trạng thái: chưa kết nối, đang kết nối, đang chia sẻ, đang giữ phím/nút. Kiểm tra bằng `jps` rằng tiến trình thoát, `netstat -ano | findstr 5000` và `findstr 5001` không còn cổng lắng nghe, bên kia kết thúc nhờ DISCONNECT/EOF hoặc liveness 8 giây. Ghi vào mục "Shutdown" của `docs/superpowers/reviews/2026-10-20-wave4-acceptance-results.md`.

**Điều kiện đạt:** test race dùng latch/barrier, không dựa sleep ngẫu nhiên; sau khi giải phóng fake, đúng generation được xử lý và không còn tài nguyên mồ côi; trạng thái thất bại trung thực (`UNCONFIRMED`/`FAILED`, không báo nhả thành công khi thiếu bằng chứng).

## Task 4.2 — Nghiệm thu vận hành và hiệu năng

**Files:**
- Create: `src/test/java/com/ltm/rd/harness/{ChildJvm,TwoJvmHarness,HarnessServerMain,HarnessClientMain,Percentiles}.java`
- Create: `src/test/java/com/ltm/rd/harness/PercentilesTest.java`, `src/test/java/com/ltm/rd/it/TwoJvmIntegrationTest.java`
- Create: `src/test/java/com/ltm/rd/ui/{AppRuntimeReconnectLoopTest,SlowNetworkRuntimeTest}.java`
- Create: `src/test/java/com/ltm/rd/spike/LatencyReport.java` (`main`: đọc CSV mẫu độ trễ, in `n`, `p50`, `p95`, `max`; không chạy trong `mvn verify`)
- Create: `docs/superpowers/reviews/2026-10-20-wave4-acceptance-results.md` (mẫu kết quả cho cả ba tầng, mọi ca "chưa chạy")

**Ba tầng kiểm chứng:**
1. **Tầng 1 — headless CI trên Java 21 [AUTO]:** unit/integration hiện có cùng test mới; `./mvnw -B -ntp verify` trên CI, `./mvnw.cmd -q verify` trên Windows.
2. **Tầng 2 — hai JVM riêng trên một máy với fake adapters [AUTO]:** xác nhận hoạt động không phụ thuộc trạng thái dùng chung trong JVM; chạy trong `mvn verify` (không phụ thuộc Robot/Swing).
3. **Tầng 3 — hai máy Windows thật [HUMAN]:** JAR, Swing, Robot và LAN thật; ghi OS, JDK, DPI, độ phân giải và điều kiện mạng.

**Interfaces:**
- Consumes: `AppRuntime.forServer/forClient`, `Controller`, `Snapshot`, `UiPhase`, `SendResult`, `AppRuntime.Seams.imageOutputDecorator` (từ 3.2), `FakeAdapterFactory`, `ThrottledOutputStream(OutputStream)` + `setRate(long bytesPerSecond)` (`Long.MAX_VALUE` = không giới hạn) từ đợt 2, `FakeInputDevice.log()`, `FakeScreenSource`, `Timeouts`.
- Produces:

```java
// harness (test code)
final class ChildJvm implements AutoCloseable {
    static ChildJvm start(String mainClass, List<String> args) throws IOException;   // java -Djava.awt.headless=true -cp target/classes;target/test-classes (dấu phân tách theo File.pathSeparator), gộp stderr vào stdout
    Optional<String> awaitLine(Predicate<String> match, Duration timeout) throws InterruptedException;
    void send(String stdinLine);
    boolean isAlive();
    void kill();                         // destroyForcibly rồi waitFor tối đa 5 s
    @Override void close();              // gửi "QUIT", chờ ngắn, rồi kill(); idempotent
}
public final class Percentiles {
    public static long nearestRank(long[] samples, double percent);   // percent ∈ (0, 100]; rank = ceil(percent/100 * n); trên bản sao đã sắp xếp
}
// HarnessServerMain args: "--liveness-ms 2000 --frame-timeout-ms 4000 ..." (Timeouts rút ngắn)
//   stdout: "READY cmd=<port> img=<port>", "CODE <6 digits>" (mỗi lần vào LISTENING), "PHASE <UiPhase>", "FRAMES <n>", "SENT <SendResult>"
//   stdin : "KEY <vk>", "DISCONNECT", "QUIT"
// HarnessClientMain args: "<host> <cmdPort> <imgPort>" + dòng stdin đầu tiên là mã
//   stdout: "PHASE <UiPhase>", "DEVICE <mục log FakeInputDevice>", "RELEASED"
//   stdin : "CANCEL", "QUIT"
```

### Ma trận nghiệm thu

- **Hoạt động liên tục 30 phút.** [HUMAN] ACTIVE 30 phút: Capture và điều khiển duy trì, không freeze; heap sau GC (`jcmd <pid> GC.run` rồi `jcmd <pid> GC.heap_info`) ghi mỗi 5 phút, số luồng `rd-*` ổn định. [AUTO] không thay thế; tầng 1 chỉ có vòng lặp ngắn bên dưới.
- **Kết nối/ngắt 50 lần.** [AUTO] `reconnectFiftyTimesThroughRuntimeReturnsToBaseline`, `alternatingDisconnectAndStopFifty`. [HUMAN] 50 lần xen kẽ *Dừng* (Client) và *Ngắt* (Server) trên hai máy; mỗi 10 lần ghi số luồng `rd-*` (`jcmd <pid> Thread.print | findstr "rd-"`), số socket (`netstat -ano | findstr <pid>`), heap sau GC; không kẹt phím; heap không tăng đều.
- **Cắt mạng khi giữ Ctrl hoặc kéo chuột.** [AUTO] `networkDropWhileCtrlHeldReleasesOnClient` (3.5) và `killingServerProcessMakesClientReleaseHeldKey` (tầng 2). [HUMAN] rút cáp/tắt Wi-Fi lúc nhập mã, lúc vừa xác thực xong, lúc đang kéo chuột: gate đóng, input nhả, kết nối lại không phục hồi held state cũ.
- **Sleep/wake và thay DPI.** [HUMAN] sleep Client giữa phiên: Server đóng phiên trong khoảng 8–15 giây; Client thức dậy tự đóng phiên cũ, không kẹt phím, kết nối lại được. Đổi scaling trên Client giữa phiên: `SCREEN_CHANGED` với thông báo rõ; tạo lại Robot khi kết nối lại (README yêu cầu khởi động lại Client sau khi đổi scaling). [AUTO] chỉ phần logic `screenChangeShowsScreenChangedMessage`.
- **Mất focus, minimize, chuyển cửa sổ.** [AUTO] 3.5. [HUMAN] Alt+Tab, thu nhỏ/mở lại cửa sổ điều khiển, click ngoài: không gửi input ngoài điều kiện, không kẹt modifier/nút.
- **Mạng chậm.** [AUTO] `SlowNetworkRuntimeTest` (throttle giả lập). [HUMAN] Wi-Fi yếu hoặc `clumsy` (độ trễ + mất gói): ghi FPS, RTT, thời điểm khóa/mở điều khiển; không thực thi input epoch cũ.
- **Đóng app ở mọi phase.** [AUTO] `eachPhaseShutdownLeavesNoOrphans` (4.1). [HUMAN] bước 6 của 4.1.

### Số đo hiệu năng

- Ghi CPU (`typeperf "\Process(java*)\% Processor Time" -si 10 -sc 180`), heap (`jcmd <pid> GC.heap_info`), FPS và KiB/s (từ giao diện), RTT, và **độ trễ input-to-frame** p50/p95. RTT không thay cho input-to-frame.
- **Cách đo input-to-frame:** quay video ≥ 60 khung/giây chứa đồng thời thao tác trên Server (nhấn phím/click, có chỉ báo thấy được) và vùng ảnh phản hồi; đếm số khung giữa hai sự kiện; tối thiểu 100 mẫu cho mỗi loại thao tác (click nút có hiệu ứng rõ; gõ một ký tự trong Notepad); ghi mỗi mẫu một dòng `millis` vào file CSV cạnh file kết quả; `java -cp target/classes;target/test-classes com.ltm.rd.spike.LatencyReport <file.csv>` in `n`, `p50`, `p95`, `max`. Ghi máy, mạng, độ phân giải, DPI, JDK, số mẫu, loại thao tác.
- Mục tiêu trên LAN tham chiếu: 10–12 FPS, input-to-frame p95 dưới 150 ms. Giữ mặc định 12 FPS, rộng tối đa 1280, JPEG 0.6 và timeout hiện có khi đo lần đầu. Nếu không đạt, xác định nút thắt (capture, nén, băng thông, decode) và sửa hoặc trình bày giới hạn có bằng chứng; không âm thầm hạ tiêu chí.
- Throttle mạng trong test phải có căn cứ: tốc độ `max(1, ceil(S / (1.5 × frameStall.seconds)))` với `S` là byte mỗi frame đo được (Δ `imgBytesOut` / Δ `frameCount` trên 10 frame ổn định trước khi bật throttle); chỉ bật **sau** `ACTIVE`; hủy được bằng `setRate(Long.MAX_VALUE)`; không làm hỏng handshake.

- [ ] **Step 1: Viết test thất bại [AUTO]**

```java
// PercentilesTest
@Test void nearestRankOnOneToHundred()                      // samples 1..100: p50 == 50, p95 == 95, p100 == 100
@Test void nearestRankSmallSample()                         // {10,20,30,40,50}: p50 == 30 (rank ceil(2.5)=3), p95 == 50
@Test void unsortedInputIsNotMutated()
@Test void emptyOrInvalidPercentThrows()                    // [] → IllegalArgumentException; percent 0 và 101 → IllegalArgumentException
// TwoJvmIntegrationTest (tầng 2; timeout test 90 s; ChildJvm luôn bị hủy trong @AfterEach)
@Test void twoProcessesReachActiveAndExchangeInput()        // server con in READY; client con kết nối; server nhận FRAMES >= 1; "KEY 65" → server in "SENT SENT"; client in "DEVICE keyPress:65" trong 15 s
@Test void killingClientProcessFreesServerSlotAndChangesCode() // kill client: server in "PHASE LISTENING" trong liveness + 6 s, kèm dòng "CODE <mã mới>" khác mã cũ; client con mới dùng mã mới kết nối được
@Test void killingServerProcessMakesClientReleaseHeldKey()  // "KEY 17" → client "DEVICE keyPress:17"; kill server → client in "DEVICE keyRelease:17" rồi "RELEASED" trong 15 s
@Test void reconnectTenTimesBetweenProcesses()              // 10 vòng: client con kết nối → ACTIVE → CANCEL → server "PHASE LISTENING"; mỗi vòng mã khác vòng trước
@Test void childProcessesAreAlwaysDestroyed()               // sau close(): ProcessHandle của cả hai con không còn sống trong 5 s
// AppRuntimeReconnectLoopTest (tầng 1; hai runtime cùng JVM)
@Test void reconnectFiftyTimesThroughRuntimeReturnsToBaseline() // 50× connect→ACTIVE→disconnect→LISTENING/IDLE: số luồng "rd-*" bằng baseline (chờ có giới hạn 5 s), serverCore.tickerRegistrations()==baseline, client runtime tickerRegistrations()==baseline, InputExecutor.state()==IDLE, adapters.inputDevice() gọi 1 lần
@Test void alternatingDisconnectAndStopFifty()              // 25 lần Server.disconnect(), 25 lần Client.disconnect() xen kẽ: lần nào cũng kết nối lại được; không phím kẹt (device log mọi keyPress có keyRelease tương ứng)
@Test void eachCycleUsesNewGenerationAndNewCode()           // generation server tăng 1..50, mã mỗi vòng khác vòng trước, generation client tăng 1..50
// SlowNetworkRuntimeTest (tầng 1; ThrottledOutputStream qua Seams.imageOutputDecorator)
@Test void slowLinkClosesGateOnceReleasesOnceAndRecovers()  // controlEnabled true → bật throttle theo công thức trên cho frame N → server snapshot controlEnabled false, inputEpoch = e+1; sendInput(gen, e, key) == GATE_CLOSED; setRate(MAX) → controlEnabled true, inputEpoch = e+2; sendInput(gen, e, key) == STALE_EPOCH; serverSession.releaseAllSent()==1 và client.releaseAllReceived()==1; phiên vẫn ACTIVE
@Test void throttleIsOffDuringHandshake()                   // decorator cài từ đầu với rate MAX tới khi ACTIVE; phiên đạt ACTIVE
@Test void heldKeyIsReleasedByStall()                       // keyPress(17) trước khi throttle; sau STALL_START device log có "keyRelease:17" đúng một lần
```

- [ ] **Step 2: Chạy, xác nhận thất bại** — `./mvnw.cmd -q test -Dtest='PercentilesTest,TwoJvmIntegrationTest,AppRuntimeReconnectLoopTest,SlowNetworkRuntimeTest'`, Expected: COMPILATION ERROR.
- [ ] **Step 3: Implement harness, `LatencyReport` và test theo Interfaces.**
- [ ] **Step 4: Chạy `TwoJvmIntegrationTest`, `AppRuntimeReconnectLoopTest`, `SlowNetworkRuntimeTest` 3 lần liên tiếp + `./mvnw.cmd -q verify`** — Expected: luôn PASS; tổng thời gian tăng không quá 2 phút.
- [ ] **Step 5: Commit** — `test: two-jvm harness, reconnect loop and slow-link scenarios`
- [ ] **Step 6 [HUMAN] — Tầng 3 trên hai máy Windows thật**, theo checklist (tick từng ca trong file kết quả, ghi ngày/giờ và người thực hiện):
  1. Ghi cấu hình: OS, JDK, độ phân giải, DPI, loại mạng (LAN có dây/Wi-Fi), phiên bản commit (`git rev-parse --short HEAD`).
  2. Chạy ma trận nghiệm thu ở trên, từng ca; ca nào không chạy được ghi "chưa kiểm chứng" kèm lý do.
  3. 30 phút liên tục; 50 lần kết nối/ngắt (chụp số liệu mỗi 10 lần).
  4. Mục 5.2 của spec: cổng bị chiếm, hai Client đồng thời, không attach, token sai, rút mạng, Ngắt/Dừng đồng thời, nối lại ngay, không tạo được Robot, resize, thu nhỏ, đổi DPI, key repeat, Hủy tới IP không tồn tại, click đầu chỉ lấy focus.
  5. Đo hiệu năng và input-to-frame (≥ 100 mẫu/loại) bằng `LatencyReport`; lưu CSV cạnh file kết quả.
  6. Điền `docs/superpowers/reviews/2026-10-20-wave4-acceptance-results.md` cho cả ba tầng (tầng 1: số test và lệnh đã chạy; tầng 2: kết quả `TwoJvmIntegrationTest`; tầng 3: ma trận).

### Điều kiện đạt

Có báo cáo kết quả thực tế cho cả ba tầng; mọi ca chưa chạy đánh dấu "chưa kiểm chứng". Không dùng số lượng test xanh thay cho chứng cứ ổn định phần cứng. Lỗi kẹt input, sai generation, rò tài nguyên hoặc UI freeze phải được sửa và chạy lại ca liên quan (cả test tự động tương ứng) trước phát hành.

## Task 4.3 — Đóng gói, tài liệu và review phát hành

**Files:**
- Create: `scripts/smoke-jar.sh` (CI/Linux), `scripts/smoke-jar.cmd` (Windows)
- Modify: `.github/workflows/ci.yml` — thêm bước chạy `scripts/smoke-jar.sh` sau `./mvnw -B -ntp verify` (verify đã đóng gói `target/remote-desktop.jar`)
- Modify: `README.md` (trạng thái, cách chạy, firewall, giới hạn, ma trận, đo hiệu năng)
- Modify: `docs/superpowers/plans/2026-10-19-remote-desktop-implementation.md` (cập nhật trạng thái task kèm đường dẫn bằng chứng)
- Create: `docs/superpowers/reviews/2026-10-20-wave4-release-jar-results.md` (mẫu kết quả chạy JAR trên hai máy), `docs/superpowers/reviews/2026-10-20-wave4-final-review.md` (mẫu checklist review cuối)
- Không sửa `pom.xml`: `finalName` `remote-desktop`, `maven-jar-plugin` với `mainClass` `com.ltm.rd.Launcher` đã có; dự án không có dependency runtime nên JAR chạy một mình.

**Interfaces:**
- Consumes: `Launcher.run(...)` và mã thoát từ 3.6 (`0` help, `2` tham số sai, `4` cần GUI nhưng headless), `LaunchOptions.USAGE` (dòng đầu `Usage: java -jar remote-desktop.jar [--server | --client] [options]`), manifest hiện có.
- Produces: hai script smoke với các phép kiểm sau (mỗi phép in `PASS <tên>` hoặc `FAIL <tên>` và script thoát khác 0 nếu có `FAIL`):

```text
help-exits-zero-and-prints-usage      java -jar target/remote-desktop.jar --help           → exit 0, stdout chứa "Usage: java -jar remote-desktop.jar"
invalid-option-exits-two              java -jar target/remote-desktop.jar --bogus          → exit 2, stderr dòng đầu "Unknown option: --bogus"
headless-server-exits-four            java -Djava.awt.headless=true -jar … --server        → exit 4, stderr chứa "graphical environment required"
headless-chooser-exits-four           java -Djava.awt.headless=true -jar …                 → exit 4
manifest-main-class                   unzip/jar: Main-Class: com.ltm.rd.Launcher
jar-has-only-project-classes          mọi entry bắt đầu bằng com/ltm/rd/ hoặc META-INF/
```

**Hành vi và tài liệu:**
- JAR chạy với JDK >= 21, không cần IDE/Maven ở máy sử dụng; JDK thấp hơn 21 sẽ báo `UnsupportedClassVersionError` của JVM (README nêu rõ yêu cầu JDK >= 21 và cách kiểm tra `java -version`).
- README: vai trò Server/Client; lệnh chạy `--help`, `--server`, `--client`, chooser khi không tham số; cổng 5000/5001 và quy tắc Windows Firewall (ví dụ `netsh advfirewall firewall add rule name="Remote Desktop LTM" dir=in action=allow protocol=TCP localport=5000,5001`); chỉ dùng trong LAN tin cậy (không mã hóa, mã 6 số một lần); bấm *Kết nối* là đồng ý chia sẻ; dừng khẩn bằng nút *Dừng*/*Ngắt* hoặc đóng cửa sổ; click đầu vào cửa sổ điều khiển chỉ lấy focus; đổi scaling thì khởi động lại Client; không điều khiển UAC/lock screen; ma trận OS/JDK/DPI đã thử (lấy từ file kết quả); lệnh build/test (`./mvnw.cmd -q verify`, `./mvnw.cmd -q package`); kết quả nghiệm thu và cách đo hiệu năng (trỏ tới file 4.2); hạn chế còn lại. Xóa/cập nhật các đoạn còn ghi "Chưa có giao diện Swing, Robot thật, launcher chạy thật".
- Kế hoạch tổng: đánh dấu hoàn thành chỉ những task có bằng chứng; **không** đánh dấu spike 3.1 hoặc nghiệm thu LAN hoàn thành nếu mới chạy fake.

- [ ] **Step 1: Viết script smoke thất bại [AUTO]** — chạy `scripts/smoke-jar.sh` trước khi có launcher thật hoặc khi chưa `package` phải thất bại (kiểm chứng script biết fail): Expected: `FAIL` kèm mã thoát khác 0 nếu thiếu `target/remote-desktop.jar`.
- [ ] **Step 2: Build và chạy smoke** — `./mvnw.cmd -q verify` rồi `scripts/smoke-jar.cmd` (Windows) và `scripts/smoke-jar.sh` (CI/Linux): Expected: 6 dòng `PASS`.
- [ ] **Step 3: Cập nhật README và kế hoạch tổng theo mục "Hành vi và tài liệu"**; grep README không còn câu "Chưa có" cho chức năng đã làm.
- [ ] **Step 4: Commit** — `build: jar smoke checks, ci step and release documentation`
- [ ] **Step 5 [HUMAN] — Chạy JAR trên cả hai máy:** chép **chỉ** `target/remote-desktop.jar` sang từng máy (không IDE/Maven); trên mỗi máy chạy `java -jar remote-desktop.jar --help`, không tham số (chooser), `--server`, `--client --host <IP>`; hoàn thành các luồng chính: kết nối, điều khiển chuột/bàn phím, Dừng/Ngắt, kết nối lại, đóng cửa sổ. Điền `docs/superpowers/reviews/2026-10-20-wave4-release-jar-results.md` (phiên bản JDK, OS, ngày, kết quả từng luồng).
- [ ] **Step 6 [REVIEW] — Review cuối riêng biệt:** reviewer độc lập (model sonnet theo cấu hình người dùng) đối chiếu toàn branch với spec và các invariant vòng đời/input (generation/epoch, nhả input, ownership, shutdown, EDT); ghi vào `docs/superpowers/reviews/2026-10-20-wave4-final-review.md` chỉ khi review đã thực sự chạy; xử lý finding trước khi coi là đạt.

### Điều kiện phát hành

- `./mvnw.cmd -q verify` xanh và CI Java 21 xanh (gồm bước smoke JAR).
- JAR chạy được trên cả hai máy thử nghiệm với các luồng sử dụng chính (file kết quả đã điền).
- Báo cáo 4.2 đầy đủ, các giới hạn công bố rõ.
- Không còn finding chưa xử lý về kẹt input, sai generation/epoch, rò tài nguyên hoặc UI freeze.
- Có checklist và bằng chứng review cuối; không tự push, merge hoặc công bố bản phát hành chỉ vì task đóng gói đã xong.

## Rà tính nhất quán của kế hoạch

- Tổng phần việc còn lại: 9 task, gồm 6 task đợt 3 và 3 task đợt 4.
- Gate phần cứng nằm trước tích hợp GUI; gate ổn định nằm trước phát hành.
- Timeout shutdown là ngân sách chờ, không phải bằng chứng Robot đã nhả phím (`UNCONFIRMED`).
- Ownership start/shutdown ngăn tài nguyên tạo muộn bị bỏ quên (checkpoint trước/sau `core.start()`).
- Kiểm thử hai JVM và hai máy bổ sung cho baseline cùng JVM, không thay bằng suy luận.
- Baseline 334 test chỉ chứng minh trạng thái trước các đợt này; mọi số đo và bảo đảm mới cần bằng chứng triển khai.

## Nhật ký chi tiết hóa

Đối chiếu kế hoạch cũ với code ở `HEAD 3158e5b` và cách giải quyết:

1. **`ServerCore.close()` tự chặn tới ~5 giây** (`CLOSE_WAIT_MS = 5000`, giới hạn con 1000/3000 ms) trong khi kế hoạch cũ yêu cầu tổng 5 giây cho cả shutdown. Nếu runtime gọi `close()` rồi chờ tiếp sẽ vượt ngân sách. Giải quyết: thêm `ServerCore.close(long deadlineNanos)` dùng mốc tuyệt đối chung; `close()` không tham số giữ nguyên hành vi.
2. **Kế hoạch cũ nói `AppRuntime.shutdownAsync()` trả "cùng future"** nhưng 3.2 cần báo trung thực kết quả nhả input. Giải quyết: future mang `ShutdownReport` (không bao giờ hoàn tất lỗi), `InputRelease.UNCONFIRMED` khi hết ngân sách.
3. **`InputExecutor.shutdownAsync()` chỉ hoàn tất sau khi worker xử lý xong END**; worker kẹt trong `InputDevice` sẽ không hoàn tất. Giải quyết: shutdown chờ nó trong phần ngân sách còn lại và báo `workersStopped=false` thay vì tạo executor mới.
4. **`ServerCore.start()` lỗi để lại core không dùng lại được** (`started=true`). Giải quyết ở 3.2 (core mới mỗi lần thử); 4.1 thêm checkpoint để core tạo muộn luôn bị đóng.
5. **Cleanup executor/scheduler của Client thuộc runtime**, còn `SessionLifecycle` cần chúng đến khi socket đóng. Giải quyết: dừng chúng ở bước 5 sau `socketsClosed()`; `Seams` cho phép test chèn executor từ chối để chứng minh raw socket vẫn đóng (hành vi đã có ở `SessionLifecycle`, đợt 2).
6. **Surefire fork và đường dẫn classpath:** harness hai JVM không dùng `java.class.path` (có thể là JAR manifest của Surefire); dùng `target/classes` và `target/test-classes` theo thư mục làm việc của Maven.
7. **CI hiện chỉ chạy `./mvnw -B -ntp verify`** nên chưa kiểm JAR. Giải quyết: thêm bước chạy `scripts/smoke-jar.sh` sau `verify`; không đổi `pom.xml` (không thêm Failsafe).
8. **JDK thấp hơn 21** không thể in thông báo thân thiện từ `Launcher` vì class đã biên dịch với `--release 21`. Giải quyết: README nêu yêu cầu JDK >= 21; script smoke không giả lập JDK cũ.
