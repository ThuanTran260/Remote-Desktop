package com.ltm.rd.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.Packet;
import com.ltm.rd.host.AdapterException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class MessagesTest {

    @Test void v2AuthAndShutdownMessagesAreNormalized() {
        assertEquals("Host đang có phiên khác hoặc đang dọn phiên trước. Hãy thử lại sau.", Messages.forAuth(Packet.AuthStatus.BUSY));
        assertEquals("Phiên bản khác nhau. Hãy dùng cùng một bản jar trên hai máy.", Messages.forAuth(Packet.AuthStatus.BAD_VERSION));
        assertEquals("Không kết nối được tới Host. Kiểm tra IP, cổng và tường lửa.",
                Messages.forThrowable(new java.net.ConnectException("secret123456")));
        assertEquals("Chưa xác nhận dọn sạch kết nối, luồng và phím/chuột. Hãy kiểm tra máy Host trước khi tiếp tục.",
                Messages.forShutdown(new ShutdownReport(false, true, InputRelease.NOT_NEEDED)));
    }

    @Test
    void everyDisconnectReasonHasNonEmptyMessage() {
        for (Packet.DisconnectReason reason : Packet.DisconnectReason.values()) {
            String message = Messages.forClose(reason);
            assertFalse(message.isBlank(), reason.name());
            assertFalse(message.chars().anyMatch(Character::isDigit), reason + " leaks digits: " + message);
        }
    }

    @Test
    void authStatusMessagesAreExact() {
        assertEquals("Mã không đúng.", Messages.forAuth(Packet.AuthStatus.WRONG_CODE));
        assertEquals("Host đang có phiên khác hoặc đang dọn phiên trước. Hãy thử lại sau.", Messages.forAuth(Packet.AuthStatus.BUSY));
        assertEquals("Bị khóa tạm thời do nhập sai nhiều lần. Thử lại sau 30 giây.",
                Messages.forAuth(Packet.AuthStatus.LOCKED));
        assertEquals("Phiên bản khác nhau. Hãy dùng cùng một bản jar trên hai máy.", Messages.forAuth(Packet.AuthStatus.BAD_VERSION));
    }

    @Test
    void bindExceptionMessageIsExact() {
        String expected = "Cổng đã được sử dụng. Đổi bằng --cmd-port/--img-port.";
        assertEquals(expected, Messages.forThrowable(new BindException("Address already in use")));
        assertEquals(expected, Messages.forThrowable(new CompletionException(new BindException("x"))));
    }

    @Test
    void connectionAndAdapterFailuresHaveExactMessages() {
        String unreachable = "Không kết nối được tới Host. Kiểm tra IP, cổng và tường lửa.";
        assertEquals(unreachable, Messages.forThrowable(new ConnectException("refused")));
        assertEquals(unreachable, Messages.forThrowable(new NoRouteToHostException("no route")));
        assertEquals(unreachable, Messages.forThrowable(new UnknownHostException("nowhere")));
        assertEquals("Không có môi trường đồ họa để chia sẻ màn hình.",
                Messages.forThrowable(new AdapterException(AdapterException.Kind.HEADLESS, null)));
        assertEquals("Không tạo được Robot. Kiểm tra quyền điều khiển màn hình của hệ điều hành.",
                Messages.forThrowable(new AdapterException(AdapterException.Kind.ROBOT_UNAVAILABLE, null)));
        assertEquals("Không đọc được cấu hình màn hình.",
                Messages.forThrowable(new AdapterException(AdapterException.Kind.NO_SCREEN, null)));
        assertEquals("Lỗi không xác định. Xem nhật ký để biết loại lỗi.",
                Messages.forThrowable(new IllegalStateException("secret detail")));
    }

    @Test
    void fixedMessagesAreExact() {
        assertEquals("Không nhả được phím/nút trên máy này. Hãy khởi động lại ứng dụng.",
                Messages.INPUT_RELEASE_FAILED);
        assertEquals("Đã ngắt kết nối.", Messages.forClose(Packet.DisconnectReason.USER));
        assertEquals("Ứng dụng đang đóng.", Messages.forClose(Packet.DisconnectReason.SHUTDOWN));
    }
}
