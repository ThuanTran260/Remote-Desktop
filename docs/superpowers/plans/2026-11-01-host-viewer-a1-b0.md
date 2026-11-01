# Đổi vai Host/Viewer — Plan C0 + A1–B0 (refactor trên wire v1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Bản đính chính G0 (2026-11-01):** plan này được sửa sau commit `30d1604` theo `docs/superpowers/plans/2026-11-01-host-viewer-orchestration-roadmap.md` (người dùng đã duyệt thực thi). Thay đổi so với bản `30d1604`: (1) bỏ `LifecyclePort.wrap(Tx)` khỏi API sản xuất; (2) `tryActivate` ép kiểm lifetime của tx gốc; (3) thêm Task 0 (C0) sửa thời điểm đọc clock trước A1; (4) `inputReleaseSeam` ở B0 biến đổi giai đoạn nguồn của bridge; (5) định tuyến model/effort theo roadmap. Đây là tài liệu; chưa có dòng code nào của C0/A1–B0 được viết.

**Goal:** Sửa thời điểm quyết định kích hoạt (C0, thay đổi hành vi có chủ đích và có test đỏ trước), rồi tách mỗi lớp session thành "vỏ kết nối" + "nửa vai trò" qua `SessionPort`/`SessionSide`/`SideTx`, gom luật gói vào `PacketRules`, chuyển hai nửa vai trò sang `viewer`/`host`, và trả slot theo mục 1.8. **Wire v1 giữ nguyên**; sau C0, A1–A4 là refactor thuần so với baseline C0 (golden trace không đổi), còn B0 chỉ đổi điều kiện trả slot.

**Architecture:** Vỏ (`ServerSession`, `ClientSession`) giữ socket, bắt tay, heartbeat, deadline và state machine. Nửa vai trò (`ControlSide`: gate/epoch/frame; `ControlledSide`: input/ScreenSender) chỉ thấy `SessionPort` và `SideTx`. Một lớp chung `LifecyclePort<P>` hiện thực `SessionPort` trên `SessionLifecycle<P>` cho cả hai vỏ. Vỏ giữ toàn bộ API public hiện có bằng cách ủy quyền cho nửa vai trò, nên `ui/` và test cũ không đổi ở A1–A3.

**Tech Stack:** Java 21 (JDK 24 trên máy dev, `maven.compiler.release` 21), JUnit 5, Maven wrapper `./mvnw.cmd`, JaCoCo 0.8.16 (gate đã có từ A0 `1b3eb94`).

**Spec:** `docs/superpowers/specs/2026-11-01-host-viewer-design.md` (mục 2.2–2.5, 3.1–3.3, 3.5, 3.7). Spec thắng khi plan và spec lệch nhau; worker hỏi coordinator nếu mâu thuẫn.

**Phạm vi:** C0, A1, A2, A3, A4, B0. **B1–B2 có plan riêng** và chỉ bắt đầu sau khi Phần 3 (UI/CLI) và Phần 4 (tài liệu/H2) của spec được trình bày và duyệt: B1 phải lật `AppRuntime` và cửa sổ (ai hiện mã, ai nhập mã) trong cùng commit nguyên khối, mà thiết kế cửa sổ chưa trình bày. Plan này không triển khai B1/B2.

## Global Constraints

- Mỗi commit xanh trên `./mvnw.cmd -q verify` (gồm gate JaCoCo CLASS/BRANCH/COVEREDRATIO ≥ 0.80 từng lớp mục tiêu).
- Không đổi `Protocol.VERSION` (= 1), wire format, lý do đóng (`DisconnectReason`), thông điệp đóng, hay thứ tự gói trên wire. C0 chỉ đổi **thời điểm đọc clock** của quyết định kích hoạt; không thêm/bớt gói, không đổi lý do hay thông điệp đóng.
- **Chuẩn bị adapter ở v1 giữ nguyên suốt C0–B0:** `AppRuntime` vẫn chuẩn bị `ScreenSource`/geometry **trước connect**, `ClientSession.handshake` vẫn đọc `screen.geometry()` ngoài tx rồi `side.announce(geometry)`. `rd-host-prepare`, chuẩn bị sau AUTH và refresh adapter lần hai **chỉ thuộc B1**; không task nào trong plan này được đưa chúng vào.
- `src/test/java/com/ltm/rd/it/GoldenWireTraceTest.java` và `RecordingRelay.java`: C0–A3 không đổi byte nào (literal và assertion trace giữ nguyên, C0 chỉ thêm test ở file khác); A4 chỉ đổi dòng `import`; B0 chỉ đổi chỗ test giả định slot trả ngay khi socket đóng (mục 3.2 spec), literal trace giữ nguyên.
- Sửa test cũ ở A1–A4 chỉ được: đổi `package`/`import`/tên tham chiếu khi chuyển lớp; sửa lời gọi constructor/method ở đúng ranh giới mà bước đó tách. Không đổi/xóa assertion, giá trị kỳ vọng, input, timeout, lý do đóng. Mỗi test cũ bị sửa phải được liệt kê trong commit body. C0 không sửa test cũ nào.
- Số test không giảm. Không `@Disabled`. Test mới không dùng `Thread.sleep`; thứ tự ép bằng latch/seam/fake clock.
- Trong tx (`atomically`) chỉ: đổi trạng thái, `offer`/`submit` không chặn, `beginSession`. Cấm I/O, chờ future, start thread trực tiếp (dùng `after`). Thứ tự khóa: session → executor.
- **Sở hữu `SideTx`:** `LifecyclePort.atomically` là nơi duy nhất tạo `SideTx`, từ lifecycle của chính port; không có API public nào dựng `SideTx` từ `SessionLifecycle.Tx` có sẵn. Vỏ đang giữ tx gốc gọi hook của nửa vai trò (và `Heartbeat.answerPing`) qua `port.atomically(stx -> …)`; khóa re-entrant và hàng `after` của tx ngoài cùng giữ nguyên thứ tự "chạy sau khi tx ngoài cùng nhả khóa".
- Nửa vai trò không ném exception qua hook; tự đóng bằng `stx.close(CloseInfo)` với đúng lý do v1. Vỏ không đổi lý do đó.
- `SideTx` chỉ hợp lệ trong callback hiện tại; không lưu vào field hay đưa vào worker.
- Giữ `inputReleased` là future **cố định tạo trong constructor** và `SessionSide.bridgeClosing` không bao giờ thay hay hoàn tất sớm future đó (A2, B0).
- `viewer.ControlView` → `server.ServerPhase` là phụ thuộc ngược **tạm thời, giữ đến B1**. A4 không loại bỏ mọi phụ thuộc ngược và không được báo như vậy.
- Comment và commit message tiếng Anh. Commit của worker kết thúc bằng dòng `Co-Authored-By` ghi **đúng agent/model thực tế** (lấy từ receipt `launch.effective` hoặc dòng attribution của harness); không ghi cứng số phiên bản model chưa được receipt chứng minh, không suy ra từ alias `sonnet`.
- Không push/merge/rebase/amend. Không `taskkill /IM java.exe`. Không chạy Robot spike, `MouseTrialSpike` hay jar GUI. Không sửa `docs/` (G0 là task tài liệu duy nhất).
- Model/effort (người dùng đã chấp nhận): G0 sonnet high; C0, A1, A2, B0 = sonnet xhigh, review xhigh; A3 = sonnet high, review high; A4 = sonnet high, review medium; review cuối branch = sonnet xhigh. Chỉ khẳng định model/effort đã dùng khi receipt `launch.effective` chứng minh.

## Review Focus

0. **Quyết định kích hoạt dùng timestamp lấy trước khi chờ khóa (C0).** v1 `ServerSession.dispatch` đọc `now` trước `lifecycle.atomically` (`ServerSession.java:244`), và `attachCommandChannel`/`attachImage` truyền `now` của caller vào `maybeActivate(tx, now)` (`:192`, `:260`, `:301`). Thread đợi khóa có thể kích hoạt sau `reservedAt + reserve`. C0 sửa trước refactor; test: `screenInfoWaitingForSessionLockCannotActivateAfterReserveDeadline`.
1. **Thứ tự callback sau tx bị đảo khi chuyển code vào nửa vai trò.** Ở v1, `ClientSession.activated()` hoàn tất trước khi seam `beforeSenderStart` chạy (`ClientSession.java:418-419`). Test ghim: Task 2 `activatedCompletesBeforeSenderStartHook`.
2. **`SideTx` bị dùng sau callback** (lưu vào field, dùng trong `after`/worker) phải ném `IllegalStateException`, không âm thầm chạy ngoài khóa; kể cả `tryActivate(stx)` với `SideTx` đã hết hạn khi Shell không đụng tx. Test: Task 1 `sideTxUsedAfterCallbackThrows`, `tryActivateWithExpiredSideTxThrows`. `SideTx` của port khác bị `tryActivate` từ chối (`tryActivateRejectsSideTxOfAnotherPort`), và không có đường public nào gắn nhãn port lên tx của lifecycle khác (đã bỏ `wrap`).
3. **Thông điệp đóng trôi khi chuyển kiểm tra sang nửa vai trò hoặc `PacketRules`**: input lúc `ATTACHING` phải vẫn là "input before session active"; `SESSION_ACTIVE` lúc `ACTIVE` vẫn "unexpected SESSION_ACTIVE"; `SCREEN_INFO` lần hai vẫn "unexpected SCREEN_INFO". Test: Task 3 `v1CloseMessagesSurviveTheRulesTable`.
4. **`after` đăng ký trong `atomically` lồng nhau** (nửa vai trò gọi `port.atomically` khi vỏ đang giữ tx) chỉ chạy khi tx ngoài cùng nhả khóa. Test: Task 1 `afterInNestedAtomicallyRunsOnlyAfterOuterTx`.
5. **Slot bị trả khi worker hoặc nhả input còn dở** (B0), hoặc trả cho phiên không còn là `current`. Test: Task 5 `slotHeldWhileInputReleaseIsHeld`, `slotNeverReleasedWhenInputReleaseFails`, `releaseOfAStaleSessionIsIgnored`.

---

## Bản đồ file

| File | Trách nhiệm | Task |
| --- | --- | --- |
| `common/SideTx.java` | view trung tính của tx cho nửa vai trò | 1 |
| `common/SessionPort.java` | những gì vỏ cấp cho nửa vai trò | 1 |
| `common/SessionSide.java` | 4 hook + 1 truy vấn; `bridgeClosing` (Task 2) | 1, 2 |
| `common/LifecyclePort.java` | `SessionPort` trên `SessionLifecycle<P>`, dùng chung cho hai vỏ | 1 |
| `server/ControlSide.java` → `viewer/` | gate/epoch, `FrameMonitor`, `publishFrame`, observer, `offerInput`, `releaseControlInput` | 1, 4 |
| `client/ControlledSide.java` → `host/` | `CommandReceiver`, `beginSession/endSession`, `ScreenSender` | 2, 4 |
| `common/PacketRules.java` | lọc loại gói theo (vỏ, kênh, stage), bảng v1 | 3 |
| `common/Heartbeat.java` | thêm `answerPing(SideTx, SessionPort, Packet)` | 2 |
| `server/ServerSession.java` (`maybeActivate` private), `ServerSessionProtocolEdgesTest` | đọc clock trong tx cho quyết định kích hoạt | 0 (C0) |
| `server/SessionManager.java`, `ServerCore.java`, `ServerSession.java` | trả slot theo 1.8 | 5 |

Tất cả đường dẫn dưới `src/main/java/com/ltm/rd/` và `src/test/java/com/ltm/rd/`.

---

### Task 0 (C0): đọc clock trong tx cho quyết định kích hoạt của `ServerSession` (thay đổi hành vi có chủ đích, trước mọi refactor)

**Baseline:** `30d1604` cộng commit tài liệu G0. SHA của commit C0 là baseline của A1; A1–A4 là refactor thuần so với SHA đó.

**Files:**
- Modify: `server/ServerSession.java` — **chỉ** method private `maybeActivate` và các callsite của nó (`attachCommandChannel`, đường `SCREEN_INFO` trong `dispatch`, `attachImage`; số dòng tại `30d1604`: `:192`, `:260`, `:301`, định nghĩa `:313`).
- Test: modify `server/ServerSessionProtocolEdgesTest.java` (chỉ thêm test, không sửa test cũ).
- Cấm: `common/`, `client/`, `pom.xml`, `GoldenWireTraceTest.java`, `RecordingRelay.java`, `ServerSession.tick`/`reserveExpired`/heartbeat/close reason.

**Interfaces — Produces (Task 1 consume):**

```java
// server/ServerSession.java, private
private void maybeActivate(SessionLifecycle.Tx<ServerPhase> tx);
```

Hành vi: trả ngay nếu `writer == null || screen == null || imageChannel == null || tx.isClosed() || tx.phase() != RESERVED`; sau đó đọc `long activationNow = env.clock().getAsLong()` **đúng một lần, bên trong tx**; nếu `reserveExpired(activationNow)` thì return (không đóng; `tick` vẫn đóng `TIMEOUT` "attach timeout" như v1); ngược lại `offer(SESSION_ACTIVE)` → `advance` → `onActive(tx, activationNow)` — cùng giá trị cho reserve check và `onActive`. Mọi callsite (SCREEN_INFO, command attach, image attach) gọi `maybeActivate(tx)`; tham số `now` của chúng chỉ còn dùng cho phần **không phải quyết định kích hoạt** (ví dụ `pc.handOver(now, …)`, `reserveExpired(now)` ở đầu `attachImage` — giữ nguyên). Timestamp heartbeat, `tick`, timeout đóng phiên và lý do đóng không đổi. Với tick-thread, `env.clock()` là cùng nguồn clock mà `dispatch` dùng.

- [ ] **Step 1: Viết test (trên baseline, chưa sửa production)** trong `ServerSessionProtocolEdgesTest`, dùng seam/fake clock/latch sẵn có (không `Thread.sleep`):

```java
@Test void screenInfoWaitingForSessionLockCannotActivateAfterReserveDeadline()
    // dispatch(SCREEN_INFO) đã đọc timestamp cũ (còn hạn) nhưng đang đợi khóa phiên (latch giữ khóa bằng lifecycle.atomically ở thread khác);
    // image + writer đã sẵn sàng; fake clock tiến qua reservedAt + reserve; thả khóa.
    // Kỳ vọng: phase không phải ACTIVE; SESSION_ACTIVE không được xếp vào writer; onActive không chạy;
    // tick sau đó đóng TIMEOUT "attach timeout" như v1.
@Test void screenInfoJustBeforeReserveDeadlineActivates()   // clock = reservedAt + reserve - 1 tick: ACTIVE, SESSION_ACTIVE xếp đúng một lần
@Test void screenInfoExactlyAtReserveDeadlineDoesNotActivate() // clock = reservedAt + reserve (now == deadline là hết hạn): không ACTIVE, tick đóng TIMEOUT
@Test void imageAttachJustBeforeAndAtReserveDeadline()      // đường attachImage dùng cùng chính sách: trước deadline ACTIVE, đúng deadline không ACTIVE
@Test void commandChannelAttachUsesTheInTxActivationTime()   // đường attachCommandChannel (SCREEN_INFO/ảnh đến trước): cùng chính sách
```

- [ ] **Step 2: Chạy trên baseline, chứng minh test race ĐỎ** (ghi tên test đỏ và thông báo lỗi vào commit body): `./mvnw.cmd -q test -Dtest=ServerSessionProtocolEdgesTest`. Test biên "ngay trước" có thể xanh sẵn; test race `screenInfoWaitingForSessionLockCannotActivateAfterReserveDeadline` **bắt buộc đỏ** trước fix. Không đỏ thì dừng và báo coordinator (test chưa ép được thứ tự).
- [ ] **Step 3: Sửa tối thiểu** như mục Interfaces. Không dọn dẹp, không đổi tên khác.
- [ ] **Step 4: Verify** — `./mvnw.cmd -q verify` exit 0; `git diff HEAD -- src/test/java/com/ltm/rd/it/GoldenWireTraceTest.java src/test/java/com/ltm/rd/it/RecordingRelay.java` rỗng (literal và assertion golden trace giữ nguyên); `git diff HEAD --stat` chỉ hai file ở mục Files; coverage 3 lớp mục tiêu hiện có ≥ 0.80.
- [ ] **Step 5: Commit** — `fix: read the clock inside the tx when deciding server activation` (body: test race đỏ trước fix; các test biên; golden trace không đổi; SHA này là baseline A1). Review xhigh kiểm: clock chỉ đọc trong tx, không có timestamp lấy trước thao tác có thể chặn còn được dùng cho quyết định kích hoạt.

---

### Task 1 (A1): `SessionPort`/`SessionSide`/`SideTx` + tách `ControlSide` khỏi `ServerSession`

**Baseline:** SHA của commit C0.

**Files:**
- Create: `common/SideTx.java`, `common/SessionPort.java`, `common/SessionSide.java`, `common/LifecyclePort.java`, `server/ControlSide.java`
- Modify: `server/ServerSession.java` (nửa điều khiển ra `ControlSide`; giữ mọi method public/package hiện có, ủy quyền), `server/FrameReceiver.java:26-41,53-54,68`
- Modify: `pom.xml` (rule `check-branch-coverage`: thêm `<include>com.ltm.rd.server.ControlSide</include>`), `src/test/resources/coverage-targets.txt` (thêm dòng tương ứng)
- Test: create `common/LifecyclePortTest.java`, `server/ControlSideTest.java`; modify (ranh giới) `server/FrameReceiverTest.java:71`, `server/ServerTestAccess.java` nếu cần accessor

**Interfaces — Produces (Task 2–5 dùng đúng tên này):**

```java
package com.ltm.rd.common;
public interface SideTx {
    boolean isClosed();
    boolean isActive();
    boolean isActivationPending();   // RESERVED on the listening shell, ATTACHING on the connecting shell
    boolean activationExpired();     // shell reads its clock inside the tx; now == deadline is expired
    boolean close(CloseInfo info);   // reason and message kept as given
    void after(Runnable action);
}

public interface SessionPort {
    <R> R atomically(java.util.function.Function<SideTx, R> body); // re-entrant
    long generation();
    boolean isClosed();                              // FrameReceiver/ScreenSender loops need it
    boolean close(CloseInfo info);
    CommandWriter.OfferResult offer(Packet p);       // CLOSED before the hand-over; never blocks
    Ticker.Registration register(Tickable t);        // no-op registration when the shell has no ticker
    void addStopHook(Runnable hook);
    boolean startWorker(String name, Runnable body); // lifecycle accounting: counted in terminated()
    void tryActivate(SideTx stx);                    // shell's activation, inside the open tx; stx must come from this port's atomically
}

public interface SessionSide {
    void onAuthenticated(SideTx stx);
    boolean readyForActive();
    void onPacket(SideTx stx, Packet p);
    boolean onActive(SideTx stx, long nowNanos, Channel img);
    java.util.concurrent.CompletableFuture<Void> onClosing();
}

public final class LifecyclePort<P extends Enum<P>> implements SessionPort {
    public interface Shell<P> {                       // all called inside the tx
        boolean isActive(P phase);
        boolean isActivationPending(P phase);
        boolean activationExpired(long nowNanos);
        CommandWriter writer();                       // null before the hand-over
        void tryActivate(SessionLifecycle.Tx<P> tx);  // no timestamp argument: the shell reads its clock inside the tx
    }
    public LifecyclePort(SessionLifecycle<P> lifecycle, long generation, Ticker ticker /* nullable */,
            java.util.function.LongSupplier clock, Shell<P> shell);
    // NO public wrap(Tx): atomically is the only place a SideTx is created, from this port's own lifecycle.
}
```

`LifecyclePort` có một view `SideTx` **private** (giữ `(port, SessionLifecycle.Tx<P>)`), chỉ được `atomically` tạo từ `lifecycle.atomically` của chính port (khóa re-entrant nên gọi lồng khi vỏ đang giữ tx gốc vẫn dùng đúng tx ngoài cùng). `activationExpired()` của view gọi `shell.activationExpired(clock.getAsLong())`. Không có factory/constructor/method public nào nhận `Tx<P>` sẵn có để dựng `SideTx`.

`tryActivate(stx)` làm đúng thứ tự: (1) `stx` phải là view private **của chính port này**, khác → `IllegalArgumentException`; (2) **ép kiểm lifetime của tx gốc** trước khi giao cho Shell (gọi một method của `SessionLifecycle.Tx` vốn đi qua `check()` ở `SessionLifecycle.java:148-152`, ví dụ `tx.isClosed()`; tx hết hạn/sai thread → `IllegalStateException`), kể cả khi Shell giả không đụng tx; (3) `shell.tryActivate(view.tx)`. Các chữ ký phase/deadline của `SideTx` và `SessionPort.isClosed()` giữ nguyên như trên.

`ControlSide` (package `server`, `final`, `implements SessionSide`): constructor `ControlSide(SessionPort port, Timeouts timeouts, LongSupplier clock, boolean receiveFrames)`; nhận nguyên trạng thái `screen`, `monitor`, `latestFrame`, `framesReceived`, `releaseAllSent`, `controlReleasesSent`, `activeAtNanos`, `framesExpected`, observers từ `ServerSession.java:76,79-86,88`; method `boolean tick(long now)` (false nếu chưa `framesExpected`), `publishFrame(long, BufferedImage, long)`, `controlEnabled()`, `offerInput(Packet)`, `offerInput(long, Packet)`, `releaseControlInput()`, `controlReleasesSent()`, `inputEpoch()`, `latestFrame()`, `activeAtNanos()`, `framesReceived()`, `releaseAllSent()`, `addFrameObserver(...)`, `Optional<Packet.SizePayload> screen()`; và các getter dùng trong tx để vỏ dựng `ControlView`. `FrameObserver` chuyển thành `ControlSide.FrameObserver` (cùng chữ ký `onChange(long, FrameMonitor.Transition)`); `ServerSession.addFrameObserver(ControlSide.FrameObserver)` ủy quyền. Test cũ chỉ truyền lambda nên không phải sửa; làm vậy để sau A4 `viewer` không phụ thuộc ngược `server`.

`FrameReceiver(SessionPort port, ControlSide target, Channel img, FrameDecoder decoder, int screenWidth, int screenHeight, LongSupplier clock)`: `target.publishFrame(port.generation(), image, now)`, vòng lặp dừng khi `port.isClosed()`, đóng qua `port.close(...)` với đúng các lý do hiện có.

Quy tắc chuyển (không đổi hành vi):
- Hook `ControlSide`: `onAuthenticated` không làm gì; `readyForActive()` = đã có `screen`; `onPacket(SCREEN_INFO)`: nếu đã có `screen` thì `stx.close(PROTOCOL_ERROR, "unexpected SCREEN_INFO", true)`, nếu chưa thì lưu `p.sizePayload()` rồi `port.tryActivate(stx)`; loại khác: `PROTOCOL_ERROR "unexpected " + type` (phòng thủ); `onActive` = thân `ServerSession.onActive` hiện tại, worker qua `stx.after(() -> port.startWorker("rd-frame-receiver", …))`, trả `true`; `onClosing()` = future đã xong.
- `ServerSession.dispatch`: PING/PONG/DISCONNECT vẫn ở vỏ; `SCREEN_INFO` cho qua khi phase `RESERVED` rồi gọi `side.onPacket`. Thứ tự trong `maybeActivate` của vỏ giữ v1: `offer(SESSION_ACTIVE)` → `advance` → `side.onActive`. Điều kiện `screen == null` được thay bằng `!side.readyForActive()`.
- `Shell.tryActivate(tx)` của `ServerSession` = `maybeActivate(tx)` (chữ ký do **C0** tạo ra: không nhận `now`, tự đọc `env.clock()` trong tx và dùng cùng giá trị cho reserve check và `onActive`). `attachImage`/`attachCommandChannel` và đường `SCREEN_INFO` đều đã gọi `maybeActivate(tx)` từ C0; A1 không đổi chính sách thời gian này và không thêm `now` nào quay lại.
- Vỏ đang giữ tx gốc mà cần gọi hook (`side.onPacket`, `side.onActive`, `side.onAuthenticated`) hoặc `Heartbeat.answerPing` thì mở `port.atomically(stx -> …)` lồng (cùng khóa re-entrant, cùng tx ngoài cùng); không có `wrap`. Mọi `tx.after` đăng ký qua `stx` vẫn chạy sau khi tx ngoài cùng nhả khóa (`afterInNestedAtomicallyRunsOnlyAfterOuterTx`).
- `ServerSession.tick`: `if (!side.tick(now) && reserveExpired(now)) closeIf(RESERVED, "attach timeout")`.
- `ServerSession.controlView()` dựng `ControlView(generation, tx.phase(), …)` trong **một** `lifecycle.atomically` từ getter của `ControlSide` (lock re-entrant, cùng một snapshot).
- `ServerSession` thêm package-private `SessionPort port()` và `ControlSide controlSide()` cho test ranh giới.

- [ ] **Step 1: Viết test hợp đồng port (đỏ vì lớp chưa có)** — `common/LifecyclePortTest.java`, lifecycle thật với fake clock, `Shell` giả ghi lại lời gọi:

```java
@Test void sideTxMapsShellPhases()              // RESERVED: isActivationPending && !isActive; sau advance ACTIVE: isActive
@Test void sideTxUsedAfterCallbackThrows()      // SideTx giữ lại ra ngoài atomically -> assertThrows(IllegalStateException, saved::isClosed)
@Test void tryActivateRejectsSideTxOfAnotherPort() // assertThrows(IllegalArgumentException, () -> portB.tryActivate(stxOfPortA)) trong tx của A; Shell của B không bị gọi
@Test void tryActivateWithExpiredSideTxThrows()   // Shell giả KHÔNG đụng tx: giữ stx ra ngoài portA.atomically; assertThrows(IllegalStateException, () -> portA.tryActivate(saved)); Shell không bị gọi
@Test void tryActivateRunsShellInsideTheSameTx()  // shell.tryActivate nhận Tx còn hợp lệ, gọi đúng 1 lần
@Test void activationExpiredReadsClockAtCallTime()  // clock.advance trước lời gọi; shell nhận đúng giá trị mới
@Test void offerIsClosedBeforeAWriterExists()   // assertEquals(OfferResult.CLOSED, port.offer(Packet.ping(1)))
@Test void registerWithoutTickerIsANoOp()       // register(t).close() không ném
@Test void startWorkerAfterCloseSkipsTheBody()  // close rồi startWorker -> false, body không chạy
@Test void afterInNestedAtomicallyRunsOnlyAfterOuterTx() // trong lifecycle.atomically: port.atomically(stx -> {stx.after(r); return null;}); r chưa chạy khi trở về tx ngoài; khi r chạy, lifecycle.isLockHeldByCurrentThread() == false
```

- [ ] **Step 2: Chạy, xác nhận đỏ vì thiếu lớp**
Run: `./mvnw.cmd -q test -Dtest=LifecyclePortTest` — Expected: lỗi biên dịch `cannot find symbol LifecyclePort`.

- [ ] **Step 3: Hiện thực `SideTx`, `SessionPort`, `SessionSide`, `LifecyclePort`** theo Interfaces. View `SideTx` là lớp private tạo trong `atomically`; mọi method của view gọi `tx` (tx tự ném `IllegalStateException` khi hết hạn, `SessionLifecycle.java:148-152`). `tryActivate` kiểm view thuộc đúng port (khác → `IllegalArgumentException`), ép kiểm lifetime tx gốc, rồi `shell.tryActivate(view.tx)`. Không thêm `wrap` hay bất kỳ API public nào dựng `SideTx` từ `Tx` có sẵn.

- [ ] **Step 4: Chạy `LifecyclePortTest` — Expected: PASS.**

- [ ] **Step 5: Viết `server/ControlSideTest.java`** (port là `LifecyclePort<ServerPhase>` trên lifecycle thật, `Shell` giả):

```java
@Test void readyOnlyAfterFirstScreenInfo()            // false -> onPacket(screenInfo(1920,1080)) -> true; shell.tryActivate gọi 1 lần
@Test void secondScreenInfoClosesWithV1Reason()     // closeInfo == PROTOCOL_ERROR, "unexpected SCREEN_INFO", sendDisconnect true
@Test void onActiveDefersTheFrameReceiverStart()    // receiveFrames=true: trong tx liveWorkers()==0; sau tx có "rd-frame-receiver"; onActive trả true
@Test void tickBeforeActiveReturnsFalse()
@Test void onClosingIsAlreadyComplete()
```

- [ ] **Step 6: Tách `ControlSide` khỏi `ServerSession`, đổi `FrameReceiver`** theo quy tắc chuyển ở trên. Sửa `FrameReceiverTest.java:71` thành `new FrameReceiver(session.port(), session.controlSide(), channelOver(bytes), decoder, 1920, 1080, System::nanoTime)` (sửa ranh giới, liệt kê trong commit body).

- [ ] **Step 7: Thêm lớp mục tiêu coverage** — `pom.xml` rule và `coverage-targets.txt` thêm `com.ltm.rd.server.ControlSide`.

- [ ] **Step 8: Verify**
Run: `./mvnw.cmd -q verify` — Expected: exit 0.
Run: `git diff HEAD -- src/test/java/com/ltm/rd/it/GoldenWireTraceTest.java src/test/java/com/ltm/rd/it/RecordingRelay.java` — Expected: rỗng.
Run: `grep -rnE "\bwrap\(" src/main/java/com/ltm/rd/common src/main/java/com/ltm/rd/server src/test/java/com/ltm/rd` — Expected: không có lời gọi/khai báo `LifecyclePort.wrap` (các kết quả không liên quan như `wrap` của stream phải được loại thủ công và ghi trong commit body).
Run: `grep -E ',(ServerSession|ControlSide|ClientSession|HandshakeHandler),' target/site/jacoco/jacoco.csv` — Expected: mỗi lớp `BRANCH_COVERED/(COVERED+MISSED) >= 0.80`. Nếu `ServerSession` tụt dưới ngưỡng sau khi tách, thêm test tất định cho nhánh còn thiếu, không hạ ngưỡng.

- [ ] **Step 9: Commit**
```bash
git add -A src/main/java/com/ltm/rd/common src/main/java/com/ltm/rd/server src/test pom.xml
git commit -m "refactor: split ControlSide out of ServerSession behind SessionPort"
```

---

### Task 2 (A2): tách `ControlledSide` khỏi `ClientSession`; PING lên vỏ

**Files:**
- Create: `client/ControlledSide.java`
- Modify: `client/ClientSession.java` (nửa bị điều khiển ra `ControlledSide`; giữ mọi method public), `client/ScreenSender.java:28-46,63-65,112`, `client/CommandReceiver.java:22-45`, `common/Heartbeat.java` (thêm `answerPing`), `common/SessionSide.java` (thêm `bridgeClosing`), `server/ServerSession.java:262-266` (dùng `Heartbeat.answerPing`)
- Modify: `pom.xml`, `coverage-targets.txt` (thêm `com.ltm.rd.client.ControlledSide`)
- Test: create `client/ControlledSideTest.java`, `common/SessionSideTest.java`; modify (ranh giới) `client/CommandReceiverTest.java`; move 2 test PING sang `common/HeartbeatTest.java`; thêm 1 test vào `it/ClientServerIntegrationTest.java`

**Interfaces:**
- Consumes: `SideTx`, `SessionPort`, `SessionSide`, `LifecyclePort` (Task 1).
- Produces:
  - `static void SessionSide.bridgeClosing(SessionSide side, CompletableFuture<Void> target)` = `bridgeClosing(side::onClosing, target)`.
  - `static void SessionSide.bridgeClosing(java.util.function.Supplier<CompletableFuture<Void>> source, CompletableFuture<Void> target)`: gọi `source.get()`; lỗi đồng bộ hoặc future `null` → `target.completeExceptionally(…)`; future lỗi → exceptionally; xong → `complete(null)`. **Không bao giờ thay `target` và không hoàn tất nó trước khi stage nguồn xong.** B0 dùng overload `Supplier` để đặt `inputReleaseSeam` lên stage nguồn.
  - `static void Heartbeat.answerPing(SideTx stx, SessionPort port, Packet ping)`: `port.offer(Packet.pong(ping.seq())) == OVERFLOW` → `stx.close(OVERFLOW, "command queue overflow", true)`.
  - `CommandReceiver(InputExecutor input, long generation, Rectangle bounds)` (bỏ `writer`); `void handle(SideTx stx, Packet p)` — chưa ACTIVE kiểm bằng `!stx.isActive()`; nhánh PING bị xóa; mọi lý do/thông điệp khác giữ nguyên (`CommandReceiver.java:47-67`).
  - `ScreenSender(SessionPort port, ScreenGeometry announced, Channel img, ScreenSource screen, FrameEncoder encoder, int fps, LongSupplier nanoClock)`.
  - `ControlledSide(SessionPort port, InputExecutor input, ScreenSource screen, Timeouts timeouts, LongSupplier clock, Runnable beforeSenderStart)` (package `client`, `final`, `implements SessionSide`) + `void announce(ScreenGeometry g)` (cầu nối v1, gọi trên luồng connect ngoài khóa trước bàn giao; B1 thay bằng `rd-host-prepare`) + getter `inputBeginCount()`, `senderRuns()`, `framesSent()`, `releaseAllReceived()`.

Quy tắc chuyển (không đổi hành vi):
- `ClientSession.handshake` vẫn đọc `screen.geometry()` ngoài tx (`ClientSession.java:278`) rồi gọi `side.announce(geometry)`.
- Trong `attachCommandChannel` (đang giữ tx gốc), gọi `port.atomically(stx -> { side.onAuthenticated(stx); return null; })` (lồng, re-entrant; không có `wrap`) đúng chỗ của `receiver = new CommandReceiver(…)` + `w.offer(Packet.screenInfo(…))` hiện tại (sau `writer = w`, sau `addStopHook(w::stop)`, trước các `tx.after`). `onAuthenticated` tạo `CommandReceiver` từ geometry đã announce và `port.offer(SCREEN_INFO)`; kết quả offer bị bỏ qua như v1.
- `dispatch` của vỏ: `SESSION_ACTIVE` giữ nguyên (`ClientSession.java:379-386`); `PING` → `Heartbeat.answerPing`; `PONG` không làm gì; `DISCONNECT` đóng như cũ; còn lại → `side.onPacket` (→ `CommandReceiver.handle`).
- `tryStart(tx)` của vỏ (đang giữ tx gốc; mọi lời gọi hook đi qua `port.atomically(stx -> …)` lồng, không có `wrap`): giữ các điều kiện cũ cộng `side.readyForActive()`; `startAttempted = true`; **đăng ký `tx.after(() -> activated.complete(null))` trước khi gọi `side.onActive`** (để `activated` xong trước sender, như v1); `side.onActive(...) == false` → `tx.close(PROTOCOL_ERROR, "input session refused", true)` và return (close procedure được xếp đầu hàng deferred, `SessionLifecycle.java:225`, nên `onClosing` làm `activated` lỗi trước, `complete(null)` sau đó vô hiệu); `true` → `tx.advance(ATTACHING, ACTIVE)`.
- `ControlledSide.onActive`: `input.beginSession(port.generation())`; false → trả false; true → `inputBegun = true`, đếm begin, `stx.after(() -> startSender(img))`. `startSender` = thân `ClientSession.startSender` hiện tại (`beforeSenderStart`, watchdog `"image write stuck"`, worker `"rd-screen-sender"` qua `port.startWorker`).
- `ControlledSide.onClosing()`: đọc `inputBegun` qua `port.atomically`; chưa begin → future đã xong; đã begin → `input.endSession(generation)`.
- Close hook của vỏ: `activated.completeExceptionally(...)` rồi `SessionSide.bridgeClosing(side, inputReleased)`. `inputReleased` vẫn là future final tạo trong constructor (**target ổn định**, bridge không thay, không hoàn tất sớm).
- Chuẩn bị adapter ở A2 giữ y v1: `AppRuntime` chuẩn bị source trước connect, `handshake` đọc geometry ngoài tx rồi `side.announce(geometry)`; **không** tạo `rd-host-prepare` hay refresh adapter lần hai (chỉ B1).
- `CommandReceiverTest`: chỉ sửa ranh giới (constructor bỏ `writer`; helper `handle` chạy `handle(stx, p)` bên trong `port.atomically(stx -> …)` của `LifecyclePort` dựng trên cùng lifecycle; **không** dùng `wrap`). Hai test PING chuyển sang `HeartbeatTest`, gọi `Heartbeat.answerPing` bên trong `port.atomically` trên cùng writer/lifecycle; **input và assertion giữ nguyên từng dòng**. Bảng truy vết bắt buộc trong commit body (tên cũ → mới; nếu giữ nguyên tên thì ghi rõ "cùng tên, đổi lớp chứa"):

  | Tên cũ (`client/CommandReceiverTest`) | Tên mới (`common/HeartbeatTest`) |
  | --- | --- |
  | `pongThatOverflowsTheWriterQueueClosesTheSession` | `pongThatOverflowsTheWriterQueueClosesTheSession` |
  | `pingAnsweredWithPong` | `pingAnsweredWithPong` |

- [ ] **Step 1: Ghim thứ tự v1 trước khi refactor** — thêm vào `it/ClientServerIntegrationTest.java`:

```java
@Test void activatedCompletesBeforeSenderStartHook() // HeldHook ở beforeSenderStart; khi reached:
    // assertTrue(c.activated().isDone()); assertFalse(c.activated().isCompletedExceptionally()); rồi release, cancel, awaitTerminated
```

- [ ] **Step 2: Chạy trên code cũ — Expected: PASS** (test đặc tả hành vi v1; nó phải xanh trước và sau refactor).
Run: `./mvnw.cmd -q test -Dtest=ClientServerIntegrationTest#activatedCompletesBeforeSenderStartHook`

- [ ] **Step 3: Viết test đỏ cho phần mới** — `common/SessionSideTest.java`:

```java
@Test void bridgeKeepsTargetAndWaitsForAHeldFuture()      // onClosing trả future chưa xong -> target chưa xong; complete -> target xong; cùng object
@Test void bridgeFailsTargetOnSynchronousThrow()          // onClosing ném -> target.isCompletedExceptionally()
@Test void bridgeFailsTargetOnFailedFuture()
@Test void bridgeFailsTargetOnNullFuture()
@Test void bridgeSupplierAppliesSourceTransformBeforeCompletingTarget() // Supplier trả stage đã biến đổi (giữ bằng latch): target chưa xong; mở latch -> xong; cùng object; không bao giờ hoàn tất trước stage nguồn
```

`client/ControlledSideTest.java` (port `LifecyclePort<ClientPhase>`, `InputExecutor` thật trên `FakeInputDevice`):

```java
@Test void onAuthenticatedOffersScreenInfoOfAnnouncedGeometry() // writer giả nhận SCREEN_INFO(1920,1080)
@Test void inputBeforeActiveKeepsV1Reason()          // PROTOCOL_ERROR "input before session active"
@Test void refusedBeginReturnsFalseAndStartsNothing() // onActive false; không có "rd-screen-sender"; onClosing() đã xong
@Test void onActiveBeginsOnceAndDefersTheSender()    // inputBeginCount()==1; trong tx chưa có worker sender
@Test void onClosingAfterBeginWaitsForEndSession()   // FakeInputDevice giữ release bằng latch -> future chưa xong; mở latch -> xong
```

- [ ] **Step 4: Chạy — Expected: lỗi biên dịch (thiếu `ControlledSide`, `bridgeClosing`).**

- [ ] **Step 5: Hiện thực** theo Interfaces và quy tắc chuyển; `ServerSession` PING (đang giữ tx gốc) dùng `port.atomically(stx -> { Heartbeat.answerPing(stx, port, p); return null; })`; `ClientSession` làm tương tự. Sau bước này `grep -rnE "\bport\.wrap\(|\.wrap\(" src/main src/test` không còn lời gọi `LifecyclePort.wrap` nào.

- [ ] **Step 6: Thêm `com.ltm.rd.client.ControlledSide` vào rule pom và `coverage-targets.txt`.**

- [ ] **Step 7: Verify**
Run: `./mvnw.cmd -q verify` — Expected: exit 0, gồm `activatedCompletesBeforeSenderStartHook` và toàn bộ golden trace.
Run: `git diff HEAD -- src/test/java/com/ltm/rd/it/GoldenWireTraceTest.java src/test/java/com/ltm/rd/it/RecordingRelay.java` — Expected: rỗng.
Run: kiểm `jacoco.csv` như Task 1 cho 5 lớp mục tiêu — Expected: mỗi lớp ≥ 0.80.

- [ ] **Step 8: Commit** — `refactor: split ControlledSide out of ClientSession; answer PING in the shells` (body liệt kê test cũ bị sửa ranh giới và hai test PING đã chuyển).

---

### Task 3 (A3): `PacketRules` theo luật v1

**Files:**
- Create: `common/PacketRules.java`
- Modify: `server/ServerSession.java:63-64,225,248-256`, `client/ClientSession.java:49-52,261,357,380`, `server/HandshakeHandler.java:62,88`, `server/FrameReceiver.java:24,47`
- Test: create `common/PacketRulesTest.java`; thêm test vào `server/ServerSessionProtocolEdgesTest.java` và `client/ClientSessionHandshakeEdgesTest.java`

**Interfaces — Produces:**

```java
public final class PacketRules {
    public enum Shell { LISTENER, CONNECTOR }     // v1: LISTENER = Server, CONNECTOR = Client; B1 rewrites the table
    public enum Lane { COMMAND, IMAGE }
    public enum Stage { HANDSHAKE, PENDING, ACTIVE } // PENDING = RESERVED / ATTACHING
    public static Set<PacketType> allowedIn(Shell shell, Lane lane, Stage stage); // unmodifiable
    public static Set<PacketType> readableAfterHandshake(Shell shell, Lane lane); // PENDING ∪ ACTIVE
    public static boolean allows(Shell shell, Lane lane, Stage stage, PacketType type);
}
```

Bảng v1 (copy đúng vào test dưới dạng literal):
- LISTENER/COMMAND: HANDSHAKE `{AUTH}`; PENDING `{SCREEN_INFO, PING, PONG, DISCONNECT}`; ACTIVE `{PING, PONG, DISCONNECT}`.
- LISTENER/IMAGE: HANDSHAKE `{ATTACH}`; PENDING `{}`; ACTIVE `{FRAME}`.
- CONNECTOR/COMMAND: HANDSHAKE `{AUTH_RESULT}`; PENDING `{SESSION_ACTIVE, PING, PONG, DISCONNECT}` ∪ INPUT; ACTIVE `{PING, PONG, DISCONNECT}` ∪ INPUT. INPUT = `{MOUSE_MOVE, MOUSE_PRESS, MOUSE_RELEASE, WHEEL, KEY_PRESS, KEY_RELEASE, RELEASE_ALL}`. Input ở PENDING được cho qua bảng **có chủ đích**: v1 đóng nó trong `CommandReceiver` với "input before session active"; B1 lật theo mục 1.7.
- CONNECTOR/IMAGE: mọi stage `{}`.

Cách dùng (giữ đúng hai tầng lỗi của v1):
- Tầng đọc: reader kênh lệnh dùng `readableAfterHandshake(shell, COMMAND)`, nên loại không bao giờ hợp lệ vẫn ra `ProtocolException` → "protocol violation". Bắt tay dùng `allowedIn(…, HANDSHAKE)`; `FrameReceiver` dùng `allowedIn(LISTENER, IMAGE, ACTIVE)`.
- Tầng phase: `dispatch` của hai vỏ map phase → `Stage` rồi `allows(...)`; sai → `PROTOCOL_ERROR "unexpected " + type`, `sendDisconnect = true` (đúng thông điệp v1). Kiểm "một lần" (`SCREEN_INFO`, `SESSION_ACTIVE`, `ATTACH`), token, payload, generation vẫn ở chỗ cũ (spec 2.4).

- [ ] **Step 1: Viết `PacketRulesTest`** — `@ParameterizedTest` trên mọi `PacketType × Shell × Lane × Stage`, so với bảng literal ở trên; thêm `readableAfterHandshakeEqualsTheOldReaderSets()` (LISTENER = `{SCREEN_INFO, PING, PONG, DISCONNECT}` = `ServerSession.java:63-64` cũ; CONNECTOR = tập ở `ClientSession.java:49-52` cũ).
- [ ] **Step 2: Chạy — Expected: lỗi biên dịch thiếu `PacketRules`.**
- [ ] **Step 3: Viết test giữ thông điệp ở vỏ** (chạy qua `dispatch` hoặc `RawPeer`; tên bắt buộc):

```java
// ServerSessionProtocolEdgesTest
@Test void secondScreenInfoInReservedPassesRulesButClosesSession() // PROTOCOL_ERROR "unexpected SCREEN_INFO"; không ACTIVE
// ClientSessionHandshakeEdgesTest
@Test void v1CloseMessagesSurviveTheRulesTable() // input lúc ATTACHING -> "input before session active";
                                                 // SESSION_ACTIVE lúc ACTIVE -> "unexpected SESSION_ACTIVE"
```

- [ ] **Step 4: Chạy các test ở Step 3 trên code cũ — Expected: PASS** (đặc tả v1).
- [ ] **Step 5: Hiện thực `PacketRules` và thay các tập rải rác** (`COMMAND_IN` ×2, `FRAME_ONLY`, `EnumSet.of(AUTH|ATTACH|AUTH_RESULT)`, `switch` phase trong `ServerSession.dispatch`, kiểm phase của `SESSION_ACTIVE` ở `ClientSession.dispatch`; cờ `activationReceived` giữ ở vỏ).
- [ ] **Step 6: Verify** — `./mvnw.cmd -q verify` exit 0; golden trace diff rỗng; `grep -rn "EnumSet.of(PacketType\|Set.of(PacketType" src/main/java` chỉ còn trong `PacketRules.java`; coverage 5 lớp ≥ 0.80.
- [ ] **Step 7: Commit** — `refactor: one PacketRules table for the v1 packet filters`.

---

### Task 4 (A4): chuyển hai nửa vai trò sang `viewer`/`host`

**Files (theo bảng 2.2 của spec, giữ tên lớp):**
- `server/` → `viewer/`: `ControlSide`, `FrameReceiver`, `FrameDecoder`, `FrameMonitor`, `ControlView`, `InputOutcome`.
- `client/` → `host/`: `ControlledSide`, `ScreenSender`, `FrameEncoder`, `CommandReceiver`, `InputExecutor`, `InputCommand`, `SubmitResult`, `InputDevice`, `RobotInputDevice`, `ScreenSource`, `RobotScreenSource`, `ScreenGeometry`, `DisplayPort`, `AwtDisplayPort`, `RobotPort`, `AwtRobotPort`, `RobotPortFactory`, `AdapterFactory`, `AdapterException`, `RobotAdapterFactory`.
- Test đi theo lớp nó kiểm: `server/{ControlSideTest, FrameDecoderTest, FrameMonitorTest, FrameReceiverTest, DelegatingImageReader}` → `viewer/`; `client/{ControlledSideTest, CommandReceiverTest, FrameEncoderTest, InputExecutorTest, RobotAdapterFactoryTest, RobotInputDeviceTest, RobotScreenSourceTest, FakeInputDevice, FakeRobotPort, FakeScreenSource}` → `host/`. Test cần quyền package-private của vỏ (`ServerSession*Test`, `FrameReceiverIntegrationTest` nếu nó dùng API package-private của `ServerSession`) ở lại, dùng API đã `public` của lớp đã chuyển.
- Modify: `pom.xml` + `coverage-targets.txt`: `com.ltm.rd.server.ControlSide` → `com.ltm.rd.viewer.ControlSide`, `com.ltm.rd.client.ControlledSide` → `com.ltm.rd.host.ControlledSide`.
- `ControlView` tạm thời vẫn có trường `ServerPhase phase` (import `server.ServerPhase` từ `viewer`). Spec 2.2 chấp nhận tên lệch đến B1; B1 đổi kiểu này. **Đây là phụ thuộc ngược `viewer → server` còn lại có chủ đích đến hết B1; A4 không tuyên bố đã tách sạch `viewer`/`host` khỏi `server`/`client`.** Commit body của A4 ghi khoản nợ này; reviewer liệt kê (bằng `grep -rn "import com.ltm.rd.server\|import com.ltm.rd.client" src/main/java/com/ltm/rd/viewer src/main/java/com/ltm/rd/host`) mọi import ngược còn lại và xác nhận mỗi cái hoặc là khoản nợ đã ghi, hoặc phải xử lý trước khi qua gate.

**Interfaces:** ranh giới giữa vỏ và nửa vai trò thành `public` (constructor, `announce`, getter, `publishFrame`, `ControlSide.FrameObserver`). Không đổi chữ ký nào khác.

- [ ] **Step 1: `git mv` từng file** (để lịch sử theo dõi rename), sửa `package`/`import`/visibility. Không đổi logic.
- [ ] **Step 2: Verify**
Run: `./mvnw.cmd -q verify` — Expected: exit 0 (`CoverageTargetsTest` bắt tên lớp mục tiêu cũ nếu quên).
Run: `git diff -M --cached --stat` trước commit — Expected: các file chuyển hiện dạng `rename` (similarity ≥ 90%).
Run: `git diff -M --cached -- src/test | grep -E '^[+-][^+-]' | grep -vE '^[+-](package|import) '` — Expected: chỉ còn dòng đổi visibility hoặc tên kiểu; reviewer đọc từng dòng.
Run: `git diff HEAD -- src/test/java/com/ltm/rd/it/GoldenWireTraceTest.java src/test/java/com/ltm/rd/it/RecordingRelay.java | grep -E '^[+-][^+-]' | grep -v import` — Expected: rỗng.
- [ ] **Step 3: Commit** — `refactor: move the role halves to the viewer and host packages`.

---

### Task 5 (B0): trả slot khi đủ `socketsClosed` + `terminated` + `inputReleased`

**Files:**
- Modify: `server/ServerSession.java` (future `inputReleased` **final tạo trong constructor — target ổn định**; close hook `SessionSide.bridgeClosing(() -> transformed(side.onClosing()), inputReleased)` với `transformed(f) = seam == null ? f : seam.apply(f)`; `public CompletableFuture<Void> inputReleased()`; `Env` thêm seam `UnaryOperator<CompletableFuture<Void>> inputReleaseSeam`, `null` = giữ nguyên). **`inputReleaseSeam` biến đổi stage đóng nguồn (kết quả `side.onClosing()`) trước khi vào bridge**; seam không bao giờ nhận, thay hay hoàn tất `inputReleased` (target), và seam trả `null`/ném thì bridge làm `inputReleased` hoàn tất exceptionally như mọi lỗi nguồn
- Modify: `server/SessionManager.java:110-115` (`onSessionSocketsClosed` → `onSessionReleased(ServerSession s)`, cùng thân: bỏ qua nếu `s != current`, cấp mã mới khi không shutdown)
- Modify: `server/ServerCore.java:45-58` (`Seams` thêm `inputReleaseSeam`, truyền vào `Env`), `ServerCore.java:251`:

```java
CompletableFuture.allOf(session.socketsClosed(), session.terminated(), session.inputReleased())
        .thenRun(() -> sessions.onSessionReleased(session)); // any failure keeps the slot BUSY (spec 1.8)
```
- Test: create `server/SlotReleaseIntegrationTest.java`; modify `server/SessionManagerTest.java` (đổi tên method gọi); modify đúng các test giả định slot trả ngay khi socket đóng (ví dụ `GoldenWireTraceTest.java:340,368,396` nếu chúng assert `current().isEmpty()` ngay sau `socketsClosed`) thành chờ bằng `awaitTrue(() -> core.sessions().current().isEmpty(), …)` hiện có. Literal trace không đổi.

**Interfaces:**
- Consumes: `SessionSide.bridgeClosing(Supplier<CompletableFuture<Void>>, CompletableFuture<Void>)` (Task 2), `ControlSide.onClosing()` (Task 1, trả future đã xong), baseline C0 (`maybeActivate(tx)`).
- Produces: `ServerSession.inputReleased()` — cùng một object trước và sau close; `SessionManager.onSessionReleased(ServerSession)`; `ServerCore.Seams.inputReleaseSeam`.

- [ ] **Step 1: Viết test đỏ** — `server/SlotReleaseIntegrationTest.java` (`ServerCore` thật, `RawPeer`/`ClientSession` thật, fake clock):

```java
@Test void inputReleasedIsTheSameObjectBeforeAndAfterClose() // cả khi seam giữ stage nguồn bằng latch: object lấy trước close == object sau close, chưa xong cho tới khi mở latch
@Test void slotHeldWhileInputReleaseIsHeld()   // seam trả future giữ bằng latch; client ngắt; socketsClosed & terminated xong
    // -> current() vẫn là phiên cũ; mã không đổi; AUTH mới nhận BUSY; mở latch -> current() rỗng, mã đổi, AUTH kế tiếp OK
@Test void slotNeverReleasedWhenInputReleaseFails() // seam trả failedFuture -> sau close: current() vẫn giữ; AUTH -> BUSY
@Test void slotHeldUntilWorkersStop()          // worker của phiên (decode frame qua DelegatingImageReader) bị latch giữ, bỏ qua interrupt;
    // socketsClosed xong nhưng terminated chưa -> AUTH mới BUSY; mở latch -> slot trả, mã đổi
@Test void releaseOfAStaleSessionIsIgnored()   // SessionManagerTest: onSessionReleased(phiên cũ) khi current là phiên khác -> current và mã giữ nguyên
```

Cleanup (`@AfterEach`, spec 3.5): mở mọi latch trong `finally` trước khi đóng core; kiểm không còn luồng `rd-*`, port đã giải phóng, `tickerRegistrations() == 0`.

- [ ] **Step 2: Chạy — Expected: đỏ** (`inputReleased()`/seam chưa có; `slotHeldUntilWorkersStop` thấy slot đã trả).
- [ ] **Step 3: Hiện thực** theo Files/Interfaces. Close hook đăng ký trong constructor `ServerSession` như `ClientSession.java:100`.
- [ ] **Step 4: Sửa test cũ giả định trả slot ngay** — chạy `./mvnw.cmd -q verify`, với mỗi test đỏ chỉ vì slot chưa trả: đổi assert tức thời thành chờ có hạn bằng helper sẵn có. Không sửa gì khác; liệt kê từng test trong commit body.
- [ ] **Step 5: Verify** — `./mvnw.cmd -q verify` exit 0; chạy `SlotReleaseIntegrationTest` 20 lần (`@RepeatedTest(20)` cho các ca có latch) xanh; coverage 5 lớp ≥ 0.80; diff golden trace chỉ ở dòng chờ slot (nếu có).
- [ ] **Step 6: Commit** — `fix: free the server slot only after workers stopped and input was released` (body: mục 1.8 của spec; danh sách test cũ đã chuyển sang chờ trả slot).

---

## Quy trình coordinator cho mỗi task

1. Giao task cho một worker (model/effort ở Global Constraints), kèm đường dẫn plan + spec và SHA baseline đã nghiệm thu (A1 bắt đầu từ SHA của C0).
2. Khi `worker_done`: tự chạy `./mvnw.cmd -q clean verify`; đối chiếu `target/site/jacoco/jacoco.csv` của các lớp mục tiêu với SHA; đọc diff `src/test` theo quy tắc sửa test; xác nhận golden trace theo Global Constraints.
3. Review theo effort đã định (C0/A1/A2/B0 xhigh; A3 high; A4 medium; review cuối branch xhigh) theo checklist spec 3.8: state machine, CAS ownership, close idempotent, deadline, thứ tự khóa, quy tắc stream 1.3, hợp đồng hook 2.3, điều kiện trả slot 1.8. Finding Critical/High phải đóng trước task sau.
4. Release worker, rồi mới giao task kế tiếp. Không chạy hai worker cùng lúc trên cùng worktree (chung `target/`).
