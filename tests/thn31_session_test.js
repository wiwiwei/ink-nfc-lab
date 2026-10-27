'use strict';
const assert = require('assert');
const fs = require('fs');
const vm = require('vm');
const source = fs.readFileSync('assets/thn31-session-isolation.js', 'utf8');
const prefix = '/data/user_de/0/com.android.nfc/files/epdprobe-owned-';
function harness(validFingerprint = true) {
    const files = new Map([[prefix + 'session.json', JSON.stringify({token: 'test-a', deadline: Date.now() + 60000})]]);
    let handler, timer, forwarded = 0;
    const target = {
        equals() { return true; },
        readByteArray() { return Buffer.from(validFingerprint ? '3f2303d55e8600f8ff8301d1fd7b02a9' : '00'.repeat(16), 'hex'); }
    };
    function File(path) {
        this.write = value => files.set(path, (files.get(path) || '') + value);
        this.flush = () => {};
        this.close = () => {};
    }
    File.readAllText = path => { if (!files.has(path)) throw Error('missing'); return files.get(path); };
    File.writeAllText = (path, value) => files.set(path, value);
    const context = {
        File, Process: {id: 123, getModuleByName: () => ({base: {add: () => target}, getExportByName: () => target})},
        NativeCallback: function (callback) { return callback; },
        NativeFunction: function () { return () => { forwarded++; return 99; }; },
        Interceptor: {replaceFast: (_, callback) => { handler = callback; return {}; }},
        send() {}, setInterval: callback => { timer = callback; }, Date, Uint8Array
    };
    vm.runInNewContext(source, context);
    return {
        files, tick: () => timer(), forwarded: () => forwarded,
        invoke(hex) {
            const raw = Buffer.from(hex, 'hex'), buffer = Buffer.alloc(8 + raw.length);
            buffer.writeUInt16LE(raw.length, 2); raw.copy(buffer, 8);
            function pointer(offset) { return {
                add: count => pointer(offset + count), readU16: () => buffer.readUInt16LE(offset),
                readByteArray: count => buffer.subarray(offset, offset + count)
            }; }
            return handler(pointer(0));
        }
    };
}
assert.throws(() => harness(false), /Unverified/);
const h = harness();
assert.strictEqual(h.invoke('0000029000'), 99); // no activation, ordinary data passes through
assert.strictEqual(h.invoke('61030f0204710a0400040102030401200002'), 1);
assert.strictEqual(h.invoke('61030f0380710a0400040102030401080000'), 1);
assert.strictEqual(h.invoke('61052002020471ff010a040004010203040120007100000b0a7833a002900102030403'), 1);
assert.strictEqual(h.invoke('600603010001'), 1);
assert.strictEqual(h.invoke('0000029000'), 1);
assert.strictEqual(h.invoke('600603010101'), 99); // another connection's credits
assert.strictEqual(h.invoke('0000039000'), 99); // malformed length
assert.strictEqual(h.files.get(prefix + 'data-frames.txt'), '600603010001\n0000029000\n');
h.files.delete(prefix + 'session.json'); h.tick();
assert.strictEqual(h.invoke('0000029000'), 99);
assert.strictEqual(JSON.parse(h.files.get(prefix + 'status.json')).state, 'idle');
// A new token cannot inherit the old activation.
h.files.set(prefix + 'session.json', JSON.stringify({token: 'test-b', deadline: Date.now() + 60000})); h.tick();
assert.strictEqual(h.invoke('0000029000'), 99);
h.files.set(prefix + 'session.json', JSON.stringify({token: 'test-b', deadline: 0})); h.tick();
assert.strictEqual(JSON.parse(h.files.get(prefix + 'status.json')).state, 'idle');
assert.strictEqual(h.invoke('0000029000'), 99);
console.log('THN31 hook: fingerprint, ownership, capture, expiry and lease-reset tests passed');
