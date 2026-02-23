package com.media.center.service.torrent;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MagnetLink {
    private final String rawLink;
    private String xt; // Exact Topic (URN usually containing hash)
    private String dn; // Display Name
    private final List<String> tr = new ArrayList<>(); // Trackers
    private byte[] infoHash;

    public MagnetLink(String magnetLink) {
        this.rawLink = magnetLink;
        parse();
    }

    private void parse() {
        if (!rawLink.startsWith("magnet:?")) {
            throw new IllegalArgumentException("Invalid magnet link");
        }

        String query = rawLink.substring(8);
        String[] params = query.split("&");

        for (String param : params) {
            int idx = param.indexOf('=');
            if (idx == -1)
                continue;

            String key = param.substring(0, idx);
            String value;
            try {
                value = URLDecoder.decode(param.substring(idx + 1), StandardCharsets.UTF_8.name());
            } catch (UnsupportedEncodingException e) {
                value = param.substring(idx + 1);
            }

            if ("xt".equals(key)) {
                this.xt = value;
                parseInfoHash(value);
            } else if ("dn".equals(key)) {
                this.dn = value;
            } else if ("tr".equals(key)) {
                this.tr.add(value);
            }
        }
    }

    private void parseInfoHash(String urn) {
        // expect urn:btih:<hash>
        if (urn.startsWith("urn:btih:")) {
            String hash = urn.substring(9);
            if (hash.length() == 40) {
                this.infoHash = hexStringToByteArray(hash);
            } else if (hash.length() == 32) {
                // Base32 (not implemented here for brevity, assume hex for standard BT)
                throw new UnsupportedOperationException("Base32 hash not supported yet, use Hex");
            }
        }
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    public static String byteArrayToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public String getDisplayName() {
        return dn;
    }

    public byte[] getInfoHash() {
        return infoHash;
    }

    public String getHexInfoHash() {
        return byteArrayToHexString(infoHash);
    }

    public List<String> getTrackers() {
        return tr;
    }
}
