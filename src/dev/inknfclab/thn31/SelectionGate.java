package dev.inknfclab.thn31;

import dev.inknfclab.Protocol;

import java.util.HashSet;
import java.util.Set;

/** One live discovery cycle only. No cached IDs, application commands or vendor RF interfaces. */
final class SelectionGate {
    private boolean started, accepted, complete;
    private String blocked;
    private int packets;
    private final Set<Integer> isoDepIds = new HashSet<>();

    void accept(PollEvidence.Frame frame) {
        if (blocked != null) return;
        if (frame.tx && frame.hex.equals(PollEvidence.request("q-select-screen"))) {
            if (started) { blocked = "检测到重复轮询请求，未选择"; return; }
            started = true;
            return;
        }
        if (!started) return;
        if (++packets > 128) { blocked = "实时通知超过采集上限，未选择"; return; }
        if (frame.tx) { blocked = "检测到其他发送命令，未选择"; return; }
        if (frame.hex.startsWith("4103")) {
            accepted = frame.hex.equals("41030100");
            if (!accepted) blocked = "轮询未被确认接受，未选择";
            return;
        }
        if (frame.hex.startsWith("6105") || frame.hex.startsWith("6106")
                || frame.hex.startsWith("6007") || frame.hex.startsWith("6008")) {
            blocked = "控制器已激活、退场或报告错误，未追加选择";
            return;
        }
        if (!frame.hex.startsWith("6103")) return;
        byte[] b = new byte[frame.hex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(frame.hex.substring(i * 2, i * 2 + 2), 16);
        if (!accepted || b.length < 8 || (b[3] & 255) == 0
                || b.length != 8 + (b[6] & 255) || (b[b.length - 1] & 255) > 2 || complete) {
            blocked = "发现通知次序或长度异常，未选择";
            return;
        }
        if ((b[4] & 255) == 4 && (b[5] & 255) == 0x71) isoDepIds.add(b[3] & 255);
        if (b[b.length - 1] == 0) complete = true;
    }

    boolean ready() { return blocked == null && accepted && complete && isoDepIds.size() == 1; }
    boolean finished() { return blocked != null || complete; }
    byte[] selection() {
        if (!ready()) throw new IllegalStateException(reason());
        return new byte[]{(byte) (int) isoDepIds.iterator().next(), 4, 2};
    }
    String reason() {
        if (blocked != null) return blocked;
        if (!started) return "未实时捕获本轮轮询请求，未选择";
        if (!accepted) return "未实时捕获轮询接受响应，未选择";
        if (!complete) return "未实时收到完整发现列表，未选择";
        if (isoDepIds.isEmpty()) return "本轮没有可选的 ISO-DEP 目标";
        if (isoDepIds.size() != 1) return "本轮出现多个 ISO-DEP 目标，未选择";
        return "已实时收到唯一 ISO-DEP 目标";
    }
}
