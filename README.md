# Remote Desktop

Ứng dụng điều khiển máy tính từ xa (Java thuần, không phụ thuộc thư viện ngoài).

## Yêu cầu

- JDK >= 21
- Maven 3.9+

## Build

```
mvn package
```

Kết quả: `target/remote-desktop.jar`.

## Chạy

```
java -jar target/remote-desktop.jar --help
```

Các tùy chọn: `--server`, `--client`, `--cmd-port N` (mặc định 5001), `--img-port N` (mặc định 5000), `--host IP` (chỉ dùng với `--client`).

## Lưu ý

Windows Firewall phải cho phép các cổng của server (cổng lệnh và cổng ảnh). Hướng dẫn chi tiết sẽ bổ sung sau.
