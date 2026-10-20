# Đợt 3 — GUI và Robot

Ngày: 2026-10-20. Trạng thái: kế hoạch, chưa triển khai.

Nguồn: [spec](../specs/2026-10-19-remote-desktop-design.md), [kế hoạch tổng](2026-10-19-remote-desktop-implementation.md), [đợt 2](2026-10-19-wave2-session-and-image.md). Tiếp theo: [đợt 4 — ổn định và phát hành](2026-10-20-wave4-hardening-and-release.md).

## Mục tiêu và bằng chứng hiện tại

Sau đợt 3, hai máy Windows trong LAN dùng giao diện Swing để chia sẻ màn hình và điều khiển bằng Robot. Server là máy điều khiển; Client là máy được điều khiển. Người dùng đã xác nhận có hai máy Windows để nghiệm thu.

Baseline đợt 2 được kiểm tra bằng `./mvnw.cmd -q verify`: 334 test, không failure, error hoặc skipped. Đây là bằng chứng cho lõi mạng với adapter giả; chưa chứng minh GUI, Robot hoặc vận hành trên hai máy thật.

Giữ Java 21, Swing system look-and-feel, giao diện tiếng Việt, không thêm runtime dependency hoặc đổi định dạng gói. Phạm vi: một phiên, màn hình chính, Windows, LAN tin cậy. Không thêm TLS/Internet, clipboard, file, âm thanh, nhiều màn hình, IME, điều khiển UAC hoặc lock screen.

## Phương pháp và hợp đồng triển khai

Áp dụng các nguyên tắc đã đọc từ skills `mattpocock-skills/engineering/codebase-design` và `mattpocock-skills/engineering/tdd`: interface nhỏ, module tự giữ invariant; kiểm thử qua hành vi công khai; mỗi lát dọc red → green → refactor. Không mô phỏng chi tiết private hoặc viết test chỉ lặp lại implementation.

Các seam đã thống nhất: Robot adapters, CoordinateMapper/InputCapture, controller–UI snapshot và AppRuntime/shutdown. Tái sử dụng kiểm thử mạng đợt 2. Không yêu cầu một bộ tự động điều khiển desktop đầy đủ; kiểm thử phần cứng phải opt-in, tách khỏi CI headless.

Thực hiện Native theo thứ tự 3.1 → 3.2 → 3.3 → 3.4 → 3.5 → 3.6. Có thể phát triển 3.2 bằng fake trong lúc chờ số đo Robot, nhưng chưa tích hợp adapter thật trước khi 3.1 đạt. Mỗi task hoàn thành có một commit sau khi kiểm tra; không tự push hoặc merge. Review bắt buộc sau 3.2 và 3.6.

Lệnh kiểm tra chung: `./mvnw.cmd -q verify`. Khi làm một lát dọc, chạy test liên quan trước, rồi chạy verify khi đóng task. Test phần cứng và LAN ghi kết quả riêng, không coi test headless là thay thế.

## Task 3.1 — Robot adapters và spike trên Windows

### Công việc

- Thêm RobotScreenSource và RobotInputDevice, sử dụng hai Robot riêng trên cùng màn hình chính. Luồng capture không giữ khóa input; chỉ worker của InputExecutor gọi Robot nhập liệu.
- InputDevice nhận tọa độ tuyệt đối. InputExecutor đã cộng bounds.x/y: adapter không được cộng lần thứ hai.
- Tạo chương trình spike thủ công opt-in, ngoài `mvn verify`, với vùng thử nghiệm nhìn thấy được và cleanup luôn thử nhả phím/nút đã giữ.
- Thử capture, bốn góc, click, kéo, wheel, chữ, Backspace, mũi tên, modifier và key repeat. Ghi OS, JDK, kích thước logic, kích thước capture và scale DPI.
- Chạy trên hai máy Windows, thử DPI 100%, 125%, 150% khi máy hỗ trợ. Ghi rõ cấu hình không thử được.
- Khi cấu hình màn hình thay đổi, đóng phiên hiện tại và tạo lại Robot khi kết nối lại. Headless hoặc khởi tạo Robot thất bại phải báo lỗi, không bắt đầu chia sẻ.

### Kiểm tra và điều kiện đạt

Test tự động cho chuyển đổi button/geometry và xử lý lỗi qua adapter seam, không tạo Robot trong CI. Spike thật phải xác nhận không lệch tọa độ và không kẹt phím/nút. Chỉ giữ chính sách repeat hiện tại nếu số đo không mất hoặc lặp đôi ký tự; nếu thất bại, sửa policy và test trước khi tích hợp GUI.

Tham chiếu: [Java 21 Robot](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/Robot.html). Capture và các thao tác chặn không chạy trên EDT.

## Task 3.2 — AppRuntime, controller và snapshot

### Công việc

- AppRuntime sở hữu vòng đời một vai trò: start server, connect/cancel/disconnect client, đổi mã và shutdown bất đồng bộ. Swing không sở hữu socket, ticker hoặc generation.
- Controller cung cấp snapshot gồm trạng thái, generation, thông báo, peer, kích thước màn hình, frame, trạng thái cho phép điều khiển và thống kê.
- Giữ TrafficCounter riêng cho từng kênh; bổ sung API snapshot thống kê và RTT. Không lộ stream hoặc đếm byte lần thứ hai.
- Bổ sung `ServerSession.releaseControlInput()` để gửi RELEASE_ALL khi mất focus kể cả lúc gate ảnh đã đóng. Queue overflow vẫn đóng phiên theo hợp đồng hiện tại.
- Thêm `inputEpoch`, tăng khi ảnh chuyển sang gián đoạn. Mỗi thao tác input mang generation và epoch hiện tại ở API nội bộ; kiểm tra cùng gate trong transaction trước khi offer. Không đổi wire protocol.
- Snapshot chứa epoch để UI nhận ra cả trường hợp ảnh gián đoạn rồi phục hồi giữa hai lần refresh.
- Giữ InputExecutor suốt vòng đời app. Chỉ cho kết nối lại khi phiên cũ terminated và nhả input thành công. Hai giây chỉ là mốc cảnh báo; không complete/cancel future nhả phím để mở lại kết nối.

### Kiểm tra và điều kiện đạt

Test controller bằng fake và socket thật: cancel, callback generation cũ, release khi gate đóng, input epoch cũ, release thất bại, reconnect khi termination/release chưa xong. Bảo đảm shutdown/start không cần caller tự nhớ khóa hay thứ tự nội bộ.

Review gate: kiểm tra ownership, atomicity giữa gate/epoch/offer, callback sau close, và future release. Chỉ sang phần bắt input khi các invariant này đã được kiểm chứng.

## Task 3.3 — RemotePanel và CoordinateMapper

### Công việc

- RemotePanel vẽ ảnh giữ tỷ lệ, có letterbox. Mapper dùng chính hình chữ nhật đã làm tròn mà renderer dùng.
- Panel kích thước 0 hoặc chưa có SCREEN_INFO trả kết quả rỗng. Kiểm tra biên và clamp theo kích thước logic.
- Bộ lấy snapshot nền chạy mỗi 50 ms và giữ duy nhất snapshot mới nhất qua AtomicReference. Swing Timer trên EDT đọc snapshot; không enqueue một runnable cho từng frame.
- Chỉ cho nhập liệu sau khi frame hợp lệ đã được áp dụng lên panel, với đúng generation và epoch.

### Kiểm tra và điều kiện đạt

Test tỷ lệ ngang/dọc, letterbox, góc, resize, panel 0, generation cũ và UI chậm. Số frame/snapshot giữ lại phải hữu hạn; không để backlog EDT tăng theo FPS.

Tham chiếu: [Swing Timer](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/swing/Timer.html); callback chạy trên EDT, chỉ thực hiện việc ngắn.

## Task 3.4 — Chuột end-to-end

### Công việc

- InputCapture xử lý sự kiện trên EDT và gửi qua controller.
- Click đầu khi panel chưa focus chỉ lấy focus. Nuốt trọn press/drag/release tương ứng theo từng nút; không dùng một cờ chung cho mọi nút.
- PRESS/RELEASE dùng tọa độ tại sự kiện. Drag ra ngoài ảnh clamp ở mép. Chỉ gửi release cho nút có press đã được chấp nhận.
- Tích lũy wheel phân số; tối đa 20 notch/gói, 3 gói/sự kiện, bỏ phần vượt giới hạn đã thống nhất.
- Mất focus hoặc đổi epoch xóa held state và wheelAcc; không tự khôi phục thao tác kéo cũ.

### Kiểm tra và điều kiện đạt

Test chuỗi event → packet → socket → fake InputDevice: lấy focus, nhiều nút, drag ngoài vùng, late release, wheel phân số, queue overflow và ảnh đứng. Sau đó thử hai máy thật: không lệch tọa độ ở các DPI đã chọn, không kẹt nút khi mất focus hoặc ngắt phiên.

## Task 3.5 — Bàn phím và nhả toàn bộ input

### Công việc

- Dùng mã VK, không gửi keyTyped, không hỗ trợ IME. Panel nhận Tab để điều khiển máy bên kia trong phạm vi đã định.
- Chỉ chấp nhận input khi có focus, đã hiển thị frame và epoch còn hiệu lực.
- Khi mất focus, window deactivation hoặc disconnect: khóa nhận sự kiện mới, yêu cầu RELEASE_ALL và xóa held state/wheelAcc. Gộp các thông báo mất focus trùng nhau.
- Áp dụng chính sách repeat đã qua spike; không tự khôi phục phím đang giữ sau khi gate mở lại.

### Kiểm tra và điều kiện đạt

Test Shift/Ctrl, Backspace, Tab, repeat, release đến muộn, Alt-Tab, queue overflow và mất mạng khi đang giữ modifier. Log thiết bị chứng minh input được nhả; đếm RELEASE_ALL ở tầng giao thức khi cần kiểm tra số gói, không suy ra từ log rỗng.

## Task 3.6 — Giao diện, launcher và thống kê

### Công việc

- Không tham số: cửa sổ chọn vai trò. Server hiển thị IP, cổng, mã, peer và nút ngắt; chỉ đổi mã khi LISTENING.
- Client có IP/mã, Kết nối/Hủy. Thao tác kết nối là đồng ý chia sẻ; khi ACTIVE luôn có cửa sổ chia sẻ nổi trên cùng, peer và nút Dừng dễ thấy.
- `--host` chỉ điền sẵn, không tự kết nối. Thay thông báo `not implemented yet` trong launcher bằng runtime thật.
- `--help` vẫn chạy trong headless. Yêu cầu GUI ở headless trả lỗi rõ; giữ validation CLI và cổng hiện có.
- Hiển thị FPS, KiB/s riêng từng kênh và RTT; tính theo thời gian thực đã trôi qua, cập nhật khoảng một giây.
- Mọi thay đổi Swing trên EDT; khởi tạo/chờ mạng, capture, decode và shutdown không chặn EDT.

### Kiểm tra và điều kiện đạt

Test khởi động thất bại, double connect, Hủy ở từng phase, callback cũ và reconnect chờ nhả input. Kiểm tra `java -jar target/remote-desktop.jar --help` sau build và thử đủ hai vai trò trên hai máy.

Review gate cuối đợt: đối chiếu spec với hành vi GUI/Robot, ownership tài nguyên, generation/epoch và các đường nhả input. Đợt 3 đạt khi điều khiển được hai máy thật; chưa đánh dấu đủ điều kiện phát hành trước đợt 4.

## Các tình huống đã dùng để rà kế hoạch

| Tình huống | Cơ chế và bằng chứng cần có |
| --- | --- |
| Capture chậm | Robot riêng, không khóa input; spike và test adapter |
| EDT chậm | Latest-only snapshot, không hàng đợi frame vô hạn |
| Ảnh gián đoạn rồi hồi phục giữa hai refresh | inputEpoch; từ chối sự kiện cũ atomically |
| Mất focus khi gate đã đóng | releaseControlInput không phụ thuộc gate ảnh |
| Nhả phím chậm hoặc lỗi | Giữ future thật, không mở reconnect vì hết hai giây |
| Callback từ phiên cũ | Generation kiểm tra trước khi cập nhật state/UI |
| CI xanh nhưng Robot lỗi | Gate phần cứng riêng, không suy diễn từ headless |

Các tiêu chí trên là yêu cầu phải kiểm chứng khi triển khai, không phải kết quả thử nghiệm đã có.
