package dev.inknfclab.thn31;

import dev.inknfclab.Protocol;

import static dev.inknfclab.thn31.TestSupport.*;


public class SelectionGateTest {
    private static final String ISO = "61030F0204710A0400040102030401200002";
    private static final String MFC = "61030F0380710A0400040102030401080000";
    private static PollEvidence.Frame frame(boolean tx, String hex) { return new PollEvidence.Frame(tx, hex); }
    private static SelectionGate started() {
        SelectionGate g = new SelectionGate();
        g.accept(frame(true, "210303017101"));
        g.accept(frame(false, "41030100"));
        return g;
    }
    public void realDualProtocolDiscoverySelectsIsoDepOnlyAfterLastNotice() {
        SelectionGate g = started();
        g.accept(frame(false, ISO)); assertFalse(g.ready());
        g.accept(frame(false, MFC)); assertTrue(g.ready());
        assertArrayEquals(new byte[]{2, 4, 2}, g.selection());
    }
    public void staleNoticesBeforeThisRequestCannotSupplyAnId() {
        SelectionGate g = new SelectionGate();
        g.accept(frame(false, ISO)); g.accept(frame(false, MFC));
        assertFalse(g.ready());
        g.accept(frame(true, "210303017101")); g.accept(frame(false, "41030100"));
        assertFalse(g.ready());
        assertThrows(IllegalStateException.class, g::selection);
    }
    public void twoIsoDepTargetsAreAmbiguous() {
        SelectionGate g = started(); g.accept(frame(false, ISO));
        g.accept(frame(false, ISO.replace("0F0204", "0F0404")));
        g.accept(frame(false, MFC));
        assertFalse(g.ready()); assertTrue(g.reason().contains("多个"));
    }
    public void competingCommandsAndStateChangesPreventSelection() {
        for (PollEvidence.Frame f : new PollEvidence.Frame[]{frame(true, "210403020402"),
                frame(false, "61060100"), frame(false, "600701A1"),
                frame(false, "61051401020400FF010904000401020304012000000000")}) {
            SelectionGate g = started(); g.accept(frame(false, ISO)); g.accept(f); g.accept(frame(false, MFC));
            assertFalse(g.ready()); assertThrows(IllegalStateException.class, g::selection);
        }
    }
    public void rejectionAndInvalidLengthCannotBecomeCandidates() {
        SelectionGate rejected = new SelectionGate();
        rejected.accept(frame(true, "210303017101")); rejected.accept(frame(false, "41030106"));
        rejected.accept(frame(false, ISO)); rejected.accept(frame(false, MFC)); assertFalse(rejected.ready());
        SelectionGate bad = started(); bad.accept(frame(false, "6103050204710A00"));
        bad.accept(frame(false, MFC)); assertFalse(bad.ready());
    }
    public static void main(String[] args) throws Exception {
        new SelectionGateTest().realDualProtocolDiscoverySelectsIsoDepOnlyAfterLastNotice();
        new SelectionGateTest().staleNoticesBeforeThisRequestCannotSupplyAnId();
        new SelectionGateTest().twoIsoDepTargetsAreAmbiguous();
        new SelectionGateTest().competingCommandsAndStateChangesPreventSelection();
        new SelectionGateTest().rejectionAndInvalidLengthCannotBecomeCandidates();
        System.out.println("SelectionGateTest: 5 passed");
    }
}
