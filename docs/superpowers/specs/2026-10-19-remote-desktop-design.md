# Thiết kế: Phần mềm điều khiển máy tính từ xa (Java thuần)

- Ngày: 2026-10-19
- Trạng thái: **baseline thiết kế v1 để triển khai**; chưa xác nhận sản phẩm hoạt động ổn định trước khi qua các gate kiểm thử.
- Bản sửa 4: rà hợp đồng đồng thời, quyền sở hữu tài nguyên và tính kiểm thử được; xem mục 1.9, 3.5 và nhật ký cuối file.
- Kế hoạch triển khai: [2026-10-19-remote-desktop-implementation.md](../plans/2026-10-19-remote-desktop-implementation.md).
- Bản sửa 2: đã áp dụng 10 góp ý của vòng review thứ 2 (xem cuối Nhật ký quyết định)
- Bản sửa 3: đã áp dụng góp ý của vòng review thứ 3 và thiết kế lại `InputExecutor`, `CommandWriter`, `Session` theo hướng module sâu (deep module): interface nhỏ, các quy tắc thứ tự, khóa và deadline nằm gọn bên trong module
- Công nghệ: JDK 21, Maven, Socket TCP (blocking I/O), `java.awt.Robot`, Swing, JUnit 5 (chỉ để test)

---

## 0. Mục tiêu và phạm vi

### 0.1 Mục tiêu
Làm đồ án môn Lập trình mạng: một máy điều khiển được một máy Windows khác trong mạng LAN. Phải demo được trên 2 máy thật, và code đủ rõ ràng để giải thích khi bảo vệ.

### 0.2 Quy ước vai trò
Vai trò TCP được xác định theo bên nào mở cổng lắng nghe. Phải ghi rõ trong báo cáo để tránh nhầm.

- **Server**: máy **điều khiển**. Mở cổng lắng nghe, hiện mã 6 số, nhận ảnh màn hình và gửi thao tác chuột/phím.
- **Client**: máy **bị điều khiển**. Người ngồi ở máy này nhập IP và mã của Server rồi bấm *Kết nối*; bấm *Kết nối* cũng có nghĩa là đồng ý chia sẻ màn hình. Client gửi ảnh và thực thi thao tác nhận được.

### 0.3 Phạm vi phiên bản 1
- Có:
  - Một máy Windows, chỉ màn hình chính, trong LAN tin cậy.
  - Xem màn hình; điều khiển chuột (di chuyển, nhấn, thả, kéo, cuộn) và bàn phím (theo mã phím ảo VK).
  - Kết nối bằng IP + mã 6 số; Client có nút *Dừng* để kết thúc ngay.
  - Hiển thị trạng thái kết nối, FPS, lưu lượng (KB/s) và RTT (thời gian khứ hồi).
- Chưa có (để dành cho phần mở rộng):
  - Truyền file, âm thanh, clipboard.
  - Nhiều màn hình.
  - Kết nối qua Internet và TLS.
  - Gõ tiếng Việt qua IME; tổ hợp phím hệ thống (Win, Alt+Tab, Ctrl+Alt+Del).
  - Điều khiển cửa sổ chạy quyền admin, màn hình UAC hoặc màn hình khóa.

### 0.4 Bảo mật
Ảnh, lệnh và mã xác thực đều truyền **không mã hóa**, nên chỉ dùng trong LAN tin cậy. Nếu muốn dùng qua Internet phải thiết kế thêm TLS (JSSE) hoặc đường hầm bảo mật.

---

## 1. Kiến trúc, trạng thái phiên, luồng và cleanup

### 1.1 Các phương án đã cân nhắc
- **Đã chọn: blocking I/O, hai kênh TCP.**
  - Cổng 5001: kênh lệnh (gói nhỏ, cần độ trễ thấp).
  - Cổng 5000: kênh ảnh (gói lớn).
  - Mỗi kênh có luồng đọc riêng; code tuyến tính, dễ debug.
- **Loại: NIO `Selector`.** Chỉ có lợi khi có nhiều kết nối. Phải tự xử lý gói bị cắt giữa chừng trong `ByteBuffer`, phức tạp hơn mà không cần thiết cho một phiên.
- **Loại: một kênh TCP dùng chung.** Gói ảnh lớn sẽ chặn lệnh chuột phía sau (head-of-line blocking).

### 1.2 Thành phần
Một jar duy nhất, `remote-desktop.jar`, chạy bằng `--server` hoặc `--client`. Không có tham số thì hiện cửa sổ chọn vai trò. Package gốc là `com.ltm.rd`.

**`common`**
- `PacketType`: enum loại gói (mã byte, độ dài cố định nếu có, độ dài tối đa).
- `Packet`: record `(PacketType type, byte[] payload)` kèm các hàm tạo/đọc; copy buffer hoặc chuyển quyền sở hữu rõ ràng để payload không đổi sau enqueue.
- `PacketIO`: `read(DataInputStream, int channelMaxLen, Set<PacketType> allowed)` và `write(DataOutputStream, Packet)`. Kiểm tra mọi giới hạn ở Phần 2.
- `Protocol`: hằng số cổng, giới hạn kích thước, timeout, magic, version.
- `Channel`: record `(Socket, DataInputStream, DataOutputStream)` dùng khi bàn giao (mục 1.5.1).
- `CommandWriter`: hàng đợi có giới hạn cộng **một luồng ghi duy nhất** cho kênh lệnh; cả hai bên đều dùng. Interface (mục 1.6):
  - `offer(Packet) → boolean`: không chặn.
  - `closeGracefully(Packet finalOrNull) → CompletableFuture<Void>`: không chặn.
  - `writeStartedAtNanos()`: dùng cho watchdog.
- `Watchdog`: theo dõi lần ghi đang chạy và deadline.
- `SessionBase`: phần dùng chung của `Session` (Server) và `ClientSession`, gồm:
  - `phase`
  - `register(Closeable)`
  - `closeIf(expectedPhase, reason)`
  - `closeSession(reason)`
  - `isClosed()`

  Đặc tả ở mục 1.8.
- `TrafficCounter`: `FilterInputStream`/`FilterOutputStream` đếm số byte để tính lưu lượng.

**`server`**
- `ServerApp`: điểm khởi động; mở 2 cổng và chạy 2 luồng accept; có cờ `shuttingDown`.
- `PendingConnections`: tập các socket đã `accept()` nhưng **chưa bàn giao**. Socket rời khỏi tập khi bàn giao xong hoặc bị đóng. `closeAll()` dùng khi tắt ứng dụng (mục 4.5).
- `SessionManager`: giữ trạng thái phiên, mã 6 số, giới hạn số lần thử theo IP, `tryReserve()`.
- `Session`: token, 2 socket, `CommandWriter`, cờ `closed`, `closeSession(reason)`.
- `HandshakeHandler`: xử lý gói đầu tiên (`AUTH` hoặc `ATTACH`) trên từng kết nối.
- `FrameReceiver`: đọc `FRAME`, kiểm tra kích thước và giải mã JPEG, đặt khung mới nhất vào `AtomicReference`.
- `RemotePanel`: vẽ khung hình giữ đúng tỷ lệ, bắt sự kiện chuột/phím.
- `CoordinateMapper`: quy đổi tọa độ từ panel sang màn hình Client.
- `InputCapture`: chuyển sự kiện Swing thành packet; theo dõi phím/nút đang giữ; gửi `RELEASE_ALL` khi mất focus.
- `ServerGUI`.

**`client`**
- `ClientApp`: điểm khởi động.
- `ClientSession`: kết nối, bắt tay, trạng thái, `closeSession(reason)`.
- `ScreenSource`: interface chụp màn hình. Bản thật dùng Robot (`RobotScreenSource`); bản giả dùng cho test.
- `ScreenSender`: chụp, thu nhỏ, nén JPEG, ghi `FRAME`. **Là nơi duy nhất ghi kênh ảnh.**
- `CommandReceiver`: đọc kênh lệnh, rồi chuyển lệnh điều khiển cho `InputExecutor.submit()`. Hàm này không chặn, nên luồng đọc không bao giờ phải chờ Robot.
- `InputDevice`: interface `mouseMove/press/release/wheel/keyPress/keyRelease`. Bản thật dùng Robot (`RobotInputDevice`); bản giả dùng cho test.
- `InputExecutor`: sống **suốt vòng đời ứng dụng** (không tạo lại mỗi phiên), có một luồng riêng `rd-input`; là nơi **duy nhất** gọi `InputDevice`. Interface gồm 3 hàm, tất cả đều không chặn (mục 3.5):
  - `beginSession(long sessionGen) → boolean`
  - `submit(long sessionGen, Command) → SubmitResult`
  - `endSession(long sessionGen) → CompletableFuture<Void>`: future hoàn tất khi đã nhả hết phím và nút.
- `ClientGUI`.

Hai interface `ScreenSource` và `InputDevice` giúp test toàn bộ luồng mạng trên localhost mà không cần màn hình thật hay Robot.

### 1.3 Trạng thái

**Trạng thái phiên trên Server.** Mỗi Session có một phase có thẩm quyền dưới khóa phiên; SessionManager giữ current session và mã dưới khóa manager. LISTENING nghĩa là không có current session và ứng dụng chưa shutdown. Không tạo hai bản phase phải cập nhật bằng tay. Khi cần cả hai khóa dùng manager → session (mục 1.9).

```
LISTENING ──tryReserve() ok──▶ RESERVED ──SCREEN_INFO + ATTACH ok──▶ ACTIVE
    ▲                              │ hết 5 s / lỗi                     │ Ngắt / timeout / lỗi
    │                              ▼                                    ▼
    └────────── đóng xong ─────── CLOSING ◀─────────────────────────────┘
```

- `tryReserve(ip, code)` làm các bước sau trong **một** khối `synchronized`:
  1. Kiểm tra trạng thái là `LISTENING`.
  2. Kiểm tra IP không bị khóa.
  3. Kiểm tra mã đúng.
  4. Tạo `Session` với token 16 byte từ `SecureRandom`, chuyển trạng thái sang `RESERVED`.

  Nếu trạng thái đang là `RESERVED`, `ACTIVE` hoặc `CLOSING`, trả `AUTH_RESULT(BUSY)` rồi đóng kết nối.
- Ở `RESERVED`: `SCREEN_INFO` hợp lệ và `ATTACH` hợp lệ có thể đến theo **thứ tự bất kỳ**. Đủ cả hai trong vòng 5 giây thì gửi `SESSION_ACTIVE` và chuyển sang `ACTIVE`; quá hạn thì `closeIf(RESERVED, "attach timeout")`.
- `ACTIVE` có thêm cờ `controlEnabled`. Chỉ bật sau khi frame hợp lệ đầu tiên đã được EDT đưa vào trạng thái hiển thị của panel. Khi cờ tắt, `InputCapture` không tạo thao tác điều khiển; gói quản lý `RELEASE_ALL` vẫn được gửi. Cờ cũng tắt khi ảnh gián đoạn (mục 1.7).
- Token chỉ attach được **một lần** và hết hiệu lực khi phiên đóng. Khi attach sai token, dùng token cũ hoặc attach lần thứ hai: chỉ đóng kết nối ảnh đó, phiên hợp lệ vẫn giữ nguyên.
- Mã 6 số tạo bằng `SecureRandom`. Mã chỉ đổi khi phiên kết thúc hoặc khi người dùng bấm *Đổi mã* ở `LISTENING`. **Nhập sai không làm đổi mã**, nếu không người khác chỉ cần nhập sai liên tục là người dùng thật không kết nối được.
- Giới hạn thử theo IP:
  - Mỗi lần sai, chờ 1 giây rồi mới trả kết quả, ngoài khóa trạng thái và vẫn chịu deadline tổng kết nối.
  - Sai 3 lần thì khóa IP đó 30 giây; trong thời gian khóa, mọi lần thử đều nhận `AUTH_RESULT(LOCKED)`.
- Trong lúc `CLOSING`, Server không nhận phiên mới.
- Bảng giới hạn IP tối đa 1024 entry, dọn entry hết hiệu lực; khi đầy loại entry hết hạn trước, nếu không có thì từ chối thử mới mà không tăng bộ nhớ. Không log code/token. Khoảng chờ trả sai mã không giữ khóa manager.

**Bắt tay trên từng kết nối** là trạng thái riêng của mỗi kết nối, không phải trạng thái chung của Server. Nhờ vậy, một kết nối mở ra rồi im lặng không chặn được người dùng thật.
- Luồng accept chỉ gọi `accept()` rồi giao kết nối cho pool `HandshakeHandler`. Pool được khai báo rõ: `ThreadPoolExecutor(core = 4, max = 4, new ArrayBlockingQueue<>(8))`. Khi bị từ chối (4 luồng đều bận và hàng đợi đã đủ 8), `RejectedExecutionHandler` đóng socket ngay.
- Ngay sau `accept()`, socket được bọc trong một đối tượng `PendingConnection` và thêm vào `PendingConnections`. `PendingConnection` có cờ `AtomicInteger owner` với ba giá trị: `PENDING`, `HANDED_OVER`, `CLOSED`.
  - **Bàn giao:** `claimForHandover()` = `owner.compareAndSet(PENDING, HANDED_OVER)`.
  - **Đóng (do deadline, bị pool từ chối, hoặc shutdown):** `claimForClose()` = `owner.compareAndSet(PENDING, CLOSED)`, thành công rồi mới đóng socket.
  - Chỉ một trong hai thao tác trên thành công. Nhờ đó, callback deadline đã bắt đầu chạy cũng không đóng được socket đã bàn giao cho phiên. Ngược lại, socket đã bị đóng thì không bao giờ được bàn giao.
- **Deadline tổng là 3 giây, tính từ lúc `accept()`**, áp dụng cho cả thời gian nằm trong hàng đợi của pool. Ngay khi accept, scheduler đặt lịch `if (conn.claimForClose()) closeQuietly(socket)` sau 3 giây. Bắt tay xong thì hủy lịch đó; việc hủy chỉ để đỡ tốn tài nguyên, tính đúng đắn do CAS bảo đảm. Cách này chặn được kiểu gửi nhỏ giọt (ví dụ mỗi 2,9 giây một byte), vì `setSoTimeout()` chỉ giới hạn *từng* lần đọc.
- `setSoTimeout(3000)` vẫn được đặt, nhưng chỉ là lớp dự phòng.
- Khi lấy kết nối từ hàng đợi ra mà `owner != PENDING`, handler bỏ qua kết nối đó, không đọc gì.

**Trạng thái trên Client:**
`IDLE → CONNECTING → AUTHENTICATING → ATTACHING → ACTIVE → CLOSING → IDLE`

**Deadline phase:** callback gọi `session.closeIf(expectedPhase, reason)`, kiểm tra phase và đánh dấu đóng trong cùng khóa. Hủy scheduled future chỉ tiết kiệm tài nguyên, không thay thế kiểm tra này. Pending handshake dùng CAS và kiểm tra deadline riêng; watchdog liveness/frame/write phải kiểm tra thêm timestamp/operation hiện tại theo mục 1.9.

### 1.4 Luồng phiên
1. Server mở cổng 5001 và 5000, hiện danh sách IP của máy và mã 6 số.
2. Client tạo phiên (`phase = CONNECTING`). Mọi socket đều được **đăng ký với phiên trước khi làm bất kỳ thao tác nào có thể chặn**:
   ```java
   Socket s = new Socket();          // not connected yet
   session.register(s);              // throws if session already closed (user pressed Cancel)
   s.connect(addr, 3000);            // Cancel closes s and makes connect() throw
   ```
   Kết nối xong, Client chuyển sang `AUTHENTICATING`, đặt **deadline 5 giây chờ `AUTH_RESULT`** (`closeIf(AUTHENTICATING, "auth timeout")`, kèm `setSoTimeout(5000)` dự phòng), rồi gửi `AUTH(magic, version, code)`. Lý do: `connect(timeout)` chỉ giới hạn bước kết nối, không giới hạn bước chờ phản hồi. Deadline 5 giây đủ chứa cả độ trễ 1 giây sau mỗi lần nhập sai mã.
3. Server gọi `tryReserve()`. `HandshakeHandler` ghi `AUTH_RESULT` **trực tiếp**; đây là lần ghi duy nhất của handler. Nếu kết quả không phải `OK`, handler đóng kết nối và Client quay về `IDLE`, hiện lý do. Nếu `OK`, handler bàn giao kết nối cho `Session` theo mục 1.5.1, sau đó mới khởi động `CommandWriter`/`CommandReader`.
4. Client nhận `OK` (kèm token) rồi làm **theo đúng thứ tự**:
   1. Chuyển sang `ATTACHING` và **ngay lập tức** đặt deadline 7 giây chờ `SESSION_ACTIVE` (`closeIf(ATTACHING, "activation timeout")`). Deadline được đặt trước khi mở kênh ảnh, nên bao trùm cả bước `connect` và bước ghi `ATTACH`.
   2. Bàn giao các stream của kênh lệnh cho `CommandWriter`/`CommandReceiver`, rồi `offer(SCREEN_INFO(w, h))`.
   3. `new Socket()`, `session.register(imgSocket)`, `connect(ip:5000, 3000)`, rồi ghi `ATTACH(magic, version, token)` trực tiếp. Lúc này luồng kết nối là chủ sở hữu duy nhất của kênh ảnh, vì `ScreenSender` chưa chạy.

   Client không có reader trên kênh ảnh; attach thất bại được phát hiện qua lỗi/đóng kênh lệnh, DISCONNECT hoặc deadline 7 giây.
5. Server nhận đủ cả hai thì `offer(SESSION_ACTIVE)` vào `CommandWriter` và chuyển sang `ACTIVE`. Thứ tự trên đường truyền luôn là `AUTH_RESULT → SESSION_ACTIVE → lệnh điều khiển`, được bảo đảm bởi hai điều:
   - `AUTH_RESULT` do `HandshakeHandler` ghi trực tiếp và `flush()` **trước khi** bàn giao. `CommandWriter` chỉ được khởi động sau khi bàn giao xong, nên không gói nào của nó có thể đi trước `AUTH_RESULT`.
   - Từ đó trở đi, mọi gói đều đi qua hàng đợi FIFO của một writer duy nhất. Lệnh điều khiển chỉ được `offer` khi `controlEnabled = true`, tức là sau khi `SESSION_ACTIVE` đã nằm trong hàng đợi.

   Ngoài ra, Client từ chối mọi lệnh điều khiển nhận được trước `SESSION_ACTIVE` (bảng 2.4).
6. Ở `ACTIVE`:
   - Client gọi `beginSession(gen)` thành công trước khi nhận lệnh điều khiển, rồi chạy `ScreenSender`; begin bị từ chối thì đóng phiên.
   - Server hiện `RemotePanel` với trạng thái chờ frame; chỉ gửi thao tác sau khi EDT đã nhận frame hợp lệ và bật controlEnabled.
   - Cả hai bên trao đổi `PING`/`PONG`.
7. Phiên kết thúc khi: người dùng bấm *Dừng* (Client) hoặc *Ngắt* (Server), hết timeout, lỗi giao thức, hoặc lỗi I/O. Khi đó chạy `closeSession(reason)` (mục 1.8).

### 1.5 Luồng xử lý (chia theo nhiệm vụ)

**Server**
- EDT (luồng giao diện của Swing).
- 2 luồng accept, cho cổng 5000 và 5001.
- Pool `HandshakeHandler`, tối đa 4 luồng.
- `CommandReader`: đọc kênh lệnh, xử lý `SCREEN_INFO`, `PING`, `PONG`, `DISCONNECT`.
- `CommandWriter`: luồng duy nhất ghi kênh lệnh.
- `FrameReceiver`: đọc và giải mã khung hình, gọi `repaint()`.
- 1 `ScheduledExecutorService` dùng chung: gửi PING, kiểm tra deadline, chạy watchdog. **Mọi tác vụ trên scheduler phải chạy xong ngay**: không chờ, không ghi socket, không `join()`.
- 1 luồng cleanup (`Executors.newSingleThreadExecutor`): chạy phần có thể chặn của `closeSession` (mục 1.8).

**Client**
- EDT.
- Luồng kết nối (không chạy trên EDT).
- `CommandReceiver`: đọc kênh lệnh. Khi nhận `PING`, chỉ đưa `PONG` vào hàng đợi, không ghi trực tiếp. Lệnh điều khiển được chuyển sang `InputExecutor.submit()` (không chặn), nên luồng đọc không bao giờ phải chờ Robot.
- `rd-input` (luồng của `InputExecutor`): luồng duy nhất gọi Robot đầu vào; Robot capture riêng thuộc `ScreenSender`.
- `CommandWriter`.
- `ScreenSender`.
- Scheduler dùng chung (tuân theo cùng quy tắc như phía Server).
- Luồng cleanup.

#### 1.5.1 Quyền sở hữu socket qua từng giai đoạn
Tại mỗi thời điểm, mỗi chiều đọc và mỗi chiều ghi của một socket chỉ có **một** chủ sở hữu.

- **Kênh lệnh, Server:**
  - Khi bắt tay: `HandshakeHandler` vừa đọc (`AUTH`) vừa ghi (`AUTH_RESULT`).
  - Sau khi bàn giao: `CommandReader` đọc, `CommandWriter` ghi.
- **Kênh lệnh, Client:**
  - Khi bắt tay: luồng kết nối ghi `AUTH` và đọc `AUTH_RESULT`.
  - Sau khi bàn giao: `CommandReceiver` đọc, `CommandWriter` ghi.
- **Kênh ảnh, Server:**
  - Khi bắt tay: `HandshakeHandler` đọc `ATTACH`.
  - Sau khi bàn giao: `FrameReceiver` đọc. Không bên nào ghi.
- **Kênh ảnh, Client:**
  - Khi bắt tay: luồng kết nối ghi `ATTACH`.
  - Sau khi bàn giao: `ScreenSender` ghi. Không bên nào đọc.

**Bàn giao (handover):**
- Khi bàn giao, chuyển nguyên **bộ stream wrapper** đã tạo (`Socket` + `DataInputStream(BufferedInputStream(TrafficCounter(socket.getInputStream())))` + `DataOutputStream(...)`), gói trong một record `Channel`. Không bao giờ gọi lại `socket.getInputStream()` để tạo wrapper mới. Lý do: `BufferedInputStream` cũ có thể đã đọc trước một phần byte của gói tiếp theo; nếu tạo wrapper mới, phần byte đó sẽ mất và luồng đọc bị lệch.
- Bên bàn giao không được dùng lại `Channel` sau khi đã giao. Ở phía Server, việc bàn giao diễn ra sau khi đã `flush()` `AUTH_RESULT` và **trước** khi khởi động `CommandWriter`.

**Quy tắc bắt buộc**
- Tại mỗi thời điểm, mỗi socket chỉ có **một** nơi ghi:
  - Trong giai đoạn bắt tay, nơi ghi là chủ sở hữu ghi ở mục 1.5.1: `HandshakeHandler` hoặc luồng kết nối.
  - **Sau khi bàn giao:** kênh lệnh chỉ có `CommandWriter` ghi; kênh ảnh chỉ có `ScreenSender` ghi.

  Các thành phần khác chỉ được `offer()`. Lý do: `DataOutputStream` không bảo đảm ghi trọn một packet là nguyên tử, nên nhiều luồng cùng ghi sẽ làm header và payload xen kẽ nhau.
- Mỗi tác vụ nền giữ tham chiếu tới **đúng** `Session` của nó. Trước khi tác động lên trạng thái chung, tác vụ kiểm tra `session == manager.current()`. Callback của phiên cũ không được đóng socket hay đổi mã của phiên mới.
- Không chụp ảnh, nén, giải mã hay đọc/ghi socket trên EDT. Giao diện chỉ được cập nhật qua `SwingUtilities.invokeLater`.

### 1.6 Hàng đợi lệnh (`CommandWriter`)
- Hàng đợi có giới hạn 256 phần tử, tự cài đặt với một khóa (`synchronized`) để gộp `MOUSE_MOVE` được nguyên tử.
- `offer()` chỉ giữ khóa metadata ngắn, không chờ I/O hay chỗ trống; an toàn khi gọi từ EDT nếu không gọi callback bên ngoài dưới khóa.
- Gộp `MOUSE_MOVE`: nếu phần tử **cuối** hàng đợi là một `MOUSE_MOVE` chưa gửi thì thay tọa độ của nó, không thêm phần tử mới. Không gộp vượt qua gói nút hay gói phím. Ví dụ: `MOVE(A) → PRESS → MOVE(B) → RELEASE` phải giữ nguyên cả 4 gói.
- Hàng đợi đầy thì báo overflow và gọi đóng đúng phiên sau khi đã rời khóa queue. Không âm thầm bỏ packet đã được nhận, nhất là KEY_RELEASE/MOUSE_RELEASE. Giới hạn wheel ở mục 3.3 diễn ra trước khi tạo packet và là ngoại lệ cố ý cho lượng cuộn vật lý bất thường.
- `closeGracefully(Packet finalOrNull) → CompletableFuture<Void> done` được gọi ở giai đoạn A của `closeSession`. Hàm này **không chặn**; gọi lại lần nữa thì trả về đúng future cũ.
  - **Ngừng nhận gói mới:** từ lúc này, mọi `offer()` trả `false`, gói bị bỏ qua, không ném lỗi.
  - **Loại bỏ các gói chưa bắt đầu gửi**, tức là các gói còn nằm trong hàng đợi.
  - **Gói đang ghi dở** (writer đã lấy ra và đang ghi) **có thể hoàn tất** trước khi socket đóng. Không có cách nào rút lại byte đã ghi một phần; chính vì vậy thứ tự đóng socket ở mục 1.8 mới quan trọng.
  - Sau gói đang ghi dở, writer ghi `finalOrNull` (thường là `DISCONNECT`), gọi `flush()` rồi mới **hoàn tất `done`**. Nếu `finalOrNull == null`, `done` hoàn tất ngay khi gói đang ghi dở (nếu có) xong. Nếu ghi lỗi, `done` hoàn tất kèm exception.
  - `done` chỉ báo rằng byte **đã được chuyển xong cho TCP** (`flush()` đã trả về). Nó không bảo đảm bên kia đã nhận được.
- Sau `closeGracefully()`, writer không lấy thêm gói nào ngoài `finalOrNull`; chỉ gói đã bắt đầu ghi có thể hoàn tất. Mọi packet được flush sau khi ghi, kể cả AUTH/ATTACH; watchdog bao gồm thời gian flush. Byte array payload phải được sao chép hoặc chuyển quyền sở hữu, không được sửa sau khi offer.
- Kênh ảnh không có hàng đợi ở tầng ứng dụng. `ScreenSender` ghi xong khung trước mới chụp khung sau, nên ứng dụng không tự tích lũy khung cũ. Tuy vậy, socket buffer của TCP ở hai đầu vẫn có thể giữ dữ liệu của một vài khung đã ghi (xem "Giới hạn đã biết" ở mục 1.7). Phía Server chỉ giữ khung mới nhất trong `AtomicReference`.

### 1.7 Heartbeat, timeout và watchdog
Mọi phép đo thời gian dùng `System.nanoTime()`.

- **Kết nối:** `connect(addr, 3000)`. Bật `setTcpNoDelay(true)` cho kênh lệnh.
- **Bắt tay:**
  - Server: deadline tổng 3 giây kể từ `accept()` (mục 1.3). Ở trạng thái `RESERVED`, phải hoàn tất trong 5 giây.
  - Client: chờ `AUTH_RESULT` tối đa 5 giây kể từ khi kết nối xong; chờ `SESSION_ACTIVE` tối đa 7 giây kể từ lúc nhận `AUTH_RESULT(OK)` (mục 1.4).
  - Deadline phase dùng `closeIf`; pending socket và watchdog dùng hợp đồng riêng ở mục 1.9.
- **Heartbeat:**
  - Client bắt đầu sau khi nhận AUTH_RESULT(OK); Server bắt đầu sau khi flush OK và bàn giao thành công. Giá trị lastCommandAt khởi tạo tại mốc đó.
  - Cả hai bên gửi `PING(seq)` mỗi 2 giây; bên nhận trả `PONG(seq)`, dùng để tính RTT.
  - **Bất kỳ** gói hợp lệ nào nhận được trên kênh lệnh cũng được tính là còn sống. Không nhận gói nào trong 8 giây thì `closeSession("heartbeat timeout")`.
  - `setSoTimeout(10000)` trên kênh lệnh chỉ là lớp dự phòng.
- **Ảnh:**
  - Server lưu `lastFrameAt`. Giá trị này được **khởi tạo bằng thời điểm chuyển sang `ACTIVE`**, nên khi chưa có khung đầu tiên, các mốc timeout dưới đây tính từ lúc `ACTIVE`.
  - Có cờ `imageStalled`, mặc định `false`. Watchdog kiểm tra mỗi giây; mọi hành động bên dưới chỉ chạy **khi cờ đổi giá trị** (edge-triggered), không lặp lại ở mỗi lần kiểm tra.
    - **`false → true`** (quá 3 giây không có khung mới):
      - Hiện cảnh báo "Luồng ảnh gián đoạn".
      - Đặt `controlEnabled = false`.
      - `offer(RELEASE_ALL)` **một lần**.
      - Xóa danh sách phím/nút đang giữ và phần cuộn lẻ ở `InputCapture`.

      Lý do: người điều khiển đang nhìn một khung hình cũ, nên mọi thao tác lúc này đều có thể sai.
    - **`true → false`** (nhận được khung hợp lệ mới): ẩn cảnh báo và bật lại `controlEnabled`.

    Lần nhận frame đầu tiên được xử lý riêng bằng `hasFrame = true` vì `imageStalled` ban đầu đã là false. Mọi frame hợp lệ cập nhật `lastFrameAt` sau decode thành công; EDT nhận snapshot mới rồi tính `controlEnabled = active && hasFrame && !imageStalled && !closed`. Focus là điều kiện riêng khi bắt sự kiện. Không chỉ dựa vào cạnh true → false để bật lần đầu.
  - Quá 15 giây kể từ `lastFrameAt` thì kiểm tra lại phase ACTIVE và lastFrameAt dưới khóa trước khi đánh dấu đóng vì frame timeout (mục 1.9).
  - Kênh ảnh đặt `setSoTimeout(20000)`.
  - **Giới hạn đã biết:** dữ liệu của các khung đã ghi vẫn có thể nằm trong socket buffer gửi của Client và buffer nhận của Server; khung đã ghi vào TCP thì không rút lại được. Hai cách giảm thiểu:
    - Phía Client, đặt `setSendBufferSize(64 * 1024)` cho kênh ảnh để thu nhỏ buffer gửi. Hệ điều hành có thể điều chỉnh giá trị này, và kích thước JPEG mỗi khung cũng thay đổi, nên **không suy ra được buffer giữ bao nhiêu khung**.
    - `ScreenSender` chỉ chụp khung tiếp theo sau khi khung trước đã ghi xong.

    Dù vậy, khi mạng chậm, khung Server hiển thị vẫn có thể trễ hơn thực tế. Mức trễ thực tế phải được đo (mục 5.2) và ghi vào báo cáo.
- **Watchdog ghi:**
  - `setSoTimeout()` chỉ giới hạn thời gian đọc, không giới hạn thời gian ghi.
  - `CommandWriter` và `ScreenSender` ghi lại `writeStartedAt` trước mỗi lần ghi và đặt lại về 0 khi ghi xong.
  - Scheduler kiểm tra mỗi giây. Nếu một lần ghi kéo dài quá 5 giây (kênh lệnh) hoặc quá 10 giây (kênh ảnh) thì đóng socket. Luồng đang kẹt sẽ nhận `SocketException` và thoát ra.
  - Watchdog không phụ thuộc vào writer đang bị kẹt.

### 1.8 Cleanup: `closeSession(reason)`
Hàm có trên cả hai bên, có thể được gọi từ bất kỳ luồng nào (timeout, lỗi ảnh, lỗi lệnh, nút *Ngắt*) và gọi nhiều lần vẫn an toàn.

Hàm chia làm hai giai đoạn. Giai đoạn A không chặn và chạy ngay trên luồng gọi; giai đoạn B có thể chặn nên chạy trên luồng cleanup.

**Giai đoạn A: chạy ngay trên luồng gọi.** Có thể gọi từ EDT, scheduler, reader hoặc writer. Chỉ giữ khóa metadata ngắn và đặt công việc; không chờ queue trống, I/O, Robot hay future. Mọi callback ra ngoài module diễn ra sau khi nhả khóa.
1. Dưới khóa phiên, nếu đã đóng thì trả về cùng kết quả đóng cũ; nếu chưa thì đặt closed, phase `CLOSING`, reason và `closeStartedAt` nguyên tử. Callback deadline dùng cùng thao tác chuyển trạng thái, không tách kiểm tra phase khỏi quyết định đóng.
2. **(Chỉ Server)** Đặt `controlEnabled = false`. Từ lúc này `InputCapture` bỏ qua mọi sự kiện.
3. **(Chỉ Client)** `releasedFuture = inputExecutor.endSession(gen)`. Hàm này **không chặn**:
   - Chuyển input generation sang ENDING, xóa lệnh chưa bắt đầu và đặt END vào ô lifecycle riêng (mục 3.5).
   - Lệnh đã bắt đầu được chạy nốt, rồi worker nhả phím. Giai đoạn A không chờ; generation mới bị từ chối cho đến khi nhả thành công.
4. `writerDone = commandWriter.closeGracefully(DISCONNECT(reason))` (mục 1.6). Nếu kết thúc vì nhận được `DISCONNECT` từ bên kia thì truyền `null`.
5. Hủy heartbeat/deadline cũ. Đặt một deadline đóng cưỡng bức độc lập tại `closeStartedAt + 500 ms`, chỉ đóng raw socket đã đăng ký. Không hủy deadline này trước khi socket đã đóng. Nếu scheduler đang tắt hoặc từ chối task thì đóng raw socket ngay.
6. Gửi giai đoạn B cho luồng cleanup; nếu executor từ chối thì vẫn đóng raw socket ngay. Khi chưa có writer/channel (hủy trong connect/auth), bỏ qua gửi DISCONNECT và đóng socket đã register ngay.

**Giai đoạn B: chạy trên luồng cleanup.**
```java
try {
    awaitUntil(writerDone, closeStartedAt + CLOSE_GRACE_NANOS); // absolute deadline, best effort
} catch (Exception e) {
    log(e);                              // timeout or write error: still close below
} finally {
    closeRegisteredRawSockets();         // close sockets FIRST; buffered stream.close may flush
    stopWriterIfPresent();
    closeWrappersAfterSockets();
}
// Client only: release is tracked separately; socket closing never waits for it
observeReleaseWithoutMutatingFuture(releasedFuture); // warning at closeStartedAt + 2 s
// then state reset and UI update (see 7)
```
- Đóng socket là cách duy nhất để giải phóng chắc chắn mọi luồng đang chặn khi đọc hoặc ghi. Vì vậy hai socket được đóng trong `finally`, **không phụ thuộc** vào việc gửi `DISCONNECT` thành công, hết hạn hay lỗi, và **không chờ** `releaseAll()`.
- Việc nhả phím được theo dõi riêng. Nếu sau 2 giây chưa xong, hiện cảnh báo "Chưa hoàn tất nhả phím" và giữ Kết nối bị khóa. Không gọi `orTimeout()` trên future thật: timeout không được biến future pending thành bằng chứng nhả phím. Khi nhả thành công muộn, có thể mở lại Kết nối nếu đúng generation và ứng dụng chưa tắt; nếu nhả lỗi thì yêu cầu khởi động lại Client.
- **Phiên mới không thể thực thi đầu vào trước khi phiên cũ nhả phím xong:** `beginSession` chỉ thành công ở IDLE sau release thành công. Không nhận/xếp begin mới trong ENDING. Một tác vụ input đã được worker nhận trước end có thể chạy nốt, nhưng không có input cũ chạy sau END.

7. Đặt lại trạng thái và cập nhật giao diện:
   - Nếu `app.shuttingDown`: vẫn hoàn tất future cleanup nhưng không khôi phục trạng thái/UI (mục 4.5).
   - Ngược lại: chỉ callback còn sở hữu current session mới đổi trạng thái chung. Server sinh mã mới rồi chuyển sang `LISTENING` khi socket đóng; Client giữ `CLOSING` cho đến khi socket đóng và release thành công, sau đó về `IDLE`. Nếu release chưa xong/lỗi thì giữ trạng thái đóng với cảnh báo và Kết nối bị khóa. Giao diện cập nhật qua `invokeLater`, kiểm tra lại generation trong runnable.

Mỗi bước được bọc `try/catch` riêng, chỉ ghi log khi lỗi, để lỗi ở một bước không chặn các bước sau. Không gọi `join()` trên EDT hay trên scheduler. Scheduler không bao giờ chờ `DISCONNECT` hay chờ nhả phím, nên watchdog luôn chạy được.

---

### 1.9 Hợp đồng đồng thời và quyền sở hữu tài nguyên

- `SessionManager` sở hữu current session và mã; mỗi Session sở hữu phase/generation/deadline và tài nguyên của nó. Thứ tự khóa khi buộc phải lồng: manager → session → queue metadata. Không module queue/input nào gọi ngược vào session khi giữ khóa. Callback overflow, future completion, logging có thể chặn và cập nhật UI đều ở ngoài khóa.
- `register(resource)` và đóng phiên dùng cùng khóa: hoặc tài nguyên được đưa vào tập trước close và sẽ bị đóng, hoặc register thấy closed rồi đóng tài nguyên mới ở ngoài khóa và báo lỗi. Cleanup đóng **toàn bộ tập**, không chỉ hai Channel đã khởi tạo xong. Mỗi socket có đúng một đường sở hữu; stream wrapper đi cùng socket.
- CAS pending chỉ phân xử deadline với claim, chưa tự bàn giao tài nguyên. `transferTo(session)` phải kiểm tra thời gian tuyệt đối, `shuttingDown` và session closed, claim rồi register socket dưới khóa theo thứ tự trên, trước khi xóa pending entry. Nếu register/khởi động reader thất bại thì đóng socket và giải phóng reservation. Không để trạng thái HANDED_OVER tồn tại mà không có chủ nhận hoặc trách nhiệm đóng trong finally.
- Hết hạn được xác định bằng monotonic clock, không chỉ việc scheduled task đã chạy. Handler không được claim sau deadline dù scheduler chạy muộn. Reservation bắt đầu tại `tryReserve`; nếu ghi AUTH_RESULT hoặc bàn giao thất bại thì đóng đúng reservation đó.
- Khi SCREEN_INFO và ATTACH đến đồng thời, cập nhật hai điều kiện và thử chuyển RESERVED → ACTIVE dưới một khóa, kiểm tra deadline; chỉ enqueue SESSION_ACTIVE một lần và chỉ activate nếu offer thành công. Reader ảnh chỉ bắt đầu sau ACTIVE; byte FRAME đến sớm vẫn bị giới hạn bởi buffer, không được decode trước SCREEN_INFO.
- `closeIf(expectedPhase, reason)` dùng cho deadline phase cố định, kiểm tra và đánh dấu CLOSING trong cùng critical section. Watchdog liveness/frame phải kiểm tra lại **timestamp hiện tại** cùng phase ngay tại quyết định đóng; writer watchdog phải kiểm tra operation id và thời điểm bắt đầu hiện tại. Chỉ kiểm tra phase không ngăn timeout cũ đóng một phiên vừa nhận frame/PONG.
- `writeStartedAt`, timestamp liveness, snapshot phase và thống kê được publish bằng atomic/volatile hoặc cùng khóa. Watchdog bắt đầu trước ghi và kết thúc sau flush; không giữ khóa metadata trong I/O. Scheduled task bắt và báo exception để không âm thầm ngừng các lần chạy sau.
- `InputCapture` và tập phím/nút phía Server chỉ sửa trên EDT. Sự kiện mạng/watchdog gửi snapshot có generation và revision tới EDT; callback cũ bị bỏ. Khi ảnh gián đoạn, chuyển gate và reset input trên EDT theo một lượt trước các input mới. Chỉ giữ một cập nhật frame UI đang chờ; frame mới thay thế dữ liệu snapshot, tránh queue EDT tăng không giới hạn.
- V1 không có hard real-time guarantee khi JVM/OS bị treo; deadline có dung sai một chu kỳ watchdog và thời gian lập lịch. Khi Robot native bị treo, đóng mạng và khóa kết nối mới vẫn phải hoạt động; không hứa nhả phím thành công trong tình huống đó.

## 2. Đặc tả giao thức

### 2.1 Định dạng gói
```
[type: 1 byte][length: int32][payload: length byte]
```
- Thứ tự byte: big-endian, theo `DataOutputStream`/`DataInputStream`. Chuỗi mã hóa UTF-8.
- `length` là số byte của payload.
- `magic` (`0x4C544D52`, tức `"LTMR"`) và `version` (`1`) chỉ có trong **gói đầu tiên** của mỗi kênh (`AUTH` và `ATTACH`), không có trong mọi gói. Khi gặp lỗi ta đóng kết nối chứ không cố đồng bộ lại luồng byte, nên magic ở từng gói không giúp gì thêm.

### 2.2 Các bước kiểm tra trong `PacketIO.read`
Chữ ký hàm: `PacketIO.read(DataInputStream in, int channelMaxLen, Set<PacketType> allowed)`.

Việc kiểm tra loại gói chia làm **hai lớp**:
- **Lớp 1: `allowed` cố định theo reader**, không phụ thuộc vào phase:
  - `HandshakeHandler` kênh lệnh: `{AUTH}`.
  - `HandshakeHandler` kênh ảnh: `{ATTACH}`.
  - `FrameReceiver`: `{FRAME}`.
  - `CommandReader` (Server): tất cả các gói C→S hợp lệ trên kênh lệnh.
  - `CommandReceiver` (Client): tất cả các gói S→C hợp lệ trên kênh lệnh.
  - Client đọc `AUTH_RESULT`: `{AUTH_RESULT}`.

  Lớp này chỉ dùng để từ chối sớm, ngay sau byte `type`. Vì tập này không đổi trong suốt vòng đời của reader, nó không thể bị "cũ" khi một luồng khác đổi phase trong lúc reader đang chặn ở `read()`.
- **Lớp 2: kiểm tra theo phase khi xử lý gói**, và đây là lớp quyết định. Reader gọi `session.dispatch(packet)`. Hàm kiểm tra bảng 2.4 theo phase hiện tại và quyết định cập nhật/đóng nguyên tử dưới khóa; side effect đóng socket, callback và UI chạy sau khi nhả khóa. Gói sai phase đánh dấu đóng vì PROTOCOL_ERROR, không tiếp tục dispatch đầu vào.

  Phần xử lý dưới khóa chỉ gồm các thao tác nhanh: cập nhật trạng thái, gọi `offer()`, gọi `submit()`. Không có I/O, không có Robot.

1. Đọc `type`. Gặp EOF ở byte đầu thì coi là bên kia đóng kết nối bình thường.
2. Giá trị `type` không có trong `PacketType`, **hoặc không nằm trong `allowed`** (lớp 1), thì ném `ProtocolException` ngay, **trước khi đọc `length` và payload**. Nhờ vậy không phải đọc một `FRAME` 8 MiB chỉ để vứt đi.
3. Đọc `length`. Nếu `length < 0`, vượt `channelMaxLen`, vượt `maxLength` của loại gói, hoặc khác độ dài cố định của loại gói thì ném `ProtocolException`. Việc kiểm tra này diễn ra **trước** khi cấp phát mảng.
4. `readFully(payload)`, vì một lần `read()` có thể không nhận đủ dữ liệu. Gặp EOF giữa header hoặc giữa payload thì coi là mất kết nối.
5. Phân tích payload theo loại gói và kiểm tra miền giá trị (mục 2.3). Giá trị nằm ngoài miền là lỗi giao thức.
6. Phía gọi chuyển gói cho `session.dispatch()` để kiểm tra theo phase (lớp 2).

Xử lý lỗi giao thức:
- Lỗi trên kênh lệnh: gọi `closeSession`.
- Lỗi trên kênh ảnh khi đang bắt tay: chỉ đóng socket đó.
- Lỗi trên kênh ảnh sau khi đã attach: gọi `closeSession`.

Không bao giờ đọc tiếp sau khi đã gặp lỗi.

### 2.3 Các loại gói
Tất cả gói đều có độ dài cố định, trừ `FRAME` và `DISCONNECT`.

Mỗi gói có miền giá trị hợp lệ; giá trị nằm ngoài miền là lỗi giao thức. Riêng mã phím không hợp lệ được xử lý mềm hơn (xem `KEY_PRESS`).

- `0x01 AUTH` (C→S, kênh lệnh): `magic:int32, version:u8, code:6 byte ASCII`. 11 byte.
  - `magic` phải đúng; sai thì đóng kết nối, không trả lời.
  - `version` khác `1` thì trả `AUTH_RESULT(BAD_VERSION)`.
  - Mỗi byte của `code` phải là `'0'..'9'`.
- `0x02 AUTH_RESULT` (S→C, kênh lệnh): `status:u8, token:16 byte`. 17 byte.
  - `status` chỉ nhận: `0 OK`, `1 WRONG_CODE`, `2 BUSY`, `3 LOCKED`, `4 BAD_VERSION`. Giá trị khác là lỗi.
  - Khi `status` khác `OK`, `token` phải toàn số 0.
- `0x03 SCREEN_INFO` (C→S, kênh lệnh): `width:int32, height:int32`. 8 byte.
  - Mỗi chiều trong khoảng `1..8192`, và `(long) width*height ≤ 33_554_432`.
- `0x04 ATTACH` (C→S, kênh ảnh): `magic:int32, version:u8, token:16 byte`. 21 byte.
- `0x05 SESSION_ACTIVE` (S→C, kênh lệnh): không có payload. 0 byte.
- `0x10 FRAME` (C→S, kênh ảnh): `width:int32, height:int32, jpeg:byte[]`.
  - Độ dài từ 9 đến 8 MiB.
  - `1 ≤ width ≤ SCREEN_INFO.width` và `1 ≤ height ≤ SCREEN_INFO.height`.
  - **Tỷ lệ khung phải khớp tỷ lệ màn hình:** `|height − round((double) width * rh / rw)| ≤ 1`, trong đó `rw`, `rh` lấy từ `SCREEN_INFO`. Chiều cao sau thu nhỏ tối thiểu 1; MAX_FRAME_WIDTH v1 là 1280 và phía nhận cũng kiểm tra trần này.
- `0x20 MOUSE_MOVE` (S→C): `x:int32, y:int32`, theo tọa độ màn hình Client. 8 byte.
  - `0 ≤ x < rw` và `0 ≤ y < rh`.
- `0x21 MOUSE_PRESS` / `0x22 MOUSE_RELEASE` (S→C): `x:int32, y:int32, button:u8`. 9 byte.
  - Tọa độ có cùng miền với `MOUSE_MOVE`. `button` chỉ nhận `1` (trái), `2` (giữa) hoặc `3` (phải).
  - Client gọi `mouseMove(x, y)` **trước**, sau đó mới `press`/`release`. Như vậy vị trí nhấn/thả luôn đúng, không phụ thuộc vào gói `MOVE` đi trước (gói đó có thể đã bị gộp hoặc bị bỏ vì nằm ngoài vùng ảnh).
- `0x23 WHEEL` (S→C): `notches:int32`, số âm là cuộn lên. 4 byte.
  - Phải khác 0 và `|notches| ≤ 20`.
- `0x24 KEY_PRESS` / `0x25 KEY_RELEASE` (S→C): `keyCode:int32`, theo `KeyEvent.VK_*`. 4 byte.
  - Miền giao thức là int32 dương; một số VK mở rộng vượt 0xFFFF nên không dùng 16 bit làm giới hạn. V1 chỉ gửi các mã VK được hỗ trợ theo ma trận phím đã kiểm thử; mã dương không được Robot hỗ trợ bị bỏ qua có log giới hạn tần suất.
  - Mã nằm trong miền nhưng Robot không chấp nhận (`IllegalArgumentException`) thì chỉ ghi log và bỏ qua lệnh đó, phiên vẫn tiếp tục (mục 3.5).
- `0x26 RELEASE_ALL` (S→C): không có payload. 0 byte.
- `0x30 PING` / `0x31 PONG` (cả hai chiều): `seq:int32`. 4 byte.
  - Nhận `PONG` có `seq` khác lần `PING` gần nhất thì chỉ bỏ qua khi tính RTT, không coi là lỗi.
- `0x3F DISCONNECT` (cả hai chiều): `reason:u8` (mã lý do), sau đó là `message` UTF-8 (tối đa 200 byte). Độ dài 1..201 byte.
  - `reason` chỉ nhận: `0 USER`, `1 TIMEOUT`, `2 PROTOCOL_ERROR`, `3 SCREEN_CHANGED`, `4 OVERFLOW`, `5 SHUTDOWN`. Giá trị lạ được coi là `PROTOCOL_ERROR`; vì phiên đằng nào cũng đóng nên không cần xử lý gì thêm.
  - `message` được giải mã bằng `CharsetDecoder` với `CodingErrorAction.REPORT`; UTF-8 lỗi thì thay bằng chuỗi rỗng.
  - Ký tự điều khiển bị loại bỏ trước khi hiển thị lên giao diện.

Giới hạn `maxLen` theo kênh:
- Kênh lệnh: 1 KiB.
- Kênh ảnh: 8 MiB.

### 2.4 Gói được phép theo kênh, chiều và trạng thái
- **Kênh lệnh, Server nhận:**
  - Khi bắt tay: chỉ `AUTH`.
  - `RESERVED`: `SCREEN_INFO` (chỉ một lần), `PING`, `PONG`, `DISCONNECT`.
  - `ACTIVE`: `PING`, `PONG`, `DISCONNECT`. Nhận `SCREEN_INFO` lần nữa là lỗi giao thức.
- **Kênh lệnh, Client nhận:**
  - `AUTHENTICATING`: chỉ `AUTH_RESULT`.
  - `ATTACHING`: `SESSION_ACTIVE`, `PING`, `PONG`, `DISCONNECT`.
  - `ACTIVE`: `MOUSE_*`, `WHEEL`, `KEY_*`, `RELEASE_ALL`, `PING`, `PONG`, `DISCONNECT`.
- **Kênh ảnh, Server nhận:**
  - Khi bắt tay: chỉ `ATTACH`.
  - Sau khi attach và phiên ACTIVE: chỉ `FRAME`; chưa đủ SCREEN_INFO thì chưa khởi động FrameReceiver.
- **Kênh ảnh, Client nhận:** không có gói nào. Server không ghi gì lên kênh ảnh, và Client không có reader trên kênh này. Nếu attach bị từ chối, Client chỉ biết qua kênh lệnh:
  - Server gửi `DISCONNECT`; hoặc
  - hết deadline 7 giây chờ `SESSION_ACTIVE` (mục 1.4).

   Sau ACTIVE, lỗi kênh ảnh được phát hiện bởi write IOException, watchdog ghi hoặc kênh lệnh đóng/timeout. Không bảo đảm lần write kế tiếp đã thấy lỗi vì TCP có thể còn nhận byte vào buffer.

### 2.5 Kiểm tra JPEG trước khi giải mã
`FrameReceiver` làm theo thứ tự:
1. Kiểm tra `width`/`height` trong header của `FRAME`.
2. Tạo `ImageReader` từ `ImageIO.getImageReadersByFormatName("jpeg")`.
3. Đọc `reader.getWidth(0)` và `reader.getHeight(0)`; bước này chỉ đọc phần header của JPEG.
4. Đối chiếu với giá trị ở bước 1 (phải bằng nhau) và với giới hạn kích thước, tỷ lệ ở mục 2.3.
5. Hợp lệ mới gọi `reader.read(0)`.

JPEG lỗi hoặc kích thước không khớp là lỗi giao thức. `ImageReader.dispose()` và đóng `ImageInputStream` trong finally; dùng stream ảnh trong bộ nhớ để không tạo cache file ngoài ý muốn. Giới hạn cả output JPEG lúc encode; payload vượt 8 MiB thì báo lỗi và đóng phiên, không gửi header sai hoặc tăng buffer không giới hạn.

---

## 3. Màn hình, tọa độ và bàn phím

### 3.1 Chụp và nén (Client)
- Hệ tọa độ "logic" được định nghĩa là `GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds()`. Đây là hệ user space, cũng là hệ mà `Robot.mouseMove` dùng.
  - Khi Windows bật scaling 125%, kích thước này nhỏ hơn số pixel thật của màn hình.
  - `SCREEN_INFO` gửi `bounds.width` và `bounds.height`.
  - **Gốc tọa độ:** giao thức luôn dùng tọa độ tương đối với `bounds`, bắt đầu từ `(0, 0)`. Client đổi sang tọa độ tuyệt đối trước khi gọi Robot: `robot.mouseMove(bounds.x + x, bounds.y + y)`. Với màn hình chính, `bounds.x` và `bounds.y` thường bằng 0, nhưng code không được giả định điều đó.
  - **Đổi DPI hoặc độ phân giải giữa phiên:** cứ mỗi 1 giây, Client đọc lại `bounds` và `getDefaultTransform().getScaleX()/getScaleY()`. Một trong hai giá trị thay đổi thì `closeSession(SCREEN_CHANGED)`.
  - JDK có thể không cập nhật DPI ngay khi người dùng đổi scaling lúc ứng dụng đang chạy. Vì vậy README hướng dẫn: đổi scaling thì khởi động lại Client. Trường hợp này cũng được đưa vào kiểm thử thủ công.
- Mỗi khung hình:
  1. `robot.createScreenCapture(bounds)`.
  2. Nếu chiều rộng lớn hơn `MAX_FRAME_WIDTH = 1280`, thu nhỏ giữ tỷ lệ vào một `BufferedImage` kiểu `TYPE_INT_RGB` (dùng `Graphics2D` với nội suy bilinear).
  3. Nén JPEG bằng `ImageWriter` với `setCompressionQuality(0.6f)`, ghi vào một `ByteArrayOutputStream` được tái sử dụng giữa các khung.
- Mục tiêu 12 FPS (cấu hình được). Mỗi vòng lặp: chụp, nén, ghi, rồi ngủ phần thời gian còn lại của chu kỳ `1000/12` ms.
- Khi đóng phiên vì màn hình thay đổi, Client hiện thông báo yêu cầu kết nối lại.

### 3.2 Hiển thị và quy đổi tọa độ (Server)
- `CoordinateMapper` dùng kích thước màn hình Client (`rw`, `rh`, lấy từ `SCREEN_INFO`) và kích thước panel (`pw`, `ph`):
  - `scale = min((double) pw/rw, (double) ph/rh)`; phép tính tỷ lệ luôn dùng số thực.
  - `dw = rw*scale`, `dh = rh*scale`
  - `ox = (pw-dw)/2`, `oy = (ph-dh)/2`
  - `remoteX = clamp(floor((mx-ox)/scale), 0, rw-1)`; `remoteY` tính tương tự.
- Mỗi lần vẽ và mỗi sự kiện đều tính lại theo kích thước panel hiện tại, nên resize cửa sổ không làm lệch tọa độ.
- Dùng cùng một rectangle đã làm tròn cho cả paint và mapper; rectangle rỗng trả Optional.empty. Thay đổi kích thước device/transform được xử lý ở Client, không tự điều chỉnh SCREEN_INFO giữa phiên.
- Nếu `pw ≤ 0`, `ph ≤ 0` (cửa sổ thu nhỏ hoặc chưa hiển thị), hoặc chưa có `SCREEN_INFO`, mapper trả `Optional.empty()`: sự kiện bị bỏ qua và `paintComponent` không vẽ ảnh.
- `RemotePanel` vẽ khung hình mới nhất vào vùng `(ox, oy, dw, dh)`. Phần viền trống tô màu nền.
- Xử lý thao tác nằm ngoài vùng ảnh:
  - `MOUSE_MOVE` và `MOUSE_PRESS` ngoài vùng ảnh bị bỏ qua.
  - **Ngoại lệ:** nếu đang giữ một nút (đang kéo chuột), `MOUSE_MOVE` vẫn được gửi với tọa độ đã chặn trong giới hạn, và `MOUSE_RELEASE` **luôn** được gửi.

### 3.3 Chuột
- `mouseMoved` và `mouseDragged` đều gửi `MOUSE_MOVE`.
- Mọi sự kiện chuột chỉ được xử lý khi `controlEnabled = true`.
- `mousePressed`/`mouseReleased` gửi `MOUSE_PRESS`/`MOUSE_RELEASE(x, y, button)`, với tọa độ đã quy đổi tại chính sự kiện đó và `button = e.getButton()` (chỉ nhận 1 đến 3). Phía Client chuyển `button` sang mặt nạ của Robot bằng `InputEvent.getMaskForButton(button)`.
- `MOUSE_RELEASE` chỉ được gửi cho nút đang nằm trong danh sách đang giữ. Sau khi đã gửi `RELEASE_ALL`, danh sách này rỗng, nên các release đến muộn bị bỏ qua.
- **Click khi panel chưa có focus: chỉ để lấy focus.** Thứ tự xử lý trong `mousePressed`:
  1. Nếu `!panel.isFocusOwner()`: gọi `panel.requestFocusInWindow()`, đặt `swallowUntilRelease = true`, rồi `return`. Click này **không** được gửi sang Client.
  2. Khi `swallowUntilRelease = true`: các sự kiện `mouseDragged` và `mouseReleased` thuộc cùng lần nhấn đó cũng bị bỏ qua. `mouseReleased` đặt lại `swallowUntilRelease = false`.
  3. Sau đó mới áp dụng các điều kiện bỏ qua khác (mục 3.4).

  `requestFocusInWindow()` luôn được gọi **trước** các điều kiện bỏ qua, nên không bao giờ rơi vào tình trạng "chưa có focus thì bỏ qua click, mà không có click thì không lấy được focus". Hành vi này cần ghi trong hướng dẫn sử dụng: *click lần đầu vào cửa sổ điều khiển chỉ để chọn cửa sổ*.
- Cuộn: cộng dồn `e.getPreciseWheelRotation()` vào biến `wheelAcc`.
  - Khi `|wheelAcc| ≥ 1`: lấy phần nguyên `n` và giữ lại phần lẻ trong `wheelAcc`. Gửi `n` theo các **gói tối đa 20 notch**, ví dụ `n = 45` thì gửi `20, 20, 5`, để luôn nằm trong giới hạn `|notches| ≤ 20` ở mục 2.3.
  - Mỗi sự kiện gửi tối đa 3 gói (60 notch). Phần vượt quá bị bỏ, để một cú vuốt touchpad bất thường không làm đầy hàng đợi.
  - Cách cộng dồn này cần cho touchpad, vì với touchpad `getWheelRotation()` thường trả về 0.
  - `wheelAcc` được đặt về 0 khi mất focus, khi `controlEnabled` chuyển sang `false`, và khi gửi `RELEASE_ALL`.

### 3.4 Bàn phím
- `RemotePanel` cần `setFocusable(true)`, `setFocusTraversalKeysEnabled(false)` (để Swing không nuốt phím Tab), và lấy focus theo quy tắc "click đầu tiên chỉ để lấy focus" ở mục 3.3. Khi cửa sổ điều khiển vừa mở, panel tự gọi `requestFocusInWindow()` một lần.
- `keyPressed`/`keyReleased` gửi `KEY_PRESS`/`KEY_RELEASE` với `e.getKeyCode()`. Bỏ qua `VK_UNDEFINED` và không dùng `keyTyped`.
- **Key repeat vẫn được gửi đi.** Giả định là Windows không tự lặp phím cho input giả lập qua `SendInput`; nếu đúng như vậy mà lọc bỏ repeat thì giữ Backspace chỉ xóa được một ký tự.
  - **Giả định này chưa được kiểm chứng**, nên phải kiểm thử thực tế (mục 5.2): giữ Backspace hoặc một phím chữ khoảng 2 giây, xem máy Client có lặp đúng không và có bị lặp gấp đôi không.
  - Nếu bị lặp gấp đôi thì đổi chính sách: Server chỉ gửi `KEY_PRESS` đầu tiên của mỗi lần giữ phím.
  - `InputExecutor` theo dõi phím đang giữ bằng một `Set`, nên nhận `KEY_PRESS` trùng không làm sai trạng thái.
- `InputCapture` theo dõi phím và nút đang giữ phía Server. Khi panel hoặc cửa sổ mất focus (`focusLost`, `windowDeactivated`), Server làm các bước sau:
  1. Gửi `RELEASE_ALL`.
  2. Xóa danh sách đang giữ và đặt `wheelAcc = 0`.
  3. Đặt cờ `hasFocus = false`.
- **Chặn sự kiện đến muộn:** khi `hasFocus = false`, hoặc `controlEnabled = false`, hoặc `!panel.isFocusOwner()`, mọi sự kiện phím và chuột đều bị bỏ qua. Riêng `mousePressed` vẫn được dùng để lấy focus theo mục 3.3.
  - `KEY_RELEASE` chỉ được gửi cho phím đang nằm trong danh sách đang giữ; nhờ vậy release đến muộn sau `RELEASE_ALL` không được gửi đi.
  - `hasFocus` chỉ được bật lại trong `focusGained`.

### 3.5 `InputExecutor` (Client)
Interface phiên gồm `beginSession(gen) → boolean`, `submit(gen, cmd) → SubmitResult` và `endSession(gen) → CompletableFuture<Void>`. Các hàm chỉ giữ một khóa ngắn để thay đổi metadata/hàng đợi; không chờ I/O, Robot, future hay chỗ trống. Không gọi đây là thuật toán lock-free. Vòng đời ứng dụng có thêm `shutdownAsync()` để từ chối begin mới, kết thúc phiên hiện tại rồi dừng worker.

- **Cấu trúc:** một worker `rd-input` với deque riêng chứa tối đa 256 lệnh thường; một ô lifecycle riêng chứa yêu cầu kết thúc phiên. Không dùng `Executors.newSingleThreadExecutor()` vì factory đó có hàng đợi không giới hạn. Worker chỉ giữ khóa khi lấy/chọn việc; gọi `InputDevice` ngoài khóa. `heldKeys`/`heldButtons` chỉ được đọc và ghi trên worker.
- **Trạng thái:** `IDLE`, `OPEN(gen)`, `ENDING(gen)`, `FAULTED`, `STOPPED`. Generation tăng đơn điệu và không tái sử dụng. Cùng một khóa bảo vệ phase, generation, deque, ô lifecycle và việc nhận lệnh đang chạy (`inFlight`).
- **`beginSession(gen)`**: đồng bộ ngắn, chỉ chuyển `IDLE → OPEN(gen)` nếu generation mới lớn hơn mọi generation đã nhận; trả `true`. Các trường hợp khác trả `false`. Không xếp tác vụ BEGIN vào queue. Client gọi hàm này khi nhận `SESSION_ACTIVE`, trước khi dispatch lệnh đầu tiên; thất bại thì đóng phiên.
- **`submit(gen, cmd)`**: dưới cùng khóa, chỉ nhận ở `OPEN(gen)`, trả `STALE` nếu phiên không còn hợp lệ. Nếu deque đầy, trả `OVERFLOW`; caller đóng đúng phiên đó sau khi đã rời khóa. Nếu nhận thì trả `ACCEPTED`. Worker lấy lệnh và đánh dấu `inFlight` nguyên tử dưới khóa; đây là điểm lệnh bắt đầu. Lệnh đã bắt đầu có thể chạy nốt khi end đến; không giữ khóa trong khi gọi Robot.
- **`endSession(gen)`**: dưới khóa, chuyển `OPEN(gen) → ENDING(gen)`, bỏ toàn bộ lệnh thường chưa bắt đầu của generation đó và đặt yêu cầu END vào ô lifecycle. Worker chạy END ngay sau `inFlight` hiện tại. Gọi lặp cho cùng generation trả cùng future; gọi với generation cũ không tác động phiên mới, không tạo thêm END. Không cho phép begin mới ở `ENDING`, nên chỉ cần một ô lifecycle riêng.
- Khi phiên mạng chưa từng begin input, end là no-op trả future thành công. Chỉ giữ record kết thúc gần nhất; generation cũ hơn trả future no-op, không tích lũy map future theo tất cả các phiên. Nếu module FAULTED, begin luôn bị từ chối và kết quả lỗi gần nhất vẫn được báo cho UI.
- **Kết thúc END:** worker cố thả mọi phím/nút, tiếp tục thử phần còn lại nếu một lần thả lỗi. Chỉ khi mọi lần thả thành công mới chuyển `ENDING → IDLE` và hoàn tất future thành công. Nếu có lỗi thì chuyển `FAULTED`, hoàn tất exceptionally, khóa kết nối mới và hiện lỗi. Nếu Robot không trả về, giữ `ENDING` và cảnh báo sau 2 giây; không coi timeout là đã nhả phím.
- Hoàn tất future và mọi callback diễn ra **ngoài khóa**. Sau mỗi lần đổi trạng thái phải kiểm tra generation. `endSession(oldGen)` không được xóa held state của generation mới.
- **`RELEASE_ALL` giữa phiên:** là lệnh thường theo FIFO, không đóng generation. Queue đầy vẫn dẫn tới đóng phiên; END riêng bảo đảm đường nhả phím tồn tại. Không dùng ô END cho các `RELEASE_ALL` lặp. Lỗi nhả phím giữa phiên khiến module `FAULTED` và đóng phiên, không giả vờ tiếp tục an toàn.
- **Theo dõi đầu vào:** chỉ release phím/nút thuộc tập đang giữ; bỏ mouse press trùng, giữ key press trùng để hỗ trợ repeat. Với press/release chuột hợp lệ, move tới tọa độ sự kiện trước thao tác. Khi độ phân giải thay đổi, release nút đang giữ vẫn phải được thử ngay cả khi tọa độ mới không còn hợp lệ; không để kiểm tra tọa độ ngăn nhả nút.
- Mã phím không được Robot hỗ trợ được log có giới hạn tần suất và bỏ qua; lỗi không dự kiến phải đưa module vào trạng thái lỗi và báo cho phiên. Không để exception làm worker chết âm thầm.
- Dùng Robot riêng cho `RobotInputDevice` và `RobotScreenSource`; chỉ `rd-input` gọi **Robot đầu vào**, `ScreenSender` gọi Robot chụp ảnh. Không chia sẻ khóa capture với input. Đặt auto delay bằng 0 và không waitForIdle ở luồng input.
- **Ba quyết định review:** giữ mức tối đa 3 gói cuộn/sự kiện (mục 3.3); giữ một ô END riêng với tính idempotent; bỏ chính sách tự bật Kết nối sau 2 giây. Hai giây chỉ là mốc cảnh báo, không phải bằng chứng thiết bị đã sẵn sàng.

---

## 4. Giao diện và thống kê

### 4.1 Server
**Cửa sổ chính gồm:**
- Danh sách IPv4 của máy.
- Cổng đang dùng.
- Mã 6 số (chữ lớn) và nút *Đổi mã*.
- Nhãn trạng thái (`LISTENING`, `RESERVED`, `ACTIVE`).

**Khi phiên `ACTIVE`:**
- Mở cửa sổ điều khiển chứa `RemotePanel`.
- Thanh trạng thái hiện: FPS, KB/s (ảnh và lệnh), RTT, cảnh báo ảnh gián đoạn.
- Nút *Ngắt*.
- Đóng cửa sổ điều khiển tương đương bấm *Ngắt*.

### 4.2 Client
**Cửa sổ chính gồm:**
- Ô nhập IP.
- Ô nhập mã.
- Nút *Kết nối*.
- Nhãn trạng thái và lý do lỗi.
- Ở các trạng thái `CONNECTING`, `AUTHENTICATING` và `ATTACHING`, nút *Kết nối* đổi thành *Hủy*. Bấm *Hủy* sẽ gọi `closeSession(USER)`. Ô nhập IP và mã bị khóa trong lúc này.
  - *Hủy* có tác dụng ngay vì mọi socket đã được `session.register()` **trước** khi gọi `connect()` hoặc `read()` (mục 1.4). Khi đóng phiên, socket bị đóng, lệnh đang chặn ném `SocketException`, và luồng kết nối thoát ra.
  - `register()` gọi sau khi phiên đã đóng sẽ đóng socket ngay rồi ném `ClosedSessionException`. Nhờ vậy, không có khoảng hở nào giữa lúc tạo socket và lúc phiên biết đến socket đó.
- Nút *Kết nối* chỉ bật lại khi socket cũ đóng và việc nhả phím của phiên trước thành công. Hai giây chỉ bật cảnh báo, không mở khóa kết nối (mục 1.8).

**Khi phiên `ACTIVE`:**
- Hiện một thanh nhỏ luôn nằm trên cùng (`setAlwaysOnTop(true)`) với dòng "Đang bị điều khiển bởi `<IP>`" và nút *Dừng*.
- Đóng cửa sổ chính cũng tương đương bấm *Dừng*.

### 4.3 Thống kê
- `TrafficCounter` đếm số byte đọc/ghi trên từng kênh.
- Một `javax.swing.Timer` chạy mỗi 1 giây, cập nhật FPS (số khung nhận hoặc gửi trong 1 giây), KB/s và RTT (lấy từ `PONG` gần nhất).

### 4.4 Lỗi khi khởi động
- Cổng đã bị chiếm (`BindException`): báo lỗi rõ, đóng cổng nào đã mở được, cho phép nhập cổng khác qua tham số `--cmd-port` và `--img-port`.
- Không tạo được Robot (`AWTException`, `HeadlessException`) hoặc không chụp được màn hình: báo lỗi rõ, `closeSession` và quay về `IDLE`.
- Windows Firewall có thể chặn cổng lắng nghe của Server. Hướng dẫn cho phép cổng được ghi trong README.

### 4.5 Đóng ứng dụng
Đóng cửa sổ chính (`windowClosing`, dùng `DO_NOTHING_ON_CLOSE`) gọi `App.shutdown()`. Hàm dùng một shutdown future chung, CAS bật shuttingDown ngay trên luồng gọi trước khi giao việc; gọi lặp nhận cùng future. Trên EDT chỉ khóa giao diện rồi giao phần còn lại cho rd-shutdown.

**Trên `rd-shutdown`, theo thứ tự:**
1. Đặt `app.shuttingDown = true` **trước tiên**. Từ lúc này:
   - Mọi callback kết thúc phiên bỏ qua bước "về `LISTENING`/`IDLE`, sinh mã mới, mở lại giao diện" (mục 1.8, bước 7).
   - Luồng accept đóng mọi socket mới nhận được ngay lập tức.
   - Client không bắt đầu kết nối mới.
2. Đóng hai `ServerSocket` (chỉ Server), để luồng accept thoát ra.
3. `closeSession(SHUTDOWN)` nếu đang có phiên. Việc gửi `DISCONNECT` vẫn là **best effort**, chờ tối đa 500 ms, và bên kia không chắc nhận được.
4. **(Chỉ Server)** `pendingConnections.closeAll()`: gọi `claimForClose()` rồi đóng mọi socket chưa bàn giao, gồm cả socket đang được handler xử lý lẫn socket đang nằm trong hàng đợi của pool. Handler đang chặn ở `read()` sẽ nhận `SocketException` và thoát.
5. `shutdownNow()` pool `HandshakeHandler`. Hàm này trả về danh sách các task chưa chạy; mỗi task đó giữ một `PendingConnection`, và được đóng thêm một lần nữa bằng `claimForClose()`. Bước này vẫn an toàn khi bước 4 đã đóng socket đó rồi.
6. Chờ luồng cleanup chạy xong giai đoạn B, tối đa 1 giây.
7. Gọi `InputExecutor.shutdownAsync()`, ngừng scheduler/cleanup sau khi socket đã đóng. Tổng thời gian chờ background shutdown tối đa 5 giây từ lúc bắt đầu; không chờ 5 giây riêng cho mỗi executor. Khi hết hạn, interrupt là best effort, không bảo đảm ngắt được Robot native. Ghi rõ release pending/failure thay vì báo đã nhả phím.
8. `SwingUtilities.invokeLater(...)`: các thao tác giao diện **chạy trên EDT**:
   - Dừng mọi `javax.swing.Timer`.
   - Gỡ các listener chuột, phím, focus và window đã đăng ký.
   - `dispose()` các cửa sổ.

**Shutdown hook của JVM** (ví dụ Ctrl+C) gọi cùng đường shutdown và chờ future trong deadline tổng cho bước 1–7; nếu shutdown đã chạy vẫn chờ kết quả, không return chỉ vì CAS thất bại. Không phụ thuộc EDT hay chỉ schedule daemon task rồi thoát hook. Không có bước 8 vì EDT có thể không còn xử lý tác vụ.

Mọi luồng nền được tạo bằng `ThreadFactory` đặt `daemon = true` và đặt tên rõ ràng (ví dụ `rd-cmd-writer`, `rd-frame-receiver`, `rd-input`) để dễ debug bằng `jstack`. `daemon = true` **chỉ giúp tiến trình thoát được**, không thay thế cleanup: socket, hàng đợi và phím đang giữ vẫn phải được dọn theo đúng các bước trên.

---

## 5. Kiểm thử và tiêu chí nghiệm thu

### 5.1 Test tự động (JUnit 5, `mvn test`, chạy được không cần màn hình)
- `PacketIO`:
  - Ghi rồi đọc lại đủ mọi loại gói (round-trip).
  - Đọc qua một `InputStream` mỗi lần chỉ trả 1 byte (mô phỏng gói bị chia nhỏ).
  - `length` âm, quá lớn, hoặc sai độ dài cố định.
  - `type` lạ.
  - EOF giữa header và EOF giữa payload.
- `CommandWriter`:
  - Gộp các `MOVE` ở cuối hàng đợi.
  - Không gộp xuyên qua `PRESS`/`RELEASE`.
  - Hàng đợi đầy thì đóng phiên.
  - `closeGracefully`:
    - Các gói chưa bắt đầu gửi bị bỏ.
    - Gói đang ghi dở được ghi nốt.
    - `done` chỉ hoàn tất **sau khi** `DISCONNECT` đã được ghi và `flush()` xong; test dùng `OutputStream` giả để chặn bước `flush()`.
    - `offer()` sau khi đóng trả `false`.
    - Gọi lại lần nữa trả về đúng future cũ.
- `SessionBase`:
  - `closeIf` với phase sai không làm gì.
  - Callback deadline chạy đồng thời với lúc chuyển phase (dùng `CountDownLatch` để ép đúng thứ tự xen kẽ) không đóng nhầm phiên.
  - `register()` sau khi phiên đã đóng thì đóng socket và ném lỗi.
- `PendingConnection`: chỉ một trong hai thao tác `claimForHandover()` / `claimForClose()` thành công, kể cả khi chạy đồng thời.
- `CoordinateMapper`:
  - Panel lớn hơn hoặc nhỏ hơn màn hình Client, tỷ lệ khác nhau.
  - Bấm vào viền trống.
  - Chặn tọa độ ở mép.
- `SessionManager`:
  - 2 luồng gọi `tryReserve` cùng lúc, chỉ 1 thành công.
  - Khóa IP sau 3 lần sai; nhập sai không làm đổi mã.
  - Token chỉ dùng được một lần.
  - Callback của phiên cũ không tác động lên phiên mới.
- `InputExecutor` (dùng `InputDevice` giả):
  - begin rồi submit ngay vẫn nhận lệnh đầu; begin rồi end trước worker chạy không hồi sinh generation.
  - stale/repeated end không thêm END hoặc tác động generation mới; release lỗi đưa FAULTED và khóa reconnect.
  - Observer 2 giây không hoàn tất future nhả phím; module còn ENDING, sockets vẫn đóng.
  - `endSession` thả đúng các phím và nút đang giữ, và hoàn tất future.
  - `submit` sau `endSession` không có tác dụng.
  - `endSession` **trả về ngay** dù `InputDevice` giả đang bị chặn bên trong `keyPress`.
  - **Thứ tự giữa hai phiên:** chặn InputDevice ở lệnh phiên 1, gọi end(1); begin(2) phải trả false. Giải phóng lệnh, đợi release thành công, rồi begin(2) và submit(2). Nhật ký phải là lệnh phiên 1, release phiên 1, input phiên 2. end(1) gọi muộn không tác động phiên 2.
  - Nhận `KEY_PRESS` trùng vẫn nhất quán.
  - Deque đầy thì `submit` trả `OVERFLOW` và caller đóng phiên; END vẫn vào được ô lifecycle riêng và nhả phím.
- `InputCapture`: test logic headless nhận snapshot focus/control và sự kiện được truyền vào; không giả định `requestFocusInWindow()` trên JPanel không hiển thị sẽ lấy được focus. Focus Swing thật kiểm tra ở GUI smoke test:
  - Click khi panel chưa có focus không tạo ra packet nào; click tiếp theo thì tạo packet.
  - Cuộn tích lũy 45 notch được tách thành `20, 20, 5`.
  - Mất focus thì xóa `wheelAcc`, và `KEY_RELEASE` đến muộn không được gửi.
  - Khi `imageStalled` chuyển `false → true` thì chỉ gửi đúng **một** `RELEASE_ALL`, dù watchdog kiểm tra nhiều lần.
- Tích hợp trên localhost (dùng `ScreenSource` và `InputDevice` giả, không dùng GUI):
  - Luồng đầy đủ từ AUTH đến ACTIVE, gửi FRAME và lệnh, rồi DISCONNECT.
  - Xác thực xong nhưng không attach thì hết hạn sau 5 giây.
  - Attach bằng token sai thì phiên vẫn còn.
  - Bên kia ngừng đọc thì watchdog đóng được socket.
  - Hai bên cùng ngắt một lúc thì cleanup chỉ chạy một lần.
  - Kết nối lại ngay sau khi phiên cũ đóng.
  - **Handshake nhỏ giọt:** gửi mỗi 2,9 giây một byte; kết nối phải bị đóng ở khoảng giây thứ 3, tính từ `accept()`.
  - **Pool đầy:** mở 4 + 8 + 1 kết nối im lặng. Kết nối cuối phải bị đóng ngay, tất cả bị đóng sau 3 giây, rồi kết nối hợp lệ vẫn vào được.
  - **Ngắt ở từng giai đoạn:** đóng socket hoặc gửi `DISCONNECT` lúc `AUTHENTICATING`, `RESERVED` (trước và sau `ATTACH`) và `ACTIVE`. Mỗi lần đều kiểm tra: Server về `LISTENING`, không còn luồng `rd-*` nào của phiên cũ, và `InputDevice` giả không còn phím/nút nào đang giữ.
  - **Thứ tự kích hoạt:** Client giả nhận được `AUTH_RESULT` trước `SESSION_ACTIVE`, sau đó mới đến lệnh điều khiển. Server không gửi lệnh nào trước khi nhận `FRAME` đầu tiên.
  - **Chờ kích hoạt quá hạn:** Server giả không bao giờ gửi `SESSION_ACTIVE`; Client phải đóng phiên sau 7 giây.
  - **Gói sai trạng thái:** gửi `FRAME` có header khai báo 8 MiB khi chưa attach; kết nối phải bị đóng ngay sau khi đọc byte `type`, không đọc payload.
  - **Mạng chậm:** test chọn tham số để **chắc chắn** tạo ra khoảng gián đoạn lớn hơn 3 giây:
    - `ScreenSource` giả trả về ảnh nhiễu ngẫu nhiên có seed cố định, để JPEG khó nén.
    - Test nén thử một khung để đo `S` = số byte của khung.
    - Đặt `ThrottledOutputStream` trên kênh ảnh với tốc độ `S / 5` byte/giây, tức khoảng 5 giây mỗi khung.

    Kiểm tra:
    - Cảnh báo bật trong khoảng 3–5 giây sau khung trước.
    - Đúng một `RELEASE_ALL` được gửi.
    - Cảnh báo tắt khi khung tiếp theo đến.
    - Phiên không bị đóng, vì khoảng 5 giây nhỏ hơn mốc 15 giây.

    Biến thể `S / 20` kỳ vọng watchdog ghi 10 giây có thể đóng trước frame timeout 15 giây. Để test riêng frame timeout: Client giả giữ heartbeat nhưng không gửi frame và không bắt đầu write; Server đóng do frame timeout. Không khẳng định frame timeout thắng trong test có write đang kẹt.
  - **Chưa có khung đầu tiên:** `ScreenSource` giả không trả về khung nào. Cảnh báo phải bật sau 3 giây và phiên đóng sau 15 giây, cả hai tính từ lúc `ACTIVE`.
  - **Hủy khi đang kết nối:** adapter kết nối giả có latch giữ bước connect, quan sát socket được register trước khi gọi; Hủy đóng socket và giải phóng latch/worker. Test socket thật riêng ở bước read AUTH_RESULT với server đã accept nhưng không trả lời. Không dùng ServerSocket không accept làm bằng chứng connect bị chặn, vì TCP có thể hoàn tất qua backlog.
  - **Không có `AUTH_RESULT`:** Server giả không trả lời; Client phải đóng phiên sau 5 giây.
  - **Shutdown khi pool đang bận:** tạo 4 kết nối đang bắt tay và 8 kết nối trong hàng đợi rồi shutdown. Mọi socket đóng; các worker hợp tác dừng trong deadline tổng 5 giây cộng dung sai lập lịch. Ca Robot fake bị chặn kiểm tra đóng mạng và báo release pending riêng, không đòi native call dừng nhờ interrupt.
  - **Cleanup không bị chặn:** dùng fake write/flush có latch hoặc xác nhận socket send buffer đã đầy. Từ closeStartedAt, socket đóng sau tối đa 500 ms cộng dung sai lập lịch, kể cả cleanup executor bận/từ chối; không chờ release input.

### 5.2 Tiêu chí nghiệm thu (kiểm tra thủ công trên 2 máy LAN)
- Cổng 5000/5001 đã bị chiếm: báo lỗi rõ, đóng các cổng đã mở.
- Hai Client xác thực đồng thời: chỉ một Client giữ được phiên.
- Xác thực xong nhưng không attach: hết deadline thì giải phóng phiên và đổi mã.
- Attach bằng token sai, token cũ hoặc attach lần hai: chỉ đóng kết nối ảnh đó, giữ nguyên phiên hợp lệ.
- Packet bị chia nhỏ qua nhiều lần đọc: vẫn ghép và đọc đúng.
- `length` sai, JPEG lỗi, gói sai trạng thái: từ chối có kiểm soát, không treo và không cấp phát quá lớn.
- Mất mạng khi đang giữ Ctrl hoặc đang kéo chuột: Client tự nhả toàn bộ phím và nút.
- Bên kia ngừng đọc: watchdog đóng được socket đang bị kẹt khi ghi.
- Bấm *Ngắt* và *Dừng* đồng thời: cleanup một lần, không lỗi trạng thái.
- Kết nối lại ngay sau phiên cũ: tác vụ của phiên cũ không ảnh hưởng phiên mới.
- Không tạo được Robot hoặc thiếu quyền: báo lỗi rõ và giải phóng phiên.
- Resize cửa sổ, Alt+Tab ở máy điều khiển, Client đặt scaling 100% / 125% / 150%: chuột không lệch, phím không bị kẹt.
- Thu nhỏ cửa sổ điều khiển rồi mở lại: không lỗi, tọa độ vẫn đúng.
- Đổi scaling hoặc độ phân giải trên Client trong lúc đang có phiên: phiên đóng với lý do `SCREEN_CHANGED`, và kết nối lại được.
- Key repeat: giữ Backspace và giữ một phím chữ 2 giây; máy Client lặp đúng, không bị lặp gấp đôi (mục 3.4).
- Bấm *Hủy* trong lúc đang kết nối tới một IP không tồn tại: giao diện phản hồi ngay, về `IDLE`.
- Đóng ứng dụng giữa phiên: tiến trình Java thoát hẳn (Task Manager hoặc jps); bên kia kết thúc nhờ DISCONNECT/EOF hoặc timeout liveness 8 giây cộng một chu kỳ watchdog và dung sai lập lịch được ghi trong kết quả. DISCONNECT là best effort.
- Click lần đầu vào cửa sổ điều khiển chỉ để chọn cửa sổ, không gây click trên máy Client; từ click thứ hai trở đi thì điều khiển bình thường.

**Độ ổn định**
- **Chạy lâu:** để phiên `ACTIVE` liên tục 30 phút. Không rò bộ nhớ: heap ổn định trong VisualVM hoặc theo `jcmd <pid> GC.heap_info`. Không mất kết nối, FPS ổn định.
- **Kết nối/ngắt lặp lại:** 50 lần liên tiếp, xen kẽ giữa *Dừng* và *Ngắt*. Lần nào cũng kết nối lại được; số luồng `rd-*` không tăng dần (kiểm tra bằng `jstack`).
- **Ngắt ở từng giai đoạn trên máy thật:** rút cáp hoặc tắt Wi-Fi lúc đang nhập mã, lúc vừa xác thực xong, và lúc đang kéo chuột.
- **Sleep/wake:** cho máy Client ngủ (sleep) giữa phiên. Server phải đóng phiên trong khoảng 8–15 giây. Khi Client thức dậy, Client tự đóng phiên cũ, không bị kẹt phím, và kết nối lại được.
- **Mạng chậm thật:** dùng Wi-Fi yếu, hoặc công cụ `clumsy` trên Windows để thêm độ trễ và mất gói. Ghi lại FPS, RTT và việc khóa/mở điều khiển vào báo cáo.
- Hiệu năng (mục tiêu đo, không phải tiêu chí bắt buộc):
  - Mục tiêu 10–12 FPS khi giới hạn 12 FPS ở 1280px; ghi CPU, heap, cấu hình máy, độ phân giải, DPI và loại mạng.
  - Mục tiêu p95 thao tác đến ảnh phản hồi dưới 150 ms trong LAN tham chiếu; đo bằng video quay đồng thời thao tác và ảnh phản hồi với tối thiểu 100 mẫu. RTT ghi riêng, không dùng thay cho độ trễ đầu cuối. Nếu chưa đạt thì ghi số đo và điều chỉnh FPS/JPEG trước gate phát hành.

---

## Nhật ký quyết định
- Server tạo mã; Client nhập IP và mã. Bấm *Kết nối* đồng nghĩa với đồng ý chia sẻ.
- Dùng JDK 21 và Maven.
- Dùng blocking I/O với hai kênh TCP.
- Trạng thái phiên và trạng thái bắt tay tách riêng; giữ chỗ phiên là một thao tác `synchronized` duy nhất.
- Mỗi socket chỉ có một writer.
- Liveness tính theo bất kỳ gói hợp lệ nào nhận được; có watchdog riêng cho thao tác ghi.
- Key repeat vẫn được gửi đi; Client theo dõi phím đang giữ bằng `Set`.
- `magic` và `version` chỉ có trong gói đầu tiên của mỗi kênh.
- Nhập sai mã không làm đổi mã; giới hạn thử theo IP (chờ 1 giây, khóa 30 giây sau 3 lần sai).
- Kích thước màn hình đổi giữa phiên thì đóng phiên.

**Vòng review thứ 2:**
1. Bắt tay dùng deadline tổng 3 giây kể từ `accept()`. Pool `HandshakeHandler` gồm 4 luồng và hàng đợi hữu hạn 8; vượt quá thì đóng kết nối.
2. Thứ tự gói Server gửi luôn là `AUTH_RESULT → SESSION_ACTIVE → lệnh`. Server chỉ bật điều khiển sau khung hình hợp lệ đầu tiên.
3. Mỗi giai đoạn có một chủ sở hữu socket rõ ràng. Khi bàn giao, chuyển nguyên bộ stream wrapper.
4. Kênh ảnh là một chiều. Client phát hiện attach thất bại nhờ deadline 7 giây chờ `SESSION_ACTIVE`.
5. `closeSession` chia hai giai đoạn: A không chặn, B chạy trên luồng cleanup và luôn đóng socket trong `finally`.
6. `MOUSE_PRESS`/`MOUSE_RELEASE` mang theo tọa độ. Sự kiện đến muộn sau khi mất focus bị chặn. Key repeat vẫn giữ, nhưng phải kiểm chứng thực tế.
7. Kiểm tra miền giá trị cho mọi payload. Gói sai trạng thái bị từ chối ngay sau byte `type`.
8. Ảnh gián đoạn thì khóa điều khiển và gửi `RELEASE_ALL`. Thừa nhận TCP vẫn có thể giữ khung cũ; giảm buffer gửi của kênh ảnh xuống 64 KB.
9. Xử lý gốc tọa độ, thay đổi DPI và panel có kích thước 0. Có nút *Hủy* khi đang kết nối. Thêm quy trình `shutdown()` khi đóng ứng dụng.
10. Thêm test tự động và kiểm thử thủ công về độ ổn định.

**Vòng review thứ 3 — lịch sử, đã được thay thế ở các điểm ghi trong vòng 4** (thiết kế lại theo hướng module sâu):
1. Đề xuất rd-input FIFO được rà lại ở vòng 4: begin không còn là task queue, END có ô riêng, trạng thái được bảo vệ bằng khóa metadata ngắn và chỉ nhận phiên mới sau release thành công. Không dùng volatile đơn lẻ làm bảo đảm thứ tự.
2. Client có deadline auth 5 giây và activation 7 giây trước connect ảnh. Socket register trước thao tác chặn; vòng 4 tách deadline phase khỏi pending CAS và watchdog timestamp.
3. Thêm `PendingConnections` với CAS `claimForHandover`/`claimForClose`. Shutdown đặt cờ `shuttingDown` trước tiên, đóng mọi socket chưa bàn giao và cả các task trả về từ `shutdownNow()`. Các thao tác giao diện chạy trên EDT. `daemon = true` không thay thế cleanup.
4. Click đầu tiên khi panel chưa có focus chỉ để lấy focus. Xóa `wheelAcc` khi mất focus hoặc khi khóa điều khiển.
5. Lượng cuộn được tách thành các gói tối đa 20 notch; mỗi sự kiện tối đa 3 gói.
6. `closeGracefully()`: các gói chưa bắt đầu gửi bị bỏ, gói đang ghi dở được ghi nốt. Future chỉ hoàn tất sau khi gói cuối đã được ghi và `flush()` xong.
7. Sửa các câu mâu thuẫn:
   - Thứ tự `AUTH_RESULT` được bảo đảm nhờ flush trước khi bàn giao, sau đó mới đến FIFO.
   - Mỗi chiều có một chủ ở từng giai đoạn; sau bàn giao chủ ghi là CommandWriter hoặc ScreenSender.
   - Bỏ câu "không dồn khung cũ" và câu "buffer giữ 1–2 khung".
   - Sửa chữ ký `PacketIO.read` ở mục 1.2.
8. Mốc timeout ảnh tính từ lúc `ACTIVE`. `RELEASE_ALL` chỉ gửi khi trạng thái chuyển sang gián đoạn (edge-triggered). Kiểm tra loại gói chia hai lớp: `allowed` cố định theo reader, và kiểm tra theo phase dưới khóa trong `dispatch()`. `DISCONNECT` là best effort. Test mạng chậm tính tốc độ giới hạn theo kích thước khung đo được.

**Vòng review thứ 4 — baseline triển khai:**

1. Thay begin task + activeGen volatile bằng admission nguyên tử và state IDLE/OPEN/ENDING/FAULTED/STOPPED trong InputExecutor; không mất input đầu và không hồi sinh phiên đã end. Worker input riêng với deque hữu hạn và ô END riêng, không dùng factory executor có queue vô hạn (mục 3.5).
2. Hai giây chỉ cảnh báo nhả phím chậm; không mở reconnect và không complete future thật bằng timeout. Release lỗi khóa input, nhả thành công mới cho begin mới (mục 1.8, 3.5, 4.2).
3. Giữ wheel tối đa 3 packet/sự kiện; giới hạn phần nguyên là lựa chọn UX được ghi rõ, không được mở rộng sang bỏ KEY_RELEASE/BUTTON_RELEASE.
4. Raw socket đóng trước buffered wrappers; deadline 500 ms tính từ lúc close bắt đầu, độc lập với queue cleanup; xử lý partial init và executor rejection (mục 1.8).
5. Thêm hợp đồng khóa, transfer-register, stale deadline timestamp, generation của callback UI và buffer bất biến (mục 1.9).
6. Bật frame đầu bằng hasFrame riêng, không phụ thuộc cạnh imageStalled; tách Robot capture/input; sửa tính số thực và giới hạn frame (mục 1.7, 2.3, 3).
7. Sửa test reconnect, focus headless, connect backlog và watchdog 10 giây so với frame timeout 15 giây. Chốt smoke tests Windows và gate nghiệm thu trong kế hoạch đi kèm.
8. Kiểm tra mô hình trừu tượng: `python docs/superpowers/reviews/check_input_lifecycle_model.py` — 58 states, 203 transitions đạt các invariant mô hình, tái hiện 2 phản ví dụ của thiết kế cũ. Không phải chứng minh Java/Robot/TCP đã hoạt động.

Báo cáo review và nguồn JDK: [2026-10-19-remote-desktop-review.md](../reviews/2026-10-19-remote-desktop-review.md).
