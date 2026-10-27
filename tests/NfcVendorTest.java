package dev.inknfclab;

public final class NfcVendorTest {
    public static void main(String[] args) {
        expect(NfcVendor.Kind.THN31, "/system/app/NfcNci/base.apk", "persist.sys.nfc.chip_name=THN31");
        expect(NfcVendor.Kind.THN31, "", "other=value persist.sys.nfc.chip_name=THN31 ");
        expect(NfcVendor.Kind.UNKNOWN, "", "libnfc-tms.conf exists");
        expect(NfcVendor.Kind.UNKNOWN, "", "persist.sys.nfc.chip_name=THN310");
        expect(NfcVendor.Kind.ST, "/system/app/Nfc_st/Nfc_st.apk", "ro.vendor.nfc.chip=PN557");
        expect(NfcVendor.Kind.NXP, "/system/app/Nfc_nxp/Nfc_nxp.apk", "ro.strongbox.model=ST54L");
        expect(NfcVendor.Kind.ST, "/system/app/NfcNci/base.apk", "ro.strongbox.model=ST54L");
        expect(NfcVendor.Kind.NXP, "/system/app/NfcNci/base.apk", "ro.vendor.nfc.chip=SN220");
        expect(NfcVendor.Kind.UNKNOWN, "/system/app/NfcNci/base.apk", "");
        expect(NfcVendor.Kind.UNKNOWN, "", "ST54L and PN557");
        expect(NfcVendor.Kind.UNKNOWN, "", "libnfc-nxp.conf exists");
        System.out.println("NFC vendor evidence tests passed");
    }

    private static void expect(NfcVendor.Kind expected, String path, String properties) {
        if (NfcVendor.identify(path, properties) != expected) throw new AssertionError(path + " / " + properties);
    }
}
