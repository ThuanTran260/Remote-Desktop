# Báo cáo closeout Host/Viewer v2 tại `e49c907`

Ngày 2026-11-08, nhánh `feat/wave3-4-desktop-v1`, SHA code cuối: **`e49c907b91244fbe02e3892b8c6497521cace0c9`**. Báo cáo này ghi các gate tự động đã chạy thật và phần chưa làm. **Không phải** nghiệm thu GĐ2, review cuối hay xác nhận merge-ready, và không tuyên bố hoàn tất giai đoạn. Bảng đầy đủ và liên kết bằng chứng nằm ở [báo cáo gate v2](../reviews/2026-11-02-v2-automated-gates.md); các liên kết `.superpowers` là artifact ignored cục bộ.

## Thay đổi sau phạm vi review T8 (`e7c0940..b6883e8`)

- `a866fc3` (fix): Viewer đặt image read timeout; Host không đặt timeout trên socket ảnh chỉ gửi (finding I1).
- `bd7c5b6` (test): ghim thứ tự đặt timeout và biên auth deadline (finding M1).
- `e49c907` (test): phủ lỗi setter timeout của kênh lệnh Host (GAP-1).

## Kết quả gate tự động (Windows/headless, Temurin 21.0.13, Maven 3.9.12)

- **Clean verify tại `e49c907`:** 1243 test, 0 failure/error/skip. Nhánh phủ (ngưỡng 0.80): `HostSession` 77/83, `HandshakeHandler` 26/26, `ControlledSide` 25/28, `ControlSide` 71/78, `ViewerSession` 88/97.
- **Race tại `e49c907`:** 25 batch, 8 method × 500 = 4000 lượt, 0 failure/error/skip.
- **Lượt trung gian tại `bd7c5b6`:** verify 1242 test xanh, race 4000 xanh, `ControlSide` 70/78. Mã nguồn `ControlSide` không đổi (71 → 70 → 71), nên một nhánh thay đổi giữa các lần chạy; nguyên nhân chưa kiểm chứng.
- **Full PIT tại `bd7c5b6`:** pitest-maven 1.19.6, native 0, khoảng 2 giờ 28 phút; 436 mutant = 343 KILLED + 84 TIMED_OUT + 7 SURVIVED + 2 NO_COVERAGE. Con số "Killed" 427 của console gộp 84 TIMED_OUT, không phải KILLED literal. XML được theo dõi tại [2026-11-08-pit-mutations.xml](../reviews/2026-11-08-pit-mutations.xml), SHA-256 `34aaf16cdfaa5ca0f102be843e9f7032f46b5220ef557fef7e3ba9ce5ea181bd`. Hoàn tất thực thi, chưa phải mutation acceptance.
- **Độ lệch SHA của PIT (người dùng duyệt ngày 2026-11-08):** spec thiết kế yêu cầu PIT chạy trên SHA cuối. PIT **không** được chạy lại ở `e49c907`, dù quy tắc của workflow đóng gói đáng lẽ yêu cầu. Lý do: `e49c907` chỉ thêm một test, `git diff bd7c5b6 e49c907 -- src/main pom.xml` rỗng, và các dòng `catch` được phủ không có mutant. Đây là độ lệch khỏi spec, không phải PIT ở `e49c907`.
- **Jar đóng gói tại `e49c907`:** SHA-256 `6a2c7314436839faed91be480f9a5e90617b79b4a4faa486a5e3e9a687e18252` (282354 byte). `mvnw -v` và build chạy trong cùng một script với `JAVA_HOME` Temurin 21.0.13 (báo `Java version: 21.0.13, vendor: Eclipse Adoptium`). Headless `--help` exit 0, `--server` exit 2, `--role viewer` exit 4, cả ba khớp mong đợi. Đây là kiểm launcher trên một máy, không phải H2. Bằng chứng: [execution.json](../../../.superpowers/sdd/2026-11-07-host-viewer-closeout/jar-e49c907-jdk21/execution.json), [mvnw-v.stdout.txt](../../../.superpowers/sdd/2026-11-07-host-viewer-closeout/jar-e49c907-jdk21/mvnw-v.stdout.txt). Receipt này thay receipt jar của T7; receipt T7 từng hiển thị Java 24.0.2 (T8 nêu) và vẫn chưa được xác minh.

## Xét 93 mutant không KILLED tại `bd7c5b6` (đọc tĩnh, không chạy gì)

- EQUIVALENT 9, TIMEOUT-REAL 83, UNRESOLVED 1, GAP 0.
- Theo T8, mutant TIMED_OUT được tính là đã bị phát hiện; các kết luận TIMEOUT-REAL xuất phát từ đọc code, chưa được xác lập bằng thực nghiệm.
- 7 SURVIVED và 2 NO_COVERAGE là đúng cùng các mutant như ở `8f1f701`, nên kết luận trước đó được giữ.
- **So với `8f1f701` (437 mutant):** 35 entry mới và 38 entry mất theo full-identity là cùng 39 mutant dịch dòng; 14 mutant đổi trạng thái (7 KILLED→TIMED_OUT, 7 TIMED_OUT→KILLED; chỉ 9 thấy được khi so full-identity, 5 bị che bởi dịch dòng), nguyên nhân CHƯA GIẢI QUYẾT, phù hợp với nhiễu thời gian nhưng không chứng minh. Một mutant thật sự mới là `ViewerSession.handshake:291` (bỏ `Socket::setSoTimeout`), KILLED bởi `ViewerSessionHandshakeEdgesTest#imageReadTimeoutIsArmedBeforeTheImageChannelIsPublished`; hai mutant thật sự biến mất đều thuộc lời gọi Host bị gỡ ở `HostSession:289` cũ. Đối chiếu do một reviewer Claude `sonnet` chỉ đọc thực hiện.
- **M1 đã đóng:** `ViewerSession#tick:206` (ConditionalsBoundary) nay KILLED, killer trong XML là `tickDecidesNothingBeforeTheHandshakeStartedOrBeforeTheDeadline`.
- **Sửa M2:** mutant từng gọi là 401 (nay ordinal 400, `HandshakeHandler.handle:62`, bỏ `System$Logger::log`) là ngoại lệ log chẩn đoán quan sát được ở mức DEBUG, không phải tương đương vô điều kiện.
- **Sửa nhãn "ordinal 143":** trên `ViewerSession#tryStart` (cũ `:436`, nay `:438`), idx 49 = `activationReceived`, idx 52 = `imageChannelReady`, idx 56 = `readyForActive`. Mục UNRESOLVED cũ (idx 52) nay KILLED. Mô tả "kích hoạt sớm" của T8 khớp idx 49 (ordinal cũ 142, mới 143), nay TIMED_OUT và mang một attribution UNRESOLVED duy nhất: đã bị phát hiện, chưa biết nguyên nhân timeout. Ordinal dịch giữa các lượt nên trích `idx`/dòng.
- **GAP-1 đã đóng:** `HostSession.setReadTimeout` catch (`HostSession.java:203-204`) nay có `HostSessionLifecycleContractTest#commandHandoverClosesSessionWhenReadTimeoutSetterFails`, đã thấy RED khi bỏ lời gọi close.
- **I1 và I2 đã xử lý phía thực thi** (code `a866fc3`; XML được theo dõi). Astra chưa xác nhận lại.

## Giới hạn Minor còn lại

- Nhánh `SocketException` của `setSoTimeout` cho socket ảnh Viewer không có test riêng: socket được tạo bên trong và lỗi đi vào `catch` chung của `connectBody`, theo đọc code.
- PIT ở `1af1d6a` thất bại và nguyên nhân thật vẫn **UNKNOWN**; `8f1f701` thêm fence PING/PONG, và PIT xanh sau đó không chứng minh đã sửa đúng nguyên nhân cũ.

## Lịch sử: kết quả tại `8f1f701` (không phải hiện hành)

Giữ làm lịch sử có ghi ngày; không dùng thay số của `e49c907`/`bd7c5b6`. Tại `8f1f701`: clean verify 1240 test, 0 bad (nhánh phủ cùng năm số như trên); race 25 batch, 4000 lượt; full PIT native 0, 2 giờ 01 phút, 437 = 344 KILLED + 84 TIMED_OUT + 7 SURVIVED + 2 NO_COVERAGE, XML SHA-256 `927db114d119c067f73c87237a824df844c60a5a9ee9470ace8b17d7ba8f1637` (XML này chỉ còn cục bộ, không theo dõi); jar SHA-256 `1273b6f90ad6f37363734b9524a0821e33bd7a1a4bd286befa8fac2da46361a6`, exit 0/2/4; T6 xét tĩnh: EQUIVALENT 9, TIMEOUT-REAL 82, GAP 1 Minor (ordinal 129, nay đã đóng), UNRESOLVED 1 (ordinal 143, nhãn bị gán sai toán hạng, xem trên). Bằng chứng: [execution.json](../../../.superpowers/sdd/2026-11-07-host-viewer-closeout/t7/jar-8f1f701-jdk21/execution.json), [T6-summary](../../../.superpowers/sdd/2026-11-07-host-viewer-closeout/t6/T6-summary.md). Số liệu ở `1af1d6a`, `02f7795` và `17c66ca` vẫn là lịch sử theo SHA, không thừa kế.

## Model receipt

- **T8 final review:** Codex `gpt-6-astra`/`high` (receipt launch xác nhận), chỉ bao phủ tới `b6883e8`.
- **`a866fc3`, `bd7c5b6`, `e49c907` và tài liệu này:** subagent Claude (công cụ Agent), model **requested** `sonnet`, cùng `haiku` cho một review hẹp của `bd7c5b6`. Chỉ biết model được yêu cầu, không biết model thực tế.
- **T6, T7:** requested Claude `sonnet`/`xhigh` (T6) và `sonnet`/`high` (T7); model thực tế chưa xác nhận.
- **Các đợt trước:** Codex `gpt-6.1-sol` với effort theo task (chi tiết trong sổ tiến độ cục bộ); không suy requested thành effective.

## Chưa làm (CHƯA CHẠY)

- **Desktop gate:** năm ca Robot/hai JVM (`TwoJvmIntegrationTest`).
- **H1 và H2:** hai Windows thật, **30 phút ACTIVE + 50 vòng connect/disconnect MỖI hướng**.
- **Astra review lại** `a866fc3`, `bd7c5b6`, `e49c907` và tài liệu này.
- **Full PIT tại `e49c907`:** không chạy (độ lệch SHA ở trên).
- Chưa kiểm chứng: Linux/native, và nguyên nhân thực nghiệm của các TIMED_OUT.
- Không có push, merge hay tuyên bố nghiệm thu, merge-ready hay hoàn tất giai đoạn.
