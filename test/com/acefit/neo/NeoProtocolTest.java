package com.acefit.neo;

import java.util.Arrays;

/**
 * Host-side unit tests for NeoProtocol framing, CRCs, command builders, and RX decoders.
 */
public final class NeoProtocolTest {
    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("FAILED: " + msg);
        }
    }

    public static void main(String[] args) {
        // 1. Verify 4 live RX packets captured from an ACEFAST ACEFIT NEO unit
        NeoProtocol.NeoState st = new NeoProtocol.NeoState();
        byte[] rxDetail = NeoProtocol.fromHex("D0D2C5C2011F0003040000AD232306009F90080002000300000001010140403F7B83DB");
        byte[] rxBattery = NeoProtocol.fromHex("D0D2C5C201200003040100A023230600CBC518000200040006005F004F0040401E1BC930");
        byte[] rxGesture = NeoProtocol.fromHex("D0D2C5C201200003040200F5232306002FDF1C00020004008F005423116740407F25DE39");
        byte[] rxSound = NeoProtocol.fromHex("D0D2C5C2011D0003040300962323060005A50000020001000000014040DF813590");

        check(NeoProtocol.applyNotification(rxDetail, st) == NeoProtocol.PacketType.DETAIL, "DETAIL type");
        check(st.detailLoaded && st.leftIsMain && st.leftConnected && st.rightConnected, "DETAIL decoded");

        check(NeoProtocol.applyNotification(rxBattery, st) == NeoProtocol.PacketType.BATTERY, "BATTERY type");
        check(st.batteryLoaded && st.leftBattery == 95 && st.rightBattery == 79 && !st.leftCharging && !st.rightCharging, "BATTERY decoded");

        check(NeoProtocol.applyNotification(rxGesture, st) == NeoProtocol.PacketType.GESTURE, "GESTURE type");
        check(st.gestureLoaded && "54231167".equals(st.gestureHex()), "GESTURE decoded");

        check(NeoProtocol.applyNotification(rxSound, st) == NeoProtocol.PacketType.SOUND, "SOUND type");
        check(st.soundLoaded && st.soundMode == NeoProtocol.SOUND_VOCAL, "SOUND decoded");

        // 2. Verify corrupted CRC rejection
        byte[] bad = Arrays.copyOf(rxBattery, rxBattery.length);
        bad[bad.length - 1] ^= 0x01;
        check(NeoProtocol.applyNotification(bad, st) == NeoProtocol.PacketType.INVALID_CRC, "Bad CRC32 rejected");

        byte[] bad8 = Arrays.copyOf(rxBattery, rxBattery.length);
        bad8[11] ^= 0x01;
        check(NeoProtocol.applyNotification(bad8, st) == NeoProtocol.PacketType.INVALID_CRC, "Bad CRC8 rejected");

        // 3. Verify right-ear-main battery mapping
        NeoProtocol.NeoState rightMain = new NeoProtocol.NeoState();
        rightMain.leftIsMain = false;
        check(NeoProtocol.applyNotification(rxBattery, rightMain) == NeoProtocol.PacketType.BATTERY, "RightMain battery");
        check(rightMain.rightBattery == 95 && rightMain.leftBattery == 79, "RightMain L/R swap");

        // 4. Verify dynamic gesture command builder framing & CRCs
        byte[] gCmd = NeoProtocol.buildGestureSetCommand("54231167");
        check(gCmd.length == 36, "Gesture command length == 36");
        check(NeoProtocol.verifyRxFrame(gCmd), "Gesture command self-passes frame/CRC8/CRC32");
        String gHex = NeoProtocol.toHex(gCmd);
        check(gHex.startsWith("D0D2C5C2002000030400005923230600"), "Gesture prefix matches AppConfig");
        check(gHex.substring(36, 60).equals("1D0000000400000154231167"), "Gesture body matches PREFIX_3 + nibbles");

        // Compare inner CRC16 for GESTURE_RESET's 4-byte payload "54112367" -> "340D"
        byte[] resetCheck = NeoProtocol.buildGestureSetCommand("54112367");
        check("340D".equals(NeoProtocol.toHex(resetCheck).substring(32, 36)), "Inner CRC16 matches AppConfig.NEO_CMD_GESTURE_RESET (340D)");

        // 5. Verify all static TX commands pass frame verification
        String[] staticCmds = new String[] {
            NeoProtocol.CMD_DETAIL_STATE, NeoProtocol.CMD_BATTERY_STATE,
            NeoProtocol.CMD_GESTURE_STATE, NeoProtocol.CMD_SOUND_STATE,
            NeoProtocol.CMD_SOUND_ORIGIN, NeoProtocol.CMD_SOUND_VOCAL,
            NeoProtocol.CMD_SOUND_BASS, NeoProtocol.CMD_GESTURE_RESET
        };
        for (String c : staticCmds) {
            check(NeoProtocol.verifyRxFrame(NeoProtocol.fromHex(c)), "Static TX valid: " + c);
        }

        // 6. Verify 00000000 gesture fallback
        byte[] zeroGesture = NeoProtocol.fromHex("D0D2C5C201200003040200F5232306002FDF1C00020004008F0000000000404000000000");
        // Fix outer CRC32 for synthetic test
        long oc = NeoProtocol.crc32(zeroGesture, 4, zeroGesture.length - 8);
        zeroGesture[zeroGesture.length - 4] = (byte) (oc & 0xFF);
        zeroGesture[zeroGesture.length - 3] = (byte) ((oc >>> 8) & 0xFF);
        zeroGesture[zeroGesture.length - 2] = (byte) ((oc >>> 16) & 0xFF);
        zeroGesture[zeroGesture.length - 1] = (byte) ((oc >>> 24) & 0xFF);
        NeoProtocol.NeoState zeroState = new NeoProtocol.NeoState();
        check(NeoProtocol.applyNotification(zeroGesture, zeroState) == NeoProtocol.PacketType.GESTURE, "Zero gesture packet");
        check("54112367".equals(zeroState.gestureHex()), "Zero gesture default fallback");

        System.out.println("ALL NEO PROTOCOL TESTS PASSED (6/6 groups)");
    }
}
