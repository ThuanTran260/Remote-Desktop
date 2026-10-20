# Review cuối toàn branch (Task 4.3)

Trạng thái: **chưa chạy**. Chỉ điền khi review độc lập đã thực sự chạy; không ghi thay cho lượt tự rà của implementer.

Reviewer (agent/model hoặc người):
Commit được review:
Ngày/giờ:

## Checklist đối chiếu spec và invariant

| Hạng mục | Kết quả | Finding |
| --- | --- | --- |
| Generation: không có đường input của generation cũ tới phiên mới | chưa chạy | |
| Epoch: kiểm tra epoch cùng gate trước offer | chưa chạy | |
| Nhả input: `releaseControlInput`, END, `inputReleased()` không bị giả hoàn tất | chưa chạy | |
| Ownership: `AppRuntime`, `ServerCore`, `ClientSession`, `InputExecutor` | chưa chạy | |
| Shutdown: ngân sách tổng, checkpoint start/shutdown, báo trung thực | chưa chạy | |
| EDT: không I/O, chờ future hay Robot trên EDT | chưa chạy | |
| Log: không mã, token, phím, ảnh | chưa chạy | |
| Rò tài nguyên: luồng `rd-*`, socket, ticker | chưa chạy | |
| UI freeze | chưa chạy | |

## Finding và xử lý
