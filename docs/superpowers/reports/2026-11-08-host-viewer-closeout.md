# Báo cáo closeout Host/Viewer v2 tại `8f1f701`

Ngày 2026-11-08, nhánh `feat/wave3-4-desktop-v1`, SHA code/test: **`8f1f70171d0157b7d27e6c267f9c671b9a5fb598`**. Báo cáo này ghi các gate tự động đã chạy thật và phần chưa làm. **Không phải** nghiệm thu GĐ2, review cuối hay xác nhận merge-ready. Bảng đầy đủ và liên kết bằng chứng nằm ở [báo cáo gate v2](../reviews/2026-11-02-v2-automated-gates.md); các liên kết `.superpowers` là artifact ignored cục bộ.

## Kết quả gate tự động (Windows/headless, Temurin 21.0.13+9, Maven 3.9.12)

- **Clean verify:** 1240 test / 73 lớp, 0 failure/error/skip, native 0. Nhánh phủ: `HostSession` 77/83, `HandshakeHandler` 26/26, `ControlledSide` 25/28, `ControlSide` 71/78, `ViewerSession` 88/97 (cả năm ≥0.80).
- **Race:** 25 batch, 8 method × 500 = 4000 lượt, 0 bad.
- **Full PIT:** native 0, 2 giờ 01 phút; 437 mutant = 344 KILLED + 84 TIMED_OUT + 7 SURVIVED + 2 NO_COVERAGE; XML SHA-256 `927db114d119c067f73c87237a824df844c60a5a9ee9470ace8b17d7ba8f1637`. Con số 428 (98%) của console gộp 84 TIMED_OUT, không phải KILLED literal. Hoàn tất thực thi, chưa phải mutation acceptance.
- **Jar đóng gói:** SHA-256 `1273b6f90ad6f37363734b9524a0821e33bd7a1a4bd286befa8fac2da46361a6` (282401 byte). Headless `--help` exit 0, `--server` exit 2, `--role viewer` exit 4, cả ba khớp mong đợi. Đây là kiểm launcher trên một máy, không phải H2. Bằng chứng: [execution.json](../../../.superpowers/sdd/2026-11-07-host-viewer-closeout/t7/jar-8f1f701-jdk21/execution.json).

## Xét 93 mutant không bị diệt (T6, đọc tĩnh, không chạy gì)

- EQUIVALENT 9 (7 SURVIVED + 2 NO_COVERAGE), TIMEOUT-REAL 82, GAP 1 Minor, UNRESOLVED 1.
- **GAP Minor:** ordinal 129, `ViewerSession#tick:206` (biên điều kiện; test `HE:271-289` chạy đua với fallback `SO_TIMEOUT` cùng độ dài). Chưa sửa.
- **UNRESOLVED:** ordinal 143, `ViewerSession#tryStart:436` (assertion có phát hiện, nhưng không biết test nào làm PIT vượt giới hạn). Chưa sửa.
- Giới hạn: kết luận TIMEOUT là suy luận từ đọc source/test; giới hạn thời gian của PIT và thứ tự test không được quan sát. Hai mục trên là giới hạn đã biết, giữ nguyên theo chỉ thị NO MORE CODE (người dùng có thể thay đổi). Tổng hợp: [T6-summary](../../../.superpowers/sdd/2026-11-07-host-viewer-closeout/t6/T6-summary.md).

## Lịch sử giữ nguyên

PIT ở `1af1d6a` **THẤT BẠI** (native 1, 192 s, không XML/HTML) tại `ViewerSessionHandshakeEdgesTest#v1CloseMessagesSurviveTheRulesTable`; assertion thật của lỗi đó vẫn **UNKNOWN**. `8f1f701` thêm fence PING/PONG (test-only, +5 dòng). Một lần PIT xanh ở `8f1f701` không chứng minh đã sửa đúng nguyên nhân cũ. Số liệu ở `1af1d6a`, `02f7795` và `17c66ca` được giữ làm lịch sử theo SHA, không thừa kế sang `8f1f701`.

## Model receipt

- **T6:** requested Claude `sonnet`/`xhigh`; model/effort thực tế **chưa xác nhận từ màn hình**.
- **T7 (tài liệu và jar):** requested Claude `sonnet`/`high`; model/effort thực tế **chưa xác nhận từ màn hình**.
- **Các đợt trước:** Codex `gpt-6.1-sol` với effort theo task (receipt launch ghi `high`, `xhigh` hoặc `medium`; ví dụ kiểm tra cuối ở `8f1f701` ghi requested/effective `gpt-6.1-sol`/`high`). Chi tiết từng task nằm trong sổ tiến độ cục bộ; không suy requested thành effective.
- Người dùng duyệt ngày 2026-11-07 việc dùng Claude `sonnet` cho phần closeout; review cuối vẫn dự kiến là Codex `gpt-6-astra`/`high`.

## Chưa làm (CHƯA CHẠY)

- **Desktop gate:** năm ca Robot/hai JVM (`TwoJvmIntegrationTest`).
- **H1 và H2:** hai Windows thật, **30 phút ACTIVE + 50 vòng connect/disconnect MỖI hướng**.
- **Final Astra review** toàn branch (gồm tài liệu này).
- Chưa kiểm chứng: Linux/native, và nguyên nhân của 84 TIMED_OUT hiện hành cùng 90 TIMED_OUT lịch sử bằng thực nghiệm.
- Không có push, merge hay tuyên bố hoàn tất giai đoạn.
