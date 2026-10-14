package org.celanwang.mio.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** 本机回环判定：仅解析 IP 字面量与 localhost，不对不可信 Host 做 DNS 查询。 */
public final class Loopback {

    public static boolean isLoopback(String host) {
        if (host == null || host.isBlank()) return false;
        if ("localhost".equalsIgnoreCase(host)) return true;
        String address = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        // Tomcat 常将 ::1 展开为 0:0:0:0:0:0:0:1。
        boolean ipv6 = address.contains(":") && address.matches("[0-9a-fA-F:.]+");
        boolean ipv4 = address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
        if (!ipv6 && !ipv4) return false;
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException ignored) {
            return false;
        }
    }

    private Loopback() {
    }
}
