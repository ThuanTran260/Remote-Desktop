# Kết quả spike Robot trên Windows (Task 3.1)

Trạng thái: **chưa chạy**. Mẫu kết quả cho task 3.1 trong [kế hoạch đợt 3](../plans/2026-10-20-wave3-gui-and-robot.md). Không điền kết quả suy luận; ca nào chưa thực hiện giữ "chưa chạy".

Các trường và bảng gốc bên dưới là mẫu/kết quả v1, giữ nguyên. Lượt v2 được ghi riêng ở cuối file; chưa chạy không phải PASS và không kế thừa kết quả từ SHA/jar v1. Hợp đồng hiện hành: [UI/CLI/H2](../specs/2026-11-02-host-viewer-ui-cli-h2-design.md), mục 3–4; hướng dẫn chạy ở [README](../../../README.md).

Người thực hiện:
Ngày/giờ:
Commit (`git rev-parse --short HEAD`):
Lệnh: `java -cp target/classes;target/test-classes com.ltm.rd.spike.RobotSpike`

## Cấu hình

| Mục | Máy A | Máy B |
| --- | --- | --- |
| OS | | |
| JDK (`java -version`) | | |
| Kích thước logic (`bounds`) | | |
| Kích thước ảnh capture | | |
| Scale DPI | | |

## Kết quả theo DPI (ghi "không thử được" nếu máy không hỗ trợ)

| Ca | 100% | 125% | 150% |
| --- | --- | --- | --- |
| Ảnh capture so với bounds logic | chưa chạy | chưa chạy | chưa chạy |
| Header FRAME hợp lệ (`checkFrameHeader`) | chưa chạy | chưa chạy | chưa chạy |
| Bốn góc (sai lệch pixel logic) | chưa chạy | chưa chạy | chưa chạy |
| Click, kéo, wheel hai chiều | chưa chạy | chưa chạy | chưa chạy |
| Gõ chữ, Backspace, mũi tên | chưa chạy | chưa chạy | chưa chạy |
| Giữ Shift/Ctrl/Alt | chưa chạy | chưa chạy | chưa chạy |
| Đóng cửa sổ khi đang giữ: không kẹt phím/nút | chưa chạy | chưa chạy | chưa chạy |

## Key repeat

| Phép thử | Ký tự kỳ vọng | Ký tự thực tế | Lặp đôi? |
| --- | --- | --- | --- |
| Giữ Backspace 2 giây | chưa chạy | chưa chạy | chưa chạy |
| Giữ một phím chữ 2 giây | chưa chạy | chưa chạy | chưa chạy |

## Quyết định chính sách repeat

Chưa quyết định. Điền `SEND_ALL` hoặc `FIRST_PRESS_ONLY` kèm số đo làm căn cứ.

## Ghi chú và cấu hình không thử được

## Lượt v2 riêng — CHƯA CHẠY

Run/người chạy/thời gian: CHƯA CHẠY. SHA build/final, dirty state, SHA-256 jar A/B, Windows/JDK/resolution/DPI và artifact path: CHƯA CHẠY. Hướng A-Host/B-Viewer: CHƯA CHẠY; hướng B-Host/A-Viewer: CHƯA CHẠY. Không điền PIN/token/phím nhạy cảm vào log/ảnh; che mã. Chỉ đổi verdict khi có kết quả thật và bằng chứng xem lại được.

H1 chạy Robot thật trên cả A/B: bounds/capture, DPI 100/125/150% nếu hỗ trợ, nhả input và key repeat 2 giây: CHƯA CHẠY. Ghi lệnh spike thực tế của SHA được chạy; không tái sử dụng lệnh/classpath cũ khi chưa kiểm. Policy SEND_ALL/FIRST_PRESS_ONLY và căn cứ số đo: CHƯA CHẠY. H1 không thay H2.

Kết quả các gate tự động và giới hạn chung được ghi một nơi ở [báo cáo gate v2](2026-11-02-v2-automated-gates.md); mục này chỉ ghi lượt thử riêng của mẫu.
