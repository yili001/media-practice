package com.media.center.service.torrent;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class TrackerClient {

    private static final String PEER_ID = "-MC1000-" + generatePeerId();

    public static List<Peer> getPeers(String trackerUrl, byte[] infoHash) {
        List<Peer> peers = new ArrayList<>();
        try {
            String encodedInfoHash = urlEncodeBytes(infoHash);
            String urlStr = trackerUrl + "?info_hash=" + encodedInfoHash +
                    "&peer_id=" + PEER_ID +
                    "&port=" + TorrentDownloader.getListenPort() +
                    "&uploaded=0&downloaded=0&left=0&compact=1&event=started";

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(ProxyConfig.getProxy());
            conn.setConnectTimeout(10000); // Increased to 10s
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() == 200) {
                try (InputStream in = conn.getInputStream()) {
                    byte[] response = in.readAllBytes();
                    Object decoded = BencodeParser.decode(response);

                    if (decoded instanceof Map) {
                        Map<String, Object> map = (Map<String, Object>) decoded;
                        if (map.containsKey("peers")) {
                            Object peersObj = map.get("peers");
                            if (peersObj instanceof byte[]) {
                                parseCompactPeers((byte[]) peersObj, peers);
                            } else if (peersObj instanceof List) {
                                // List of dictionaries (non-compact)
                                // Not implemented for brevity, most modern trackers use compact
                            }
                        }
                    }
                }
            } else {
                System.err.println("Tracker " + trackerUrl + " responded with code " + conn.getResponseCode());
            }
        } catch (Exception e) {
            System.err.println("Tracker failed: " + trackerUrl + " - " + e.getMessage());
        }
        return peers;
    }

    private static void parseCompactPeers(byte[] data, List<Peer> peers) {
        // Compact peers: 6 bytes per peer (4 IP + 2 Port)
        for (int i = 0; i < data.length; i += 6) {
            if (i + 6 > data.length)
                break;

            String ip = String.format("%d.%d.%d.%d",
                    data[i] & 0xFF, data[i + 1] & 0xFF, data[i + 2] & 0xFF, data[i + 3] & 0xFF);

            int port = ((data[i + 4] & 0xFF) << 8) | (data[i + 5] & 0xFF);

            // Skip bogus peers (0.0.0.0, port 0, or port 65535)
            if ("0.0.0.0".equals(ip) || port <= 0 || port >= 65535)
                continue;

            peers.add(new Peer(ip, port));
        }
    }

    private static String generatePeerId() {
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append(r.nextInt(10));
        }
        return sb.toString();
    }

    public static String urlEncodeBytes(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append("%").append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static class Peer {
        String ip;
        int port;

        public Peer(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }

        @Override
        public String toString() {
            return ip + ":" + port;
        }
    }

    public static String getPeerId() {
        return PEER_ID;
    }
}
