# Checkpoint quota — Host/Viewer B1–B2

Ngày 2026-11-02. Người dùng yêu cầu dừng ở khoảng 5% quota, lưu công việc rồi chỉ tiếp tục khi có lệnh bắt đầu/continue sau reset. Coordinator quan sát CLI chạm **5% quota 5 giờ** lúc khoảng 13:35 +07; đã dừng công việc mới. CLI hiển thị reset lúc 17:29; mốc reset không phải lệnh tự tiếp tục.

## Trạng thái code và plan

- [Plan thực thi](../plans/2026-11-02-host-viewer-b1-b2.md) đã commit ở `5d9eb565f12445e80fcc651834813a8b8bc424a3`. Người dùng đã duyệt Phần 3–4 và B1/B2, ghi ở `25b6a53`; không hỏi lại phương pháp hoặc thiết kế đã được duyệt.
- P0 hoàn tất, tự review và coordinator preflight đã chốt. Codex Sol xhigh đã được xác nhận bằng receipt; worker P0 đã release và completion đã ack. Không giao lại P0, A1–B0 hoặc đợt chuẩn bị v2.
- **B1 chưa hoàn tất, chưa commit.** Diff hiện chỉ đổi Protocol/PacketRules và ba test packet tương ứng. Session/runtime/UI/CLI vẫn chưa chuyển vai; không sử dụng diff này như bản v2 hoạt động được.
- Coordinator đã đối chiếu XML/exit: RED 245 test/101 failures/0 errors/0 skipped; GREEN 245/0/0/0 sau đổi một-byte version và bảng nhận. Đây là focused evidence, không phải full verify, coverage v2, review độc lập hoặc jar v2. Target/jar cũ không được dùng làm bằng chứng mới. [Báo cáo phần việc B1](../reports/2026-11-02-host-viewer-b1-partial.md).
- B2, G1, review cuối Astra, hai JVM Robot thật và H2 hai máy đều **chưa chạy** trong wave này. Kết quả 912 test/coverage/Astra của đợt chuẩn bị thuộc source `e58afc7`, không chứng nhận diff B1.

## Phục hồi và quyền sở hữu

- Repo `E:/luyentaphe/LapTrinhMang/DoAn-LTM`, branch `feat/wave3-4-desktop-v1`; BASE_B1 là `5d9eb565f12445e80fcc651834813a8b8bc424a3`. Checkpoint docs có thể tạo commit kế tiếp, nhưng không thay BASE review hoặc commit source đang dở.
- Orca executable `C:/Users/Admin/AppData/Local/Programs/orca/resources/bin/orca.exe`; Run `run_7e40ca2f04a4`; runtime `c6f9572f-1ad5-4b6a-8116-3fc1bc678387`; coordinator `term_c30697b4-aa49-4250-9fb8-d6c6420b02ec`; workspace selector `identity:wt2:local:419fd186-c5a0-42ac-b8d6-0e1be9f95658`.
- P0: Task `task_1ed07c3b7a2b`, Dispatch `ctx_ec6b7d78e7e2`, succeeded/released.
- B1: Task `task_3f7504bb05aa`, Dispatch `ctx_2f464c705d0e`, terminal `term_23bbb1d1-9b43-4de9-ab8b-37c7a9d7353f`, effective `codex / gpt-6.1-sol / xhigh`. Worker đã dừng implementation/tests, lưu report/checkpoint nhưng chạm usage limit trước khi gửi worker_done. Coordinator không giả lập completion: thực hiện quota-stop đã được người dùng yêu cầu; receipt xác nhận stopped/ptyKilled, Task failed/incomplete. Sau đó chạy đúng nextAction worker-release: released, không còn writer B1. Archive transcript của B1 báo unavailable; các file evidence thực tế vẫn còn, không khẳng định đã archive transcript.
- Orca restart trong lúc chốt: runtime mới `da548701-a9a2-465e-a329-849b15a98035`; Run/Task/Dispatch vẫn giữ identity. Việc dừng dựa trên quota-stop được yêu cầu và thông báo provider hết usage, không suy từ stale_status rằng process đã chết. Source/index/target được bàn về coordinator và để yên chờ reset, không có Maven chạy nền theo report.
- Artifact cục bộ giữ trong `.superpowers/sdd/2026-11-02-host-viewer-b1-b2/`: `progress.md`, `task-1-brief.md`, `task-1-report.md`, `task-1-checkpoint.md`, `task-1-evidence/`, launch/check/release receipts. Đây là scratch bị Git ignore nhưng giữ nguyên trên workspace, không dọn/xóa sau checkpoint.
- Coordinator lưu thêm `coordinator-quota-working-tree.patch` và `quota-file-hashes.json` cho năm source/test file + test-map mới. Các thay đổi B1 đều unstaged; checkpoint docs/report là commit riêng. Không có full B1 commit hoặc source diff bị mất.

## Tiếp tục lần tới

Chờ người dùng yêu cầu bắt đầu/continue sau reset. Đọc checkpoint này, ledger, report/checkpoint B1 và diff hiện có; giữ mọi phần đang dở, không reset/amend hoặc khởi động lại từ đầu. Nếu Orca đã restart, kiểm runtime/coordinator mới và bind đúng Run; không dùng handle cũ như thể còn live. Retry cùng Task B1 với Dispatch mới qua `--retry-of` sau khi xác nhận Dispatch cũ đã failed/settled, dùng exact workspace và Codex Sol xhigh. Không dùng lại lifecycle IDs của lượt đã settle.

Tiếp tục các vòng RED→GREEN còn lại của Task B1: Host prepare/lifecycle/slot/input, Viewer session/buffer/ControlView, runtime/UI/CLI/README và test map/tám race. Khi toàn B1 xanh và self-review xong mới tạo một commit hành vi nguyên khối. Coordinator review cố định BASE_B1..HEAD bằng Sol xhigh và verify độc lập; chỉ sau đạt mới B2 Sol high (rename cơ học), G1 Sol xhigh (PIT1.19.6 + adapter1.2.3, 25×20 race, harness hai JVM), tài liệu/jar/status và Astra high review cuối. H2 hai Windows desktop theo hai hướng vẫn cần máy/người/lịch và bằng chứng thật.

Giữ các ràng buộc: một source/index/HEAD owner và một Maven/target owner; không Claude/Luna/model rẻ/helper agent; không push/merge/rebase/amend, test skip hoặc giảm assertions/timeouts/repetition. Sửa lỗi sau review bằng commit riêng, không viết lại lịch sử. Không commit B1 nửa vời để tiện checkpoint.
