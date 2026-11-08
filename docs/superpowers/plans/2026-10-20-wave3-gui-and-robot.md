# Đợt 3 — GUI và Robot

Ngày: 2026-10-20. Trạng thái: kế hoạch đã chi tiết hóa, chưa triển khai.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans để thực hiện từng task. Bước dùng cú pháp checkbox (`- [ ]`). Bước gắn **[HUMAN]** cần người ngồi trước hai máy Windows thật; agent không được tự đánh dấu hoàn thành và không được thay bằng kết quả headless.

Nguồn: [spec](../specs/2026-10-19-remote-desktop-design.md) (mục 3, 4, 5.2), [kế hoạch tổng](2026-10-19-remote-desktop-implementation.md), [đợt 2](2026-10-19-wave2-session-and-image.md). Tiếp theo: [đợt 4 — ổn định và phát hành](2026-10-20-wave4-hardening-and-release.md).

## Mục tiêu và bằng chứng hiện tại

Sau đợt 3, hai máy Windows trong LAN dùng giao diện Swing để chia sẻ màn hình và điều khiển bằng Robot. Server là máy điều khiển; Client là máy được điều khiển. Người dùng đã xác nhận có hai máy Windows để nghiệm thu.

Baseline đợt 2 được kiểm tra bằng `./mvnw.cmd -q verify`: 334 test, không failure, error hoặc skipped. Đây là bằng chứng cho lõi mạng với adapter giả; chưa chứng minh GUI, Robot hoặc vận hành trên hai máy thật.

Giữ Java 21, Swing system look-and-feel, giao diện tiếng Việt, không thêm runtime dependency hoặc đổi định dạng gói. Phạm vi: một phiên, màn hình chính, Windows, LAN tin cậy. Không thêm TLS/Internet, clipboard, file, âm thanh, nhiều màn hình, IME, điều khiển UAC hoặc lock screen.

## Phương pháp

Áp dụng các nguyên tắc đã đọc từ skills `mattpocock-skills/engineering/codebase-design` và `mattpocock-skills/engineering/tdd`: interface nhỏ, module tự giữ invariant; kiểm thử qua hành vi công khai; mỗi lát dọc red → green → refactor. Không mô phỏng chi tiết private hoặc viết test chỉ lặp lại implementation.

Các seam đã thống nhất: Robot adapters (`RobotPort`, `DisplayPort`, `AdapterFactory`), `CoordinateMapper`/`InputCapture`, `Controller`/`Snapshot` và `AppRuntime`/shutdown. Tái sử dụng kiểm thử mạng đợt 2. Không yêu cầu bộ tự động điều khiển desktop; kiểm thử phần cứng là chương trình `main` opt-in, tách khỏi CI headless.

Thứ tự: 3.1 → 3.2 → 3.3 → 3.4 → 3.5 → 3.6. Có thể làm 3.2 bằng fake trong lúc chờ số đo Robot, nhưng không tích hợp adapter thật vào GUI trước khi gate 3.1 đạt. Mỗi task một commit sau khi kiểm tra; không tự push hoặc merge. Review bắt buộc sau 3.2 và sau 3.6.

## Global Constraints

- **Build:** `./mvnw.cmd -q verify` xanh sau mỗi task trên Windows; CI chạy `./mvnw -B -ntp verify` với JDK 21. Surefire đã đặt `java.awt.headless=true`; không đổi.
- **Package:** `com.ltm.rd.{common,server,client,ui}`. `ui` chứa cả lớp không-Swing (`AppRuntime`, `Controller`, `Snapshot`, `Messages`) và lớp Swing; chỉ lớp Swing (`*Frame`, `RemotePanel`, `SharingBar`, `GuiApp`, `SnapshotTimer`) được import `javax.swing`/mở cửa sổ. Code, comment, commit message bằng tiếng Anh, Conventional Commits; chuỗi hiển thị cho người dùng bằng tiếng Việt có dấu.
- **EDT:** mọi thay đổi Swing chạy trên EDT (`RemotePanel.apply`, cập nhật nhãn/nút, `Timer`). Không I/O, không chờ future, không `Thread.sleep`, không `Robot`, không decode/encode, không gọi `AppRuntime.snapshot()` trên EDT: EDT chỉ đọc `SnapshotPump.latest()`. Các hàm `Controller` thay đổi trạng thái (`connect`, `cancel`, `disconnect`, `sendInput`, `releaseControlInput`, `regenerateCode`, `shutdownAsync`) được phép gọi từ EDT vì chỉ quyết định trong một khóa ngắn rồi giao việc cho `rd-app`.
- **Không side effect dưới khóa:** kế thừa quy tắc đợt 2. Khóa `AppRuntime.lock` chỉ bảo vệ các trường của runtime (slot, cờ, generation); không gọi `ServerCore`, `ClientSession`, `InputExecutor`, `CompletableFuture.complete/whenComplete`, adapter hay UI khi đang giữ khóa này. Thứ tự khóa: `AppRuntime.lock` không bao giờ được giữ khi vào khóa manager hoặc khóa phiên.
- **Tên luồng `rd-*`, daemon:** thêm `rd-app` (một luồng điều phối blocking của runtime), `rd-shutdown`, `rd-snapshot`, `rd-input` (đã có), cùng các luồng hiện có (`rd-ticker`, `rd-close-timer`, `rd-cleanup-N`, `rd-connect`, `rd-cmd-*`, `rd-frame-receiver`, `rd-screen-sender`). Luồng AWT/Swing (`AWT-EventQueue-0`, `TimerQueue`) không thuộc quy ước này.
- **Không log mã/token/phím/ảnh:** chỉ log reason, phase, generation, loại exception. `Snapshot.toString()` không in `code`, `frame`, `peer`. Không đưa `CloseInfo.message` nội bộ lên giao diện; giao diện chỉ dùng chuỗi trong `Messages`.
- **Headless CI:** test không tạo `JFrame`/`Robot`; chỉ dùng thành phần nhẹ (`JPanel`, `MouseEvent`, `KeyEvent` với source là `JPanel`) và `FakeInputDevice`/`FakeScreenSource`. Focus owner thật không có trong headless: truyền `BooleanSupplier focusOwner` tường minh.
- **Test phần cứng opt-in:** là các lớp có `main` trong `src/test/java/com/ltm/rd/spike/`, tên kết thúc `Spike` hoặc `Report` (không khớp mẫu `Test*`/`*Test`/`*Tests` của Surefire nên `mvn verify` không chạy). Chạy tay bằng `java -cp target/classes;target/test-classes <lớp>`. Không dùng tag JUnit cho phần cứng và không sửa `pom.xml`.
- **Race:** mỗi thứ tự quan trọng có test dùng latch/barrier; không dùng `sleep` để quyết định đúng/sai; fake clock cho mọi mốc thời gian.
- **Gate HUMAN:** kết quả ghi vào file mẫu trong `docs/superpowers/reviews/`; mẫu ban đầu ghi "chưa chạy" cho mọi ca. Commit của task chứa gate HUMAN phải nêu đường dẫn file kết quả đã điền hoặc nêu rõ ca nào chưa kiểm chứng.

## Gate HUMAN và bước tự động

- **[HUMAN]** (hai máy Windows thật): spike 3.1; thử chuột 3.4; thử bàn phím 3.5; thử giao diện/launcher 3.6.
- **[AUTO]** (CI headless, `./mvnw.cmd -q verify`): toàn bộ test trong các task còn lại, gồm adapter qua port giả, `AppRuntime` với socket thật trên `127.0.0.1`, mapper, `InputCapture` theo sự kiện tổng hợp, `Launcher.run`, `RateSampler`, form state.
- **[REVIEW]** (reviewer riêng, model sonnet): sau 3.2 và sau 3.6; chỉ ghi nhận review đã thực sự chạy.

## Hợp đồng chung (chốt trước khi triển khai 3.2–3.6)

Các hợp đồng này được xác minh bằng đọc code ở `HEAD 3158e5b`. Task 3.3–3.6 và 4.1 chỉ dùng API nêu ở đây; thay đổi phải sửa mục này trước.

### C1. Vòng đời vai trò — `AppRuntime`

Một `AppRuntime` sở hữu đúng một vai trò (`LaunchOptions.Role.SERVER` hoặc `CLIENT`; `CHOOSER` là lỗi `IllegalArgumentException`). Màn hình chọn vai trò tạo runtime sau khi người dùng chọn; đổi vai trò = shutdown runtime rồi tạo runtime mới. Swing không sở hữu socket, ticker, generation hay executor.

```java
// com.ltm.rd.ui
public record AppConfig(InetAddress bindAddress, int cmdPort, int imgPort, String presetHost,
        Timeouts timeouts, LongSupplier nanoClock, Duration shutdownBudget) {
    public static AppConfig from(LaunchOptions o);   // DEFAULTS, System::nanoTime, wildcard bind, budget 5 s
}

public final class AppRuntime implements Controller {
    public static AppRuntime forServer(AppConfig cfg);                                  // không I/O
    public static AppRuntime forClient(AppConfig cfg, AdapterFactory adapters) throws AdapterException;
    public LaunchOptions.Role role();
    // + toàn bộ Controller (C2)
}
```

- `forClient` tạo `Ticker("rd-ticker")`, scheduler `rd-close-timer`, cleanup `rd-cleanup-N`, `AdapterFactory.inputDevice()` và **một** `InputExecutor` (xem C6). Thất bại → `AdapterException` trước khi có luồng nào chạy (không để `rd-input` mồ côi).
- `forServer` không tạo gì; `ServerCore` được tạo trong `startServer()` (xem dưới).
- Luồng blocking của runtime là một `ExecutorService` một luồng tên `rd-app`; mỗi `startServer`/`connect` giao việc cho nó.
- Package-private `record Seams(ClientHooks hooks, SocketConnector connector, UnaryOperator<OutputStream> imageOutputDecorator, Runnable beforeServerStart, Runnable afterServerStart)` cho test; `Seams.none()` trong production. `beforeServerStart` chạy trên `rd-app` sau khi `ServerCore` đã được gán vào trường và trước `core.start()`; `afterServerStart` chạy ngay sau khi `start()` trả về.
- Package-private `int tickerRegistrations()` (Client: số đăng ký của `Ticker` runtime; Server: `ServerCore.tickerRegistrations()`) cho test baseline.

### C2. `Controller` — giao diện duy nhất UI nhìn thấy

```java
public interface Controller {
    Snapshot snapshot();                                              // đọc nhanh, không I/O; chỉ SnapshotPump gọi
    CompletableFuture<Void> startServer();                            // SERVER; IDLE|FAILED → STARTING → LISTENING
    boolean regenerateCode();                                         // SERVER; true chỉ khi phase == LISTENING
    ConnectOutcome connect(String host, String code);                 // CLIENT; trả ngay, việc nặng ở rd-app
    boolean cancel();                                                 // CLIENT; true nếu phase ∈ {CONNECTING, AUTHENTICATING, ATTACHING}
    boolean disconnect();                                             // mọi vai trò; true nếu có phiên để đóng
    SendResult sendInput(long generation, long inputEpoch, Packet inputPacket);   // SERVER
    boolean releaseControlInput(long generation);                     // SERVER; true nếu RELEASE_ALL đã vào hàng đợi ghi
    CompletableFuture<ShutdownReport> shutdownAsync();                // cùng một future cho mọi lần gọi
}

public enum ConnectOutcome { STARTED, INVALID_INPUT, BUSY, WAITING_RELEASE, INPUT_FAULTED, SHUTTING_DOWN, WRONG_ROLE }
public enum SendResult { SENT, STALE_GENERATION, STALE_EPOCH, GATE_CLOSED, OVERFLOW, NO_SESSION, SHUTTING_DOWN, WRONG_ROLE }
public enum InputRelease { NOT_NEEDED, RELEASED, FAILED, UNCONFIRMED }
public record ShutdownReport(boolean socketsClosed, boolean workersStopped, InputRelease inputRelease) {}
```

Quy tắc:
- `startServer()` chạy đúng khi `phase ∈ {IDLE, FAILED}` và chưa shutdown; ngược lại future thất bại `IllegalStateException`. Runtime tạo `new ServerCore(ServerConfig)` và gán vào trường **trước** khi gọi `core.start()` (để shutdown luôn thấy nó). `start()` ném `IOException` (vd. `BindException`) → runtime gọi `core.close()`, phase `FAILED`, future thất bại với chính exception đó; lần `startServer()` sau tạo `ServerCore` mới (code hiện tại: `ServerCore.start()` đặt `started=true` nên một core hỏng không dùng lại được).
- `connect(host, code)`: `host` không rỗng sau `trim`, `code` khớp `\d{6}`; sai → `INVALID_INPUT`, không tạo gì, không tăng generation.
- Chỉ **một** thao tác `connect` thắng khi gọi đồng thời; kết quả `BUSY` nếu slot đang có phiên chưa kết thúc; `WAITING_RELEASE` nếu phiên cũ chưa (terminated **và** inputReleased); `INPUT_FAULTED` nếu `inputReleased()` của phiên cũ hoàn tất lỗi hoặc `InputExecutor.state() == FAULTED` (vĩnh viễn cho tới khi khởi động lại ứng dụng).
- `cancel()` trước khi `ClientSession` được tạo (đang chuẩn bị adapter) đánh dấu slot đã hủy: việc ở `rd-app` thấy cờ thì không tạo/không `start()` phiên (test `cancelBeforeSessionCreatedNeverStarts`).

### C3. `Snapshot` bất biến

```java
public enum UiPhase { IDLE, STARTING, LISTENING, RESERVED, CONNECTING, AUTHENTICATING, ATTACHING, ACTIVE,
        CLOSING, FAILED, SHUTTING_DOWN }

public record TrafficStats(long cmdBytesIn, long cmdBytesOut, long imgBytesIn, long imgBytesOut, long rttNanos) {
    public static final long NO_RTT = -1L;
    public static final TrafficStats ZERO = new TrafficStats(0, 0, 0, 0, NO_RTT);
}

public record Snapshot(LaunchOptions.Role role, UiPhase phase, long generation, long inputEpoch, String message,
        String peer, int cmdPort, int imgPort, String code, int screenWidth, int screenHeight,
        BufferedImage frame, long frameCount, boolean controlEnabled, TrafficStats stats) {
    @Override public String toString();   // role, phase, generation, inputEpoch; không in code/frame/peer
}
```

- `generation`: Server = `ServerSession.generation()` của phiên hiện tại; Client = generation do runtime cấp (bắt đầu 1, tăng mỗi `connect` được chấp nhận); không có phiên = 0. Hai vai trò không so sánh generation với nhau.
- `inputEpoch`: xem C4. `frame`: ảnh mới nhất, **không bao giờ bị sửa** sau khi publish (an toàn chia sẻ tham chiếu). `frameCount`: Server = `framesReceived`, Client = `framesSent`. `code`: chỉ Server khi phase ∈ {LISTENING, RESERVED, ACTIVE}, ngược lại `null`. `peer`: Server = IP của Client; Client = `host` đã nhập.
- `controlEnabled` và `frame`/`inputEpoch` của Server lấy từ **một** `ServerSession.controlView()` (một `atomically`): `controlEnabled == true` suy ra `frame` thuộc đúng `inputEpoch` đó.
- Phase suy ra theo đối tượng hiện tại, không lưu cờ riêng: Server — chưa start = `IDLE`; đang bind = `STARTING`; bind lỗi = `FAILED`; có core, không phiên = `LISTENING`; phiên `RESERVED`/`ACTIVE` map thẳng; phiên đã đóng nhưng `sessions().current()` chưa trống = `CLOSING`; shutdown = `SHUTTING_DOWN`. Client — không slot = `IDLE`; chuẩn bị adapter hoặc `CONNECTING` = `CONNECTING`; `AUTHENTICATING`/`ATTACHING`/`ACTIVE` map thẳng; `ClientPhase.CLOSING` = `CLOSING` cho tới khi (terminated **và** inputReleased thành công) rồi về `IDLE`; release lỗi = `FAILED`.
- `message`: chuỗi tiếng Việt từ `Messages` (xem 3.2); rỗng khi không có sự kiện đáng báo.
- Vì `Snapshot` suy ra từ phiên hiện tại, callback của phiên cũ không thể ghi đè trạng thái phiên mới.

### C4. `inputEpoch` và thao tác input mang (generation, epoch)

- **Định nghĩa:** `ServerSession.inputEpoch()` = `monitor == null ? 0 : monitor.revision()`, đọc trong `atomically`. Không có bộ đếm thứ hai nên không thể lệch `FrameMonitor`. `revision()` tăng ở **mọi** chuyển trạng thái (`STALL_START`, `STALL_END`, `TIMEOUT`), nên epoch đổi cả khi ảnh gián đoạn rồi phục hồi giữa hai lần làm tươi UI; UI coi bất kỳ thay đổi epoch nào là "xóa held state và wheelAcc" (xem 3.4).
- **Nguyên tử với gate:** `ServerSession.offerInput(long inputEpoch, Packet p)` thực hiện **trong một** `lifecycle.atomically`: gate mở (`phase==ACTIVE && monitor.hasFrame() && !monitor.stalled() && !closed`) → `inputEpoch == monitor.revision()` → `writer.offer(p)`; `OVERFLOW` → `tx.close(OVERFLOW)`. Thứ tự kiểm tra cố định: gate rồi epoch, nên trong lúc ảnh gián đoạn kết quả là `GATE_CLOSED`, sau khi phục hồi với epoch cũ là `STALE_EPOCH`.
- **Generation:** `Controller.sendInput` đọc tham chiếu `ServerSession` hiện tại **một lần**, so `session.generation() == generation` (bất biến theo đối tượng) rồi gọi `offerInput(epoch, p)` trên chính đối tượng đó; phiên đã đóng trả `GATE_CLOSED`. Không tồn tại đường nào gửi input của generation cũ vào phiên mới.
- **Chỉ nhận gói input:** `MOUSE_MOVE`, `MOUSE_PRESS`, `MOUSE_RELEASE`, `WHEEL`, `KEY_PRESS`, `KEY_RELEASE`; loại khác ném `IllegalArgumentException` như `offerInput(Packet)` hiện có. `offerInput(Packet)` cũ (không epoch) giữ nguyên cho test đợt 2.

### C5. `ServerSession.releaseControlInput()`

`CommandWriter.OfferResult releaseControlInput()` — trong một `atomically`: phiên đã đóng, `phase != ACTIVE` hoặc `writer == null` → `CLOSED` (không gửi gì); ngược lại `writer.offer(Packet.releaseAll())` **không phụ thuộc gate ảnh**; `OVERFLOW` → `tx.close(OVERFLOW)` (hợp đồng hiện tại: hàng đợi đầy đóng phiên). Thành công (`ACCEPTED`) tăng `controlReleasesSent` (bộ đếm mới, tách khỏi `releaseAllSent` vì `releaseAllSent` được tài liệu hóa là "do stall" và test đợt 2 khẳng định `== 1`). Đếm ở tầng giao thức; đối chiếu `ClientSession.releaseAllReceived()`, không suy ra từ log thiết bị rỗng.

### C6. `InputExecutor` suốt vòng đời app và điều kiện kết nối lại

- Runtime Client tạo **một** `InputExecutor(device, () -> currentScreen.geometry().bounds(), 256, InputExecutor.defaultThreadFactory(), onFault)`; `onFault` lưu `lastInputFault` (volatile). Không tạo executor thứ hai để vượt qua worker cũ đang kẹt (xem 4.1). `shutdownAsync()` của executor chỉ gọi khi shutdown ứng dụng.
- `InputExecutor` nhận **một** `InputDevice` cố định khi tạo, nhưng 3.1 yêu cầu tạo lại Robot khi kết nối lại. Giải quyết: `RobotInputDevice` giữ port thay được (`refresh()`); `AppRuntime` chỉ gọi `adapters.refreshInputDevice()` khi điều kiện kết nối lại thỏa, tức executor `IDLE` và worker không còn lệnh; `ScreenSource` tạo mới cho **mỗi** `connect` (`adapters.newScreenSource()`).
- **Điều kiện kết nối lại** (đánh giá trong khóa ngắn của runtime, đọc trạng thái future đã hoàn tất): `slot == null || (slot.session.terminated().isDone() && slot.session.inputReleased()` hoàn tất **thành công**`)`. Chưa xong → `WAITING_RELEASE`; hoàn tất lỗi → `INPUT_FAULTED`. Hai giây chỉ là mốc cảnh báo ở UI; không `orTimeout`, không complete/cancel `inputReleased()` để mở lại nút (spec 4.2).
- Generation đơn điệu: runtime cấp `++counter` mỗi `connect` được chấp nhận, vì `InputExecutor.beginSession` yêu cầu `gen > maxGen`.

### C7. Thống kê

- `TrafficCounter` hiện được tạo inline ở `HandshakeHandler.java:50`, `ClientSession.java:229`, `ClientSession.java:261` và không với tới được. `Channel` (record `socket, in, out`) thêm thành phần thứ tư `TrafficCounter traffic` và giữ constructor 3 tham số cho test hiện có (`FrameReceiverTest:55`, `ServerSessionAttachTest:61`, `ServerSessionGateTest:141,145`) tạo counter riêng. Mỗi byte vẫn đếm đúng một lần (cùng bộ wrapper).
- `common/TrafficSnapshot(long cmdIn, long cmdOut, long imgIn, long imgOut)` + `ZERO`; `ServerSession.traffic()` và `ClientSession.traffic()` đọc counter của hai `Channel` (0 khi chưa gắn kênh).
- RTT: `Heartbeat.lastRttNanos(): OptionalLong` đã có nhưng `Heartbeat` là trường private của hai phiên → thêm `OptionalLong rttNanos()` trên cả `ServerSession` và `ClientSession`. `Snapshot.stats.rttNanos` = giá trị đó hoặc `NO_RTT`.
- FPS và KiB/s không nằm trong `Snapshot`; `RateSampler` (3.6) tính từ các bộ đếm tích lũy theo **thời gian thực đã trôi qua**, không giả định 1 s.

### C8. Truy cập phiên từ package `ui`

`ServerSession.lifecycle()` là package-private; không mở rộng visibility. Thêm các hàm public ủy quyền trên `ServerSession`: `disconnect()`, `socketsClosed()`, `terminated()`, `closeInfo()`, `rttNanos()`, `traffic()`, `controlView()`, `inputEpoch()`, `controlReleasesSent()`. `ClientSession` đã có `lifecycle()` public và các future cần dùng.

## Task 3.1 — Robot adapters và spike trên Windows

**Files:**
- Create: `src/main/java/com/ltm/rd/client/{RobotPort,AwtRobotPort,DisplayPort,AwtDisplayPort,RobotInputDevice,RobotScreenSource,AdapterFactory,AdapterException,RobotAdapterFactory}.java`
- Create: `src/test/java/com/ltm/rd/client/{FakeRobotPort,RobotInputDeviceTest,RobotScreenSourceTest,RobotAdapterFactoryTest}.java`
- Create: `src/test/java/com/ltm/rd/spike/RobotSpike.java` (có `main`, không chạy trong `mvn verify`)
- Create: `docs/superpowers/reviews/2026-10-20-wave3-spike-robot-results.md` (mẫu kết quả, mọi ca "chưa chạy")

**Interfaces:**
- Consumes (đã đọc code): `InputDevice` — `mouseMove(int absX, int absY)`, `mousePress(int button)`, `mouseRelease(int button)`, `mouseWheel(int notches)`, `keyPress(int keyCode)` (ném `IllegalArgumentException` nếu mã không hỗ trợ), `keyRelease(int keyCode)`; `ScreenSource` — `ScreenGeometry geometry()`, `BufferedImage capture(Rectangle bounds) throws IOException`; `ScreenGeometry(Rectangle bounds, double scaleX, double scaleY)`; `InputExecutor` cộng `bounds.x/y` trước khi gọi `device.mouseMove` (`InputExecutor.java` nhánh `MouseMove`/`MousePress`), nên adapter nhận tọa độ **tuyệt đối** và không được cộng lần hai.
- Produces:

```java
public interface RobotPort {
    void mouseMove(int x, int y);
    void mousePress(int buttonMask);
    void mouseRelease(int buttonMask);
    void mouseWheel(int notches);
    void keyPress(int virtualKey);
    void keyRelease(int virtualKey);
    BufferedImage createScreenCapture(Rectangle bounds);
}
public interface DisplayPort { Rectangle bounds(); double scaleX(); double scaleY(); }
public final class AwtRobotPort implements RobotPort {      // autoDelay 0, autoWaitForIdle false
    public AwtRobotPort() throws AWTException;
}
public final class AwtDisplayPort implements DisplayPort {  // GraphicsEnvironment default screen, getDefaultTransform scale
    public AwtDisplayPort();
}
public interface RobotPortFactory { RobotPort create() throws AdapterException; }

public final class RobotInputDevice implements InputDevice {
    public RobotInputDevice(RobotPortFactory factory) throws AdapterException;
    public void refresh() throws AdapterException;   // chỉ gọi khi InputExecutor IDLE; lỗi giữ nguyên port cũ
    // + sáu hàm InputDevice
}
public final class RobotScreenSource implements ScreenSource {
    public RobotScreenSource(RobotPort port, DisplayPort display);
}

public class AdapterException extends Exception {
    public enum Kind { HEADLESS, ROBOT_UNAVAILABLE, NO_SCREEN }
    public AdapterException(Kind kind, Throwable cause);
    public Kind kind();
}
public interface AdapterFactory {
    ScreenSource newScreenSource() throws AdapterException;    // mỗi connect
    InputDevice inputDevice() throws AdapterException;         // một lần cho cả ứng dụng
    void refreshInputDevice() throws AdapterException;         // trước mỗi connect, khi executor IDLE
}
public final class RobotAdapterFactory implements AdapterFactory {
    public RobotAdapterFactory();
    RobotAdapterFactory(RobotPortFactory ports, Supplier<DisplayPort> display, BooleanSupplier headless);
}
```

**Hành vi:**
- Hai Robot riêng cho cùng màn hình chính: một cho `RobotScreenSource` (chỉ luồng `rd-screen-sender` gọi), một cho `RobotInputDevice` (chỉ `rd-input` gọi). Không chia sẻ khóa.
- Map nút: `button 1/2/3 → InputEvent.getMaskForButton(button)` (`BUTTON1/2/3_DOWN_MASK` = 1024/2048/4096). `mouseWheel` truyền nguyên số có dấu (dương = cuộn xuống, cùng quy ước `MouseWheelEvent`); spike xác nhận chiều.
- `capture` bọc `RuntimeException` của port thành `IOException`; `geometry()` trả bản sao `bounds`.
- Headless hoặc khởi tạo Robot lỗi → `AdapterException` với `Kind.HEADLESS`/`ROBOT_UNAVAILABLE`; không bắt đầu chia sẻ (spec 4.4).
- Đổi cấu hình màn hình: phiên đã đóng bằng `SCREEN_CHANGED`; lần `connect` sau gọi `refreshInputDevice()` và `newScreenSource()` mới.

- [ ] **Step 1: Viết test thất bại [AUTO]** (`FakeRobotPort` ghi log chuỗi `move:x,y`, `press:mask`, `release:mask`, `wheel:n`, `keyPress:vk`, `keyRelease:vk`, `capture:x,y,w,h`)

```java
// RobotInputDeviceTest
@Test void mouseMoveForwardsAbsoluteCoordinatesUnchanged()        // mouseMove(1920,1080) → log ["move:1920,1080"]
@Test void buttonsMapToDownMasks()                                // press(1),press(2),press(3) → press:1024, press:2048, press:4096; release same masks
@Test void wheelForwardsSignedNotches()                           // mouseWheel(-3) → ["wheel:-3"]
@Test void unsupportedKeyPropagatesIllegalArgument()              // port throws IAE on keyPress(999999) → same IAE thrown, log empty
@Test void releaseFailureIsNotSwallowed()                         // port throws on keyRelease(65) → exception reaches caller
@Test void executorAddsBoundsOriginExactlyOnce()                  // InputExecutor bounds (100,50,800,600) + RobotInputDevice: MouseMove(10,20) → ["move:110,70"]
@Test void refreshSwapsPortOnSuccess()                            // factory returns port2 → next move logged on port2
@Test void refreshFailureKeepsOldPortAndReportsRobotUnavailable() // factory throws → AdapterException kind ROBOT_UNAVAILABLE; next move on port1
// RobotScreenSourceTest
@Test void geometryReadsBoundsAndScale()                          // display (0,0,1536,864) scale 1.25/1.25 → ScreenGeometry equal
@Test void captureForwardsGivenBounds()                           // capture(new Rectangle(0,0,1536,864)) → log ["capture:0,0,1536,864"]
@Test void captureRuntimeExceptionBecomesIOException()
@Test void geometryReturnsDefensiveCopy()                         // mutate returned bounds; second geometry() unchanged
// RobotAdapterFactoryTest
@Test void headlessReportsHeadlessAndCreatesNoRobot()             // headless=true: newScreenSource() → AdapterException HEADLESS; port factory calls == 0
@Test void robotCreationFailureReportsRobotUnavailable()
@Test void inputDeviceCreatedOncePerFactory()                     // two inputDevice() calls → one port created, same instance
@Test void newScreenSourceCreatesFreshPortEachTime()              // two calls → two ports
```

- [ ] **Step 2: Chạy, xác nhận thất bại** — `./mvnw.cmd -q test -Dtest='RobotInputDeviceTest,RobotScreenSourceTest,RobotAdapterFactoryTest'`, Expected: COMPILATION ERROR.
- [ ] **Step 3: Implement theo Interfaces** và viết `RobotSpike` (xem dưới).
- [ ] **Step 4: `./mvnw.cmd -q verify`** — Expected: BUILD SUCCESS, không test nào tạo `java.awt.Robot`.
- [ ] **Step 5 [HUMAN] — Spike trên hai máy Windows** (gate G0b; người dùng thực hiện):
  1. Build: `./mvnw.cmd -q test-compile`; chạy `java -cp target/classes;target/test-classes com.ltm.rd.spike.RobotSpike` trên **từng** máy. Chương trình mở cửa sổ thử nghiệm có vùng nhìn thấy được; mọi thao tác dùng `RobotInputDevice`/`RobotScreenSource` qua `InputExecutor`; khối `finally` luôn thử nhả mọi phím/nút đã giữ.
  2. Ghi OS, JDK, kích thước logic (`bounds`), kích thước ảnh `capture` và scale DPI (in sẵn bởi spike).
  3. Với DPI 100%, 125%, 150% (nếu máy hỗ trợ; ghi rõ cấu hình không thử được): capture; bốn góc (kỳ vọng sai lệch 0 pixel logic); click; kéo; wheel cả hai chiều; gõ chữ; Backspace; mũi tên; Shift/Ctrl/Alt giữ; **key repeat**: giữ Backspace và một phím chữ khoảng 2 giây, đếm ký tự trên máy bị điều khiển.
  4. Kiểm tra ảnh capture hợp lệ với `Validation.checkFrameHeader` (kích thước capture sau thu nhỏ so với bounds logic, sai số ≤ 1 pixel): ghi "đạt/không đạt" cho mỗi cấu hình DPI.
  5. Kiểm tra không kẹt phím/nút sau khi thoát spike kể cả khi đóng cửa sổ giữa lúc giữ phím.
  6. Điền `docs/superpowers/reviews/2026-10-20-wave3-spike-robot-results.md`; mục "Quyết định chính sách repeat" ghi `SEND_ALL` (giữ chính sách hiện tại) hoặc `FIRST_PRESS_ONLY`.
- [ ] **Step 6: Commit** — `feat(client): robot adapters behind ports with manual spike`

**Điều kiện đạt (gate G0b):** có bảng OS/JDK/DPI; không lệch tọa độ; không kẹt phím/nút; quyết định repeat dựa trên số đo (giữ `SEND_ALL` chỉ khi không mất và không lặp đôi ký tự; thất bại → đổi policy và test ở 3.5 trước khi tích hợp GUI). Chặn: 3.4–3.6 chỉ tích hợp `RobotAdapterFactory` sau gate này; 3.2 dùng `FakeAdapterFactory`.

Tham chiếu: [Java 21 Robot](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/Robot.html). Capture và thao tác chặn không chạy trên EDT.

## Task 3.2 — AppRuntime, Controller và Snapshot

**Files:**
- Create: `src/main/java/com/ltm/rd/common/TrafficSnapshot.java`
- Modify: `src/main/java/com/ltm/rd/common/Channel.java` — thêm thành phần `TrafficCounter traffic`; constructor 3 tham số cũ tạo `new TrafficCounter()`; `wrap(...)` truyền counter đang dùng.
- Create: `src/main/java/com/ltm/rd/server/{ControlView,InputOutcome}.java`
- Modify: `src/main/java/com/ltm/rd/server/ServerSession.java` — các hàm ở C4, C5, C7, C8.
- Modify: `src/main/java/com/ltm/rd/client/ScreenSender.java` (bộ đếm `framesSent`), `src/main/java/com/ltm/rd/client/ClientSession.java` (`traffic()`, `rttNanos()`, `framesSent()`; lưu `Channel` lệnh khi gắn).
- Create: `src/main/java/com/ltm/rd/ui/{AppConfig,AppRuntime,Controller,Snapshot,TrafficStats,UiPhase,ConnectOutcome,SendResult,InputRelease,ShutdownReport,Messages}.java`
- Test: `src/test/java/com/ltm/rd/common/ChannelTrafficTest.java`, `src/test/java/com/ltm/rd/server/ServerSessionControlTest.java`, `src/test/java/com/ltm/rd/ui/{AppRuntimeServerTest,AppRuntimeClientTest,AppRuntimeEndToEndTest,MessagesTest,FakeAdapterFactory,TestTimeouts}.java`

**Interfaces:**
- Consumes (đã xác minh):
  - `ServerCore(ServerConfig)`, `start() throws IOException`, `close()`, `cmdPort()`, `imgPort()`, `sessions(): SessionManager`, `tickerRegistrations()`.
  - `SessionManager.currentCode(): String`, `regenerateCode(): boolean` (từ chối khi còn phiên hoặc shutting down), `current(): Optional<ServerSession>`, `shuttingDown(): boolean`.
  - `ServerSession`: `generation()`, `peer(): InetAddress`, `phase(): ServerPhase`, `screen(): Optional<Packet.SizePayload>`, `controlEnabled()`, `offerInput(Packet): CommandWriter.OfferResult`, `latestFrame(): Optional<BufferedImage>`, `framesReceived()`, `releaseAllSent()`, `addFrameObserver(FrameObserver)`.
  - `FrameMonitor.revision()` (monitor là trường private của phiên, chỉ truy cập trong `atomically`).
  - `ClientSession(ClientConfig, long generation, InputExecutor, ScreenSource, SocketConnector, ClientHooks, Ticker, ScheduledExecutorService, Executor)`, `start(): boolean`, `activated()`, `phase()`, `cancel()`, `socketsClosed()`, `terminated()`, `authFailure(): Optional<Packet.AuthStatus>`, `inputReleased()`, `releaseAllReceived()`, `lifecycle()`.
  - `InputExecutor(InputDevice, Supplier<Rectangle>, int, ThreadFactory, Consumer<Throwable>)`, `defaultThreadFactory()`, `beginSession(long)`, `shutdownAsync(): CompletableFuture<Void>`, `state(): State`, `lastFault(): Throwable`.
  - `Heartbeat.lastRttNanos(): OptionalLong`; `Ticker(Duration, LongSupplier)`; `Channel.wrap(Socket, TrafficCounter)`; `TrafficCounter.bytesIn()/bytesOut()`.
  - Adapter: `AdapterFactory` của 3.1 (3.2 dùng `FakeAdapterFactory`).
- Produces:

```java
// common
public record TrafficSnapshot(long cmdIn, long cmdOut, long imgIn, long imgOut) { public static final TrafficSnapshot ZERO; }
public record Channel(Socket socket, DataInputStream in, DataOutputStream out, TrafficCounter traffic) {
    public Channel(Socket socket, DataInputStream in, DataOutputStream out);   // counter riêng
}

// server
public enum InputOutcome { SENT, GATE_CLOSED, STALE_EPOCH, OVERFLOW }
public record ControlView(long generation, ServerPhase phase, boolean controlEnabled, long inputEpoch,
        BufferedImage frame, long framesReceived) {}
// ServerSession (thêm)
public long inputEpoch();
public ControlView controlView();                                   // một atomically
public InputOutcome offerInput(long inputEpoch, Packet inputPacket);
public CommandWriter.OfferResult releaseControlInput();
public long controlReleasesSent();
public void disconnect();                                           // close(USER, "disconnected", sendDisconnect=true)
public CompletableFuture<CloseInfo> socketsClosed();
public CompletableFuture<CloseInfo> terminated();
public Optional<CloseInfo> closeInfo();
public OptionalLong rttNanos();
public TrafficSnapshot traffic();

// client
// ClientSession (thêm)
public TrafficSnapshot traffic();
public OptionalLong rttNanos();
public long framesSent();
// ScreenSender (thêm)
public long framesSent();                                           // tăng sau mỗi flush thành công

// ui — chữ ký ở C1, C2, C3; thêm:
public final class Messages {
    public static String forAuth(Packet.AuthStatus status);
    public static String forClose(Packet.DisconnectReason reason);
    public static String forThrowable(Throwable t);                // BindException, ConnectException, AdapterException, IOException khác
    public static final String INPUT_RELEASE_FAILED;               // "Không nhả được phím/nút trên máy này. Hãy khởi động lại ứng dụng."
}
```

`Messages` (giá trị chính xác): `WRONG_CODE` → "Mã không đúng."; `BUSY` → "Máy chủ đang có phiên khác."; `LOCKED` → "Bị khóa tạm thời do nhập sai nhiều lần. Thử lại sau 30 giây."; `BAD_VERSION` → "Phiên bản không tương thích."; `USER` → "Đã ngắt kết nối."; `TIMEOUT` → "Hết thời gian chờ phản hồi."; `PROTOCOL_ERROR` → "Lỗi giao thức hoặc mất kết nối."; `SCREEN_CHANGED` → "Cấu hình màn hình đã thay đổi. Hãy kết nối lại."; `OVERFLOW` → "Hàng đợi lệnh bị đầy, phiên đã đóng."; `SHUTDOWN` → "Ứng dụng đang đóng."; `BindException` → "Cổng đã được sử dụng. Đổi bằng --cmd-port/--img-port."; `ConnectException`/`NoRouteToHostException`/`UnknownHostException` → "Không kết nối được tới máy chủ. Kiểm tra IP, cổng và tường lửa."; `AdapterException` `HEADLESS` → "Không có môi trường đồ họa để chia sẻ màn hình."; `ROBOT_UNAVAILABLE` → "Không tạo được Robot. Kiểm tra quyền điều khiển màn hình của hệ điều hành."; `NO_SCREEN` → "Không đọc được cấu hình màn hình."; mặc định → "Lỗi không xác định. Xem nhật ký để biết loại lỗi."

**Hành vi chính** (ngoài C1–C8): luồng `connect` ở `rd-app`: `refreshInputDevice()` → `newScreenSource()` → tạo `ClientSession` với `ClientConfig(host, cmdPort, imgPort, code, timeouts, nanoClock, decorator)` → kiểm tra cờ hủy/shutdown dưới khóa → `session.start()` ngoài khóa. Lỗi adapter → slot kết thúc, phase `IDLE`, `message = Messages.forThrowable(e)`, generation đã dùng không tái sử dụng. Mã xác thực không rời `connect` → `ClientConfig` (đã có `toString()` không in mã).

- [ ] **Step 1: Viết test thất bại [AUTO]**

```java
// ChannelTrafficTest
@Test void wrapExposesTheCounterItCounts()                        // socket pair: write 10 bytes+flush → traffic().bytesOut()==10; peer reads 10 → its traffic().bytesIn()==10; bytesOut stays 10 (not 20)
@Test void threeArgConstructorGivesIndependentCounter()
// ServerSessionControlTest (server/, fake clock + fake writer như ServerSessionGateTest)
@Test void inputEpochStartsAtZeroAndTracksMonitorRevision()        // before first frame 0; tick→STALL_START → 1; frame→STALL_END → 2; equals the revision seen by the observer
@Test void offerInputWithCurrentEpochIsSent()                      // after first frame: offerInput(0, keyPress(17)) == SENT; writer queue = [KEY_PRESS 17]
@Test void offerInputDuringStallIsGateClosed()                     // epoch 1: offerInput(1, …) == GATE_CLOSED; queue = [RELEASE_ALL]
@Test void offerInputWithOldEpochAfterRecoveryIsStale()            // captured epoch 0; stall+recover → epoch 2; offerInput(0, …) == STALE_EPOCH; offerInput(2, …) == SENT
@Test void epochCheckGateAndOfferAreOneTransaction()               // latch holds tick inside the transaction; offerInput(0, …) on another thread waits; result is GATE_CLOSED or STALE_EPOCH, never SENT; queue = [RELEASE_ALL]
@Test void offerInputRejectsNonInputPackets()                      // offerInput(0, ping(1)) → IllegalArgumentException
@Test void releaseControlInputWorksWhileGateClosed()               // stalled: releaseControlInput() == ACCEPTED; releaseAllSent()==1 (stall) and controlReleasesSent()==1
@Test void releaseControlInputBeforeActiveReturnsClosed()          // RESERVED → CLOSED, nothing queued, controlReleasesSent()==0
@Test void releaseControlInputOverflowClosesSessionWithOverflow()  // writer queue full → OVERFLOW; closeInfo().reason()==OVERFLOW
@Test void controlViewIsOneConsistentSnapshot()                    // after 2 frames: view.framesReceived()==2, view.frame() same object as latestFrame(), controlEnabled true
@Test void controlViewNeverEnabledWithoutFrame()                   // 10_000 iterations, barrier: concurrent publishFrame vs controlView(): never (controlEnabled && frame==null)
@Test void disconnectClosesWithUserAndSendsDisconnect()            // closeInfo = (USER, sendDisconnect=true); terminated() completes
@Test void rttEmptyUntilMatchingPong()                             // rttNanos().isEmpty(); after ping(1)/pong(1) at +40 ms → 40_000_000
@Test void trafficReportsPerChannelBytes()                         // real sockets via ServerCore: cmdOut>0 after SESSION_ACTIVE, imgIn>0 after first frame, cmd and img counted separately
// MessagesTest
@Test void everyDisconnectReasonHasNonEmptyMessage()               // loop DisconnectReason.values(); none contains digits (no code/seq leaked)
@Test void authStatusMessagesAreExact()                            // WRONG_CODE → "Mã không đúng."; LOCKED → "Bị khóa tạm thời do nhập sai nhiều lần. Thử lại sau 30 giây."
@Test void bindExceptionMessageIsExact()
// AppRuntimeServerTest (ServerCore thật, cổng 0, TestTimeouts)
@Test void startServerReachesListening()                           // phase LISTENING; cmdPort>0; imgPort>0; code matches \d{6}; generation 0; controlEnabled false; frame null
@Test void startServerPortInUseFailsThenRetrySucceeds()            // occupy cmdPort → future fails with BindException; phase FAILED; message = bind message; free port → startServer() → LISTENING (new ServerCore)
@Test void startServerWhileListeningFails()                        // IllegalStateException in future
@Test void regenerateCodeOnlyWhenListening()                       // LISTENING: true and code differs; RESERVED/ACTIVE: false and code unchanged
@Test void snapshotToStringNeverContainsCodeOrPeer()
@Test void disconnectReturnsToListeningWithNewCode()               // ACTIVE → disconnect()==true → LISTENING; code != previous; generation 0
@Test void sendInputOnWrongRoleReturnsWrongRole()
// AppRuntimeClientTest
@Test void forClientAdapterFailureThrowsAndStartsNoThread()        // factory throws HEADLESS → AdapterException; no live thread named rd-input
@Test void connectInvalidInputIsRejectedWithoutSideEffects()       // connect(" ", "123456") and connect("127.0.0.1","12345") → INVALID_INPUT; adapters.newScreenSource calls 0; snapshot.generation 0
@Test void connectReachesActiveAndSnapshotShowsPeerAndScreen()     // phase ACTIVE; peer "127.0.0.1"; screen 1920x1080 (FakeScreenSource); generation 1
@Test void doubleConnectSecondIsBusy()                             // connect #2 while #1 CONNECTING → BUSY; newScreenSource calls == 1
@Test void cancelBeforeSessionCreatedNeverStarts()                 // newScreenSource blocked by latch; cancel()==true; release latch → session never started (senderRuns 0, inputBeginCount 0); phase IDLE
@Test void cancelInEachPreActivePhase()                            // parameterized CONNECTING / AUTHENTICATING / ATTACHING via ClientHooks and blocking connector: cancel()==true; terminated(); phase back to IDLE; inputBeginCount 0
@Test void cancelWhenActiveReturnsFalseButDisconnectCloses()
@Test void reconnectBlockedUntilTerminatedAndInputReleased()       // device gate holds keyRelease:17; session closes; connect() == WAITING_RELEASE; advance fake clock +10 s → still WAITING_RELEASE and inputReleased() not done; open gate → connect() == STARTED
@Test void reconnectAfterReleaseFailureIsInputFaulted()            // device.failOn("keyRelease:17") → inputReleased() fails; connect() == INPUT_FAULTED; phase FAILED; message == Messages.INPUT_RELEASE_FAILED; stays so
@Test void generationsIncreaseAcrossConnects()                     // snapshot.generation 1 then 2; both beginSession accepted
@Test void staleSessionCompletionDoesNotOverwriteNewSlot()         // session #1 terminated() completes (latch) after connect #2 started: snapshot shows #2's generation/phase
@Test void wrongCodeShownAsMessageAndPhaseReturnsToIdle()          // server code differs → message "Mã không đúng."; phase IDLE after terminated+released
@Test void adapterFailureOnConnectSetsMessageAndIdle()             // newScreenSource throws ROBOT_UNAVAILABLE → message exact, phase IDLE, connect() allowed again
@Test void inputExecutorKeptAcrossReconnects()                     // adapters.inputDevice() calls == 1 over three connects; refreshInputDevice() calls == 3
// AppRuntimeEndToEndTest (hai runtime cùng JVM, 127.0.0.1)
@Test void serverInputReachesClientDeviceAfterFirstFrame()         // server snapshot controlEnabled → sendInput(gen, epoch, mousePress(100,100,1)) == SENT; client device log: "mouseMove:100,100" then "mousePress:1"
@Test void sendInputWithStaleGenerationIsRejected()                // session gen1 closed, gen2 ACTIVE: sendInput(1, e, keyPress(65)) == STALE_GENERATION; device log has no keyPress:65
@Test void sendInputWithStaleEpochAfterRecoveryIsRejected()        // client capture blocked → STALL_START; unblock → STALL_END; sendInput(gen, oldEpoch, …) == STALE_EPOCH; sendInput(gen, snapshot.inputEpoch, …) == SENT
@Test void releaseControlInputReachesDeviceWhileFrameStalled()     // key 17 held, capture blocked until STALL_START; releaseControlInput(gen) == true; client.releaseAllReceived()==2 (stall + focus); device log "keyRelease:17" exactly once
@Test void oldSessionObserverNotificationDoesNotChangeNewSnapshot()
// shutdown (khung; ngân sách và race ở 4.1)
@Test void shutdownAsyncReturnsSameFutureWhenRepeated()            // f1 == f2 (same instance)
@Test void shutdownSetsShuttingDownImmediately()                   // before future completes: connect()==SHUTTING_DOWN; startServer() fails; snapshot.phase SHUTTING_DOWN
@Test void shutdownClosesActiveSessionAndStopsInputExecutor()      // report.inputRelease==RELEASED; InputExecutor.state()==STOPPED; after future: no live thread named rd-* except rd-shutdown
```

- [ ] **Step 2: Chạy, xác nhận thất bại** — `./mvnw.cmd -q test -Dtest='ChannelTrafficTest,ServerSessionControlTest,MessagesTest,AppRuntime*Test'`, Expected: COMPILATION ERROR.
- [ ] **Step 3: Implement theo Interfaces và C1–C8.** `shutdownAsync()` ở 3.2: CAS bật `shuttingDown`, chạy trên `rd-shutdown`: đóng phiên → `ServerCore.close()` → chờ `terminated()`/`inputReleased()` (giới hạn `shutdownBudget` tổng) → `InputExecutor.shutdownAsync()` → ticker/scheduler/cleanup → hoàn tất future với `ShutdownReport`. 4.1 hoàn thiện thứ tự, ngân sách, race và hook.
- [ ] **Step 4: Chạy `ServerSessionControlTest` và `AppRuntimeEndToEndTest` 3 lần liên tiếp + `./mvnw.cmd -q verify`** — Expected: luôn PASS, vẫn đủ 334 test cũ.
- [ ] **Step 5: Commit** — `feat(ui): app runtime, controller and snapshot with epoch-checked input`
- [ ] **Step 6 [REVIEW]:** reviewer riêng kiểm tra ownership (`AppRuntime` vs `ServerCore`/`ClientSession`), atomicity gate/epoch/offer (`ServerSession.offerInput(long, Packet)`), callback sau close, đường future nhả input (không `orTimeout`, không complete giả). Chỉ sang 3.3 khi mọi invariant được chứng minh; ghi kết quả review thực sự đã chạy.

## Task 3.3 — RemotePanel, CoordinateMapper và SnapshotPump

**Files:**
- Create: `src/main/java/com/ltm/rd/ui/{Edt,CoordinateMapper,RemotePanel,SnapshotPump,SnapshotApplier,SnapshotTimer}.java`
- Test: `src/test/java/com/ltm/rd/ui/{CoordinateMapperTest,RemotePanelTest,SnapshotPumpTest,SnapshotApplierTest}.java`

**Interfaces:**
- Consumes: `Controller.snapshot()`, `Snapshot` (C3); `javax.swing.Timer`; `Validation`-style biên (không dùng).
- Produces:

```java
public final class Edt {
    public static void requireEdt();                 // ném IllegalStateException nếu không phải EDT
    public static void requireNotEdt();
}
public final class CoordinateMapper {
    public record RemotePoint(int x, int y) {}
    /** Rectangle làm tròn dùng chung cho paint và mapper; rỗng nếu panel hoặc remote có cạnh <= 0. */
    public static Optional<Rectangle> imageRect(int panelW, int panelH, int remoteW, int remoteH);
    /** Chỉ điểm nằm trong imageRect; ngoài → rỗng. */
    public static Optional<RemotePoint> map(int mx, int my, int panelW, int panelH, int remoteW, int remoteH);
    /** Như map nhưng kẹp vào [0, remote-1]; dùng cho kéo chuột và release. */
    public static Optional<RemotePoint> mapClamped(int mx, int my, int panelW, int panelH, int remoteW, int remoteH);
}
public final class RemotePanel extends JPanel {
    public record InputGate(long generation, long inputEpoch) {}
    public RemotePanel();
    public void apply(Snapshot s);                   // EDT; bỏ qua snapshot cũ hơn (generation nhỏ hơn, hoặc cùng generation và epoch nhỏ hơn)
    public Optional<InputGate> inputGate();          // có giá trị khi snapshot đã áp dụng có controlEnabled && frame != null && screen > 0
    public Optional<CoordinateMapper.RemotePoint> toRemote(int mx, int my);
    public Optional<CoordinateMapper.RemotePoint> toRemoteClamped(int mx, int my);
    public int remoteWidth(); public int remoteHeight();
}
public final class SnapshotPump implements AutoCloseable {
    public SnapshotPump(Controller controller, Duration period /* 50 ms */);   // luồng rd-snapshot
    public Snapshot latest();                        // AtomicReference, không khóa
    public long polls();                             // cho test
    public void close();
}
public final class SnapshotApplier {                 // logic thuần của Timer, test không cần EDT thật
    public SnapshotApplier(SnapshotPump pump, Consumer<Snapshot> onChange);
    public void tick();                              // gọi onChange đúng một lần khi latest() khác tham chiếu đã áp dụng
}
public final class SnapshotTimer {                   // bọc javax.swing.Timer 50 ms chạy SnapshotApplier.tick
    public SnapshotTimer(SnapshotApplier applier, int periodMs);
    public void start(); public void stop();
}
```

**Công thức:** `scale = min(pw/rw, ph/rh)` (double); `dw = rw*scale`, `dh = rh*scale`; `rect = (round(ox), round(oy), round(dw), round(dh))` với `ox=(pw-dw)/2`, `oy=(ph-dh)/2` và `round = Math.round`; mapper dùng `rect`: `remoteX = floor((mx - rect.x) * rw / (double) rect.width)`, `remoteY = floor((my - rect.y) * rh / (double) rect.height)`; `map` yêu cầu điểm nằm trong `[rect.x, rect.x+rect.width) × [rect.y, rect.y+rect.height)`; `mapClamped` kẹp kết quả vào `[0, rw-1] × [0, rh-1]`. `SnapshotPump` chạy `controller.snapshot()` mỗi 50 ms và `set` vào `AtomicReference`; không có hàng đợi.

- [ ] **Step 1: Viết test thất bại [AUTO]**

```java
// CoordinateMapperTest
@Test void imageRectLandscapeFill()                       // panel 960x540, remote 1920x1080 → (0,0,960,540)
@Test void imageRectLetterboxHorizontal()                 // panel 1000x400, remote 1600x900 → (144,0,711,400)
@Test void imageRectRoundsHalfUp()                        // panel 1000x800, remote 1920x1080 → (0,119,1000,563)  (dh=562.5, oy=118.75)
@Test void imageRectPortraitRemote()                      // panel 800x1000, remote 1080x1920: scale=min(0.7407,0.5208)=0.5208 → (round(…)) khớp công thức; không vượt panel
@Test void emptyWhenPanelOrRemoteIsZero()                 // (0,540,…), (960,0,…), remote 0 → Optional.empty() cho cả ba hàm
@Test void mapCornersAndCenter()                          // panel 960x540, remote 1920x1080: (0,0)→(0,0); (480,270)→(960,540); (959,539)→(1918,1078)
@Test void mapOutsideRectIsEmpty()                        // (960,540) → empty; letterbox (143,10) → empty
@Test void mapClampedKeepsEdges()                         // letterbox 1000x400/1600x900: (143,10)→(0,22); (900,10)→(1599,22); (854,399)→(1597,897)
@Test void resizeChangesMappingConsistently()             // cùng điểm tương đối ở panel 960x540 và 1920x1080 → cùng RemotePoint
@Test void paintAndMapperShareTheSameRect()               // rect do RemotePanel.paint dùng == imageRect (kiểm qua pixel biên ở RemotePanelTest)
// RemotePanelTest (headless, JPanel thường, paint vào BufferedImage)
@Test void paintsLetterboxWithBackgroundAndImageInside()  // frame đỏ 1920x1080, panel 1000x400… pixel (10,10) == nền 0x202020; (500,200) == 0xFF0000
@Test void noScreenInfoPaintsNothingAndMapsEmpty()        // snapshot screenWidth 0 → toRemote empty, pixel nền
@Test void panelZeroSizeMapsEmpty()
@Test void inputGateEmptyBeforeFirstApply()
@Test void inputGateEmptyWhenControlDisabled()            // snapshot controlEnabled=false → empty (frame vẫn vẽ)
@Test void inputGateCarriesGenerationAndEpochOfAppliedSnapshot() // apply(gen 7, epoch 3, enabled, frame) → InputGate(7,3)
@Test void olderSnapshotIsIgnored()                       // apply(gen 7, epoch 3) then apply(gen 6, …) and apply(gen 7, epoch 2) → gate stays (7,3)
@Test void applyOffEdtThrows()                            // gọi từ luồng test (không phải EDT) → IllegalStateException; qua invokeAndWait thì chạy
// SnapshotPumpTest
@Test void pumpKeepsOnlyLatestSnapshot()                  // controller giả trả snapshot frameCount 1..1000 nhanh; sau khi dừng, latest().frameCount() == 1000
@Test void pumpThreadIsNamedRdSnapshotAndStopsOnClose()   // sau close(): không còn luồng "rd-snapshot"
@Test void slowControllerDoesNotQueuePolls()              // snapshot() chặn 200 ms bằng latch: polls() không tăng trong lúc chặn
// SnapshotApplierTest
@Test void slowEdtAppliesOnlyTheLatest()                  // pump nhận 1000 snapshot, không tick; một tick() → onChange gọi đúng 1 lần với frameCount 1000
@Test void unchangedReferenceIsNotReapplied()             // tick() hai lần không có snapshot mới → onChange 1 lần
@Test void applierHoldsNoBacklog()                        // sau 10_000 snapshot, bộ nhớ giữ lại: chỉ latest (kiểm bằng WeakReference tới snapshot thứ 1 sau GC)
```

- [ ] **Step 2: Chạy, xác nhận thất bại** — `./mvnw.cmd -q test -Dtest='CoordinateMapperTest,RemotePanelTest,SnapshotPumpTest,SnapshotApplierTest'`.
- [ ] **Step 3: Implement theo Interfaces.**
- [ ] **Step 4: `./mvnw.cmd -q verify`** — Expected: BUILD SUCCESS.
- [ ] **Step 5: Commit** — `feat(ui): remote panel, coordinate mapper and latest-only snapshot pump`

**Điều kiện đạt:** số snapshot/frame giữ lại hữu hạn (một tham chiếu); không có runnable theo từng frame trên EDT. Tham chiếu: [Swing Timer](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/swing/Timer.html); callback chạy trên EDT, chỉ làm việc ngắn.

## Task 3.4 — Chuột end-to-end

**Files:**
- Create: `src/main/java/com/ltm/rd/ui/InputCapture.java` (phần chuột; 3.5 thêm phần bàn phím vào cùng lớp)
- Test: `src/test/java/com/ltm/rd/ui/{InputCaptureMouseTest,FakeController,MouseEndToEndIntegrationTest}.java`
- Create: `src/test/java/com/ltm/rd/spike/MouseTrialSpike.java` (cửa sổ thử nghiệm thủ công có `main`, dùng `RobotAdapterFactory` thật; dùng lại cho 3.5)
- Create: `docs/superpowers/reviews/2026-10-20-wave3-mouse-trial-results.md` (mẫu kết quả)

**Interfaces:**
- Consumes: `Controller.sendInput(long, long, Packet): SendResult`, `Controller.releaseControlInput(long): boolean`; `RemotePanel.inputGate(): Optional<InputGate>`, `toRemote`, `toRemoteClamped`; `Packet.mouseMove(int x, int y)`, `mousePress(int x, int y, int button)`, `mouseRelease(int x, int y, int button)`, `wheel(int notches)`; `Protocol.MAX_WHEEL_NOTCHES` (20).
- Produces:

```java
public final class InputCapture implements MouseListener, MouseMotionListener, MouseWheelListener /* + KeyListener, FocusListener ở 3.5 */ {
    public InputCapture(Controller sink, RemotePanel panel, BooleanSupplier focusOwner, Runnable requestFocus);
    public void onApplied(long generation, long inputEpoch);   // EDT, gọi sau RemotePanel.apply; thay đổi generation hoặc epoch → resetHeldState()
    public int heldButtonCount();
    public double wheelAccumulator();
    // mouseClicked/mouseEntered/mouseExited: không làm gì
}
```

**Quy tắc (spec 3.2–3.3):**
- `mousePressed`: (1) luôn `swallowed.remove(button)` trước (một lần nhấn mới bắt đầu thao tác mới); (2) `!focusOwner` → `requestFocus.run()`, `swallowed.add(button)`, return; (3) button ngoài 1..3 → bỏ; (4) không có `inputGate` → bỏ; (5) `toRemote` rỗng (ngoài ảnh) → bỏ; (6) gửi `mousePress(rx, ry, button)` với `(gate.generation, gate.inputEpoch)`; chỉ khi kết quả `SENT` mới thêm button vào `held`.
- `mouseDragged`/`mouseMoved`: bỏ nếu tất cả nút đang nhấn (từ `getModifiersEx`) thuộc `swallowed`; không có nút giữ → dùng `toRemote` (ngoài ảnh thì bỏ); có nút trong `held` → dùng `toRemoteClamped` rồi gửi `mouseMove`.
- `mouseReleased`: `swallowed.remove(button)` thành công → bỏ; chỉ gửi nếu `held.remove(button)` thành công; tọa độ `toRemoteClamped`, **luôn gửi** (kể cả sau khi chuột ra ngoài ảnh); nếu `inputGate` rỗng (đang stall) chỉ xóa held — `RELEASE_ALL` do server đã gửi tại `STALL_START`.
- Wheel: `acc += e.getPreciseWheelRotation()`; `n = (int) acc` (cắt về 0), `acc -= n`; chia `n` thành gói ≤ 20 notch, tối đa 3 gói/sự kiện (phần nguyên vượt 60 bị bỏ, phần lẻ giữ); chỉ gửi khi `focusOwner` và có `inputGate`.
- `resetHeldState()`: xóa `held`, `wheelAcc = 0`; **không** xóa `swallowed` (mỗi phần tử tự hết khi release hoặc khi nhấn mới của nút đó). Không khôi phục thao tác kéo cũ.

- [ ] **Step 1: Viết test thất bại [AUTO]** (`FakeController` ghi `(generation, epoch, packet)`, trả `SendResult` cấu hình được; `RemotePanel` thật với snapshot áp dụng qua `invokeAndWait`; `MouseEvent` tạo với source là panel)

```java
// InputCaptureMouseTest — panel 960x540, remote 1920x1080, gate (generation 7, epoch 3), trừ khi ghi khác
@Test void firstClickWithoutFocusOnlyRequestsFocus()      // focusOwner=false: press b1 → requestFocus 1 lần, sent=[]; drag và release của nhấn đó → sent=[]
@Test void swallowIsPerButton()                           // press b1 (không focus, bị nuốt); focusOwner=true; press b3 → sent=[press b3]; release b1 → không gửi; release b3 → sent thêm release b3
@Test void newPressClearsStaleSwallowOfSameButton()       // press b1 không focus (nuốt); không có release; focusOwner=true; press b1 lại → được gửi
@Test void pressUsesEventCoordinates()                    // press(480,270,b1) → mousePress(960,540,1) với (7,3)
@Test void pressOutsideImageIsIgnored()                   // letterbox 1000x400/1600x900: press(143,10,b1) → sent=[]; heldButtonCount 0
@Test void pressRejectedByGateIsNotHeld()                 // sink trả GATE_CLOSED → held 0; release sau đó không gửi gì
@Test void stalePressResultsNeverMarkHeld()               // sink trả STALE_EPOCH và STALE_GENERATION → held 0
@Test void dragOutsideImageClampsAtEdge()                 // letterbox: press trong ảnh, drag tới (143,10) → mouseMove(0,22)
@Test void moveOutsideImageWithoutButtonIsIgnored()
@Test void releaseOutsideImageIsStillSentClamped()        // letterbox: press trong ảnh, release tại (900,10) → mouseRelease(1599,22,1)
@Test void multipleButtonsHeldIndependently()             // press b1, press b2, release b1 → held={2}
@Test void buttonsOutsideOneToThreeAreIgnored()           // getButton()==4 → sent=[]
@Test void eventsIgnoredWithoutGate()                     // chưa áp dụng snapshot nào: press có focus → sent=[]; press không focus vẫn requestFocus
@Test void wheelFractionAccumulates()                     // 0.5 → sent=[]; 0.5 → wheel(1), acc 0.0; −0.5 ×3 → wheel(−1) ở lần 2, acc −0.5 ở lần 3
@Test void wheelSplitsIntoPacketsOfTwenty()               // 45.0 → wheel(20), wheel(20), wheel(5); −45.0 → −20, −20, −5
@Test void wheelCapsAtThreePacketsAndDropsExcessInteger() // 100.0 → 20,20,20, acc 0.0; 61.5 → 20,20,20 và acc 0.5
@Test void wheelRequiresFocusAndGate()
@Test void epochChangeClearsHeldAndWheelAcc()             // press b1 held, wheel 0.5; onApplied(7,4) → held 0, acc 0.0; release b1 sau đó → sent không thêm gì
@Test void generationChangeClearsHeld()                   // onApplied(8,0)
@Test void sameGenerationAndEpochKeepsHeld()              // onApplied(7,3) lặp lại → held giữ
// MouseEndToEndIntegrationTest (server runtime + client runtime cùng JVM, FakeInputDevice, FakeScreenSource 1920x1080)
@Test void clickDragReleaseReachesDeviceInOrder()         // panel 960x540: press tâm → device log "mouseMove:960,540","mousePress:1"; drag → "mouseMove:…"; release → "mouseRelease:1"
@Test void dragOutsideThenReleaseReachesDevice()          // release ngoài ảnh vẫn tới device (mouseRelease:1), không kẹt nút
@Test void lateReleaseAfterEpochChangeIsDropped()         // stall → epoch đổi → UI reset; release muộn không tới server; device nhận "mouseRelease:1" đúng một lần (từ RELEASE_ALL do stall)
@Test void imageStallClosesGateForMouse()                 // capture bị chặn: sendInput → GATE_CLOSED; sau hồi phục + onApplied → gửi lại được
@Test void queueOverflowClosesSessionWithOverflow()       // device chặn bằng Gate; gửi > 256 gói không coalesce → phiên đóng reason OVERFLOW; snapshot message == Messages.forClose(OVERFLOW)
```

- [ ] **Step 2: Chạy, xác nhận thất bại** — `./mvnw.cmd -q test -Dtest='InputCaptureMouseTest,MouseEndToEndIntegrationTest'`.
- [ ] **Step 3: Implement theo Interfaces.**
- [ ] **Step 4: Chạy `MouseEndToEndIntegrationTest` 3 lần liên tiếp + `./mvnw.cmd -q verify`** — Expected: luôn PASS.
- [ ] **Step 5: Commit** — `feat(ui): mouse capture with per-button swallow and epoch-gated input`
- [ ] **Step 6 [HUMAN] — Thử chuột trên hai máy thật:** chạy `RobotAdapterFactory` thật qua một lối vào thử nghiệm của task này (cửa sổ tạm trong `src/test/java/com/ltm/rd/spike/MouseTrialSpike.java`, `main`):
  1. DPI Client 100%/125%/150% (nếu hỗ trợ): bốn góc và tâm không lệch quá 1 pixel logic.
  2. Click lần đầu vào cửa sổ điều khiển chỉ lấy focus; từ lần hai điều khiển bình thường.
  3. Kéo, kéo ra ngoài ảnh rồi thả; đồng thời hai nút; cuộn chuột và touchpad.
  4. Mất focus (Alt+Tab) hoặc rút mạng/Ngắt khi đang giữ nút: nút nhả, không kẹt.
  5. Resize cửa sổ điều khiển, thu nhỏ rồi mở lại: tọa độ vẫn đúng.
  6. Điền `docs/superpowers/reviews/2026-10-20-wave3-mouse-trial-results.md`.

**Điều kiện đạt:** test tự động xanh; kết quả HUMAN ghi rõ DPI đã thử, không lệch tọa độ, không kẹt nút. Ca chưa thử ghi "chưa kiểm chứng".

## Task 3.5 — Bàn phím và nhả toàn bộ input

**Files:**
- Modify: `src/main/java/com/ltm/rd/ui/InputCapture.java` (thêm `KeyListener`, `FocusListener`, `windowDeactivated`, `disconnecting`)
- Test: `src/test/java/com/ltm/rd/ui/{InputCaptureKeyboardTest,KeyboardEndToEndIntegrationTest}.java`
- Create: `docs/superpowers/reviews/2026-10-20-wave3-keyboard-trial-results.md` (mẫu kết quả)

**Interfaces:**
- Consumes: `Controller.sendInput`, `Controller.releaseControlInput(long)`; `Packet.keyPress(int)`, `Packet.keyRelease(int)`; `RemotePanel.inputGate()`; trạng thái `held`/`wheelAcc`/`generation` của `InputCapture` (3.4); `ClientSession.releaseAllReceived()`, `ServerSession.controlReleasesSent()` cho kiểm chứng giao thức.
- Produces:

```java
public enum RepeatPolicy { SEND_ALL, FIRST_PRESS_ONLY }
// InputCapture (thêm)
public InputCapture(Controller sink, RemotePanel panel, BooleanSupplier focusOwner, Runnable requestFocus,
        RepeatPolicy repeatPolicy);                      // constructor 4 tham số của 3.4 dùng DEFAULT_REPEAT_POLICY
public static final RepeatPolicy DEFAULT_REPEAT_POLICY;  // đặt theo kết quả spike 3.1 (mặc định SEND_ALL)
public void keyPressed(KeyEvent e);  public void keyReleased(KeyEvent e);  public void keyTyped(KeyEvent e);   // keyTyped bỏ qua
public void focusGained(FocusEvent e);  public void focusLost(FocusEvent e);
public void windowDeactivated();         // gọi từ WindowListener của ControlFrame
public void disconnecting();             // gọi khi bấm Ngắt/đóng cửa sổ điều khiển
public boolean acceptingInput();         // cờ hasFocus
public int heldKeyCount();
```

**Quy tắc (spec 3.4):** `panel.setFocusable(true)`, `setFocusTraversalKeysEnabled(false)` để nhận Tab; sự kiện phím `consume()` khi đã xử lý. Nhận input khi `acceptingInput && focusOwner && inputGate` có giá trị. `VK_UNDEFINED` bỏ qua; không dùng `keyTyped`; không IME. `SEND_ALL` gửi cả lần lặp; `FIRST_PRESS_ONLY` chỉ gửi `KEY_PRESS` đầu tiên của mỗi lần giữ. Chỉ thêm vào `held` khi `SENT`; `keyReleased` chỉ gửi nếu phím nằm trong `held`. `focusLost`/`windowDeactivated`/`disconnecting`: nếu chưa có yêu cầu nhả đang chờ → `acceptingInput=false`, gọi `releaseControlInput(lastGeneration)` **một lần** (kể cả khi gate ảnh đã đóng), xóa `held` phím/nút, `wheelAcc=0`; các thông báo trùng được gộp cho tới khi `focusGained` đặt lại `acceptingInput=true`. Không khôi phục phím đã giữ khi gate hoặc focus trở lại.

- [ ] **Step 1: Viết test thất bại [AUTO]**

```java
// InputCaptureKeyboardTest (gate generation 7, epoch 3; FakeController đếm releaseControlInput)
@Test void shiftAndCtrlHeldAreSentThenReleased()          // press VK_SHIFT(16), VK_CONTROL(17), A(65); release 65,17,16 → sent keyPress 16,17,65 rồi keyRelease 65,17,16, tất cả (7,3)
@Test void backspaceAndTabAreForwardedAndConsumed()       // VK_BACK_SPACE(8), VK_TAB(9) → keyPress(8), keyPress(9); e.isConsumed() == true
@Test void keyTypedIsNeverSent()
@Test void undefinedKeyIsIgnored()                        // VK_UNDEFINED(0) → sent=[]
@Test void sendAllForwardsRepeats()                       // SEND_ALL: press 8 ba lần → keyPress(8) ×3; heldKeyCount 1
@Test void firstPressOnlyForwardsOnce()                   // FIRST_PRESS_ONLY: press 8 ba lần → keyPress(8) ×1; release 8 → keyRelease(8)
@Test void releaseWithoutHeldIsIgnored()                  // release 65 chưa giữ → sent=[]
@Test void lateReleaseAfterFocusLossIsIgnored()           // press 17; focusLost → releaseControlInput 1 lần; release 17 → sent không thêm keyRelease
@Test void focusLossSendsReleaseOnceAndClearsState()      // giữ phím 17, nút 1, wheel 0.5 → focusLost: releaseControlInput(7) ×1; heldKeyCount 0; heldButtonCount 0; wheelAccumulator 0.0
@Test void duplicateDeactivationNotificationsAreCoalesced() // focusLost + windowDeactivated + disconnecting → releaseControlInput ×1
@Test void eventsIgnoredUntilFocusGained()                // sau focusLost: press 65 bị bỏ; focusGained; press 65 gửi; release 17 (trước đó) vẫn bị bỏ
@Test void focusRegainDoesNotRestoreOldHeldKeys()         // press 17; focusLost; focusGained; release 17 → không gửi
@Test void altTabSequence()                               // press VK_ALT(18) gửi; windowDeactivated → release ×1; release 18 muộn → bỏ
@Test void releaseRequestedEvenWhenGateIsClosed()         // gate rỗng (stall): focusLost vẫn gọi releaseControlInput(lastGeneration)
@Test void noKeysWithoutFocusOwnerOrGate()
@Test void epochChangeClearsHeldKeys()                    // onApplied(7,4) xóa heldKeyCount
// KeyboardEndToEndIntegrationTest (hai runtime cùng JVM)
@Test void modifierHeldThenFocusLossReleasesOnDevice()    // press 17 → device "keyPress:17"; focusLost → device "keyRelease:17" ×1; server controlReleasesSent()==1 và client releaseAllReceived()==1 (đếm ở tầng giao thức)
@Test void networkDropWhileCtrlHeldReleasesOnClient()     // press 17; ngắt phiên server (disconnect hoặc đóng socket) → device "keyRelease:17"; client inputReleased() hoàn tất; phím không giữ ở phiên sau
@Test void releaseLateAfterGateClosedDoesNotExecuteOldEpoch() // stall → epoch đổi; keyRelease cũ không tới server (sendInput trả STALE_EPOCH/GATE_CLOSED); device chỉ nhả một lần
@Test void queueOverflowWhileKeyHeldStillReleasesKey()    // device chặn; tràn hàng đợi → phiên đóng OVERFLOW; sau khi mở gate: device "keyRelease:17" (từ END của generation), inputReleased() hoàn tất
@Test void releaseAllCountersAgreeAcrossEnds()            // sau hai lần mất focus tách biệt: server controlReleasesSent()==2, client releaseAllReceived()==2
```

- [ ] **Step 2: Chạy, xác nhận thất bại** — `./mvnw.cmd -q test -Dtest='InputCaptureKeyboardTest,KeyboardEndToEndIntegrationTest'`.
- [ ] **Step 3: Implement** và đặt `DEFAULT_REPEAT_POLICY` theo mục "Quyết định chính sách repeat" của kết quả spike (nếu spike ghi lặp đôi → `FIRST_PRESS_ONLY`; nếu chưa có kết quả spike thì giữ `SEND_ALL` và ghi rõ trong commit là chưa kiểm chứng).
- [ ] **Step 4: Chạy `KeyboardEndToEndIntegrationTest` 3 lần liên tiếp + `./mvnw.cmd -q verify`** — Expected: luôn PASS.
- [ ] **Step 5: Commit** — `feat(ui): keyboard capture with focus-loss release of all input`
- [ ] **Step 6 [HUMAN] — Thử bàn phím trên hai máy thật** (cùng lối vào thử nghiệm của 3.4):
  1. Gõ chữ, Backspace, Tab, mũi tên; Shift/Ctrl+C/V trong Notepad máy Client.
  2. Key repeat: giữ Backspace và một phím chữ 2 giây; đếm ký tự, xác nhận không lặp đôi (đối chiếu kết quả spike).
  3. Alt+Tab khỏi cửa sổ điều khiển khi đang giữ Ctrl: Ctrl nhả trên Client, không kẹt modifier.
  4. Rút mạng khi giữ Ctrl: Client tự nhả; kết nối lại không giữ phím cũ.
  5. Điền `docs/superpowers/reviews/2026-10-20-wave3-keyboard-trial-results.md`.

**Điều kiện đạt:** log thiết bị chứng minh input được nhả và bộ đếm giao thức khớp; HUMAN ghi quyết định repeat cuối cùng.

## Task 3.6 — Giao diện, launcher và thống kê

**Files:**
- Create: `src/main/java/com/ltm/rd/ui/{RoleChooserFrame,ServerFrame,ClientFrame,ControlFrame,SharingBar,GuiApp,RateSampler,Rates,LocalAddresses,ServerFormState,ClientFormState}.java`
- Modify: `src/main/java/com/ltm/rd/Launcher.java` (dòng 24 in `not implemented yet` được thay)
- Test: `src/test/java/com/ltm/rd/LauncherTest.java`, `src/test/java/com/ltm/rd/ui/{RateSamplerTest,LocalAddressesTest,ServerFormStateTest,ClientFormStateTest}.java`
- Create: `docs/superpowers/reviews/2026-10-20-wave3-gui-trial-results.md` (mẫu kết quả)

**Interfaces:**
- Consumes: `LaunchOptions` — `record LaunchOptions(Role role, boolean help, int cmdPort, int imgPort, String host)`, `LaunchOptions.parse(String[])`, `LaunchOptions.USAGE`, `Role {CHOOSER, SERVER, CLIENT}`; `AppRuntime.forServer/forClient`, `AppConfig.from`, `Controller`, `Snapshot`, `SnapshotPump`, `SnapshotApplier`, `SnapshotTimer`, `RemotePanel`, `InputCapture`, `RobotAdapterFactory`, `Messages`.
- Produces:

```java
// Launcher
public final class Launcher {
    public static final int STAY_RUNNING = -1;
    static int run(String[] args, PrintStream out, PrintStream err, boolean headless, GuiLauncher gui);
    @FunctionalInterface interface GuiLauncher { int launch(LaunchOptions options) throws Exception; }
    public static void main(String[] args);   // code = run(..., GraphicsEnvironment.isHeadless(), GuiApp::launch); code >= 0 → System.exit(code)
}
// Mã thoát: 0 = --help; 2 = tham số sai (như hiện tại, in lỗi rồi USAGE); 4 = cần GUI nhưng headless
// (stderr: "graphical environment required (java.awt.headless=true)"); STAY_RUNNING = giao diện đã mở.

public final class GuiApp {                         // EDT bootstrap
    public static int launch(LaunchOptions options) throws Exception;   // trả STAY_RUNNING
}
// Rates
public record Rates(double fps, double cmdKiBps, double imgKiBps, long rttNanos) { public static final Rates ZERO; }
public final class RateSampler {
    public RateSampler(LongSupplier nanoClock);
    public Rates sample(Snapshot s);                // so với lần sample trước; generation đổi hoặc lần đầu → Rates.ZERO và đặt baseline
}
public final class LocalAddresses {
    public static List<String> ipv4();                                  // NetworkInterface thật
    static List<String> filterIpv4(Collection<InetAddress> all);        // loại loopback, link-local, IPv6; sắp xếp tăng dần theo chuỗi
}
// Form state thuần (test không cần cửa sổ)
public record ServerFormState(boolean regenerateEnabled, boolean disconnectEnabled, boolean controlWindowVisible, String statusText) {
    public static ServerFormState of(Snapshot s, boolean closingWarning);
}
public record ClientFormState(boolean fieldsEnabled, boolean connectEnabled, boolean cancelVisible,
        boolean disconnectVisible, boolean sharingBarVisible, String statusText) {
    public static ClientFormState of(Snapshot s, boolean closingWarning);
}
```

**Hành vi:**
- Không tham số: `RoleChooserFrame` chọn vai trò. Server: `ServerFrame` hiển thị IPv4 (`LocalAddresses.ipv4()`), cổng, mã lớn, peer, nút *Đổi mã* (bật chỉ khi `LISTENING`) và *Ngắt*; khi `ACTIVE` mở `ControlFrame` (RemotePanel + thanh trạng thái FPS, KiB/s từng kênh, RTT, cảnh báo ảnh gián đoạn); đóng `ControlFrame` ≡ *Ngắt* (`InputCapture.disconnecting()` rồi `disconnect()`).
- Client: ô IP, ô mã, *Kết nối*/*Hủy*; bấm *Kết nối* là đồng ý chia sẻ. Khi `ACTIVE` luôn có `SharingBar` (`setAlwaysOnTop(true)`) với "Đang bị điều khiển bởi `<peer>`" và nút *Dừng*; đóng cửa sổ chính ≡ *Dừng*.
- `--host` chỉ điền sẵn ô IP, không tự `connect`. Cổng `--cmd-port/--img-port` đi qua `AppConfig`.
- `closingWarning = true` khi phase `CLOSING` kéo dài ≥ 2 giây (đo bằng fake/real clock ở UI): `statusText = "Đang chờ nhả phím/nút trên máy này…"`, nhưng `connectEnabled` vẫn `false`.
- Thống kê: `Swing Timer` 1000 ms trên EDT gọi `RateSampler.sample(pump.latest())` và cập nhật nhãn; `fps = Δ frameCount / Δ giây thực`, `KiB/s = Δ (in+out) / 1024 / Δ giây thực` cho từng kênh; RTT từ `stats.rttNanos` (`NO_RTT` hiển thị "—").
- Mọi thay đổi Swing trên EDT; khởi tạo/chờ mạng, capture, decode và shutdown không chặn EDT (Global Constraints).

- [ ] **Step 1: Viết test thất bại [AUTO]**

```java
// LauncherTest (không gọi System.exit; GuiLauncher giả đếm lần gọi)
@Test void helpWorksInHeadless()                           // run(["--help"], …, headless=true) == 0; out == LaunchOptions.USAGE; gui không được gọi
@Test void invalidOptionExitsTwoWithUsage()                // ["--bogus"] → 2; err bắt đầu bằng "Unknown option: --bogus" và chứa USAGE
@Test void serverRoleInHeadlessExitsFourWithoutGui()       // ["--server"], headless → 4; err chứa "graphical environment required"; gui gọi 0 lần
@Test void chooserInHeadlessExitsFour()                    // [] headless → 4
@Test void clientHostIsPassedToGuiWithoutConnecting()      // ["--client","--host","10.0.0.5"], headless=false → gui nhận options.host()=="10.0.0.5"; run trả STAY_RUNNING
@Test void portsArePassedToGui()                           // ["--server","--cmd-port","6001","--img-port","6000"] → gui nhận 6001/6000
@Test void notImplementedMessageIsGone()                   // mọi tham số hợp lệ: err không chứa "not implemented yet"
@Test void sameCmdAndImgPortStillRejected()                // ["--cmd-port","5000","--img-port","5000"] → 2 (validation hiện có)
// RateSamplerTest (FakeClock)
@Test void firstSampleIsZero()                             // Rates.ZERO
@Test void fpsFromFrameCountOverElapsed()                  // t=0 frameCount 0; t=2.0 s frameCount 24 → fps 12.0
@Test void rateUsesRealElapsedNotNominalSecond()           // t=1.5 s, +18 frame → fps 12.0 (không phải 18.0)
@Test void kibPerSecondPerChannel()                        // img +102400 byte (in+out) trong 2 s → 50.0; cmd +2048 byte trong 2 s → 1.0
@Test void generationChangeResetsBaseline()                // phiên mới có counter nhỏ hơn: không âm, mẫu đầu sau đổi = ZERO
@Test void rttPassedThroughAndNoRttKept()                  // rttNanos 40_000_000 → 40_000_000; NO_RTT → NO_RTT
// LocalAddressesTest
@Test void filtersLoopbackLinkLocalAndIpv6()               // [127.0.0.1, 192.168.1.5, fe80::1, 10.0.0.2, 169.254.3.4] → ["10.0.0.2","192.168.1.5"]
// ServerFormStateTest
@Test void regenerateEnabledOnlyWhenListening()            // LISTENING true; RESERVED, ACTIVE, CLOSING false
@Test void controlWindowVisibleOnlyWhenActive()
@Test void disconnectEnabledWhenReservedOrActive()
// ClientFormStateTest
@Test void idleAllowsConnect()                             // fieldsEnabled true, connectEnabled true, cancelVisible false
@Test void preActivePhasesShowCancelAndLockFields()        // CONNECTING/AUTHENTICATING/ATTACHING → cancelVisible true, fieldsEnabled false, connectEnabled false
@Test void activeShowsSharingBarWithPeerAndStop()          // sharingBarVisible true; statusText "Đang bị điều khiển bởi 192.168.1.5"; disconnectVisible true
@Test void closingKeepsConnectDisabled()                   // CLOSING → connectEnabled false
@Test void closingWarningAfterTwoSecondsDoesNotEnableConnect() // closingWarning=true → statusText "Đang chờ nhả phím/nút trên máy này…"; connectEnabled false
@Test void failedInputKeepsConnectDisabledAndShowsMessage() // FAILED + Messages.INPUT_RELEASE_FAILED
@Test void idleWithMessageShowsReasonAndAllowsRetry()       // IDLE + "Mã không đúng." → connectEnabled true
```

- [ ] **Step 2: Chạy, xác nhận thất bại** — `./mvnw.cmd -q test -Dtest='LauncherTest,RateSamplerTest,LocalAddressesTest,ServerFormStateTest,ClientFormStateTest'`.
- [ ] **Step 3: Implement theo Interfaces.** `GuiApp` ghép `RobotAdapterFactory` thật (đã qua gate 3.1).
- [ ] **Step 4: `./mvnw.cmd -q verify`, rồi `java -jar target/remote-desktop.jar --help`** — Expected: BUILD SUCCESS; lệnh in `LaunchOptions.USAGE` và thoát 0 (kể cả trên máy không có màn hình).
- [ ] **Step 5: Commit** — `feat: swing gui, real launcher and live stats`
- [ ] **Step 6 [HUMAN] — Thử giao diện và hai vai trò trên hai máy thật:**
  1. Mở JAR không tham số trên cả hai máy: chọn Server ở máy A, Client ở máy B; nhập IP + mã, *Kết nối*; thanh chia sẻ nổi trên cùng hiện peer và nút *Dừng*.
  2. Điều khiển chuột + bàn phím như 3.4/3.5; thống kê FPS, KiB/s từng kênh, RTT cập nhật khoảng 1 giây.
  3. *Hủy* khi kết nối tới IP không tồn tại: giao diện phản hồi ngay, về `IDLE`.
  4. *Ngắt* (Server) và *Dừng* (Client) bấm đồng thời; kết nối lại ngay; mã cũ bị từ chối.
  5. `--host`, `--cmd-port`, `--img-port` hoạt động; cổng bị chiếm báo lỗi rõ.
  6. Đóng từng cửa sổ giữa phiên: tiến trình Java thoát (kiểm tra `jps`).
  7. Điền `docs/superpowers/reviews/2026-10-20-wave3-gui-trial-results.md`.
- [ ] **Step 7 [REVIEW] — Review cuối đợt:** đối chiếu spec với hành vi GUI/Robot, ownership tài nguyên, generation/epoch và các đường nhả input; ghi kết quả review thực sự đã chạy.

**Điều kiện đạt đợt 3:** điều khiển được hai máy thật với kết quả HUMAN đã điền (3.1, 3.4, 3.5, 3.6); `./mvnw.cmd -q verify` xanh; review 3.2 và 3.6 không còn finding mở. Chưa đánh dấu đủ điều kiện phát hành trước đợt 4.

## Các tình huống đã dùng để rà kế hoạch

| Tình huống | Cơ chế và bằng chứng cần có |
| --- | --- |
| Capture chậm | Robot riêng, không khóa input; spike 3.1 và test port |
| EDT chậm | Latest-only `SnapshotPump`; `slowEdtAppliesOnlyTheLatest` |
| Ảnh gián đoạn rồi hồi phục giữa hai refresh | `inputEpoch = revision`; `epochCheckGateAndOfferAreOneTransaction`, `sendInputWithStaleEpochAfterRecoveryIsRejected` |
| Mất focus khi gate đã đóng | `releaseControlInput` không phụ thuộc gate; `releaseControlInputWorksWhileGateClosed` |
| Nhả phím chậm hoặc lỗi | Giữ future thật; `reconnectBlockedUntilTerminatedAndInputReleased`, `reconnectAfterReleaseFailureIsInputFaulted` |
| Callback từ phiên cũ | Snapshot suy ra từ phiên hiện tại; `staleSessionCompletionDoesNotOverwriteNewSlot` |
| CI xanh nhưng Robot lỗi | Gate HUMAN riêng, mẫu kết quả "chưa chạy" |

Các tiêu chí trên là yêu cầu phải kiểm chứng khi triển khai, không phải kết quả thử nghiệm đã có.

## Nhật ký chi tiết hóa

Đối chiếu kế hoạch cũ với code ở `HEAD 3158e5b` và cách giải quyết:

1. **`TrafficCounter` không với tới được.** Counter được tạo inline ở `HandshakeHandler.java:50` và `ClientSession.java:229,261`; `Channel` không giữ nó. Giải quyết: thêm thành phần `traffic` vào `Channel` (giữ constructor 3 tham số cho 4 chỗ test đang dùng) thay vì đếm lần hai (C7).
2. **`ServerSession.lifecycle()` là package-private** nên package `ui` không lấy được `terminated()`/`socketsClosed()`. Giải quyết: hàm ủy quyền public trên `ServerSession`, không mở rộng visibility (C8).
3. **`Heartbeat` là trường private** của hai phiên; `lastRttNanos()` có nhưng không dẫn tới UI. Giải quyết: `rttNanos()` trên cả hai phiên (C7).
4. **`releaseAllSent()` chỉ đếm RELEASE_ALL do stall** (đợt 2 khẳng định `== 1`). Giải quyết: bộ đếm mới `controlReleasesSent()` cho RELEASE_ALL do mất focus.
5. **`offerInput(Packet)` hiện có không mang epoch.** Giải quyết: thêm overload `offerInput(long, Packet)`, giữ hàm cũ cho test đợt 2.
6. **`FrameMonitor.revision()` tăng cả ở `STALL_END` và `TIMEOUT`**, trong khi kế hoạch cũ nói epoch tăng "khi ảnh chuyển sang gián đoạn". Giải quyết: `inputEpoch = revision()`; mọi thay đổi epoch làm UI xóa held state, hậu quả an toàn (phím đã được `RELEASE_ALL` nhả ở `STALL_START`).
7. **`InputExecutor` nhận một `InputDevice` cố định khi tạo** nhưng 3.1 yêu cầu tạo lại Robot khi kết nối lại và 3.2 yêu cầu giữ executor suốt đời app. Giải quyết: `RobotInputDevice.refresh()` thay port, chỉ gọi khi executor `IDLE` (đảm bảo bởi điều kiện kết nối lại); `ScreenSource` tạo mới mỗi `connect` (C6).
8. **`ServerCore.start()` lỗi để lại `started=true`**, core không dùng lại được. Giải quyết: `startServer()` tạo `ServerCore` mới cho mỗi lần thử và runtime sở hữu core trước khi `start()` (C2).
9. **`ScreenSender` không đếm frame**, không tính được FPS phía Client. Giải quyết: `ScreenSender.framesSent()` và `ClientSession.framesSent()`.
10. **`LaunchOptions.Role` có `CHOOSER`** còn `AppRuntime` chỉ một vai trò. Giải quyết: `CHOOSER` bị từ chối; chooser tạo runtime sau khi chọn.
11. **`Launcher.java:24` in `not implemented yet` và thoát 3.** Giải quyết: thay bằng `Launcher.run` có thể test; mã 3 bỏ, thêm mã 4 cho yêu cầu GUI trong headless; `--help` và lỗi tham số giữ nguyên hành vi.
12. **Surefire chưa có cấu hình nhóm test phần cứng.** Giải quyết: spike là lớp `main` có tên không khớp mẫu Surefire, không sửa `pom.xml`.
13. **Quy tắc `Validation.checkFrameHeader` (sai số tỷ lệ ≤ 1 pixel) có thể lệch khi `Robot.createScreenCapture` trả kích thước vật lý ở DPI khác 100%.** Giải quyết: spike 3.1 ghi kích thước capture và kết quả kiểm tra header cho từng DPI; nếu không đạt thì sửa trước khi tích hợp GUI.
14. **`SessionLifecycle.startWorker` có thêm overload `startWorker(String, Runnable, Runnable onSkipped)`** không có trong plan đợt 2; không ảnh hưởng đợt 3, ghi lại để reviewer biết API thực tế.
