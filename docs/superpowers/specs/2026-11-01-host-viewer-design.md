# Thiết kế: Đổi vai Host/Viewer (Protocol v2)

Ngày: 2026-11-01. Branch: `feat/wave3-4-desktop-v1`. Thay thế quy ước vai trò ở mục 0.2, luồng phiên 1.4 và bảng 2.4 của `2026-10-19-remote-desktop-design.md` khi v2 xong.

## Trạng thái duyệt

| Phần | Nội dung | Trạng thái |
| --- | --- | --- |
| 1 | Hướng kết nối, bắt tay, deadline, hủy/mất kênh | Đã duyệt (2026-10-31) |
| 2 | Tách package/lớp theo vai, trình tự commit, kiểm thử | **Đã duyệt để triển khai** (2026-11-01, người dùng chốt P2 sau review lần 4). A0 đã xong (`1b3eb94`); chưa có bằng chứng v2 |
| 2′ | Lộ trình G0 → C0 → A1 → A2 → A3 → A4 → B0 → review toàn đợt | Đã thực thi, review PASS / APPROVED; code v1 `84a4682`. Follow-up pending ticker đã sửa ở `e58afc7`, coordinator verify 912 test đạt; chưa có code/test v2. [Báo cáo chuẩn bị v2](../reports/2026-11-02-host-viewer-v2-preparation.md). |
| 3 | Giao diện và CLI (tên cửa sổ, cờ dòng lệnh) | [Thiết kế đã qua builder/re-review và final Astra](2026-11-02-host-viewer-ui-cli-h2-design.md), **người dùng duyệt để tiếp tục B1/B2 ngày 2026-11-02**. |
| 4 | Tài liệu, lịch, nghiệm thu H2 | Cùng thiết kế Phần 3–4 ở trên, **đã duyệt** ma trận hai hướng và tiêu chí bằng chứng; chưa có kết quả H2 v2, máy/người/lịch thực tế được xác nhận khi chạy. |

Định tuyến hiện hành theo người dùng: thực thi dùng Codex `gpt-6.1-sol`, effort theo độ khó; review cuối được phép dùng `gpt-6-astra`, không dùng Luna/model rẻ hơn hoặc giao thêm Claude. Các tên Sonnet trong lịch sử thiết kế bên dưới là định tuyến cũ. Khi quota còn khoảng 5%, lưu checkpoint và dừng; sau reset chỉ tiếp tục khi người dùng nói “bắt đầu”.

## 0. Mục tiêu

Trải nghiệm giống UltraViewer:
- **Host** = máy bị điều khiển. Nó lắng nghe kết nối, hiện IP và mã 6 số, kiểm tra mã.
- **Viewer** = máy điều khiển. Người dùng nhập IP và mã, thấy màn hình Host và gửi chuột/phím.
- **Đồng ý = đưa mã.** Người ngồi ở Host đọc mã cho người kia là đã cho phép; không có hộp thoại xác nhận thêm.

Cách làm: giữ nguyên logic của cả hai nửa hiện có, chỉ đổi hướng kết nối. Dòng `Protocol.VERSION` đổi từ 1 lên 2. Hai phiên bản không tương thích nhau và được từ chối rõ ràng.

Ngoài phạm vi: P1b (FRAME_ACK, freshness) để dành cho v3, xem `docs/superpowers/plans/2026-10-27-remote-upgrade-plan.md`.

Điều kiện đã có trước khi bắt đầu: phần sửa gate "đã áp ≠ đã vẽ" đã nằm trong commit `960bf81` (GĐ0, xong trước H2).

## 1. Hướng kết nối và bắt tay (đã duyệt)

### 1.1 Ai làm gì

| Việc | v1 | v2 |
| --- | --- | --- |
| Lắng nghe 5001 (lệnh) và 5000 (ảnh), giữ mã, kiểm tra mã | Server (máy điều khiển) | **Host** |
| Chủ động kết nối cả hai kênh, gửi AUTH và ATTACH | Client (máy bị điều khiển) | **Viewer** |
| Gửi SCREEN_INFO và FRAME | Client | **Host** |
| Gửi MOUSE_*, WHEEL, KEY_*, RELEASE_ALL | Server | **Viewer** |
| Theo dõi frame (stall 3 s, timeout 15 s) và gate/epoch | Server | **Viewer** |
| Chạy input bằng Robot, nhả phím khi phiên đóng | Client | **Host** |

Token 16 byte gắn kênh ảnh vào phiên đã xác thực. Điều khiển chỉ được phép sau khi phiên vào `ACTIVE` và frame đầu tiên đã được vẽ.

### 1.2 Trạng thái

- Host: `LISTENING → RESERVED → ACTIVE → CLOSING → LISTENING`. Giữ nguyên `tryReserve`, `AuthGuard` (sai thì chờ 1 s; sai 3 lần thì khóa IP 30 s) và `PendingConnection` CAS. Pool vẫn 4 luồng, hàng đợi 8.
  - Thay đổi: `RESERVED → ACTIVE` chỉ cần **ATTACH hợp lệ**, vì SCREEN_INFO giờ do Host gửi.
- Viewer: `IDLE → CONNECTING → AUTHENTICATING → ATTACHING → ACTIVE → CLOSING → IDLE`. Giữ nguyên quy tắc "đăng ký socket với phiên trước mọi thao tác có thể chặn", nhờ vậy nút Hủy luôn có tác dụng.

### 1.3 Thứ tự bắt tay

1. Viewer `register(cmd)`, rồi `connect(ip:5001, 3 s)` và chuyển sang `AUTHENTICATING`.
2. Viewer đặt deadline 5 s chờ `AUTH_RESULT`, rồi ghi `AUTH(magic, version=2, code)`.
3. Host chạy `HandshakeHandler` (deadline tổng 3 s kể từ `accept()`). Nó gọi `tryReserve` rồi ghi trực tiếp và flush `AUTH_RESULT`, như v1. Handler không chuẩn bị adapter; I/O bắt tay và bàn giao vẫn chịu deadline/CAS của `PendingConnection`.
   - Nếu kết quả không phải `OK` (`WRONG_CODE`, `BUSY`, `LOCKED`, `BAD_VERSION`): Host đóng kết nối.
   - Nếu `OK` (kèm token): Host bàn giao kết nối cho phiên rồi khởi động `CommandWriter`, reader và heartbeat. Phiên đã vào `RESERVED` tại `tryReserve`; hạn reserve là `reservedAt + 5 s`, không được tính lại khi bàn giao hoặc khi worker bắt đầu.
   - Ngay khi bàn giao xong, phiên chạy worker chuẩn bị adapter **`rd-host-prepare`** (mục 1.8).
     - Worker này do lifecycle quản lý, nên `terminated()` chờ cả nó.
     - Worker refresh thiết bị input và tạo `ScreenSource` mới, chạy ngoài mọi khóa.
     - Sau đó, trong tx, nó kiểm phiên còn hợp lệ, `offer(SCREEN_INFO(w, h))`; chỉ khi writer trả `ACCEPTED` mới lưu geometry và đánh dấu sẵn sàng.
4. Viewer nhận `OK` rồi làm đúng thứ tự:
   1. Chuyển sang `ATTACHING` và ngay lập tức đặt deadline 7 s chờ `SESSION_ACTIVE`. Deadline này bao cả bước connect kênh ảnh lẫn bước ghi ATTACH.
   2. Bàn giao stream kênh lệnh cho reader của phiên. **Phải dùng lại đúng object `InputStream` đã đọc `AUTH_RESULT`**, không bọc lại (xem `Channel.java:15`), vì `SCREEN_INFO` có thể đã nằm sẵn trong buffer.
   3. `register(img)`, `connect(ip:5000, 3 s)`, rồi ghi trực tiếp `ATTACH(magic, version=2, token)`.
   4. Sau flush và hook `afterAttachFlushed`, bàn giao `Channel img` vào phiên trong tx, đặt `imageChannelReady`, rồi thử kích hoạt. Socket đã đăng ký từ trước, nên close trong khoảng này vẫn đóng được nó; không được công bố kênh hay kích hoạt nếu close/deadline đã thắng.
5. Host thử kích hoạt (`maybeActivate`, trong tx) mỗi khi một trong hai điều kiện vừa xong. Hai điều kiện là "đã chuẩn bị và `SCREEN_INFO` đã được writer nhận" và "ATTACH hợp lệ", đến theo **thứ tự bất kỳ** (giống cặp `SCREEN_INFO` + `ATTACH` ở v1, `ServerSession.java:313-325`). Trả về ngay nếu phiên đã đóng, không còn `RESERVED` hoặc đã thử kích hoạt. Sau đó đọc clock **trong tx**: nếu đã tới hạn (`now >= reservedAt + reserve`) thì đóng `TIMEOUT` "attach timeout", kể cả khi ticker chưa chạy. Khi còn hạn và đủ điều kiện, trong cùng tx:
   1. Đánh dấu `activationAttempted`, rồi `beginSession(gen)`. Nếu `false`: đóng phiên với `PROTOCOL_ERROR` "input session refused", như v1 (`ClientSession.java:411-413`), và **không** gửi `SESSION_ACTIVE`. Nếu thành công, đặt `inputBegun = true` ngay, trước mọi bước có thể đóng phiên.
   2. Công bố snapshot bounds của phiên này cho executor (mục 1.8), không gọi adapter hay I/O.
   3. `offer(SESSION_ACTIVE)` phải trả `ACCEPTED`. Nếu `OVERFLOW`: đóng với `OVERFLOW`; nếu `CLOSED`: đóng phiên nếu chưa đóng. Mọi đường lỗi sau `beginSession` đều phải đi qua `endSession` nhờ `inputBegun`.
   4. `advance(RESERVED, ACTIVE)`. Các callback bắt đầu `ScreenSender` chỉ chạy sau khi tx kết thúc, qua `port.startWorker`. Nếu phiên đã đóng vì lỗi hoặc race thì worker không chạy. Không khởi động sender/receiver trực tiếp trong hook.

   Nói cách khác, Host chỉ công bố `ACTIVE` sau khi `beginSession` đã thành công.
6. Viewer nhận `SESSION_ACTIVE`:
   - Nếu chưa có `SCREEN_INFO`: đóng phiên vì lỗi giao thức.
   - Nếu đã có `SCREEN_INFO`: ghi nhận `activationReceived`. Chỉ chuyển sang `ACTIVE` và bắt đầu `FrameReceiver` khi **đồng thời `activationReceived && imageChannelReady`**, phiên vẫn `ATTACHING` và còn hạn. Nhận `SESSION_ACTIVE` và bàn giao kênh ảnh cục bộ có thể theo thứ tự bất kỳ; cả hai đường đều thử kích hoạt, tối đa một lần. Không đóng phiên chỉ vì kênh ảnh cục bộ chưa sẵn sàng; giữ nguyên quy tắc `ClientSession.java:290-295,406-419` và test `ClientServerIntegrationTest.java:257-277,303-317`.
   - Gate chỉ mở khi frame hợp lệ đầu tiên đã được `RemotePanel` vẽ.

Thứ tự trên kênh lệnh:
- **Host → Viewer:** `AUTH_RESULT → (PING/PONG)* → SCREEN_INFO → (PING/PONG)* → SESSION_ACTIVE → …`
  - Heartbeat chạy từ lúc bàn giao, nên PING/PONG có thể đến trước `SCREEN_INFO` (khi chuẩn bị chậm hơn `pingInterval`) và trước `SESSION_ACTIVE` (khi ATTACH chậm).
  - `SCREEN_INFO` luôn đứng trước `SESSION_ACTIVE`, vì cả hai đi qua cùng một writer FIFO và `SESSION_ACTIVE` chỉ được `offer` khi việc chuẩn bị (gồm `offer(SCREEN_INFO)`) đã xong.
- **Viewer → Host:** `AUTH → (PING/PONG)* → input`. Input chỉ xuất hiện sau `SESSION_ACTIVE`.

### 1.4 Deadline (giữ giá trị trong `Timeouts.DEFAULTS`)

| Deadline | Bên | Mốc bắt đầu | Hết hạn thì |
| --- | --- | --- | --- |
| `connect` 3 s | Viewer, mỗi kênh | `connect()` | đóng phiên, báo "không kết nối được" |
| `handshake` 3 s | Host, mỗi kết nối | `accept()` | `claimForClose`, đóng socket. Bao cả đọc AUTH, ghi/flush `AUTH_RESULT` và CAS bàn giao; việc chuẩn bị adapter nằm ngoài khoảng này |
| `clientAuth` 5 s | Viewer | connect kênh lệnh xong | `closeIf(AUTHENTICATING, "auth timeout")` |
| `reserve` 5 s | Host | vào `RESERVED` | `closeIf(RESERVED, "attach timeout")`. Bao trùm cả việc chuẩn bị adapter lẫn ATTACH. Worker chuẩn bị chưa trả về thì vẫn giữ slot (mục 1.8) |
| `clientActivation` 7 s | Viewer | nhận `AUTH_RESULT(OK)` | `closeIf(ATTACHING, "activation timeout")` |
| `pingInterval` 2 s / `liveness` 8 s | cả hai | bàn giao xong | đóng phiên "heartbeat timeout" |
| `frameStall` 3 s | Viewer | `lastFrameAt` (khởi tạo lúc vào ACTIVE) | đóng gate, gửi `RELEASE_ALL` một lần |
| `frameTimeout` 15 s | Viewer | `lastFrameAt` | đóng phiên "frame timeout" |
| `cmdWriteLimit` 5 s / `imgWriteLimit` 10 s | bên ghi | `writeStartedAt` | đóng socket |

### 1.5 Hủy và mất kênh

| Sự kiện | Viewer | Host |
| --- | --- | --- |
| Viewer bấm Hủy lúc `CONNECTING`/`AUTHENTICATING` | đóng socket đã đăng ký, về `IDLE` | kết nối pending đóng do EOF hoặc deadline 3 s; không tính là nhập sai |
| Viewer bấm Hủy lúc `ATTACHING` | như trên | kênh lệnh EOF → `closeSession` → `LISTENING` |
| Viewer bấm Ngắt lúc `ACTIVE` | `DISCONNECT(USER)` rồi đóng | nhả hết phím/nút (`endSession`), về `LISTENING` |
| Host bấm Dừng | hiện lý do, về `IDLE` | `DISCONNECT(USER)`, nhả input, đóng |
| Mất kênh lệnh ở bất kỳ phase nào sau bàn giao | đóng phiên | đóng phiên, nhả input |
| Kênh ảnh không đến hoặc ATTACH sai | hết 7 s → đóng | hết 5 s → đóng. Token sai hoặc ATTACH lần hai: chỉ đóng kết nối ảnh đó (giữ quy tắc v1) |
| Mất kênh ảnh lúc `ACTIVE` | `FrameReceiver` gặp lỗi đọc → đóng phiên; dự phòng là stall 3 s rồi timeout 15 s | phát hiện qua lỗi ghi, watchdog ghi hoặc kênh lệnh đóng → đóng, nhả input |
| Không có kết nối lại | v2 không ghép lại kênh ảnh mới vào phiên cũ: mất một kênh là đóng cả phiên | |

Mọi chỗ ghi "về `LISTENING`" hoặc "nhả input" ở phía Host trong bảng trên đều theo điều kiện trả slot ở mục 1.8.

### 1.6 Socket và phiên bản

- `setTcpNoDelay(true)` ở **cả hai đầu của cả hai kênh**. Bây giờ Viewer là bên gửi input, nên socket Viewer tự mở cũng phải bật. Ở v1 chỉ có `ServerCore.java:226` bật.
- `setSendBufferSize(64 KiB)` chuyển sang socket ảnh của Host, tức socket do accept trả về (v1 đặt ở `ClientSession.java:284`).
- Peer v1 gặp peer v2:
  - Viewer v2 → Server v1: Server v1 trả `BAD_VERSION`.
  - Client v1 → Host v2: Host v2 trả `BAD_VERSION`.
  - Hai bên đều hiện "phiên bản khác nhau, hãy dùng cùng một bản jar".

### 1.7 Gói được phép theo kênh, chiều và phase (thay bảng 2.4)

- **Kênh lệnh, Host nhận:**
  - khi bắt tay: chỉ `AUTH`;
  - `RESERVED`: `PING`, `PONG`, `DISCONNECT`;
  - `ACTIVE`: `MOUSE_MOVE`, `MOUSE_PRESS`, `MOUSE_RELEASE`, `WHEEL`, `KEY_PRESS`, `KEY_RELEASE`, `RELEASE_ALL`, `PING`, `PONG`, `DISCONNECT`.
- **Kênh lệnh, Viewer nhận:**
  - `AUTHENTICATING`: chỉ `AUTH_RESULT`;
  - `ATTACHING`: `SCREEN_INFO` (đúng một lần, phải đến trước `SESSION_ACTIVE`), `SESSION_ACTIVE`, `PING`, `PONG`, `DISCONNECT`;
  - `ACTIVE`: `PING`, `PONG`, `DISCONNECT`. `SCREEN_INFO` lần hai là lỗi giao thức.
- **Kênh ảnh, Host nhận:** khi bắt tay chỉ `ATTACH`. Sau đó Host không đọc kênh này.
- **Kênh ảnh, Viewer nhận:** chỉ `FRAME`, và `FrameReceiver` chỉ chạy sau `ACTIVE`. Byte đến sớm nằm chờ trong buffer, không được decode.
- Mọi gói khác: lỗi giao thức, đóng phiên.

### 1.8 Giải phóng slot và quyền sở hữu `InputExecutor` ở Host (bổ sung theo review Phần 2)

Lý do: ở v1, slot của Server được trả ngay khi socket đóng (`SessionManager.onSessionSocketsClosed`, gọi từ `ServerCore.java:251`). Điều đó an toàn vì Server không chạy Robot. Phía chạy Robot (Client v1) chặn kết nối mới cho đến khi `terminated()` và `inputReleased()` xong (`AppRuntime.java:359-367`). Ở v2, Host chạy Robot nên phải giữ quy tắc chặt hơn này.

- **Sở hữu `InputExecutor`:** `AppRuntime.forHost` tạo **một** `InputExecutor` (cùng thiết bị input của `AdapterFactory.inputDevice()`) dùng cho cả đời ứng dụng (như `AppRuntime.java:140-157` đang làm cho Client), rồi truyền vào `HostCore`.
  - Mỗi phiên chỉ mượn executor qua `ControlledSide`: `beginSession(gen)` khi vào `ACTIVE`, `endSession(gen)` khi đóng.
  - Phiên không bao giờ tạo hay shutdown executor. Chỉ `AppRuntime` gọi `shutdownAsync()`, lúc thoát.
- **Adapter theo phiên (giữ như v1, `AppRuntime.java:381-384`):** `ScreenSource` và geometry **không** sống suốt ứng dụng.
  - Lý do: `RobotScreenSource` chốt geometry ngay lúc khởi tạo (`RobotScreenSource.java:15`). Nếu giữ source qua nhiều phiên, phiên sau có thể dùng độ phân giải hoặc DPI cũ.
  - Mỗi phiên làm theo thứ tự `adapters.refreshInputDevice()` rồi `adapters.newScreenSource()`.
  - Geometry của source mới chỉ được dùng cho đúng phiên đó: cho `SCREEN_INFO`, cho `ScreenSender`, và cho bounds mà executor đọc qua supplier (như `AppRuntime.java:635-638`).
- **Chuẩn bị adapter là một worker của phiên** (`rd-host-prepare`, khởi động qua `port.startWorker`, mục 2.3). Không chạy trên luồng handshake, vì hai lẽ:
  - Deadline handshake chỉ đóng được socket pending; nó không dừng được `refreshInputDevice()` hay `newScreenSource()` đang treo.
  - Chỉ worker do lifecycle quản lý mới được tính vào `terminated()`. Nhờ vậy, theo đúng điều kiện trả slot bên dưới, **slot không thể được trả khi việc chuẩn bị còn chạy**. Đây cũng là bất biến v1 đã kiểm ở `AppRuntimeClientTest.cancelBeforeSessionCreatedNeverStarts` (`AppRuntimeClientTest.java:265-293`, giải thích ở dòng 439-443).
  - Đây là **P2 được chọn**. P1 (giữ trên handshake rồi thêm bộ đếm/future riêng) không dùng, vì lặp lại cơ chế quản lý worker đã có. P2 đổi hành vi bắt tay chỉ từ B1; A1–B0 vẫn giữ luồng chuẩn bị v1 (mục 2.5).
  - Trước khi lên lịch worker, vỏ phải đã gắn close hook, future trả slot và graceful writer. Callback khởi động writer/reader phải đứng trước callback khởi động prepare. `startWorker` kiểm closed và giữ worker count trước khi tạo thread, như `SessionLifecycle.java:299-345`; nếu close thắng trước khi body chạy thì không gọi adapter.
- **Thứ tự trong worker:**
  1. Ngoài mọi khóa: `adapters.refreshInputDevice()`, rồi `adapters.newScreenSource()`.
  2. Mở tx mới, không giữ lại `SideTx` của `onAuthenticated`. Clock được đọc sau khi đã lấy khóa. Nếu phiên đã đóng hoặc không còn chờ kích hoạt thì bỏ kết quả; nếu hết reserve thì đóng `TIMEOUT` "attach timeout" và bỏ kết quả. Khi bỏ: không lưu geometry, không `offer(SCREEN_INFO)`, không chạm bounds, rồi trả về.
  3. Ngược lại: `offer(SCREEN_INFO)` và kiểm kết quả. **Chỉ `ACCEPTED` mới cho phép lưu source/geometry, đặt `prepared = true`, rồi gọi `port.tryActivate(stx)` trong cùng tx**. `OVERFLOW` đóng với `OVERFLOW`; `CLOSED` kết thúc phiên nếu chưa đóng; không đặt ready và không gọi `beginSession` ở cả hai trường hợp. `COALESCED` không hợp lệ cho `SCREEN_INFO`/`SESSION_ACTIVE` (writer chỉ gộp `MOUSE_MOVE`).
- **Không có trạng thái dùng chung nào bị worker cũ ghi đè:**
  - Worker chỉ ghi vào phiên của chính nó, trong tx, và chỉ khi phiên còn hợp lệ.
  - Bounds mà executor dùng chỉ được gán ở bước kích hoạt (1.3 bước 5.2), tức trong tx, trước khi công bố `ACTIVE`. Supplier của executor đọc snapshot geometry bất biến được công bố qua `volatile` hoặc cơ chế tương đương; không lấy ngược khóa session từ executor. Không còn trường `currentScreen` nào được việc chuẩn bị gán trực tiếp như v1.
- **Vì sao `refreshInputDevice()` chỉ chạy khi executor rỗi** (hợp đồng của `AdapterFactory`):
  - `tryReserve` chỉ thành công khi slot trống.
  - Slot chỉ trống sau khi phiên trước đã `terminated()` (gồm cả worker chuẩn bị của nó) và `endSession` đã xong.
  - Trong cùng một phiên, `beginSession` chỉ được gọi sau khi chuẩn bị xong (điều kiện kích hoạt).
- **Chuẩn bị adapter lỗi** (`AdapterException` hay `RuntimeException`):
  - Nhánh này chỉ bắt lỗi từ hai lời gọi adapter; lỗi sau đó trong tx/kích hoạt đi qua cơ chế lỗi lifecycle và cờ `inputBegun`, không được suy rằng input chưa begin.
  - Worker đóng phiên với `PROTOCOL_ERROR` "host screen unavailable", cùng lý do v1 dùng khi chụp màn hình lỗi (`ScreenSender.java:84`).
  - Không gọi `beginSession`, nên `inputReleased()` xong ngay. Slot được trả khi worker kết thúc.
  - Nếu phiên đã đóng, giữ nguyên lý do đóng đầu tiên; nếu reserve đã hết thì ưu tiên `TIMEOUT`, không thay bằng lỗi adapter đến muộn. Viewer nhận `DISCONNECT` khi kênh lệnh còn dùng được (`AUTH_RESULT(OK)` đã gửi trước đó); lỗi I/O đồng thời có thể chỉ cho thấy EOF/timeout. UI Host hiện lỗi adapter khi lỗi đó là nguyên nhân đóng.
- **Slot (`SessionManager.current`) chỉ được trả khi đủ cả ba điều kiện:**
  1. `socketsClosed()`;
  2. `terminated()`, tức mọi worker của phiên đã dừng (reader, writer, `rd-host-prepare`, `ScreenSender`);
  3. `inputReleased()` (future từ `onClosing()`, mục 2.3) hoàn tất bình thường.

  `inputReleased()` là **một future cố định được tạo từ constructor**, chưa hoàn tất khi phiên còn mở, như `ClientSession.java:66,100,446-455`. Close hook chỉ nối kết quả `endSession` vào future đó, không thay object. Manager có thể lấy future ngay sau reserve mà vẫn chờ đúng lần nhả về sau. `inputBegun` được đọc trong tx; nếu chưa begin thì chỉ hoàn tất future lúc close hook chạy; nếu đã begin thì phải chờ `endSession`, kể cả lỗi sau begin nhưng trước `ACTIVE`.

  Hàm `onSessionReleased(session)` thay cho `onSessionSocketsClosed`. Mã mới được cấp đúng lúc trả slot, như v1.
- **Đang dọn phiên** (socket đã đóng nhưng prepare/worker hoặc input chưa xong):
  - `tryReserve` trả `AUTH_RESULT(BUSY)` và không gọi `beginSession`.
  - UI Host hiện "Đang dọn phiên trước…"; nếu đang chờ input thì hiện "Đang nhả phím/chuột của phiên trước…" (tương ứng `WAITING_RELEASE` ở v1).
- **Nhả thất bại** (`inputReleased()` kết thúc exceptionally, executor ở `FAULTED`):
  - Slot **không** được trả, nên mọi AUTH sau đó đều nhận `BUSY`.
  - UI Host hiện `Messages.INPUT_RELEASE_FAILED` và yêu cầu khởi động lại ứng dụng (tương ứng `INPUT_FAULTED` ở v1).
  - Không thêm mã `AuthStatus` mới.
- **Không cưỡng bức nhả slot bằng timeout.** Nếu Robot hoặc adapter bỏ qua interrupt và treo, Host giữ `BUSY`; khi thoát vẫn có hạn chót shutdown của `AppRuntime` và phải báo `workersStopped=false` nếu worker chưa dừng (test v1: `AppRuntimeClientTest.java:518-530`). Không được báo đã dọn sạch hoặc tái sử dụng executor trong trường hợp này. Đây là giới hạn đã biết.
- `Viewer` không có ràng buộc này. Phía Viewer, input được nhả tại chỗ: `InputCapture` gửi `RELEASE_ALL` và xóa trạng thái phím/nút đang giữ.

## 2. Tách package và lớp theo vai (đã duyệt)

### 2.1 Hiện trạng

Code hiện chia theo hướng kết nối, nên mỗi lớp session gộp hai nửa:
- `server/ServerSession.java` (590 dòng) = nửa lắng nghe (reserve, token, ATTACH) + nửa điều khiển (`FrameMonitor`, `publishFrame`, gate/epoch, `offerInput`, `releaseControlInput`, frame observer).
- `client/ClientSession.java` (458 dòng) = nửa kết nối (connect, AUTH, ATTACH, cancel, deadline) + nửa bị điều khiển (dispatch input sang `InputExecutor`, `ScreenSender`, `inputReleased`).

Hai nửa này còn phụ thuộc trực tiếp vào lớp session, không chỉ chung trường dữ liệu:
- `ScreenSender` giữ `ClientSession` (`ScreenSender.java:28,37,64-65`: lifecycle, `announcedGeometry()`).
- `FrameReceiver` giữ `ServerSession` (`FrameReceiver.java:26,33,53-54,68`: `publishFrame`, `generation()`, lifecycle).

A1/A2 phải tháo các phụ thuộc này.

### 2.2 Bảng lớp cũ → đích

Mỗi lớp chỉ chuyển package **đúng một lần**. Test của lớp nào chuyển cùng bước với lớp đó.

| Lớp hiện tại | Đích | Bước |
| --- | --- | --- |
| *(mới)* `ControlSide`, `SessionPort`, `SessionSide`, `SideTx` | `viewer.ControlSide`, `common.SessionPort`, `common.SessionSide`, `common.SideTx` | tạo ở A1 (`server`, `common`), `ControlSide` chuyển ở A4 |
| *(mới)* `ControlledSide` | `host.ControlledSide` | tạo ở A2 (`client`), chuyển ở A4 |
| *(mới)* `PacketRules` | `common.PacketRules` | A3 |
| `server.FrameReceiver`, `FrameDecoder`, `FrameMonitor`, `ControlView`, `InputOutcome` | `viewer.*` (giữ tên) | A4 |
| `client.ScreenSender`, `FrameEncoder`, `CommandReceiver`, `InputExecutor`, `InputCommand`, `SubmitResult`, `InputDevice`, `RobotInputDevice`, `ScreenSource`, `RobotScreenSource`, `ScreenGeometry`, `DisplayPort`, `AwtDisplayPort`, `RobotPort`, `AwtRobotPort`, `RobotPortFactory`, `AdapterFactory`, `AdapterException`, `RobotAdapterFactory` | `host.*` (giữ tên) | A4 |
| `server.ServerCore`, `ServerConfig`, `ServerPhase`, `ServerSession` | `host.HostCore`, `HostConfig`, `HostPhase`, `HostSession` | B2 |
| `server.SessionManager`, `AuthGuard`, `HandshakeHandler`, `PendingConnection`, `PendingConnections`, `ReserveResult` | `host.*` (giữ tên) | B2 |
| `client.ClientSession`, `ClientConfig`, `ClientPhase`, `ClientHooks` | `viewer.ViewerSession`, `ViewerConfig`, `ViewerPhase`, `ViewerHooks` | B2 |
| `client.SocketConnector` | `viewer.SocketConnector` | B2 |
| `common.*` (còn lại) | giữ nguyên; `Protocol.VERSION = 2` ở B1 | — |
| `ui.*` | chỉ sửa import ở mỗi bước; đổi tên lớp UI ở Phần 3 | — |

`SubmitResult` là kết quả của `InputExecutor.submit()` (`InputExecutor.java:89`), nên thuộc `host`.

Sau A4, vỏ v1 tạm thời trông lệch tên: `server.ServerSession` dùng `viewer.ControlSide`, còn `client.ClientSession` dùng `host.ControlledSide`. Điều này chấp nhận được, vì package lúc đó gọi theo **nửa vai trò** và B1 sẽ lật vỏ cho khớp.

**Phụ thuộc ngược tạm thời:** `viewer.ControlView` giữ trường `server.ServerPhase phase` cho đến B1 (B1 đổi kiểu này). Vì vậy A4 **không** loại bỏ mọi phụ thuộc ngược `viewer → server` và không được báo như vậy; khoản nợ này phải được ghi trong commit body A4 và reviewer liệt kê mọi import ngược còn lại.

### 2.3 Hợp đồng giữa vỏ và nửa vai trò

**`SessionPort`** là thứ vỏ cấp cho nửa vai trò. Nửa vai trò không thấy socket và không thấy lớp vỏ. Port bọc `SessionLifecycle`, `CommandWriter` và `Ticker` hiện có:
- `atomically(tx -> …)`, `generation()`, `close(CloseInfo)`;
- `offer(Packet)` → `CommandWriter.OfferResult` (không chặn);
- `register(Tickable)` → `Registration`, `addStopHook(Runnable)`;
- `startWorker(name, Runnable) → boolean`: dùng đúng cơ chế đếm/skipping của lifecycle, worker được tính vào `terminated()`;
- `tryActivate(stx)`: chạy `maybeActivate(tx)` của vỏ trong **chính tx đang mở**, không tự mở tx lồng nhau và không nhận timestamp do caller giữ từ trước. Port kiểm (1) `stx` là view do **chính port này** tạo (khác → `IllegalArgumentException`); (2) **ép kiểm lifetime của tx gốc** (hết hạn/sai thread → `IllegalStateException`) trước khi giao cho Shell, kể cả khi Shell không đụng tx. Vỏ đọc clock trong tx, kiểm closed/phase/deadline, rồi mới kiểm đủ điều kiện và kích hoạt tối đa một lần.
- **Nguồn gốc `SideTx`:** chỉ `LifecyclePort.atomically` tạo `SideTx`, từ lifecycle của chính port; **không có `wrap(Tx)` hay API public nào** dựng `SideTx` từ `SessionLifecycle.Tx` có sẵn (nếu có, `portA.wrap(txB)` gắn nhãn A cho tx của B mà không có cách kiểm). Vỏ đang giữ tx gốc mà cần gọi hook hoặc `Heartbeat.answerPing` thì mở `port.atomically(stx -> …)` lồng: khóa re-entrant, và hàng `after` chỉ chạy khi tx ngoài cùng nhả khóa.

**`SideTx`** là giao diện trung tính mà vỏ dựng từ `SessionLifecycle.Tx<Phase>` của nó. Nửa vai trò chỉ thấy `SideTx`, không thấy `Tx<ClientPhase>` hay `Tx<ServerPhase>`:
- `isClosed()`;
- `isActive()` (vỏ tự quy đổi từ phase của mình);
- `isActivationPending()` (chỉ xét phase chờ kích hoạt: `RESERVED` ở vỏ lắng nghe, `ATTACHING` ở vỏ kết nối);
- `activationExpired()` (vỏ đọc clock và deadline của chính nó trong tx; tại `now == deadline` đã hết hạn; chỉ gọi khi đang chờ kích hoạt);
- `close(CloseInfo)`, giữ nguyên lý do và thông điệp mà nửa vai trò đưa vào;
- `after(Runnable)`.

`SideTx` chỉ hợp lệ trong callback `atomically`/hook hiện tại, không được lưu trong field hay đưa vào worker. Worker luôn mở `port.atomically` mới. Port phải kiểm `SideTx` thuộc đúng port và tx gốc còn hợp lệ khi nhận `tryActivate` (xem trên; test `tryActivateRejectsSideTxOfAnotherPort`, `tryActivateWithExpiredSideTxThrows`); nửa vai trò không tự nhìn thấy enum phase hay thay deadline.

**`SessionSide`** gồm 4 hook và 1 truy vấn do vỏ gọi:

| Hook | Ai gọi, khi nào | Khóa | Host (`ControlledSide`) | Viewer (`ControlSide`) |
| --- | --- | --- | --- | --- |
| `onAuthenticated(stx)` | vỏ, ngay sau khi bàn giao kênh lệnh | trong tx | `stx.after(() -> port.startWorker("rd-host-prepare", prepare))`. Worker làm theo 1.8 rồi gọi `port.tryActivate` | không làm gì |
| `readyForActive()` | `maybeActivate` của vỏ | trong tx | `true` khi chuẩn bị xong và writer đã nhận `SCREEN_INFO` (`prepared` ở 1.8) | `true` khi đã nhận `SCREEN_INFO`. Vỏ Viewer nhận `SESSION_ACTIVE` mà side chưa sẵn sàng thì đóng với `PROTOCOL_ERROR` |
| `onPacket(stx, p)` | reader kênh lệnh, sau khi `PacketRules` cho qua loại gói | trong tx | input → `CommandReceiver.handle(stx, p)` → `InputExecutor.submit(gen, cmd)`. Kết quả và lý do đóng **giữ đúng v1** (`CommandReceiver.java:47-67`): chưa ACTIVE → `PROTOCOL_ERROR`; lệnh sai → `PROTOCOL_ERROR`; `STALE` → `PROTOCOL_ERROR` "stale input session"; `OVERFLOW` → `OVERFLOW` "input queue overflow" | `SCREEN_INFO`: kiểm "chưa nhận lần nào" và `Validation`, rồi lưu kích thước; sai → `PROTOCOL_ERROR` |
| `onActive(stx, now, img) → boolean` | vỏ, đúng một lần khi đủ điều kiện ACTIVE. Vỏ Host chỉ `offer(SESSION_ACTIVE)` và `advance` **sau khi** hook trả `true` (1.3 bước 5) | trong tx | `beginSession(gen)`; nếu `false` thì trả `false` và vỏ đóng phiên với "input session refused"; gán bounds của phiên cho executor; `stx.after(start ScreenSender ghi img.out())` | `monitor.reset(now)`; `stx.after(start FrameReceiver đọc img.in())` |
| `onClosing() → CompletableFuture<Void>` | lifecycle, đúng một lần khi phiên bắt đầu đóng | ngoài khóa | `endSession(gen)` nếu đã begin, nếu chưa thì trả future đã xong | future đã xong (`InputCapture` tự nhả ở EDT) |

Vỏ nối kết quả future của `onClosing()` vào **future `inputReleased()` cố định** ở mục 1.8 (`SessionSide.bridgeClosing`), không thay tham chiếu và không trả một future đã xong trước khi close hook chạy. Seam kiểm thử `inputReleaseSeam` (B0) chỉ biến đổi **stage nguồn** (kết quả `onClosing()`) trước khi vào bridge; nó không thay thế hay hoàn tất target ổn định đó. `SessionManager` dùng `allOf(socketsClosed, terminated, inputReleased)` và chỉ trả slot nếu cả ba hoàn tất bình thường, phiên vẫn là `current` (so sánh identity/generation). Close hook phải chuyển cả lỗi đồng bộ lẫn lỗi future của `endSession` thành hoàn tất exceptionally; không được nuốt lỗi rồi báo nhả thành công.

Hook `onActive` ở Host chỉ đặt `inputBegun`, công bố bounds và đăng ký callback qua `stx.after`; việc `offer(SESSION_ACTIVE)`/`advance` vẫn do vỏ làm trong cùng tx. Callback phải dùng `port.startWorker`, nên nếu `offer` lỗi hoặc close thắng thì không chạy sender. `readyForActive()` chỉ đọc trạng thái đã được bảo vệ bởi tx, không I/O hay gọi adapter. Vỏ Viewer còn phải giữ đủ hai cờ `activationReceived && imageChannelReady` trước khi gọi hook; `readyForActive()` của `ControlSide` không thay thế hai cờ thuộc vỏ này.

**Khác biệt chuyển tiếp bắt buộc:** bảng hook trên mô tả v2 ở B1. Trong A1–B0 (wire v1), `ControlledSide` vẫn nhận source đã chuẩn bị bởi `AppRuntime` trước connect; `onAuthenticated` vẫn xếp `SCREEN_INFO` trước heartbeat như code hiện tại, không tạo `rd-host-prepare` và không refresh adapter lần hai. `ControlSide` của v1 giữ cách nhận `SCREEN_INFO`/frame hiện tại. Chỉ B1 chuyển sang chuẩn bị sau AUTH, cho phép PING đứng trước `SCREEN_INFO` và áp dụng cặp prepared + ATTACH; phải thể hiện thay đổi đó trong trace v2.

Input của Viewer đi từ UI: EDT gọi `ControlSide.offerInput(epoch, p)`, hàm này tự mở tx qua port. Kiểm gate/epoch và `offer` nằm trong **cùng một** tx, như `ServerSession.offerInput` hiện tại (`ServerSession.java:417`).

**Quy tắc bắt buộc:**
- **Trong tx chỉ được:**
  - đổi trạng thái;
  - gọi `offer`/`submit` không chặn;
  - gọi `beginSession`. Đây là khóa ngắn của executor; thứ tự khóa luôn là session → executor, như `ClientSession.tryStart` đang làm (`ClientSession.java:406-421`).

  **Trong tx cấm:** I/O, chờ future, start thread trực tiếp (phải dùng `tx.after`).
- **Lỗi:**
  - Nửa vai trò không bao giờ ném exception qua hook.
  - Nửa vai trò tự đóng phiên bằng `stx.close(CloseInfo)`, với **đúng lý do v1** (ví dụ `OVERFLOW` khác `PROTOCOL_ERROR`). Vỏ không được đổi lý do này.
  - Muốn đổi cách xử lý một kết quả (ví dụ bỏ qua `STALE` thay vì đóng phiên) thì làm thành một thay đổi hành vi riêng, ngoài đợt này.
  - Worker gặp lỗi I/O gọi `port.close(CloseInfo(…))`.
- **Kênh ảnh:**
  - Vỏ giữ socket và truyền `Channel img` vào `onActive`.
  - Worker chỉ dùng một chiều: Host chỉ ghi, Viewer chỉ đọc.
  - Đóng socket là việc của lifecycle.
- **Tháo phụ thuộc:**
  - `ScreenSender` nhận `SessionPort` + `ScreenGeometry` thay cho `ClientSession`.
  - `FrameReceiver` nhận `SessionPort` + `ControlSide` (đích của `publishFrame`) thay cho `ServerSession`.
  - `CommandReceiver.handle` đang nhận `SessionLifecycle.Tx<ClientPhase>` và tự kiểm `tx.phase() != ClientPhase.ACTIVE` (`CommandReceiver.java:39,47-48`). A2 đổi thành `handle(SideTx, Packet)` và kiểm qua `stx.isActive()`; mọi lý do đóng giữ nguyên.
  - Nhánh `PING` của `CommandReceiver` (`CommandReceiver.java:40-45`) chuyển lên vỏ `ClientSession` ở A2: vẫn trả `PONG` và vẫn đóng `OVERFLOW` khi hàng đợi đầy. Lý do: ở v2, PING/PONG là việc của vỏ (heartbeat) ở cả hai phía, còn `ControlledSide` chỉ nhận gói input.

### 2.4 Phạm vi của `PacketRules`

- Bảng chỉ lọc **loại gói** theo (phía, kênh, phase), thay cho các tập rải rác như `ServerSession.COMMAND_IN` (`ServerSession.java:63`) và các `case` theo phase trong hai `dispatch`.
- Mọi kiểm tra khác vẫn nằm trong tx ở vỏ hoặc nửa vai trò, **không** đưa vào bảng:
  - gói chỉ được nhận một lần (`SCREEN_INFO`, `SESSION_ACTIVE`, `ATTACH`);
  - token đúng và chỉ dùng một lần;
  - giá trị payload (`Validation`: kích thước màn hình, tọa độ, nút, phím);
  - generation/epoch;
  - phiên đã đóng.
- Độ dài payload theo loại gói vẫn do `PacketIO.read` kiểm (min/max của `PacketType`).
- Ví dụ: hai `SCREEN_INFO` liền nhau ở `ATTACHING` (v2), hoặc ở `RESERVED` (v1, bước A3), đều qua được bảng, nhưng gói thứ hai phải bị cờ "đã nhận" chặn và phiên đóng vì lỗi giao thức. Có test cho ca này.

### 2.5 Trình tự commit (refactor trước, đổi vai sau)

Mỗi commit đều phải xanh. Model và effort ghi theo quy ước worker.

0. **A0** — thêm JaCoCo và chốt chặn dữ liệu theo mục 3.7, rồi nâng độ phủ nhánh của các lớp mục tiêu hiện có lên ngưỡng.
   - Baseline đo tại `8321095` bằng JaCoCo 0.8.16 (561 test xanh):

     | Lớp | Nhánh phủ | Tỷ lệ | Cần thêm để đạt 0.80 |
     | --- | --- | --- | --- |
     | `ServerSession` | 133–134 / 172 | 77,3–77,9% | 4–5 nhánh |
     | `ClientSession` | 66 / 86 | 76,7% | 3 nhánh |
     | `HandshakeHandler` | 18 / 24 | 75,0% | 2 nhánh |

     `ServerSession` lệch 1 nhánh giữa hai lần chạy: coordinator được 133, reviewer được 134. Chênh lệch phù hợp với lịch thực thi khác nhau; hai số tổng chưa xác định được nhánh cụ thể. A0 cần đối chiếu báo cáo theo method/line và dùng seam/latch cho các nhánh bổ sung, không coi ba lượt xanh là bằng chứng mọi race đã hết.
   - Chỉ thêm test, **không sửa code chính**, cho đến khi đạt. Nếu thiếu hơn 10 điểm phần trăm thì dừng lại, báo coordinator để quyết. Hiện cả ba đều thiếu dưới 6 điểm.
   - **Cổng không được dựa vào dữ liệu tích lũy:** nghiệm thu A0 bằng **3 lượt `clean verify` độc lập liên tiếp**, lưu riêng counter/report của từng lượt. Mỗi lượt phải có `C >= ceil(0.80 × T) + 2`, với `C` là số nhánh phủ, `T` là tổng nhánh của chính lớp trong lượt đó. Đây là biên nghiệm thu A0; rule build thường xuyên vẫn là 0.80 từng lớp. Nhánh mới phủ phải đi qua seam/latch có tính tất định, không trông vào may rủi của race. Không merge execution data giữa các lượt.
   - Với số nhánh hiện tại, mục tiêu A0 có biên là: `ServerSession >= 140/172` (thêm 6–7 nhánh), `ClientSession >= 71/86` (thêm 5), `HandshakeHandler >= 22/24` (thêm 4): **15–16 nhánh**, không phải khoảng 10. Đây không phải dự báo số test; tổng nhánh production không đổi chỉ vì thêm test. Nếu tổng nhánh đổi thì giải thích nguyên nhân (toolchain/bytecode/cấu hình đo) và tính lại theo công thức trên.
   - Thêm luôn các golden trace ở 3.2. Lưới an toàn này phải có trước khi refactor.

   *(sonnet, xhigh: cần phủ nhánh race bằng seam tất định và dựng golden trace)*
1. **C0** — sửa **thời điểm đọc clock của quyết định kích hoạt** trước mọi refactor (thay đổi hành vi có chủ đích, tách riêng khỏi A1).
   - Chỉ đổi `ServerSession.maybeActivate` (private) thành `maybeActivate(SessionLifecycle.Tx<ServerPhase> tx)`: đọc `env.clock()` bên trong tx, dùng cùng giá trị cho reserve check và `onActive`; mọi callsite (SCREEN_INFO, attach kênh lệnh, attach kênh ảnh) dùng nó. Không đổi timestamp heartbeat, `tick`, ý nghĩa đóng timeout, wire hay lý do đóng.
   - Lý do: v1 đọc `now` trước khi lấy khóa phiên (`ServerSession.java:244`), nên thread đợi khóa có thể kích hoạt sau `reservedAt + reserve` trong khi fake clock đã tiến.
   - Test mới trong `ServerSessionProtocolEdgesTest`: `screenInfoWaitingForSessionLockCannotActivateAfterReserveDeadline` (phải **đỏ trên baseline trước khi sửa**) cùng các ca biên còn hạn / đúng deadline (`now == deadline` là hết hạn) cho các đường gọi. Golden trace literal và assertion không đổi.
   - Sau C0, A1–A4 là refactor thuần so với baseline (SHA) của C0; A1 không đổi chính sách thời gian này. *(sonnet, xhigh; review xhigh)*
2. **A1** — tạo `SessionPort`/`SessionSide`/`SideTx`; tách `ControlSide` khỏi `ServerSession` (vẫn trong `server`); `FrameReceiver` chuyển sang phụ thuộc port + `ControlSide`. Không đổi wire hay hành vi so với baseline C0. `Shell.tryActivate` của `ServerSession` gọi `maybeActivate(tx)` của C0. *(sonnet, xhigh; review xhigh)*
3. **A2** — tách `ControlledSide` khỏi `ClientSession` (vẫn trong `client`).
   - `ScreenSender` chuyển sang phụ thuộc port + `ScreenGeometry`.
   - `CommandReceiver.handle` chuyển sang nhận `SideTx`; nhánh `PING` lên vỏ (mục 2.3) và `Heartbeat.answerPing` được gọi qua `port.atomically`. Hai test PING chuyển sang `HeartbeatTest` giữ nguyên input/assertion, kèm bảng truy vết tên cũ → mới trong commit body.
   - `inputReleased` giữ là future final tạo trong constructor (target ổn định); `SessionSide.bridgeClosing` không bao giờ thay hay hoàn tất sớm nó.
   - Không đổi wire, hành vi hay lý do đóng.
   - Vẫn dùng source chuẩn bị trước connect của v1; chưa đưa `rd-host-prepare` vào luồng chạy. A0 chỉ đổi cấu hình build/thêm test; A1–B0 không được lén áp dụng bắt tay v2 (chuẩn bị adapter sau AUTH chỉ thuộc B1).

   *(sonnet, xhigh; review xhigh)*
4. **A3** — thêm `PacketRules` (mục 2.4) theo luật v1. *(sonnet, high; review high)*
5. **A4** — chuyển hai nửa vai trò sang `viewer`/`host` theo bảng 2.2, không đổi logic. API ở ranh giới chuyển thành `public`. `viewer.ControlView` giữ phụ thuộc tạm vào `server.ServerPhase` đến B1 (mục 2.2). *(sonnet, high; review medium)*
6. **B0** — `SessionManager` trả slot theo mục 1.8: chờ đủ `socketsClosed` + `terminated` + `inputReleased`.
   - Wire vẫn v1. Ở v1, `ControlSide.onClosing()` trả future đã xong, nên thay đổi hành vi duy nhất là slot còn chờ thêm worker dừng hẳn.
   - Có test với `SessionSide` giả giữ future bằng latch. Seam `inputReleaseSeam` biến đổi stage đóng nguồn trước bridge; target `inputReleased` cố định tạo từ constructor không bị thay hay hoàn tất sớm.

   *(sonnet, xhigh; review xhigh)*
7. **B1** — đổi vai trong **một commit nguyên khối**:
   - vỏ lắng nghe dùng `host.ControlledSide`, vỏ kết nối dùng `viewer.ControlSide`;
   - `PacketRules` lật sang mục 1.7; bắt tay theo 1.3; socket theo 1.6; `VERSION = 2`;
   - `AppRuntime.forHost` sở hữu `InputExecutor` theo 1.8.
   - Chính bước này chuyển chuẩn bị adapter sang `rd-host-prepare`, áp dụng hai điều kiện kích hoạt và các hợp đồng future/offer/deadline ở 1.3, 1.8, 2.3. Đưa test seam `beforeSenderStart` sang phía Host; hook `afterAttachFlushed` ở phía Viewer, không chuyển cả hai theo tên `ClientHooks` cũ.

   Không chia nhỏ được, vì chỉ đổi một phía thì test tích hợp hai phía sẽ đỏ. **Điều kiện thiết kế đã đạt ngày 2026-11-02:** người dùng duyệt Phần 3 (UI/CLI) và Phần 4 (tài liệu/H2) để tiếp tục B1/B2. Lập plan riêng trước source; B1 là một commit hành vi nguyên khối, B2 đổi tên/package cơ học. Định tuyến hiện hành: Codex Sol xhigh cho B1 và review, Sol high cho B2; review cuối Astra theo quy tắc model và quota ở đầu spec.
8. **B2** — chuyển các lớp vỏ sang `host`/`viewer` và đổi tên theo bảng 2.2, không đổi logic. *(sonnet, high; review medium)*

## 3. Kiểm thử chặn lỗi (đã duyệt cùng Phần 2)

### 3.1 Luật chặn, áp dụng mọi task

- Một test đỏ, kể cả flaky, chặn task kế tiếp. Flaky được coi là bug; không chạy lại cho đến khi xanh.
- Worker báo tên test đã đỏ trước khi sửa (TDD), rồi `./mvnw.cmd -q verify` xanh. Coordinator tự chạy lại verify độc lập.
- Số test không giảm, trừ khi có dòng giải thích trong bảng truy vết (3.4). Không `@Disabled`. Test mới không dùng `Thread.sleep` (hiện `src/test`, không tính spike, có 0 chỗ).

### 3.2 Chụp hành vi trước refactor (golden trace, cho A1–B0)

- Trước A1, thêm test dùng `it/RawPeer` ghi chuỗi gói trên wire (loại gói, chiều, phase), **ghi riêng cho từng kênh** vì thứ tự giữa hai kênh không cố định.
- Có 1 luồng thành công và 6 luồng lỗi: sai mã, khóa IP, BUSY, token sai, hết reserve, ngắt giữa ACTIVE.
- **Xử lý PING/PONG:** heartbeat chạy từ lúc bàn giao, nên nếu ATTACH chậm hơn `pingInterval` thì PING có thể đứng trước `SESSION_ACTIVE`. Vì vậy:
  - Trace chính chạy bằng fake clock và `TestTimeouts` có `pingInterval` dài hơn thời gian của ca, nên không có PING/PONG.
  - Thêm một ca riêng "ATTACH chậm hơn `pingInterval`": PING/PONG được phép xen ở bất kỳ đâu **sau** bàn giao (kể cả trước `SESSION_ACTIVE`) nhưng không bao giờ trước `AUTH_RESULT`. Bỏ PING/PONG ra thì các gói còn lại phải đúng thứ tự của trace chính.
- C0–B0 phải giữ các chuỗi này y hệt (literal và assertion không đổi, kể cả sau C0). C0 chỉ **thêm** test vào `ServerSessionProtocolEdgesTest` (race `screenInfoWaitingForSessionLockCannotActivateAfterReserveDeadline` phải đỏ trước fix, cùng các ca biên deadline); không sửa test cũ nào.
- **A1–A4: quy tắc sửa test cũ.**
  - Được phép (chỉ là sửa cơ học):
    - đổi `package`/`import` và tên tham chiếu khi chuyển lớp;
    - sửa chỗ gọi constructor/method ở đúng ranh giới mà bước đó tách ra, ví dụ `FrameReceiver(ServerSession, …)` thành `FrameReceiver(SessionPort, ControlSide, …)`, hay `CommandReceiver.handle(Tx<ClientPhase>, …)` thành `handle(SideTx, …)`.
  - **Không** được đổi hay xóa assertion, giá trị kỳ vọng, input của test, timeout, hay lý do đóng mà test chờ.
  - Reviewer đọc diff thư mục test theo đúng quy tắc này. Cần sửa nhiều hơn thì commit đó không còn là refactor thuần, phải dừng lại để hỏi.
- B0 chỉ được sửa test cũ ở đúng chỗ test giả định slot được trả ngay khi socket đóng. Phải chuyển sang chờ trả slot, và liệt kê từng test trong commit body.
- Ở B1, phần khác biệt giữa các chuỗi chính là đặc tả v2 (mục 1.3). Reviewer so từng dòng.

### 3.3 Kỳ vọng theo từng tình huống

Mỗi ca kiểm **trạng thái riêng của ca đó**. Việc dọn dẹp chung để ở 3.5.

| Ca | Host phải | Viewer phải |
| --- | --- | --- |
| Sai mã | vẫn `LISTENING`; mã **không đổi**; `AuthGuard` +1; chỉ đóng kết nối đó (sau 1 s) | `IDLE`, lý do `WRONG_CODE` |
| Sai lần thứ 3, rồi thử tiếp | lần sau nhận `LOCKED` ngay; hết 30 s (fake clock) thì được thử lại | `IDLE`, lý do `LOCKED` |
| `BUSY` (đang có phiên) | phiên hiện tại giữ nguyên phase, generation, mã | Viewer mới: `IDLE`, lý do `BUSY` |
| `BAD_VERSION` (peer v1) | vẫn `LISTENING`; `AuthGuard` giữ như hành vi v1 (`SessionManager.java:76` trả trước khi kiểm mã) | `IDLE`, báo khác phiên bản |
| Token sai hoặc ATTACH lần hai | chỉ đóng kết nối ảnh đó; phiên giữ phase (`RESERVED` vẫn chờ, `ACTIVE` vẫn chạy); không gửi `DISCONNECT` trên kênh lệnh | không bị ảnh hưởng (Viewer giả gửi ATTACH thứ hai) |
| Hai `SCREEN_INFO` (Host giả gửi) | — | đóng vì `PROTOCOL_ERROR` |
| `SCREEN_INFO` dính liền `AUTH_RESULT` trong một lần ghi TCP | — | không mất `SCREEN_INFO`; vào `ACTIVE` bình thường |
| Viewer hủy khi chưa gửi AUTH (seam giữ trước ghi AUTH; Host đọc EOF mà chưa có AUTH hợp lệ) | không reserve; vẫn `LISTENING`, mã không đổi | `IDLE` |
| Viewer hủy sau khi Host reserve thành công nhưng trước khi Viewer thấy `AUTH_RESULT(OK)` | đóng phiên đã reserve, trả slot theo 1.8, mã đổi khi trả slot; không hứa mã giữ nguyên chỉ vì Viewer chưa đọc reply | `IDLE`; callback muộn không mở lại phiên |
| Viewer hủy sau `AUTH_RESULT(OK)` | trả slot theo 1.8, mã đổi | `IDLE` |
| Hết từng deadline ở 1.4 | đúng phase và đúng lý do ở bảng 1.4 | như cột Host |
| Viewer ngắt, Host dừng, mất kênh lệnh hoặc ảnh ở `ACTIVE` | đóng, nhả input, trả slot theo 1.8 | đóng, hiện lý do |
| Viewer thứ hai gửi AUTH lúc `endSession()` còn chạy (`InputDevice` giả bị latch giữ) | trả `BUSY`, không gọi `beginSession`; mở latch thì slot được trả, mã đổi, Viewer thứ ba vào được | Viewer thứ hai: `BUSY` |
| Nhả input thất bại | slot không được trả; mọi AUTH sau đó nhận `BUSY`; UI báo `INPUT_RELEASE_FAILED` | `BUSY` |
| Stall rồi hồi phục | — | gate đóng và `RELEASE_ALL` gửi một lần; gate chỉ mở lại sau khi frame mới đã được vẽ (`960bf81`) |
| Đổi geometry giữa hai phiên (`AdapterFactory` giả: phiên 1 trả 1920×1080, phiên 2 trả 1280×720) | `refreshInputDevice()` và `newScreenSource()` mỗi hàm được gọi đúng một lần cho mỗi phiên, trên `rd-host-prepare`, khi executor rỗi; tọa độ input của phiên 2 được kẹp theo 1280×720 | phiên 2 nhận `SCREEN_INFO(1280, 720)` |
| Chuẩn bị adapter lỗi | đóng với `PROTOCOL_ERROR` "host screen unavailable"; không gọi `beginSession`; slot được trả khi worker kết thúc; UI báo lỗi adapter | nhận `DISCONNECT(PROTOCOL_ERROR)`, về `IDLE`, hiện thông điệp |
| Chuẩn bị bị giữ quá `reserve` (`FakeAdapterFactory.blockNewScreenSource(true)` giữ latch và bỏ qua interrupt; fake clock vượt 5 s) | phiên đóng "attach timeout". Trong lúc latch còn giữ: AUTH mới nhận `BUSY` và slot không được trả. Mở latch: worker thấy phiên đã đóng nên bỏ kết quả (không `SCREEN_INFO`, không gán bounds, không `beginSession`), rồi slot mới được trả và mã đổi. Viewer kế tiếp dùng geometry từ lần chuẩn bị của chính nó | Viewer đầu: hết 7 s hoặc nhận `DISCONNECT(TIMEOUT)`. Viewer thứ hai: `BUSY` |
| ATTACH đến trước khi chuẩn bị xong | `RESERVED` giữ nguyên; chuẩn bị xong thì kích hoạt ngay (thứ tự bất kỳ) | nhận `SCREEN_INFO` rồi `SESSION_ACTIVE` |
| `SESSION_ACTIVE` đến khi hook Viewer `afterAttachFlushed` còn bị latch giữ | phiên Host đã có thể ACTIVE | vẫn `ATTACHING`, không receiver; mở latch thì bàn giao ảnh và kích hoạt đúng một lần; nếu hủy/hết deadline trước khi mở latch thì không kích hoạt |
| Chuẩn bị chậm hơn `pingInterval` | gửi PING trước `SCREEN_INFO` | chấp nhận PING ở `ATTACHING` và vào `ACTIVE` bình thường |
| `beginSession` trả `false` | đóng "input session refused"; **không** gửi `SESSION_ACTIVE` | `DISCONNECT(PROTOCOL_ERROR)`, không vào `ACTIVE` |
| Input `STALE` / hàng đợi input đầy | đóng với đúng lý do v1: `PROTOCOL_ERROR` / `OVERFLOW` | thấy `DISCONNECT` với đúng lý do đó |
| Prepare và ATTACH cùng hoàn tất (barrier, ép cả hai thứ tự) | `beginSession`, `SESSION_ACTIVE` và sender mỗi thứ đúng một lần; `SCREEN_INFO` luôn trước `SESSION_ACTIVE` | vào `ACTIVE` đúng một lần |
| Worker trả về đúng hạn `reservedAt + reserve`, ticker chưa chạy | đọc clock trong tx, đóng `TIMEOUT`; không publish source/bounds, không xếp `SCREEN_INFO` hay `SESSION_ACTIVE` | không vào `ACTIVE` |
| Writer trả `OVERFLOW` khi xếp `SCREEN_INFO` | đóng `OVERFLOW`; `prepared` vẫn false; không begin, không sender | không nhận `SESSION_ACTIVE` |
| Writer trả `OVERFLOW` khi xếp `SESSION_ACTIVE`, sau begin | đã đặt `inputBegun`; close hook gọi `endSession` đúng một lần. Giữ future nhả bằng latch thì AUTH mới vẫn BUSY | không vào `ACTIVE` |
| Lấy `inputReleased()` trước close rồi giữ `endSession` bằng latch | vẫn cùng object future sau close; future cũ chưa xong, slot chưa trả; mở latch mới cho trả slot | AUTH trong lúc chờ nhận BUSY |
| Close thắng trước khi worker prepare bắt đầu | body bị skip; không gọi adapter, không sender; các future dọn phiên hoàn tất và không rò worker | đóng phiên |
| Host shutdown khi prepare bỏ qua interrupt | shutdown trả trong hạn, báo `workersStopped=false`, không báo dọn sạch; mở latch thì worker bỏ kết quả | đóng phiên |

Các ca giữ adapter qua close phải dùng gate **không ngắt được** (`true`): gate mặc định ném `AdapterException` khi bị interrupt (`FakeAdapterFactory.java:58-65,92-103`), nên không chứng minh được BUSY khi adapter còn chạy. Luôn mở latch trong `finally`/cleanup trước khi đòi `rd-*` bằng 0. Ca lỗi adapter thông thường kiểm DISCONNECT khi writer/kênh còn dùng được; ca đứt mạng đồng thời kiểm close reason cục bộ và dọn tài nguyên, không hứa chuyển được gói cuối trên socket hỏng.

### 3.4 Đổi vai (B1)

- **Bảng truy vết** `docs/superpowers/reviews/<ngày>-v2-test-map.md`: mỗi test cũ chạm `ServerSession`, `ClientSession` hoặc `it/*` được ánh xạ sang test mới, hoặc ghi lý do xóa.
- **Ma trận gói × phase × phía** (test tham số hóa, sinh từ mục 1.7): mỗi ô cho kết quả "qua bảng" hoặc "đóng vì lỗi giao thức". Ví dụ Host nhận `FRAME`, Viewer nhận `MOUSE_MOVE` đều phải bị đóng. Các kiểm tra ngoài bảng (mục 2.4) có test riêng trong 3.3.
- **Race bằng latch/barrier:**
  - Các ca: close/start, cancel/`AUTH_RESULT`, mất kênh ảnh/`SESSION_ACTIVE`, stall/input, disconnect/frame, AUTH mới/`endSession`, hết `reserve`/worker chuẩn bị trả về, ATTACH/chuẩn bị xong.
  - Commit với `@RepeatedTest(20)`. Trước merge, coordinator chạy 500 vòng và yêu cầu 0 lỗi.

### 3.5 Dọn dẹp cuối mỗi test (tách khỏi kỳ vọng của ca)

`@AfterEach` đóng cả hai phía (core, runtime), rồi mới kiểm:
- không còn luồng `rd-*`;
- port đã được giải phóng;
- `tickerRegistrations() == 0`.

Lỗi ở bước này được báo riêng, để không lẫn với kỳ vọng của ca.

### 3.6 Hai JVM thật trên loopback

Kéo `TwoJvmIntegrationTest` từ wave 4.2 lên làm điều kiện xong GĐ2. Host dùng Robot và màn hình giả, Viewer chạy headless. Kiểm:
- 30 frame tới nơi;
- 100 sự kiện input đến đúng thứ tự;
- từng phía tắt thì phía kia đóng sạch;
- kill một JVM thì phía kia đóng trong ≤ 15 s.

### 3.7 Độ phủ (đã chốt 2026-11-01)

**JaCoCo** chốt `jacoco-maven-plugin:0.8.16` (chỉ ở build, không vào jar), đã dùng để đo baseline trên JDK 24; CI vẫn chạy JDK 21. Gắn `prepare-agent` ở `initialize`, rồi `report` và `check` theo đúng thứ tự ở `verify`. Bản này và mô hình fork phải giữ nguyên giữa ba lượt nghiệm thu A0.
- **Rule ghi rõ, không dùng mặc định:** mặc định JaCoCo đo cả bundle theo instruction, nên phải khai báo `element=CLASS`, `counter=BRANCH`, `value=COVEREDRATIO`, `minimum=0.80`.
  - `includes` **của rule CLASS** liệt kê đúng tên đầy đủ (`com.ltm.rd.…`) của từng lớp mục tiêu, không dùng wildcard. Không dùng các tên có dấu chấm này làm bộ lọc file ở cấp plugin; `report`/phân tích để mặc định toàn bộ classes, không đặt bộ lọc che lớp mục tiêu.
  - Lớp lồng nhau (record, lambda holder) không nằm trong cổng này; PIT sẽ phủ chúng.
- **Lớp mục tiêu đổi tên theo giai đoạn:**

  | Từ bước | Lớp mục tiêu |
  | --- | --- |
  | A0 | `server.ServerSession`, `client.ClientSession`, `server.HandshakeHandler` |
  | A1 | thêm `server.ControlSide` |
  | A2 | thêm `client.ControlledSide` |
  | A4 | `server.ControlSide` → `viewer.ControlSide`; `client.ControlledSide` → `host.ControlledSide` |
  | B2 | `host.HostSession`, `viewer.ViewerSession`, `host.HandshakeHandler`, `viewer.ControlSide`, `host.ControlledSide` |
- **Chống "lọt cổng âm thầm":** nếu một tên trong `includes` không còn khớp lớp nào, JaCoCo không báo lỗi. Vì vậy:
  - Danh sách lớp mục tiêu được ghi thêm ở `src/test/resources/coverage-targets.txt`.
  - `CoverageTargetsTest` nạp từng tên bằng `Class.forName(name, false, loader)` và **parse XML** để so sánh tập tên trong file với đúng rule `CLASS/BRANCH/COVEREDRATIO/0.80` của execution `check` đang được gắn vào build. Không dùng tìm chuỗi trong toàn bộ pom: tên trong comment, `pluginManagement` hoặc profile không hoạt động không được tính. A0 đặt cấu hình này trực tiếp trong `build/plugins`, không dùng profile.
  - Đổi tên hay chuyển package mà quên một trong hai chỗ thì `verify` đỏ.
  - Coordinator đối chiếu CSV/XML và xác nhận có đủ các lớp mục tiêu ở mỗi bước, kèm `BRANCH_COVERED/BRANCH_MISSED` và SHA. Nghiệm thu âm: gõ sai một tên trong rule/file phải làm `verify` đỏ.
- **Dữ liệu mới cho mỗi lượt `verify`:** ở `initialize`, dùng một execution riêng của `maven-clean-plugin:3.5.0` chỉ xóa `${project.build.directory}/jacoco.exec` và thư mục `${project.build.directory}/site/jacoco`.
  - Trong **configuration của riêng execution này**, đặt `excludeDefaultDirectories=true`, `fast=false`, `failOnError=true`; fileset không đi theo symlink và chỉ trỏ tới hai đầu ra coverage trên. Không xóa classes/test-classes hay đổi hành vi mặc định của lệnh `clean`.
  - Khai báo clean plugin trước JaCoCo trong `build/plugins`, để reset chạy trước `prepare-agent` tại cùng phase `initialize`. Đặt `append=false` ở `prepare-agent`. A0 giữ Surefire một fork tái sử dụng (`forkCount=1`, `reuseForks=true`); muốn nhiều fork thì phải thiết kế file riêng/merge trong **cùng lượt**, không dùng chung file bị ghi đè.
  - Chỉ `append=false` chưa đủ: nếu agent không chạy, file cũ vẫn còn. `requireFilesExist` cũng không phân biệt được file cũ với file mới. Vì vậy reset, agent và chốt tồn tại phải đi cùng nhau. Lệnh `verify` thường xuyên vẫn tạo dữ liệu mới ngay cả khi người chạy không thêm `clean`.
- **Chống "check bị bỏ qua":** khi không có `jacoco.exec`, goal `check` chỉ in "Skipping JaCoCo execution due to missing execution data file" mà build vẫn `BUILD SUCCESS`.
  - Coordinator đã thử lại điều này với 0.8.16 bằng một pom tạm, ngày 2026-11-01.
  - Thêm execution `verify` dùng `requireFilesExist` cho `${project.build.directory}/jacoco.exec`, `…/site/jacoco/jacoco.csv` và `…/site/jacoco/jacoco.xml`.
  - **Chỉ một block `maven-enforcer-plugin`** (giữ bản 3.5.0 hiện tại): chuyển cả block ra sau JaCoCo trong `build/plugins`, giữ nguyên execution kiểm môi trường ở `validate`, thêm execution kiểm coverage ở `verify`. Không khai báo plugin lần hai; thứ tự các execution bên trong một block cũ đứng trước JaCoCo không làm nó chạy sau JaCoCo.
  - Thiếu dữ liệu hoặc thiếu báo cáo thì `verify` đỏ.
  - Surefire hiện không ghi đè `argLine` (`pom.xml:61-70`), nên agent của `prepare-agent` gắn được. Ai thêm `argLine` sau này thì phải nối `@{argLine}`.
  - **Đã thử bằng fixture POM độc lập (2026-11-01):** seed file coverage cũ rồi chỉ gọi Enforcer thì xanh (exit 0); chạy `initialize` thì hết cả ba file cũ và vẫn còn sentinel trong `target/classes`. Chạy đầy đủ `verify` không có JVM test, hoặc seed dữ liệu cũ rồi `verify -Djacoco.skip=true`, đều đỏ ở Enforcer (exit 1). Thứ tự quan sát được: reset → prepare-agent → report → check → enforcer. Đây là bằng chứng cho cấu hình mẫu, chưa phải A0 đã triển khai vào pom thật.
  - A0 phải kiểm lại hai ca âm trên pom thật: có sẵn dữ liệu cũ rồi tắt agent, và làm thiếu dữ liệu/báo cáo của lượt hiện tại; cả hai đều phải đỏ. Ba lượt nghiệm thu là lượt bình thường đầy đủ test, không dùng tùy chọn skip.

Nguồn chính thức cho cấu hình: [Maven lifecycle và thứ tự goal](https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html), [Clean filesets/excludeDefaultDirectories](https://maven.apache.org/plugins/maven-clean-plugin/clean-mojo.html), [JaCoCo prepare-agent/append](https://www.jacoco.org/jacoco/trunk/doc/prepare-agent-mojo.html), [JaCoCo check rules](https://www.jacoco.org/jacoco/trunk/doc/check-mojo.html), [Enforcer requireFilesExist](https://maven.apache.org/enforcer/enforcer-rules/requireFilesExist.html).

**PIT** (`pitest-maven` + `pitest-junit5-plugin`; khai báo trong `pom.xml` nhưng không gắn vào lifecycle) chạy tay một lần trước merge, trên **SHA cuối** của branch:
- `targetClasses` giới hạn đúng 5 lớp ở dòng B2, kèm lớp lồng nhau (`…HostSession*` để bắt cả `HostSession$…`).
- Không bó `targetTests` vào `host`/`viewer`/`it`: giữ phát hiện test mặc định trong dự án, gồm cả `ui`/`common`, vì các test runtime/lifecycle cũng kiểm hành vi của năm lớp mục tiêu. Chỉ giới hạn code bị mutation qua `targetClasses`.
- Xuất `XML` và `HTML`. Lưu `mutations.xml` vào `docs/superpowers/reviews/<ngày>-pit-mutations.xml`.
- Review từng `SURVIVED` và `NO_COVERAGE`: thêm test, hoặc chứng minh mutation tương đương code gốc; không coi thiếu coverage là tương đương. `TIMED_OUT`/`RUN_ERROR` phải được điều tra, không tự tính là test đã kiểm đúng hành vi. Không chỉ ghi điểm tổng; code/test liên quan thay đổi sau lượt đo thì chạy lại phạm vi bị ảnh hưởng và ghi SHA mới. Cấu hình theo [PIT Maven](https://pitest.org/quickstart/maven/).

### 3.8 Review và nghiệm thu tay

- Mỗi task có review độc lập theo effort đã duyệt (C0/A1/A2/B0/B1 xhigh; A3 high; A4 và B2 medium) theo checklist: state machine, CAS ownership, close idempotent, deadline, thứ tự lock, quy tắc stream ở 1.3, hợp đồng hook ở 2.3, điều kiện trả slot ở 1.8. Finding Critical/High phải đóng trước task sau.
- Cuối branch: review toàn branch (sonnet xhigh). Coordinator chạy verify, 500 vòng race và (nếu duyệt) PIT.
- H2 do người dùng chạy trên 2 máy bằng v2. Ngoài các ca cũ, thêm:
  - gõ phím ngay sau khi kết nối;
  - click ngay sau khi hồi phục từ stall;
  - sai mã 3 lần → khóa 30 s;
  - Viewer bấm Hủy lúc đang kết nối;
  - đóng cửa sổ Host giữa phiên (Viewer thấy lý do, phím được nhả);
  - ngắt switch ảo (hai bên tự đóng ≤ 15 s);
  - máy có nhiều adapter mạng;
  - jar v1 kết nối vào v2.

## 4. Ghi chú cho Phần 3 (chưa trình bày)

- `ClientFrame` (sẽ thành cửa sổ nhập mã của Viewer):
  - Lỗi đã biết: `pendingText` (lý do `connect()` không chạy) bị xóa ngay ở snapshot kế tiếp nếu snapshot `IDLE` vẫn còn `message` của phiên trước. Vì vậy người dùng có thể không kịp thấy lỗi nhập liệu (`ClientFrame.java:89-93`).
  - Sửa cùng Phần 3, có test.
- `SharingBar` chuyển sang Host. `ControlFrame` và `RemotePanel` chuyển sang Viewer.

## Lịch sử sửa

- **2026-11-01, G0 (đính chính tài liệu trước C0; người dùng đã duyệt thực thi lộ trình; chưa sửa source/test/pom):**
  1. Bỏ `LifecyclePort.wrap(Tx)` khỏi API sản xuất: chỉ `LifecyclePort.atomically` tạo `SideTx` từ lifecycle của chính port; vỏ đang giữ tx gốc dùng `port.atomically` lồng (mục 2.3). `tryActivate` kiểm view thuộc đúng port và ép kiểm lifetime tx gốc trước khi gọi Shell; thêm test `tryActivateWithExpiredSideTxThrows`.
  2. Thêm C0 (mục 2.5) trước A1: `maybeActivate(tx)` đọc clock trong tx; A1–A4 là refactor thuần so với baseline C0.
  3. Giữ chuẩn bị adapter v1 (trước connect) suốt C0–B0; `rd-host-prepare` chỉ B1. `viewer.ControlView → server.ServerPhase` tạm thời đến B1. B1/B2 hoãn đến khi Phần 3–4 được duyệt.
  4. B0: `inputReleaseSeam` biến đổi stage nguồn của bridge, không thay target `inputReleased` cố định.
  5. Model/effort theo định tuyến người dùng chấp nhận: G0 high; C0/A1/A2/B0 xhigh (review xhigh); A3 high (review high); A4 high (review medium); review cuối xhigh. Co-author phải ghi đúng agent thực tế, không ghi cứng số phiên bản model chưa được receipt chứng minh.
- **2026-11-01, review Phần 2 (5 điểm, đối chiếu code tại `8321095`):**
  1. [Cao] Thêm mục 1.8: trả slot ở Host chỉ sau khi input đã nhả; ghi rõ quyền sở hữu `InputExecutor` và cách xử lý khi nhả thất bại. Thêm commit B0 và test "Viewer thứ hai lúc `endSession()` còn chạy".
  2. [TB] Bảng 2.2 chuyển thành bảng lớp cũ → đích theo từng bước: hai nửa vai trò chuyển ở A4, các lớp vỏ ở B2. `SubmitResult` chuyển sang `host`.
  3. [TB] Mục 2.3 mô tả hợp đồng `SessionPort`/`SessionSide`: hook nào chạy trong tx, cách truyền kênh ảnh, cách báo lỗi, cách chờ nhả input, và các phụ thuộc phải tháo.
  4. [TB] Thêm mục 2.4: `PacketRules` chỉ lọc loại gói; kiểm tra "chỉ một lần", token, payload và generation vẫn nằm trong tx.
  5. [TB] Thêm mục 3.3 (kỳ vọng theo từng ca) và 3.5 (dọn dẹp tách riêng); 3.2 thêm quy tắc xử lý PING/PONG trong golden trace.
- **2026-11-01, review lần 2 (2 điểm TB + 1 làm rõ, đối chiếu code tại `8321095`):**
  1. [TB] Mục 1.8: chỉ giữ một `InputExecutor`; `ScreenSource`/geometry tạo lại và thiết bị input được refresh theo từng phiên, như v1 (`AppRuntime.java:381-384`, `RobotScreenSource.java:15`). Việc chuẩn bị chạy ngoài mọi khóa, trước `AUTH_RESULT(OK)`; có ca lỗi adapter. Mục 3.3 thêm ca đổi geometry giữa hai phiên.
  2. [TB] Mục 2.3: thêm `SideTx` trung tính; bỏ `Verdict`; giữ đúng lý do đóng v1 (`STALE` → `PROTOCOL_ERROR`, `OVERFLOW` → `OVERFLOW`, `CommandReceiver.java:66-67`); `CommandReceiver.handle` bỏ phụ thuộc `Tx<ClientPhase>`; nhánh PING chuyển lên vỏ. Đổi xử lý `STALE` thì làm thành thay đổi hành vi riêng.
  3. Mục 3.2: ghi rõ các sửa test cơ học được phép ở A1–A4 (package/import/tên, chữ ký ở ranh giới); assertion và kỳ vọng giữ nguyên.
  4. Mục 3.7 chốt JaCoCo (CLASS/BRANCH/COVEREDRATIO ≥ 0.80 từng lớp, danh sách lớp theo giai đoạn, `CoverageTargetsTest`) và PIT (SHA cuối, 5 lớp + lớp lồng nhau, review từng mutation còn sống). Thêm bước A0 (JaCoCo baseline + golden trace trước refactor).
- **2026-11-01, review lần 3 (1 điểm Cao + 2 chỉnh lý; coordinator kiểm lại bằng chạy thật):**
  1. [Cao] Chuẩn bị adapter chuyển từ luồng handshake sang worker `rd-host-prepare` do lifecycle quản lý. Nhờ vậy `terminated()` chờ nó, và slot không thể được trả khi việc chuẩn bị còn chạy. Sau khi worker trả về, nó kiểm phiên và `reserveExpired` trong tx rồi mới công bố geometry; nếu không hợp lệ thì bỏ kết quả. Bounds của executor chỉ được gán lúc kích hoạt. Kích hoạt cần "đã chuẩn bị" + "ATTACH" theo thứ tự bất kỳ. Thêm các ca: giữ adapter quá deadline, ATTACH đến trước, PING trước `SCREEN_INFO`, `beginSession` bị từ chối.
  2. Mục 1.3 thống nhất với các mục sau: PING/PONG có thể đến trước `SCREEN_INFO` và `SESSION_ACTIVE`; `beginSession` phải thành công trước khi công bố `ACTIVE`.
  3. Mục 3.7: thêm chốt `requireFilesExist`, vì đã tái hiện được việc `check` bị bỏ qua khi thiếu dữ liệu. A0 ghi baseline thật và yêu cầu cổng ổn định qua 3 lần chạy, có biên 2 nhánh.
- **2026-11-01, bản chốt kỹ thuật lần 4 (reviewer sửa spec, chưa sửa source/pom):**
  1. Giữ P2; ràng buộc vòng đời worker, deadline đọc trong tx, chỉ ready sau `offer(SCREEN_INFO) == ACCEPTED`, kích hoạt tối đa một lần, và ghi nhận begin trước mọi đường lỗi. Tách rõ refactor wire v1 ở A1–B0 với chuẩn bị bất đồng bộ v2 ở B1.
  2. Giữ một future `inputReleased()` cố định từ constructor; bridge kết quả close hook vào đó. Bổ sung `SideTx` trung tính cho phase chờ/deadline, cấm giữ tx qua worker; công bố bounds an toàn cho supplier của input executor.
  3. Bổ sung test overflow, future identity, hai trigger kích hoạt, biên deadline và shutdown; sửa gate giữ adapter thành `blockNewScreenSource(true)`. Tách hủy trước/sau reserve thay vì suy từ thời điểm Viewer đọc reply.
  4. Coverage phải là dữ liệu mới từng lượt: reset riêng đầu ra coverage ở initialize, JaCoCo 0.8.16 với append=false, một block Enforcer sau JaCoCo. Fixture POM đã kiểm được cleanup có giới hạn và build đỏ khi thiếu dữ liệu mới. Chốt 3 lượt clean verify độc lập; tính lại biên A0 thành 15–16 nhánh bổ sung theo baseline, không suy ra số test từ số nhánh.
  5. Parse XML để kiểm đúng rule coverage; PIT không loại test ui/common và phải xử lý cả NO_COVERAGE/lỗi chạy. Đây là bản thiết kế đủ cụ thể để triển khai có kiểm thử, không phải chứng nhận v2 đã chạy đúng hay cam kết không còn lỗi.
  6. Builder check độc lập đã đọc spec và code v1, không còn câu hỏi thiết kế quan trọng. Làm rõ thêm điều kiện Viewer `activationReceived && imageChannelReady` theo test hiện có; không có quyết định mới cần người dùng cung cấp.
