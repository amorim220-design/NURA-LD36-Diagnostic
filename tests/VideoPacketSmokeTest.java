package com.nura.ld36diagnostic;

public final class VideoPacketSmokeTest {
    public static void main(String[] args) {
        byte[] nal = new byte[] {0,0,0,1,0x67,0x42,0x00,0x1f};
        byte[] data = VideoPacket.make(0x11223344, 123456L, 0, nal);
        if (data.length != 40 || (data[0]&255)!=40) throw new AssertionError("len");
        if (data[4]!='R'||data[5]!='R'||data[6]!='I'||data[7]!='M') throw new AssertionError("tag");
        if (data[8]!=0x44||data[9]!=0x33||data[10]!=0x22||data[11]!=0x11) throw new AssertionError("magic");
        if (data[16]!='T'||data[17]!='A'||data[18]!='D'||data[19]!='V') throw new AssertionError("cmd");
        if (data[28]!=8 || data[32]!=0||data[35]!=1) throw new AssertionError("payload");
        if (!VideoPacket.isAnnexB(nal)) throw new AssertionError("AnnexB");
        boolean failed=false;
        try { VideoPacket.make(0,0,0,new byte[0]); } catch (IllegalArgumentException ex) {failed=true;}
        if (!failed) throw new AssertionError("empty data accepted");
        System.out.println("VDAT packet framing test passed");
    }
}
