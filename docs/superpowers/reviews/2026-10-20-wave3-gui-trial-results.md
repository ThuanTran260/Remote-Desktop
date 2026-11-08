# Kết quả thử giao diện và launcher hai máy thật (Task 3.6)

Trạng thái: **chưa chạy**. Mẫu kết quả cho task 3.6 trong [kế hoạch đợt 3](../plans/2026-10-20-wave3-gui-and-robot.md). Ca chưa thực hiện giữ "chưa chạy".

Các trường và bảng gốc bên dưới là mẫu/kết quả v1, giữ nguyên. Lượt v2 được ghi riêng ở cuối file; chưa chạy không phải PASS và không kế thừa kết quả từ SHA/jar v1. Hợp đồng hiện hành: [UI/CLI/H2](../specs/2026-11-02-host-viewer-ui-cli-h2-design.md), mục 3–4; hướng dẫn chạy ở [README](../../../README.md).

Người thực hiện:
Ngày/giờ:
Commit (`git rev-parse --short HEAD`):
OS/JDK hai máy:

| Ca | Kết quả |
| --- | --- |
| `java -jar target/remote-desktop.jar --help` in usage, thoát 0 | chưa chạy |
| Không tham số: chọn vai trò; Server hiện IP, cổng, mã | chưa chạy |
| Client kết nối; thanh chia sẻ nổi trên cùng có peer và nút Dừng | chưa chạy |
| FPS, KiB/s từng kênh, RTT cập nhật khoảng 1 giây | chưa chạy |
| Hủy khi kết nối tới IP không tồn tại: phản hồi ngay, về IDLE | chưa chạy |
| Ngắt và Dừng bấm đồng thời; kết nối lại ngay; mã cũ bị từ chối | chưa chạy |
| `--host`, `--cmd-port`, `--img-port`; cổng bị chiếm báo lỗi rõ | chưa chạy |
| Đóng từng cửa sổ giữa phiên: `jps` không còn tiến trình | chưa chạy |

## Ghi chú

## Lượt v2 riêng — CHƯA CHẠY

Run/người chạy/thời gian: CHƯA CHẠY. SHA build/final, dirty state, SHA-256 jar A/B, Windows/JDK/resolution/DPI và artifact path: CHƯA CHẠY. Hướng A-Host/B-Viewer: CHƯA CHẠY; hướng B-Host/A-Viewer: CHƯA CHẠY. Không điền PIN/token/phím nhạy cảm vào log/ảnh; che mã. Chỉ đổi verdict khi có kết quả thật và bằng chứng xem lại được.

Chooser và --role host/--role viewer đúng máy; --address chỉ preset Viewer, không auto-connect. Host hiện IP/mã/SharingBar; Viewer có ControlFrame. Cờ --server/--client/--host phải lỗi migration; không PIN argv/log. Help/exit, lỗi nhập không bị snapshot cũ xóa, Hủy/Dừng/Ngắt, bind lỗi và thống kê: CHƯA CHẠY. Đóng ControlFrame chỉ ngắt phiên, giữ form Viewer; đóng form chính mới shutdown. Kiểm input/focus ngay sau ACTIVE và cleanup từng phase: CHƯA CHẠY.

Kết quả các gate tự động và giới hạn chung được ghi một nơi ở [báo cáo gate v2](2026-11-02-v2-automated-gates.md); mục này chỉ ghi lượt thử riêng của mẫu.
