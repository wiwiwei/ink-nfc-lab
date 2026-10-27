package dev.inknfclab.thn31;

/** The session hook also checks the live JNI address and machine-code prefix. */
public final class Thn31Profile {
    public static boolean supported(String chip) {
        return "THN31".equals(chip);
    }
    private Thn31Profile() {}
}
