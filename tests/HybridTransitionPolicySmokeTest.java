package com.nura.ld36diagnostic;

import java.util.Arrays;

public final class HybridTransitionPolicySmokeTest {
    public static void main(String[] args) {
        equal(HybridTransitionPolicy.profile(1080,2400),new int[]{576,1280},"v2.2 portrait");
        equal(HybridTransitionPolicy.profile(2400,1080),new int[]{1280,576},"v2.7 landscape");
        check(HybridTransitionPolicy.evaluate(1080,2400,1080,2400)
              == HybridTransitionPolicy.Decision.KEEP_SESSION,"same portrait");
        check(HybridTransitionPolicy.evaluate(1080,2400,1090,2380)
              == HybridTransitionPolicy.Decision.KEEP_SESSION,"same orientation noisy resize");
        check(HybridTransitionPolicy.evaluate(1080,2400,2400,1080)
              == HybridTransitionPolicy.Decision.NEW_CONSENT_REQUIRED,"portrait-to-landscape");
        check(HybridTransitionPolicy.evaluate(2400,1080,1080,2400)
              == HybridTransitionPolicy.Decision.NEW_CONSENT_REQUIRED,"landscape-to-portrait");
        check(!HybridTransitionPolicy.mayReconfigureCodecWhileStreaming(),
              "no verified dynamic decoder negotiation yet");
        System.out.println("Hybrid transition policy smoke test passed");
    }
    private static void equal(int[] actual,int[] expected,String message) {
        check(Arrays.equals(actual,expected),message+": "+Arrays.toString(actual));
    }
    private static void check(boolean ok,String message) {
        if (!ok) throw new AssertionError(message);
    }
}
