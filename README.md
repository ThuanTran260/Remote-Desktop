# Remote Desktop

[![CI](https://github.com/ThuanTran260/Remote-Desktop/actions/workflows/ci.yml/badge.svg)](https://github.com/ThuanTran260/Remote-Desktop/actions/workflows/ci.yml)

Phần mềm điều khiển máy tính từ xa trong mạng LAN, viết bằng **Java thuần**: Socket TCP, `java.awt.Robot` và Swing, không dùng thư viện runtime bên ngoài. Đồ án môn Lập trình mạng.

> **Trạng thái:** đang phát triển. Đã xong các module nền (codec giao thức, hàng đợi ghi lệnh, bộ thực thi thao tác). Kết nối mạng, phiên làm việc và giao diện **chưa chạy được end-to-end**. Tiến độ chi tiết xem [kế hoạch triển khai](docs/superpowers/plans/2026-10-19-remote-desktop-implementation.md).

## Vai trò

| Vai trò | Máy | Việc làm |
|---|---|---|
| **Server** (`--server`) | Máy **điều khiển** | Mở cổng lắng nghe, hiện mã 6 số, nhận ảnh màn hình, gửi thao tác chuột/phím |
| **Client** (`--client`) | Máy **bị điều khiển** | Nhập IP + mã của Server; bấm *Kết nối* là đồng ý chia sẻ. Gửi ảnh màn hình, thực thi thao tác nhận được |

Vai trò TCP được xác định theo bên nào mở cổng lắng nghe, nên máy điều khiển là Server.

## Kiến trúc tóm tắt

```
 Client (bị điều khiển)                         Server (điều khiển)
 ┌──────────────────────┐   cổng 5001: lệnh    ┌──────────────────────┐
 │ InputExecutor ◀──────┼──────────────────────┼─ InputCapture        │
 │ (Robot)              │   AUTH, PING/PONG... │  (chuột/phím Swing)   │
 │                      │                      │                      │
 │ ScreenSender ────────┼──────────────────────┼▶ FrameReceiver       │
 │ (chụp + JPEG)        │   cổng 5000: ảnh     │  → RemotePanel       │
 └──────────────────────┘                      └──────────────────────┘
```

- Hai kênh TCP tách riêng, để ảnh dung lượng lớn không làm chậm lệnh chuột.
- Giao thức nhị phân tự định nghĩa: `[type: 1 byte][length: int32][payload]`, big-endian.
- Đặc tả đầy đủ: [docs/superpowers/specs/2026-10-19-remote-desktop-design.md](docs/superpowers/specs/2026-10-19-remote-desktop-design.md).

## Yêu cầu

- JDK 21 trở lên
- Không cần cài Maven: dự án có kèm Maven Wrapper (`mvnw`)

## Build và test

```bash
# Linux / macOS / Git Bash
./mvnw verify

# Windows (cmd / PowerShell)
mvnw.cmd verify
```

Lệnh trên chạy toàn bộ test (headless, không cần màn hình) và tạo file `target/remote-desktop.jar`.

## Chạy

```bash
java -jar target/remote-desktop.jar --help
java -jar target/remote-desktop.jar --server [--cmd-port 5001] [--img-port 5000]
java -jar target/remote-desktop.jar --client [--host <IP>]
```

Hiện tại `--server` và `--client` chỉ in `not implemented yet`.

## Cấu trúc mã nguồn

```
src/main/java/com/ltm/rd/
├── Launcher.java, LaunchOptions.java   # điểm khởi động, đọc tham số
├── common/    # giao thức: Packet, PacketIO, CommandWriter, Channel, ...
├── client/    # máy bị điều khiển: InputExecutor, InputDevice, ...
└── server/    # máy điều khiển (chưa có)
docs/superpowers/
├── specs/     # đặc tả thiết kế
├── plans/     # kế hoạch triển khai theo task
└── reviews/   # báo cáo review thiết kế
```

## Giới hạn

- Chỉ dùng trong **LAN tin cậy**: ảnh, lệnh và mã xác thực truyền **không mã hóa**.
- Phiên bản 1 chỉ hỗ trợ một phiên, màn hình chính, Windows.
- Không điều khiển được cửa sổ chạy quyền admin, UAC hoặc màn hình khóa.
- Windows Firewall phải cho phép hai cổng lắng nghe của Server (mặc định 5000 và 5001).

## Quy ước phát triển

- Commit theo [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `build:`, `docs:`, `chore:`).
- Mỗi thay đổi làm trên một branch riêng rồi mở PR vào `main`; CI phải xanh trước khi merge.
- Code, comment và commit message viết bằng tiếng Anh; tài liệu viết bằng tiếng Việt.
