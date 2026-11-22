package com.nura.ld36diagnostic;

public final class ProtocolInspectorSmokeTest {
    private static void check(boolean okay, String message) {
        if (!okay) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        byte[] heartbeat = new byte[]{0, 0, 0, 0};
        check(ProtocolInspector.inspect(heartbeat, heartbeat.length, 4).contains("heartbeat"), "heartbeat");
        byte[] sync = new byte[25];
        sync[0] = 25;
        sync[4] = 'C'; sync[5] = 'N'; sync[6] = 'Y'; sync[7] = 'S';
        String info = ProtocolInspector.inspect(sync, sync.length, 25);
        check(info.contains("len LE=25"), "length endian");
        check(info.contains("invertida=SYNC"), "reverse tag");
        check(ProtocolInspector.toHex(new byte[]{0, 1, (byte) 255}, 0, 3).equals("00 01 FF"), "hex");
        check(ProtocolInspector.inspect(new byte[]{1, 2}, 2, 2).contains("HEX=01 02"), "partial read");
        System.out.println("OK: 5 verificacoes ProtocolInspector");
    }
}
