package org.x04g.bootstrap;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Pure policy shared with HOST tests; no Android or credential logging. */
final class ProvisionRules {
    static final int WAIT = 0, SAVED = 1, FAIL = -1;
    private ProvisionRules() {}

    static boolean metadata(long uid, long gid, int mode, long links, long size,
                            int expectedUid, int limit) {
        int permissions = mode & 07777;
        return expectedUid >= 10000 && expectedUid <= 19999 && uid == 0
                && (gid == 0 || gid == expectedUid) && (mode & 0170000) == 0100000
                && (permissions == 0600 || permissions == 0640) && links == 1
                && size > 0 && size <= limit;
    }

    static String decode(byte[] input, int limit) throws Exception {
        if (input == null || input.length == 0 || input.length > limit) throw new Exception();
        for (byte value : input) if (value == 0) throw new Exception();
        String result = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input)).toString();
        if (result.length() == 0 || result.charAt(0) == '\ufeff') throw new Exception();
        return result;
    }

    static int cameraResult(String state, boolean loaded, long revision, int count, int expectedCount) {
        if ("REJECTED".equals(state) || "BUSY".equals(state)) return FAIL;
        if ("SAVED".equals(state) && loaded && revision != 0) {
            return revision == 1 && expectedCount >= 0 && count == expectedCount ? SAVED : FAIL;
        }
        return "PENDING".equals(state) || "NONE".equals(state) || "SAVED".equals(state) ? WAIT : FAIL;
    }

    static int gateResult(String state, boolean configured) {
        if ("REJECTED".equals(state) || "BUSY".equals(state)) return FAIL;
        if ("SAVED".equals(state) && configured) return SAVED;
        return "PENDING".equals(state) || "NONE".equals(state) || "SAVED".equals(state) ? WAIT : FAIL;
    }

    static boolean sameIdentity(long firstDevice, long firstInode, long firstSize,
                                long device, long inode, long size) {
        return firstDevice == device && firstInode == inode && firstSize == size;
    }
}
