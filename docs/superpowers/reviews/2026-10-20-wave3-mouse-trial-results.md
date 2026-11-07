# Kết quả thử chuột hai máy thật (Task 3.4)

Trạng thái: **chưa chạy**. Mẫu kết quả cho task 3.4 trong [kế hoạch đợt 3](../plans/2026-10-20-wave3-gui-and-robot.md). Ca chưa thực hiện giữ "chưa chạy".

Các trường và bảng gốc bên dưới là mẫu/kết quả v1, giữ nguyên. Lượt v2 được ghi riêng ở cuối file; chưa chạy không phải PASS và không kế thừa kết quả từ SHA/jar v1. Hợp đồng hiện hành: [UI/CLI/H2](../specs/2026-11-02-host-viewer-ui-cli-h2-design.md), mục 3–4; hướng dẫn chạy ở [README](../../../README.md).

Người thực hiện:
Ngày/giờ:
Commit (`git rev-parse --short HEAD`):
OS/JDK hai máy:

| Ca | 100% | 125% | 150% |
| --- | --- | --- | --- |
| Bốn góc và tâm không lệch quá 1 pixel logic | chưa chạy | chưa chạy | chưa chạy |
| Click đầu chỉ lấy focus; click hai điều khiển bình thường | chưa chạy | chưa chạy | chưa chạy |
| Kéo; kéo ra ngoài ảnh rồi thả | chưa chạy | chưa chạy | chưa chạy |
| Hai nút đồng thời | chưa chạy | chưa chạy | chưa chạy |
| Cuộn chuột và touchpad | chưa chạy | chưa chạy | chưa chạy |
| Resize, thu nhỏ rồi mở lại: tọa độ đúng | chưa chạy | chưa chạy | chưa chạy |

| Ca không phụ thuộc DPI | Kết quả |
| --- | --- |
| Mất focus (Alt+Tab) khi giữ nút: nút nhả | chưa chạy |
| Rút mạng hoặc Ngắt khi giữ nút: nút nhả, không kẹt | chưa chạy |

## Ghi chú

## Lượt v2 riêng — CHƯA CHẠY

Run/người chạy/thời gian: CHƯA CHẠY. SHA build/final, dirty state, SHA-256 jar A/B, Windows/JDK/resolution/DPI và artifact path: CHƯA CHẠY. Hướng A-Host/B-Viewer: CHƯA CHẠY; hướng B-Host/A-Viewer: CHƯA CHẠY. Không điền PIN/token/phím nhạy cảm vào log/ảnh; che mã. Chỉ đổi verdict khi có kết quả thật và bằng chứng xem lại được.

Viewer gửi chuột tới Host; bốn góc/tâm, click lấy focus, kéo/cuộn/nhiều nút, resize/minimize, DPI và nhả nút khi mất focus/mạng trên Host: CHƯA CHẠY cho từng hướng. Giữ toàn bộ ca gốc áp dụng, ghi ca/DPI/artifact riêng; cấu hình thiếu vẫn CHƯA CHẠY.

Kết quả các gate tự động và giới hạn chung được ghi một nơi ở [báo cáo gate v2](2026-11-02-v2-automated-gates.md); mục này chỉ ghi lượt thử riêng của mẫu.
