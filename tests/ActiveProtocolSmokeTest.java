package com.nura.ld36diagnostic;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ActiveProtocolSmokeTest {
    public static void main(String[] args) throws Exception {
        List<byte[]> out = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        ActiveProtocolSession session = new ActiveProtocolSession(new ActiveProtocolSession.Sink(){
            @Override public void send(byte[] b,String label) { out.add(b.clone()); }
            @Override public void note(String s) { notes.add(s); }
        });
        byte[] zero = {0,0,0,0};
        session.accept(zero,2);
        session.accept(new byte[]{0,0},2);
        assertTrue(out.size()==1 && out.get(0).length==4, "fragmented heartbeat");
        byte[] sync = new byte[25];
        sync[0]=25;
        System.arraycopy(new byte[]{'C','N','Y','S'},0,sync,4,4);
        sync[8]=0x34; sync[9]=0x12; sync[10]=(byte)0xa5;sync[11]=(byte)0xa5;
        sync[16]=2;sync[20]=1;sync[22]=1;
        session.accept(sync,7);
        session.accept(java.util.Arrays.copyOfRange(sync,7,25),18);
        assertTrue(out.size()==3,"SYNC responses");
        assertTrue(out.get(1).length==28 && out.get(2).length==30,"SYNC packet sizes");
        assertTrue(out.get(1)[12]==0x34 && out.get(1)[14]==(byte)0xa5,"magic echo");
        byte[] star = new byte[32];
        star[0]=32;
        System.arraycopy(new byte[]{'R','R','I','M'},0,star,4,4);
        System.arraycopy(new byte[]{'R','A','T','S'},0,star,16,4);
        star[20]=0;star[21]=5;star[22]=(byte)0xd0;star[23]=2;
        session.accept(star,32);
        assertTrue(out.size()==4 && out.get(3).length==48,"STAR response");
        assertTrue(session.summary().contains("STAR=true"),"state");
        System.out.println("Active protocol smoke test passed");
    }
    private static void assertTrue(boolean ok,String message) {
        if (!ok) throw new AssertionError(message);
    }
}
