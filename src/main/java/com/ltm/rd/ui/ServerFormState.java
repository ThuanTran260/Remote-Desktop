package com.ltm.rd.ui;

/** What the Server window enables and says for a snapshot; pure, so it is tested without a window. */
public record ServerFormState(boolean regenerateEnabled, boolean disconnectEnabled, boolean controlWindowVisible,
        String statusText) {
    static final String WAITING_RELEASE = "Đang chờ nhả phím/nút trên máy này…";

    public static ServerFormState of(Snapshot s, boolean closingWarning) {
        UiPhase p = s.phase();
        String status = switch (p) {
            case IDLE -> withReason(s, "Chưa khởi động máy chủ.");
            case STARTING -> "Đang khởi động máy chủ…";
            case LISTENING -> withReason(s, "Đang chờ máy được điều khiển kết nối.");
            case RESERVED -> "Một máy đang kết nối…";
            case ACTIVE -> "Đang điều khiển " + s.peer();
            case CLOSING -> closingWarning ? WAITING_RELEASE : withReason(s, "Đang đóng phiên…");
            case FAILED -> s.message().isEmpty() ? "Máy chủ gặp lỗi." : s.message();
            case SHUTTING_DOWN -> "Ứng dụng đang đóng.";
            case CONNECTING, AUTHENTICATING, ATTACHING -> "Đang kết nối…"; // Client phases, never on the Server
        };
        return new ServerFormState(p == UiPhase.LISTENING, p == UiPhase.RESERVED || p == UiPhase.ACTIVE,
                p == UiPhase.ACTIVE, status);
    }

    private static String withReason(Snapshot s, String base) {
        return s.message().isEmpty() ? base : s.message() + " " + base;
    }
}
