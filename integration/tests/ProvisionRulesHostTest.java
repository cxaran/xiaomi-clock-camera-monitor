package org.x04g.bootstrap;

import java.nio.charset.StandardCharsets;

/** Tests only the production policy class on HOST JVM; no Android runtime. */
public final class ProvisionRulesHostTest {
    private static int checks;
    private static void check(boolean value) { if (!value) throw new AssertionError(); ++checks; }
    private static void rejected(byte[] data, int limit) {
        try { ProvisionRules.decode(data, limit); throw new AssertionError(); }
        catch (AssertionError error) { throw error; }
        catch (Exception expected) { ++checks; }
    }
    public static void main(String[] args) throws Exception {
        check(ProvisionRules.metadata(0, 12345, 0100640, 1, 10, 12345, 65536));
        check(ProvisionRules.metadata(0, 0, 0100600, 1, 10, 12345, 65536));
        long[][] invalid = {
            {12345,12345,0100640,1,10}, {0,12346,0100640,1,10},
            {0,12345,0120640,1,10}, {0,12345,0040640,1,10}, {0,12345,0010640,1,10},
            {0,12345,0100660,1,10}, {0,12345,0100644,1,10},
            {0,12345,0104640,1,10}, {0,12345,0100640,2,10},
            {0,12345,0100640,0,10}, {0,12345,0100640,1,0},
            {0,12345,0100640,1,65537}
        };
        for (long[] row : invalid) check(!ProvisionRules.metadata(row[0], row[1], (int)row[2], row[3], row[4], 12345, 65536));
        check(!ProvisionRules.metadata(0, 0, 0100600, 1, 10, 2000, 65536));
        check("{}".equals(ProvisionRules.decode(new byte[]{123,125}, 2)));
        check("ñ".equals(ProvisionRules.decode("ñ".getBytes(StandardCharsets.UTF_8), 2)));
        rejected(null, 10); rejected(new byte[0], 10); rejected(new byte[]{123,125}, 1);
        rejected(new byte[]{(byte)0xc3, 0x28}, 10); rejected(new byte[]{0}, 10);
        rejected(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf,123,125}, 10);
        check(ProvisionRules.sameIdentity(1, 2, 3, 1, 2, 3));
        check(!ProvisionRules.sameIdentity(1, 2, 3, 1, 4, 3));
        check(!ProvisionRules.sameIdentity(1, 2, 3, 4, 2, 3));
        check(!ProvisionRules.sameIdentity(1, 2, 3, 1, 2, 4));
        check(ProvisionRules.cameraResult("PENDING", true, 0, 0, 2) == ProvisionRules.WAIT);
        check(ProvisionRules.cameraResult("SAVED", false, 0, 0, 2) == ProvisionRules.WAIT);
        // IO commits before the posted UI reload: existing snapshot may still be revision0.
        check(ProvisionRules.cameraResult("SAVED", true, 0, 0, 2) == ProvisionRules.WAIT);
        check(ProvisionRules.cameraResult("SAVED", true, 1, 2, 2) == ProvisionRules.SAVED);
        check(ProvisionRules.cameraResult("SAVED", true, 1, 1, 2) == ProvisionRules.FAIL);
        check(ProvisionRules.cameraResult("SAVED", true, 2, 2, 2) == ProvisionRules.FAIL);
        check(ProvisionRules.cameraResult("BUSY", true, 0, 0, 2) == ProvisionRules.FAIL);
        check(ProvisionRules.cameraResult("REJECTED", true, 0, 0, 2) == ProvisionRules.FAIL);
        check(ProvisionRules.cameraResult("NOT_REQUESTED", true, 0, 0, 2) == ProvisionRules.FAIL);
        check(ProvisionRules.gateResult("PENDING", false) == ProvisionRules.WAIT);
        check(ProvisionRules.gateResult("SAVED", false) == ProvisionRules.WAIT);
        check(ProvisionRules.gateResult("SAVED", true) == ProvisionRules.SAVED);
        check(ProvisionRules.gateResult("REJECTED", false) == ProvisionRules.FAIL);
        check(ProvisionRules.gateResult("BUSY", false) == ProvisionRules.FAIL);
        check(ProvisionRules.gateResult("UNKNOWN", false) == ProvisionRules.FAIL);
        System.out.println("{\"pass\":true,\"assertions\":"+checks+",\"Android_runtime\":false}");
    }
}
