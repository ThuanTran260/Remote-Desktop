# Review thiết kế Remote Desktop — vòng 4

Ngày: 2026-10-19. Đã chỉnh trực tiếp [spec](../specs/2026-10-19-remote-desktop-design.md) và thêm [kế hoạch triển khai](../plans/2026-10-19-remote-desktop-implementation.md). Chưa implement app, chưa commit.

## Phương pháp và skill

- Đã đọc skill `codebase-design` trong cache mattpocock-skills 1.3.1: đánh giá interface gồm cả quy tắc thứ tự/lỗi, không chỉ đếm số method. Đã giữ InputDevice/ScreenSource làm seam có hai adapter thực/fake; metadata concurrency nằm trong module.
- Đã đọc `postman:api-engineer` và nhánh `postman:api-documentation`: dùng spec hiện có làm hợp đồng, kiểm tra consumer/error behavior, phân biệt bằng chứng review với test runtime. Không tạo OpenAPI/HTTP collection hoặc workspace Postman cho giao thức TCP nhị phân này.
- Đã tìm trong repo và các thư mục skills/cache nhưng không thấy `receiving-code-review` hay `superpowers:writing-plans`; không tuyên bố đã chạy hai skill đó. Kế hoạch TDD được viết trực tiếp theo hợp đồng đã rà.
- Không dùng agent phụ; kiểm tra tuần tự cùng một workspace.

## Phát hiện chính và xử lý

| Phát hiện trong bản sửa 3 | Hậu quả | Sửa ở baseline 4 |
| --- | --- | --- |
| beginSession chỉ xếp task, submit kiểm tra activeGen ngay | Mất lệnh đầu tiên trước khi worker chạy BEGIN | begin nhận generation nguyên tử, submit được nhận ngay |
| end tới trước task BEGIN | BEGIN có thể bật lại generation đã đóng | Không có task BEGIN; end chuyển ENDING và chặn begin mới |
| volatile check rồi ghi activeGen và END không idempotent | End cũ có thể can thiệp phiên mới, END lặp vượt suất dự phòng | Một khóa metadata, generation, một lifecycle slot, kết quả end có tính idempotent |
| newSingleThreadExecutor được mô tả queue 256 | Không thực thi giới hạn đã cam kết | Worker/deque hữu hạn rõ ràng |
| orTimeout trên release future; mở reconnect sau 2 giây | Timeout trở thành completion giả, phiên mới được nhận khi input còn treo | Observer riêng; reconnect chỉ sau release thành công |
| Chỉ finally cleanup với get(500) | Thời gian đợi cleanup queue chưa tính, close wrapper có thể flush | Deadline tuyệt đối từ close, raw sockets trước, fallback executor rejection |
| CAS pending chưa quy định register cho chủ nhận | Khoảng hở trách nhiệm đóng khi shutdown/handover | transfer kiểm tra deadline, register, finally và lock order |
| closeIf chỉ kiểm tra ACTIVE | Timeout cũ đóng phiên vừa nhận frame/PONG | Kiểm tra timestamp/operation hiện tại ngay khi quyết định |
| imageStalled ban đầu false, chỉ bật theo cạnh true→false | Frame đầu đến sớm có thể không bật điều khiển | hasFrame và snapshot EDT riêng |
| Test S/20 đòi frame timeout thắng | Watchdog write 10 giây có thể thắng 15 giây | Tách hai ca test theo deadline thực |
| ServerSocket không accept được dùng để giả lập connect kẹt | TCP connect có thể hoàn tất qua backlog | Fake connector có latch + test read socket thật riêng |

## Ba quyết định người viết đề xuất

1. **Giữ 3 gói cuộn/sự kiện:** có giới hạn rõ, test 45 và 100 notch; bỏ phần nguyên vượt 60 trước tạo packet, giữ phần lẻ. Đây là đánh đổi trải nghiệm, không áp dụng cho nhả phím/nút.
2. **Bỏ mở Kết nối sau 2 giây:** chỉ cảnh báo. Nếu release trả lỗi thì khóa đến khi khởi động lại; nếu chỉ chậm rồi thành công thì có thể khôi phục đúng generation.
3. **Giữ ô riêng cho END:** không dùng chung cho mọi RELEASE_ALL. Không nhận generation mới khi ENDING và end lặp không thêm task, nên một ô đủ.

## Bằng chứng đã chạy

Lệnh:

```text
python docs/superpowers/reviews/check_input_lifecycle_model.py
```

Kết quả:

```text
Original design counterexamples: 2
Revised bounded model: 58 states, 203 transitions; invariants PASS
Targeted traces: immediate submit, close during command, stale end, full queue, release failure PASS
```

Mô hình duyệt không gian hữu hạn gồm hai generation, queue hai phần tử, một worker và các bước metadata nguyên tử. Nó kiểm tra không chạy lệnh cũ sau END, không nhận generation mới khi còn giữ input cũ, END vẫn hoạt động khi queue đầy và release lỗi chặn begin. Không mô hình hóa JVM memory model, thread starvation, native Robot, TCP hoặc UI thật; mô hình hóa atomic operation không thay thế implementation khóa đúng.

Repo lúc review chưa có pom hoặc source Java. Không chạy `mvn test`/LAN vì chưa có app để kiểm thử. Không ghi nhận G0–G8 đạt chỉ từ mô hình này.

Đã kiểm tra 3 tài liệu Markdown: code fence cân bằng, 5 liên kết local đều tồn tại, không có trailing whitespace. Tìm các biểu thức implementation cũ `activeGen =`, `releasedFuture.orTimeout` và chính sách mở kết nối sau 2 giây: không còn trong spec hiện hành. Git vẫn báo `?? docs/`; chưa stage hoặc commit.

## Nguồn kỹ thuật đã đối chiếu

- [JDK 21 Executors](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executors.html): newSingleThreadExecutor dùng queue không giới hạn; một worker không đồng nghĩa có bounded queue.
- [JDK 21 CompletableFuture](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html): orTimeout có thể hoàn tất exceptionally chính future đó và trả về cùng future. Vì thế observer timeout không được sửa future chứng nhận release thật.
- [JDK 21 Robot](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/java/awt/Robot.html): thao tác có thể phụ thuộc quyền/nền tảng và capture không nên chạy EDT; hành vi DPI/key repeat vẫn phải kiểm thử trên máy Windows hỗ trợ.

## Kết luận review

Baseline đủ cụ thể để bắt đầu triển khai v1 LAN theo các task/gate. Đây là chấp nhận thiết kế có điều kiện kiểm thử, không phải chứng nhận sản phẩm ổn định. Các trường hợp OS/native bị treo không thể được chữa bằng thêm Java timeout; phần mềm phải đóng mạng, chặn phiên mới và báo trạng thái thật.
