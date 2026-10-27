package dev.inknfclab.thn31;

import dev.inknfclab.Protocol;

import android.os.IBinder;
import android.os.Parcel;

/** Existing HAL write only: never opens, closes or replaces the system NFC callback. */
final class NfcHalWriter {
    private static final String DESCRIPTOR = "android.hardware.nfc.INfc";
    // AOSP frozen INfc v1: write is the eighth method. Hash matched on the target phone.
    private static final String HASH = "37acf94ceb095ad537b66c3bd77c30a6ad5f5b0e";
    private final IBinder binder;

    NfcHalWriter() throws Exception {
        Class<?> manager = Class.forName("android.os.ServiceManager");
        binder = (IBinder) manager.getMethod("checkService", String.class)
                .invoke(null, "android.hardware.nfc.INfc/default");
        if (binder == null || !DESCRIPTOR.equals(binder.getInterfaceDescriptor()))
            throw new IllegalStateException("Verified NFC HAL not available");
        Parcel request = Parcel.obtain(), response = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            if (!binder.transact(16777215, request, response, 0)) throw new IllegalStateException("HAL version unavailable");
            response.readException();
            if (response.readInt() != 1) throw new IllegalStateException("Unverified NFC HAL version");
        } finally { request.recycle(); response.recycle(); }
        request = Parcel.obtain(); response = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            if (!binder.transact(16777214, request, response, 0)) throw new IllegalStateException("HAL hash unavailable");
            response.readException();
            if (!HASH.equals(response.readString())) throw new IllegalStateException("Unverified NFC HAL interface hash");
        } finally { request.recycle(); response.recycle(); }
    }

    void writeData(byte[] packet) throws Exception {
        if (packet.length < 4 || packet.length > 258 || (packet[0] != 0 && packet[0] != 0x10)
                || packet[1] != 0 || (packet[2] & 255) != packet.length - 3)
            throw new IllegalArgumentException("Only RF connection 0 NCI DATA is permitted");
        Parcel request = Parcel.obtain(), response = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR); request.writeByteArray(packet);
            if (!binder.transact(8, request, response, 0)) throw new IllegalStateException("HAL write unavailable");
            response.readException();
            if (response.readInt() != packet.length) throw new IllegalStateException("HAL incomplete NCI write; do not replay");
        } finally { request.recycle(); response.recycle(); }
    }
}
