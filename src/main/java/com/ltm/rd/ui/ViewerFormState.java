package com.ltm.rd.ui;

/**
 * What the Client window enables and says for a snapshot; pure, so it is tested without a window.
 * Connect stays disabled in every phase except IDLE, in particular while CLOSING (the previous
 * session has not yet confirmed that its input was released) and after an input failure.
 */
public record ViewerFormState(boolean fieldsEnabled, boolean connectEnabled, boolean cancelVisible,
        boolean disconnectVisible, boolean controlWindowVisible, String statusText) {

    public record PendingText(long generation, String text) {
        public PendingText retainFor(Snapshot snapshot) {
            return snapshot.phase() == UiPhase.IDLE && snapshot.generation() == generation ? this : null;
        }
    }

    public static PendingText pendingFor(ConnectOutcome outcome, long generation) {
        return outcome == ConnectOutcome.STARTED ? null : new PendingText(generation, textFor(outcome));
    }

    public static ViewerFormState of(Snapshot s, boolean closingWarning) {
        UiPhase p = s.phase();
        boolean idle = p == UiPhase.IDLE;
        boolean preActive = p == UiPhase.CONNECTING || p == UiPhase.AUTHENTICATING || p == UiPhase.ATTACHING;
        boolean active = p == UiPhase.ACTIVE;
        String status = switch (p) {
            case IDLE -> s.message().isEmpty() ? "Nhập IP và mã rồi bấm Kết nối." : s.message();
            case CONNECTING -> "Đang kết nối Host…";
            case AUTHENTICATING -> "Đang xác thực…";
            case ATTACHING -> "Đang thiết lập phiên với Host…";
            case ACTIVE -> s.frame() == null ? "Đang chờ ảnh Host…" : s.controlEnabled()
                    ? "Đang điều khiển Host " + s.peer() : "Ảnh bị gián đoạn — tạm khóa điều khiển.";
            case CLOSING -> s.message().isEmpty() ? "Đang đóng phiên…" : s.message();
            case FAILED -> s.message().isEmpty() ? Messages.INPUT_RELEASE_FAILED : s.message();
            case SHUTTING_DOWN -> "Ứng dụng đang đóng.";
            case STARTING, LISTENING, RESERVED -> "Đang chờ…"; // Server phases, never on the Client
        };
        return new ViewerFormState(idle, idle, preActive, active, active, status);
    }

    /** The text for a connect() the runtime did not start; empty when it started. */
    public static String textFor(ConnectOutcome outcome) {
        return switch (outcome) {
            case STARTED -> "";
            case INVALID_INPUT -> "IP hoặc mã không hợp lệ (mã gồm 6 chữ số).";
            case BUSY -> "Đang kết nối…";
            case WAITING_RELEASE -> "Đang đóng phiên…";
            case INPUT_FAULTED -> Messages.INPUT_RELEASE_FAILED;
            case SHUTTING_DOWN -> "Ứng dụng đang đóng.";
            case WRONG_ROLE -> "Vai trò không phù hợp.";
        };
    }
}
