'use strict';
// PLQ110/THN31 owned-session bridge. No firmware or on-air packet modification.
const leasePath = '/data/user_de/0/com.android.nfc/files/epdprobe-owned-session.json';
const statusPath = '/data/user_de/0/com.android.nfc/files/epdprobe-owned-status.json';
const framesPath = '/data/user_de/0/com.android.nfc/files/epdprobe-owned-data-frames.txt';
function readLease() {
    try {
        const lease = JSON.parse(File.readAllText(leasePath));
        return typeof lease.token === 'string' && Date.now() < lease.deadline ? lease : null;
    } catch (_) { return null; }
}
if (!readLease()) throw new Error('No live owner lease');
const library = Process.getModuleByName('libnfc_nci_jni.so');
const target = library.getExportByName('_Z22nfc_ncif_process_eventP7NFC_HDR');
const hex = p => Array.from(new Uint8Array(p.readByteArray(16)), v => v.toString(16).padStart(2, '0')).join('');
if (!target.equals(library.base.add(0x1c8004)) || hex(target) !== '3f2303d55e8600f8ff8301d1fd7b02a9')
    throw new Error('Unverified NFC JNI function');
let targetId = -1, uid = '', active = false, count = 0, captured = 0;
let token = null, frames = null;
function status(state) {
    File.writeAllText(statusPath, JSON.stringify({token, state, consumed: count, captured,
        protocol_version: 2, nfc_pid: Process.id, resident_passthrough: state === 'idle'}));
}
function leased() {
    const lease = readLease();
    if (!lease) {
        if (frames !== null) {
            active = false;
            frames.close(); frames = null;
            status('idle');
        }
        return false;
    }
    if (token !== lease.token || frames === null) {
        if (frames !== null) frames.close();
        token = lease.token;
        targetId = -1; uid = ''; active = false; count = 0; captured = 0;
        frames = new File(framesPath, 'a');
        status('ready');
    }
    return true;
}
// A dedicated, bounded receive stream. The owner process reads this file rather
// than relying on rate-limited logcat to deliver every RF credit and APDU reply.
function capture(message) {
    const length = message.add(2).readU16(), offset = message.add(4).readU16();
    if (length < 3 || length > 258 || offset > 256) return;
    const bytes = new Uint8Array(message.add(8 + offset).readByteArray(length));
    if (bytes.length !== bytes[2] + 3) return;
    const isData = (bytes[0] === 0 || bytes[0] === 0x10) && bytes[1] === 0;
    const isCredits = bytes[0] === 0x60 && bytes[1] === 6;
    const isDeactivation = bytes[0] === 0x61 && bytes[1] === 6;
    if (!isData && !isCredits && !isDeactivation) return;
    if (captured >= 4096) throw new Error('Owned NFC frame capture budget exceeded');
    frames.write(Array.from(bytes, x => x.toString(16).padStart(2, '0')).join('') + '\n');
    frames.flush();
    captured++;
}
function owned(message) {
    const length = message.add(2).readU16(), offset = message.add(4).readU16();
    if (length < 3 || length > 258 || offset > 256) return false;
    const b = new Uint8Array(message.add(8 + offset).readByteArray(length));
    if (length !== b[2] + 3) return false;
    if (b[0] === 0x61 && ((b[1] === 3 && b[5] !== 0x71) || (b[1] === 5 && b[6] !== 0x71))) active = false;
    if (b[0] === 0x61 && b[1] === 3 && length >= 15 && b[5] === 0x71 &&
            length === 8 + b[6] && b[9] === 4 && (b[4] === 4 || b[4] === 0x80)) {
        const id = Array.from(b.slice(10, 14)).join(',');
        if (b[4] === 4 && targetId < 0) { targetId = b[3]; uid = id; }
        return uid === id;
    }
    if (b[0] === 0x61 && b[1] === 5 && length >= 23 && b[3] === targetId &&
            b[4] === 2 && b[5] === 4 && b[6] === 0x71 && b[12] === 4 &&
            Array.from(b.slice(13, 17)).join(',') === uid) {
        active = true;
        return true;
    }
    if (!active) return false;
    if ((b[0] === 0 || b[0] === 0x10) && b[1] === 0) return true;
    if (b[0] === 0x60 && b[1] === 6 && length >= 4 && length === 4 + 2 * b[3]) {
        for (let i = 4; i < length; i += 2) if (b[i] !== 0) return false;
        return true;
    }
    if (b[0] === 0x61 && b[1] === 6) { active = false; return true; }
    return false;
}
let original;
const replacement = new NativeCallback(message => {
    try {
        if (leased() && owned(message)) {
            capture(message);
            count++;
            return 1;
        }
    } catch (_) { /* Fail open; the app will time out rather than retry data. */ }
    return original(message);
}, 'uint', ['pointer']);
original = new NativeFunction(Interceptor.replaceFast(target, replacement), 'uint', ['pointer']);
// One stable trampoline per NFC process. Never revert while another NFC thread
// may still be executing its callback. A missing/expired lease always fails open.
leased();
send('EPD_NFC_ISOLATION_READY');
setInterval(leased, 100);
