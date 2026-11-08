package com.ltm.rd.ui;

import com.ltm.rd.common.Packet;
import com.ltm.rd.host.AdapterException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * The only user-visible texts that describe runtime events. Internal close messages and exception
 * texts never reach the UI: they can carry addresses or other details.
 */
public final class Messages {
    public static final String INPUT_RELEASE_FAILED =
            "Không nhả được phím/nút trên máy này. Hãy khởi động lại ứng dụng.";

    /** The input device failed while a session was running; the session was closed. */
    public static final String INPUT_FAULTED =
            "Thiết bị nhập liệu bị lỗi nên phiên đã đóng. Hãy khởi động lại ứng dụng.";

    private static final String UNKNOWN = "Lỗi không xác định. Xem nhật ký để biết loại lỗi.";

    private Messages() {}

    public static String forShutdown(ShutdownReport report) {
        if (report != null && report.socketsClosed() && report.workersStopped()
                && (report.inputRelease() == InputRelease.NOT_NEEDED || report.inputRelease() == InputRelease.RELEASED)) return "";
        return "Chưa xác nhận dọn sạch kết nối, luồng và phím/chuột. Hãy kiểm tra máy Host trước khi tiếp tục.";
    }

    public static String forAuth(Packet.AuthStatus status) {
        return switch (status) {
            case OK -> "";
            case WRONG_CODE -> "Mã không đúng.";
            case BUSY -> "Host đang có phiên khác hoặc đang dọn phiên trước. Hãy thử lại sau.";
            case LOCKED -> "Bị khóa tạm thời do nhập sai nhiều lần. Thử lại sau 30 giây.";
            case BAD_VERSION -> "Phiên bản khác nhau. Hãy dùng cùng một bản jar trên hai máy.";
        };
    }

    public static String forClose(Packet.DisconnectReason reason) {
        return switch (reason) {
            case USER -> "Đã ngắt kết nối.";
            case TIMEOUT -> "Hết thời gian chờ phản hồi.";
            case PROTOCOL_ERROR -> "Lỗi giao thức hoặc mất kết nối.";
            case SCREEN_CHANGED -> "Cấu hình màn hình đã thay đổi. Hãy kết nối lại.";
            case OVERFLOW -> "Hàng đợi lệnh bị đầy, phiên đã đóng.";
            case SHUTDOWN -> "Ứng dụng đang đóng.";
        };
    }

    public static String forThrowable(Throwable t) {
        Throwable cause = t;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof BindException) {
            return "Cổng đã được sử dụng. Đổi bằng --cmd-port/--img-port.";
        }
        if (cause instanceof ConnectException || cause instanceof NoRouteToHostException
                || cause instanceof UnknownHostException) {
            return "Không kết nối được tới Host. Kiểm tra IP, cổng và tường lửa.";
        }
        if (cause instanceof AdapterException a) {
            return switch (a.kind()) {
                case HEADLESS -> "Không có môi trường đồ họa để chia sẻ màn hình.";
                case ROBOT_UNAVAILABLE -> "Không tạo được Robot. Kiểm tra quyền điều khiển màn hình của hệ điều hành.";
                case NO_SCREEN -> "Không đọc được cấu hình màn hình.";
            };
        }
        return UNKNOWN;
    }
}
