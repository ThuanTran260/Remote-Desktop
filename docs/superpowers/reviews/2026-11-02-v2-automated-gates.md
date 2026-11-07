# Trạng thái gate tự động Host/Viewer v2

Mốc refresh: **2026-11-08**, source/test cố định **`8f1f70171d0157b7d27e6c267f9c671b9a5fb598`** (nhánh `feat/wave3-4-desktop-v1`). Đây là bản tổng hợp bằng chứng đã có thật tại SHA này; **không phải** nghiệm thu GĐ2, review cuối, hay xác nhận merge-ready. Desktop gate, H1, H2 và final Astra review **CHƯA CHẠY** (xem bảng cuối). Nếu source/test/config hay artifact build đổi, ghi SHA/fingerprint mới và kiểm lại phạm vi bị ảnh hưởng. Báo cáo ngắn của đợt đóng gói: [closeout 2026-11-08](../reports/2026-11-08-host-viewer-closeout.md).

[README](../../../README.md) ghi vai/CLI/port hiện hành; [spec Host/Viewer](../specs/2026-11-01-host-viewer-design.md) và [UI/CLI/H2](../specs/2026-11-02-host-viewer-ui-cli-h2-design.md) ghi hợp đồng đã duyệt. [Test map](2026-11-02-v2-test-map.md) giữ truy vết lịch sử và tên sau B2. Không dùng trạng thái thiết kế hoặc PASS v1 thay nghiệm thu v2.

## Code đã được nhận theo đợt và provenance

Phần 3–4 và thực thi B1/B2 đã được người dùng duyệt. Các commit dưới đây phân biệt code/refactor đã nhận với các gate nghiệm thu cuối.

| Mốc | Commit | Phạm vi |
| --- | --- | --- |
| B1 | `88ad8d47424e6bf8b28e6312a9a53d147f706b56` | Đảo vai v2 nguyên khối |
| Sửa race B1 | `75447ced40603930d49d324cd7578a239f17b3a3` | Sửa race sau review |
| F0 | `4b6f28c5720cfa63c988ebfc974d944d12900275` | Readiness trước B2 |
| B2 | `8024887e2eb46cb6b4a3bdb80d197f0f8ac966b6` | Rename/package cơ học |
| Sửa assertion EOF | `dc9842c9feffc11fc24bc4c7c25d25dc66b37742` | Assertion EOF toàn Host |
| Tooling G1 | `6502b1158a10c983d35dee5314f2221ba5abc670` | Tooling gate; chưa là kết quả final gate |
| G1 vòng 1 / sửa tài liệu | `367576c29bd427feefccef2fde85b567c5c19d6c` / `2a651675bb0d0f989226466a7e88cc918772446d` | Source và sửa encoding tài liệu riêng |
| N1 | `525be7c644fd98633903dd4166462ff54d2f50eb` | Portability; full verify tại SHA này thất bại |
| F1 | `17c66ca75f009dce189b1ebc8363a571cafcb997` | Fixture đợi cleanup Viewer cũ trước reconnect |
| F2 | `02f77958ad86de97b46d4ba3b9f8a30e84486e3e` | Fallback classpath diagnostic child và một regression, test-only |
| Vòng 4 | `917d9876f95b72625d4f47eb7d2e89c4d3bfead4` | Thêm 22 test/C1–C9 cho 39 behavior gaps; test-only |
| Vòng 5 | `1af1d6a04e0aa6056afa7a4a116f703b4a6e4ada` | Sửa race quan sát I1 trong fixture graceful-close; test-only, không đổi product/POM |
| Fence PING/PONG, **SHA hiện hành** | `8f1f70171d0157b7d27e6c267f9c671b9a5fb598` | Test-only, +5 dòng ở `ViewerSessionHandshakeEdgesTest.java`: chặn tiến độ writer bằng PING/PONG cố định trước hai assertion đóng cuối; giữ nguyên mọi assertion và kịch bản. Không đổi product/POM/script |

Commit `8f1f701` nêu rõ fence là tiền điều kiện của test, **không** xác định phase, exception hay lịch chạy của lỗi PIT lịch sử ở `1af1d6a`. Review của thay đổi này không có Critical/Important; còn một Minor về provenance dành cho review cuối (tóm tắt trong workflow đóng gói, không phải review cuối).

Model và effort: các đợt trước dùng Codex `gpt-6.1-sol` với effort theo task (receipt launch ghi `high`, `xhigh` hoặc `medium`; chi tiết theo từng task trong sổ tiến độ cục bộ). Ngày 2026-11-07 người dùng duyệt đổi riêng cho phần đóng gói (closeout): worker thực thi là Claude `sonnet`, không dùng `haiku`, `opus` hay Luna; review cuối vẫn là Codex `gpt-6-astra`. Chỉ dẫn bỏ ngưỡng dừng 5% vẫn giữ. Receipt T6 và T7: **requested** Claude `sonnet`/`xhigh` (T6) và `sonnet`/`high` (T7); **effective model/effort chưa xác nhận được từ màn hình**, không đổi requested thành effective bằng suy luận. Prose phục hồi Host tại [host-review.md, dòng 83](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/PIT-adjudication-02f7795/host-review.md) nói high, nhưng [receipt launch thực tế](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/PIT-host-recovery-20261106-launch.json) ghi requested/effective **medium** là provenance có thẩm quyền; giữ nguyên báo cáo gốc. Receipt gói nghiệm thu cuối của Astra: **CHƯA CÓ** (review chưa chạy).

## Bằng chứng hoàn tất trên SHA hiện hành `8f1f701`

Các liên kết `.superpowers` trong tài liệu là **artifact ignored cục bộ** tại checkout `E:/luyentaphe/LapTrinhMang/DoAn-LTM`, không phải bằng chứng công khai/remote CI. Không sao chép log/secret/evidence blob vào tài liệu tracked, không dựng link công khai giả.

**Clean verify.** `mvnw.cmd -B -ntp clean verify`, native **0**, **1240 test / 73 lớp / 0 failure / 0 error / 0 skip**, JaCoCo và Enforcer đạt. UTC **2026-11-07T12:45:23.9217973Z–12:47:15.1716764Z**, commit đầu và cuối đều `8f1f701`, `DirtyAfter=[]`, Temurin **21.0.13+9**, Maven **3.9.12**, Windows/headless. Nguồn: [verification.json](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/baseline-readiness-coordinator-8f1f701-jdk21/verification.json).

| Lớp | Nhánh phủ/tổng tại `8f1f701` | Điều kiện riêng từng lớp |
| --- | ---: | --- |
| `host.HostSession` | 77/83 | ≥0.80, đạt |
| `host.HandshakeHandler` | 26/26 | ≥0.80, đạt |
| `host.ControlledSide` | 25/28 | ≥0.80, đạt |
| `viewer.ControlSide` | 71/78 | ≥0.80, đạt |
| `viewer.ViewerSession` | 88/97 | ≥0.80, đạt |

**Race.** Chiến dịch riêng trên `8f1f701`: **25/25 batch, tám method × 500 = 4000 lượt, 0 failure/error/skip**, native 0, UTC 2026-11-07T12:57:34.9953115Z–13:06:07.3587466Z. Mỗi batch 20 lượt/method; fingerprint runner trước/sau không đổi, manifest 437 mục đã đối chiếu. Báo cáo: [finalchecks-8f1f701-report.md](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/finalchecks-8f1f701-report.md), thư mục chạy `G1-races-8f1f701-jdk21`. Bằng chứng headless race không thay native desktop/H1/H2, và repetitions trong verify/PIT không được tính thay chiến dịch này.

**Full PIT.** Native exit **0**, **2 giờ 01 phút** (UTC 2026-11-07T13:08:27Z–15:09:49Z, PIT 1.19.6, BUILD SUCCESS), XML đóng, không rỗng, HTML đủ. **437 mutant = 344 KILLED + 84 TIMED_OUT + 7 SURVIVED + 2 NO_COVERAGE** (các trạng thái khác đều 0). XML SHA-256 **`927db114d119c067f73c87237a824df844c60a5a9ee9470ace8b17d7ba8f1637`**. Con số 428 (98%) ở console là 344 KILLED **cộng** 84 TIMED_OUT; không đọc nó như số KILLED literal. Đây là hoàn tất thực thi, không phải mutation acceptance. Theo lớp: ViewerSession 148, HostSession 115, ControlSide 81, ControlledSide 45, HandshakeHandler 29, ViewerSession$1 11, HostSession$1 8 (chi tiết trong báo cáo finalchecks). XML `partial="true"` là passthrough `reportCoverage` của PIT 1.19.6, không phải cờ hoàn tất; độ hoàn tất dựa vào exit, XML đóng, HTML và hash. Bằng chứng: `G1-PIT-8f1f701-jdk21/` (`pit.log`, `pit.exit`, `execution.json`, `pit-reports/`, `artifact-hashes.json`).

**Xét 93 mutant không bị diệt (T6) — chỉ đọc tĩnh, không chạy gì.** Mỗi mutant có đúng một kết luận ([T6-summary](../../../.superpowers/sdd/2026-11-07-host-viewer-closeout/t6/T6-summary.md); chi tiết: [viewer](../../../.superpowers/sdd/2026-11-07-host-viewer-closeout/t6/viewer-review.md), [host](../../../.superpowers/sdd/2026-11-07-host-viewer-closeout/t6/host-review.md), [sides/handshake](../../../.superpowers/sdd/2026-11-07-host-viewer-closeout/t6/sides-review.md)):

- **EQUIVALENT 9**: toàn bộ 7 SURVIVED và 2 NO_COVERAGE (ordinal 56, 70, 88, 89, 104, 177, 214, 389, 401), mỗi mutant có lý do riêng.
- **TIMEOUT-REAL 82**: test có chờ giới hạn 5 s/10 s bị mutant kéo dài hoặc không hoàn tất, nên vượt giới hạn của PIT; một trường hợp là vòng lặp thật (ordinal 270).
- **GAP 1 (Minor)**: ordinal 129, `ViewerSession#tick:206`, biên điều kiện; test `HE:271-289` chạy đua với fallback `SO_TIMEOUT` cùng độ dài. **Chưa sửa.**
- **UNRESOLVED 1**: ordinal 143, `ViewerSession#tryStart:436`; review ghi assertion có phát hiện mutant, nhưng không biết test nào làm PIT vượt giới hạn. **Chưa sửa.**

Giới hạn: các kết luận TIMEOUT là suy luận tĩnh; giới hạn thời gian của PIT và thứ tự test **không được quan sát**, nên không xác định nguyên nhân từng timeout bằng thực nghiệm. Hai mục 129 và 143 là **giới hạn đã biết**, được công bố, không sửa theo chỉ thị NO MORE CODE hiện hành (người dùng có thể thay đổi). Đây không phải mutation acceptance toàn bộ.

**Packaged headless launcher tại `8f1f701`** (build: `./mvnw.cmd -B -ntp clean package -DskipTests`, exit 0, Temurin **21.0.13+9**, Maven **3.9.12**): SHA-256 JAR **`1273b6f90ad6f37363734b9524a0821e33bd7a1a4bd286befa8fac2da46361a6`**, **282401 byte**. `java -Djava.awt.headless=true -jar target/remote-desktop.jar --help` exit **0** (help v2), `--server` exit **2** (thông báo từ chối legacy), `--role viewer` exit **4** (`graphical environment required (java.awt.headless=true)`). Cả ba khớp mong đợi. Bằng chứng, argv, UTC và exit thực: [execution.json](../../../.superpowers/sdd/2026-11-07-host-viewer-closeout/t7/jar-8f1f701-jdk21/execution.json). Một lượt đầu trong cùng thư mục (`attempt-1-exitcode-lost`) không ghi được exit code do `Start-Process` trả `ExitCode` rỗng; lượt này được chạy lại bằng `System.Diagnostics.Process` và giữ cả hai. Đây là kiểm packaged launcher trên một máy, không phải JAR A/B trên hai máy và không phải H2; thay build phải ghi provenance mới.

## Lịch sử tại `1af1d6a` (không phải SHA hiện hành)

Các kết quả dưới đây đúng ở `1af1d6a` và được giữ làm lịch sử; **không dùng thay số của `8f1f701`**.

- Verify ở `1af1d6a`: 1240 test / 73 lớp / 0 bad, nhánh phủ ControlSide **70/78** (các lớp còn lại như bảng trên), receipt UTC 2026-11-06T14:02:24Z–14:04:39Z. Nguồn: [verification.json](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/PIT-wave5-coordinator-1af1d6a-jdk21/verification.json), [invocation-preservation](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/PIT-wave5-coordinator-1af1d6a-jdk21/invocation-preservation.json).
- Race ở `1af1d6a`: 25 batch, 4000 lượt, 0 bad ([báo cáo](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/G1-races-1af1d6a-report.md), [root audit](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/G1-races-1af1d6a-root-audit.json)). Tám method của chiến dịch này không gồm `v1CloseMessagesSurviveTheRulesTable`.
- JAR ở `1af1d6a`: digest `6bc7df1c3a0d436bb6d7202d878f574ce885c79084b5a0f62d30a474f23e6062`, exit 0/2/4 ([execution](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/Task4-jar-1af1d6a-jdk21/execution.json), [jar-inspection](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/Task4-jar-1af1d6a-jdk21/jar-inspection.json)).
- **Full PIT ở `1af1d6a` THẤT BẠI**: native exit 1 sau 192 s ở pha coverage, không có XML/HTML. Test lỗi trước khi mutate là `ViewerSessionHandshakeEdgesTest#v1CloseMessagesSurviveTheRulesTable`; log chỉ nêu tên test, **không có assertion hay exception thật, nên nguyên nhân vẫn UNKNOWN**. Chạy riêng test này một lần thì xanh, không giải thích hay xóa lỗi PIT. Báo cáo: [G1-PIT-1af1d6a-report.md](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/G1-PIT-1af1d6a-report.md), [chẩn đoán tĩnh](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/PIT-baseline-close-diagnosis.md) (giả thuyết reader/writer chưa được chứng minh). `8f1f701` thêm fence PING/PONG, và PIT xanh một lần ở `8f1f701` **không chứng minh** đã sửa đúng nguyên nhân cũ; không relabel lần thất bại.
- [ControlSide scoped native PIT](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/PIT-controlside-1af1d6a-report.md) ở `1af1d6a`: một invocation trong copy giới hạn, native 0, **81 = 50 KILLED + 8 SURVIVED + 22 NO_COVERAGE + 1 TIMED_OUT**. Chỉ là diagnostic hẹp, không phải acceptance; số 81 của lớp `ControlSide` ở full PIT `8f1f701` (80 KILLED + 1 TIMED_OUT) thuộc một lần chạy khác và không so sánh trực tiếp.

## Diễn tiến F2, test-only vòng 4–5 và mutation evidence lịch sử

Không có `G1-fix-F2-report.md`; báo cáo F2 thực là [G1-fix-round-3-report.md](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/G1-fix-round-3-report.md). Regression `childDiagnosticsWorkWithoutSurefireClasspath` xóa Surefire property, giữ `java.class.path` thật, chứng minh ordinary Viewer child và diagnostic child/cleanup. RED/GREEN tái hiện fallback thiếu gây NPE rồi sửa; vì log PIT cũ không có exception/property, đây là sufficient cause được tái hiện, **không chứng minh exception lịch sử chắc chắn là NPE**. Commit `02f7795` đã được nhận; full verify hoàn tất khi ấy **1218/71/0 bad**, không gán count này cho SHA khác.

[Vòng 4](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/PIT-wave4-report.md) thêm **22 test** và C1–C9 cho **39 behavior gaps**. [Review vòng 4](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/PIT-wave4-review.md) giữ finding Important **I1** về race quan sát phase-B, dù covering/full verify lúc đó xanh. [Vòng 5](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/PIT-wave5-report.md) sửa riêng I1, không đổi product/POM; [review scoped vòng 5](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/PIT-wave5-review.md) **I1 ADDRESSED / ACCEPT**, không finding Critical/Important/Minor mới trong scope. Đây không phải review toàn branch hay final Astra. **Chín identity source-fault RED/GREEN đại diện vòng 4 và hai control vòng 5 không phải native PIT kills, không chứng minh nguyên nhân timeout cũ.** Các failed compiler/fixture/old-schedule attempts vẫn giữ trong báo cáo gốc, không relabel thành RED sản phẩm hoặc PASS.

Full canonical PIT **lịch sử** trên `02f7795` có native **0**, closed XML/HTML được phục hồi và hash-check: **437 = 299 KILLED + 36 SURVIVED + 12 NO_COVERAGE + 90 TIMED_OUT**. [Báo cáo execution phục hồi](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/G1-PIT-02f7795-execution-report.md) giữ XML SHA-256 `f3a32b674ada1c9d10348ee448b64d7f59c9696361cdc5c130745d5d9da62fb9`; không gộp 90 timeout vào literal KILLED dù console hiển thị 389 detected. Ba báo cáo semantic đã hoàn tất: [Host](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/PIT-adjudication-02f7795/host-review.md), [Viewer](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/PIT-adjudication-02f7795/viewer-review.md), [Sides/Handshake](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/PIT-adjudication-02f7795/sides-handshake-review.md). Giữ nguyên 437 hàng và disposition theo thời điểm; completed capture/semantic review **không phải mutation acceptance**. Số `02f7795` (299/36/12/90) thuộc SHA đó và không được thừa kế cho `8f1f701`, nơi số hiện hành là 344/7/2/84 ở trên; **nguyên nhân của 90 TIMED_OUT lịch sử vẫn chưa được giải quyết** (84 timeout hiện hành chỉ có suy luận tĩnh ở T6).

Giới hạn ruling của vòng 4–5 vẫn nguyên: năm invariant EQ **57/71/105/178/390** có proof/observer bounds riêng; chỉ thêm **conditional production-ingress EQ 89/90/215** trong điều kiện một reader, PONG callback hoàn tất trước packet kế, first close immutable, writer closing/stopped hoặc raw channel closed và không đổi RTT/traffic/frame/input/futures. Nếu ingress đổi phải xem lại; không mở concurrent direct-dispatch contract/private timestamp seam. **402** chỉ là ngoại lệ **optional DEBUG-presence** trong limited observer context của ruling, không unconditional EQ, không bỏ privacy/redaction/cleanup/actionable diagnostics. Không đổi nhãn tương đương hàng loạt: ledger **39 behavior gaps + 8 bounded EQ + 1 observer exception** giữ đúng giới hạn, không tự coi 39 là native kills. Các ruling này gắn với run `02f7795`; số thứ tự mutant ở run `8f1f701` (T6) là một bộ đánh số khác.

## Lịch sử F1 giữ nguyên theo SHA

Các đoạn F1 dưới đây giữ kết quả đúng mốc lịch sử `17c66ca`, không phải verify mới của `8f1f701`; bảng 87/97 thuộc F1, bảng 88/97 hiện hành ở trên.

Root chạy độc lập `mvnw.cmd -B -ntp clean verify` trên đúng clean `17c66ca`: native exit **0**, **1217 test / 71 lớp / 0 failure / 0 error / 0 skip**, JaCoCo và Enforcer đạt. Bắt đầu `2026-11-04T07:12:00.9971782Z`, kết thúc `2026-11-04T07:14:25.6157752Z`; Windows 11 amd64, Eclipse Adoptium Temurin **21.0.13+9**, Maven **3.9.12**. HEAD đầu/cuối không đổi, `DirtyAfter=[]`. Đây là bằng chứng Windows/headless, không chứng minh Ubuntu/native/PIT/H2.

Nguồn: [verification.json](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/F1-coordinator-17c66ca-jdk21/verification.json), [native exit](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/F1-coordinator-17c66ca-jdk21/verify.exit), [log](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/F1-coordinator-17c66ca-jdk21/verify.log), [XML Surefire đã sao lưu](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/F1-coordinator-17c66ca-jdk21/surefire/), [JaCoCo XML](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/F1-coordinator-17c66ca-jdk21/jacoco.xml).

| Lớp | Nhánh phủ/tổng | Điều kiện riêng từng lớp |
| --- | ---: | --- |
| `host.HostSession` | 77/83 | ≥0.80, đạt |
| `host.HandshakeHandler` | 26/26 | ≥0.80, đạt |
| `host.ControlledSide` | 25/28 | ≥0.80, đạt |
| `viewer.ControlSide` | 70/78 | ≥0.80, đạt |
| `viewer.ViewerSession` | 87/97 | ≥0.80, đạt |

[Review F1](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/F1-review.md) phê duyệt code, không Critical/Important; tại thời điểm review, clean verify của root chưa có kết quả nên recommendation còn điều kiện. Kết quả hoàn tất ở trên được ghi riêng sau đó. Finding Minor yêu cầu dịch phần map mới; bản tài liệu này chỉ dịch phần append, bảo toàn nguyên tiền tố 197795 byte. [Báo cáo triển khai F1](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/F1-reconnect-readiness-report.md) ghi RED/GREEN có kiểm soát và 45 lượt lớp; đó là bằng chứng candidate tại BASE với dirty bytes, không đổi thành clean verify đã commit.

Lượt full verify trước ở `525be7c` vẫn **THẤT BẠI**, native exit **1**, 1217 test / 71 lớp / 1 failure / 0 error / 0 skip, dừng ở Surefire trước JaCoCo: [bản ghi gốc](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/G1-fix-coordinator-525be7c-jdk21/verification.json). Không relabel hoặc xóa thất bại này.

### Baseline PIT thất bại trên `17c66ca` — ghi nhận lịch sử tại D1

Đoạn sau giữ trạng thái lúc D1: khi ấy chưa có XML/adjudication; các bằng chứng hoàn tất trên `02f7795` và review sau đó được ghi riêng phía trên, không xóa hoặc đổi nhãn lần thất bại này.

[Báo cáo PIT đã hoàn tất](../../../.superpowers/sdd/2026-11-02-host-viewer-b1-b2/G1-PIT-execution-report.md) ghi lệnh `mvnw.cmd -B -ntp test-compile org.pitest:pitest-maven:1.19.6:mutationCoverage` chạy một lần trên clean `17c66ca`, native exit 1. Baseline thất bại tại `TwoJvmPeerTest.childProductLoggingAndRawStreamsAreTraceableWithoutPersistedSecrets`; chưa xác định nguyên nhân gốc. Không có `mutations.xml`/HTML; số 0 trong trường MutationCount của báo cáo phản ánh thiếu XML, không chứng minh không có mutant hoặc gate đã đạt. Báo cáo ghi 242 input hash trước/sau không đổi. Chưa có verdict adjudication hợp lệ; root điều tra riêng, không retry để lấy xanh hoặc suy PASS từ full verify trước.

## Trạng thái gate tại `8f1f701`

| Gate / artifact | Trạng thái | Phạm vi / phần còn thiếu |
| --- | --- | --- |
| Clean verify / năm lớp coverage | ĐẠT Windows/headless | 1240/73/0 bad; nhánh 77/83, 26/26, 25/28, 71/78, 88/97 |
| Race cuối 4000 lượt | ĐẠT chiến dịch riêng | 25 batch, 500 MỖI method trong tám method, 0 bad |
| JAR đóng gói / digest / CLI headless | ĐẠT trong phạm vi ba ca actual 0/2/4 | Digest `1273b6f9…6a`; không chứng nhận GUI hoặc hai máy |
| Full PIT | HOÀN TẤT THỰC THI, native 0 | 437 = 344 KILLED + 84 TIMED_OUT + 7 SURVIVED + 2 NO_COVERAGE; **chưa là mutation acceptance** |
| Xét mutant (T6, đọc tĩnh) | HOÀN TẤT, còn giới hạn | EQUIVALENT 9, TIMEOUT-REAL 82, GAP 1 Minor (129), UNRESOLVED 1 (143); hai mục sau chưa sửa. Nguyên nhân lỗi PIT ở `1af1d6a` vẫn UNKNOWN |
| Năm ca desktop hai JVM/Robot | **CHƯA CHẠY** | 30 frame/100 input thực, hai graceful-close và hai kill ≤15 s; headless TwoJvm harness không thay designated native desktop cases |
| H1/H2 v2 | **CHƯA CHẠY** | Hai Windows thật, A-Host/B-Viewer và B-Host/A-Viewer; **30 phút ACTIVE + 50 connect/disconnect MỖI hướng**, physical pre-ACTIVE, DPI/focus/repeat theo spec |
| Linux/native và giới hạn N1/M2 | CHƯA KIỂM CHỨNG ở đây | Windows/headless không đóng FIFO/AWT/focus/idle/integrity/hooks/OS-delivery hoặc broader M2 |
| Review cuối Astra (toàn branch, gồm tài liệu này) | **CHƯA CHẠY** | Chưa có receipt/finding thật; không dùng review scoped trước đó thay thế |

Sáu mẫu v2 vẫn để trống/CHƯA CHẠY cho run, người chạy, giờ, SHA build-final, dirty state, Windows/JDK/monitor/DPI, artifact và JAR A/B digest đúng lượt hai máy. Bằng chứng JAR headless một máy ở trên không điền thay các trường đó. H1/H2 và năm ca desktop vẫn cần native evidence riêng; không có headless/JAR/race/PIT substitution.

Host là máy bị điều khiển, lắng nghe TCP **5001 lệnh/5000 ảnh**, hiện mã và SharingBar; Viewer là máy điều khiển, kết nối/nhận frame và dùng ControlFrame. `--address` chỉ preset Viewer, không auto-connect; legacy bị từ chối; không PIN argv/log. Wire **v2 không phải Internet relay**; **FRAME_ACK/freshness vẫn thuộc v3 tương lai**, native codec ngoài đợt này. **GĐ2 chưa được nghiệm thu đầy đủ, chưa merge-ready**: mutation acceptance, desktop/H1/H2 và final Astra còn thiếu.
