# Kết quả thử bàn phím hai máy thật (Task 3.5)

Trạng thái: **chưa chạy**. Mẫu kết quả cho task 3.5 trong [kế hoạch đợt 3](../plans/2026-10-20-wave3-gui-and-robot.md). Ca chưa thực hiện giữ "chưa chạy".

Các trường và bảng gốc bên dưới là mẫu/kết quả v1, giữ nguyên. Lượt v2 được ghi riêng ở cuối file; chưa chạy không phải PASS và không kế thừa kết quả từ SHA/jar v1. Hợp đồng hiện hành: [UI/CLI/H2](../specs/2026-11-02-host-viewer-ui-cli-h2-design.md), mục 3–4; hướng dẫn chạy ở [README](../../../README.md).

Người thực hiện:
Ngày/giờ:
Commit (`git rev-parse --short HEAD`):
OS/JDK hai máy:

| Ca | Kết quả |
| --- | --- |
| Gõ chữ, Backspace, Tab, mũi tên | chưa chạy |
| Shift/Ctrl+C/V trong Notepad máy Client | chưa chạy |
| Key repeat: giữ Backspace và phím chữ 2 giây, không lặp đôi | chưa chạy |
| Alt+Tab khi giữ Ctrl: Ctrl nhả trên Client | chưa chạy |
| Rút mạng khi giữ Ctrl: Client tự nhả; kết nối lại không giữ phím cũ | chưa chạy |

Chính sách repeat cuối cùng (`SEND_ALL` hoặc `FIRST_PRESS_ONLY`): chưa quyết định.

## Ghi chú

## Lượt v2 riêng — CHƯA CHẠY

Run/người chạy/thời gian: CHƯA CHẠY. SHA build/final, dirty state, SHA-256 jar A/B, Windows/JDK/resolution/DPI và artifact path: CHƯA CHẠY. Hướng A-Host/B-Viewer: CHƯA CHẠY; hướng B-Host/A-Viewer: CHƯA CHẠY. Không điền PIN/token/phím nhạy cảm vào log/ảnh; che mã. Chỉ đổi verdict khi có kết quả thật và bằng chứng xem lại được.

Notepad chạy trên Host; Viewer gõ chữ/Backspace/Tab/mũi tên và Ctrl+C/V. Host nhả Ctrl khi Viewer mất focus hoặc mất mạng; reconnect không giữ phím cũ. Key repeat 2 giây/policy/số ký tự thực tế: CHƯA CHẠY cho từng hướng. Không đổi SEND_ALL chỉ từ giả định chưa đo.

Kết quả các gate tự động và giới hạn chung được ghi một nơi ở [báo cáo gate v2](2026-11-02-v2-automated-gates.md); mục này chỉ ghi lượt thử riêng của mẫu.
