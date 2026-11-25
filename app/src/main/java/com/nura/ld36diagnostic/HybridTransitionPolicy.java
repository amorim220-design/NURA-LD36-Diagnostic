package com.nura.ld36diagnostic;

/**
 * Pure, non-Android transition policy for the two profiles confirmed on LD36.
 *
 * STAR conveys video width/height in its response once per negotiated session.
 * VDAT carries H264 Annex-B and an angle; it does NOT carry a second size pair.
 * Without verified LD36 decoder/SPS renegotiation, changing encoder dimensions
 * mid-VDAT is unsafe. This policy deliberately refuses such a transition.
 *
 * It never opens USB, sends protocol messages, or touches a MediaCodec.
 */
public final class HybridTransitionPolicy {
    public enum Decision { KEEP_SESSION, NEW_CONSENT_REQUIRED }
    private HybridTransitionPolicy() { }

    public static boolean portrait(int sourceWidth, int sourceHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0)
            throw new IllegalArgumentException("Invalid source dimensions");
        return sourceHeight >= sourceWidth;
    }

    /** v2.2 portrait aspect, 16-pixel alignment; v2.7 landscape reference. */
    public static int[] profile(int sourceWidth, int sourceHeight) {
        if (portrait(sourceWidth, sourceHeight)) {
            int w = Math.round((1280f * sourceWidth / sourceHeight) / 16f) * 16;
            return new int[]{Math.max(320, Math.min(720, w)), 1280};
        }
        return new int[]{1280,576};
    }

    /**
     * No live SPS/PPS switch is authorized. Repeated notifications
     * and brief dimension changes must be handled by existing debounce.
     */
    public static Decision evaluate(int initialWidth, int initialHeight,
                                    int newWidth, int newHeight) {
        return portrait(initialWidth,initialHeight) == portrait(newWidth,newHeight)
            ? Decision.KEEP_SESSION : Decision.NEW_CONSENT_REQUIRED;
    }

    public static boolean mayReconfigureCodecWhileStreaming() {
        // Until tests with real LD36 decoder prove safe renegotiation.
        return false;
    }
}
