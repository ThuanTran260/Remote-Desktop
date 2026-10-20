# Đợt 4 — Kiểm thử ổn định và đóng gói Remote Desktop v1

Ngày: 2026-10-20. Trạng thái: kế hoạch, chưa triển khai.

Phụ thuộc: [đợt 3 — GUI và Robot](2026-10-20-wave3-gui-and-robot.md) đạt gate cuối đợt. Nguồn hợp đồng: [spec](../specs/2026-10-19-remote-desktop-design.md) và [kế hoạch tổng](2026-10-19-remote-desktop-implementation.md).

## Mục tiêu và cách thực hiện

Hoàn thiện shutdown, chứng minh vận hành ổn định trên hai máy Windows trong LAN và đóng gói một JAR sử dụng được ngoài IDE. Phạm vi và các seam giữ nguyên như đợt 3; không mở rộng tính năng trong đợt này.

Thứ tự: 4.1 → 4.2 → 4.3. Thực hiện Native; mỗi task một commit sau kiểm tra, không tự push/merge. Áp dụng codebase-design và TDD qua hành vi công khai như đợt 3. Mọi phát hiện làm thay đổi hợp đồng phải cập nhật spec, test và tài liệu cùng nhau.

## Task 4.1 — Shutdown và xử lý lỗi toàn ứng dụng

### Công việc

- `AppRuntime.shutdownAsync()` trả cùng future khi gọi lặp; bật shuttingDown ngay khi bắt đầu. Tổng ngân sách chờ là 5 giây, không cộng thêm 5 giây cho từng bước và không chờ trên EDT.
- Thứ tự: khóa nhận input → đóng session/listener → chờ release/termination có giới hạn → dừng sampler/timer/executor → dispose UI trên EDT.
- Shutdown hook dùng cùng future, không phụ thuộc EDT; nếu shutdown đã bắt đầu thì vẫn chờ future trong phần ngân sách còn lại.
- Runtime nhận quyền sở hữu ServerCore trước khi start. Start chạy đua shutdown phải kiểm tra checkpoint và đóng tài nguyên tạo muộn, không để listener/worker mồ côi.
- Không tạo InputExecutor thay thế để vượt qua worker cũ đang kẹt. Phân biệt socket đã đóng, worker đã dừng và input đã nhả; hết ngân sách không được báo nhả thành công khi chưa có bằng chứng.
- Log reason, phase, generation; không log mã/token, nội dung phím hoặc ảnh. Lỗi hiển thị rõ: bind cổng, kết nối, xác thực, timeout, quyền Robot, cấu hình màn hình thay đổi, lỗi nhả input.

### Kiểm tra và điều kiện đạt

Test đóng cửa sổ/Ctrl+C trong các phase, shutdown lặp, startup đang chạy, scheduler từ chối tác vụ, writer/capture/input bị giữ bằng fake. Dùng latch/barrier để ép race; không dựa vào sleep ngẫu nhiên. Sau khi giải phóng fake, kiểm tra đúng generation và không còn tài nguyên mồ côi.

Chạy `./mvnw.cmd -q verify`. Thử đóng ứng dụng thật trong lúc kết nối, chia sẻ và giữ phím/nút. Các lời gọi chờ phải có giới hạn; trạng thái thất bại phải trung thực.

## Task 4.2 — Nghiệm thu vận hành và hiệu năng

### Ba tầng kiểm chứng

1. Headless CI trên Java 21: unit/integration hiện có cùng test mới, chạy `./mvnw -B -ntp verify` trên CI và `./mvnw.cmd -q verify` trên Windows.
2. Hai JVM riêng trên một máy với fake adapters: xác nhận hoạt động không phụ thuộc trạng thái dùng chung trong JVM. Harness dùng cổng test phù hợp và đóng cả hai process khi kết thúc.
3. Hai máy Windows thật: JAR, Swing, Robot và LAN thật; ghi OS, JDK, DPI, độ phân giải và điều kiện mạng.

### Ma trận nghiệm thu

| Ca kiểm tra | Bằng chứng và điều kiện đạt |
| --- | --- |
| Hoạt động liên tục 30 phút | Capture và điều khiển duy trì; không freeze hoặc tăng tài nguyên không giới hạn |
| Kết nối/ngắt 50 lần | Không kẹt phím; thread/socket trở về mức nền phù hợp; heap không tăng đều sau các chu kỳ thu hồi |
| Cắt mạng khi giữ Ctrl hoặc kéo chuột | Gate đóng, input được nhả; reconnect không phục hồi held state cũ |
| Sleep/wake và thay DPI | Đóng/hồi phục theo spec, thông báo rõ; tạo lại Robot khi cần |
| Mất focus, minimize, chuyển cửa sổ | Không gửi input ngoài điều kiện cho phép, không kẹt modifier/nút |
| Mạng chậm | Đóng gate đúng, RELEASE_ALL theo chuyển trạng thái; không thực thi input epoch cũ |
| Đóng app ở mọi phase | Không cửa sổ treo hoặc worker/listener tồn tại ngoài vòng đời đã công bố |

### Số đo hiệu năng

- Ghi CPU, heap, FPS, throughput, RTT và độ trễ input-to-frame p50/p95. RTT không thay cho input-to-frame.
- Mục tiêu trên cấu hình LAN tham chiếu: 10–12 FPS, input-to-frame p95 dưới 150 ms. Ghi cách đo, số mẫu, loại thao tác, máy và mạng để kết quả tái hiện được.
- Giữ mặc định 12 FPS, rộng tối đa 1280, JPEG 0.6 và timeout hiện có khi đo lần đầu. Nếu không đạt, xác định nút thắt và sửa hoặc trình bày giới hạn có bằng chứng; không âm thầm hạ tiêu chí.
- Test throttle mạng cần giới hạn có căn cứ từ kích thước frame, hủy được, không làm hỏng handshake ngoài chủ đích của test.

### Điều kiện đạt

Có báo cáo kết quả thực tế cho cả ba tầng; mọi ca chưa chạy phải đánh dấu chưa kiểm chứng. Không dùng số lượng test xanh thay cho chứng cứ ổn định phần cứng. Lỗi kẹt input, sai generation, rò tài nguyên hoặc UI freeze phải được sửa và chạy lại ca liên quan trước phát hành.

## Task 4.3 — Đóng gói, tài liệu và review phát hành

### Công việc

- Build một `remote-desktop.jar`, chạy với JDK >= 21 không cần IDE/Maven ở máy sử dụng. Kiểm tra `java -jar target/remote-desktop.jar --help`, `--server`, `--client` và chooser khi không có tham số.
- README hướng dẫn vai trò, cổng/Windows Firewall, LAN tin cậy, đồng ý chia sẻ, dừng khẩn bằng nút Dừng và các giới hạn đã thử nghiệm.
- Ghi ma trận OS/JDK/DPI, lệnh build/test, kết quả nghiệm thu, cách đo hiệu năng và hạn chế còn lại. Xóa/cập nhật các đoạn README còn ghi chức năng đã làm là chưa có.
- Cập nhật kế hoạch tổng để phản ánh task đã hoàn thành và bằng chứng tương ứng. Không đánh dấu spike hoặc nghiệm thu LAN hoàn thành nếu chỉ chạy fake.
- Review cuối riêng biệt với lượt tự rà implementation, đối chiếu toàn branch với spec và các invariant vòng đời/input. Nếu dùng reviewer agent, tuân theo cấu hình project và chỉ ghi nhận review thực sự đã chạy.

### Điều kiện phát hành

- `./mvnw.cmd -q verify` xanh và CI Java 21 xanh.
- JAR chạy được trên cả hai máy thử nghiệm với các luồng sử dụng chính.
- Báo cáo 4.2 đầy đủ, các giới hạn công bố rõ.
- Không còn finding chưa xử lý về kẹt input, sai generation/epoch, rò tài nguyên hoặc UI freeze.
- Có checklist và bằng chứng review cuối; không tự push, merge hoặc công bố bản phát hành chỉ vì task đóng gói đã xong.

## Rà tính nhất quán của kế hoạch

- Tổng phần việc còn lại: 9 task, gồm 6 task đợt 3 và 3 task đợt 4.
- Gate phần cứng nằm trước tích hợp GUI; gate ổn định nằm trước phát hành.
- Timeout shutdown là ngân sách chờ, không phải bằng chứng Robot đã nhả phím.
- Ownership start/shutdown ngăn tài nguyên tạo muộn bị bỏ quên.
- Kiểm thử hai JVM và hai máy bổ sung cho baseline cùng JVM, không thay bằng suy luận.
- Baseline 334 test chỉ chứng minh trạng thái trước các đợt này; mọi số đo và bảo đảm mới cần bằng chứng triển khai.
