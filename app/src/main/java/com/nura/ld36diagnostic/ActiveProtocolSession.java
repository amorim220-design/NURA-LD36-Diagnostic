package com.nura.ld36diagnostic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

/**
 * USB AOA v2 diagnostic state machine. Only replies to incoming documented
 * heartbeat / SYNC / STAR frames. Never probes random commands or firmware.
 * This is a diagnostic, NOT screen recording, decoding, or audio playback.
 */
public final class ActiveProtocolSession {
    public interface Sink {
        void send(byte[] data, String label) throws IOException;
        void note(String message);
    }
    private final Sink sink;
    private byte[] waiting = new byte[0];
    private int magic = 0x4E555241;
    private long rxFrames;
    private long echoedHeartbeats;
    private boolean synced;
    private boolean startSeen;
    private boolean syncAcknowledged;
    private int unknownBytes;
    private int replies;
    private static final int MAX_FRAME = 2 * 1024 * 1024;

    public ActiveProtocolSession(Sink sink) { this.sink = sink; }

    public synchronized void accept(byte[] incoming, int len) throws IOException {
        if (len <= 0) return;
        if (len > incoming.length) len = incoming.length;
        if (waiting.length + len > MAX_FRAME + 16384) {
            sink.note("RX: buffer excedeu limite de segurança; descartando fragmentos.");
            waiting = new byte[0];
        }
        byte[] fresh = Arrays.copyOf(waiting, waiting.length + len);
        System.arraycopy(incoming, 0, fresh, waiting.length, len);
        waiting = fresh;
        int pos = 0;
        while (waiting.length - pos >= 4) {
            int length = get32(waiting, pos);
            if (length == 0) {
                transmit(new byte[] {0,0,0,0}, "ECHO_HEARTBEAT");
                echoedHeartbeats++;
                pos += 4;
                continue;
            }
            if (length < 16 || length > MAX_FRAME) {
                unknownBytes++;
                pos++;
                continue;
            }
            if (waiting.length - pos < length) break;
            byte[] packet = Arrays.copyOfRange(waiting, pos, pos + length);
            onFrame(packet);
            pos += length;
        }
        if (pos > 0) waiting = Arrays.copyOfRange(waiting, pos, waiting.length);
    }

    private void onFrame(byte[] pkt) throws IOException {
        rxFrames++;
        String tag = ascii(pkt, 4, 4);
        int peerMagic = get32(pkt, 8);
        if ("CNYS".equals(tag) && pkt.length == 25) {
            int ver = get16(pkt, 16);
            sink.note(String.format(Locale.US,
                "RX SYNC 25B peerMagic=0x%08X version=%d vid=%d pid=%d",
                peerMagic, ver, get16(pkt,20), get16(pkt,22)));
            if (ver < 2) {
                sink.note("Protocolo legado 1.0: modo ativo nao responde.");
                return;
            }
            magic = (int)(System.currentTimeMillis() & 0xffff0000L) | 0x4E55;
            byte[] rsp = frame(28, "CNYS");
            put32(rsp, 8, magic);
            put32(rsp, 12, peerMagic);
            put16(rsp, 16, 2);
            put16(rsp, 18, 0);
            put16(rsp, 20, 1);
            put16(rsp, 22, 1);
            put32(rsp, 24, 0);
            transmit(rsp, "SYNC_REPLY_28");
            synced = true;
            byte[] msg = new byte[] {8,1,3,0,61,0,0,0,0,0};
            byte[] control = frame(30, "_PPA");
            put32(control, 8, magic);
            put32(control, 16, msg.length);
            System.arraycopy(msg, 0, control, 20, msg.length);
            transmit(control, "APP_MSG_SYNC_30");
            sink.note("SYNC recebido e respondido. Aguardando ACK/STAR do dongle.");
        } else if ("_PPA".equals(tag) && pkt.length >= 22) {
            int type = pkt[21] & 0xff;
            sink.note(String.format(Locale.US, "RX APP_ tipo=0x%02X len=%d",type,pkt.length));
            if (type == 0x81) syncAcknowledged = true;
        } else if ("RRIM".equals(tag) && pkt.length >= 32 && "RATS".equals(ascii(pkt,16,4))) {
            int width = get16(pkt,20), height = get16(pkt,22);
            int bitRate = get32(pkt,24), fps = get16(pkt,28);
            startSeen = true;
            sink.note(String.format(Locale.US, "RX STAR w=%d h=%d bitrate=%d fps=%d",width,height,bitRate,fps));
            if (!synced) {
                sink.note("STAR sem SYNC anterior: resposta bloqueada.");
                return;
            }
            // Diagnostic STAR response: values represent advertised capability only.
            // No encoder / MediaProjection is started by this application.
            byte[] rsp = frame(48, "RRIM");
            put32(rsp,8,magic);
            System.arraycopy(new byte[]{'R','A','T','S'},0,rsp,16,4);
            int vw = width == 0 || width == 0xffff ? 1280 : Math.min(width,1920);
            int vh = height == 0 || height == 0xffff ? 720 : Math.min(height,1080);
            put16(rsp,20,vw);
            put16(rsp,22,vh);
            put32(rsp,24,bitRate <= 0 ? 8000000 : Math.min(bitRate,30000000));
            put16(rsp,28,1920);
            put16(rsp,30,1080);
            put32(rsp,32,0);
            put32(rsp,36,0);
            rsp[40] = 0;
            rsp[41] = 2;
            put16(rsp,42,16);
            put32(rsp,44,48000);
            transmit(rsp,"STAR_REPLY_48_DIAGNOSTIC");
            sink.note("STAR confirmado; ainda sem captura de tela, video ou audio.");
        } else {
            sink.note("RX frame tag=" + tag + " len=" + pkt.length);
        }
    }

    private void transmit(byte[] data, String label) throws IOException {
        sink.send(data, label);
        replies++;
    }

    public synchronized String summary() {
        return "Ativo: heartbeats ecoados=" + echoedHeartbeats
             + " frames RX=" + rxFrames + " respostas TX=" + replies
             + " SYNC=" + synced + " ACK=" + syncAcknowledged
             + " STAR=" + startSeen + " bytes sem framing=" + unknownBytes;
    }

    private byte[] frame(int len, String tag) {
        byte[] out = new byte[len];
        put32(out,0,len);
        byte[] chars = tag.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(chars,0,out,4,4);
        return out;
    }

    private static int get16(byte[] a,int p) {
        return (a[p]&255) | ((a[p+1]&255)<<8);
    }
    private static int get32(byte[] a,int p) {
        return (a[p]&255) | ((a[p+1]&255)<<8) | ((a[p+2]&255)<<16) | ((a[p+3]&255)<<24);
    }
    private static void put16(byte[] a,int p,int n) {
        a[p]=(byte)n;a[p+1]=(byte)(n>>>8);
    }
    private static void put32(byte[] a,int p,int n) {
        for(int i=0;i<4;i++) a[p+i]=(byte)(n>>>(8*i));
    }
    private static String ascii(byte[] a,int p,int len) {
        return new String(a,p,len,java.nio.charset.StandardCharsets.US_ASCII);
    }
}
