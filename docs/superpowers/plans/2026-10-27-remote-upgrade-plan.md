# Kế hoạch nâng cấp Remote Desktop: giảm trễ và kết nối Internet

Ngày: 2026-10-27, rà soát lần 2 ngày 2026-10-31 (mục 2.1), lần 3 và 4 ngày 2026-11-01 (mục 2.2, 2.3). Trạng thái: **kế hoạch đề xuất, chưa triển khai/chưa benchmark**. Code tham chiếu: `feat/wave3-4-desktop-v1` tại `4a555f9`.

**Phụ thuộc: đổi vai Host/Viewer.** Người dùng đã chọn đổi sang mô hình UltraViewer: máy bị điều khiển (Host) lắng nghe và giữ mã, máy điều khiển (Viewer) nhập IP + mã; `Protocol.VERSION` lên 2 (thiết kế đang duyệt, chưa có spec). Tên lớp trong kế hoạch này là tên hiện tại: phía `client/` (capture, encode, `ScreenSender`) sẽ thuộc Host, phía `server/` + `ui/` (`FrameReceiver`, `RemotePanel`, gate/epoch) thuộc Viewer; `AuthGuard`/`HandshakeHandler` chuyển sang Host. Không bắt đầu P0 trước khi đổi vai xong và H2 chạy trên bản đã đổi, để baseline đo đúng bản sẽ phát hành. H2 ở đây là bài thử hai máy của đợt 3 (`reviews/2026-10-20-wave3-4-checkpoint.md:36`), hiện viết theo vai Server/Client cũ; các file kết quả thử chuột/bàn phím/giao diện phải được cập nhật cho Host/Viewer trong đợt đổi vai. Spec bắt tay Host/Viewer chưa được viết; nó là đầu ra của đợt thiết kế đổi vai, không thuộc tài liệu này.

**Quy ước version giao thức** (dùng thống nhất trong toàn tài liệu):
- **v1**: wire format hiện tại trong code (`Protocol.VERSION = 1`). Chỉ dùng để chỉ code trước đổi vai.
- **v2**: đổi vai Host/Viewer. Sửa gate "đã áp/đã vẽ" của `RemotePanel` đi cùng đợt này nhưng là commit và test riêng, hoàn thành trước H2; sửa gate không đổi wire format.
- **Baseline B0** đo trên SHA thực tế của bản v2 đã sửa gate (ghi SHA vào file kết quả khi đo), không phải `4a555f9`. `4a555f9` chỉ là commit dùng làm bằng chứng số dòng trong mục 2.
- **v3**: P1b (sequence trong `FRAME`, ACK, giới hạn frame đang bay). Tách khỏi v2 vì thay đổi cả ACK, điều tiết gửi và stall.
- Các nhánh đổi wire format sau đó (tile B1, codec B2b, framing relay C) ghi "**version tiếp theo**" cho tới khi chốt wire format và thứ tự thực hiện.

Đầu vào: [báo cáo khảo sát đã rà soát](../specs/2026-10-27-remote-upgrade-research.md), [spec gốc, giao thức v1](../specs/2026-10-19-remote-desktop-design.md), [kế hoạch đợt 4](2026-10-20-wave4-hardening-and-release.md). Tài liệu này lập công việc nâng cấp tiếp theo; không đánh dấu đợt 3/4 hoàn thành, không thay tiêu chí nghiệm thu v1.

Các giao diện, cấu hình, ngưỡng và test mới dưới đây là **đề xuất**, chưa tồn tại trong code nếu không ghi rõ. `[AUTO]` là việc chạy được bằng code/tool; `[HAI MÁY]` cần dữ liệu từ hai máy Windows thật. Không thay bằng test headless để kết luận về DPI, Robot hay độ trễ người dùng nhìn thấy. Mọi checkbox ban đầu để trống; thay đổi tài liệu chưa phải hoàn thành task.

## 1. Quyết định về phạm vi và thứ tự

Mục tiêu gần: thao tác phản hồi nhanh hơn trên hai máy Windows trong LAN, giữ Java 21, Swing, Robot và wire format hiện hành (v2 sau đổi vai) cho nhóm P0–P3; P1b là v3. Mục tiêu tiếp theo: kết nối Internet qua ID/phiên được xác thực. Không cam kết đạt hiệu năng ngang TeamViewer/UltraViewer khi chưa có số đo.

Thứ tự khuyến nghị:

```text
P0 baseline + P1 telemetry → P2 đường hiển thị → P3 cấu hình/socket
                                               ↓ số đo + nhu cầu
                    ┌──────────────────────────┼─────────────────────┐
               P4 thích ứng             C kết nối Internet      B1 hoặc B2
               nếu mạng yếu             nếu cần khác mạng      nếu nén/capture là trần
```

P1b (tuổi frame, mục 6b) cần đổi giao thức nhưng nên đi **trước** P4 và trước mọi thử nghiệm Internet/mạng yếu, vì đó là cách duy nhất phát hiện ảnh cũ khi FPS vẫn bình thường. P1 có thể được chuẩn bị khi chờ lịch thử hai máy, nhưng không đổi mặc định trước baseline. Không bắt buộc làm dirty-rect trước Internet. Chỉ làm nhánh có bằng chứng cần thiết; mỗi nhánh có thể dừng ở kết quả spike và quyết định tiếp tục/dừng.

| Nhóm | Phạm vi | Thay wire format? | Gate trước khi bắt đầu |
| --- | --- | --- | --- |
| P0–P1 | Baseline, công cụ đo, telemetry local | Không | Build chạy được; ghi trung thực phần chưa kiểm chứng |
| P2–P3 | Giảm chờ UI, cấu hình, thử NODELAY | Không | Có baseline P0 và số đo liên quan P1 |
| P1b | Tuổi frame, số frame đang bay, giới hạn N | Có, v3 (sequence trong `FRAME`, gói `FRAME_ACK`) | Có baseline B0 trên v2; spec v3 chốt ACK cộng dồn, cửa sổ khi ẩn/thu nhỏ và stall |
| P4 | Thích ứng bằng tín hiệu local + tuổi frame | Không thêm ngoài P1b | P3 cho thấy nghẽn mạng/CPU; có P1b để đạt G4; giữ profile cố định làm đối chứng |
| C | Transport outbound, ghép cặp, TLS đầu-cuối, relay | Thêm giao thức relay (version tiếp theo); giao thức phiên hiện hành có thể giữ bên trong | Chốt danh tính/khóa, spike transport local an toàn |
| B1 | FULL/DELTA theo ô | Có, version tiếp theo và cập nhật spec | Đo cho thấy nén/băng thông là vấn đề và thử tile có lợi |
| B2 | Native capture, rồi mới cân nhắc H.264 | Capture + JPEG giữ wire format hiện hành; H.264 cần version tiếp theo | Capture/codec là trần; spike và đóng gói được |

## 2. Bằng chứng hiện trạng và các sửa đổi sau review

Các đường dẫn `src/...` dưới đây tính từ gốc repo, số dòng tại commit tham chiếu.

| Phát hiện đã kiểm tra | Ý nghĩa cho kế hoạch | Nguồn |
| --- | --- | --- |
| FPS=12, JPEG=0,6; width tối đa 1280 | Giữ làm đối chứng, chưa tăng mặc định | `src/main/java/com/ltm/rd/client/ClientSession.java:45`; `src/main/java/com/ltm/rd/common/Protocol.java:15` |
| Poll và timer UI đều 50 ms | Có thể thêm chờ pha; chưa chứng minh chi phí thực tế. Khoảng 0–100 ms lý tưởng không phải cận trên | `src/main/java/com/ltm/rd/ui/SnapshotFeed.java:11`; `src/main/java/com/ltm/rd/ui/SnapshotPump.java:31` |
| Server bật NODELAY sau accept; Client chưa bật | Sửa kênh lệnh cho đúng spec, thử riêng kênh ảnh; không hứa giảm một số ms cố định | `src/main/java/com/ltm/rd/server/ServerCore.java:208`; `src/main/java/com/ltm/rd/client/ClientSession.java:244`; `:283`; `docs/superpowers/specs/2026-10-19-remote-desktop-design.md:241` |
| Server và UI đã giữ ảnh mới nhất; Client ghi xong mới chụp tiếp | Không thêm một hàng đợi ảnh chung để giải quyết vấn đề đã có; vẫn phải khảo sát buffer TCP | `src/main/java/com/ltm/rd/server/ServerSession.java:354`; `src/main/java/com/ltm/rd/ui/SnapshotPump.java:18`; `src/main/java/com/ltm/rd/client/ScreenSender.java:79` |
| Decoder cho phép ảnh thu nhỏ đúng tỷ lệ, JPEG phải khớp header | Hạ width trong giới hạn hiện hành không bắt buộc đổi protocol; tile tùy ý thì cần hợp đồng khác | `src/main/java/com/ltm/rd/common/Validation.java:18`; `src/main/java/com/ltm/rd/server/FrameDecoder.java:40`; `src/test/java/com/ltm/rd/server/FrameDecoderTest.java:41` |
| Gate/epoch/frame lấy cùng transaction | Không đẩy ảnh riêng rồi ghép với gate từ snapshot khác | `src/main/java/com/ltm/rd/server/ServerSession.java:480`; `src/main/java/com/ltm/rd/ui/Snapshot.java:7` |
| Mã 6 số và token 16 byte đi qua socket thường | Cần mã hóa/xác thực endpoint; token 128 bit hiện tại không yếu chỉ vì độ dài | `src/main/java/com/ltm/rd/client/ClientSession.java:244`; `:257`; `src/main/java/com/ltm/rd/server/SessionManager.java:85` |
| Baseline/DPI trong báo cáo vẫn “chưa chạy” | Gate là có dữ liệu thật, không coi test xanh là đã đạt hiệu năng | `docs/superpowers/reviews/2026-10-20-wave4-acceptance-results.md:50`; `docs/superpowers/reviews/2026-10-20-wave3-spike-robot-results.md:17` |

### 2.1 Rà soát lần 2 (2026-10-31): đã kiểm chứng với code

| Phát hiện | Kết luận kiểm chứng | Nguồn | Sửa ở |
| --- | --- | --- | --- |
| Liveness ảnh tính theo lúc frame **tới và giải mã xong**, không theo tuổi frame | **Đúng.** `FrameMonitor.onFrame(now)` đặt `lastFrameAt = now`; `now` lấy sau decode. Stall 3 s, timeout 15 s. Frame nằm chờ trong buffer TCP/hàng đợi router vẫn tới đều thì gate mở dù ảnh cũ. Độ lớn chưa đo; "FPS vẫn cao" chỉ xảy ra ở một số chế độ mạng (frame nhỏ, buffer sâu) | `server/FrameMonitor.java:30-33`; `server/ServerSession.java:344-356`; `server/FrameReceiver.java:52-53`; `common/Timeouts.java:34-35` | P0 (kịch bản backlog), P1b, P4, G4 |
| Gate mở khi snapshot được **áp**, chưa chắc frame đã được **vẽ** | **Đúng**, có trong code hiện tại. `apply()` gán `gate` rồi mới `repaint()`; Swing: "The component will be repainted after all of the currently pending events have been dispatched". Sự kiện chuột/phím đang chờ được xử lý với gate mới trong khi màn hình còn ảnh cũ. Cửa sổ thường ngắn (các sự kiện chờ trước lần paint) nhưng rơi đúng lúc hồi phục stall và đầu phiên. Javadoc `RemotePanel` hứa mạnh hơn code. Mức: Trung bình (không phải Cao) | `ui/RemotePanel.java:14-15,53-54`; [JComponent.repaint](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/swing/JComponent.html#repaint(java.awt.Rectangle)) | Bất biến 1, P2 |
| Khóa đăng nhập theo **IP socket** | **Đúng.** Qua relay hoặc CGNAT, nhiều peer chung một IP: kẻ dò mã khóa luôn người dùng thật (3 lần sai → 30 s). TLS không sửa logic này | `server/HandshakeHandler.java:65-66`; `server/ServerCore.java:85` | C0, C1, GC |
| FULL refresh mỗi giây (B1) có thể triệt tiêu dirty-rect | **Đúng.** Ví dụ giả định 500 KiB ở 125 KiB/s cần 4 s, vượt stall 3 s; refresh chiếm hết băng thông. Ghi chú thêm: code hiện tại đã có vấn đề này khi một frame JPEG đầy đủ truyền lâu hơn 3 s | `plans/…-remote-upgrade-plan.md` mục B1 (bản cũ dòng 160); `common/Timeouts.java:34` | B1 |
| Tiêu chí tốc độ thiếu ngưỡng chất lượng sử dụng | **Đúng một phần.** Sweep P3 cố định width/quality (mục 8), nên G3 chỉ hở khi chọn cấu hình đổi quality/width; rủi ro chính ở P4 ("giới hạn dễ đọc đã thử" chưa định nghĩa) | mục 8, mục 9 (bản cũ dòng 123, 127, 132) | Mục 4.1, P0, G3, G4 |
| Gate cho phép diễn giải rộng | **Đúng.** G2 "đạt mục tiêu hoặc có phân tích nguyên nhân"; GB2 "đủ rõ" không có quy tắc | bản cũ dòng 115, 173 | Mục 4.1, G2, GB2 |
| Bỏ mẫu telemetry khi sink chậm làm lệch p95 | **Đúng.** Mất mẫu tương quan với tải nên phần còn lại nghiêng về lúc chạy nhẹ | bản cũ dòng 99, 101 | P1 |

### 2.2 Rà soát lần 3 (2026-11-01)

| Phát hiện | Kết luận kiểm chứng | Sửa ở |
| --- | --- | --- |
| ACK chưa xử lý frame không được vẽ; cửa sổ N có thể bị chiếm mãi | **Đúng, và nặng hơn nêu trong review:** bản trước gắn liveness/giới hạn với frame "đã vẽ", nên Viewer thu nhỏ cửa sổ thì Host ngừng gửi và phiên bị đóng sau frame timeout 15 giây (`Timeouts.java:35`). N = 2 với vòng 200 ms chỉ cho khoảng 10 FPS | P1b: ACK cộng dồn `FRAME_ACK(consumedSeq, shownSeq)`, điều tiết theo `consumedSeq`, N là cấu hình thử |
| Tiến độ byte không chứng minh ảnh đủ mới; DELTA rỗng có thể thành heartbeat | **Đúng.** Bản trước gộp "giữ kết nối" với "cho phép điều khiển" | B1: tiến độ byte chỉ hoãn timeout đóng phiên; gate cần ảnh hoàn chỉnh đủ mới; DELTA rỗng mang `baseSeq`, chỉ đi trên kênh ảnh theo thứ tự |
| Baseline và số version mâu thuẫn | **Đúng.** Đầu tài liệu yêu cầu v2 trước P0, nhưng B0 ghi `4a555f9`, nhóm A ghi "giữ v1", B1 dùng tên v2 | Quy ước version ở đầu tài liệu; B0 = SHA bản v2 đã sửa gate; P1b = v3; nhánh sau ghi "version tiếp theo" |
| `ThrottledOutputStream` không tái hiện hàng đợi mạng | **Đúng.** Lớp này làm chậm chính `write` của ứng dụng (`it/ThrottledOutputStream.java:44`), nên kiểm tra backpressure chứ không phải "ghi nhanh, nhận muộn" | P0: kịch bản (a) backpressure và (b) proxy hàng đợi FIFO có `R`, `Q` |

### 2.3 Rà soát lần 4 (2026-11-01, chiều)

| Phát hiện | Kết luận kiểm chứng | Sửa ở |
| --- | --- | --- |
| Tuổi frame tính ở Host nhưng gate ở Viewer; thiếu ngưỡng tuyệt đối | **Đúng.** Viewer không có `captureAt`; điều kiện "không tăng dần" vẫn đạt khi ảnh luôn trễ 2 giây | P1b: Host có thẩm quyền (`viewSeq` trong thao tác, `FRESHNESS`), `A_max`, G1b có ngưỡng tuyệt đối |
| DELTA rỗng kiểm base theo ảnh đã vẽ | **Đúng.** Decoder có thể đi trước EDT một frame, và thu nhỏ cửa sổ làm dừng vẽ; khi đó quy tắc cũ đòi FULL nhầm | B1: base kiểm theo canvas decoder; `contentSeq`/`validAsOfSeq` |
| Đếm frame bỏ qua từ hai sequence | **Đúng.** Vẽ đủ 1–11 và chỉ vẽ 11 cho cùng ACK `(11, 11)` | P1b: counter cục bộ ở Viewer |
| Công thức N dùng p95 vòng lúc nghẽn | **Đúng.** Nghẽn 2 giây, 30 FPS cho N ≥ 60 | P1b: vòng nền, `N_max`, giới hạn byte, giảm tải thay vì tăng N |
| G3 còn ghi "default của v1" | **Đúng** | G3 |

### 2.4 Sửa đổi về cách đo (rà soát lần 1)

Ba sửa đổi về cách đo: `write+flush` không phải thời gian truyền tới màn hình; timestamp `nanoTime` của hai JVM không trừ trực tiếp được; `paintComponent` không phải xác nhận pixel đã xuất hiện trên màn hình vật lý. Gắn ID click vào ảnh đầu tiên chụp sau lệnh cũng chưa chứng minh ứng dụng đích đã render kết quả. Giữ phép đo video có hai sự kiện nhìn thấy làm tiêu chí đầu cuối. Nguồn: `docs/superpowers/specs/2026-10-19-remote-desktop-design.md:234`, [System.nanoTime](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html#nanoTime()), [Swing repaint](https://docs.oracle.com/en/java/javase/21/docs/api/java.desktop/javax/swing/JComponent.html#repaint(java.awt.Rectangle)).

## 3. Các bất biến phải giữ

1. Ảnh, kích thước tọa độ, generation, inputEpoch và trạng thái gate phải nhất quán khi áp lên panel. Input epoch cũ bị từ chối; frame/callback của phiên cũ không được ghi đè sau disconnect/reconnect. Epoch là revision của stall/recovery, **không** thay cho frame sequence (`ServerSession.java:471,480`). **Gate chỉ mở sau khi frame mang đúng (generation, epoch) đó đã được `paintComponent` vẽ**, không phải khi snapshot vừa được áp; đóng gate thì có hiệu lực ngay khi áp. Code hiện tại chưa bảo đảm vế "đã vẽ" (`RemotePanel.java:53-54`); sửa trong đợt đổi vai (commit riêng, trước H2), test mô tả ở P2.
2. Frame đã publish không bị sửa lại. Buffer encoder có thể tái sử dụng khi thuộc riêng một worker; không chia sẻ mảng còn bị ghi với network/painter (`Snapshot.java:7`; `FrameEncoder.java:31`).
3. EDT không capture, encode, decode, chờ network hay ghi file. Không tạo một Runnable không giới hạn cho mỗi khung; dùng timer coalesce hoặc tối đa một lần dispatch đang chờ (`SnapshotTimer.java:7`; `RobotScreenSource.java:8`).
4. Mất focus, stall, hủy hoặc mất kết nối vẫn thả input; thay đổi tốc độ ảnh không thay deadline đóng phiên để che lỗi (`ServerSession.java:370,449`; `ClientSession.java:446`).
5. Không log ảnh, nội dung phím, mã xác thực hay token. Telemetry không được chặn luồng chức năng (`AppRuntime.java:55`; `Snapshot.java:17`).

## 4. Hợp đồng đo và cách so sánh

### Dữ liệu và môi trường

- Baseline B0: SHA thực tế của bản v2 (đổi vai + sửa gate), ghi khi đo; 12 FPS / width 1280 / quality 0,6, network và timeout như hiện tại. Ghi JDK thực chạy, OS, CPU/GPU, độ phân giải logic/ảnh, DPI, loại mạng và kích thước socket buffer thực tế.
- Cấu hình tham chiếu đề xuất: LAN có dây, màn hình chính, DPI 100%; ghi độ phân giải thật, không giả định máy có 1080p. Wi-Fi và DPI 125%/150% là các lượt riêng, không trộn chung phân phối.
- Warmup 60 giây; mỗi kịch bản tải ảnh chạy tối thiểu 120 giây. Gõ văn bản, cuộn trang/kéo cửa sổ, nội dung thay đổi liên tục và idle là các kịch bản riêng. So sánh cùng máy/nội dung/quality/width, đổi một biến mỗi thử nghiệm.
- Chạy ít nhất 3 lượt cho cấu hình đối chứng và cấu hình được chọn. Input-to-visible có tối thiểu 100 mẫu click và 100 mẫu gõ/cấu hình, phân bố qua các lượt; lưu số mẫu/từng lượt, không lấy trung bình p95 thành p95 chung.
- Video tối thiểu 60 FPS thấy đồng thời thao tác và kết quả trên panel. Ghi mốc đầu/cuối, công thức đổi số khung sang ms và độ phân giải phép đo; không công bố chênh lệch nhỏ hơn khả năng phân biệt của phép đo là cải thiện chắc chắn.

### Tên chỉ số bắt buộc phân biệt

| Chỉ số | Cách thu | Không được suy ra |
| --- | --- | --- |
| capture / encode / write_flush | Mốc trước/sau từng công đoạn trên Client | Thời gian write nhỏ không chứng minh ảnh đã tới Server |
| read_wait_and_packet / decode / publish | Mốc trên Server; read gồm chờ khung tiếp theo | Không gọi read là độ trễ mạng một chiều |
| publish_to_first_paint | Metadata local đi cùng đúng frame tới lần paint đầu | Không gọi là click-to-photon vật lý |
| sent / received / decoded / first-painted FPS | Counter riêng; paint đếm một lần mỗi sequence | Repaint cùng frame không phải tăng FPS |
| bytes/frame, KiB/s, RTT, CPU, heap/GC | Ghi theo lượt và từng phía | RTT kênh lệnh không thay input-to-visible |
| input-to-visible p50/p95/max | Từ video các sự kiện nhìn thấy | Không thay bằng lệnh đã được `Robot` thực thi |

CSV đề xuất cho telemetry: `run_id,role,generation,frame_sequence,metric,duration_ns,jpeg_bytes`. Metadata môi trường/profile lưu ở file kèm. Chỉ trừ timestamp trong cùng JVM. Dữ liệu hidden/minimized không vào percentile hiển thị, nhưng vẫn ghi số mẫu bị loại. Báo n/p50/p95/max, phạm vi lấy mẫu và số mẫu telemetry bị mất.

### 4.1 Quy tắc quyết định (chốt trước khi đo)

- **Trạng thái gate** chỉ dùng bốn giá trị: `CHƯA CHẠY`; `ĐÃ KHẢO SÁT` (có dữ liệu thật và phân tích nguyên nhân, nhưng chưa đạt ngưỡng hoặc dữ liệu chưa đủ); `CHƯA ĐẠT` (đủ dữ liệu, không đạt ngưỡng); `ĐẠT`. Phân tích nguyên nhân là đầu ra hợp lệ của khảo sát, không bao giờ là `ĐẠT`.
- **Ngưỡng ghi trước:** mỗi gate ghi ngưỡng số, kịch bản và cấu hình đối chứng vào file kết quả **trước** lượt đo đầu tiên của gate đó. Đổi ngưỡng sau khi có số phải ghi lý do và đo lại; không sửa ngưỡng để hợp thức hóa kết quả.
- **Quy tắc so sánh:** cấu hình mới chỉ được gọi là tốt hơn khi (a) mỗi phía có ≥3 lượt cùng kịch bản; (b) p95 gộp của cấu hình mới tốt hơn đối chứng một khoảng lớn hơn cả độ phân giải phép đo (video 60 FPS: 16,7 ms) lẫn biên độ p95 giữa các lượt của đối chứng; (c) không lượt nào của cấu hình mới kém hơn trung vị p95 các lượt đối chứng. Không đạt (b) hoặc (c) thì ghi "không phân biệt được", không ghi "cải thiện".
- **Ngưỡng dễ dùng (bắt buộc cho mọi cấu hình đổi quality/width/codec/tile):** bộ việc cố định đo ở P0 trên B0 và lặp lại y nguyên: đọc đoạn văn mẫu 11 pt và 9 pt (Notepad, DPI 100%), chọn chính xác một từ bằng kéo chuột, click 10 biểu tượng 16 px. Cấu hình chỉ hợp lệ khi tỷ lệ đọc đúng và click trúng không thấp hơn B0 quá ngưỡng ghi trước (đề xuất: đọc đúng ≥ 98% ký tự ở 11 pt, click trúng 10/10). Tốc độ đạt nhờ ảnh mờ dưới ngưỡng này không được tính.
- **Tuổi frame:** khi đã có P1b, gate có mục tiêu độ trễ phải kèm p95 tuổi frame và xu hướng theo thời gian (không tăng dần); FPS cao không thay được điều kiện này.

## 5. P0 — Baseline có thể tái lập

**Đầu ra:** hồ sơ đo và CSV/video trong `docs/superpowers/reviews/2026-10-27-remote-upgrade-results.md` cùng các file dữ liệu cạnh đó; đây là file sẽ tạo khi thực hiện, chưa phải kết quả hiện có.

- [ ] [AUTO] Xác nhận checkout/JDK, chạy `./mvnw.cmd -q verify` và ghi kết quả. Kiểm tra công cụ `LatencyReport` trước khi dùng: hiện mới được mô tả ở kế hoạch đợt 4, chưa có source trong checkout.
- [ ] [AUTO] Nếu chưa có công cụ percentile, thực hiện phần tương ứng task 4.2 hoặc dùng công cụ độc lập đã kiểm tra trên bộ số có đáp án. Ghi định nghĩa percentile và định dạng CSV; không tạo hai implementation trùng nhau.
- [ ] [HAI MÁY] Thu B0 theo mục 4. Kiểm tra ít nhất bốn góc, kéo chuột, modifier, mất focus và reconnect trước khi tin dữ liệu độ trễ; chạy ma trận DPI còn thiếu của đợt 3.
- [ ] Ghi baseline đạt/chưa đạt mục tiêu của đợt 4: input-to-visible p95 <150 ms và FPS nhận 10–12 trong LAN tham chiếu (`plans/2026-10-20-wave4-hardening-and-release.md:195`).
- [ ] [HAI MÁY] Đo bộ việc "ngưỡng dễ dùng" (mục 4.1) trên B0 để làm mốc chất lượng cho mọi so sánh sau.
- [ ] [HAI MÁY] **Kịch bản backlog**, hai dạng tách riêng, đều với nội dung chuyển động liên tục 120 giây sau khi phiên ACTIVE rồi bỏ giới hạn. Quay video đồng hồ/bộ đếm chạy trên Host cạnh panel của Viewer, đo độ chênh theo thời gian: có tăng dần không, và mất bao lâu để hết chênh sau khi bỏ giới hạn. Ghi FPS nhận cùng lúc để thấy trường hợp FPS bình thường nhưng ảnh cũ.
  - **(a) Backpressure tại ứng dụng:** `ThrottledOutputStream` (`src/test/java/com/ltm/rd/it/ThrottledOutputStream.java:44`) làm chậm chính thao tác `write` của Host. Dạng này kiểm tra Host chờ ghi rồi mới chụp tiếp, nhưng **không** tái hiện hàng đợi ở router: Host thấy ghi chậm nên không có trường hợp "ghi nhanh mà nhận muộn".
  - **(b) Hàng đợi sâu phía mạng:** một proxy TCP chỉ dùng cho thử nghiệm, đặt giữa Viewer và cổng ảnh của Host, đọc từ Host nhanh nhất có thể vào hàng đợi FIFO dung lượng `Q` byte và xả sang Viewer với tốc độ cố định `R` byte/giây; kênh lệnh đi thẳng, không qua hàng đợi. Host vì vậy ghi nhanh trong khi Viewer nhận muộn, giống bufferbloat. Ghi `R`, `Q`, độ chiếm hàng đợi theo thời gian và độ trễ hàng đợi tối đa lý thuyết `Q / R` (ví dụ R = 125 KiB/s, Q = 1 MiB thì tối đa khoảng 8 giây). Hàng đợi đầy thì proxy ngừng đọc, tức là đẩy backpressure về Host thay vì bỏ gói như router thật; ghi rõ giới hạn này trong kết quả.
  - Chỉ dùng `clumsy` hoặc công cụ mạng khác thay cho (b) khi đã kiểm chứng nó tạo được hàng đợi FIFO có tốc độ thoát và dung lượng xác định, và ghi lại cấu hình.

**Gate G0:** có dữ liệu thật cùng cấu hình và phương pháp; baseline chậm vẫn là dữ liệu hợp lệ để tối ưu. Lỗi sai tọa độ/kẹt input phải sửa trước khi tiếp tục tối ưu đường liên quan. Chưa có hai máy thì ghi thiếu bằng chứng, vẫn có thể chuẩn bị P1 và test logic; không kết luận đã tăng tốc.

## 6. P1 — Telemetry local, không đổi giao thức

**Điểm chạm:** `client/ScreenSender`, `server/FrameReceiver`, `server/ServerSession`, `server/ControlView`, `ui/Snapshot`, `ui/RemotePanel`; bộ thu/xuất metrics mới nếu cần. Tên là các lớp trong `src/main/java/com/ltm/rd/`.

- [ ] Thêm bộ thu opt-in, mặc định tắt; no-op khi tắt. Đo các stage ở mục 4, không thay thứ tự capture/encode/write và không thêm timestamp vào wire packet.
- [ ] Thêm metadata local `{generation, frameSequence, publishedAtNanos}` cùng frame trong transaction publish/control-view. Tăng sequence mỗi frame được publish, không dùng epoch làm sequence. Giữ metadata qua snapshot tới lần paint đầu, bỏ mẫu paint lại cùng sequence.
- [ ] Ghi metrics vào bộ nhớ cấp sẵn theo kích thước lượt đo (mảng `long` nguyên thủy; ví dụ 30 FPS × 300 s × 10 chỉ số ≈ 90.000 giá trị, khoảng 0,7 MB), xuất CSV ở worker riêng sau lượt đo hoặc khi phiên kết thúc; không chặn EDT/mạng, không giữ ảnh trong collector. Phiên kết thúc phải dừng collector và đóng file.
- [ ] Đầy bộ nhớ hoặc sink lỗi thì **đánh dấu cả lượt đo không hợp lệ** cho mục đích chọn profile, không chỉ đếm loss: mẫu mất thường rơi đúng lúc tải cao nên phần còn lại làm p95 đẹp hơn thực tế. Lượt dùng để quyết định phải có loss = 0.
- [ ] Nếu cần giảm overhead, chỉ lấy mẫu **xác định trước theo sequence** (ví dụ `frameSequence % k == 0`, `k` ghi trước khi chạy), không bao giờ bỏ mẫu theo tải hiện tại.
- [ ] Test có ý nghĩa: metadata không ghép nhầm frame qua stall/reconnect; repaint lặp không tăng FPS; sink chậm không chặn shutdown; tập mẫu có đáp án cho percentile. Không dùng giới hạn wall-clock mong manh trong headless CI để khẳng định hiệu năng.
- [ ] Đo bật/tắt trên cùng build, ghi overhead. Nếu overhead làm FPS hoặc CPU khác biệt theo quy tắc mục 4.1, dùng lấy mẫu xác định theo sequence và công bố `k`; không coi dữ liệu thiếu là toàn bộ phân phối.

**Gate G1:** không đổi wire/default/timeout, số liệu đối chiếu được với counter hiện tại và không gây rò tài nguyên. Lượt benchmark dùng để chọn profile phải có loss = 0 và ghi overhead/`k`.

## 6b. P1b — Tuổi frame và số frame đang bay (cần giao thức mới)

Liveness hiện tại không thấy ảnh cũ (mục 2.1). Đo được tuổi frame mà không trừ đồng hồ hai máy: Host ghi `captureAtNanos` theo `frameSequence` trong JVM của nó; Viewer gửi lại sequence trên kênh lệnh; Host tính `ackAt − captureAt` bằng chính đồng hồ của mình. Giá trị này gồm thêm đường về của gói ACK, nên là **cận trên** của tuổi frame; ghi kèm RTT kênh lệnh. Đây là v3, đi sau baseline B0 trên v2.

**Ai quyết định ảnh "đủ mới":** chỉ Host biết `captureAt` theo đồng hồ của mình, còn gate nằm ở Viewer. Viewer không tự tính được tuổi frame nếu không trừ đồng hồ hai máy. Vì vậy v3 đề xuất **Host là bên có thẩm quyền**, Viewer chỉ hiển thị và khóa theo:
- Mỗi thao tác điều khiển (`MOUSE_*`, `WHEEL`, `KEY_*`) mang `viewSeq` = `shownSeq` của ảnh đang hiển thị khi sự kiện được tạo. Host từ chối thao tác khi `now − captureAt(viewSeq) > A_max`, khi `viewSeq` không thuộc phiên hiện tại, hoặc khi `viewSeq` lớn hơn sequence đã gửi. Đây là kiểm tra trực tiếp "người dùng có đang thao tác trên ảnh cũ không", chỉ dùng một đồng hồ.
- Host theo dõi tuổi ảnh đang hiển thị: `now − captureAt(shownSeq mới nhất)`, tăng dần khi không có ACK mới. Vượt `A_max` thì Host chuyển sang `STALE`: nhả phím/nút đang giữ ngay trên máy Host, rồi gửi `FRESHNESS(STALE, ageMs)` trên kênh lệnh. Về dưới ngưỡng thì gửi `FRESHNESS(FRESH, ageMs)`. Ngoài lúc đổi trạng thái, Host gửi lại khoảng mỗi giây để Viewer hiển thị.
- Viewer nhận `STALE` thì xử lý như stall hiện tại: đóng gate, tăng epoch, hiện "Ảnh đang trễ X giây — tạm khóa điều khiển". Kiểm tra của Host vẫn là lớp chặn cuối cùng: thao tác đã gửi trước khi Viewer kịp nhận `STALE` vẫn bị Host từ chối.
- **Ngưỡng tuyệt đối** (giả thuyết, ghi trước khi đo): `A_max` = 1000 ms để cho phép điều khiển; mục tiêu p95 tuổi ảnh ở LAN ≤ 250 ms. Chỉ đòi "không tăng dần" là chưa đủ: ảnh luôn trễ đều 2 giây vẫn thỏa điều kiện đó.

**Hợp đồng ACK v3 (đề xuất, cần chốt trong spec v3 trước khi code):**
- `FRAME` mang `frameSequence` tăng 1 mỗi frame Host gửi trong một phiên, bắt đầu từ 1; phiên mới bắt đầu lại.
- Gói `FRAME_ACK(consumedSeq, shownSeq)` từ Viewer, đều là giá trị **cộng dồn**:
  - `consumedSeq`: sequence lớn nhất đã đọc trọn và giải mã xong. Mọi frame có sequence nhỏ hơn hoặc bằng nó được coi là đã xử lý xong, **kể cả frame bị bỏ qua không vẽ** vì đã có frame mới hơn trong slot latest. Ví dụ Host gửi 10 và 11, Viewer chỉ vẽ 11: `FRAME_ACK(11, 11)` giải phóng cả 10.
  - `shownSeq`: sequence lớn nhất đã được `paintComponent` vẽ lần đầu; chỉ dùng cho số đo tuổi frame và độ mới của gate, **không** dùng để điều tiết gửi.
- Kiểm tra phía Host: hai giá trị không giảm; ACK trùng (bằng giá trị cũ) là no-op hợp lệ; giảm, `consumedSeq` lớn hơn sequence đã gửi, hoặc `shownSeq > consumedSeq` là lỗi giao thức. Viewer gửi ACK khi giá trị đổi, gộp kiểu latest-only như `MOUSE_MOVE` trong `CommandWriter`, để ACK không làm đầy hàng đợi lệnh.
- **Frame bị bỏ qua chỉ đếm được ở Viewer.** `shownSeq` là số thứ tự, không phải số lượng: vẽ đủ frame 1–11 và chỉ vẽ frame 11 đều cho ACK cuối `(11, 11)`, nên Host không suy ra được số frame bỏ qua từ hai sequence. Viewer giữ counter cục bộ: đã giải mã, đã vẽ lần đầu, giải mã nhưng không vẽ. Ghi vào telemetry phía Viewer, không coi frame bỏ qua là lỗi. Chỉ thêm counter vào ACK khi Host thật sự cần; điều tiết gửi không cần.
- **Viewer ẩn hoặc thu nhỏ:** không có paint, nên `shownSeq` đứng yên nhưng `consumedSeq` vẫn tăng. Điều tiết gửi tiếp tục bình thường, gate đóng vì panel không hiển thị, stall/timeout của phiên vẫn dựa trên frame tới. **Không được** để giới hạn N phụ thuộc vào paint: nếu vậy, thu nhỏ cửa sổ làm Host ngừng gửi, Viewer không còn frame tới và phiên bị đóng sau frame timeout 15 giây (`Timeouts.java:35`).
- **Cửa sổ N:** số frame đang bay = `lastSentSeq − consumedSeq`. Host không gửi frame mới khi giá trị này ≥ N; khi chờ, Host chụp lại khung mới nhất lúc cửa sổ mở thay vì gửi khung đã chụp từ trước. Cửa sổ đầy lâu không tự đóng phiên; peer chết được phát hiện qua liveness kênh lệnh như hiện tại.
- **N là cấu hình thử, không phải hằng số:** thông lượng tối đa xấp xỉ `N / thời gian vòng capture→ACK`. Ví dụ vòng 200 ms với N = 2 chỉ cho khoảng 10 FPS, thấp hơn cả mục tiêu 25–30 FPS.
  - Tính N từ **thời gian vòng nền** đo khi mạng không nghẽn (p50 ở LAN rảnh, hoặc giá trị nhỏ nhất trong cửa sổ trượt): `N = ⌈FPS mục tiêu × vòng nền⌉`. **Không** dùng p95 đo lúc nghẽn: nghẽn 2 giây với mục tiêu 30 FPS sẽ cho N ≥ 60, tức cho phép giữ lại cả giây ảnh cũ.
  - Ba giới hạn cùng áp dụng: N ≤ `N_max` (giả thuyết: 4); số byte đã gửi mà chưa `consumed` ≤ thông lượng ước tính × tuổi mục tiêu; và ngưỡng `A_max` ở trên.
  - Khi băng thông không đủ để đạt FPS mục tiêu trong các giới hạn đó, **giảm tải** (FPS, quality, width theo P4) chứ không tăng N. Công thức trên chỉ để đánh giá có đạt được thông lượng hay không.
  - So tuổi frame và FPS giữa các N theo quy tắc mục 4.1.

- [ ] Viết spec v3 theo hợp đồng trên: `FRAME_ACK`, `viewSeq` trong thao tác điều khiển, `FRESHNESS(state, ageMs)`, miền giá trị, chỉ được phép ở ACTIVE, reconnect không dùng sequence cũ, quan hệ giữa `STALE` với stall/epoch hiện có, tăng `Protocol.VERSION` lên 3. Từ v3, telemetry của P1 dùng `frameSequence` trên wire thay cho sequence đánh cục bộ lúc publish, để số đo hai phía ghép được theo cùng một frame.
- [ ] Đo khi **chưa** bật giới hạn: p50/p95/max tuổi frame, số frame đang bay, số frame bỏ qua và thời gian vòng capture→ACK theo thời gian, ở LAN và kịch bản backlog P0.
- [ ] Sau khi có số đo mới bật giới hạn N. Test: Host gửi 10, 11, Viewer chỉ vẽ 11 thì ACK 11 giải phóng cả 10 và ghi nhận 10 bị bỏ qua; ACK trùng không đổi trạng thái; ACK giảm hoặc vượt số đã gửi đóng phiên với lỗi giao thức; Viewer thu nhỏ 60 giây thì phiên vẫn sống và Host vẫn gửi; link chậm không làm tuổi frame tăng dần; ACK đứng thì Host ngừng gửi nhưng gate phía Viewer đóng theo stall và input được nhả như hiện tại. Thêm cho freshness: thao tác có `viewSeq` cũ hơn `A_max` bị Host từ chối kể cả khi Viewer chưa nhận `STALE`; Host chuyển `STALE` khi đang giữ Ctrl thì Ctrl được nhả trên Host; Viewer nhận `STALE` thì đóng gate và tăng epoch; về `FRESH` thì chỉ mở lại sau khi vẽ ảnh mới; `viewSeq` của phiên cũ hoặc vượt số đã gửi bị từ chối.
- [ ] Tính N theo vòng nền và ba giới hạn ở trên; ghi `N_max`, giới hạn byte và `A_max` vào file kết quả trước khi đo.

**Gate G1b:** có p95 tuổi frame ở LAN và kịch bản backlog (cả hai dạng hàng đợi ở P0). Với N đã chọn: p95 tuổi ảnh ở LAN ≤ 250 ms; ở link chậm tuổi ảnh không tăng dần trong 120 giây **và** không vượt `A_max` khi đang cho điều khiển (vượt thì phải ở trạng thái `STALE` với input bị khóa); về mức LAN trong thời gian ghi trước sau khi bỏ giới hạn; FPS ở LAN không thấp hơn khi chưa bật giới hạn theo quy tắc mục 4.1. Các ngưỡng là giả thuyết của lượt thử đầu.

## 7. P2 — Giảm chờ hiển thị, giữ Snapshot nguyên khối

**Điểm chạm:** `ui/SnapshotFeed`, `SnapshotPump`, `SnapshotApplier`, `RemotePanel`, `ControlFrame`, `ServerFrame`. Chưa tách ảnh thành kênh UI độc lập ở lần đầu.

- [ ] Ở phiên ACTIVE, thử giảm cả nhịp poll và timer từ 50 xuống 16 ms; giữ thống kê khoảng 1 giây. Chạy ở 12 FPS để cô lập lợi ích hiển thị. Khi không ACTIVE giữ nhịp cũ; không thêm timer/worker không được quản lý vòng đời.
- [ ] Chỉ repaint khi trạng thái hình ảnh cần đổi: frame mới, kích thước/phiên bị xóa hoặc visual state đổi. Vẫn áp gate/epoch khi chỉ trạng thái điều khiển đổi; resize và repaint do hệ điều hành vẫn được phép vẽ lại frame.
- [ ] Giữ frame+gate của cùng snapshot, kiểm tra lại stale generation/epoch ở runtime. Không bỏ cập nhật đóng gate chỉ vì frame reference chưa đổi.
- [ ] **Tách "đã áp" và "đã vẽ" trong `RemotePanel`:** `apply()` đóng gate ngay nếu snapshot cấm điều khiển, nhưng chỉ ghi gate *chờ mở*; `paintComponent` sau khi `drawImage` frame đó mới chuyển gate sang mở cho đúng (generation, epoch). `inputGate()` chỉ trả gate đã vẽ. Panel bị ẩn/thu nhỏ thì không mở gate. Sửa này không đổi wire format; **làm trong đợt đổi vai v2**, commit và test riêng, xong trước H2. P2 chỉ kiểm lại sau khi giảm nhịp hiển thị.
- [ ] Test thứ tự trên EDT (headless được, gọi `paintComponent` với `Graphics` của `BufferedImage`): apply snapshot mở gate → input trước paint bị từ chối → paint → input được nhận; hồi phục sau stall: input trước lần paint frame mới bị từ chối; snapshot đóng gate có hiệu lực ngay không cần paint; frame của generation cũ được vẽ không mở gate của generation mới.
- [ ] Mở rộng `RemotePanelTest`, `SnapshotPumpTest` và test runtime phù hợp: stall/recovery không lọt input cũ; disconnect xóa ảnh/gate; reconnect không hiện ảnh cũ; paint/telemetry đếm đúng frame.
- [ ] [HAI MÁY] Đối chiếu B0/P1: publish-to-first-paint, input-to-visible, CPU/repaint ở idle và khi chuyển động. Mục tiêu thiết kế local là p95 publish-to-first-paint ≤33 ms khi panel hiển thị; đây là gate đề xuất cần đo, không phải số hiện đạt.

**Gate G2:** `ĐẠT` khi test thứ tự apply/paint/input xanh, input an toàn trên hai máy và p95 publish-to-first-paint ≤ 33 ms (ngưỡng ghi trước, so theo mục 4.1). Có số đo nhưng chưa đạt thì `ĐÃ KHẢO SÁT` kèm phân tích nguyên nhân, không chuyển sang P3 với lý do "đã giải thích". Nếu hai nhịp 16 ms vẫn thêm chờ đáng kể, tạo task riêng cho immutable presentation envelope + latest slot + một dispatch đang chờ; notification phải xuất từ transaction và hủy được khi phiên đóng. Không trộn refactor đó với tăng FPS.

## 8. P3 — Cấu hình và thử nghiệm socket/encoder

**Điểm chạm:** `LaunchOptions`, `ui/AppConfig`, `client/ClientConfig`, `ClientSession`, `FrameEncoder` và test tương ứng.

- [ ] Thêm cấu hình Client cho FPS, quality và maxWidth; truyền xuyên suốt tới sender/encoder. Mặc định giữ 12/0,6/1280. Giới hạn đề xuất: FPS 1–30, quality 0,1–1, width 1–1280; encoder vẫn clamp theo màn hình và giữ quy tắc tỷ lệ của wire format hiện hành.
- [ ] Bật NODELAY cho socket lệnh Client trước khi gửi AUTH theo spec. Thử riêng NODELAY socket ảnh bật/tắt; ghi cả `getSendBufferSize()`/`getReceiveBufferSize()`, không coi số 64 KiB yêu cầu là kích thước thật hoặc giới hạn số frame đang bay. [Socket options](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/Socket.html).
- [ ] Sweep nhanh 12/20/25/30 FPS, cố định width/quality; chỉ cấu hình được chọn mới chạy đầy đủ ma trận mục 4. Không đổi NODELAY, FPS và quality cùng một lượt đối chứng.
- [ ] Nếu profile cho thấy allocation/GC đáng kể, thử tái sử dụng writer/buffer trên đúng worker và dispose cuối phiên. Đảm bảo byte/frame đã gửi/publish không bị sửa; rollback nếu không có lợi.
- [ ] Test cấu hình hợp lệ/sai, khung ở nhiều width vẫn hợp lệ với wire format hiện hành và pointer vẫn theo kích thước SCREEN_INFO; chạy lại handshake/cancel/socket-failure khi thay khởi tạo socket.

**Gate G3:** profile cao hơn chỉ được gọi là `ĐẠT` khi first-painted FPS ≥90% FPS đặt trong tải chuyển động đã ghi, input-to-visible p95 <150 ms trên LAN tham chiếu, không tăng dần backlog/heap (tuổi frame nếu đã có P1b), và nếu đổi quality/width thì qua "ngưỡng dễ dùng" mục 4.1. So với đối chứng theo quy tắc mục 4.1. 25–30 FPS là mục tiêu thử nghiệm, không phải điều kiện buộc mọi máy đạt. Ghi CPU/GC để giải thích tradeoff. Chọn cấu hình tốt nhất trong số cấu hình hợp lệ; giữ cấu hình mặc định của bản đối chứng v2 trong task này.

## 9. P4 — Thích ứng và backlog, chỉ khi có bằng chứng

- [ ] Bản đầu dùng tín hiệu local: thời gian vòng capture/encode/write, byte/frame, mức FPS đạt được. Write chậm gợi ý backpressure; write nhanh không chứng minh ảnh còn mới. Không tính one-way latency từ hai đồng hồ khác nhau.
- [ ] Đề xuất thử nghiệm: giảm quality theo nấc nhưng không xuống dưới "ngưỡng dễ dùng" mục 4.1 (nấc thấp nhất phải được đo qua bộ việc đó trước khi cho phép), rồi width 1280→960→640 (clamp theo màn hình); tăng lại chậm hơn. Có hysteresis, dwell time và profile cố định để A/B; ghi rõ ngưỡng sau P1, không gán ngưỡng theo phỏng đoán.
- [ ] Nếu capture/encode đã quá tải thì giảm FPS trước; không ép tăng FPS để bù ảnh thiếu. Thử bước giới hạn băng thông sau ACTIVE, phục hồi mạng và kiểm tra profile hồi phục, input không kẹt, heap/queue không tăng mãi.
- [ ] Chỉ tách reader/decoder nếu decode là nút thắt: một reader duy nhất đọc trọn packet có giới hạn, slot latest dung lượng 1 và một decoder đang chạy; không dùng `available()` làm ranh giới frame, không cắt byte đang truyền. Chốt cách xử lý packet/JPEG hỏng trước khi cho phép bỏ decode, vì code hiện tại decode từng FRAME (`FrameReceiver.java:47-53`). Nhánh này chưa mặc định thuộc phạm vi triển khai P4.
- [ ] Không áp dụng bỏ tùy ý frame cho tile/H.264 phụ thuộc khung. Write cục bộ nhanh **không** loại trừ ảnh cũ: khi có bufferbloat, send buffer xả nhanh vào hàng đợi router. Vì vậy tín hiệu thích ứng chính phải là tuổi frame/số frame đang bay của P1b; nếu chưa có P1b thì P4 chỉ được thử trên link chậm với ghi chú "chưa kiểm soát tuổi frame".

**Gate G4:** `ĐẠT` khi benchmark link yếu tốt hơn profile cố định theo quy tắc mục 4.1, p95 tuổi frame không tăng dần (cần P1b), mọi nấc dùng đến đều qua "ngưỡng dễ dùng", link phục hồi không dao động profile liên tục, test stall/epoch/release vẫn đúng. Thiếu P1b thì tối đa là `ĐÃ KHẢO SÁT`.

## 10. C — Internet bằng phiên/ID, làm độc lập với dirty-rect

**Phạm vi đầu:** hai endpoint chủ động nối ra relay, một phiên, hai luồng, ghép cặp có xác thực; chưa làm hole punching, unattended access, UAC hay thay remote input. Host/Viewer (sau đợt đổi vai) là vai trò ứng dụng, tách khỏi chiều TCP; vai trò TLS có thể giữ như cũ dù cả hai kết nối TCP outbound.

- [ ] **Giới hạn thử mã không theo IP socket:** qua relay (và cả CGNAT/NAT chung khi kết nối trực tiếp) nhiều peer có cùng IP, nên khóa theo IP hiện tại (`HandshakeHandler.java:65-66`, `ServerCore.java:85`) để kẻ dò mã khóa luôn người dùng thật. Sau mTLS, đếm sai mã theo danh tính thiết bị đã xác thực (fingerprint certificate) và theo ticket/phiên; thêm trần tổng số lần sai mỗi Host trong một khoảng thời gian để chống nhiều danh tính. IP relay chỉ dùng cho giới hạn tài nguyên của relay, không cho quyết định khóa đăng nhập.

- [ ] **C0 — danh tính và transport:** lập spec ghép cặp, bootstrap/thu hồi khóa, quyền bắt đầu/đồng ý phiên. Prototype dùng certificate/pin peer provision qua kênh tin cậy và mTLS đầu-cuối; ID chỉ dùng tìm máy. Không tự chấp nhận certificate, không coi mã 6 số là toàn bộ danh tính.
- [ ] Tách socket creation/accept khỏi session ở `ServerCore` và `ClientSession`; seam `SocketConnector` hiện chỉ bọc `connect`, chưa đủ cho endpoint điều khiển nhận transport đã ghép. Chứng minh direct LAN vẫn hoạt động và không viết hai bản session/input riêng.
- [ ] **C1 — relay local:** định nghĩa registration/ticket theo cặp peer, role, channel-kind và TTL. Ticket ngẫu nhiên một lần; giới hạn số phiên chờ, byte, handshake timeout và cleanup khi chỉ tới một kênh. Không ghép phiên chỉ bằng ID hay IP công bố bởi client.
- [ ] Registration, ghép kênh và inner-TLS có deadline hữu hạn riêng, tài nguyên tạm được đăng ký để cancel/shutdown đóng được. Chỉ bắt đầu reservation của session sau khi transport đã sẵn sàng; không tăng timeout ảnh/lệnh toàn cục để che thời gian chờ ghép relay.
- [ ] Relay chuyển tiếp byte inner-TLS giữa hai ứng dụng trên từng kênh; TLS endpoint↔relay cho điều phối không thay inner-TLS. Giữ hai kênh riêng hoặc có lịch ưu tiên đã kiểm chứng để ảnh không chặn lệnh. Token ATTACH vẫn ràng buộc đúng session sau xác thực.
- [ ] Kiểm tra âm: sai pin/certificate, ticket hết hạn/tái sử dụng, đổi role, trộn kênh từ phiên khác, relay thay peer, relay mất giữa lúc giữ Ctrl, kênh ảnh không tới; **hai peer qua cùng relay**: peer A nhập sai mã đủ số lần khóa, peer B hợp lệ vẫn kết nối được ngay. Fail closed, nhả input, không âm thầm fallback plaintext; private key không vào repo/log. Không bật TLS early data cho thao tác điều khiển vì nguy cơ replay ([RFC 8446 §8](https://www.rfc-editor.org/rfc/rfc8446.html#section-8)).
- [ ] **C2 — thử Internet:** chỉ sau C0/C1 có bằng chứng; thử LAN khác mạng, Wi-Fi↔di động/CGNAT, relay restart và reconnect. Ghi RTT, input-to-visible, throughput, CPU và byte relay thực mang; không hứa giữ mốc LAN 150 ms cho mọi đường Internet.
- [ ] Sau relay-only mới quyết định cần ID lâu dài/rendezvous/direct-first hay WebRTC. ICE-TCP có tồn tại nhưng số liệu simultaneous-open năm 2005 không phải dự báo mạng hiện tại. [RFC 6544](https://www.rfc-editor.org/rfc/rfc6544), [WebRTC signaling/ICE](https://webrtc.org/getting-started/peer-connections).

**Gate GC:** cả hai kênh xác minh đúng peer, có bằng chứng test chống ghép sai/replay, test hai peer cùng relay không khóa nhầm nhau, và thử hai mạng thật. Mã hóa chỉ tới relay không đạt yêu cầu E2E. Relay là khả năng kết nối dự phòng, không tự giảm latency.

## 11. B1 — Tile/delta có điều kiện, cần spec version tiếp theo trước code

- [ ] Spike offline với cùng ảnh capture, so FULL JPEG và tile 64/128 px cho văn bản, cuộn/kéo và chuyển động liên tục. Tính cả diff, encode, decode, compose/copy, byte và chất lượng chữ; không kết luận bằng ngưỡng diện tích đổi 50% chưa đo.
- [ ] Spec version tiếp theo định nghĩa FULL/DELTA (dùng lại `frameSequence` và ACK của v3), sequence/base-frame, kích thước ảnh mã hóa khác kích thước màn hình logic, giới hạn tọa độ/số ô/tổng byte/pixel và hành vi base sai. Hai bản không tương thích phải báo BAD_VERSION rõ, không âm thầm hiểu gói khác nghĩa.
- [ ] FULL đầu phiên/reconnect; mỗi DELTA áp theo thứ tự lên baseline đúng. Không bỏ delta trung gian. Khi FULL rẻ hơn tổng tile+overhead thì gửi FULL, theo ngưỡng từ spike.
- [ ] Canvas ghép thuộc riêng decoder; publish ảnh bất biến, không sửa ảnh painter đang giữ. Kiểm tra tổng allocation/heap, không chỉ byte mạng.
- [ ] **Tách liveness capture khỏi việc truyền lại toàn ảnh.** Không gửi FULL refresh định kỳ mỗi giây: ví dụ giả định ảnh 500 KiB ở 125 KiB/s mất 4 giây, vượt stall 3 giây (`Timeouts.java:34`) và chiếm hết băng thông của delta. Thay bằng: sau mỗi lần chụp thật mà không có ô đổi, gửi một DELTA rỗng nhỏ mang `frameSequence` riêng (chứng minh capture còn chạy, PING kênh lệnh thì không); FULL chỉ gửi khi đầu phiên, reconnect, base sai, hoặc theo ngân sách refresh tính từ thông lượng đo được (đề xuất: FULL định kỳ dùng không quá 20% thông lượng). Ghi thay đổi monitor vào spec version tiếp theo.
- [ ] **DELTA rỗng phải gắn với đúng ảnh nền**, không phải heartbeat:
  - Mang `baseSeq` là sequence của ảnh mà Host đã so sánh để kết luận "không đổi", và chỉ được gửi khi mọi thay đổi chụp trước đó đã được ghi vào kênh ảnh. Chỉ đi trên kênh ảnh, theo đúng thứ tự TCP sau các DELTA/FULL trước nó; không bao giờ gửi trên kênh lệnh hay một đường ưu tiên khác, vì như vậy nó có thể tới trước nội dung đang chờ truyền.
  - **Kiểm tra base theo canvas của decoder, không theo ảnh đã vẽ.** `baseSeq` phải bằng sequence mà canvas tái dựng của decoder đang giữ; lệch thì là base sai (yêu cầu FULL). Ví dụ decoder đã ghép frame 11 trong khi EDT còn hiển thị frame 10, rồi DELTA rỗng có `baseSeq = 11` tới: base hợp lệ, không được yêu cầu FULL. Viewer thu nhỏ cửa sổ cũng không làm base sai.
  - Điều kiện "đã vẽ" chỉ áp cho `shownSeq` và gate. Mỗi ảnh publish mang hai số: `contentSeq` (frame cuối cùng làm đổi nội dung) và `validAsOfSeq` (frame cuối cùng xác nhận nội dung, gồm cả DELTA rỗng). Vẽ một ảnh thì `shownSeq = validAsOfSeq` của ảnh đó. DELTA rỗng tới khi ảnh đang vẽ có cùng `contentSeq` thì Viewer được tăng `shownSeq` mà không cần vẽ lại, vì nội dung trên màn hình giống hệt.
  - Với P1b, DELTA rỗng có sequence riêng và được ACK như frame thường, nên tuổi frame vẫn đo được khi màn hình tĩnh.
- [ ] **Giữ kết nối khác với cho phép điều khiển.** Frame lớn đang truyền dở có thể làm hai việc khác nhau:
  - *Timeout đóng phiên* (15 giây, `Timeouts.java:35`): tiến độ byte của packet đang đọc **được phép** hoãn timeout này, vì kết nối vẫn sống.
  - *Stall và gate* (3 giây, `Timeouts.java:34`): tiến độ byte **không** được giữ gate mở. Gate chỉ mở khi có ảnh hoàn chỉnh đã áp, đã vẽ và đủ mới (theo tuổi frame của P1b khi đã có). Ảnh trên màn hình không đổi suốt 4 giây vì FULL đang truyền dở thì khóa input là đúng: người dùng đang nhìn ảnh cũ.
  - Để giảm số lần khóa như vậy, Host nên tránh FULL có thời gian truyền ước tính (byte / thông lượng) vượt ngưỡng stall bằng cách giảm width/quality riêng cho FULL đó, vẫn qua ngưỡng dễ dùng. Ghi chú: code hiện tại đã stall khi một frame đầy đủ truyền quá 3 giây.
- [ ] Test dựng lại ảnh qua nhiều delta, cạnh tile/đổi width, base sai, quá nhiều ô/byte, ảnh cũ không bị mutate, disconnect/reconnect, idle không stall giả và capture thật dừng vẫn đóng gate. Thêm: DELTA rỗng có `baseSeq` lệch không giữ gate mở; FULL truyền dở quá 3 giây thì gate đóng nhưng phiên không bị timeout khi byte vẫn tới; DELTA rỗng không thể tới trước DELTA có nội dung gửi trước nó.

**Gate GB1:** có lợi trên workload mục tiêu sau khi tính diff/compose/allocation và refresh, theo quy tắc mục 4.1; không làm xấu độ trễ; qua "ngưỡng dễ dùng"; màn hình tĩnh trên link 125 KiB/s không gây stall giả. Nếu không có lợi thì giữ FULL JPEG, không đưa tile vào mặc định. Robot vẫn chụp toàn màn hình; B1 không giải quyết được trần capture.

## 12. B2 — Native theo hai spike tách biệt

- [ ] **B2a capture:** thử Desktop Duplication trên Host nhưng giữ JPEG và wire format hiện hành để cô lập lợi ích capture. Đo cả GPU→CPU copy/chuyển BGRA→RGB và encode, không chỉ thời gian `AcquireNextFrame`. Xử lý timeout/ACCESS_LOST, reconnect và DPI bằng dữ liệu thật.
- [ ] So sánh JNI/JNA/tiến trình phụ bằng prototype nhỏ; chưa mặc định dùng FFM preview hoặc nâng JDK. Java 21 + JAR hiện tại phải có phương án đóng gói ngoài IDE, nạp native thất bại có thông báo và đường Robot dự phòng được chọn rõ trước phiên.
- [ ] **B2b codec:** chỉ nếu B2a/P1 cho thấy cần; spike H.264 low-latency hai đầu, gồm BGRA→NV12/YUV, encode phần cứng nếu có, decode và đường đưa ảnh vào Swing. Đo toàn pipeline/copies; không suy ra GPU đồng nghĩa nhanh hơn.
- [ ] Trước tích hợp H.264, viết spec codec/config/keyframe/reset, giới hạn tài nguyên, cách thay transport và điều kiện bỏ frame phụ thuộc. Không tự bỏ P/B frame như FULL JPEG.
- [ ] Thử DLL/JAR trên máy sạch đúng kiến trúc, ngoài IDE, GPU không hỗ trợ và GPU reset. Ghi dependency/giấy phép khi chọn thư viện; giữ UAC/màn hình khóa ngoài phạm vi.

**Gate GB2:** `ĐẠT` khi so với profile Java tốt nhất (theo quy tắc mục 4.1) cải thiện ít nhất một trong hai chỉ số ghi trước (đề xuất: input-to-visible p95 giảm ≥ 20% hoặc first-painted FPS tăng ≥ 30% ở cùng ngưỡng dễ dùng), không chỉ số nào xấu đi ngoài biến thiên giữa các lượt, và đóng gói chạy được trên máy sạch. Nếu capture native + JPEG đủ đáp ứng mục tiêu thì có thể dừng ở B2a, không bắt buộc H.264.

Nguồn: [Desktop Duplication](https://learn.microsoft.com/en-us/windows/win32/direct3ddxgi/desktop-dup-api), [H.264 encoder](https://learn.microsoft.com/en-us/windows/win32/medfound/h-264-video-encoder), [JEP 454: FFM final ở Java 22](https://openjdk.org/jeps/454). Bộ encoder phần mềm Microsoft mặc định slice encoding; low-latency không phải lời hứa chung cho mọi hardware/codec.

## 13. Hoàn tất từng nhóm và phạm vi phiên triển khai đầu

Phiên triển khai đầu nên giới hạn ở **P0/P1 và P2**. Cấu hình cao hơn, thích ứng, relay và native chưa được gộp vào cùng thay đổi. Không tự mở port công cộng hoặc triển khai server khi thực hiện phần đo/giảm trễ LAN.

- [ ] Mỗi thay đổi giữ một biến thử nghiệm; ghi profile cũ/mới, commit/build và cách quay lại đối chứng. Không tự commit/push trong công việc chỉ yêu cầu chỉnh kế hoạch.
- [ ] Chạy test liên quan sau thay đổi; khi chốt một nhóm chạy `./mvnw.cmd -q verify`. Test headless kiểm chứng logic, thử hai máy kiểm chứng Robot/DPI/latency; không thay thế nhau.
- [ ] Trước phát hành cấu hình được chọn, chạy lại gate ổn định cuối đợt 4: hoạt động 30 phút, 50 lần connect/disconnect, cắt mạng khi giữ input, focus/minimize, DPI/shutdown. Không bắt chạy lại toàn bộ phép thử dài sau mọi chỉnh sửa nhỏ.
- [ ] Báo cáo nêu đạt/chưa đạt từng gate, dữ liệu còn thiếu, profile khuyến nghị và nhánh cần làm tiếp. Chưa có số đo thì ghi “chưa kiểm chứng”; không đổi mục tiêu để hợp thức hóa kết quả.

Con trỏ từ xa và nhiều màn hình là backlog riêng. Con trỏ native cần giao thức vị trí/hình dạng và quy tắc phối hợp con trỏ local; nhiều màn hình cần lựa chọn màn hình, tọa độ/DPI và xử lý topology. Chúng không phải điều kiện bắt đầu P0–P3 và không được tự gộp vào thay đổi tối ưu.
