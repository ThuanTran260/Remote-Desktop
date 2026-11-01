# Nghiệm thu chuẩn bị Host/Viewer v2

Ngày 2026-11-02. Run Orca `run_2b84ac203dc6`, branch `feat/wave3-4-desktop-v1`. Phạm vi: sửa pending ticker registration và chuẩn bị thiết kế UI/CLI/H2. Bản triển khai vẫn dùng wire v1; chưa triển khai B1/B2 và chưa push/merge.

## Cleanup mặc định đã nghiệm thu

Code SHA `e58afc77a41870d110cfb456a536f6253644cd94`, base `c48bfda61fa8813e577c49563a21cbf9dc4b3fae`. `ServerCore` nay giữ handle pending registration và gỡ trong shutdown dùng chung, trước khi đóng ticker. Không đổi `Ticker.close`, public API, deadline, gói tin, điều kiện ba future trả slot hoặc UI/CLI.

Worker **Sol high** chứng minh RED: 22 lượt, 21 thất bại đúng vì registration còn 1. Sau sửa, 89 test tập trung đạt. Hai chỗ tắt pending sweep trong fixture B0 đã được bật lại; giữ mọi assertion BUSY/PIN/input/thread/port và 20 lượt mỗi ca latch.

Coordinator chạy mới `mvnw.cmd -q clean verify` trên đúng SHA code: **912 test, 0 failures/errors/skipped**, exit 0. Reviewer **Sol xhigh** đọc độc lập source/diff và XML: **spec PASS, clean code PASS / APPROVED**, không có finding Critical/Important. Giới hạn: cửa sổ hẹp giữa công bố ticker và công bố handle được kiểm bằng proof thứ tự volatile; các test race hiện hữu không ép riêng đúng cửa sổ đó.

| Lớp mục tiêu | Nhánh phủ | Tỷ lệ |
| --- | ---: | ---: |
| server.ServerSession | 76/83 | 91,57% |
| server.HandshakeHandler | 24/24 | 100% |
| host.ControlledSide | 10/10 | 100% |
| viewer.ControlSide | 70/78 | 89,74% |
| client.ClientSession | 77/83 | 92,77% |

JaCoCo và Enforcer đạt. Kết quả được lưu ngoài target bằng log, CSV, summary và toàn bộ XML coordinator; không chạy lại suite đã đạt trên source không đổi. [Review cleanup](../reviews/2026-11-02-pending-ticker-review.md).

## Hai ghi nhận Minor và đính chính

1. Subject commit e58afc7 có U+FEFF trước `fix:`. Raw commit xác nhận `EF BB BF 66 69 78 3A`. Đây là ngoại lệ quy ước Conventional Commits, có thể ảnh hưởng parser yêu cầu type ở đầu chuỗi; không ảnh hưởng source/tree/runtime. Giữ SHA theo ràng buộc không amend/rebase; message sau dùng UTF-8 không BOM.
2. Báo cáo worker nói đã lưu hai file rỗng `task-1-red-production-diff.txt` và `task-1-final-status.txt`, nhưng chúng không có trong gói bàn giao. Không tạo lại rồi xem như chứng cứ lịch sử. RED log/exit/XML, GREEN và diff cuối có thật; trạng thái sạch hiện tại và verify mới được ghi riêng, không dùng để chứng nhận snapshot cũ.

Reviewer xếp hai điểm này Minor, không yêu cầu sửa production. Bản ghi `task-1-evidence-clarification.md` trong workspace của Run đính chính báo cáo worker được giữ nguyên. Không có tuyên bố tuyệt đối hết bug hoặc sẵn sàng merge v2.

## Thiết kế UI/CLI/H2

Worker Sol high đã giao [đề xuất Phần 3–4](../specs/2026-11-02-host-viewer-ui-cli-h2-design.md). Builder review Sol high chặn một Important (đổi phase của ControlView phải ở B1, không phải B2) và một Minor (thiếu cắt mạng thật trước ACTIVE). Một worker sửa cả hai trong vòng 1; scoped re-review Sol high kết luận **PASS / APPROVED, cả hai ADDRESSED, không có breakage mới**. [Lịch sử review và re-review](../reviews/2026-11-02-host-viewer-ui-cli-h2-review.md).

Cú pháp đề xuất là `--role host|viewer` và `--address`, từ chối cờ v1 với hướng dẫn migration theo đúng vai máy. Host tự lắng nghe/hiện IP và mã; Viewer nhập địa chỉ/mã; SharingBar thuộc Host, ControlFrame thuộc Viewer. ControlView đổi sang `client.ClientPhase` cùng producer/consumer/UI/test ngay B1; B2 chỉ rename sang `viewer.ViewerPhase`. H2 ghi rõ cắt mạng trước và sau AUTH/ACTIVE, deadline theo phase, hai hướng máy và CHƯA CHẠY khi thiếu bằng chứng. Thiết kế vẫn **chờ người dùng duyệt**; source vẫn v1, chưa có lệnh v2 hoặc kết quả H2.

## Quyền sở hữu và việc còn lại

Worker code và designer chạy song song, chỉ worker code được ghi source/index/HEAD/target. Reviewer chỉ đọc, coordinator sở hữu một lượt full verify; designer chỉ ghi artifact riêng. Các Dispatch đã hoàn tất được release trước ack; terminal người dùng tiếp quản được giữ theo receipt.

Gate Task 1 và builder/re-review Task 2 đã đạt; review toàn đợt Astra vẫn chờ. Cần trình Phần 3–4 cụ thể để duyệt trước plan/code B1/B2. PIT/500 race trên SHA v2 cuối, hai JVM và H2 hai máy Windows thật còn là gate sau; không được thay bằng các test headless trong đợt này. Pending ticker của cấu hình mặc định đã xử lý trong SHA nêu trên; nhận định chưa xử lý ở báo cáo A1–B0 là lịch sử của SHA cũ.

Theo chỉ dẫn mới, khi quota còn khoảng 5% phải ghi checkpoint và dừng; sau reset không tự chạy, chỉ tiếp tục khi người dùng nói “bắt đầu”. Checkpoint ghi rõ review/gate nào còn thiếu, không đổi chúng thành PASS để kết thúc phiên.

Đã dừng an toàn khi CLI báo 6% trong lúc chốt phần dự phòng. [Checkpoint để tiếp tục](../checkpoints/2026-11-02-host-viewer-v2-preparation.md) ghi SHA, sáu Dispatch đã release, gói diff/brief và lệnh phục hồi. **Review toàn đợt Astra chưa khởi chạy**, chưa có verdict cuối; không triển khai B1/B2 trong lần dừng này.

## Coordinator rulings (ordered ledger extract)

- Ruling: keep the user's established feature checkout and separate draft writers from the sole source/index owner — independent report work is authorized while another worktree would add unnecessary integration — cost if wrong: recoverable local commit rework; no shared branch push.
- Ruling: retain/close the pending Registration at ServerCore rather than clearing all Ticker registrations globally — resource ownership matches the existing session-registration pattern and preserves shared Ticker semantics — cost if wrong: late-publication races need a follow-up, guarded by existing deterministic race seams plus added count assertions.
- Ruling: execute the already-described cleanup requirement without another plan-approval pause; UI/CLI/H2 remains a concrete proposal requiring approval before B1/B2 — the user explicitly said start after the proposed two tasks, while the existing spec withholds approval of the unwritten UI design — cost if wrong: small isolated cleanup/docs rework; no premature v2 implementation.
- Ruling: Sol high for implementation/design, Sol xhigh for concurrency review, Sol high for design builder review, Astra high for final whole-wave review — task complexity and the user's explicit model constraint override generic cheap-model routing — cost if wrong: additional review time or higher token cost, never silent model fallback.
- Ruling: reuse the source-identical accepted baseline as historical evidence and run new focused red/green plus one fresh coordinator full verify for this source change — no unexplained baseline failure exists and duplicate unchanged suites add no evidence — cost if wrong: an environment change is discovered at the new verification gate before acceptance.
- Ruling: retain the unpublished e58afc7 SHA despite its leading commit-message U+FEFF, disclose the Conventional Commits parser risk and require BOM-free UTF-8 for later messages — both reviewer and raw subject verify a metadata-only issue, while this plan forbids history rewriting — cost if wrong: future commit-message tooling may require a separately authorized history cleanup before publication; runtime/tree/test evidence is unaffected.
- Ruling: correct the handover claim about the two absent empty files in a coordinator clarification rather than manufacture historical witnesses or rerun passing tests — the saved RED log/exit/XML and GREEN/diff exist, but the claimed empty production/status snapshots do not — cost if wrong: exact historical zero-output snapshots remain unavailable; current clean status cannot certify that old moment.
- Ruling: use a fresh authoritative Orca fix Dispatch after the original designer and reviewer were released, instead of generic SDD same-agent resume — settled-terminal ownership is governed by Orca and all prior context is in scoped draft/review artifacts — cost if wrong: one fresh context, not duplicate source owners or stale lifecycle authority.
- Ruling: for an ignored-document fix, hand the re-reviewer immutable r0/current artifacts plus a saved git diff --no-index rather than invent Git commit SHAs — no tracked/product change exists in this round — cost if wrong: artifact provenance would need reconstruction, guarded by saved before/after hashes and full diff.
