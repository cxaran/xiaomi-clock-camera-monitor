package org.x04g.home.gate;

import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Tuya55AA v3.3: AES128 ECB PKCS7 and CRC32. The MD5 text prefix belongs to v3.1, not v3.3. */
public final class Tuya33Codec {
    static final int MAX_FRAME_BYTES = 65536, DP_QUERY = 10, CONTROL = 7, STATUS = 8;
    private static final int PREFIX = 0x55aa, SUFFIX = 0xaa55;
    private static final byte[] VERSION = {'3', '.', '3', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    private Tuya33Codec() {}

    static byte[] request(int sequence, int command, byte[] json, byte[] key) throws Exception {
        if (command != DP_QUERY && command != CONTROL) throw new IllegalArgumentException("command_not_allowed");
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, json, key);
        byte[] payload;
        if (command == CONTROL) {
            payload = Arrays.copyOf(VERSION, VERSION.length + encrypted.length);
            System.arraycopy(encrypted, 0, payload, VERSION.length, encrypted.length);
        } else payload = encrypted; // DP_QUERY has no 15-byte protocol prefix.
        try { return frame(sequence, command, payload); }
        finally { Arrays.fill(encrypted, (byte) 0); Arrays.fill(payload, (byte) 0); }
    }

    static byte[] frame(int sequence, int command, byte[] payload) throws IOException {
        int size = 16 + payload.length + 8;
        if (size > MAX_FRAME_BYTES) throw new ProtocolException("frame_too_large");
        ByteBuffer data = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        data.putInt(PREFIX).putInt(sequence).putInt(command).putInt(payload.length + 8).put(payload);
        CRC32 crc = new CRC32(); crc.update(data.array(), 0, size - 8);
        data.putInt((int) crc.getValue()).putInt(SUFFIX);
        return data.array();
    }

    static int frameLength(byte[] header) throws IOException {
        if (header.length != 16) throw new ProtocolException("header_length");
        ByteBuffer data = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        if (data.getInt() != PREFIX) throw new ProtocolException("frame_prefix");
        data.getInt(); data.getInt();
        long body = Integer.toUnsignedLong(data.getInt());
        if (body < 12 || body > MAX_FRAME_BYTES - 16) throw new ProtocolException("frame_length");
        return (int) body + 16;
    }

    static Frame response(byte[] bytes) throws IOException {
        if (bytes.length < 28 || bytes.length != frameLength(Arrays.copyOf(bytes, 16))) throw new ProtocolException("frame_length");
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        data.getInt(); int sequence = data.getInt(), command = data.getInt(); data.getInt();
        int returnCode = data.getInt();
        long expected = Integer.toUnsignedLong(ByteBuffer.wrap(bytes, bytes.length - 8, 4).getInt());
        CRC32 crc = new CRC32(); crc.update(bytes, 0, bytes.length - 8);
        if (expected != crc.getValue() || ByteBuffer.wrap(bytes, bytes.length - 4, 4).getInt() != SUFFIX)
            throw new ProtocolException("frame_integrity");
        return new Frame(sequence, command, returnCode, Arrays.copyOfRange(bytes, 20, bytes.length - 8));
    }

    static byte[] plaintext(byte[] payload, byte[] key) throws Exception {
        int offset = 0;
        if (payload.length >= 3 && payload[0] == '3' && payload[1] == '.' && payload[2] == '3') {
            if (payload.length < VERSION.length) throw new ProtocolException("version_header");
            // L20 responses may put metadata in the twelve reserved header bytes.
            offset = VERSION.length;
        }
        if (payload.length - offset == 0 || ((payload.length - offset) & 15) != 0) throw new ProtocolException("cipher_length");
        byte[] encrypted = Arrays.copyOfRange(payload, offset, payload.length);
        try { return crypt(Cipher.DECRYPT_MODE, encrypted, key); }
        finally { Arrays.fill(encrypted, (byte) 0); }
    }

    static byte[] crypt(int mode, byte[] data, byte[] key) throws Exception {
        if (key.length != 16 || data.length > MAX_FRAME_BYTES) throw new IllegalArgumentException("cipher_parameters");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(data);
    }

    static final class Frame {
        final int sequence, command, returnCode;
        final byte[] payload;
        Frame(int sequence, int command, int returnCode, byte[] payload) {
            this.sequence = sequence; this.command = command; this.returnCode = returnCode; this.payload = payload;
        }
    }
}
