package com.ltm.rd.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalAddressesTest {

    @Test
    void filtersLoopbackLinkLocalAndIpv6() throws Exception {
        List<InetAddress> all = List.of(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("192.168.1.5"),
                InetAddress.getByName("fe80::1"), InetAddress.getByName("10.0.0.2"),
                InetAddress.getByName("169.254.3.4"), InetAddress.getByName("::1"));
        assertEquals(List.of("10.0.0.2", "192.168.1.5"), LocalAddresses.filterIpv4(all));
    }

    @Test
    void duplicatesAreCollapsed() throws Exception {
        List<InetAddress> all = List.of(InetAddress.getByName("10.0.0.2"), InetAddress.getByName("10.0.0.2"));
        assertEquals(List.of("10.0.0.2"), LocalAddresses.filterIpv4(all));
    }

    @Test
    void realLookupNeverThrowsAndReturnsOnlyUsableAddresses() {
        for (String a : LocalAddresses.ipv4()) {
            assertTrue(a.matches("\\d+\\.\\d+\\.\\d+\\.\\d+"), a);
            assertFalse(a.startsWith("127.") || a.startsWith("169.254."), a);
        }
    }
}
