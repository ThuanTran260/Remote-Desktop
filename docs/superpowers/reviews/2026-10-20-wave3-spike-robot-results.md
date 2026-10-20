# Kết quả spike Robot trên Windows (Task 3.1)

Trạng thái: **chưa chạy**. Mẫu kết quả cho task 3.1 trong [kế hoạch đợt 3](../plans/2026-10-20-wave3-gui-and-robot.md). Không điền kết quả suy luận; ca nào chưa thực hiện giữ "chưa chạy".

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
