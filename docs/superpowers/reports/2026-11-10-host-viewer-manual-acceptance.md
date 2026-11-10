# Bản ghi xác nhận nghiệm thu thủ công Host/Viewer v2 (2026-11-10)

Ngày 2026-11-10. Bản ghi này ghi lại **xác nhận của chủ dự án** về nghiệm thu thủ công H1/H2 v2, việc PR #3 đã được merge vào `main`, và kết quả `verify` chạy ngày 2026-11-10 tại `cd63fcf`. Nó **không** thay các gate tự động đã ghi trong [báo cáo gate v2](../reviews/2026-11-02-v2-automated-gates.md) và [closeout 2026-11-08](2026-11-08-host-viewer-closeout.md); phần không được xác nhận nằm ở mục "Không thuộc phạm vi xác nhận này".

## Xác nhận của chủ dự án

Ngày 2026-11-10, chủ dự án xác nhận rằng bài kiểm thử thủ công trên máy thật (H1/H2 v2) đã được thực hiện và đạt, và việc nghiệm thu thủ công đã hoàn thành. Chủ dự án cho biết các báo cáo ghi "chưa nghiệm thu" chỉ vì tài liệu chưa được cập nhật. Nguyên văn lời chủ dự án: "phần báo cáo chưa nghiệm thu do chưa cập nhật, tôi đã test bằng tay và nghiệm thu hoàn thành".

Giới hạn của bản ghi này:

- Đây là **xác nhận của chủ dự án**, không phải nhật ký chạy. Kho mã **không lưu** nhật ký chi tiết: ngày chạy, hai máy tham gia, SHA hoặc JAR đã được thử, kết quả từng ca và số đo thời gian đều không được ghi lại ở đây.
- Chủ dự án không nêu kết quả riêng cho từng ca (ví dụ ca cắt mạng H2-11a/b/c, DPI, focus, key repeat), nên tài liệu này không khẳng định kết quả của từng ca và không điền số đo nào.
- Các mẫu nghiệm thu v2 trong [spec UI/CLI/H2](../specs/2026-11-02-host-viewer-ui-cli-h2-design.md) (mục 4.3) giữ nguyên là mẫu chưa điền; bản ghi này không điền thay các trường đó.

## Merge PR #3

PR #3 (nhánh `feat/wave3-4-desktop-v1`) đã được merge vào `main` thành commit `cd63fcfea71172806fe935a484539a8d60aa38be` ngày 2026-11-08 (`git show -s`: "Merge pull request #3 from ThuanTran260/feat/wave3-4-desktop-v1"). Đầu nhánh được merge là `3579b4f`; từ SHA code cuối `e49c907` đến `cd63fcf`, `git diff --stat e49c907 cd63fcf -- src pom.xml scripts .github` rỗng, các thay đổi chỉ nằm trong tài liệu và file XML PIT được theo dõi. Các tài liệu ngày 2026-11-08 ghi "không merge" mô tả trạng thái trước thời điểm này.

## Verify ngày 2026-11-10 tại `cd63fcf`

`./mvnw -B -ntp verify` chạy ngày 2026-11-10 tại `cd63fcf` trên máy phát triển, JDK **24.0.2**: BUILD SUCCESS, **1243 test, 0 failure, 0 error, 0 skipped**, kiểm tra nhánh JaCoCo đạt. Lưu ý: các lượt trước ghi trong báo cáo gate dùng Temurin 21.0.13; lượt này dùng JDK 24.0.2 trong khi CI yêu cầu JDK 21, nên không thay thế bằng chứng JDK 21. `verify` thường loại các test gắn tag `desktop`, nên lượt này không chạy năm ca Robot/hai JVM.

## Không thuộc phạm vi xác nhận này

Lời xác nhận của chủ dự án nói về kiểm thử và nghiệm thu thủ công. Nó không nói gì về các mục sau; trạng thái đã ghi của chúng **giữ nguyên**:

- **Desktop gate tự động:** năm ca `TwoJvmIntegrationTest` gắn tag `desktop` (Robot, hai JVM) vẫn **CHƯA CHẠY** theo các bản ghi hiện có. Không có tài liệu nào cho biết bài thử thủ công có thay hoặc chạy các ca này.
- **PIT:** full PIT chỉ chạy tại `bd7c5b6` (độ lệch SHA đã được người dùng duyệt ngày 2026-11-08), không chạy lại ở `e49c907` hay `cd63fcf`. Vẫn chưa là mutation acceptance; các kết luận TIMEOUT-REAL (83 mục, đọc tĩnh) vẫn chưa xác lập bằng thực nghiệm.
- **Review cuối:** review Astra T8 chỉ bao phủ tới `b6883e8`; `a866fc3`, `bd7c5b6`, `e49c907` và các tài liệu sau đó chưa có review lại.
- **Mục v3 và ngoài phạm vi:** FRAME_ACK/freshness, relay/Internet, native codec vẫn thuộc v3 hoặc ngoài đợt này.
- **Linux/native:** chưa được kiểm chứng ở đây, như các báo cáo trước.
- **Race 4000 lượt** chỉ chạy tại `e49c907`; lượt `verify` ngày 2026-11-10 không phải lượt race mới.

Tài liệu này không tuyên bố hoàn tất toàn bộ GĐ2: nó ghi riêng phần nghiệm thu thủ công do chủ dự án xác nhận, còn các mục trên vẫn theo trạng thái đã ghi.
