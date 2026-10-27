package dev.inknfclab.thn31;

import static dev.inknfclab.thn31.TestSupport.*;

public final class Thn31ProfileTest {
    public static void main(String[] args) {
        // The gate accepts only the chip property; model and SDK are not inputs.
        assertTrue(Thn31Profile.supported("THN31"));
        assertFalse(Thn31Profile.supported("ST54L"));
        assertFalse(Thn31Profile.supported("THN310"));
        assertFalse(Thn31Profile.supported(null));
        System.out.println("Thn31ProfileTest: 4 passed");
    }
}
