# Review và nghiệm thu đợt 3–4 (2026-10-21)

Reviewer: Claude Opus 5.5 (coordinator, không phải reviewer sonnet riêng theo Step 7 của plan đợt 3).
Commit được review: `7273d17` (branch `feat/wave3-4-desktop-v1`).

## Kết luận nghiệm thu

- **Đợt 3: CHƯA ĐẠT.** Điều kiện đạt (plan đợt 3, dòng 728) gồm 3 phần; mới đạt 1:
  - `./mvnw.cmd -q verify` xanh: **đạt** (exit 0, 524 test, 0 fail/err/skip).
  - Kết quả HUMAN 3.1, 3.4, 3.5, 3.6 đã điền: **chưa** (4 file `2026-10-20-wave3-*-results.md` đều "chưa chạy").
  - Review 3.2 và 3.6 không còn finding mở: **chưa** (review 3.6 dưới đây còn M1 mở).
- **Đợt 4: CHƯA BẮT ĐẦU.** 4.1, 4.2, 4.3 chưa có commit; 3 file kết quả đợt 4 đều "chưa chạy".

## Bằng chứng tự động

- `./mvnw.cmd -q verify` → exit 0; surefire: tests=524 fail=0 err=0 skip=0.
- `java -jar target/remote-desktop.jar --help` → in USAGE, exit 0.
- Smoke: chạy `--server` và `--client --host 127.0.0.1` cùng lúc trên một máy, 8 giây, cả hai tiến trình còn sống, stderr rỗng; sau đó dừng theo PID. Không kiểm chứng giao diện hiển thị đúng hay kết nối.

## Finding review Task 3.6

- **M1 (mở) — `SharingBar` nhận được focus.** `ui/SharingBar.java` là `JFrame` focusable, nút *Dừng* là component focusable duy nhất. Khi phiên ACTIVE, `setVisible(true)` trên Windows thường kích hoạt cửa sổ này, nên phím Robot từ máy điều khiển đi vào thanh: gõ Space/Enter sẽ bấm *Dừng* và cắt phiên, các phím khác bị mất. Sửa: `setFocusableWindowState(false)` và `setAutoRequestFocus(false)` trong constructor (chuột vẫn bấm được nút). Cần xác nhận trong H2.
- **L1 — `SharingBar.showFor` gọi `pack()` và `setLocation` mỗi 50 ms.** Chỉ cần làm khi text đổi hoặc lần hiện đầu tiên.
- **L2 — Đóng `ServerFrame` giữa phiên không gọi `capture.disconnecting()`.** `closeControl()` chỉ `dispose()`. Việc nhả input vẫn xảy ra phía Client khi phiên đóng (`ClientSession.inputReleased`), nên chấp nhận được; ghi lại để thử ở ca "Đóng từng cửa sổ giữa phiên".

Không thấy vấn đề với: mã thoát `Launcher` (0/1/2/4/STAY_RUNNING), init ngoài EDT trong `GuiApp`, shutdown có budget rồi `System.exit(0)`, `RateSampler` chia theo thời gian thực và reset theo generation, `ClosingWatch`, `LocalAddresses`.
