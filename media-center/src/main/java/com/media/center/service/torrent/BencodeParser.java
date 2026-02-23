package com.media.center.service.torrent;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class BencodeParser {

    public static Object decode(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        return decode(buffer);
    }

    private static Object decode(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return null;
        }

        byte b = buffer.get(buffer.position());

        if (Character.isDigit(b)) {
            return decodeString(buffer);
        } else if (b == 'i') {
            return decodeInteger(buffer);
        } else if (b == 'l') {
            return decodeList(buffer);
        } else if (b == 'd') {
            return decodeDictionary(buffer);
        } else {
            throw new IllegalArgumentException("Invalid bencode format");
        }
    }

    private static byte[] decodeStringBoxed(ByteBuffer buffer) {
        int colonIndex = -1;
        int start = buffer.position();

        // Find colon
        while (buffer.hasRemaining()) {
            if (buffer.get() == ':') {
                colonIndex = buffer.position() - 1;
                break;
            }
        }

        if (colonIndex == -1) {
            throw new IllegalArgumentException("Invalid string length format");
        }

        buffer.position(start);
        byte[] lenBytes = new byte[colonIndex - start];
        buffer.get(lenBytes);
        int length = Integer.parseInt(new String(lenBytes));

        // Skip colon
        buffer.get();

        byte[] strBytes = new byte[length];
        if (buffer.remaining() < length) {
            throw new IllegalArgumentException(
                    "String length " + length + " exceeds remaining buffer " + buffer.remaining());
        }
        buffer.get(strBytes);
        return strBytes;
    }

    private static String decodeString(ByteBuffer buffer) {
        // Use ISO_8859_1 (1:1 byte mapping) to preserve binary data like SHA1 hashes.
        // Bencode strings are raw byte sequences, not necessarily text.
        return new String(decodeStringBoxed(buffer), StandardCharsets.ISO_8859_1);
    }

    private static Long decodeInteger(ByteBuffer buffer) {
        buffer.get(); // consume 'i'
        int start = buffer.position();
        int end = -1;
        while (buffer.hasRemaining()) {
            if (buffer.get() == 'e') {
                end = buffer.position() - 1;
                break;
            }
        }

        if (end == -1)
            throw new IllegalArgumentException("Invalid integer format");

        buffer.position(start);
        byte[] numBytes = new byte[end - start];
        buffer.get(numBytes);
        buffer.get(); // consume 'e'

        return Long.parseLong(new String(numBytes));
    }

    private static List<Object> decodeList(ByteBuffer buffer) {
        buffer.get(); // consume 'l'
        List<Object> list = new ArrayList<>();
        while (buffer.hasRemaining()) {
            if (buffer.get(buffer.position()) == 'e') {
                buffer.get(); // consume 'e'
                return list;
            }
            list.add(decode(buffer));
        }
        throw new IllegalArgumentException("Unterminated list");
    }

    private static Map<String, Object> decodeDictionary(ByteBuffer buffer) {
        buffer.get(); // consume 'd'
        Map<String, Object> map = new TreeMap<>(); // Keys must be sorted in Bencode, but we just store them
        while (buffer.hasRemaining()) {
            if (buffer.get(buffer.position()) == 'e') {
                buffer.get(); // consume 'e'
                return map;
            }

            // Keys are always strings
            String key = decodeString(buffer);
            Object value = decode(buffer);
            map.put(key, value);
        }
        throw new IllegalArgumentException("Unterminated dictionary");
    }
}
