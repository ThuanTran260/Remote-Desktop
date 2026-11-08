package com.ltm.rd.ui;

/** What the Server window enables and says for a snapshot; pure, so it is tested without a window. */
public record HostFormState(boolean regenerateEnabled, boolean disconnectEnabled, boolean sharingBarVisible,
        boolean retryEnabled, String statusText) {
    static final String WAITING_RELEASE = "Đang nhả phím/chuột của phiên trước…";

    public static HostFormState of(Snapshot s, boolean closingWarning) {
        UiPhase p = s.phase();
        String status = switch (p) {
            case IDLE -> withReason(s, "Chưa khởi động Host.");
            case STARTING -> "Đang khởi động Host…";
            case LISTENING -> withReason(s, "Đang chờ Viewer kết nối.");
            case RESERVED -> "Viewer đang kết nối; đang chuẩn bị chia sẻ…";
            case ACTIVE -> "Đang được điều khiển bởi " + s.peer();
            case CLOSING -> "Đang dọn phiên trước…";
            case FAILED -> s.message().isEmpty() ? "Host gặp lỗi." : s.message();
            case SHUTTING_DOWN -> "Ứng dụng đang đóng.";
            case CONNECTING, AUTHENTICATING, ATTACHING -> "Đang kết nối…"; // Client phases, never on the Server
        };
        if (p != UiPhase.SHUTTING_DOWN) {
            status = switch (s.cleanup()) {
                case NONE -> status;
                case WORKERS -> "Đang dọn phiên trước…";
                case INPUT_PENDING -> WAITING_RELEASE;
                case INPUT_FAILED -> Messages.INPUT_RELEASE_FAILED;
                case INPUT_FAULTED -> Messages.INPUT_FAULTED;
            };
        }
        boolean ready = s.cleanup() == Snapshot.Cleanup.NONE;
        return new HostFormState(ready && p == UiPhase.LISTENING,
                ready && (p == UiPhase.RESERVED || p == UiPhase.ACTIVE), ready && p == UiPhase.ACTIVE,
                ready && (p == UiPhase.IDLE || p == UiPhase.FAILED), status);
    }

    private static String withReason(Snapshot s, String base) {
        return s.message().isEmpty() ? base : s.message() + " " + base;
    }
}
