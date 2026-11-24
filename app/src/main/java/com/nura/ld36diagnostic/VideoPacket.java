package com.nura.ld36diagnostic;

import java.util.Locale;

/** Packet layout derived from the documented Mirror 2.0 protocol. */
public final class VideoPacket {
    private VideoPacket() {}

    /** MIRR/VDAT frame: length (including length), tag, magic, value, TADV, ts, angle, N, AVC Annex-B. */
    public static byte[] make(int magic, long timestampMs, int angle, byte[] annexB) {
        if (annexB == null || annexB.length == 0 || annexB.length > 1024 * 1024) {
            throw new IllegalArgumentException("Invalid H264 payload");
        }
        byte[] p = new byte[32 + annexB.length];
        put32(p, 0, p.length);
        System.arraycopy(new byte[] {'R','R','I','M'}, 0, p, 4, 4);
        put32(p, 8, magic);
        System.arraycopy(new byte[] {'T','A','D','V'}, 0, p, 16, 4);
        put32(p, 20, (int) timestampMs);
        put32(p, 24, angle);
        put32(p, 28, annexB.length);
        System.arraycopy(annexB, 0, p, 32, annexB.length);
        return p;
    }

    public static void put32(byte[] p, int offset, int v) {
        for (int i=0;i<4;i++) p[offset+i] = (byte)(v >>> (8*i));
    }
    public static boolean isAnnexB(byte[] p) {
        if (p == null) return false;
        if (p.length >= 4 && p[0]==0 && p[1]==0 && p[2]==0 && p[3]==1) return true;
        return p.length >= 3 && p[0]==0 && p[1]==0 && p[2]==1;
    }
}
