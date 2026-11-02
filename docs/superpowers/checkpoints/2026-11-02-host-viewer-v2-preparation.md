# Checkpoint Host/Viewer v2 preparation — 2026-11-02

## Trạng thái mới nhất

**Đợt chuẩn bị đã hoàn tất, review cuối Astra high PASS / APPROVED.** Task `task_4515d13f1ba1` / Dispatch `ctx_c1a587518156` xét range `3694407..26b0491`, không có Critical/Important hoặc blocker mới; hai Minor của Task 1 được công khai và phân xử. [Báo cáo review cuối](../reviews/2026-11-02-host-viewer-v2-preparation-final-review.md). **Gate còn thiếu là người dùng duyệt Phần 3–4 trước plan/code B1/B2.**

Source vẫn bằng `e58afc7` đã verify 912/0/0/0; các thay đổi sau đó chỉ là tài liệu. Bảy Dispatch của Run đã settle/release; completion cuối đã được xác thực và ack. Không có reviewer đang chạy hoặc fix wave còn mở trong đợt này. Phiên bản sản phẩm vẫn wire v1, chưa có triển khai/kiểm chứng v2.

## Lịch sử quota và quy tắc tiếp tục

Lần trước checkpoint được chuẩn bị khi CLI báo 6%, rồi xác nhận `only 5% left` trong receipt gốc. Người dùng đã nói “continue” sau reset; Run được nối lại bằng terminal coordinator mới và review cuối đã hoàn tất. Quan sát khi reviewer chạy: quota tuần còn 22%, reset hiển thị 14:47 ngày 4/11; đây là quan sát lịch sử, không phải bảo đảm quota ở lần đọc sau.

Quy tắc vẫn áp dụng: **khi quota còn khoảng 5%, lưu checkpoint và dừng; sau reset chỉ tiếp tục khi người dùng yêu cầu bắt đầu/continue, không tự chạy lại.** Kiểm quota hiển thị thực tế, không suy từ token budget. Resume quota không thay thế phê duyệt thiết kế UI/CLI/H2.

## Nơi làm việc và thẩm quyền

- Repo: `E:/luyentaphe/LapTrinhMang/DoAn-LTM`, branch `feat/wave3-4-desktop-v1`.
- Plan: [chuẩn bị v2](../plans/2026-11-02-host-viewer-v2-preparation.md).
- Run: `run_2b84ac203dc6`; Orca: `C:/Users/Admin/AppData/Local/Programs/orca/resources/bin/orca.exe`.
- Workspace selector: `identity:wt2:local:419fd186-c5a0-42ac-b8d6-0e1be9f95658`.
- Artifact/ledger: `.superpowers/sdd/2026-11-02-host-viewer-v2-preparation/`; không đọc/ghi workspace plan khác.
- Entry SHA: `369440779658424e631b4d0a965f870dde5ea7b9`; plan commit `c48bfda`; code hiện tại `e58afc77a41870d110cfb456a536f6253644cd94`. Các thay đổi sau code này chỉ là tài liệu nếu source equality được xác nhận.
- Worker thực thi dùng `gpt-6.1-sol`, effort theo độ khó; review cuối có thể dùng `gpt-6-astra`. Không Luna/model rẻ hơn/Claude; không push/merge/amend/rebase hoặc tự chạy Maven thứ hai.

## Đã hoàn tất — không giao lại

| Việc | Bằng chứng | Lifecycle đã settle |
| --- | --- | --- |
| Sửa pending ticker ở core mặc định | `e58afc7`; RED 21 lỗi count/22 lượt, GREEN 89/0; chỉ bốn file source/test | implement `task_2ccac3ce5336 / ctx_39d71560fcdf` |
| Review cleanup | PASS / APPROVED; 0 Critical/Important, hai Minor metadata/evidence đã công khai | review `task_0e924712d95e / ctx_a837c85ea088` |
| Verify độc lập | `clean verify` trên e58afc7: 912/0/0/0; năm gate ≥0.80, log/CSV/XML đã lưu ngoài target | coordinator; đã kết thúc, không còn Maven của lượt này |
| Draft UI/CLI/H2 | `--role host\|viewer` + `--address`, UI theo vai, ma trận H2 hai hướng; chỉ artifact riêng | draft `task_7d3b3d04728b / ctx_ca499ce06cc8` |
| Builder review | Ban đầu NEEDS_FIXES: ControlView đổi phase phải ở B1, và thiếu cắt mạng trước ACTIVE | `task_4074090f32c9 / ctx_42bc7bfad23a` |
| Sửa draft vòng 1 | Hai finding sửa trong snapshot r1, không đổi source | `task_a8f6b05044c3 / ctx_00a72b079e2d` |
| Re-review vòng 1 | PASS / APPROVED; cả hai ADDRESSED, không breakage mới | `task_de81bae272a8 / ctx_2ff268cc85e0` |
| Review cuối Astra high | PASS / APPROVED, 0 Critical/Important, hai Minor đã phân xử; không yêu cầu fix wave | `task_4515d13f1ba1 / ctx_c1a587518156` |

Các worker trên đã release trước ack; không dùng lại lifecycle IDs cũ để gửi completion. Reviewer APPROVED thiết kế có nghĩa đủ điều kiện trình người dùng, **chưa phải người dùng duyệt UI/CLI/H2**. [Báo cáo hiện tại](../reports/2026-11-02-host-viewer-v2-preparation.md), [đề xuất cụ thể](../specs/2026-11-02-host-viewer-ui-cli-h2-design.md).

## Việc tiếp theo

1. Trình người dùng duyệt [Phần 3–4](../specs/2026-11-02-host-viewer-ui-cli-h2-design.md): cú pháp CLI breaking, hành vi cửa sổ và cách nghiệm thu H2 hai hướng. Đây là đề xuất đã review, chưa phải user approval.
2. Sau khi được duyệt, lập plan riêng và triển khai B1/B2 theo quyền sở hữu/kiểm chứng hiện hành; không dùng lại Task/Dispatch đã hoàn tất.
3. Source hiện vẫn `Protocol.VERSION = 1`; chưa có UI/CLI/Robot/hai máy v2. Chưa được merge/push hoặc chứng nhận v2 sẵn sàng.
4. PIT, 500 race trên SHA v2 cuối, hai JVM và H2 thực tế còn chưa chạy. Không dùng 912 headless tests để đánh dấu các gate đó PASS.

Hai Minor Task 1: U+FEFF trước `fix:` trong subject có thể ảnh hưởng parser Conventional Commits; hai file witness rỗng được worker dẫn không tồn tại. Giữ nguyên SHA và đính chính bằng chứng, không dựng witness lịch sử. Xem `task-1-evidence-clarification.md` và review cleanup.

## Cách tiếp tục đúng gate

1. Đọc checkpoint, ledger `progress.md`, plan và báo cáo; kiểm `git status --short`, `git log -5 --oneline`, source/index còn bằng e58afc7 hay không.
2. Dùng đúng Orca executable đã ghi: `orchestration run-use --id run_2b84ac203dc6 --json`; `orchestration worker-list --run run_2b84ac203dc6 --include-remote --json`; `orchestration check --run run_2b84ac203dc6 --json`.
3. Xử lý mọi Delivery trước ack. Tất cả bảy Dispatch nêu trên đã hoàn tất/release ở snapshot này; không giao reviewer cuối trùng. Nếu có attempt mới hơn, kiểm live authority trong ledger/fleet; `unverifiable` không cho phép stop/retry/release.
4. Tiếp tục tại phê duyệt thiết kế rồi plan B1/B2; không giao lại cleanup/draft/re-review/final review đã đạt, không chạy lại suite chỉ để lấy xanh trên source không đổi.
5. Giữ một source/index owner và một Maven owner; worker chỉ đọc với báo cáo riêng có thể song song. Không tự tiếp tục sau reset nếu chưa có lời “bắt đầu”.
