# Remote Desktop

[![CI](https://github.com/ThuanTran260/Remote-Desktop/actions/workflows/ci.yml/badge.svg)](https://github.com/ThuanTran260/Remote-Desktop/actions/workflows/ci.yml)

Ứng dụng điều khiển máy tính trong LAN bằng Java, Socket TCP, Swing và `java.awt.Robot`, không có thư viện runtime bên ngoài. Đồ án môn Lập trình mạng.

## Vai trò protocol v2

| Vai trò | Chạy trên máy | Công việc |
| --- | --- | --- |
| Host (`--role host`) | Máy bị điều khiển | Lắng nghe, hiện IP/mã 6 số, gửi ảnh và thực thi input |
| Viewer (`--role viewer`) | Máy điều khiển | Chủ động nối, nhập IP/mã của Host, nhận ảnh và gửi input |

Người ngồi ở Host đưa mã là đồng ý chia sẻ, không có hộp thoại xác nhận thứ hai. Nhập mã trong cửa sổ Viewer, không đưa mã vào argv, biến môi trường hoặc nhật ký.

```text
Host (lắng nghe)                         Viewer (chủ động nối)
InputExecutor / Robot  <--- input ------- InputCapture
ScreenSender / Robot   --- ảnh/JPEG ----> FrameReceiver / RemotePanel
```

Host lắng nghe TCP 5001 cho lệnh và 5000 cho ảnh. Token 16 byte ghép kênh ảnh vào phiên đã xác thực. Gate chỉ mở sau khi frame đúng generation/epoch đã được vẽ. Stall khóa input và nhả held state; mất một kênh kết thúc cả phiên, không reconnect riêng kênh ảnh.

Host dùng một input executor suốt đời runtime, source/geometry mới theo từng phiên. Mã mới chỉ hiện sau khi socket, worker và input release đều hoàn tất bình thường. Host giữ BUSY khi prepare hoặc release chưa dừng; lỗi nhả input yêu cầu khởi động lại ứng dụng.

## Build và test

Cần JDK 21 trở lên; Maven Wrapper có sẵn, CI dùng JDK 21.

```bash
# Linux / macOS / Git Bash
./mvnw -B -ntp verify

# Windows PowerShell / cmd
.\mvnw.cmd -B -ntp verify
```

Build tạo `target/remote-desktop.jar`. Test tự động chạy headless bằng adapter giả và socket loopback. Chúng không chứng nhận Robot, focus, DPI hay mạng thật trên hai Windows desktop. Nghiệm thu thủ công H1/H2 v2 trên máy thật đã được chủ dự án xác nhận hoàn thành ngày 2026-11-10; kho mã không lưu nhật ký chạy chi tiết (xem [bản ghi xác nhận](docs/superpowers/reports/2026-11-10-host-viewer-manual-acceptance.md)).

## Chạy

```bash
java -jar target/remote-desktop.jar --help
java -jar target/remote-desktop.jar
java -jar target/remote-desktop.jar --role host
java -jar target/remote-desktop.jar --role viewer --address 192.168.1.20
```

Không tham số mở chooser. Host tự start listener; Viewer chỉ được điền sẵn địa chỉ, phải bấm Kết nối. `--address` chỉ hợp lệ với `--role viewer` tường minh. Có thể thêm cùng `--cmd-port 6001 --img-port 6000` ở cả hai máy; port phải khác nhau và trong 1..65535. Mỗi cờ chỉ dùng một lần, role phân biệt hoa/thường, không nhận `--role=host`, positional argument hoặc cờ mã. Help vẫn validate toàn bộ đối số.

Windows Firewall cần cho phép inbound hai cổng tại Host; Viewer không mở listener. Chọn IPv4 LAN phù hợp của Host từ form; địa chỉ ví dụ chưa phải địa chỉ đã đo.

| Cú pháp v1 theo máy cũ | Cú pháp v2 |
| --- | --- |
| `--server` trên máy điều khiển | `--role viewer`, nhập IP máy bị điều khiển |
| `--client --host IP_SERVER_V1` trên máy bị điều khiển | `--role host`, bỏ IP máy điều khiển cũ |
| `--host IP` không role | Chọn role tường minh; không rewrite tự động |

`--server`, `--client`, `--host` bị từ chối ở mọi vị trí, không alias. Không đổi máy móc IP Server v1 thành `--address`, vì hướng kết nối đã đảo. VERSION 2 từ chối peer v1 bằng BAD_VERSION; dùng cùng một bản jar trên hai máy.

## Giao diện và ownership

Host sở hữu SharingBar không nhận focus, mã và nút Dừng. Viewer sở hữu ControlFrame, Hủy trước ACTIVE và Ngắt khi ACTIVE. Đóng cửa sổ điều khiển chỉ ngắt phiên; đóng form chính shutdown runtime. Cleanup chạy nền; kết quả socket/worker/input chưa sạch được cảnh báo trên EDT trước khi thoát.

- `common/`: giao thức, lifecycle, writer, ticker, timeout.
- `host/`: HostCore/HostSession, handshake, input, screen sender, Robot adapter.
- `viewer/`: ViewerSession, kết nối, frame receiver, gate/epoch, control state.
- `ui/`: Controller/Snapshot, runtime, HostFrame/ViewerFrame, form state và capture trên EDT.

Trạng thái theo SHA và các gate còn thiếu: [báo cáo gate v2 tập trung](docs/superpowers/reviews/2026-11-02-v2-automated-gates.md). Bảng test được giữ trong [test map](docs/superpowers/reviews/2026-11-02-v2-test-map.md). PR #3 đã được merge vào `main` (`cd63fcf`, 2026-11-08). Nghiệm thu thủ công GĐ2 đã được chủ dự án xác nhận ngày 2026-11-10; desktop gate tự động, PIT ở SHA cuối và review cuối chưa thuộc xác nhận đó, xem [bản ghi 2026-11-10](docs/superpowers/reports/2026-11-10-host-viewer-manual-acceptance.md).

[Thiết kế Host/Viewer v2](docs/superpowers/specs/2026-11-01-host-viewer-design.md) và [hợp đồng UI/CLI/H2](docs/superpowers/specs/2026-11-02-host-viewer-ui-cli-h2-design.md) là đặc tả hiện hành.

## Giới hạn và quy ước

Chỉ dùng LAN tin cậy: ảnh, lệnh và mã truyền không mã hóa. Phạm vi là một phiên, màn hình chính, Windows; không điều khiển UAC, màn hình khóa hoặc cửa sổ quyền admin cao hơn. Chưa có FRAME_ACK/freshness v3, relay sản phẩm hay tự reconnect.

Code/comment/commit tiếng Anh, tài liệu tiếng Việt. UTF-8 không BOM, LF; Java indent 4, XML/YAML/JSON indent 2 theo `.editorconfig`. Commit theo Conventional Commits; CI phải xanh trước merge. Không hạ coverage gate, timeout/repetition hoặc disable test để lấy xanh.
