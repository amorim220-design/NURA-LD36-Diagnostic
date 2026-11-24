package com.nura.ld36diagnostic;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Incremental passive observations; never assumes read() boundaries are frame boundaries. */
public final class StreamProbe {
    private final byte[] tail = new byte[12];
    private int tailSize;
    private long offset;
    private long zeroBytes;
    private long nonZeroBytes;
    private long syncCandidates;
    private long starCandidates;
    private long otherCandidates;
    private long candidateAt = -1;

    public void add(byte[] data, int length) {
        int n = Math.min(Math.max(length, 0), data.length);
        for (int i = 0; i < n; i++) {
            byte value = data[i];
            if (value == 0) zeroBytes++; else nonZeroBytes++;
            if (tailSize < tail.length) {
                tail[tailSize++] = value;
            } else {
                System.arraycopy(tail, 1, tail, 0, tail.length - 1);
                tail[tail.length - 1] = value;
            }
            offset++;
            if (tailSize == 12) inspectWindow();
        }
    }

    private void inspectWindow() {
        long len = (tail[0] & 255L) | ((tail[1] & 255L) << 8)
                | ((tail[2] & 255L) << 16) | ((tail[3] & 255L) << 24);
        if (len < 12 || len > 2 * 1024 * 1024) return;
        String tag = new String(tail, 4, 4, StandardCharsets.US_ASCII);
        if (!(tag.equals("CNYS") || tag.equals("RATS") || tag.equals("RRIM")
                || tag.equals("_PPA") || tag.equals("EVIL"))) return;
        candidateAt = offset - 12;
        if (tag.equals("CNYS")) syncCandidates++;
        else if (tag.equals("RATS")) starCandidates++;
        else otherCandidates++;
    }

    public String report() {
        return String.format(Locale.US,
                "Stream RX: %d bytes | zeros=%d | nao-zeros=%d | candidatos SYNC=%d STAR=%d outros=%d | ultimo offset candidato=%d. "
                + "Candidatos nao confirmam frames completos, nem handshake.",
                offset, zeroBytes, nonZeroBytes, syncCandidates, starCandidates, otherCandidates, candidateAt);
    }

    public long getBytes() { return offset; }
    public long getNonZeroBytes() { return nonZeroBytes; }
}
