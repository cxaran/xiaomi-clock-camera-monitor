package org.x04g.home.camera;

/** ITU G.711 A-law -> signed 16-bit PCM. 8 kHz, mono; no resampling. */
final class G711 {
    private G711() {}
    static short alaw(int value) {
        value = (value & 255) ^ 0x55;
        int sample = (value & 15) << 4;
        int segment = (value & 0x70) >> 4;
        if (segment == 0) sample += 8;
        else {
            sample += 0x108;
            if (segment > 1) sample <<= segment - 1;
        }
        return (short) ((value & 0x80) != 0 ? sample : -sample);
    }
    static int decode(byte[] src, int offset, int length, short[] dst) {
        if (offset < 0 || length < 0 || offset + length > src.length || length > dst.length)
            throw new IllegalArgumentException("audio_buffer_bound");
        for (int i = 0; i < length; i++) dst[i] = alaw(src[offset+i]);
        return length;
    }
}
