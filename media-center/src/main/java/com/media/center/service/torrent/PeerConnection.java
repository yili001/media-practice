package com.media.center.service.torrent;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class PeerConnection implements AutoCloseable {

    private final String peerIp;
    private final int peerPort;
    private final byte[] infoHash;
    private final String peerId;

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;

    private boolean choked = true;
    private boolean interested = false;

    private Map<Long, Byte> extensionIds = new HashMap<>(); // Extension Name -> ID
    private Integer metadataSize = null;

    public PeerConnection(String peerIp, int peerPort, byte[] infoHash, String peerId) {
        this.peerIp = peerIp;
        this.peerPort = peerPort;
        this.infoHash = infoHash;
        this.peerId = peerId;
    }

    public void connect() throws IOException {
        socket = new Socket(ProxyConfig.getPeerProxy());
        // Short timeout for connect
        socket.connect(new InetSocketAddress(peerIp, peerPort), 5000);
        socket.setSoTimeout(10000); // 10 sec read timeout

        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());

        performHandshake();
    }

    private void performHandshake() throws IOException {
        // 1. BitTorrent Protocol
        String protocol = "BitTorrent protocol";
        out.writeByte(19);
        out.writeBytes(protocol);

        // 2. Reserved bytes (support extension protocol bit 20 from right =
        // 0x0000000000100000)
        // We set the 5th byte (index 20 from end counting bits?) to include extension
        // bit
        // Actually it's the 6th byte from left (0-indexed 5) containing bit 0x10
        // 00 00 00 00 00 10 00 00
        byte[] reserved = new byte[8];
        reserved[5] = 0x10;
        out.write(reserved);

        // 3. Info Hash
        out.write(infoHash);

        // 4. Peer ID
        out.writeBytes(peerId);
        out.flush();

        // Read response
        int pstrlen = in.readUnsignedByte();
        if (pstrlen != 19)
            throw new IOException("Invalid protocol length: " + pstrlen);

        byte[] pstr = new byte[19];
        in.readFully(pstr);
        if (!"BitTorrent protocol".equals(new String(pstr, StandardCharsets.ISO_8859_1))) {
            throw new IOException("Invalid protocol name");
        }

        byte[] peerReserved = new byte[8];
        in.readFully(peerReserved);

        byte[] peerInfoHash = new byte[20];
        in.readFully(peerInfoHash);
        if (!Arrays.equals(infoHash, peerInfoHash)) {
            throw new IOException("Info hash mismatch");
        }

        byte[] peerIdBytes = new byte[20];
        in.readFully(peerIdBytes);

        // Check for extension support (bit 20 from right, so byte 5, bit 0x10)
        if ((peerReserved[5] & 0x10) != 0) {
            sendExtensionHandshake();
        }
    }

    private void sendExtensionHandshake() throws IOException {
        // ID 20 (Extended)
        // Msg ID 00 (Handshake)
        // Dictionary: { "m": { "ut_metadata": 1 } }

        Map<String, Object> m = new HashMap<>();
        m.put("ut_metadata", 1);

        Map<String, Object> handshake = new HashMap<>();
        handshake.put("m", m);

        String bencoded = mapToBencode(handshake); // Need encoder... simple one for now
        byte[] data = bencoded.getBytes(StandardCharsets.ISO_8859_1);

        int len = 2 + data.length; // 1 byte ID + 1 byte MsgID + data
        out.writeInt(len);
        out.write(20);
        out.write(0);
        out.write(data);
        out.flush();
    }

    // Very simple Bencode encoder for this specific Map<String, Map<String,
    // Integer>> case
    private String mapToBencode(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append('d');
        // m dictionary
        if (map.containsKey("m")) {
            sb.append("1:m");
            sb.append('d');
            Map<String, Integer> m = (Map<String, Integer>) map.get("m");
            if (m.containsKey("ut_metadata")) {
                sb.append("11:ut_metadatai").append(m.get("ut_metadata")).append('e');
            }
            sb.append('e');
        }
        // For requestMetadataPieces
        if (map.containsKey("msg_type")) {
            sb.append("8:msg_typei").append(map.get("msg_type")).append('e');
        }
        if (map.containsKey("piece")) {
            sb.append("5:piecei").append(map.get("piece")).append('e');
        }
        sb.append('e');
        return sb.toString();
    }

    private int utMetadataId = -1; // The ID peer expects for ut_metadata messages

    private void handleExtensionHandshake(byte[] payload) {
        try {
            Object decoded = BencodeParser.decode(payload);
            if (decoded instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) decoded;
                if (map.containsKey("metadata_size")) {
                    Object sizeObj = map.get("metadata_size");
                    if (sizeObj instanceof Long)
                        this.metadataSize = ((Long) sizeObj).intValue();
                    else if (sizeObj instanceof Integer)
                        this.metadataSize = (Integer) sizeObj;
                }
                if (map.containsKey("m")) {
                    Map<String, Object> m = (Map<String, Object>) map.get("m");
                    if (m != null && m.containsKey("ut_metadata")) {
                        Object idObj = m.get("ut_metadata");
                        if (idObj instanceof Long) {
                            this.utMetadataId = ((Long) idObj).intValue();
                        } else if (idObj instanceof Integer) {
                            this.utMetadataId = (Integer) idObj;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static final int METADATA_PIECE_SIZE = 16384; // 16 KB per BEP 9

    private void requestMetadataPiece(int piece) throws IOException {
        if (utMetadataId == -1)
            return;

        Map<String, Object> request = new HashMap<>();
        request.put("msg_type", 0);
        request.put("piece", piece);

        String bencoded = mapToBencode(request);
        byte[] data = bencoded.getBytes(StandardCharsets.ISO_8859_1);

        int len = 2 + data.length;
        out.writeInt(len);
        out.write(20); // Extension Protocol ID
        out.write(utMetadataId); // Peer's ID for ut_metadata
        out.write(data);
        out.flush();
    }

    private void requestAllMetadataPieces() throws IOException {
        if (utMetadataId == -1 || metadataSize == null || metadataSize == 0)
            return;

        int numPieces = (metadataSize + METADATA_PIECE_SIZE - 1) / METADATA_PIECE_SIZE;
        for (int i = 0; i < numPieces; i++) {
            requestMetadataPiece(i);
        }
    }

    // Helper to decode extension message headers properly since they are
    // concatenated with data
    private MetadataPieceResult handleMetadataMessage(byte[] payload) {
        // Payload is: Bencoded Dictionary + Binary Data
        // We need to find where the dictionary ends and binary data begins
        try {
            if (payload.length == 0 || payload[0] != 'd')
                return null;

            // Proper bencode-aware scanner to find end of dictionary
            int endOfDict = findBencodeEnd(payload, 0);

            if (endOfDict != -1 && endOfDict <= payload.length) {
                byte[] dictBytes = new byte[endOfDict];
                System.arraycopy(payload, 0, dictBytes, 0, endOfDict);

                Object decoded = BencodeParser.decode(dictBytes);
                if (decoded instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) decoded;
                    if (map.containsKey("msg_type")) {
                        long msgType = (Long) map.get("msg_type");
                        if (msgType == 1) {
                            // Data piece! Binary data follows dict
                            int pieceIndex = 0;
                            if (map.containsKey("piece")) {
                                pieceIndex = ((Long) map.get("piece")).intValue();
                            }
                            int dataLen = payload.length - endOfDict;
                            byte[] data = new byte[dataLen];
                            System.arraycopy(payload, endOfDict, data, 0, dataLen);
                            return new MetadataPieceResult(pieceIndex, data);
                        } else if (msgType == 2) {
                            // Reject - peer doesn't have metadata
                            return null;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Silently ignore parse errors — try next peer
        }
        return null;
    }

    /** Simple holder for piece index + data */
    private static class MetadataPieceResult {
        final int piece;
        final byte[] data;

        MetadataPieceResult(int piece, byte[] data) {
            this.piece = piece;
            this.data = data;
        }
    }

    /**
     * Scans a bencoded element starting at pos, returns the index AFTER the element
     * ends.
     * Returns -1 if malformed.
     */
    private int findBencodeEnd(byte[] data, int pos) {
        if (pos >= data.length)
            return -1;
        byte b = data[pos];

        if (b == 'd' || b == 'l') {
            // Dictionary or List: consume 'd'/'l', then elements, then 'e'
            pos++;
            while (pos < data.length) {
                if (data[pos] == 'e') {
                    return pos + 1; // past the 'e'
                }
                if (b == 'd') {
                    // Key (string)
                    pos = findBencodeEnd(data, pos);
                    if (pos == -1)
                        return -1;
                }
                // Value
                pos = findBencodeEnd(data, pos);
                if (pos == -1)
                    return -1;
            }
            return -1; // unterminated
        } else if (b == 'i') {
            // Integer: i<number>e
            int eIdx = pos + 1;
            while (eIdx < data.length && data[eIdx] != 'e')
                eIdx++;
            return (eIdx < data.length) ? eIdx + 1 : -1;
        } else if (b >= '0' && b <= '9') {
            // String: <length>:<data>
            int colonIdx = pos;
            while (colonIdx < data.length && data[colonIdx] != ':')
                colonIdx++;
            if (colonIdx >= data.length)
                return -1;
            int strLen;
            try {
                strLen = Integer.parseInt(new String(data, pos, colonIdx - pos));
            } catch (NumberFormatException e) {
                return -1;
            }
            return colonIdx + 1 + strLen;
        }
        return -1; // unknown
    }

    public byte[] fetchMetadata() throws IOException {
        // Read loop until we get all metadata pieces or timeout
        long startTime = System.currentTimeMillis();

        // Pieces will be collected here once we know the count
        Map<Integer, byte[]> pieces = new HashMap<>();
        int totalPieces = -1;
        boolean piecesRequested = false;

        while (System.currentTimeMillis() - startTime < 15000) { // 15s wait
            if (in.available() < 4) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                }
                continue;
            }

            int len = in.readInt();
            if (len <= 0)
                continue; // Keep alive

            byte id = in.readByte();
            if (id == 20) { // Extended
                byte extMsgId = in.readByte();
                byte[] payload = new byte[len - 2];
                in.readFully(payload);

                if (extMsgId == 0) { // Handshake
                    handleExtensionHandshake(payload);
                    if (metadataSize != null && metadataSize > 0) {
                        totalPieces = (metadataSize + METADATA_PIECE_SIZE - 1) / METADATA_PIECE_SIZE;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (Exception e) {
                    }
                    if (!piecesRequested) {
                        requestAllMetadataPieces();
                        piecesRequested = true;
                    }
                } else if (extMsgId == (byte) 1) {
                    // ut_metadata response — we advertised ID 1, so peer sends us on ID 1
                    MetadataPieceResult result = handleMetadataMessage(payload);
                    if (result != null) {
                        pieces.put(result.piece, result.data);

                        // Check if we have all pieces
                        if (totalPieces > 0 && pieces.size() >= totalPieces) {
                            return reassembleMetadata(pieces, totalPieces);
                        }
                    }
                }
            } else {
                // Skip other messages
                long skipped = 0;
                while (skipped < len - 1) {
                    skipped += in.skip(len - 1 - skipped);
                }
            }
        }

        // Timeout — return what we have if we got all pieces
        if (totalPieces > 0 && pieces.size() >= totalPieces) {
            return reassembleMetadata(pieces, totalPieces);
        }
        return null;
    }

    private byte[] reassembleMetadata(Map<Integer, byte[]> pieces, int totalPieces) {
        // Calculate total size
        int totalSize = 0;
        for (int i = 0; i < totalPieces; i++) {
            byte[] piece = pieces.get(i);
            if (piece == null)
                return null; // Missing piece
            totalSize += piece.length;
        }

        byte[] metadata = new byte[totalSize];
        int offset = 0;
        for (int i = 0; i < totalPieces; i++) {
            byte[] piece = pieces.get(i);
            System.arraycopy(piece, 0, metadata, offset, piece.length);
            offset += piece.length;
        }
        return metadata;
    }

    @Override
    public void close() throws IOException {
        if (socket != null && !socket.isClosed())
            socket.close();
    }
}
