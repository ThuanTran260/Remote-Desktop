# Kết quả chạy JAR trên hai máy (Task 4.3)

Trạng thái: **chưa chạy**. Mẫu kết quả cho task 4.3 trong [kế hoạch đợt 4](../plans/2026-10-20-wave4-hardening-and-release.md). Chỉ chép `target/remote-desktop.jar` sang từng máy; không IDE hoặc Maven.

Các trường và bảng gốc bên dưới là mẫu/kết quả v1, giữ nguyên. Lượt v2 được ghi riêng ở cuối file; chưa chạy không phải PASS và không kế thừa kết quả từ SHA/jar v1. Hợp đồng hiện hành: [UI/CLI/H2](../specs/2026-11-02-host-viewer-ui-cli-h2-design.md), mục 3–4; hướng dẫn chạy ở [README](../../../README.md).

Người thực hiện:
Ngày/giờ:
Commit (`git rev-parse --short HEAD`):
OS/JDK hai máy:

| Luồng | Máy A | Máy B |
| --- | --- | --- |
| `--help` | chưa chạy | chưa chạy |
| Không tham số (chooser) | chưa chạy | chưa chạy |
| `--server` và `--client --host <IP>` | chưa chạy | chưa chạy |
| Kết nối và điều khiển chuột/bàn phím | chưa chạy | chưa chạy |
| Dừng/Ngắt, kết nối lại | chưa chạy | chưa chạy |
| Đóng cửa sổ giữa phiên | chưa chạy | chưa chạy |

Script smoke (`scripts/smoke-jar.cmd`, `scripts/smoke-jar.sh`): chưa chạy.

## Ghi chú

## Lượt v2 riêng — CHƯA CHẠY

Run/người chạy/thời gian: CHƯA CHẠY. SHA build/final, dirty state, SHA-256 jar A/B, Windows/JDK/resolution/DPI và artifact path: CHƯA CHẠY. Hướng A-Host/B-Viewer: CHƯA CHẠY; hướng B-Host/A-Viewer: CHƯA CHẠY. Không điền PIN/token/phím nhạy cảm vào log/ảnh; che mã. Chỉ đổi verdict khi có kết quả thật và bằng chứng xem lại được.

Jar v2/SHA-256 giống nhau ở A/B: CHƯA CHẠY. --help, chooser, --role host, --role viewer --address IP_HOST, preset không connect, legacy bị từ chối, exit 0/2/4/1 theo điều kiện launcher: CHƯA CHẠY. Không PIN argv/log; Hủy/Dừng/Ngắt, đóng ControlFrame so với form chính, shutdown PID/thread/port/input và reconnect mỗi hướng: CHƯA CHẠY. Lệnh/exit/stdout/stderr thực tế và script smoke tương ứng SHA: CHƯA CHẠY; không suy smoke mới từ đường dẫn script lịch sử.

Kết quả các gate tự động và giới hạn chung được ghi một nơi ở [báo cáo gate v2](2026-11-02-v2-automated-gates.md); mục này chỉ ghi lượt thử riêng của mẫu.
