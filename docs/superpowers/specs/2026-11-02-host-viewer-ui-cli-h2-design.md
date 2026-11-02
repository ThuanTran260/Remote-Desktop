# Đề xuất Phần 3–4: UI/CLI và nghiệm thu H2 Host/Viewer v2

Ngày: 2026-11-02. **Trạng thái: Người dùng đã duyệt thiết kế để tiếp tục B1/B2.** Câu trả lời phê duyệt: “Duyệt thiết kế, tiếp tục B1/B2 (Khuyến nghị)”. Builder/re-review vòng 1 và review toàn đợt Codex Astra high đã PASS / APPROVED, cả hai finding cũ ADDRESSED. Cho phép lập plan và thực thi B1/B2; phê duyệt thiết kế không phải bằng chứng code/test v2 hoặc H2 đã chạy.

Nguồn đọc cố định: `369440779658424e631b4d0a965f870dde5ea7b9` (gọi tắt **S**). Mọi `path:line` dưới đây thuộc S, được đọc bằng `git show S:path`, không phải số dòng của working tree có worker khác đang sửa. Tham chiếu tương đối tính từ gốc repository. Đặc tả chính: `docs/superpowers/specs/2026-11-01-host-viewer-design.md` (gọi tắt **HV**). “Phần 3–4” là hai phần UI/CLI và tài liệu/H2 trong bảng trạng thái HV, nay đã duyệt; không thay thế mục `## 3. Kiểm thử chặn lỗi` đã duyệt của HV.

## 1. Ý định, điều đã chốt và phạm vi đề xuất

Người dùng đã chốt Host là máy bị điều khiển, lắng nghe và đưa mã; Viewer là máy điều khiển, nhập IP/mã. Đưa mã là đồng ý chia sẻ, không thêm hộp thoại chấp thuận ở Host. Hướng kết nối, handshake, deadline, gate sau paint, P2 prepare worker, ba future giữ slot và ownership executor tuân theo HV mục 0–2; bản nháp không mở lại các quyết định này.

Mục tiêu mới là người dùng nhìn tên cửa sổ, help hoặc README đều biết phải chạy trên máy nào; thao tác Hủy/Dừng/Ngắt có nghĩa nhất quán; UI không báo sẵn sàng khi Host còn chạy adapter hoặc nhả input. Phần H2 phải ghi được kết quả thật trên hai máy và tách bằng chứng đó khỏi test giả/headless.

Tên cờ, chính sách bỏ cú pháp cũ, tên lớp UI, cách giữ thông báo lỗi nhập liệu, bố trí cửa sổ và cách phân bố hai lượt H2 bên dưới đều là **đề xuất của người soạn**, chưa phải quyết định người dùng. Không thêm dependency, lớp điều phối tổng quát, seam dự phòng, chức năng ghép lại một kênh, tự reconnect, cấp quyền mới, FRAME_ACK/freshness v3 hay đổi giá trị timeout. Cleanup pending ticker thuộc Task 1 và không được bản nháp này chứng nhận.

## 2. Hiện trạng tại S và các cách ánh xạ

| Bằng chứng tại S | Ý nghĩa cho v2 |
| --- | --- |
| `src/main/java/com/ltm/rd/LaunchOptions.java:7`, `:15`, `:67`, `:78`; `src/test/java/com/ltm/rd/LaunchOptionsTest.java:58`, `:64` | `SERVER` điều khiển, `CLIENT` bị điều khiển; `--host IP` là địa chỉ Server v1. Cả không chọn role + `--host IP` hiện cũng hợp lệ. Không được diễn giải token `--host` cũ thành vai Host mới. |
| `src/main/java/com/ltm/rd/Launcher.java:32`, `:41`, `:45`, `:51`; `src/test/java/com/ltm/rd/LauncherTest.java:36`, `:65`, `:98` | Parse trước help, rồi kiểm headless, rồi mở GUI; CLI chỉ điền địa chỉ, không tự kết nối. Exit 0/2/4/1 và không in chi tiết exception đã có hợp đồng. |
| `src/main/java/com/ltm/rd/ui/GuiApp.java:48`, `:70`; `src/main/java/com/ltm/rd/ui/RoleChooserFrame.java:23` | Init runtime ngoài EDT; Server tự start lắng nghe, chooser dùng tên vai v1. |
| `src/main/java/com/ltm/rd/ui/ServerFrame.java:31`, `:71`; `src/main/java/com/ltm/rd/ui/ClientFrame.java:31`, `:75` | Form IP/mã do ServerFrame giữ; form nhập địa chỉ do ClientFrame giữ. Cần chuyển công việc của cửa sổ phụ theo vai, không đổi tên rồi giữ nguyên cả form. |
| `src/main/java/com/ltm/rd/ui/ClientFrame.java:89` | `pendingText` lỗi nhập bị snapshot IDLE mang message cũ xóa. V2 phải sửa trong thay đổi UI có kiểm chứng riêng. |
| `src/main/java/com/ltm/rd/ui/ControlFrame.java:31`, `:57`, `:76`; `src/main/java/com/ltm/rd/ui/SharingBar.java:22`, `:45` | ControlFrame sở hữu capture và timer thống kê; SharingBar không nhận focus và luôn trên cùng. Hai cửa sổ phụ thuộc hai vai khác nhau. |
| `src/main/java/com/ltm/rd/ui/AppRuntime.java:133`, `:295`, `:325`, `:375`, `:665`; `src/main/java/com/ltm/rd/ui/Controller.java:10` | Runtime đang sở hữu tài nguyên; input executor ở Client v1, UI điều khiển ở Server v1. Phải chuyển ownership thật trong B1. |
| `src/main/java/com/ltm/rd/ui/Messages.java:28`, `:38`, `:49`; `src/main/java/com/ltm/rd/ui/Snapshot.java:15` | Text được chuẩn hóa, không hiển thị exception/close message nội bộ; snapshot không in mã/IP/ảnh. Giữ nguyên nguyên tắc này khi đổi vai. |
| `src/main/java/com/ltm/rd/ui/SnapshotFeed.java:16`, `:33`; `src/main/java/com/ltm/rd/ui/RemotePanel.java:56`, `:106`, `:115` | Pump đọc snapshot nền, timer áp trên EDT; gate mở sau paint đã tồn tại tại S. Không coi việc sửa gate là việc mới của bản nháp. |
| `README.md:7`, `:13`, `:55`, `:60`, `:65`, `:81` | README còn nói chưa có UI/Robot và launcher chưa triển khai dù source/test đã có; sơ đồ và Firewall vẫn theo vai v1. Cần cập nhật cả sự thật hiện trạng và hướng chạy, không chỉ thay chữ. |

### 2.1 Các phương án thực tế

| Phương án | Đánh đổi | Kết luận đề xuất |
| --- | --- | --- |
| Giữ `--server`/`--client`, chỉ đổi nhãn UI | Ít sửa parser nhưng tên TCP tiếp tục đối nghịch ý nghĩa người dùng, script cũ có thể chạy đúng listener nhưng sai máy điều khiển; README phải giải thích hai hệ tên. | Không chọn. |
| `--host`/`--viewer`, địa chỉ chuyển sang `--address` | Tên ngắn; phải cấm toàn bộ `--host IP` cũ và tránh parser coi IP là đối số thừa sau khi đã chọn Host. Tên cờ cũ đã đổi nghĩa rất dễ gây nhầm. | Có thể làm bằng breaking migration, nhưng không đề xuất. |
| **`--role host` / `--role viewer` và `--address ADDRESS`**, từ chối toàn bộ cờ vai/địa chỉ cũ | Dài hơn; vai và địa chỉ tách rõ, không dùng lại `--host` với nghĩa khác. Một bộ parse chung và một bản help đủ giải thích. | **Đã được người dùng chọn và duyệt ngày 2026-11-02.** |

UI đề xuất dùng lại hai form phù hợp hướng kết nối, di chuyển ControlFrame sang form kết nối và SharingBar sang form lắng nghe. Giữ hai form riêng giúp logic enable/disable có locality; một form đa vai sẽ cần nhiều nhánh và ownership cửa sổ khó rõ. Không tạo tầng UI đa vai mới. Việc chuyển handler/cửa sổ phụ trong B1 là đổi hành vi; đổi tên file/lớp trong B2 chỉ cơ học.

## 3. Phần 3 — Hợp đồng UI và CLI đề xuất

### 3.1 CLI v2 chính xác

Help dự kiến (chỉ là nội dung đề xuất, **jar tại S chưa nhận cú pháp này**):

```text
Usage: java -jar remote-desktop.jar [--role host|viewer] [options]
  --role host       controlled machine; listens and displays its connection code
  --role viewer     controlling machine; enters Host address and code
  (no --role)       show the role chooser
  --address ADDRESS pre-fill Host address; requires --role viewer; never auto-connects
  --cmd-port N      command port, 1..65535 (default 5001)
  --img-port N      image port, 1..65535 (default 5000; must differ from command port)
  --help            validate arguments, print help and exit
Legacy --server, --client and --host IP are rejected; use the v2 role on the correct machine.
Enter the six-digit connection code in the Viewer window. Do not put it on the command line.
```

Tên role phân biệt hoa/thường: chỉ `host`, `viewer`; đối số theo token rời như help, không có `--role=host`, cờ rút gọn, positional argument hay chế độ headless runtime. Thứ tự cờ không quan trọng. Không có `--pin`, `--code`, biến môi trường mã hoặc tự kết nối. `--address` chỉ là preset; thiếu address ở Viewer vẫn mở form trống. Address phải trim rồi không rỗng, không bắt đầu bằng `--`; vẫn để connector phân giải tên/IP như đường v1, không DNS/I/O ở parser. Nhãn UI hướng dẫn nhập IP Host; không mở rộng hợp đồng mạng/định dạng địa chỉ ngoài connector hiện có.

Chỉ cho `--address` khi có `--role viewer` tường minh; không tự suy role. Không role + tùy chọn port hợp lệ vẫn mở chooser và truyền hai port cho role được chọn. Mỗi cờ chỉ xuất hiện một lần, kể cả giá trị giống nhau và `--help`; lặp `--role` không có quy tắc “cờ sau thắng”. Port giữ quy tắc số nguyên của parser hiện tại, miền 1..65535 và hai port khác nhau. Không nhận port 0 ở CLI dù test socket có thể dùng ephemeral port.

| Lệnh cũ theo ý định người dùng | Lệnh v2 đề xuất | Cảnh báo migration cụ thể |
| --- | --- | --- |
| Không tham số | Không tham số | Chooser mới ghi rõ vai máy. |
| `--server` trên máy điều khiển cũ | `--role viewer` | Máy này giờ chủ động kết nối đến Host; cần nhập IP **máy bị điều khiển**. Không ánh xạ thành Host chỉ vì cũ lắng nghe. |
| `--client --host IP_SERVER_V1` trên máy bị điều khiển cũ | `--role host` | Bỏ `IP_SERVER_V1`; máy này giờ tự hiện IP/mã và lắng nghe. IP cũ là máy điều khiển, không được chuyển máy móc thành `--address`. |
| `--host IP_SERVER_V1` không role | Bị từ chối; người dùng chọn `--role host` hoặc `--role viewer` theo máy | Không có rewrite tự động đúng cho mọi trường hợp. |
| Viewer v2 biết IP máy bị điều khiển | `--role viewer --address IP_HOST_V2` | Đây là địa chỉ mới đúng hướng, không nhất thiết bằng IP trong script cũ. |
| Port tùy chỉnh ở mỗi phía | Thêm cùng `--cmd-port 6001 --img-port 6000` vào cả hai lệnh v2 | Listener ở Host; không mở inbound trên Viewer chỉ vì hướng dẫn v1. |

Tất cả `--server`, `--client`, `--host` đều bị từ chối, riêng lẻ hay trộn với cờ mới; không alias, không đoán theo số đối số. Diagnostic cho nhóm cũ cố định: `Legacy options are not supported in v2. Choose --role host on the controlled machine or --role viewer [--address ADDRESS] on the controlling machine.` Không in lại giá trị người dùng; ghi rõ migration trong help/README.

| Nhóm ví dụ | Kết quả xác định |
| --- | --- |
| Rỗng; `--cmd-port 6001 --img-port 6000` | Chooser, không tự kết nối. |
| `--role host`; `--role viewer`; `--address 192.168.1.20 --role viewer` | GUI đúng vai; Host start listener; Viewer chỉ preset address. |
| `--role host --role host`; `--role host --role viewer`; hai `--address`; hai `--help` | Lỗi trùng cờ, exit 2. |
| `--role host --address 192.168.1.20`; chỉ `--address 192.168.1.20` | Sai role, exit 2, không GUI/network. |
| `--role`; `--role server`; `--role HOST`; `--role=host`; `--role viewer --address`; `--address " " --role viewer` | Thiếu/sai giá trị hoặc cú pháp không hỗ trợ, exit 2. |
| `--cmd-port 0`; `--img-port 65536`; `--cmd-port abc`; `--cmd-port 5000`; `--cmd-port 6001 --img-port 6001` | Sai port hoặc trùng port, exit 2. |
| `--server`; `--client --host 192.168.1.10`; `--host`; `--role viewer --host 192.168.1.10` | Diagnostic migration cố định, exit 2. |
| `--pin 123456`; `--code 123456`; positional argument; cờ lạ | Lỗi cú pháp, exit 2; không in toàn argv hoặc giá trị mã. |
| `--help`; `--help --role viewer`; `--help --role viewer --address 192.168.1.20` | Validate toàn bộ rồi help ở stdout, exit 0; không GUI, hoạt động cả headless. |
| `--help --role host --address X`; `--help --server`; `--help --bogus` | Vẫn là lỗi, help không che lỗi; stderr diagnostic + usage, exit 2. |

Nếu nhiều lỗi cùng lúc, ưu tiên nhận diện cờ legacy ở bất cứ vị trí nào để in hướng migration; nếu không có legacy, báo lỗi cú pháp đầu tiên theo thứ tự token, rồi kiểm điều kiện role/port sau parse. Diagnostics mới chỉ nêu tên cờ hợp lệ hoặc nhóm lỗi, không echo token/value tùy ý hay exception message chứa argv. Giữ exit launcher: 0 help/thành công kết thúc bình thường; 2 đối số sai; 4 thiếu môi trường đồ họa; 1 không mở được giao diện. `STAY_RUNNING = -1` là giá trị nội bộ, không phải process exit code. Lỗi mạng/auth sau khi GUI mở hiện trên UI, không giả làm lỗi parse. Không đổi mã thoát để làm thước đo cleanup; shutdown report mới là bằng chứng nhả input.

### 3.2 Tên cửa sổ, nút và lớp

| Bề mặt | Nội dung đề xuất | Trách nhiệm |
| --- | --- | --- |
| Chooser | Tiêu đề `Điều khiển máy từ xa`; nút `Host — Máy bị điều khiển`, `Viewer — Máy điều khiển` | Chọn một vai cho đời runtime; không đổi vai tại chỗ. |
| Form Host | `Host — Máy bị điều khiển`; `Địa chỉ IP của Host`, `Cổng lệnh`, `Cổng ảnh`, `Mã kết nối (6 chữ số)`, `Viewer kết nối` | Hiện tất cả IPv4 từ LocalAddresses, không tự chọn adapter “đúng”; người dùng lấy IP LAN phù hợp. Nút `Đổi mã`, `Dừng`, `Thử lại`. |
| Form Viewer | `Viewer — Máy điều khiển`; `IP máy Host`, `Mã kết nối (6 chữ số)` | Nút `Kết nối`, `Hủy`, `Ngắt`; Enter trên ô mã chỉ kết nối khi nút được bật. Giữ IP cho lần thử lại; xóa mã sau khi một lần kết nối hợp lệ được runtime nhận. Không lưu mã xuống đĩa/log. |
| ControlFrame | `Viewer — Điều khiển Host`; thống kê FPS/lệnh/ảnh/RTT giữ hiện tại | Chỉ Viewer có RemotePanel/InputCapture. Đóng cửa sổ này là Ngắt phiên, giữ form Viewer để nối lại. |
| SharingBar | `Host — Đang chia sẻ màn hình`; `Đang được điều khiển bởi <IP Viewer>` và `Dừng` | Chỉ Host, hiện khi ACTIVE; giữ non-focusable, không giành focus khi gõ ngay sau kết nối. Dừng đóng phiên, giữ Host lắng nghe sau cleanup. |

Host tự start listener khi mở role như cách Server v1 hiện tại; người dùng đưa mã mới là cho phép Viewer, không thêm prompt nhận phiên. `Đổi mã` chỉ khi LISTENING, theo AuthGuard/SessionManager hiện tại; sai mã không đổi mã. Trong RESERVED/ACTIVE/CLOSING ẩn mã (hiện `—`) để không đưa mã đang bận như mã sẵn sàng; mã mới chỉ hiện khi slot được trả bình thường. Nút Dừng không tắt listener; đóng form chính mới shutdown ứng dụng. Không thêm nút bật/tắt listener vào phạm vi này.

| Tên ở S | B1: nghĩa/hành vi sau đổi vai, tên tạm giữ | B2: tên cuối cơ học |
| --- | --- | --- |
| `ui.ServerFrame`, `ui.ServerFormState` | Form Host lắng nghe/hiện mã; bỏ sở hữu ControlFrame, nhận SharingBar | `ui.HostFrame`, `ui.HostFormState` |
| `ui.ClientFrame`, `ui.ClientFormState` | Form Viewer nhập địa chỉ/mã; bỏ SharingBar, nhận ControlFrame | `ui.ViewerFrame`, `ui.ViewerFormState` |
| `ui.ControlFrame`, `SharingBar`, `RemotePanel`, `InputCapture`, `Controller`, `AppRuntime`, `Messages`, `RoleChooserFrame` | Giữ tên; chuyển handler/ownership/text đúng vai trong B1 | Giữ tên; import và nhắc tên role được đồng bộ |
| `LaunchOptions.Role.SERVER/CLIENT`; `host` preset | B1 dùng `HOST/VIEWER` và tên preset `address` theo cú pháp mới | Giữ nguyên hợp đồng B1 |
| `AppRuntime.forServer`, `forClient`; `Controller.startServer` | B1 thay bằng `forHost(cfg, adapters)`, `forViewer(cfg)`; `startHost()` | Không đổi nghĩa lần nữa ở B2 |
| `server.ServerCore/Config/Phase/Session`, `client.ClientSession/Config/Phase/Hooks` | Tên package/vỏ tạm giữ, gắn ControlledSide ở listener và ControlSide ở connector | `host.HostCore/Config/Phase/Session`, `viewer.ViewerSession/Config/Phase/Hooks`, đúng HV 2.2 |
| `viewer.ControlView.phase`: `server.ServerPhase` tại S | B1 đổi thành `client.ClientPhase`; chuyển producer sang `client.ClientSession` của Viewer và đồng bộ consumer runtime/snapshot/UI cùng test | Chỉ rename kiểu/package `client.ClientPhase` → `viewer.ViewerPhase`; `viewer.ControlView` giữ tên, không đổi nghĩa phase |

Test form đi cùng tên lớp ở B2. Test runtime hiện theo hướng TCP (`AppRuntimeServerTest`/`ClientTest`) đề xuất đổi cơ học thành `AppRuntimeHostTest`/`ViewerTest` ở B2 sau khi đã chuyển các kỳ vọng chạy Robot sang Host ở B1; test `inputExecutorKeptAcrossReconnects` phải được ánh xạ theo năng lực bị điều khiển, không chuyển cả file chỉ dựa tên cũ. Bảng truy vết ghi tên từng test bị di chuyển, mọi assertion giữ trừ thay đổi v2 đã duyệt. Các lớp host/viewer đã chuyển ở A4 không chuyển lại. Chưa có các API/tên mới này trong source S.

### 3.3 Trạng thái và thao tác Host

Các trạng thái phụ trong bảng chỉ là projection từ session/future cho UI, không là state machine phiên thứ hai. Runtime lấy phiên hiện tại theo identity/generation, snapshot chỉ chứa dữ liệu cần hiển thị. UI không poll future, lấy executor hoặc đoán trạng thái dựa hai giây chờ.

| Phase / điều kiện thật | Text và cửa sổ | Thao tác được phép |
| --- | --- | --- |
| IDLE / STARTING | `Chưa khởi động Host.` / `Đang khởi động Host…`; không mã, không SharingBar | IDLE: Thử lại; STARTING: chỉ đóng ứng dụng. |
| LISTENING, slot trống | `Đang chờ Viewer kết nối.` + IP/port/mã mới; giữ lý do phiên trước nếu có | Đổi mã, đóng ứng dụng; Dừng không bật. |
| RESERVED, prepare hoặc ATTACH đang chờ | `Viewer đang kết nối; đang chuẩn bị chia sẻ…`; không SharingBar, không mã | Dừng phiên, đóng ứng dụng; không Đổi mã/Thử lại. Không suy “ATTACH xong = ACTIVE”. |
| ACTIVE | `Đang được điều khiển bởi <IP Viewer>`; SharingBar hiện; không mã | Dừng từ form/bar, đóng ứng dụng. Host không có capture hay ControlFrame. |
| CLOSING, còn worker (kể cả prepare) | `Đang dọn phiên trước…`; SharingBar ẩn, không mã | Đóng ứng dụng; không Đổi mã, không Thử lại, không mở slot. AUTH mới vẫn BUSY. |
| CLOSING, input release pending | `Đang nhả phím/chuột của phiên trước…` (ưu tiên nếu đồng thời còn worker) | Như trên; mốc cảnh báo hiện có không cho phép cấp slot. |
| inputReleased lỗi / input FAULTED | `Messages.INPUT_RELEASE_FAILED` khi nhả lỗi; `Messages.INPUT_FAULTED` khi lỗi trong phiên | Không thử start/reconnect/Đổi mã; yêu cầu khởi động lại ứng dụng. Listener nếu còn chạy vẫn trả BUSY theo HV 1.8; không thêm AuthStatus. |
| Start/bind thất bại, chưa có phiên | `Messages.forThrowable` chuẩn hóa; không mã | Thử lại hoặc đóng; cleanup lần start lỗi phải xong trước lần mới. |
| Prepare adapter lỗi trước begin | Local Host hiện lỗi adapter chuẩn hóa nếu là nguyên nhân đóng đầu tiên; sau cleanup về LISTENING giữ lý do | Chỉ sau đủ ba future bình thường mới hiện mã mới/cho Đổi mã. Không coi lỗi adapter tạm thời là lỗi nhả input. |
| SHUTTING_DOWN | `Ứng dụng đang đóng.`; tắt mọi điều khiển | Không tác vụ mới; chờ kết quả nền trong shutdown budget hiện có. |

Runtime cần cung cấp đủ phân biệt “còn worker / chờ input / release failed” trong snapshot của Host (một giá trị trạng thái dọn dẹp dẫn xuất, không lộ future cho UI). `FAILED` của UI phải phân biệt start có thể retry với input fault không thể retry; không dùng chung điều kiện `phase == FAILED` để bật Thử lại. Không thêm timer tự trả slot hoặc tự restart executor.

### 3.4 Trạng thái và thao tác Viewer

| Phase / sự kiện | Hiển thị và thao tác | Kết quả session bắt buộc |
| --- | --- | --- |
| IDLE | Nhập/sửa IP, mã; Kết nối được bật; Hủy/Ngắt ẩn | Không tự kết nối từ CLI, không khởi tạo Robot/InputExecutor. |
| CONNECTING | `Đang kết nối Host…`; khóa ô nhập; chỉ Hủy/đóng app | Socket register trước connect; Hủy đóng socket đã đăng ký. |
| AUTHENTICATING | `Đang xác thực…`; Hủy/đóng app | Deadline auth 5 s từ connect lệnh xong; không gia hạn vì UI. |
| ATTACHING | `Đang thiết lập phiên với Host…`; Hủy/đóng app | Chờ SCREEN_INFO/SESSION_ACTIVE và handover ảnh. Không có ControlFrame/capture hoạt động trước ACTIVE. |
| ACTIVE, chưa vẽ frame đầu | Mở ControlFrame, `Đang chờ ảnh Host…`; form cho Ngắt, không cho sửa IP/mã | Input gate đóng đến khi frame đúng generation/epoch được paint; ACTIVE không đồng nghĩa được gửi input. |
| ACTIVE, frame đã vẽ, panel có focus | `Đang điều khiển Host <IP>`; chuột/phím theo gate | Mỗi lệnh qua Controller đến ControlSide, kiểm generation/epoch/gate trong cùng tx. |
| ACTIVE, stall 3 s | `Ảnh bị gián đoạn — tạm khóa điều khiển.`; Ngắt vẫn được | Gate đóng và RELEASE_ALL một lần; hồi phục chỉ mở sau paint frame epoch mới, không replay input cũ. |
| Mất focus/deactivate/minimize | Giữ phiên, không gửi input; lần click lấy lại focus chỉ chọn cửa sổ | Capture nhả held state, wheel accumulator; không hồi sinh phím đã giữ. Panel không paint thì gate mới chưa mở. |
| Hủy trước ACTIVE / Ngắt ACTIVE / đóng ControlFrame | `Đang đóng phiên…`; khóa Kết nối/ô nhập; đóng ControlFrame và dọn capture/timer | Về IDLE sau cleanup cục bộ; Viewer không đợi future Robot Host để giải phóng runtime cục bộ. Kết nối mới là phiên mới, không nối lại riêng kênh ảnh. |
| CLOSING | Chỉ đóng app; không nhấn Kết nối liên tiếp để vượt cleanup | Khi workers/sockets của Viewer đã dọn mới nhận attempt mới; không dùng text chờ nhả input cục bộ trên Viewer. Host có thể vẫn BUSY. |
| SHUTTING_DOWN | Tắt mọi tác vụ; dispose cửa sổ trên EDT | Shutdown nền, không chặn EDT chờ mạng/future. |

Nếu click Hủy đua với ACTIVE, runtime quyết định bằng phase trong tx như hợp đồng hiện tại: Hủy chỉ đóng phase trước ACTIVE; khi ACTIVE đã thắng, snapshot tiếp theo hiện Ngắt. Đóng form chính luôn shutdown cả role ở mọi phase. Dừng Host và Ngắt Viewer đồng thời đều qua close idempotent, giữ close reason đầu tiên.

### 3.5 Lỗi, ATTACH, deadline và thông báo

| Tình huống | Quy tắc và text đề xuất |
| --- | --- |
| WRONG_CODE | Viewer về IDLE sau cleanup, `Mã không đúng.`; cho nhập lại. Host trì hoãn 1 s, sai 3 lần khóa IP 30 s như hiện có; không đổi mã vì nhập sai. |
| LOCKED | `Bị khóa tạm thời do nhập sai nhiều lần. Thử lại sau 30 giây.`; không auto-retry, không timer local tự khẳng định đã hết khóa. |
| BUSY | `Host đang có phiên khác hoặc đang dọn phiên trước. Hãy thử lại sau.`; không biết từ wire đó là release failed, nên không tuyên bố lỗi cụ thể trên Viewer. |
| BAD_VERSION | `Phiên bản khác nhau. Hãy dùng cùng một bản jar trên hai máy.`; cả Viewer v2 → Server v1 và Client v1 → Host v2 phải bị từ chối; không hạ cấp. |
| Kết nối lỗi/hết hạn | `Không kết nối được tới Host. Kiểm tra IP, cổng và tường lửa.` hoặc text TIMEOUT hiện có theo loại kết quả; không hiển thị exception message. |
| Prepare chậm, ATTACH đến trước | Host vẫn RESERVED; Viewer vẫn ATTACHING; heartbeat có thể chạy trước SCREEN_INFO. Không kéo dài reserve 5 s, không cấp control sớm. |
| Prepare trước ATTACH / hai trigger đồng thời | Host chỉ ACTIVE khi prepare + offer SCREEN_INFO ACCEPTED + ATTACH hợp lệ; beginSession thành công trước SESSION_ACTIVE. Hai thứ tự cùng một kết quả, tối đa một lần. |
| Viewer nhận SESSION_ACTIVE trước handover ảnh cục bộ | Giữ ATTACHING đến khi imageChannelReady; deadline 7 s vẫn tính từ AUTH OK. Phải có SCREEN_INFO trước SESSION_ACTIVE; thiếu là lỗi giao thức. |
| Cancel/timeout trong prepare | Socket đóng; kết quả prepare muộn bị bỏ, không cập nhật geometry/bounds/UI hay mở slot. Slot chỉ trả sau ba future; prepare bỏ qua interrupt vẫn BUSY. |
| ATTACH thiếu/sai/lặp | Thiếu hợp lệ: Host reserve hết 5 s; Viewer activation hết 7 s. Sai token hoặc lần hai chỉ đóng socket ảnh đó, không hủy phiên hợp lệ chỉ vì socket lạ. |
| Mất một kênh ACTIVE | Đóng cả phiên khi phát hiện, không ghép lại kênh. Lệnh EOF đóng ngay theo đường I/O; ảnh EOF/write error/watchdog đóng, hoặc stall 3 s rồi frame timeout 15 s. Không hứa mọi mất ảnh đều đóng đúng 3 s. |
| SCREEN_CHANGED | `Cấu hình màn hình đã thay đổi. Hãy kết nối lại.`; Host đọc geometry mới theo phiên kế tiếp. Nếu JDK không cập nhật scaling, hướng dẫn khởi động lại Host. |
| Shutdown/native treo hoặc nhả lỗi | Không báo đã nhả sạch khi chưa xác nhận. Giữ ShutdownReport với socketsClosed, workersStopped và trạng thái input release; hiển thị cảnh báo chuẩn hóa trên EDT trước kết thúc GUI nếu kết quả không sạch. Không sửa wire reason để truyền báo cáo nội bộ. |

Giữ nguyên connect 3 s mỗi kênh; Host handshake tổng 3 s từ accept; reserve 5 s từ reservedAt; Viewer activation 7 s từ AUTH OK, bao cả connect/flush ảnh; ping 2 s, liveness 8 s; cmd write 5 s, img write 10 s; stall 3 s, frame timeout 15 s (HV 1.4). UI chỉ hiển thị trạng thái, không tính lại deadline. Không log mã/token/ảnh/phím, không dump LaunchOptions chứa mã do argv sai. Peer IP chỉ ở vị trí UI chủ ý và báo cáo môi trường được người chạy điền, không đưa vào log exception thô.

`pendingText` của form Viewer: lỗi do connect không được runtime nhận ưu tiên hơn message IDLE cũ, tồn tại qua các snapshot IDLE lặp lại của cùng attempt. Chỉ xóa khi người dùng sửa ô nhập, gửi attempt được STARTED, hoặc có chuyển trạng thái/generation thực sự của attempt mới. Tách chính sách ưu tiên text thành logic trình bày thuần có test; không giữ phiên hay auth state trong frame. Test phải bắt chuỗi: phiên cũ có lý do đóng → IDLE → nhập mã sai định dạng → nhiều snapshot IDLE có lý do cũ; lỗi nhập vẫn hiện cho đến hành động mới. Không chỉ assert text ngay sau click.

### 3.6 Module, Interface, Seam và ownership

**Hợp đồng phase bắt buộc ở B1 (HV 2.2, dòng 221):** `viewer.ControlView.phase` dùng trực tiếp `client.ClientPhase` của lifecycle connector Viewer, do `client.ClientSession.controlView()` sản xuất trong cùng transaction với control state. B1 bỏ producer ControlView ở listener `server.ServerSession`; `AppRuntime` và đường chiếu snapshot/UI chuyển sang consumer của producer Viewer này, dùng `ClientPhase.CONNECTING/AUTHENTICATING/ATTACHING/ACTIVE/CLOSING` theo bảng 3.4. IDLE là trạng thái UI khi không có phiên, không thêm vào enum phiên. Đồng bộ constructor/import, phase comparisons, projection và test producer/consumer/runtime/snapshot/form trong B1; kiểm view/gate cùng generation, không báo ACTIVE trước đủ điều kiện activation/ảnh và không bật input trước paint. Không giữ `ServerPhase` cho ControlView qua B1, không thêm mapping phase/vỏ tương thích hoặc abstraction tạm để giữ hợp đồng cũ. B2 chỉ đổi `client.ClientPhase` thành `viewer.ViewerPhase` và các tham chiếu package/tên tương ứng, giữ nguyên phase values, producer/consumer và kỳ vọng B1.

`AppRuntime` là Module sâu: Interface cho UI vẫn là Controller + Snapshot + outcome/shutdown report. Seam này có runtime thật và FakeController kiểm trình bày; GUI/CLI dùng cùng parse/config, không có session engine thứ hai. Caller không cần học socket, token, adapter worker hay ba future. Các phương thức Controller ngắn, quyết định nhanh; runtime/session đưa việc chặn ra worker. Không tạo Interface tổng quát mới chỉ để wrapper một lời gọi.

| Tài nguyên / hành vi | Owner v2 và đường đi | Không để trôi sang UI |
| --- | --- | --- |
| Runtime theo role | GuiApp tạo ngoài EDT; một runtime cho một role. Form chính phát lệnh shutdown khi đóng; runtime chịu rollback init một phần | Không tạo runtime trong listener repaint; không để init thất bại bỏ sót resource đã tạo. |
| Một InputExecutor + input device | `AppRuntime.forHost` tạo, truyền cho HostCore; chỉ runtime shutdown executor lúc thoát. Phiên mượn begin/end qua ControlledSide | Không executor ở Viewer, không mỗi session một executor, không shutdown executor khi chỉ Dừng phiên. |
| AdapterFactory | Host runtime sở hữu, input device dùng lại; refresh và newScreenSource theo từng phiên trên `rd-host-prepare` do lifecycle quản lý | Không Robot trực tiếp trong HostFrame/SharingBar; không prepare trên EDT/handshake/khóa runtime. |
| ScreenSource/geometry | ControlledSide của phiên Host giữ kết quả prepare hợp lệ. Bounds snapshot bất biến công bố lúc activate; sender dùng đúng geometry đó | Không giữ currentScreen dùng chung để worker cũ ghi đè; supplier không lấy ngược khóa session từ executor. |
| Socket pending/accepted/session | Core quản pending và listener; CAS bàn giao; lifecycle đóng socket đã nhận. Viewer register socket trước mọi thao tác chặn và dùng lại đúng stream AUTH_RESULT | UI không đóng raw socket, không tạo reader/writer riêng, không parse packet. |
| Slot, mã, cleanup | SessionManager giữ current đến khi socketsClosed + terminated + inputReleased đều thành công; identity/generation bảo vệ callback cũ | Snapshot không tự trả slot, không bật retry dựa vào timer cảnh báo; chỉ manager sinh mã mới lúc trả slot. |
| Ticker, registrations, scheduler/cleanup | Core/runtime sở hữu phần của mình; mỗi registration có owner đóng; session đăng ký qua lifecycle port và gỡ lúc close | Không đổi semantics Ticker.close, không coi số registration là bằng chứng thread đã dừng. Task 1 cần kiểm riêng. |
| Viewer control state | ControlSide giữ gate/epoch/frame; runtime chiếu snapshot nhất quán. InputCapture thuộc ControlFrame, gửi qua Controller, RemotePanel mở gate sau paint | Frame không tự bật control vì đã thấy ACTIVE hoặc apply snapshot; không nhân đôi SessionLifecycle trong UI. |
| Cửa sổ phụ, feed, listener, timer | HostFrame sở hữu SharingBar; ViewerFrame sở hữu ControlFrame; cửa sổ chính sở hữu feed. ControlFrame dispose phải nhả capture cục bộ, dừng stats timer và tháo listener; form chính đóng feed một lần | Mọi create/apply/show/hide/dispose, capture event, timer Swing trên EDT. SnapshotPump đọc runtime ngoài EDT. |
| Shutdown | Runtime đặt cờ không nhận việc mới, đóng session/core theo ownership, chờ cleanup rồi shutdown executor input và tài nguyên dùng chung trong budget hiện có; cùng một shutdown future cho gọi lặp | GUI không `.get()`/join/sleep trên EDT, không đổi thành “thoát là đã nhả”. Kết quả không sạch phải báo đúng; native bỏ qua interrupt vẫn là giới hạn. |

InputExecutor gọi Robot input trên worker riêng; sender gọi Robot capture riêng, không chung khóa capture/input. Chuẩn bị phải refreshInputDevice rồi newScreenSource ngoài mọi khóa; kiểm closed/deadline mới trong tx trước offer; chỉ ACCEPTED mới ready. Host gọi beginSession trong tx, ghi inputBegun trước bước có thể lỗi; mọi đường close sau đó endSession đúng một lần. UI chỉ nhận phân loại lỗi chuẩn hóa cần thiết, không nhận exception/future/session reference. Đây là ownership bắt buộc từ HV 1.8/2.3, không là seam mới cho B1.

## 4. Phần 4 — Tài liệu và nghiệm thu H2 đề xuất

### 4.1 Tài liệu cần đồng bộ khi B1/B2 được phép

| Tài liệu / artifact | Thay đổi ở B1 | Đồng bộ cơ học ở B2 |
| --- | --- | --- |
| `README.md` | Vai Host/Viewer, sơ đồ hai kênh, đưa mã là đồng ý, CLI migration/help, Firewall inbound ở Host, build/run, giới hạn và trạng thái thực tế | Tên package/lớp cuối, liên kết báo cáo theo SHA. Không xóa sự thật “H2 chưa chạy” trước khi có kết quả. |
| HV và bản tích hợp đề xuất `docs/superpowers/specs/2026-11-02-host-viewer-ui-cli-h2-design.md` | Coordinator cập nhật bảng trạng thái Phần 3–4 bằng quyết định duyệt thật, ghi wire v2 chỉ khi commit B1 có bằng chứng | Ghi B2 và tên lớp cuối; giữ lịch sử và các giới hạn. Không dùng trạng thái lịch sử G0 làm trạng thái hiện tại. |
| `docs/superpowers/plans/2026-11-01-host-viewer-orchestration-roadmap.md`, `2026-11-02-host-viewer-v2-preparation.md` và báo cáo execution | Gắn các gate đã đạt với SHA, provider thực tế theo receipt; đánh dấu B1/B2 còn chờ khi chưa duyệt | Ghi hoàn tất đúng phạm vi được kiểm; không coi checklist là kết quả test. |
| `docs/superpowers/specs/2026-10-19-remote-desktop-design.md` | Chỉ rõ các đoạn vai/luồng v1 được HV v2 thay; link quy tắc đang có hiệu lực | Không viết lại lịch sử như thể v1 vốn là Host/Viewer. |
| `docs/superpowers/plans/2026-10-27-remote-upgrade-plan.md` | Sửa thông tin “chưa có spec”; giữ H2 v2 trước P0, FRAME_ACK/freshness vẫn v3 | Sửa tên lớp khi cần, không triển khai nâng cấp hiệu năng. |
| `docs/superpowers/reviews/2026-10-20-wave3-4-checkpoint.md` | Thêm hướng dẫn H2 v2 đang có hiệu lực và link, cờ Server/Client cũ chỉ còn là lịch sử | Không tiếp tục chỉ người dùng chạy jar v2 bằng cờ v1. |
| `docs/superpowers/reviews/2026-10-20-wave3-{spike-robot,mouse,keyboard,gui}-trial-results.md` | Mẫu/lượt v2 mới ghi đúng Host/Viewer, SHA và tình trạng H1/H2; giữ kết quả v1 có nhãn riêng nếu có | Liên kết tên cuối và final jar digest. |
| `docs/superpowers/reviews/2026-10-20-wave4-acceptance-results.md`, `2026-10-20-wave4-release-jar-results.md` | Tạo phần/lượt v2 riêng cho dài hạn, jar/help, exit, shutdown và thiếu bằng chứng | Không kế thừa PASS từ jar hay SHA cũ. |
| Test map `docs/superpowers/reviews/<ngày>-v2-test-map.md`, report coverage/500 race/PIT | Trace từng test cũ sang kỳ vọng v2; lưu artifact theo SHA được chạy | Tên đích đúng HV 2.2/3.7, class gate không mất tên; final-SHA evidence do coordinator sở hữu. |

Không sửa những tài liệu trên ở Task 2. Coordinator tích hợp sau builder review, giải quyết finding material rồi trình người dùng lựa chọn; chưa bắt đầu viết plan B1.

### 4.2 Lệnh và trình tự chạy H2 v2 dự kiến

**Các lệnh dưới đây dành cho bản v2 tương lai sau duyệt và B1/B2, chưa chạy trong task này.** Chỉ một người sở hữu build/target; không build đồng thời worker khác. Trên máy build Windows ở root repo:

```powershell
git rev-parse HEAD
java -version
.\mvnw.cmd -q clean verify
Get-FileHash -Algorithm SHA256 .\target\remote-desktop.jar
java -jar .\target\remote-desktop.jar --help
```

Lưu stdout/stderr, exit code, SHA commit, JDK thực tế, giờ chạy và digest jar. Verify xanh không chứng minh H2. Copy đúng jar sang thư mục H2 của cả hai máy, kiểm SHA-256 bằng nhau; cả hai cần JDK >= 21 và desktop tương tác. Nếu H1 Robot thật chưa có bằng chứng đạt thì ghi thiếu và hoàn tất H1 trước khi kết luận H2. Xác nhận firewall inbound hai port tại **máy Host** bằng quyền của người vận hành; không yêu cầu Viewer mở listener. Tài liệu giới hạn vẫn chỉ LAN tin cậy, Windows/màn hình chính, không UAC/admin/lock screen.

Lượt A: máy A bị điều khiển, máy B điều khiển. Tại A:

```powershell
java -jar .\remote-desktop.jar --role host --cmd-port 5001 --img-port 5000
```

Đọc IP LAN của A trên HostFrame, đưa mã ngoài command line cho người ở B. Tại B (thay IP mẫu bằng IPv4 LAN thật của **A**):

```powershell
java -jar .\remote-desktop.jar --role viewer --address 192.168.1.20 --cmd-port 5001 --img-port 5000
```

Nhập mã 6 số trong Viewer ở B, bấm Kết nối; kiểm màn hình A hiện trên B và input ở B chỉ tác động A. Lượt B: shutdown sạch cả hai runtime, **máy B chạy `--role host`**, máy A chạy `--role viewer --address IP_LAN_CUA_B`; nhập mã mới do B đưa. IP mẫu không phải thông tin môi trường đã đo. Thử không tham số để kiểm chooser đúng vai. Nếu dùng 6001/6000 thì đổi cả hai lệnh và inbound Host tương ứng; không tráo port lệnh/ảnh.

Đề xuất tổ chức: kiểm mọi ca chức năng dưới đây ở cả hai hướng; ca 30 phút và 50 vòng chạy trọn ở mỗi hướng để phủ Robot trên từng máy. Đây là phân bố nghiệm thu đề xuất cần người dùng xác nhận về thời gian; **không giảm gate hiện hữu 30 phút / 50 lần** và không tự coi lượt đảo vai đã được miễn. Không ấn định ngày hoàn tất khi chưa biết hai máy/người chạy.

### 4.3 Ma trận H2 và bằng chứng cần lưu

Tất cả dòng dưới đây hiện là **CHƯA CHẠY** cho v2; không có số đo thật trong bản nháp. Mỗi ID phải có hai bản ghi `A-Host/B-Viewer` và `B-Host/A-Viewer`, trừ ca chỉ áp dụng máy build hoặc bài raw-peer đã được gán tự động. Video/screenshot che mã; nhật ký không thu mã/token/phím hay ảnh màn hình nhạy cảm.

| ID / kịch bản | Quan sát PASS bắt buộc | Bằng chứng / cách thực hiện |
| --- | --- | --- |
| H2-01 jar/role/chooser/help | Hai máy cùng digest/SHA v2; đúng máy hiện IP/mã, đúng máy nhập; không auto-connect; SharingBar không giành focus | Digest hai máy, help và exit, ảnh chooser/form đã che mã; ghi màn hình/OS/JDK. |
| H2-02 kết nối và nhập ngay | Có ảnh đúng Host; chỉ nhận input sau frame đã vẽ; gõ ngay sau ACTIVE không vào SharingBar hay sai máy | Video quan sát cả hai máy, thời điểm kết nối và ứng dụng nhận input; không dùng headless thay. |
| H2-03 chuột và DPI | Click/kéo/cuộn/nút 1–3 đúng vị trí trong ảnh letterbox khi resize; DPI Host 100/125/150% nếu hỗ trợ, đảo vai | Ghi resolution, scaling cả hai máy, kích thước panel, điểm thử góc/tâm; cấu hình không thể thử là chưa chạy, không PASS chung. |
| H2-04 focus/minimize | Alt+Tab/mất focus khi giữ Ctrl/nút chuột nhả đúng; click đầu lấy focus không phát click; restore không dùng gate/frame cũ | Video trước/sau, thử từ Viewer; kiểm phím không kẹt trên Host. |
| H2-05 key repeat | Giữ Backspace và phím chữ 2 s lặp đúng, không lặp đôi; Tab/modifier đúng | Ghi policy, số ký tự quan sát và cách đo; nếu lặp đôi thì FAIL, chưa tự đổi policy rồi gộp kết quả cũ. |
| H2-06 đổi DPI/resolution | Nếu hệ thống phát hiện đổi cấu hình: SCREEN_CHANGED và cleanup, nối lại geometry đúng; JDK không cập nhật DPI thì ghi giới hạn và thử lại sau restart Host | Ghi trường hợp thực tế; không báo hỗ trợ thay nóng khi chỉ restart mới đúng. |
| H2-07 sai mã/LOCKED | Sai mã chờ 1 s, ba lần khóa IP 30 s; mã Host không đổi vì sai; sau khóa dùng mã đúng được | Đồng hồ/video, không ghi giá trị mã. UI không phải chứng cứ cho khoảng thời gian chính xác; timer core được kiểm tự động. |
| H2-08 BUSY/concurrent | Khi Host có phiên, Viewer thứ hai bị BUSY; Dừng/Ngắt đồng thời không lỗi; chỉ có một phiên | Cần thêm một tiến trình Viewer trên máy điều khiển, không cần máy thứ ba; ghi PID và timeline. |
| H2-09 Hủy/reconnect | Hủy ở CONNECTING/AUTHENTICATING/ATTACHING phản hồi UI ngay; sau cleanup có thể thử phiên mới, không callback cũ ghi đè | Video phase và attempt; phase khó bắt dùng harness/seam ở tự động, không giả đã ép được trên GUI thật. |
| H2-10 Host Dừng/đóng app | Dừng trả Host về LISTENING chỉ sau cleanup, mã mới; đóng Host app thì Viewer đóng phiên, input không kẹt | Video và PID/jps/Task Manager; DISCONNECT best effort, khi mất kênh ghi EOF/timeout thực tế. |
| H2-11 mất mạng theo giai đoạn | Thực hiện riêng H2-11a/b/c trên mạng thật ở cả hai hướng; giữ các deadline theo phase, không áp deadline ACTIVE cho trước kết nối | Ledger từng subcase/hướng, phase thực tế của hai bên, mốc cắt/phát hiện/đóng/cleanup và reconnect; Hủy (H2-09) hoặc shutdown (H2-19) không thay lượt cắt mạng. |
| H2-11a đang nhập mã / trước kết nối | Rút cáp/tắt Wi-Fi/switch ảo khi Viewer còn IDLE đang nhập mã, Host LISTENING; chưa có phiên nên chưa có timeout phiên. Sau đó bấm Kết nối khi mạng vẫn bị cắt: lỗi I/O sớm hoặc connect 3 s từ mỗi connect(); nếu đã tới AUTHENTICATING thì clientAuth 5 s từ connect lệnh xong, Host pending handshake 3 s từ accept nếu đã accept | Ghi phase trước cắt và phase thực sự đạt, mốc connect/accept nếu có; không coi IDLE đứng yên là timeout thành công. Kiểm cleanup cục bộ, Host không giữ pending/slot ngoài hợp đồng; khôi phục mạng, nhập mã hợp lệ hiện tại và nối lại thành công. |
| H2-11b ngay sau xác thực, trước ACTIVE | Cắt mạng thật khi đã AUTH OK nhưng còn Host RESERVED / Viewer ATTACHING. I/O error/EOF có thể đóng sớm; nếu vẫn ở phase chờ thì Host reserve 5 s từ reservedAt, Viewer activation 7 s từ nhận AUTH OK (bao connect/flush ảnh); connect ảnh 3 s nếu đang connect. Heartbeat sau handover vẫn giữ ping 2 s/liveness 8 s; không kéo dài deadline phase | Bằng chứng bắt đúng phase/mốc AUTH OK, reservedAt, handover và cắt mạng; ghi đường đóng/deadline thực sự áp dụng mỗi phía. Sau cleanup ba future ở Host và cleanup cục bộ Viewer, khôi phục mạng và nối lại; không có late callback hồi sinh phiên/control. Nếu ACTIVE thắng trước cắt thì chưa chứng minh subcase này. |
| H2-11c ACTIVE giữ input | Rút cáp/tắt Wi-Fi/switch ảo lúc giữ Ctrl và lúc kéo; hai phía tự đóng trong ≤15 s theo gate HV, Host nhả input | Ghi mốc cắt và mốc đóng từng phía bằng đồng hồ/video, cách xác nhận phím/nút đã nhả; thiếu đo thời gian không PASS. Kiểm cleanup và khôi phục mạng/nối lại ở cả hai hướng. |
| H2-12 mất một kênh / stall recovery | Mất ảnh ACTIVE đóng cả phiên; stall còn heartbeat khóa input sau 3 s, RELEASE_ALL một lần; hồi phục cần paint epoch mới; click ngay hồi phục an toàn | Fault injection có kiểm soát theo kênh và trace phân loại, video UI; nếu chỉ rút toàn bộ mạng thì chưa chứng minh riêng mất kênh ảnh. |
| H2-13 adapter chậm/lỗi và nhả lỗi | Prepare muộn không bật SharingBar/control hay mở slot; nhả lỗi hiện INPUT_RELEASE_FAILED và AUTH sau BUSY | Ca hợp tác/không hợp tác và release fail dùng test giả/latch tự động; lỗi Robot/quyền thật trên máy ghi riêng. Không cố làm Robot native treo để thay latch. H2 không bịa đã tái hiện fault không có. |
| H2-14 nhiều adapter/port chiếm | Chọn được IPv4 LAN đúng giữa VPN/VM adapter; bind lỗi rõ và không giữ port bind một phần | Danh sách adapter đã lược dữ liệu riêng, port trước/sau, kết quả retry; inbound ở Host. |
| H2-15 30 phút ACTIVE | Đủ 30 phút liên tục, không mất phiên, FPS ổn định; heap sau warmup không tăng kéo dài không giải thích được | Bắt đầu/kết thúc, FPS/RTT/CPU/heap định kỳ bằng VisualVM hoặc jcmd; lưu cách lấy mẫu và đồ thị/số liệu thật. |
| H2-16 50 connect/disconnect | Đủ 50 lần liên tiếp, xen kẽ Dừng Host/Ngắt Viewer; mọi lần nối lại được; rd-thread/socket/heap không tăng dần | Ledger vòng 1..50; chụp jstack/heap/port sau mỗi 10 vòng và sau shutdown; không lấy số vòng test fake làm số vòng H2. |
| H2-17 sleep/wake/mạng chậm | Host sleep: Viewer đóng phiên trong khoảng 8–15 s, wake không kẹt input và nối lại được; mạng yếu gate/cleanup đúng | Ghi delay/loss cấu hình hoặc mạng Wi-Fi dùng thật, FPS/RTT; RTT không thay input-to-frame. |
| H2-18 v1/v2 | Viewer v2 → Server v1 và Client v1 → Host v2 đều BAD_VERSION rõ, không vào ACTIVE | Lưu SHA/digest riêng của jar v1 và v2, tiến trình/role, kết quả từng chiều; legacy CLI chỉ dùng với jar v1 đó. |
| H2-19 shutdown từng phase | Đóng form/Ctrl+C khi kết nối/chờ attach/ACTIVE/cleanup không sót tiến trình, hai port dùng lại, không input kẹt; báo cáo native treo trung thực | Ghi phase thực tế bắt được, PID/thread/socket/input release và duration; harness kiểm thêm phase khó ép. Budget shutdown giữ cấu hình hiện tại. |

Mỗi H2-11a/b/c phải có bản ghi riêng cho `A-Host/B-Viewer` và `B-Host/A-Viewer`: cách cắt mạng thật, phase hai đầu, deadline và mốc gốc tương ứng (không tính lại tất cả từ lúc cắt), thời gian thực tế/lý do đóng, socket/pending/rd-thread/slot/input sau cleanup và kết quả reconnect. Host chỉ trả slot sau socketsClosed + terminated + inputReleased thành công; worker/native chưa xong hoặc release lỗi vẫn giữ BUSY, không ép cleanup bằng timer. Lưu video/trace không chứa mã/token/phím nhạy cảm; mốc/phase không bắt được hoặc thiếu bằng chứng thì phần đó **CHƯA CHẠY**. Harness/latch có thể bổ sung kiểm phase khó bắt với nhãn tự động riêng, không biến Hủy/shutdown/harness thành PASS cắt mạng vật lý trên hai máy (kế thừa spec v1 tại S dòng 691 và HV dòng 479).

Thử raw-peer split packet/length/JPEG sai/ATTACH sai hoặc cũ/không attach/bên kia ngừng đọc thuộc hợp đồng cũ `docs/superpowers/specs/2026-10-19-remote-desktop-design.md:668`; phải được đưa vào test v2 tự động và test map, không bỏ vì khó làm tay. Ca end-to-end UI/mạng thật tương ứng vẫn ghi kết quả H2 riêng. Các mục tiêu hiệu năng 10–12 FPS và p95 input-to-frame dưới 150 ms từ ít nhất 100 mẫu được giữ là mục tiêu đo của spec cũ `:694`, không tự chuyển thành ngưỡng bắt buộc mới. Chưa có số đo; nếu đo thì lưu video/cách tính và RTT riêng.

### 4.4 Mẫu ledger kết quả và verdict

| Trường mỗi bản ghi | Giá trị hiện tại / yêu cầu điền |
| --- | --- |
| Run ID / người chạy / giờ bắt đầu-kết thúc | Chưa có |
| Commit SHA build; SHA final được nghiệm thu; dirty state | Chưa có SHA v2; S chỉ là nguồn đọc v1, không là SHA H2 |
| SHA-256 jar máy A/B; jar v1 đối chiếu nếu có | Chưa có |
| Máy A/B: Windows build, JDK vendor/version, CPU/RAM/GPU | Chưa ghi |
| LAN/IP phù hợp, Wi-Fi/Ethernet/VPN/adapter, firewall/port | Chưa ghi; IP không sao chép vào log sản phẩm |
| Host/Viewer ở từng lượt; monitor chính, resolution/scaling, panel size | Chưa ghi |
| ID ca / subcase / hướng / iteration / tiền điều kiện / thao tác / kỳ vọng | Theo H2-01..19, H2-11a/b/c riêng từng hướng và ledger 1..50 |
| H2-11: phase hai đầu / cách cắt mạng / deadline và mốc gốc / cleanup / reconnect | Chưa bắt phase, chưa đo; ghi riêng connect/accept/reservedAt/AUTH OK/handover khi áp dụng, cắt/phát hiện/đóng; thiếu subcase/hướng vẫn CHƯA CHẠY |
| Thời gian thực tế, FPS/RTT/CPU/heap, trạng thái phím/nút | Chưa đo; không dùng số mặc định thay số đo |
| Thread/port/PID sau cleanup, artifact path/video/log | Chưa có |
| Kết quả / lỗi / giải thích giới hạn / người xác nhận | **CHƯA CHẠY** |

**PASS** chỉ khi ca thực hiện đủ trên đúng jar/SHA/môi trường, mọi tiêu chí bắt buộc đạt và có artifact xem lại. **FAIL** khi đã thực hiện và vi phạm kỳ vọng hoặc tài nguyên/input không được dọn đúng. **CHƯA CHẠY** khi thiếu máy, thời gian, cấu hình DPI, injection, phép đo hoặc artifact đủ chứng minh; không coi là PASS và không tự gắn N/A để đóng gate. Ghi “đã quan sát một phần” trong ghi chú nếu cần nhưng verdict vẫn chưa chạy cho phần thiếu. H2 toàn bộ chưa đạt nếu còn FAIL/chưa chạy ở ca bắt buộc; muốn thay phạm vi hoặc miễn phải là quyết định người dùng sau review, không là quyền của worker.

### 4.5 Kiểm thử tự động, hai máy và ranh giới commit

| Nhóm | B1: đổi hành vi nguyên khối | B2: cơ học và gate cuối |
| --- | --- | --- |
| CLI/launcher | Ma trận ở 3.1, legacy migration, duplicate/mixed/help/exit/headless, preset không connect, diagnostic không lộ mã | Tên role/config cuối và help đúng package/run; giữ kỳ vọng B1 |
| UI thuần/headless | Host/Viewer form state mọi phase và cleanup subtype; retry chỉ bind failure; pendingText không mất qua snapshot IDLE cũ; ownership ControlFrame/SharingBar qua FakeController | Rename form/test/import, không đổi behavior; giữ boundary test UI không lấy session/executor |
| Runtime/session | Host một executor, source mới từng phiên; thứ tự prepare/ATTACH, PING trước SCREEN_INFO; đúng stream wrapper; hai trigger activate, deadline biên/offer fail/begin fail; hủy/reconnect, input release future cố định/ba future/FAULTED BUSY; mất kênh/shutdown. Đổi ControlView sang client.ClientPhase cùng producer client.ClientSession, consumer AppRuntime/snapshot/UI và test hợp đồng phase/generation/gate theo §3.6; bỏ phụ thuộc ServerPhase trong B1 | Rename vỏ theo HV 2.2; riêng ControlView chỉ rename client.ClientPhase → viewer.ViewerPhase cùng import/test tương ứng, giữ kỳ vọng B1; trace/package targets đồng bộ |
| Input/paint | Giữ test generation/epoch, applied≠painted, stall/recovery/focus/repeat, release khi gate đóng; không giảm assertions/timeout/lượt | Giữ test map và dữ liệu coverage mới; không chuyển expected “đã áp” thành “đã vẽ” |
| Tích hợp | Trace literal v2 và packet×phase×side; race latch/barrier theo HV 3.3–3.4; cleanup mỗi test: rd-thread, port, registration 0 | Verify/coverage đúng SHA; 500 race và PIT trên SHA v2 cuối do coordinator chạy, review mutation từng mục |
| Hai JVM vs hai máy | Hai JVM loopback theo HV 3.6: 30 frame, 100 input đúng thứ tự, tắt peer/kill ≤15 s là gate riêng | Không lấy kết quả này thay Windows Robot/DPI/focus/key repeat và 30 phút/50 lần H2 |

Tại S có test v1 để kế thừa: `src/test/java/com/ltm/rd/ui/UiBoundaryTest.java:24`, `ServerFormStateTest.java:18`, `ClientFormStateTest.java:26`, `MessagesTest.java:25`, `RemotePanelTest.java:184`, `InputCaptureKeyboardTest.java:202`, `AppRuntimeClientTest.java:379`, `:407`, `:481`, `:518`, `AppRuntimeEndToEndTest.java:115`, `:140`, `:252` (các tên rút gọn cùng thư mục ui). Chúng là source evidence về hợp đồng, không phải lần test mới hay bằng chứng v2.

B1 phải lật cả hai phía, VERSION/PacketRules/handshake/socket/ownership runtime, hợp đồng ControlView dùng client.ClientPhase cùng producer/consumer/UI/test, UI và CLI đang dùng cùng lúc trong một commit xanh; không phát hành trạng thái nửa v1/nửa v2. B2 chỉ đổi tên/package/import/tài liệu cơ học, đồng bộ tên coverage targets và test map, không đưa bugfix/hành vi mới vào tên “rename”. Nếu phát hiện việc cơ học cần đổi nghĩa thì báo coordinator để tách và review đúng phạm vi. Bản nháp chỉ chốt ranh giới, không đưa task checklist/code/implementation plan B1; plan chỉ được soạn sau duyệt Phần 3–4.

## 5. Những lựa chọn đã được người dùng duyệt ngày 2026-11-02

1. **CLI breaking migration:** dùng `--role host|viewer` + `--address`, từ chối cả `--server`/`--client`/`--host IP`; script cũ cần đổi theo vai người dùng, không ánh xạ tự động IP cũ. Không chọn phương án cờ `--host`/`--viewer`.
2. **Trải nghiệm cửa sổ:** hai form chính, Host tự lắng nghe; đưa mã là đồng ý; SharingBar ở Host, ControlFrame ở Viewer, đóng ControlFrame chỉ ngắt còn đóng form chính thoát ứng dụng. Tên/nút cụ thể theo 3.2 đã được duyệt.
3. **Thời gian/môi trường H2:** đã duyệt đủ bộ chức năng và 30 phút/50 lần ở mỗi hướng A/B; hai máy/người chạy, lịch và cấu hình DPI thực tế được xác nhận khi nghiệm thu. Chưa có số đo hay kết quả chạy; không miễn ca thiếu môi trường.

Các chi tiết routine được duyệt cùng thiết kế: không alias cũ, role phân biệt hoa/thường, help validate trước in, cấm address không role, xóa mã sau attempt STARTED, ẩn mã khi bận, giữ lỗi nhập trước snapshot IDLE cũ, không retry input fault. Builder/re-review/final review đã hoàn tất; bước tiếp là plan và thực thi theo hợp đồng này. Trạng thái “design approved” không được diễn giải thành “v2 implemented” hoặc H2 PASS.
