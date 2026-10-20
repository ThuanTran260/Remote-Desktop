# Kết quả nghiệm thu vận hành và hiệu năng (Task 4.1 bước thử thật và 4.2)

Trạng thái: **chưa chạy**. Mẫu kết quả cho task 4.1 và 4.2 trong [kế hoạch đợt 4](../plans/2026-10-20-wave4-hardening-and-release.md). Không dùng số test xanh thay cho tầng 3; ca chưa thực hiện giữ "chưa chạy".

Người thực hiện:
Ngày/giờ:
Commit (`git rev-parse --short HEAD`):

| Mục | Máy A (Server) | Máy B (Client) |
| --- | --- | --- |
| OS | | |
| JDK | | |
| Độ phân giải, DPI | | |
| Mạng (có dây/Wi-Fi, router) | | |

## Tầng 1 — headless CI

| Mục | Kết quả |
| --- | --- |
| `./mvnw.cmd -q verify` và số test | chưa chạy |
| CI Java 21 | chưa chạy |

## Tầng 2 — hai JVM trên một máy

| Mục | Kết quả |
| --- | --- |
| `TwoJvmIntegrationTest` | chưa chạy |
| `AppRuntimeReconnectLoopTest`, `SlowNetworkRuntimeTest` | chưa chạy |

## Tầng 3 — hai máy Windows thật

| Ca | Kết quả | Bằng chứng (lệnh, số liệu) |
| --- | --- | --- |
| Shutdown: đóng cửa sổ và Ctrl+C ở từng phase | chưa chạy | |
| Hoạt động liên tục 30 phút | chưa chạy | |
| Kết nối/ngắt 50 lần (luồng `rd-*`, socket, heap sau GC mỗi 10 lần) | chưa chạy | |
| Cắt mạng khi giữ Ctrl hoặc kéo chuột | chưa chạy | |
| Sleep/wake | chưa chạy | |
| Thay DPI giữa phiên | chưa chạy | |
| Mất focus, minimize, chuyển cửa sổ | chưa chạy | |
| Mạng chậm (`clumsy` hoặc Wi-Fi yếu) | chưa chạy | |
| Đóng app ở mọi phase | chưa chạy | |
| Spec 5.2: cổng bị chiếm, hai Client đồng thời, không attach, token sai | chưa chạy | |
| Spec 5.2: Ngắt/Dừng đồng thời, nối lại ngay, không tạo được Robot, Hủy tới IP không tồn tại | chưa chạy | |

## Hiệu năng (LAN tham chiếu)

| Chỉ số | Giá trị | Mục tiêu |
| --- | --- | --- |
| FPS | chưa chạy | 10–12 |
| Throughput (KiB/s ảnh, lệnh) | chưa chạy | ghi nhận |
| RTT | chưa chạy | ghi nhận, không thay input-to-frame |
| CPU, heap sau warmup | chưa chạy | ghi nhận |
| Input-to-frame click: p50 / p95 / số mẫu | chưa chạy | p95 dưới 150 ms, từ 100 mẫu |
| Input-to-frame gõ phím: p50 / p95 / số mẫu | chưa chạy | p95 dưới 150 ms, từ 100 mẫu |

Cách đo, loại thao tác, máy và mạng: chưa ghi.

## Giới hạn và phát hiện còn lại
