package dev.inknfclab.thn31;

import dev.inknfclab.Protocol;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

final class ProbeIo {
    static byte[] read(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); copy(input, output, limit); return output.toByteArray();
    }
    static void copy(InputStream input, OutputStream output, int limit) throws Exception {
        byte[] buffer = new byte[65536]; int count, total = 0;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new IllegalStateException("Input exceeds bounded size");
            output.write(buffer, 0, count);
        }
    }
}
