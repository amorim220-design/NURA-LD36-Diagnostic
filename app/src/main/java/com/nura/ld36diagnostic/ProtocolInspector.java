package com.nura.ld36diagnostic;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A raw USB read is NOT necessarily a protocol packet. This helper provides
 * offset-zero candidates only; the exported raw stream is the source of truth.
 */
public final class ProtocolInspector {
    private ProtocolInspector() { }

    public static String inspect(byte[] buffer, int length, int previewLimit) {
        if (buffer == null || length <= 0) return "RX vazio";
        if (length > buffer.length) length = buffer.length;

        StringBuilder out = new StringBuilder();
        if (length >= 4) {
            long le = ((long) (buffer[0] & 255))
                    | ((long) (buffer[1] & 255) << 8)
                    | ((long) (buffer[2] & 255) << 16)
                    | ((long) (buffer[3] & 255) << 24);
            long be = ((long) (buffer[3] & 255))
                    | ((long) (buffer[2] & 255) << 8)
                    | ((long) (buffer[1] & 255) << 16)
                    | ((long) (buffer[0] & 255) << 24);
            if (le == 0) {
                out.append("Possivel heartbeat 00 00 00 00; ");
            } else {
                out.append("candidato len LE=").append(le)
                        .append(" BE=").append(be).append("; ");
            }
        }
        if (length >= 8) {
            String rawTag = new String(buffer, 4, 4, StandardCharsets.US_ASCII);
            String tag = new StringBuilder(rawTag).reverse().toString();
            out.append("tag@4=").append(safeAscii(rawTag))
                    .append(" (invertida=").append(safeAscii(tag)).append("); ");
        }
        if (length >= 12) {
            out.append("magic@8=").append(toHex(buffer, 8, 4)).append("; ");
        }
        out.append("HEX=").append(toHex(buffer, 0, Math.min(length, previewLimit)));
        if (length > previewLimit) out.append(" ... [mais ").append(length - previewLimit).append(" B]");
        out.append(" [apenas hipotese se bloco alinhado]");
        return out.toString();
    }

    public static String toHex(byte[] bytes, int offset, int length) {
        if (bytes == null) return "";
        int from = Math.max(0, offset);
        int end = Math.min(bytes.length, from + Math.max(0, length));
        StringBuilder sb = new StringBuilder(Math.max(0, end - from) * 3);
        for (int i = from; i < end; i++) {
            if (i > from) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", bytes[i] & 255));
        }
        return sb.toString();
    }

    private static String safeAscii(String s) {
        return s.replaceAll("[^\\x20-\\x7E]", "?");
    }
}
