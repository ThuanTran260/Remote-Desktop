# Checkpoint đợt 3–4 (tạm dừng vì quota)

Ngày: 2026-10-20. Branch: `feat/wave3-4-desktop-v1` (chưa push). Orca run: `run_f5c387ed0b73`.

## Đã xong (đã commit; `./mvnw.cmd -q verify` xanh, 524 test; `java -jar target/remote-desktop.jar --help` exit 0)

| Bước | Commit | Ghi chú |
| --- | --- | --- |
| Plan gốc | `3158e5b` | |
| B0 chi tiết hóa plan | `97a5172` | Interfaces, test, 7 mẫu kết quả trong `docs/superpowers/reviews/` |
| Seam dùng chung | `765253f` | `AdapterFactory`, `AdapterException` |
| 3.1 Robot adapters + spike (phần code) | `6a275df`, merge `05c1c9b` | Spike **chưa chạy** (cổng H1) |
| 3.2 AppRuntime/Controller/Snapshot | `31ba3cb` | Review R1 đã chạy |
| Sửa theo review R1 | `7eab22f` | H1, M1–M5, L1, L4, L7; bỏ L2, L3, L5, L6 |
| 3.3 RemotePanel/CoordinateMapper | `61705b9` | |
| 3.4 Chuột (phần tự động) | `f798063` | |
| MouseTrialSpike | `7ecd82c` | Công cụ thử tay chuột/phím |
| 3.5 Bàn phím (phần tự động) | `de33128` | `InputCapture.DEFAULT_REPEAT_POLICY = SEND_ALL`, chưa kiểm chứng |
| 3.6 GUI, launcher, thống kê (phần tự động) | `7273d17` | Robot thật nối vào launcher (người dùng đồng ý); các frame Swing **chưa từng mở trên màn hình**, CI không test được |

Lưu ý: `stash@{0}` "w34-task-3.6-wip ..." là bản test cũ đã lỗi thời (6 file test giờ đã nằm trong `7273d17`). Có thể `git stash drop stash@{0}` sau khi xác nhận.

## Cập nhật 2026-10-21

- Nghiệm thu tự động và review R2 (sonnet, Orca run `run_e96af2280557`) đã chạy: `docs/superpowers/reviews/2026-10-21-wave3-r2-review.md`; review coordinator: `2026-10-21-wave3-acceptance-review.md`.
- Verdict: đợt 3 **chưa đạt** (H1/H2 chưa chạy; mở M1 SharingBar focus, M2 JVM thoát âm thầm khi init chậm, M3 thiếu bằng chứng cấp cửa sổ; L1–L7).
- M1, M2, L1 (phần SharingBar) đã sửa ở `8e0fad0`; verify xanh 528 test. Chỉ kiểm bằng đọc code, cần H2 xác nhận.
- Bước tiếp: H1 → H2 (thêm ca "gõ ngay sau kết nối" và "máy nhiều adapter mạng ảo") → re-review phần sửa.

## Việc còn lại theo thứ tự

1. **H1 (người dùng):** spike Robot trên 2 máy Windows, DPI 100/125/150% nếu có.
   `java -jar C:\Users\Admin\orca\workspaces\DoAn-LTM\w34-robot-adapters\target\robot-spike.jar`
   (đóng gói lại nếu cần: `./mvnw.cmd -q test-compile` rồi `jar --create --file target/robot-spike.jar --main-class com.ltm.rd.spike.RobotSpike -C target/classes . -C target/test-classes com/ltm/rd/spike`).
   Ghi vào `2026-10-20-wave3-spike-robot-results.md`. Key repeat lặp đôi → đổi `InputCapture.DEFAULT_REPEAT_POLICY` sang `FIRST_PRESS_ONLY`.
2. **H2 (người dùng):** thử hai vai trò trên 2 máy:
   - build: `./mvnw.cmd -q verify` (copy `target/remote-desktop.jar` sang máy kia, JDK ≥ 21);
   - máy Server: `java -jar remote-desktop.jar --server` (hoặc không tham số rồi chọn Server), đọc IP và mã trên cửa sổ;
   - máy Client: `java -jar remote-desktop.jar --client --host <IP_SERVER>`, nhập mã 6 số, bấm Kết nối;
   - thử nhanh một máy: `java -cp "target/classes;target/test-classes" com.ltm.rd.spike.MouseTrialSpike loopback`.
   Ghi vào `2026-10-20-wave3-{mouse,keyboard,gui}-trial-results.md`.
3. **R2:** review sonnet cuối đợt 3 (cần H1 đạt), theo Step [REVIEW] cuối plan đợt 3.
4. **Đợt 4:** 4.1 shutdown (worker A) ‖ 4.2 tầng 2 harness hai JVM (worker B) → **H3** (người dùng: ma trận tầng 3) → 4.3 tài liệu/đóng gói (haiku cho tài liệu) → **R3** review toàn branch → push + PR (cần người dùng đồng ý).

## Tiếp tục lần sau

- Mở Claude Code tại repo, nói: "đọc `docs/superpowers/reviews/2026-10-20-wave3-4-checkpoint.md` và tiếp tục orchestration".
- Có thể dùng lại run `run_f5c387ed0b73` hoặc tạo run mới; worker mới nhận spec từ plan nên không cần ngữ cảnh cũ. Đóng các tab terminal worker cũ trong Orca (worker `worker-stop` không đóng được terminal "external").
- Worktree `C:\Users\Admin\orca\workspaces\DoAn-LTM\w34-robot-adapters` (branch `feat/w34-robot-adapters`, đã merge) giữ cho 4.2 hoặc xóa: `git worktree remove <path>` rồi `git branch -d feat/w34-robot-adapters`.
- Quy ước: implementer/reviewer `sonnet`, việc cơ học `haiku`; coordinator tự chạy verify sau mỗi task; reviewer không được `taskkill java.exe`; tin nhắn dừng gửi worker chỉ có hiệu lực ở checkpoint của nó.
