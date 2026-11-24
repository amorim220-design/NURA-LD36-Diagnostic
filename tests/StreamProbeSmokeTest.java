package com.nura.ld36diagnostic;

public final class StreamProbeSmokeTest {
    public static void main(String[] args) {
        StreamProbe probe = new StreamProbe();
        byte[] sync = new byte[] {25,0,0,0,'C','N','Y','S',1,2,3,4};
        probe.add(sync, 5);
        probe.add(new byte[] {'N','Y','S',1,2,3,4}, 7);
        if (!probe.report().contains("SYNC=1")) throw new AssertionError(probe.report());
        if (probe.getBytes() != 12) throw new AssertionError("bytes");
        if (probe.getNonZeroBytes() != 9) throw new AssertionError("nonzero");
        StreamProbe empty = new StreamProbe();
        empty.add(new byte[] {0,0,0,0}, 4);
        if (!empty.report().contains("nao-zeros=0")) throw new AssertionError(empty.report());
        System.out.println("StreamProbe smoke test passed");
    }
}
