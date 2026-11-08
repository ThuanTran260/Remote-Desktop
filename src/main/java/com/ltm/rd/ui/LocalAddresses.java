package com.ltm.rd.ui;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/** The IPv4 addresses this machine can be reached on, for the Server window. */
public final class LocalAddresses {
    private LocalAddresses() {}

    /** From the real network interfaces; empty if they cannot be read. Not for the EDT. */
    public static List<String> ipv4() {
        List<InetAddress> all = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                all.addAll(Collections.list(nic.getInetAddresses()));
            }
        } catch (SocketException e) {
            return List.of();
        }
        return filterIpv4(all);
    }

    /** IPv4 only; loopback and link-local are dropped; sorted ascending by text, without duplicates. */
    static List<String> filterIpv4(Collection<InetAddress> all) {
        TreeSet<String> result = new TreeSet<>();
        for (InetAddress a : all) {
            if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                result.add(a.getHostAddress());
            }
        }
        return List.copyOf(result);
    }
}
