package dev.inknfclab.thn31;

import dev.inknfclab.Protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline evidence classification. Unknown vendor notices never count as tag replies. */
public final class PollEvidence {
    private static final Pattern LINE = Pattern.compile("TmsNci([XR])\\s*: len =\\s*(\\d+) > ([0-9A-F]+)\\s*$");
    public static final class Frame {
        public final boolean tx;
        public final String hex;
        Frame(boolean tx, String hex) { this.tx = tx; this.hex = hex; }
    }
    public static final class Result {
        public String commandStatus, stopStatus, selectStatus, selectRequest;
        public int discovered, activated, malformed, errors, unknownNotices;
        public boolean selectedIsoDepActivated;
        public final List<String> details = new ArrayList<>();
        public final List<Frame> window = new ArrayList<>();
        public boolean accepted() { return "00".equals(commandStatus); }
        public boolean stopped() { return "00".equals(stopStatus); }
    }
    public static String request(String profile) {
        switch (profile) {
            case "a-poll-screen": return "210303010001";
            case "q-poll-screen": case "q-poll-probe": case "q-select-screen": case "q-d1-screen": case "q-write-screen": case "q-refresh-screen": return "210303017101";
            case "mixed-poll-screen": return "2103050200017101";
            default: throw new IllegalArgumentException("Unknown polling profile");
        }
    }
    static byte[] bytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
    private static int u(byte[] b, int i) { return b[i] & 255; }
    static Frame frame(String line) {
        Matcher m = LINE.matcher(line);
        if (!m.find()) return null;
        String raw = m.group(3);
        if (raw.length() != Integer.parseInt(m.group(2)) * 2 || (raw.length() & 1) != 0)
            throw new IllegalArgumentException("HAL log packet length mismatch");
        byte[] b = bytes(raw);
        if (b.length < 3 || b.length != 3 + u(b, 2))
            throw new IllegalArgumentException("NCI envelope length mismatch");
        return new Frame(m.group(1).equals("X"), raw);
    }
    private static String status(List<Frame> frames, int start, String prefix) {
        for (int i = start + 1; i < frames.size(); i++) {
            Frame f = frames.get(i);
            if (f.tx) return null;
            if (f.hex.startsWith(prefix)) return f.hex.length() == 8 && f.hex.substring(4, 6).equals("01")
                    ? f.hex.substring(6) : null;
        }
        return null;
    }
    public static Result parse(String log, String profile) {
        String request = request(profile);
        List<Frame> frames = new ArrayList<>();
        for (String line : log.split("\\r?\\n")) {
            Frame f = frame(line);
            if (f != null) frames.add(f);
        }
        Result r = new Result();
        int start = -1, stop = -1, selectIndex = -1;
        for (int i = 0; i < frames.size(); i++)
            if (frames.get(i).tx && frames.get(i).hex.equals(request)) start = i;
        if (start < 0) return r;
        r.commandStatus = status(frames, start, "4103");
        for (int i = start + 1; i < frames.size(); i++) {
            Frame f = frames.get(i);
            if (f.tx && f.hex.startsWith("2104")) {
                selectIndex = i;
                r.selectRequest = f.hex;
                r.selectStatus = status(frames, i, "4104");
            }
            if (f.tx && f.hex.equals("21060100")) { stop = i; break; }
        }
        if (stop < 0) return r; // an unbounded window cannot establish which run observed a tag
        r.stopStatus = status(frames, stop, "4106");
        for (int i = start; i <= stop; i++) {
            Frame f = frames.get(i);
            // Never persist unrelated APDU/routing payloads from the system log.
            if (f.hex.startsWith("2103") || f.hex.startsWith("4103") || f.hex.startsWith("6103")
                    || f.hex.startsWith("6105") || f.hex.startsWith("2104") || f.hex.startsWith("4104")
                    || f.hex.startsWith("2106") || f.hex.startsWith("6007")
                    || f.hex.startsWith("6008") || (f.hex.startsWith("6F") && r.unknownNotices < 16)) {
                if (r.window.size() >= 128) throw new IllegalArgumentException("Evidence frame budget exceeded");
                r.window.add(f);
            }
            if (f.tx) continue;
            byte[] b = bytes(f.hex);
            if (f.hex.startsWith("6103")) {
                int n = b.length - 3;
                if (n < 5 || u(b, 3) == 0 || n != 5 + u(b, 6) || u(b, b.length - 1) > 2) {
                    r.malformed++; continue;
                }
                r.discovered++;
                r.details.add("发现通知：技术模式=" + u(b, 5) + "，协议=" + u(b, 4));
            } else if (f.hex.startsWith("6105")) {
                int n = b.length - 3;
                if (n < 11 || u(b, 3) == 0) { r.malformed++; continue; }
                int techLen = u(b, 9), actLenIndex = 13 + techLen;
                if (actLenIndex >= b.length || n != 11 + techLen + u(b, actLenIndex)) {
                    r.malformed++; continue;
                }
                r.activated++;
                if (i > selectIndex && selectIndex >= 0 && "00".equals(r.selectStatus)
                        && r.selectRequest.matches("210403[0-9A-F]{2}0402")
                        && u(b, 3) == Integer.parseInt(r.selectRequest.substring(6, 8), 16)
                        && u(b, 4) == 2 && u(b, 5) == 4) r.selectedIsoDepActivated = true;
                r.details.add("激活通知：技术模式=" + u(b, 6) + "，协议=" + u(b, 5));
                if (u(b, 6) == 0 && techLen >= 4) {
                    int uidLen = u(b, 12), selIndex = 13 + uidLen;
                    if ((uidLen == 0 || uidLen == 4 || uidLen == 7 || uidLen == 10)
                            && selIndex < 10 + techLen && u(b, selIndex) <= 1
                            && techLen == 4 + uidLen + u(b, selIndex))
                        r.details.add("ATQA=" + f.hex.substring(20, 24));
                }
            } else if (f.hex.startsWith("6007") || f.hex.startsWith("6008")) r.errors++;
            else if (f.hex.startsWith("6F")) r.unknownNotices++;
        }
        return r;
    }
}
