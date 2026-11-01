# Checkpoint Host/Viewer v2 preparation — 2026-11-02

## Quy tắc dừng và tiếp tục

**ĐÃ DỪNG TẠI CHECKPOINT QUOTA.** CLI coordinator báo 6% trong lúc chốt, giữ phần dự phòng khoảng 5% thay vì mở một lượt review dài có thể vượt ngưỡng. **Sau reset chỉ tiếp tục khi người dùng nói “bắt đầu”; không tự chạy lại.** Reset hiển thị 05:33 giờ máy (Asia/Bangkok). Đây là quan sát quota CLI, không suy từ token budget.

Tài liệu tích hợp đã commit ở `11258cd`; commit checkpoint cập nhật này và ledger ghi HEAD bàn giao cuối. Source vẫn bằng `e58afc7` đã verify. Sáu Dispatch của Run đã settle/release, không còn agent active hoặc Delivery chưa xử lý tại lúc dừng. Gói review cuối và brief đã chuẩn bị; chưa có reviewer Astra được giao.

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

Các worker trên đã release trước ack; không dùng lại lifecycle IDs cũ để gửi completion. Reviewer APPROVED thiết kế có nghĩa đủ điều kiện trình người dùng, **chưa phải người dùng duyệt UI/CLI/H2**. [Báo cáo hiện tại](../reports/2026-11-02-host-viewer-v2-preparation.md), [đề xuất cụ thể](../specs/2026-11-02-host-viewer-ui-cli-h2-design.md).

## Việc đang chờ ở snapshot này

1. Review toàn đợt Astra **chưa chạy/chưa có verdict** tại lúc chuẩn bị checkpoint. Kiểm ledger và fleet trước khi khởi chạy để biết có Dispatch mới sau snapshot này hay không; không tạo reviewer trùng.
2. Sau final review: xử lý finding theo một fix wave + scoped re-review nếu cần; ghi đúng gate còn thiếu nếu chạm quota.
3. Người dùng duyệt Phần 3–4 rồi mới lập plan/triển khai B1/B2. Source hiện vẫn `Protocol.VERSION = 1`; chưa có UI/CLI/Robot/hai máy v2.
4. PIT, 500 race trên SHA v2 cuối và H2 thực tế còn chưa chạy. Không dùng 912 headless tests để đánh dấu các gate đó PASS.

Hai Minor Task 1: U+FEFF trước `fix:` trong subject có thể ảnh hưởng parser Conventional Commits; hai file witness rỗng được worker dẫn không tồn tại. Giữ nguyên SHA và đính chính bằng chứng, không dựng witness lịch sử. Xem `task-1-evidence-clarification.md` và review cleanup.

## Tiếp tục sau lời “bắt đầu”

1. Đọc checkpoint, ledger `progress.md`, plan và báo cáo; kiểm `git status --short`, `git log -5 --oneline`, source/index còn bằng e58afc7 hay không.
2. Dùng đúng Orca executable đã ghi: `orchestration run-use --id run_2b84ac203dc6 --json`; `orchestration worker-list --run run_2b84ac203dc6 --include-remote --json`; `orchestration check --run run_2b84ac203dc6 --json`.
3. Xử lý mọi Delivery trước ack; nếu reviewer đã có, đọc `worker-show/worker-read` theo đúng Dispatch trong ledger. `unverifiable` không cho phép stop/retry/release. Task failed/stopped có bằng chứng mới được `--retry-of` với placement/model rõ ràng.
4. Tiếp tục đúng gate còn thiếu; không giao lại cleanup/draft/re-review đã đạt, không chạy lại suite chỉ để lấy xanh trên source không đổi.
5. Giữ một source/index owner và một Maven owner; worker chỉ đọc với báo cáo riêng có thể song song. Không tự tiếp tục sau reset nếu chưa có lời “bắt đầu”.
