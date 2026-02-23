package com.media.center.service.torrent;

import java.net.InetSocketAddress;
import java.net.Proxy;

public class ProxyConfig {
    private static Proxy proxy = Proxy.NO_PROXY;
    private static String host;
    private static int port;
    private static Proxy.Type type = Proxy.Type.DIRECT;

    public static void setProxy(String host, String portStr, String typeStr) {
        if (host == null || host.isEmpty() || portStr == null || portStr.isEmpty()
                || "None".equalsIgnoreCase(typeStr)) {
            proxy = Proxy.NO_PROXY;
            ProxyConfig.host = null;
            ProxyConfig.port = 0;
            ProxyConfig.type = Proxy.Type.DIRECT;
            System.out.println("Proxy disabled");
            return;
        }

        try {
            int port = Integer.parseInt(portStr);
            Proxy.Type type = "SOCKS".equalsIgnoreCase(typeStr) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;

            proxy = new Proxy(type, new InetSocketAddress(host, port));
            ProxyConfig.host = host;
            ProxyConfig.port = port;
            ProxyConfig.type = type;
            System.out.println("Proxy set to " + type + " " + host + ":" + port);
        } catch (NumberFormatException e) {
            System.err.println("Invalid proxy port: " + portStr);
            proxy = Proxy.NO_PROXY;
        }
    }

    /**
     * Proxy for HTTP requests (tracker announcements). Works with both HTTP and
     * SOCKS.
     */
    public static Proxy getProxy() {
        return proxy;
    }

    /**
     * Proxy for raw TCP peer connections (BitTorrent wire protocol).
     * Peer connections always go direct — the proxy is for tracker HTTP
     * requests only (bypassing ISP blocks). Routing hundreds of raw TCP
     * connections through a proxy overwhelms it and adds latency.
     * For full anonymity, use a system-level VPN instead.
     */
    public static Proxy getPeerProxy() {
        return Proxy.NO_PROXY;
    }
}
