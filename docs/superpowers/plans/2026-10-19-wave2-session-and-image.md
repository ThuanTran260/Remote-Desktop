# Đợt 2: Phiên, bắt tay hai kênh và luồng ảnh — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hai đầu socket trên `127.0.0.1` (cùng JVM trong test) bắt tay qua hai kênh TCP, xác thực bằng mã, truyền khung JPEG và thao tác chuột/phím end-to-end, dùng `ScreenSource`/`InputDevice` giả. Chưa có Robot, Swing, hay harness hai JVM (để Task 8 của plan tổng, khi launcher chạy thật).

**Architecture:** Mỗi phiên có một `SessionLifecycle` giữ **khóa private**, phase, socket đã đăng ký, worker và thủ tục đóng hai giai đoạn. Mọi thay đổi trạng thái phiên đi qua `lifecycle.atomically(tx -> …)`; side effect (hook, đóng, khởi động luồng, callback) được hoãn bằng `tx.after(...)` và chỉ chạy khi lớp khóa ngoài cùng đã nhả. Mọi deadline là `Tickable` theo mẫu "quyết định nguyên tử dưới khóa riêng, hành động ngoài khóa", được `Ticker` gọi với độ phân giải danh nghĩa 100 ms; test gọi `tick(fakeNow)` trực tiếp.

**Tech Stack:** Java 21 (`--release 21`), Maven Wrapper, JUnit 5.10.3, `java.net.Socket`, `javax.imageio`. Không thêm dependency runtime.

**Spec:** [docs/superpowers/specs/2026-10-19-remote-desktop-design.md](../specs/2026-10-19-remote-desktop-design.md) — mục 1.3–1.9, 2.2–2.5, 3.1. Plan tổng: [2026-10-19-remote-desktop-implementation.md](2026-10-19-remote-desktop-implementation.md) (Task 4, Task 5).

## Global Constraints

- Build: `./mvnw verify` xanh sau mỗi task; CI chạy JDK 21.
- Package `com.ltm.rd.{common,server,client}`. Code, comment, commit message tiếng Anh; Conventional Commits.
- Không thêm dependency runtime; không dùng Java Object Serialization.
- Luồng nền: daemon, tên bắt đầu `rd-` (`rd-accept-cmd`, `rd-accept-img`, `rd-handshake-N`, `rd-cmd-reader`, `rd-cmd-writer-N`, `rd-frame-receiver`, `rd-screen-sender`, `rd-ticker`, `rd-cleanup`, `rd-connect`). Worker của phiên chỉ được tạo qua `lifecycle.startWorker`.
- **Không có side effect khi đang giữ bất kỳ khóa nào**: không I/O, không gọi `InputDevice`, không complete future, không chạy hook/callback. Ngoại lệ được phép (non-blocking, không callback): `CommandWriter.offer`, `InputExecutor.beginSession/submit/endSession`, `register(Socket)`. Thứ tự khóa: manager → session → queue/input.
- **Hợp đồng `atomically`:** là critical section, không rollback. `Tx` chỉ hợp lệ trong callback hiện tại (không lưu, không chuyển luồng; dùng ngoài callback → `IllegalStateException`). Body ném exception → nhả khóa trong `finally`, đánh dấu `close(PROTOCOL_ERROR "internal error")`, vẫn xả mọi action đã đăng ký rồi ném lại. Mỗi action chạy trong `try/catch` riêng; action lỗi không chặn action sau. Worker chỉ khởi động qua `lifecycle.startWorker` (tự kiểm tra lại trạng thái), không bao giờ `tx.after(writer::start)`.
- **Khóa manager:** không gọi `atomically` của phiên khi đang giữ khóa manager. `SessionFactory.create` chỉ dựng đối tượng chưa chạy; đăng ký ticker và khởi động làm sau khi rời khóa manager. `SessionManager.shuttingDown()` đọc cờ `volatile`, không lấy khóa.
- **Ba mốc thời gian đo riêng:** (a) quyết định timeout ∈ `[limit, limit + 600 ms]` tính từ lúc bắt đầu operation; (b) socket đóng (`socketsClosed()`) ≤ (a) + `closeGrace` + 600 ms; (c) worker dừng đo qua `terminated()` với giới hạn chờ riêng (5 s). Không dùng chung một khoảng cho cả ba.
- Test race: luôn có test dùng latch ép từng thứ tự quan trọng; vòng lặp 10.000 lần ngẫu nhiên chỉ là bổ sung.
- Mọi vòng lặp luồng nền bắt `Throwable` (`ProtocolException` là unchecked) và chuyển thành đóng phiên.
- Thời gian lấy từ `LongSupplier nanoClock` được inject (mặc định `System::nanoTime`).
- Giá trị mặc định (spec): bắt tay 3 s từ `accept()`; RESERVED 5 s; Client chờ AUTH_RESULT 5 s, chờ SESSION_ACTIVE 7 s; PING 2 s; liveness 8 s; `soTimeout` lệnh 10 s, ảnh 20 s; ảnh gián đoạn 3 s, frame timeout 15 s; watchdog ghi lệnh 5 s, ảnh 10 s; close grace 500 ms; connect 3 s; chờ sau mã sai 1 s; khóa IP 30 s sau 3 lần sai; bảng IP tối đa 1024 entry; pool bắt tay 4 + hàng đợi 8; tick 100 ms; 12 FPS; JPEG 0.6; rộng tối đa 1280; `SO_SNDBUF` ảnh 64 KiB.
- **Độ trễ deadline:** độ phân giải danh nghĩa 100 ms cộng trễ lập lịch của JVM/OS; không hứa trễ tối đa. Không bao giờ đóng **trước** deadline.
- **Tiêu chí thời gian trong test:** unit test (fake clock) phải chứng minh `tick(deadline - 1)` không kích hoạt và `tick(deadline)` kích hoạt. Integration test assert trong khoảng `[deadline, deadline + 600 ms]`.
- Không log mã xác thực, token, nội dung ảnh hay phím. Dùng `System.Logger`.
- Test: cổng 0 trên `127.0.0.1`. Đồng bộ bằng latch/barrier có giới hạn chờ; không dùng `sleep` để quyết định đúng/sai. Integration test dùng `Timeouts` rút ngắn.
- Chưa tạo `RobotInputDevice`/`RobotScreenSource` (chờ spike G0b).

## Review Focus

1. **Server không chạy / sai IP:** Client nhận `ConnectException` → đóng phiên với lý do rõ, `inputReleased()` hoàn tất, không treo. Test ở Task 5.
2. **Bên kia đóng kênh lệnh (EOF) khi ACTIVE:** phiên đóng ngay, không chờ hết liveness. Test ở Task 5 và Task 7.
3. **Byte rác trên cổng ảnh:** chỉ socket đó bị đóng; phiên đang chạy không bị ảnh hưởng và token không bị tiêu. Test ở Task 4.
4. **Kết nối lại ngay bằng mã cũ:** nhận `WRONG_CODE`; mã mới vào được; callback phiên cũ không động tới phiên mới. Test ở Task 5.
5. **FRAME vượt giới hạn, sai tỷ lệ so với SCREEN_INFO, hoặc khai báo kích thước không khớp JPEG:** đóng `PROTOCOL_ERROR` và không gọi giải mã toàn bộ. (Thu nhỏ 1920×1080 → 1280×720 là **hợp lệ**.) Test ở Task 6 và Task 7.

---

## File Structure

**common/**
- `Timeouts.java`; `Tickable.java`, `Ticker.java`.
- `WriteTracker.java` — trạng thái ghi nguyên tử (thay cho đọc rời `writeStartedAtNanos`/`writeOperationId`); `WriteWatchdog.java`; `WorkerStarter.java`.
- `Heartbeat.java`.
- `CloseInfo.java`, `ClosedSessionException.java`, `SessionLifecycle.java`.
- Modify: `CommandWriter.java` (dùng `WriteTracker`), `Channel.java` (thêm overload có decorator).

**server/** — `ServerPhase`, `AuthGuard`, `ReserveResult`, `SessionManager`, `PendingConnection`, `PendingConnections`, `HandshakeHandler`, `ServerSession`, `ServerConfig`, `ServerCore`; Task 6–7: `FrameDecoder`, `FrameReceiver`, `FrameMonitor`.

**client/** — `ClientPhase`, `ClientConfig`, `SocketConnector`, `ClientHooks`, `ScreenGeometry`, `ScreenSource`, `CommandReceiver`, `ClientSession`; Task 6–7: `FrameEncoder`, `ScreenSender`.

**test/** — `common/FakeClock`; `client/FakeScreenSource`; `FakeInputDevice` đổi thành `public`; `it/ThrottledOutputStream`, `it/*IntegrationTest`.

**Thứ tự phụ thuộc:** Task 1 → Task 2 → Task 3 → Task 4 → Task 5 ; {Task 5, Task 6} → Task 7. Task 6 chỉ dùng code đợt 1 (`Protocol`, `Validation`, `Packet`) nên chạy song song được với Task 1–5.

---

### Task 1: Timeouts, Ticker, WriteTracker, watchdog và heartbeat

**Files:**
- Create: `src/main/java/com/ltm/rd/common/{Timeouts,Tickable,Ticker,WriteTracker,WriteWatchdog,Heartbeat}.java`
- Create: `src/main/java/com/ltm/rd/common/WorkerStarter.java`
- Modify: `src/main/java/com/ltm/rd/common/CommandWriter.java` — ghi qua `WriteTracker`; giữ `writeStartedAtNanos()`/`writeOperationId()` làm view chỉ đọc (test đợt 1 vẫn xanh); thêm `WriteTracker tracker()`; thêm overload `boolean start(WorkerStarter starter)` (giữ `start()` cũ).
- Test: `src/test/java/com/ltm/rd/common/{FakeClock,TickerTest,WriteTrackerTest,WriteWatchdogTest,HeartbeatTest}.java`

**Interfaces:**
- Consumes: `CommandWriter.offer(Packet) → OfferResult`; `Packet.ping(int)`, `Packet.pong(int)`, `Packet.seq()`.
- Produces:
  - `record Timeouts(Duration handshake, Duration reserve, Duration clientAuth, Duration clientActivation, Duration pingInterval, Duration liveness, Duration cmdSoTimeout, Duration frameStall, Duration frameTimeout, Duration imgSoTimeout, Duration cmdWriteLimit, Duration imgWriteLimit, Duration closeGrace, Duration connect, Duration wrongCodeDelay, Duration ipLock, Duration tick)` + `static final Timeouts DEFAULTS` (giá trị ở Global Constraints).
  - `interface Tickable { void tick(long nowNanos); }`
  - `final class Ticker implements AutoCloseable` — `Ticker(Duration period, LongSupplier nanoClock)`; `AutoCloseable register(Tickable t)`; `int registrations()` (cho test baseline); `void close()`. Luồng `rd-ticker`; exception của một tickable được log, không dừng tickable khác hay lần tick sau.
  - `interface WorkerStarter { boolean start(String name, Runnable body); }` — `SessionLifecycle::startWorker` thỏa interface này. `CommandWriter.start(WorkerStarter)` gọi `starter.start("rd-cmd-writer-N", this::run)`; trả `false` → writer coi như đã dừng (`offer` trả `CLOSED`).
  - `final class WriteTracker` — trạng thái `IDLE | WRITING(op, startedAt) | EXPIRED(op)` trong một `AtomicReference`; **`EXPIRED` là trạng thái cuối**:
    - `long begin(long nowNanos)` — chỉ hợp lệ từ `IDLE` → op mới (≥ 1), chuyển sang `WRITING`; đang `EXPIRED` → trả `WriteTracker.EXPIRED_OP` (`-1`), writer phải ngừng gửi; đang `WRITING` → `IllegalStateException` (lỗi lập trình).
    - `boolean end(long op)` — CAS `WRITING(op) → IDLE`; `false` nếu op đã bị `EXPIRED`. Gọi trong `finally` ở cả `CommandWriter` và `ScreenSender`.
    - `boolean tryExpire(long nowNanos, Duration limit)` — CAS `WRITING(op, s) → EXPIRED(op)` chỉ khi `now - s >= limit`; `true` đúng một lần trong đời tracker.
    - `boolean isExpired()`, `long writeStartedAtNanos()`, `long writeOperationId()` (view).
  - `final class WriteWatchdog implements Tickable` — `WriteWatchdog(WriteTracker tracker, Duration limit, Runnable onStuck)`; gọi `onStuck` ngoài mọi khóa, tối đa một lần, chỉ khi `tryExpire` thắng.
  - `final class Heartbeat implements Tickable` — khóa private; `Heartbeat(CommandWriter writer, Duration interval, Duration liveness, long startNanos, Runnable onTimeout)`; `boolean onPacketReceived(long nowNanos)` (`false` nếu đã quyết định timeout); `void onPong(int seq, long nowNanos)`; `OptionalLong lastRttNanos()`. `tick` quyết định timeout và đánh dấu `expired` trong cùng khóa với `onPacketReceived`; `offer(ping)` và `onTimeout` chạy sau khi nhả khóa.

- [ ] **Step 1: Viết test thất bại**

```java
@Test void tickerKeepsRunningAfterTickableThrows()        // counting tickable reaches >= 3 within 2 s
@Test void trackerExpiresOnlyAtLimit()                     // begin(0); tryExpire(s(5)-1, 5s) false; tryExpire(s(5), 5s) true; again false
@Test void endAfterExpireReturnsFalse()
@Test void expireAfterEndReturnsFalse()                    // write finished between check and decision → no expire
@Test void endAndExpireRaceExactlyOneWins()                // 10_000 iterations, CyclicBarrier(2): end(op) vs tryExpire(overdue): exactly one true
@Test void beginAfterExpireReturnsExpiredOpAndStaysExpired() // expire; begin() == EXPIRED_OP; end(any) false; isExpired() still true; tryExpire false
@Test void beginWhileWritingThrows()
@Test void writerStopsAfterExpire()                         // gated output blocks write 1; tracker expired; release gate → write 2 never reaches stream
@Test void startWithRefusingStarterLeavesWriterClosed()      // starter returns false → offer(...) == CLOSED, no thread created
@Test void watchdogFiresOnceAndNeverWhenIdle()
@Test void heartbeatSendsPingEveryInterval()               // tick 0, 2 s, 4 s → ping(1), ping(2) offered
@Test void heartbeatBoundary()                             // start 0: tick(s(8)-1) no timeout; tick(s(8)) timeout once
@Test void packetReceivedResetsLiveness()                  // onPacketReceived(s(7)); tick(s(15)-1) none; tick(s(15)) once
@Test void packetVersusTimeoutRaceExactlyOne()             // barrier: onPacketReceived(s(8)) vs tick(s(8)) from start 0: exactly one of (accepted, timedOut)
@Test void rttFromMatchingPongOnly()
@Test void commandWriterTracksEveryWrite()                 // after 3 writes: tracker IDLE, writeOperationId()==3; existing CommandWriterTest still passes
```

- [ ] **Step 2: Chạy, xác nhận thất bại** — `./mvnw -q test -Dtest='TickerTest,WriteTrackerTest,WriteWatchdogTest,HeartbeatTest'`, Expected: COMPILATION ERROR.
- [ ] **Step 3: Implement theo Interfaces.** `CommandWriter.write`: `op = tracker.begin(now)`; `EXPIRED_OP` → thoát vòng lặp; ghi + `flush`; `finally tracker.end(op)`.
- [ ] **Step 4: `./mvnw -q verify`** — Expected: BUILD SUCCESS (gồm 128 test đợt 1).
- [ ] **Step 5: Commit** — `feat(common): ticker, atomic write tracker, watchdog and heartbeat`

---

### Task 2: SessionLifecycle — khóa private, transaction, đóng hai giai đoạn

**Files:**
- Create: `src/main/java/com/ltm/rd/common/{CloseInfo,ClosedSessionException,SessionLifecycle}.java`
- Test: `src/test/java/com/ltm/rd/common/SessionLifecycleTest.java`

**Interfaces:**
- Consumes: `Timeouts.closeGrace()`, `Packet.DisconnectReason`.
- Produces:
  - `record CloseInfo(Packet.DisconnectReason reason, String message, boolean sendDisconnect)`; `class ClosedSessionException extends IllegalStateException` (unchecked, để `tx.register` dùng được trong `Function<Tx<P>, R>`).
  - `final class SessionLifecycle<P extends Enum<P>>`:
    - `SessionLifecycle(P initial, P closing, LongSupplier nanoClock, Duration closeGrace, ScheduledExecutorService scheduler, Executor cleanup)`
    - `<R> R atomically(Function<Tx<P>, R> body)` — chạy `body` dưới `ReentrantLock` private. Gọi lồng được; mọi hành động `tx.after(...)` và mọi việc của `close` chỉ chạy **sau khi lớp `atomically` ngoài cùng nhả khóa**, theo thứ tự đăng ký. Exception, phạm vi `Tx`, cô lập action: theo "Hợp đồng `atomically`" ở Global Constraints.
    - `interface Tx<P>`:
      - `P phase()`, `boolean isClosed()`
      - `boolean advance(P from, P to)`
      - `boolean close(CloseInfo)`, `boolean closeIf(P expected, CloseInfo)` — chỉ đánh dấu; thủ tục đóng chạy sau khi nhả khóa.
      - `Socket register(Socket s)` — đã đóng thì xếp việc đóng `s` sau khi nhả khóa rồi ném `ClosedSessionException`.
      - `boolean setGracefulStep(Function<CloseInfo, CompletableFuture<?>> step)` — `false` nếu đã đóng (caller không được khởi động writer).
      - `void after(Runnable action)`
    - Tiện ích ngoài transaction (mỗi cái là một `atomically` ngắn): `phase()`, `isClosed()`, `Optional<CloseInfo> closeInfo()`, `close(CloseInfo)`, `closeIf(P, CloseInfo)`, `register(Socket)`.
    - `void addCloseHook(Runnable)` — chạy ở giai đoạn A; đăng ký sau khi đã đóng thì chạy ngay (ngoài khóa).
    - `void addStopHook(Runnable)` — chạy sau khi mọi socket đã đóng và worker đã bị interrupt (dừng writer, hủy đăng ký ticker, đóng wrapper stream); đăng ký muộn thì chạy ngay.
    - `boolean startWorker(String name, Runnable body)` (thỏa `WorkerStarter`) — gọi khi đang giữ khóa → `IllegalStateException`. Dưới khóa: đã đóng → `false`; chưa → **đặt chỗ** worker (`STARTING`). Ngoài khóa: tạo luồng daemon `name` bọc `body`; `Thread.start()` lỗi → nhả chỗ, `close(PROTOCOL_ERROR)`, trả `false`. Khi luồng chạy: phiên đã đóng → bỏ qua `body`; xong (kể cả lỗi → `close(PROTOCOL_ERROR …)`) → nhả chỗ. Theo dõi cả rd-connect, command writer (qua `CommandWriter.start(WorkerStarter)`), command reader, frame receiver, screen sender.
    - `int liveWorkers()` — số worker `STARTING` + đang chạy (cho test baseline).
    - `CompletableFuture<CloseInfo> socketsClosed()` — mọi socket đã đăng ký đã đóng.
    - `CompletableFuture<CloseInfo> terminated()` — socket đã đóng **và** stop hook đã chạy **và** không còn worker `STARTING` hay đang chạy.
    - `boolean isLockHeldByCurrentThread()` (package-private, cho test).

**Thủ tục đóng** (spec 1.8), sau khi khóa ngoài cùng đã nhả:
1. Đặt lịch đóng **raw socket** cưỡng bức tại `closeStartedAt + closeGrace`; scheduler từ chối → đóng ngay.
2. Chạy close hook, mỗi hook trong `try/catch` riêng.
3. Gọi graceful step trong `try/catch` → future (lỗi → future thất bại).
4. Gửi giai đoạn B cho `cleanup`; bị từ chối → đóng raw socket ngay trên luồng gọi (không chờ).
5. Giai đoạn B: chờ future tới deadline tuyệt đối; `finally`, theo đúng thứ tự: đóng mọi **raw socket** đã đăng ký (idempotent, chỉ `Socket.close()`) → hoàn tất `socketsClosed()` → **interrupt** mọi worker đang chạy → chạy stop hook (đóng wrapper ở đây, không trước interrupt, vì writer có thể đang bị throttle giữ wrapper).
6. `terminated()` hoàn tất khi stop hook xong và worker cuối cùng nhả chỗ.

- [ ] **Step 1: Viết test thất bại**

```java
@Test void closeIsIdempotentAndFirstInfoWins()
@Test void closeIfWrongPhaseDoesNothing()
@Test void closeIfRacingAdvance()                        // barrier, 1000 iterations: never (phase==ACTIVE && closed by closeIf(RESERVED))
@Test void closeInsideNestedAtomicallyDefersAllSideEffects() // atomically{ atomically{ tx.close } ; assert hook not run yet }; after return hook ran with isLockHeldByCurrentThread()==false
@Test void afterActionsRunOutsideLockInOrder()
@Test void registerAfterCloseClosesSocketAndThrows()
@Test void setGracefulStepAfterCloseReturnsFalseAndIsNeverInvoked()
@Test void throwingHookStillClosesSocketsByDeadline()   // hook throws; graceful never completes; closeGrace 200 ms; socketsClosed() in [200, 800] ms
@Test void throwingGracefulStepStillClosesSockets()
@Test void socketsClosedBeforeStopHooks()                // stop hook observes socket.isClosed()
@Test void rejectedSchedulerAndCleanupStillCloseSockets()
@Test void terminatedWaitsForWorkers()                   // worker blocked on socket read; terminated() completes only after worker exits from SocketException
@Test void startWorkerRefusedAfterClose()
@Test void startWorkerInsideLockThrows()
@Test void bodyThrowsAfterRegisterClosesSessionAndSocket() // atomically{ tx.register(s); throw } → exception rethrown; isClosed(); s closed; after-actions registered before the throw still ran
@Test void bodyThrowsAfterCloseStillRunsCloseProcedure()
@Test void throwingActionDoesNotSkipLaterActions()
@Test void txUsedOutsideCallbackThrows()
@Test void closeBetweenReservationAndThreadStart()       // seam: latch inside startWorker after reservation; close(); release → body never runs; terminated() completes
@Test void threadStartFailureReleasesReservation()        // thread factory seam throws on start → false; liveWorkers()==0; session closed
@Test void interruptPrecedesWrapperClose()                // stop hook records worker.isInterrupted()==true already
@Test void blockedWriterReleasedByInterrupt()             // worker blocked in interruptible wait holding a wrapper; raw socket closed; interrupt frees it; terminated() completes
```

`SessionLifecycle` có constructor package-private nhận thêm `ThreadFactory` (seam cho hai test về khởi động luồng).

- [ ] **Step 2: Chạy, xác nhận thất bại** — `./mvnw -q test -Dtest=SessionLifecycleTest`.
- [ ] **Step 3: Implement theo Interfaces và thủ tục ở trên.** Dùng `ReentrantLock` + đếm độ sâu lồng; hàng đợi hành động hoãn chỉ được xả khi độ sâu trở về 0.
- [ ] **Step 4: Chạy `SessionLifecycleTest` 5 lần liên tiếp + `./mvnw -q verify`** — Expected: luôn PASS.
- [ ] **Step 5: Commit** — `feat(common): session lifecycle with private lock and two-phase close`

---

### Task 3: AuthGuard và SessionManager (logic thuần)

**Files:**
- Create: `src/main/java/com/ltm/rd/server/{ServerPhase,AuthGuard,ReserveResult,SessionManager}.java`
- Create (khung, hoàn thiện ở Task 4): `src/main/java/com/ltm/rd/server/ServerSession.java` — constructor, `generation()`, `token()` (bản sao), `lifecycle()`, `peer()`, `reservedAtNanos()`.
- Test: `src/test/java/com/ltm/rd/server/{AuthGuardTest,SessionManagerTest}.java`

**Interfaces:**
- Consumes: `SessionLifecycle<ServerPhase>`, `Packet.AuthStatus`, `Timeouts`.
- Produces:
  - `enum ServerPhase { RESERVED, ACTIVE, CLOSING }`
  - `final class AuthGuard` — `AuthGuard(int maxEntries /*1024*/, int failuresBeforeLock /*3*/, Duration window /*30 s*/)`:
    - Entry theo IP: `failures`, `lastFailureAt`, `lockedUntil`.
    - **Hết hạn entry:** chưa khóa → hết hạn khi `now ≥ lastFailureAt + window` (đếm sai lại từ 0); đã khóa → hết hạn khi `now ≥ lockedUntil`. Lần sai thứ 3 trong cửa sổ → `lockedUntil = now + window`.
    - `boolean isLocked(InetAddress ip, long now)`, `void recordFailure(InetAddress ip, long now)`, `void recordSuccess(InetAddress ip)` (xóa entry).
    - Bảng đầy (1024): xóa entry hết hạn trước; vẫn đầy → `isLocked` trả `true` cho IP chưa có entry.
  - `record ReserveResult(Packet.AuthStatus status, ServerSession session)` — `session` khác null chỉ khi `OK`.
  - `final class SessionManager` — khóa manager private:
    - `SessionManager(SecureRandom random, AuthGuard guard, Timeouts timeouts, LongSupplier nanoClock, SessionFactory factory)`; `interface SessionFactory { ServerSession create(long generation, byte[] token, InetAddress peer, long reservedAtNanos); }`
    - `SessionFactory.create` chạy dưới khóa manager nên chỉ dựng đối tượng **chưa chạy** (không ticker, không luồng, không `atomically`); `ServerCore` đăng ký ticker và nối `socketsClosed()` sau khi `tryReserve` trả về.
    - `String currentCode()`; `boolean regenerateCode()` (chỉ khi không có phiên). Mã mới luôn **khác** mã vừa dùng (sinh lại khi trùng), để test "mã cũ" không thất bại ngẫu nhiên 1/10⁶.
    - `ReserveResult tryReserve(InetAddress ip, int version, String code)` — **thứ tự theo spec 1.3**: version ≠ 1 → `BAD_VERSION`; đang có phiên (kể cả CLOSING) hoặc `shuttingDown` → `BUSY`; IP bị khóa → `LOCKED`; mã sai (`MessageDigest.isEqual`) → `recordFailure`, `WRONG_CODE`; đúng → `recordSuccess`, token 16 byte `SecureRandom`, generation tăng, `factory.create`, `OK`.
    - `Optional<ServerSession> current()`; `boolean shuttingDown()` (đọc `volatile`, không khóa — được gọi từ trong khóa phiên); `void setShuttingDown()`.
    - `void onSessionSocketsClosed(ServerSession s)` — chỉ khi `s == current`: bỏ phiên và sinh mã mới, trừ khi `shuttingDown`.
  - Token được kiểm tra trong `ServerSession.attachImage` (Task 4), không có `claimAttach` ở manager.

- [ ] **Step 1: Viết test thất bại**

```java
@Test void codeIsSixDigits()
@Test void correctCodeReservesAndSecondGetsBusy()
@Test void busyReportedBeforeLocked()                 // ip locked AND session active → BUSY (spec order)
@Test void wrongCodeDoesNotChangeCode()
@Test void threeFailuresWithinWindowLock()            // fails at 0, 10 s, 20 s → locked; correct code at 21 s → LOCKED; at 50 s+ → OK
@Test void failuresOutsideWindowDoNotAccumulate()     // fails at 0, 10 s; then 41 s (> 10 s + 30 s) → count restarts; not locked
@Test void lockIsPerIp()
@Test void badVersionReported()
@Test void concurrentReserveOnlyOneWins()             // 8 threads, CyclicBarrier → exactly one OK
@Test void staleSocketsClosedCallbackDoesNotTouchNewSession()
@Test void guardTableBoundedAt1024()
@Test void noNewCodeWhileShuttingDown()
@Test void regeneratedCodeDiffersFromPrevious()       // SecureRandom stub returns the same digits twice, then others → new code != old
@Test void factoryCalledWithoutStartingAnything()     // factory spy: no thread, no lifecycle lock taken while manager lock held
```

- [ ] **Step 2: Chạy, xác nhận thất bại** — `./mvnw -q test -Dtest='AuthGuardTest,SessionManagerTest'`.
- [ ] **Step 3: Implement theo Interfaces.**
- [ ] **Step 4: `./mvnw -q verify`** — Expected: BUILD SUCCESS.
- [ ] **Step 5: Commit** — `feat(server): session manager with code and per-IP lockout`

---

### Task 4: Bắt tay phía Server qua socket thật

**Files:**
- Create: `src/main/java/com/ltm/rd/server/{PendingConnection,PendingConnections,HandshakeHandler,ServerConfig,ServerCore}.java`
- Modify: `src/main/java/com/ltm/rd/server/ServerSession.java`
- Modify: `src/main/java/com/ltm/rd/common/Packet.java` — thêm `static Packet attach(int version, byte[] token)` (giống `auth(int, String)`, để test đường version sai); `attach(byte[])` gọi nó với `Protocol.VERSION`.
- Test: `src/test/java/com/ltm/rd/server/PendingConnectionTest.java`, `src/test/java/com/ltm/rd/it/ServerHandshakeIntegrationTest.java` (client thô viết bằng `PacketIO`)

**Interfaces:**
- Consumes: Task 1–3; `Channel.wrap(Socket, TrafficCounter)`, `Channel.closeRawSocket()`, `Channel.closeWrappersQuietly()`; `PacketIO`; `CommandWriter`.
- Produces:
  - `final class PendingConnection` — `PendingConnection(Socket s, long acceptedAtNanos)`; `boolean claimForClose()`; `boolean handOver(long now, Duration deadline, SessionLifecycle.Tx<?> tx)` — gọi **bên trong** transaction của phiên nhận: từ chối nếu `now ≥ acceptedAt + deadline`, hoặc `tx.isClosed()`, hoặc CAS `PENDING→HANDED_OVER` thất bại; thành công thì `tx.register(socket)`.
  - `final class PendingConnections implements Tickable` — `add`, `remove`, `tick(now)` (đóng kết nối quá hạn qua `claimForClose`), `closeAll()`.
  - `record ServerConfig(InetAddress bindAddress, int cmdPort, int imgPort, Timeouts timeouts, LongSupplier nanoClock, SecureRandom random)`; `static ServerConfig defaults()`.
  - `final class ServerCore implements AutoCloseable` — `start()` (bind cả hai cổng; một cổng lỗi → đóng cổng kia rồi ném), `cmdPort()`, `imgPort()`, `sessions()`, `close()` (shutting down → đóng hai `ServerSocket` → đóng phiên → `closeAll` pending → `shutdownNow` pool và đóng socket của các task trả về → dừng ticker/cleanup; tổng tối đa 5 s). Pool: `ThreadPoolExecutor(4, 4, 0, MILLISECONDS, new ArrayBlockingQueue<>(8), factory("rd-handshake-"), handler)`; handler từ chối thì đóng socket. `ServerCore` gọi `sessions.onSessionSocketsClosed(s)` khi `s.lifecycle().socketsClosed()` hoàn tất.
  - `HandshakeHandler` — gọi `Channel.wrap` **một lần** trước gói đầu và bàn giao đúng `Channel` đó.
    - Cổng lệnh: đọc `AUTH` (`allowed={AUTH}`, `CMD_MAX_LEN`); magic sai → đóng, không trả lời; `tryReserve`; mã sai → chờ `wrongCodeDelay` ngoài mọi khóa (nhưng không vượt deadline bắt tay); ghi + `flush` `AUTH_RESULT`; `OK` → `session.attachCommandChannel(pending, channel, now)`; trả `false` hoặc lỗi bất kỳ sau `OK` → `session.lifecycle().close(...)`.
    - Cổng ảnh: đọc `ATTACH` (`allowed={ATTACH}`); `sessions.current()` → `session.attachImage(payload, pending, channel, now)`; `REJECTED` → chỉ đóng socket này.
    - `finally`: chưa bàn giao → `claimForClose` + đóng socket; luôn `pending.remove`.
  - `ServerSession` (bổ sung):
    - `boolean attachCommandChannel(PendingConnection pc, Channel ch, long now)` — trong **một** `atomically`: `pc.handOver(...)` → tạo `CommandWriter` → `tx.setGracefulStep(info -> writer.closeGracefully(info.sendDisconnect() ? Packet.disconnect(...) : null))` (`false` → hủy) → `tx.after(() -> writer.start(lifecycle::startWorker))` → `tx.after(() -> lifecycle.startWorker("rd-cmd-reader", …))` → `tx.after(` đăng ký `Heartbeat` và `WriteWatchdog(writer.tracker(), cmdWriteLimit, () -> lifecycle.close(TIMEOUT "command write stuck"))` vào ticker, kèm stop hook hủy đăng ký `)`. Đặt `soTimeout = cmdSoTimeout`.
    - `enum AttachResult { ATTACHED, REJECTED }`; `AttachResult attachImage(Packet.AttachPayload p, PendingConnection pc, Channel ch, long now)` — trong **một** `atomically`, theo thứ tự: `p.version()==1`; chưa đóng; `phase==RESERVED`; `now < reservedAt + reserve`; `!sessions.shuttingDown()`; token khớp (`MessageDigest.isEqual`); chưa có kênh ảnh; `pc.handOver(...)` thành công → **chỉ lúc này** mới đánh dấu đã attach và lưu channel, rồi thử kích hoạt. Mọi điều kiện sai → `REJECTED`, token vẫn dùng được.
    - `void dispatch(Packet p)` — trong `atomically` theo bảng spec 2.4: RESERVED nhận `SCREEN_INFO` một lần, `PING/PONG/DISCONNECT`; ACTIVE nhận `PING/PONG/DISCONNECT`; sai phase → `tx.close(PROTOCOL_ERROR)`. `PING` → `offer(pong)`; `DISCONNECT` → `tx.close(sendDisconnect=false)`. Gói hợp lệ → `heartbeat.onPacketReceived(now)` (gọi ngoài transaction).
    - Kích hoạt (trong transaction): đủ `SCREEN_INFO` + kênh ảnh, phase RESERVED, `now < reservedAt + reserve` → `offer(SESSION_ACTIVE)`; chỉ khi `ACCEPTED` mới `advance(RESERVED, ACTIVE)`.
    - Tickable của phiên: `closeIf(RESERVED, TIMEOUT "attach timeout")` khi `now ≥ reservedAt + reserve`.
    - `Optional<Packet.SizePayload> screen()`, `ServerPhase phase()`.

- [ ] **Step 1: Viết unit test thất bại cho `PendingConnection`**

```java
@Test void onlyOneOfHandOverAndClaimForCloseWins()   // 1000 iterations with barrier
@Test void handOverRefusedAtAbsoluteDeadline()        // acceptedAt 0, deadline 3 s: handOver(3 s - 1) ok in fresh case; handOver(3 s) false
@Test void handOverToClosedLifecycleFails()
@Test void tickClosesExpiredOnly()
```

- [ ] **Step 2: Viết integration test thất bại** (handshake 300 ms, reserve 500 ms, liveness 800 ms, ping 200 ms, wrongCodeDelay 0)

```java
@Test void authOkThenScreenInfoThenAttachActivates()   // raw client reads AUTH_RESULT(OK) before SESSION_ACTIVE
@Test void attachBeforeScreenInfoAlsoActivates()
@Test void wrongCodeReturnsWrongCodeAndCloses()
@Test void secondClientGetsBusy()
@Test void silentConnectionClosedAtHandshakeDeadline() // EOF in [300, 900] ms
@Test void dripFeedClosedAtHandshakeDeadline()
@Test void poolFullClosesThirteenthImmediately()       // 4 + 8 silent; 13th EOF < 200 ms; valid client works after 1 s
@Test void reservedWithoutAttachTimesOutAndCodeChanges()
@Test void attachWithBadVersionDoesNotConsumeToken()   // ATTACH v2 → socket closed; then ATTACH v1 same token → ACTIVE
@Test void attachAfterHandshakeDeadlineDoesNotConsumeToken() // second image socket sent ATTACH late (> 300 ms) rejected; a fresh one with same token in time → ACTIVE
@Test void wrongTokenAttachClosesOnlyThatSocket()
@Test void garbageOnImagePortClosesOnlyThatSocket()    // Review Focus 3
@Test void disconnectPacketClosesWithHooksOutsideLock() // client sends DISCONNECT; test close hook records lifecycle.isLockHeldByCurrentThread()==false
@Test void portInUseFailsStartAndReleasesOtherPort()
@Test void noTrafficClosesAfterLiveness()              // closed in [800, 1400] ms
@Test void terminatedLeavesNoSessionThreads()          // after close: terminated() done; no live thread named rd-cmd-reader / rd-cmd-writer-*
@Test void handOverRacingShutdown()                     // latch after token check; ServerCore.close() → attach REJECTED or session closed; socket closed either way; terminated() done
@Test void readerStartFailureClosesSession()            // thread-factory seam fails for rd-cmd-reader → session closed, slot freed, code changes
@Test void tickerRegistrationsReturnToBaseline()        // after terminated(): ticker.registrations() == value before AUTH
```

Từ chối gói sai trạng thái trước khi đọc payload đã được chứng minh ở `PacketIOTest` (đợt 1, có assert vị trí stream); không lặp lại ở integration.

- [ ] **Step 3: Chạy, xác nhận thất bại** — `./mvnw -q test -Dtest='PendingConnectionTest,ServerHandshakeIntegrationTest'`.
- [ ] **Step 4: Implement theo Interfaces.**
- [ ] **Step 5: Chạy integration test 3 lần liên tiếp + `./mvnw -q verify`** — Expected: luôn PASS.
- [ ] **Step 6: Commit** — `feat(server): two-channel handshake with deadlines`

---

### Task 5: ClientSession và CommandReceiver

**Files:**
- Create: `src/main/java/com/ltm/rd/client/{ClientPhase,ClientConfig,SocketConnector,ClientHooks,ScreenGeometry,ScreenSource,CommandReceiver,ClientSession}.java`
- Modify: `src/main/java/com/ltm/rd/common/Channel.java` — thêm `static Channel wrap(Socket s, TrafficCounter c, UnaryOperator<OutputStream> outputDecorator)`; decorator bọc **raw** output của socket **trước** lớp đếm và buffer, nên vẫn chỉ có một bộ wrapper.
- Modify: `src/test/java/com/ltm/rd/client/FakeInputDevice.java` → `public`.
- Test: `src/test/java/com/ltm/rd/client/{FakeScreenSource,CommandReceiverTest}.java`, `src/test/java/com/ltm/rd/it/ClientServerIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1–4; `InputExecutor.beginSession/submit/endSession`, `InputCommand.*`; `Validation.checkPointer`.
- Produces:
  - `enum ClientPhase { CONNECTING, AUTHENTICATING, ATTACHING, ACTIVE, CLOSING }`
  - `record ClientConfig(String host, int cmdPort, int imgPort, String code, Timeouts timeouts, LongSupplier nanoClock, UnaryOperator<OutputStream> imageOutputDecorator)` — decorator mặc định `UnaryOperator.identity()`.
  - `interface SocketConnector { void connect(Socket s, InetSocketAddress addr, int timeoutMs) throws IOException; SocketConnector DEFAULT = Socket::connect; }`
  - `record ClientHooks(Runnable afterAttachFlushed, Runnable beforeSenderStart)` — seam cho test, mặc định no-op; `afterAttachFlushed` chạy trên luồng kết nối sau khi `ATTACH` đã `flush`, trước khi bàn giao kênh ảnh.
  - `record ScreenGeometry(Rectangle bounds, double scaleX, double scaleY)`; `interface ScreenSource { ScreenGeometry geometry(); BufferedImage capture(Rectangle bounds) throws IOException; }`
  - `final class CommandReceiver` — trong transaction của phiên: `MOUSE_*` → `Validation.checkPointer(x, y, bounds.width, bounds.height)` → `InputCommand`; `WHEEL`, `KEY_*`, `RELEASE_ALL` → `InputCommand`; `submit` trả `OVERFLOW` → `tx.close(OVERFLOW)`, `STALE` khi ACTIVE → `tx.close(PROTOCOL_ERROR)`. Lệnh điều khiển trước ACTIVE → `PROTOCOL_ERROR`. `PING` → `offer(pong)`. Đếm `releaseAllReceived()`.
  - `final class ClientSession`:
    - `ClientSession(ClientConfig cfg, long generation, InputExecutor input, ScreenSource screen, SocketConnector connector, ClientHooks hooks, Ticker ticker, ScheduledExecutorService scheduler, Executor cleanup)`
    - `boolean start()` — khởi động luồng `rd-connect` **qua `lifecycle.startWorker`** (để `terminated()` theo dõi cả nó); `CompletableFuture<Void> activated()` hoàn tất khi tới ACTIVE. Thân `rd-connect` không ném ra ngoài. Thứ tự spec 1.4: `new Socket()` → `register` → `connector.connect` → AUTHENTICATING + deadline `clientAuth` → ghi `AUTH` → đọc `AUTH_RESULT` → không `OK` thì đóng với lý do → **ngay khi có `OK`**: ATTACHING + deadline `clientActivation` → bàn giao kênh lệnh (`CommandWriter`, reader, `Heartbeat`, `WriteWatchdog`) → `offer(SCREEN_INFO)` → socket ảnh: `new Socket()` → `register` → `setSendBufferSize(64 KiB)` → connect → `Channel.wrap(…, imageOutputDecorator)` → ghi + `flush` `ATTACH` → `hooks.afterAttachFlushed()` → transaction: `imageChannelReady = true`, thử khởi động.
    - Khi reader nhận `SESSION_ACTIVE` → transaction: `activationReceived = true`, thử khởi động.
    - **Thử khởi động** (trong transaction, chạy đúng một lần): cần `activationReceived && imageChannelReady && !closed && phase==ATTACHING` → `input.beginSession(generation)`; `false` → `tx.close(PROTOCOL_ERROR)`; `true` → `advance(ATTACHING, ACTIVE)` và (Task 7) `tx.after(() -> lifecycle.startWorker("rd-screen-sender", …))`. `record ClientHooks` có thêm `Runnable beforeSenderStart` (mặc định no-op, chạy trong action trước `startWorker`) để test Hủy chen giữa.
    - Close hook: nếu đã begin thì `input.endSession(generation)` (future lưu cho `inputReleased()`); graceful step = `writer.closeGracefully(...)`.
    - `ClientPhase phase()`, `void cancel()` (= `close(USER)`), `CompletableFuture<CloseInfo> socketsClosed()`, `CompletableFuture<CloseInfo> terminated()`, `Optional<Packet.AuthStatus> authFailure()`, `CompletableFuture<Void> inputReleased()` (không bao giờ dùng `orTimeout` lên future này), `long releaseAllReceived()`.

- [ ] **Step 1: Viết unit test thất bại cho `CommandReceiver`**

```java
@Test void pointerOutsideScreenIsProtocolError()
@Test void controlBeforeActiveIsProtocolError()
@Test void keyPressMapsToInputCommand()          // device.awaitCall("keyPress:65")
@Test void overflowClosesSession()
@Test void pingAnsweredWithPong()
```

- [ ] **Step 2: Viết integration test thất bại** (`ServerCore` thật hoặc server thô; Timeouts rút ngắn)

```java
@Test void clientReachesActiveAndServerSeesScreenInfo()
@Test void sessionActiveBeforeImageHandoverStartsOnce()   // hooks.afterAttachFlushed waits on latch; SESSION_ACTIVE arrives; phase still ATTACHING; release latch → ACTIVE; beginSession called once
@Test void imageHandoverBeforeSessionActiveStartsOnce()   // raw server delays SESSION_ACTIVE until signaled
@Test void cancelBetweenActivationAndHandoverStartsNothing() // hold hook, receive SESSION_ACTIVE, cancel(), release → never ACTIVE, beginSession never called, terminated() done
@Test void cancelAtEachHandshakePoint()                    // parameterized: during connect / after AUTH sent / after AUTH_RESULT / after ATTACH flushed → terminated() done, liveWorkers()==0, no input begun
@Test void cancelAfterBeginInputBeforeSenderRuns()         // hold beforeSenderStart; cancel(); release → sender body never runs; inputReleased() done; terminated() done
@Test void wrongCodeReportedToClient()
@Test void serverNotRunningClosesCleanly()                 // Review Focus 1
@Test void cancelDuringBlockedConnectReleasesThread()      // connector waits until socket.isClosed() then throws; run() returns < 500 ms after cancel
@Test void noAuthResultClosesAfterClientAuthDeadline()     // in [clientAuth, clientAuth + 600 ms]
@Test void noSessionActiveClosesAfterActivationDeadline()  // measured from AUTH_RESULT(OK)
@Test void serverEofWhileActiveClosesClientImmediately()   // Review Focus 2: < 500 ms
@Test void reconnectWithOldCodeFailsNewCodeWorks()         // Review Focus 4
@Test void heldKeyReleasedWhenConnectionDrops()            // raw server sends KEY_PRESS(17); awaitCall("keyPress:17"); kill sockets; awaitCall("keyRelease:17"); inputReleased() done
```

- [ ] **Step 3: Chạy, xác nhận thất bại** — `./mvnw -q test -Dtest='CommandReceiverTest,ClientServerIntegrationTest'`.
- [ ] **Step 4: Implement theo Interfaces.**
- [ ] **Step 5: Chạy integration test 3 lần liên tiếp + `./mvnw -q verify`** — Expected: luôn PASS.
- [ ] **Step 6: Commit** — `feat(client): client session with handshake and command receiver`

---

### Task 6: FrameEncoder và FrameDecoder (thuần, không socket)

**Files:**
- Create: `src/main/java/com/ltm/rd/client/FrameEncoder.java`, `src/main/java/com/ltm/rd/server/FrameDecoder.java`
- Test: `src/test/java/com/ltm/rd/client/FrameEncoderTest.java`, `src/test/java/com/ltm/rd/server/FrameDecoderTest.java`

**Interfaces:**
- Consumes: `Protocol.MAX_FRAME_WIDTH`, `Protocol.IMG_MAX_LEN`, `Validation.checkFrameHeader`, `Packet.FramePayload`.
- Produces:
  - `final class FrameEncoder` — `FrameEncoder(int maxWidth /*1280*/, float quality /*0.6f*/)`; `EncodedFrame encode(BufferedImage src) throws IOException`; `record EncodedFrame(int width, int height, byte[] jpeg)`. `w = min(src.w, maxWidth)`, `h = max(1, round((double) w * src.h / src.w))`; vẽ vào `TYPE_INT_RGB`, nội suy bilinear; `ImageWriter` + `MODE_EXPLICIT`; `dispose()` trong `finally`. `jpeg.length > IMG_MAX_LEN - 8` → `IOException("frame too large")`.
  - `final class FrameDecoder` — `FrameDecoder()` và constructor package-private `FrameDecoder(Supplier<ImageReader> readers)` (seam cho spy); `BufferedImage decode(Packet.FramePayload p, int rw, int rh)` ném `ProtocolException`: `checkFrameHeader` → `MemoryCacheImageInputStream` → `getWidth(0)`/`getHeight(0)` phải bằng header **trước** `read(0)` → `dispose()` và đóng stream trong `finally`; `IOException` → `ProtocolException`.

- [ ] **Step 1: Viết test thất bại**

```java
@Test void downscalesWideImageKeepingRatio()         // 1920x1080 → 1280x720
@Test void keepsSmallImageSize()
@Test void extremeRatioHeightAtLeastOne()            // 8000x2 → 1280x1
@Test void outputPassesValidationRatioRule()         // {1366x768, 1920x1200, 2560x1080}
@Test void encodeThenDecodeRoundTrip()
@Test void corruptJpegIsProtocolErrorAndReaderDisposed() // spy reader: dispose() called
@Test void declaredSizeMismatchNeverCallsFullRead()      // JPEG 640x360, header 1280x720 → ProtocolException; spy: read(0) never called
@Test void headerLargerThanScreenRejectedBeforeReaderCreated() // screen 1280x720, header 1281x721 → spy supplier never invoked
@Test void wrongRatioRejected()                       // screen 1920x1080, header 1280x1024
```

- [ ] **Step 2: Chạy, xác nhận thất bại** — `./mvnw -q test -Dtest='FrameEncoderTest,FrameDecoderTest'`.
- [ ] **Step 3: Implement theo Interfaces.** Spy (test) `DelegatingImageReader extends javax.imageio.ImageReader` — kế thừa lớp **abstract công khai**, bọc reader thật từ `ImageIO.getImageReadersByFormatName("jpeg")` bằng delegation (`super(delegate.getOriginatingProvider())`), đếm `read`/`dispose`. Không kế thừa lớp nội bộ JDK.
- [ ] **Step 4: `./mvnw -q verify`** — Expected: BUILD SUCCESS.
- [ ] **Step 5: Commit** — `feat: jpeg frame encoder and validating decoder`

---

### Task 7: Luồng ảnh end-to-end và cổng điều khiển

**Files:**
- Create: `src/main/java/com/ltm/rd/client/ScreenSender.java`, `src/main/java/com/ltm/rd/server/{FrameReceiver,FrameMonitor}.java`
- Modify: `ServerSession.java` (khởi động `FrameReceiver` sau ACTIVE, cổng điều khiển, `offerInput`), `ClientSession.java` (khởi động `ScreenSender` khi thử khởi động thành công)
- Test: `src/test/java/com/ltm/rd/server/{FrameMonitorTest,ServerSessionGateTest}.java`, `src/test/java/com/ltm/rd/it/{ThrottledOutputStream,ThrottledOutputStreamTest,ImageStreamIntegrationTest}.java`

**Interfaces:**
- Consumes: Task 1–6.
- Produces:
  - `final class ScreenSender` — `ScreenSender(ClientSession session, Channel img, ScreenSource screen, FrameEncoder encoder, int fps /*12*/, LongSupplier nanoClock)`; `WriteTracker tracker()`; `Runnable body()` để chạy qua `lifecycle.startWorker("rd-screen-sender", …)`. Vòng lặp: mỗi 1 s so `geometry()` với geometry ban đầu, khác → `close(SCREEN_CHANGED)`; chụp → nén → `op = tracker.begin(now)` (`EXPIRED_OP` → thoát) → `PacketIO.write` + `flush` → `finally tracker.end(op)` → ngủ phần còn lại của chu kỳ `1000/fps` ms (thoát sớm khi phiên đóng). Lỗi → đóng phiên. Phiên đăng ký `WriteWatchdog(sender.tracker(), imgWriteLimit, () -> lifecycle.close(TIMEOUT "image write stuck"))`.
  - `final class FrameMonitor` — **logic thuần, không khóa, không listener**; chỉ được gọi bên trong transaction của phiên (khóa lifecycle bảo vệ nó). `FrameMonitor(long activeAtNanos, Duration stall, Duration timeout)`; `enum Transition { NONE, STALL_START, STALL_END, TIMEOUT }`; `Transition onFrame(long now)` (đã `TIMEOUT` → trả `TIMEOUT`, frame bị bỏ); `Transition onTick(long now)`; `boolean hasFrame()`, `boolean stalled()`, `boolean timedOut()`, `long revision()` (tăng mỗi lần chuyển trạng thái). `lastFrameAt` khởi tạo bằng `activeAtNanos`; mỗi chuyển trạng thái trả về đúng một lần.
  - `final class FrameReceiver` — `Runnable` chạy qua `startWorker("rd-frame-receiver", …)`: `PacketIO.read(in, IMG_MAX_LEN, Set.of(FRAME))` → `FrameDecoder.decode` (ngoài khóa) → `session.publishFrame(generation, image, now)`. Lỗi → đóng `PROTOCOL_ERROR`/`TIMEOUT`.
  - `ServerSession` (bổ sung):
    - **Gate nguyên tử:** `publishFrame` và tickable của phiên gọi `monitor.onFrame/onTick` **bên trong `atomically`**, và trong cùng transaction đó xử lý `Transition`: `STALL_START` → gate đóng + `offer(RELEASE_ALL)` (tăng `releaseAllSent` khi `ACCEPTED`); `STALL_END` → gate mở; `TIMEOUT` → `tx.closeIf(ACTIVE, TIMEOUT "frame timeout")`. Không có input nào chen được giữa "khóa điều khiển" và `RELEASE_ALL`.
    - `void publishFrame(long generation, BufferedImage img, long now)` — sai generation hoặc đã đóng → bỏ qua; cập nhật `latestFrame` trong transaction.
    - `boolean controlEnabled()` = trong `atomically`: `phase==ACTIVE && monitor.hasFrame() && !monitor.stalled() && !closed`.
    - `CommandWriter.OfferResult offerInput(Packet p)` — chỉ `MOUSE_*`, `WHEEL`, `KEY_*`; **kiểm tra gate và `offer` trong cùng một `atomically`**; gate đóng → `CLOSED`; `OVERFLOW` → `tx.close(OVERFLOW)`.
    - `void addFrameObserver(FrameObserver o)`; `interface FrameObserver { void onChange(long revision, FrameMonitor.Transition t); }` — cho UI/test, chạy qua `tx.after` (ngoài khóa); observer bỏ thông báo có `revision` cũ hơn cái đã thấy.
    - `Optional<BufferedImage> latestFrame()`, `long framesReceived()`, `long releaseAllSent()`.
  - `ThrottledOutputStream` (test) — `ThrottledOutputStream(OutputStream delegate)`, **mặc định không giới hạn** (bắt tay chạy tốc độ thường); `void setRate(long bytesPerSecond)` (`Long.MAX_VALUE` = không giới hạn) đổi được bất cứ lúc nào từ test; khi giới hạn: ghi theo khối `max(1, rate / 100)` byte, chờ theo lát ≤ 10 ms; `close()` và interrupt đánh thức ngay rồi ném `IOException`; được inject qua `ClientConfig.imageOutputDecorator`. Test chỉ bật throttle **sau** `activated()`, dùng latch ở `FakeScreenSource.capture` để biết frame nào bị làm chậm.

- [ ] **Step 1: Viết unit test thất bại**

```java
// FrameMonitorTest
@Test void stallBoundaryFromActive()                       // tick(stall-1) none; tick(stall) start once; later ticks still once
@Test void firstFrameBeforeStallEnablesWithoutStallEnd()
@Test void stallEndsOnNextFrame()
@Test void frameAtTimeoutInstantAfterTickIsDropped()        // onTick(15 s) == TIMEOUT; onFrame(15 s) == TIMEOUT; timedOut()
@Test void frameTimeoutUsesCurrentLastFrameAt()
@Test void revisionIncrementsOncePerTransition()
// ServerSessionGateTest (server/, fake writer, fake clock; latch-forced orderings)
@Test void stallThenRecoveryThenInputOrdered()              // tick→STALL_START (RELEASE_ALL queued) ; frame→STALL_END ; input → writer order: RELEASE_ALL before input; releaseAllSent==1
@Test void inputDuringStallDecisionNeverPrecedesReleaseAll() // latch holds tick inside transaction; offerInput on other thread waits; result CLOSED; queue = [RELEASE_ALL]
@Test void staleObserverNotificationIgnored()               // observer gets STALL_START(r1) after STALL_END(r2) → ends in "not stalled"
@Test void frameVersusTimeoutRaceExactlyOne()               // 10_000 iterations, barrier: publishFrame(15 s) vs tick(15 s): exactly one of (frame counted, closed TIMEOUT)
// ThrottledOutputStreamTest
@Test void closeWakesBlockedWriterQuickly()                 // 1 byte/s, write 100 bytes on another thread; close(); writer throws within 100 ms
```

- [ ] **Step 2: Viết integration test thất bại** (`FakeScreenSource` 1920×1080; frameStall 600 ms, frameTimeout 2 s, imgWriteLimit 1.5 s)

```java
@Test void framesFlowAndControlEnablesAfterFirstFrame()
@Test void inputReachesClientDeviceAfterFirstFrame()       // offerInput(mousePress(100,100,1)) → awaitCall("mouseMove:100,100") then "mousePress:1"
@Test void inputRejectedBeforeFirstFrame()                 // screen source blocks first capture → offerInput == CLOSED
@Test void slowLinkStallsOnceThenRecovers() {
    // after activated() and first frame: offerInput(keyPress(17)); assertTrue(device.awaitCall("keyPress:17"));
    // capture latch: next capture is frame N; throttle.setRate(S / (1.5 × frameStall)) just for frame N
    // observer sees STALL_START → throttle.setRate(MAX) → observer sees STALL_END
    // assert: server.releaseAllSent() == 1 (counted when writer ACCEPTED) and client.releaseAllReceived() == 1 (counted when parsed+accepted)
    // assert: device log contains "keyRelease:17"                                  (effect: key really released)
    // assert: session still open
}
@Test void heartbeatAliveButNoFramesTimesOut()             // reason TIMEOUT "frame timeout"; decision in [2 s, 2.6 s] after ACTIVE; socketsClosed separately ≤ decision + closeGrace + 600 ms
@Test void stuckImageWriteClosesSessionWithTimeout()       // after activated(): setRate(1); reason TIMEOUT "image write stuck" (not "frame timeout": frameTimeout set to 10 s here); decision in [imgWriteLimit, +600 ms] from write start; terminated() within 5 s (throttle woken by interrupt)
@Test void reconnectFiftyTimesReturnsToBaseline()          // 50× connect → ACTIVE → cancel → terminated(); afterwards: no live rd-* session thread, ticker.registrations() == baseline, server current() empty
@Test void wrongRatioFrameIsProtocolError()                // Review Focus 5: fake capture switches to 1600x1280 (ratio 5:4) without geometry change → PROTOCOL_ERROR
@Test void downscaledFrameIsAccepted()                     // 1920x1080 source → frames 1280x720 accepted
@Test void screenGeometryChangeClosesWithScreenChanged()
@Test void disconnectEndToEndReleasesInputAndSlot()        // cancel(): server current() empty; inputReleased() done; both terminated() done; no rd-frame-receiver / rd-screen-sender alive
```

- [ ] **Step 3: Chạy, xác nhận thất bại** — `./mvnw -q test -Dtest='FrameMonitorTest,ServerSessionGateTest,ThrottledOutputStreamTest,ImageStreamIntegrationTest'`.
- [ ] **Step 4: Implement theo Interfaces.** Teardown của mọi integration test: `ServerCore.close()`, `cancel()`, rồi chờ `terminated()` tối đa 5 s.
- [ ] **Step 5: Chạy toàn bộ integration test 3 lần liên tiếp + `./mvnw -q verify`** — Expected: luôn PASS. Tổng thời gian < 2 phút là mục tiêu, không phải lý do bỏ assertion hay rút timeout.
- [ ] **Step 6: Commit** — `feat: end-to-end image stream with stall gate and frame timeout`

---

## Kết thúc đợt 2

- `./mvnw verify` xanh sau từng task và trên CI (JDK 21); toàn bộ integration test chạy liên tiếp ba lượt đều xanh.
- Test reconnect 50 lần: số worker `rd-*` của phiên và `ticker.registrations()` trở về baseline.
- Không mất lệnh đầu, không thực thi input của generation cũ, không treo nhả phím với fake device hợp tác.
- Mỗi test timeout assert **đúng lý do** (`CloseInfo.message`); timeout khác đóng trước thì test fail.
- Review độc lập toàn branch (sonnet); xử lý finding trước merge.
- Mức nghiệm thu: lõi mạng ổn định trên localhost với thiết bị giả — **chưa** kết luận về Windows thật, DPI, key repeat hay LAN.
- Cập nhật README mục "Trạng thái": bắt tay, phiên, luồng ảnh và thao tác chạy end-to-end giữa hai đầu socket bằng adapter giả; chưa có GUI và Robot.
- Ngoài phạm vi (Task 6–8 của plan tổng): Swing, `RobotInputDevice`/`RobotScreenSource`, `App.shutdown()` đầy đủ, launcher chạy thật, harness hai JVM.

## Nhật ký sửa plan

**Review vòng 1** (đã kiểm chứng với code đợt 1):
1. Watchdog/heartbeat/frame monitor: thay đọc rời bằng quyết định nguyên tử (`WriteTracker` CAS; khóa private trong `Heartbeat`/`FrameMonitor`); thêm test race "hoàn tất giữa kiểm tra và quyết định".
2. Bỏ `synchronized(lifecycle)` do caller giữ (monitor vào lại làm hook chạy dưới khóa); thay bằng `atomically(tx)` với khóa private và hành động hoãn tới khi lớp ngoài cùng nhả khóa; test qua `dispatch()`.
3. Đóng phiên: đặt deadline cưỡng bức trước hook; `register(Socket)` thay `register(Closeable)`; raw socket trước wrapper; `setGracefulStep` trong transaction; tách `socketsClosed()` và `terminated()`; `startWorker`, `addStopHook`.
4. Token chỉ được ghi nhận sau khi bàn giao thành công trong cùng transaction; kiểm tra version `ATTACH`; thứ tự `BUSY` trước `LOCKED` theo spec.
5. Client khởi động input/sender chỉ khi đủ `activationReceived` và `imageChannelReady`, đúng một lần; test cả hai thứ tự và Hủy xen giữa.
6. `RELEASE_ALL` đếm ở tầng giao thức (`releaseAllSent`/`releaseAllReceived`), log thiết bị chỉ chứng minh hiệu quả; chờ `keyPress:17` trước khi gây gián đoạn.
7. Seam `imageOutputDecorator` bọc raw output trước `Channel.wrap`; throttle hủy được; watchdog ảnh đóng phiên với `TIMEOUT`.
8. Ghi rõ độ phân giải tick danh nghĩa; không đóng trước deadline; sửa Review Focus 5; dùng spy reader thay cho "không OOM"; Goal là hai đầu socket cùng JVM; định nghĩa hết hạn entry `AuthGuard`.

**Review vòng 2 (chốt):**
1. Hợp đồng `atomically`: exception → đóng phiên + vẫn xả action; action cô lập; `Tx` giới hạn trong callback; `ClosedSessionException` unchecked; không gọi `atomically` dưới khóa manager; `shuttingDown` là `volatile`.
2. Worker: `startWorker` đặt chỗ dưới khóa, `Thread.start()` ngoài khóa, xử lý close-trước-chạy và start-lỗi; `CommandWriter.start(WorkerStarter)` (thay vì chỉ `ThreadFactory`, vì factory không biết `start()` thất bại); `rd-connect` cũng qua `startWorker`; cleanup: raw socket → interrupt → wrapper.
3. `FrameMonitor` thành logic thuần trả `Transition`, chạy trong transaction của phiên; gate + `RELEASE_ALL` + `offerInput` cùng transaction; observer mang `revision`.
4. `WriteTracker.EXPIRED` là trạng thái cuối; `begin` chỉ từ `IDLE`; writer dừng khi expired.
5. Throttle mặc định không giới hạn, `setRate` sau ACTIVE, stall một lần rồi khôi phục; tách ba mốc đo thời gian; mã mới khác mã cũ; spy reader dùng delegation; test reconnect 50 lần về baseline.
