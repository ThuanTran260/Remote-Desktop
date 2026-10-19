# Kế hoạch triển khai Remote Desktop v1

Ngày: 2026-10-19. Nguồn hợp đồng: [spec bản sửa 4](../specs/2026-10-19-remote-desktop-design.md).

Phạm vi phát hành: ứng dụng đồ án trên Windows, một phiên, màn hình chính, LAN tin cậy. Không coi đây là sản phẩm truy cập Internet hoặc có bảo đảm thời gian thực. Không đổi sang HTTP/Postman collection: đây là giao thức TCP nhị phân tùy chỉnh, kiểm thử bằng JUnit và socket peers.

## Cách thực hiện và bằng chứng

- Mỗi task: viết test hành vi rủi ro → chạy và xác nhận thất bại đúng lý do → implementation nhỏ nhất → chạy lại → refactor mà giữ test xanh. Không viết test chỉ lặp lại getter hoặc cấu trúc private.
- Java 21, Maven, JUnit 5. `mvn test` chạy headless; test phần cứng đặt ngoài bộ mặc định. Pin phiên bản dependency/plugin tương thích JDK 21 khi tạo pom, ghi phiên bản vào báo cáo build.
- Inject clock monotonic, deadline scheduler và adapters InputDevice/ScreenSource; dùng latch/barrier để ép race, không dựa vào sleep ngẫu nhiên. Socket thật dùng localhost và cổng 0 ở test; không chiếm 5000/5001 trong CI.
- Với deadline, test clock giả kiểm tra chính xác logic; integration thật kiểm tra trong khoảng deadline + một chu kỳ watchdog + dung sai CI được ghi rõ. Không hứa tất cả máy đều hủy trong 200 ms.
- Chỉ mở task sau khi dependency gate đạt. Làm trên branch `feat/remote-desktop-v1`; mỗi task commit trong worktree riêng, coordinator review rồi merge vào branch. Không push/publish.
- Môi trường dev hiện tại: JDK 24.0.2, Maven 3.9.12; build bằng `--release 21`. Máy demo cần JDK ≥ 21.

## Task 0a — Khởi tạo project (gate G0a, tự động)

Tạo `pom.xml`, `src/main/java/com/ltm/rd/`, `src/test/java/com/ltm/rd/`, README và `.gitignore` cho build output. Đặt main class launcher, jar tên `remote-desktop.jar`, compiler release 21, Surefire headless và cấu hình manifest.

Đạt khi `mvn test`, `mvn package` chạy được và `java -jar target/remote-desktop.jar --help` in usage. Chưa cần mạng hoàn chỉnh.

## Task 0b — Spike Robot thủ công (gate G0b, người dùng thực hiện, song song với Task 1–5)

Spike thủ công nhỏ trên hai máy Windows để kiểm tra Robot: capture, mapping góc màn hình ở DPI 100/125/150%, key repeat Backspace/chữ/mũi tên, giữ modifier, UAC ngoài phạm vi. Dùng Robot input và capture riêng. Chốt chính sách repeat theo số đo; nếu không đạt, sửa policy và test trước khi ghép UI.

Đạt khi có bảng OS/JDK/DPI và kết quả spike. Chỉ chặn Task 6 và việc chốt `RobotInputDevice`/`RobotScreenSource`; Task 1–5 dùng adapter fake.

## Task 1 — Codec và validation (G1; sau G0a)

Tạo `common/PacketType.java`, `Packet.java`, `PacketIO.java`, `Protocol.java` và `ProtocolException.java`.

Test: round-trip mọi type; golden byte AUTH/ATTACH/PRESS/FRAME; endian; đọc 1 byte/lần; length âm/biên; EOF mọi vị trí header và payload; sai type bị từ chối trước allocation; ASCII/UTF-8 sai; miền tọa độ/button/wheel/status. FRAME JPEG kiểm tra dimensions trước decode ở task 5. Payload đã enqueue không bị thay đổi khi caller sửa buffer.

Đạt khi các golden packets khớp spec; serializer và parser cùng áp dụng giới hạn; EOF sạch vẫn kích hoạt đóng phiên ở tầng gọi. Không tạo magic mỗi packet hoặc tự resync sau lỗi.

## Task 2 — Writer có giới hạn (G2; sau G1)

Tạo `common/CommandWriter.java`, `Channel.java`, `TrafficCounter.java`. Test qua stream fake và loopback.

Test: gộp MOVE cuối queue; không gộp xuyên PRESS; overflow không gọi callback dưới khóa; offer sau close; packet đang ghi được xử lý trước final; close lặp trả cùng future; future chưa xong khi flush bị giữ bởi latch; lỗi write hoàn tất future exceptionally. Chặn thực sự writer bằng stream fake hoặc xác nhận socket buffer đã đầy; không chỉ ngừng reader rồi giả định write đã kẹt.

Đạt khi số phần tử hữu hạn, close không chờ I/O ở caller, counters không đếm đôi và buffer payload bất biến.

## Task 3 — InputExecutor và thế hệ phiên (G3; sau G1)

Tạo `client/InputDevice.java`, `InputExecutor.java` và fake InputDevice cho test. `RobotInputDevice.java` chỉ làm sau spike G0b. Theo đúng máy trạng thái mục 3.5; không dùng volatile đơn lẻ để thay cho thao tác nhận/end nguyên tử.

Test bắt buộc:

- begin thành công rồi submit ngay: không mất lệnh đầu.
- begin rồi end trước khi worker nhận lệnh: không có tác vụ bật lại generation cũ.
- end lúc lệnh đang chạy: caller trả về, lệnh đó được kết thúc rồi release; lệnh còn trong queue bị bỏ.
- queue đầy: END vẫn tồn tại; end lặp không chiếm thêm lifecycle slot.
- begin generation mới bị từ chối đến khi release thành công; stale end không chạm phiên mới.
- release lỗi: thử nhả các phím khác, future lỗi, module FAULTED; hai giây không mở kết nối.
- Robot bị chặn: observer cảnh báo không complete/cancel release future; khi fake được giải phóng, kiểm tra hồi phục đúng generation.
- release nút vẫn được thử khi screen bounds thay đổi; mouse press trùng, key repeat và release không thuộc held set.

Đạt khi toàn bộ test giao diện module xanh. Mô hình Python ở thư mục reviews chỉ hỗ trợ suy luận, không thay thế test này.

## Task 4 — Session, deadline và bắt tay hai kênh (G4; sau G2, G3)

Tạo `SessionBase` (hoặc helper composition nếu tránh kế thừa làm rõ hơn), `server/SessionManager`, `Session`, `PendingConnections`, `HandshakeHandler`, `client/ClientSession` và Watchdog.

Test: hai AUTH đồng thời; SCREEN_INFO/ATTACH cả hai thứ tự; kích hoạt đúng một lần; timeout auth/reservation/activation; đăng ký socket chạy đua với close; claim bàn giao chạy đua shutdown/deadline; claim sau deadline dù scheduler chậm; flush AUTH_RESULT lỗi phải giải phóng reservation; attach token sai/đã dùng chỉ đóng socket lạ. Phiên cũ không cập nhật UI/state mới.

Test timeout stale: snapshot thấy frame/PONG cũ, luồng khác cập nhật rồi watchdog quyết định; phải kiểm tra lại timestamp. Tương tự write operation id thay đổi. Hủy trước writer/channel vẫn đóng socket raw. Close không phụ thuộc cleanup executor rảnh, kể cả executor từ chối task. Assert callback/Robot/I/O không chạy khi giữ khóa metadata bằng fake adapter có kiểm soát.

Đạt khi headless integration AUTH → ACTIVE → input → DISCONNECT chạy; cả hai socket đóng, reserved slot trả lại; timeout socket không chờ release input.

## Task 5 — Luồng ảnh và backpressure (G5; sau G4)

Tạo `ScreenSource`, `RobotScreenSource`, `ScreenSender`, `server/FrameReceiver`. Chụp/nén/ghi tuần tự; không catch-up frame. Decode ngoài khóa phase; trước publish kiểm tra generation.

Test: JPEG hỏng, kích thước giả lớn, ratio sai, byte limit; dispose readers/writers/streams; frame đầu đến trước 3 giây vẫn bật hasFrame; timeout khi chưa có frame; gián đoạn 3 giây chỉ một RELEASE_ALL; hồi phục không cập nhật panel phiên cũ. Test mạng S/5 kiểm tra stall rồi hồi phục; S/20 kiểm tra write watchdog có thể thắng. Riêng frame timeout dùng peer có heartbeat nhưng không gửi ảnh.

Đạt khi memory có trần theo số frame đang xử lý, UI pending update được gộp, không có queue ảnh không giới hạn. Chưa khẳng định giới hạn độ trễ chỉ từ SO_SNDBUF.

## Task 6 — Swing, tọa độ và trải nghiệm điều khiển (G6; sau G5 và G0b)

Tạo `CoordinateMapper`, `RemotePanel`, `InputCapture`, `ServerGUI`, `ClientGUI`, launcher. UI hiện rõ vai trò; Client có Hủy trong connect/auth/attach và Dừng trong ACTIVE. Nút đổi mã chỉ LISTENING. Tham số cổng dùng được ở cả hai bên, kiểm tra cổng khác nhau và trong 1..65535.

Test headless logic bằng input/focus snapshot được truyền rõ, không trông đợi JPanel headless trở thành focus owner thật. GUI smoke test riêng: click lấy focus; lần press đó không gửi; multi-button drag; release ngoài vùng; mất focus; panel resize/0; scaling; wheel 45 → 20/20/5, 100 → 20/20/20 và bỏ phần nguyên vượt ngưỡng, giữ phần lẻ. Tất cả state InputCapture chỉ sửa trên EDT.

Đạt khi không I/O/encode/Robot trên EDT; chưa có frame thì không điều khiển; ảnh stalled khóa điều khiển; giữ focus không đồng nghĩa tự khôi phục phím đã release.

## Task 7 — Shutdown, lỗi và khả năng chẩn đoán (G7; sau G6)

Hoàn thiện App.shutdown idempotent: cờ shutdown bật trước khi dispatch; listener/pending/registered sockets đóng; executor từ chối cũng không mất cleanup; worker input kết thúc riêng và có timeout tổng được ghi rõ. Shutdown hook dùng cùng future, chờ giới hạn cho phần mạng và release; không chỉ schedule tác vụ daemon rồi return ngay.

Log reason, phase, generation, số queue và lỗi I/O có giới hạn tần suất; không log mã xác thực/token hoặc nội dung ảnh/phím. Mã lỗi khởi động và hướng dẫn Firewall/JDK trong README. Giới hạn bảng khóa IP và loại entry hết hạn để tránh tăng bộ nhớ lâu dài.

Đạt khi đóng từng cửa sổ/CLI Ctrl+C giải phóng cổng; close một raw socket không phụ thuộc buffered stream flush. Test shutdown khi handshake pool bận, writer kẹt, frame decode đang chạy và input fake bị chặn.

## Task 8 — Nghiệm thu và đóng gói (G8; sau G7)

- `mvn clean verify`, `java -jar target/remote-desktop.jar --server`, `--client`, không tham số, invalid args và cổng tùy chỉnh.
- Hai máy LAN: tất cả mục 5.2, 30 phút chạy liên tục, 50 lần reconnect, rút mạng khi giữ phím, sleep/wake, DPI và resize. Không dùng số thread tổng bằng 0 khi app còn chạy; so baseline thread cấp ứng dụng với thread cấp phiên.
- Ghi FPS gần 12, RTT riêng, p50/p95 thao tác → ảnh, CPU và heap sau warmup, cấu hình máy/mạng/JDK. Ghi backlog hoặc jitter chưa giải quyết thành limitation rõ ràng.
- So heap sau GC ở nhiều thời điểm và số thread/socket giữa các chu kỳ; một snapshot heap riêng lẻ không chứng minh không rò.
- Artifact: jar, README cách chạy/đồng ý chia sẻ/dừng, protocol spec, báo cáo test gồm lệnh/kết quả/ngày/môi trường và danh sách limitation. Không yêu cầu người dùng mở IDE để chạy.

**Được phát hành v1** khi G0–G8 đều có bằng chứng, không còn lỗi kẹt phím, sai generation, queue không giới hạn hoặc shutdown rò tài nguyên trong ma trận hỗ trợ. Nếu OS/Robot treo không nhả được phím, phải đóng mạng/khóa kết nối và hiển thị lỗi, không báo thành công giả.

## Tình trạng lúc lập kế hoạch

Chỉ có tài liệu, chưa có pom/source Java. Review spec, đối chiếu tài liệu JDK và mô hình lifecycle nhỏ có thể được chạy ngay; mọi gate Java/GUI/LAN bên trên đều **chưa chạy**. Không dùng việc tài liệu không có TODO làm bằng chứng runtime đã đúng.
