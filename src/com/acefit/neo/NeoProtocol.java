package com.acefit.neo;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Pure Java codec and constants for ACEFAST ACEFIT NEO (FA003 / Wuqi WQ7033) D0D2C5C2 BLE protocol.
 */
public final class NeoProtocol {
    public static final String TARGET_DEVICE_NAME = "ACEFAST ACEFIT NEO";

    public static final UUID SVC_MAIN = UUID.fromString("00007033-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_WRITE = UUID.fromString("00001001-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_NOTIFY = UUID.fromString("00001002-0000-1000-8000-00805f9b34fb");
    public static final UUID SVC_SEC = UUID.fromString("0131F5DA-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_NOTIFY2 = UUID.fromString("0331f5da-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final String CMD_DETAIL_STATE = "D0D2C5C2001C0003040000C9232306000000080000000000000040401D7326DB";
    public static final String CMD_BATTERY_STATE = "D0D2C5C2001C0003040000C92323060000001800000000000600404029ADE3D9";
    public static final String CMD_GESTURE_STATE = "D0D2C5C2001C0003040000C92323060000001C00000000008F004040E2F45940";
    public static final String CMD_SOUND_STATE = "D0D2C5C2001E0003040000A7232306007497000000000200000013044040396C54EE";

    public static final String CMD_SOUND_ORIGIN = "D0D2C5C2001E0003040000A723230600E4CF0000000002000000691F4040C257ED4C";
    public static final String CMD_SOUND_VOCAL = "D0D2C5C2001E0003040000A723230600279C00000000020000006A1F40405B9EB354";
    public static final String CMD_SOUND_BASS = "D0D2C5C2001E0003040000A72323060066AD00000000020000006B1F404013DB56EA";
    public static final String CMD_GESTURE_RESET = "D0D2C5C2002000030400005923230600340D1D0000000400E30154112367404063142D42";

    public static final int SOUND_ORIGIN = 0;
    public static final int SOUND_VOCAL = 1;
    public static final int SOUND_BASS = 2;

    public static final String[] SOUND_LABELS = new String[] { "原声", "人声", "重低音" };

    public static final String[] GESTURE_SLOT_LABELS = new String[] {
        "左耳单击", "右耳单击",
        "左耳双击", "右耳双击",
        "左耳三击", "右耳三击",
        "左耳长按", "右耳长按"
    };

    public static final String[] ACTION_CODES = new String[] {
        "1", "2", "3", "4", "5", "6", "7", "8"
    };

    public static final String[] ACTION_LABELS = new String[] {
        "播放 / 暂停",
        "上一曲",
        "下一曲",
        "音量 +",
        "音量 -",
        "语音助手",
        "游戏模式",
        "无作用"
    };

    private static final String GESTURE_SET_HDR = "D0D2C5C2002000030400005923230600";
    private static final String GESTURE_SET_MID = "1D00000004000001";
    private static final String GESTURE_SET_TAIL_PRE = "4040";

    private NeoProtocol() {}

    public enum PacketType {
        DETAIL,
        BATTERY,
        GESTURE,
        SOUND,
        INVALID_CRC,
        UNKNOWN
    }

    /** Mutable snapshot of earbud connection, battery, sound preset, and 8 touch gestures. */
    public static final class NeoState {
        public boolean detailLoaded;
        public boolean batteryLoaded;
        public boolean gestureLoaded;
        public boolean soundLoaded;

        public boolean leftIsMain = true;
        public boolean leftConnected;
        public boolean rightConnected;
        public int leftBattery = -1;
        public int rightBattery = -1;
        public boolean leftCharging;
        public boolean rightCharging;
        public int soundMode = -1;
        public final String[] gestures = new String[] { "5", "4", "1", "1", "2", "3", "6", "7" };

        public String gestureHex() {
            StringBuilder sb = new StringBuilder(8);
            for (String g : gestures) {
                sb.append(g != null && g.length() == 1 ? g : "8");
            }
            return sb.toString();
        }
    }

    /** Computes custom CRC-8 (poly=0x31, init=0xFF, refin/refout=true, xorout=0xFF). */
    public static int crc8(byte[] data, int offset, int length) {
        int crc = 0xFF;
        for (int i = 0; i < length; i++) {
            crc ^= (data[offset + i] & 0xFF);
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? ((crc >>> 1) ^ 0x8C) : (crc >>> 1);
            }
        }
        return (crc ^ 0xFF) & 0xFF;
    }

    /** Computes standard IEEE CRC-32 over a byte range. */
    public static long crc32(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return crc.getValue() & 0xFFFFFFFFL;
    }

    /** Builds a 36-byte D0D2C5C2 gesture update packet from 8 nibble characters ('1'..'8'). */
    public static byte[] buildGestureSetCommand(String eightNibbles) {
        if (eightNibbles == null || eightNibbles.length() != 8) {
            throw new IllegalArgumentException("Gesture string must be 8 nibbles");
        }
        for (int i = 0; i < 8; i++) {
            char c = eightNibbles.charAt(i);
            if (c < '1' || c > '8') {
                throw new IllegalArgumentException("Invalid gesture nibble: " + c);
            }
        }
        byte[] payload = fromHex(eightNibbles);
        long innerCrc16 = crc32(payload, 0, payload.length) & 0xFFFFL;
        String innerLe = String.format(Locale.US, "%02X%02X", (int) (innerCrc16 & 0xFF), (int) ((innerCrc16 >>> 8) & 0xFF));

        String bodyHex = GESTURE_SET_HDR + innerLe + GESTURE_SET_MID + eightNibbles.toUpperCase(Locale.US) + GESTURE_SET_TAIL_PRE;
        byte[] body = fromHex(bodyHex);
        // Verify dynamic CRC-8 matches byte[11]
        body[11] = (byte) crc8(body, 4, 7);

        long outerCrc = crc32(body, 4, body.length - 4);
        byte[] full = Arrays.copyOf(body, body.length + 4);
        full[body.length] = (byte) (outerCrc & 0xFF);
        full[body.length + 1] = (byte) ((outerCrc >>> 8) & 0xFF);
        full[body.length + 2] = (byte) ((outerCrc >>> 16) & 0xFF);
        full[body.length + 3] = (byte) ((outerCrc >>> 24) & 0xFF);
        return full;
    }

    /** Returns the command bytes for the requested EQ preset index (0=Origin, 1=Vocal, 2=Bass). */
    public static byte[] buildSoundCommand(int mode) {
        if (mode == SOUND_ORIGIN) return fromHex(CMD_SOUND_ORIGIN);
        if (mode == SOUND_VOCAL) return fromHex(CMD_SOUND_VOCAL);
        if (mode == SOUND_BASS) return fromHex(CMD_SOUND_BASS);
        throw new IllegalArgumentException("Unknown sound mode: " + mode);
    }

    /** Verifies framing header, CRC-8, and trailing CRC-32 of a received D0D2C5C2 packet. */
    public static boolean verifyRxFrame(byte[] raw) {
        if (raw == null || raw.length < 30) return false;
        if ((raw[0] & 0xFF) != 0xD0 || (raw[1] & 0xFF) != 0xD2 || (raw[2] & 0xFF) != 0xC5 || (raw[3] & 0xFF) != 0xC2) {
            return false;
        }
        int expectedLen = raw[5] & 0xFF;
        if (expectedLen != raw.length - 4) return false;
        if (crc8(raw, 4, 7) != (raw[11] & 0xFF)) return false;
        long calcCrc32 = crc32(raw, 4, raw.length - 8);
        long pktCrc32 = (raw[raw.length - 4] & 0xFFL)
                | ((raw[raw.length - 3] & 0xFFL) << 8)
                | ((raw[raw.length - 2] & 0xFFL) << 16)
                | ((raw[raw.length - 1] & 0xFFL) << 24);
        return calcCrc32 == pktCrc32;
    }

    /** Decodes a notification packet into NeoState following ObserverManager.actionNeo rules. */
    public static PacketType applyNotification(byte[] raw, NeoState state) {
        if (!verifyRxFrame(raw)) {
            return PacketType.INVALID_CRC;
        }
        String hex = toHex(raw);
        if (hex.length() == 70 && hex.substring(36, 40).equalsIgnoreCase("0800")) {
            String isMaster = hex.substring(52, 54);
            String isLeft = hex.substring(54, 56);
            String isConnect = hex.substring(56, 58);
            state.leftIsMain = isLeft.equalsIgnoreCase(isMaster);
            if ("01".equalsIgnoreCase(isConnect)) {
                state.leftConnected = true;
                state.rightConnected = true;
            } else if ("01".equalsIgnoreCase(isLeft)) {
                state.leftConnected = true;
                state.rightConnected = false;
            } else {
                state.leftConnected = false;
                state.rightConnected = true;
            }
            state.detailLoaded = true;
            return PacketType.DETAIL;
        }
        if (hex.length() == 72 && hex.substring(36, 44).equalsIgnoreCase("18000200")) {
            int masterBat = Integer.parseInt(hex.substring(52, 54), 16);
            boolean masterChg = "01".equalsIgnoreCase(hex.substring(54, 56));
            int subBat = Integer.parseInt(hex.substring(56, 58), 16);
            boolean subChg = "01".equalsIgnoreCase(hex.substring(58, 60));
            if (state.leftIsMain) {
                state.leftBattery = masterBat;
                state.leftCharging = masterChg;
                state.rightBattery = subBat;
                state.rightCharging = subChg;
            } else {
                state.leftBattery = subBat;
                state.leftCharging = subChg;
                state.rightBattery = masterBat;
                state.rightCharging = masterChg;
            }
            state.batteryLoaded = true;
            return PacketType.BATTERY;
        }
        if (hex.length() == 72 && (hex.substring(36, 40).equalsIgnoreCase("1C00") || hex.substring(36, 40).equalsIgnoreCase("1D00"))) {
            String g = hex.substring(52, 60);
            if ("00000000".equals(g)) {
                g = "54112367";
            }
            for (int i = 0; i < 8; i++) {
                state.gestures[i] = g.substring(i, i + 1);
            }
            state.gestureLoaded = true;
            return PacketType.GESTURE;
        }
        if (hex.length() == 66) {
            String tag = hex.substring(32, 36);
            String idx = hex.substring(52, 54);
            if ("02D2".equalsIgnoreCase(tag) && "00".equalsIgnoreCase(idx)) {
                state.soundMode = SOUND_ORIGIN;
                state.soundLoaded = true;
                return PacketType.SOUND;
            }
            if ("05A5".equalsIgnoreCase(tag) && "01".equalsIgnoreCase(idx)) {
                state.soundMode = SOUND_VOCAL;
                state.soundLoaded = true;
                return PacketType.SOUND;
            }
            if ("0C3C".equalsIgnoreCase(tag) && "02".equalsIgnoreCase(idx)) {
                state.soundMode = SOUND_BASS;
                state.soundLoaded = true;
                return PacketType.SOUND;
            }
        }
        return PacketType.UNKNOWN;
    }

    /** Maps a gesture code ("1".."8") to its Chinese display name. */
    public static String actionLabelForCode(String code) {
        for (int i = 0; i < ACTION_CODES.length; i++) {
            if (ACTION_CODES[i].equals(code)) {
                return ACTION_LABELS[i];
            }
        }
        return "未知 (" + code + ")";
    }

    public static String toHex(byte[] bytes) {
        if (bytes == null) return "";
        char[] hexChars = "0123456789ABCDEF".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = hexChars[v >>> 4];
            out[i * 2 + 1] = hexChars[v & 0x0F];
        }
        return new String(out);
    }

    public static byte[] fromHex(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
