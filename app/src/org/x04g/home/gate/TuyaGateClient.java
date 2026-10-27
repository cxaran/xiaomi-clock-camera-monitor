package org.x04g.home.gate;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Blocking worker API. No automatic connection, polling, retransmission, control queue or RF learning. */
public final class TuyaGateClient implements Closeable {
    private static final long TOTAL_NS = 4_000_000_000L, CONNECT_NS = 1_500_000_000L;
    private static final long PROOF_NS = 30_000_000_000L, TICKET_NS = 5_000_000_000L;
    private final GateProfile profile;
    private final byte[] key;
    private final TransportFactory factory;
    private final Clock clock;
    private final Object lifecycle = new Object();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicInteger sequence = new AtomicInteger(1);
    private volatile long lastKeyProof = Long.MIN_VALUE;
    private volatile boolean closed;
    private Transport active;

    public TuyaGateClient(GateProfile profile) {
        this(profile, new NioFactory(), new Clock() { public long nanoTime() { return System.nanoTime(); } });
    }
    TuyaGateClient(GateProfile profile, TransportFactory factory, Clock clock) {
        if (profile == null || factory == null || clock == null) throw new IllegalArgumentException("client_parameters");
        this.profile = profile; this.key = profile.keyCopy(); this.factory = factory; this.clock = clock;
    }

    /** Does not perform IO. A query rejection decrypted with the right key can also establish this proof. */
    public boolean canActuate() { return !closed && !busy.get() && profile.hasDocumentedReplayProfile() && freshProof(); }
    public boolean physicalReplayVerified() { return profile.physicalReplayVerified(); }

    /** Call only for a fresh deliberate user tap, then submit immediately to the UI's worker. */
    public ManualRequest manualRequestFromUserTap() { return new ManualRequest(this, clock.nanoTime()); }

    public GateStatus queryStatus() {
        long now = clock.nanoTime();
        if (closed) return GateStatus.simple(GateStatus.State.CLOSED, GateStatus.Problem.CANCELLED, false, now);
        if (!busy.compareAndSet(false, true)) return GateStatus.simple(GateStatus.State.BUSY, GateStatus.Problem.NONE, false, now);
        Transport connection = null; byte[] operationKey = null; boolean connected = false;
        try {
            operationKey = operationKey();
            long deadline = clock.nanoTime() + TOTAL_NS;
            connection = open(Math.min(deadline, clock.nanoTime() + CONNECT_NS)); connected = true;
            int seq = nextSequence();
            JSONObject payload = new JSONObject().put("gwId", profile.deviceId).put("devId", profile.deviceId)
                    .put("uid", profile.deviceId).put("t", Long.toString(System.currentTimeMillis() / 1000));
            byte[] encoded = Tuya33Codec.request(seq, Tuya33Codec.DP_QUERY,
                    payload.toString().getBytes(StandardCharsets.UTF_8), operationKey);
            try { writeAll(connection, encoded, deadline, new SendState()); }
            finally { Arrays.fill(encoded, (byte) 0); }
            for (int frames = 0; frames < 8; frames++) {
                Tuya33Codec.Frame reply = readFrame(connection, deadline);
                try {
                    if (!(reply.command == Tuya33Codec.DP_QUERY && reply.sequence == seq
                            || reply.command == Tuya33Codec.STATUS)) continue;
                    boolean queryRejected = reply.returnCode != 0;
                    if (reply.payload.length == 0) {
                        if (queryRejected) return finishStatus(GateStatus.simple(GateStatus.State.REACHABLE_UNCONFIRMED,
                                GateStatus.Problem.DEVICE_REJECTED, true, clock.nanoTime()));
                        continue; // A bare ACK does not confirm the key.
                    }
                    // L20 returns a known encrypted rejection with retcode 1. Only its verified plaintext proves the key.
                    byte[] plain = Tuya33Codec.plaintext(reply.payload, operationKey);
                    GateStatus.Decoded decoded;
                    try { decoded = GateStatus.inspect(plain); } finally { Arrays.fill(plain, (byte) 0); }
                    return finishStatus(new GateStatus(decoded.keyConfirmed
                            ? decoded.hasDps && !queryRejected ? GateStatus.State.ONLINE_STATUS : GateStatus.State.ONLINE_QUERY_REJECTED
                            : GateStatus.State.REACHABLE_UNCONFIRMED,
                            queryRejected || decoded.rejected ? GateStatus.Problem.DEVICE_REJECTED : GateStatus.Problem.NONE,
                            true, decoded.keyConfirmed, decoded.types, clock.nanoTime()));
                } finally { Arrays.fill(reply.payload, (byte) 0); }
            }
            return finishStatus(GateStatus.simple(GateStatus.State.REACHABLE_UNCONFIRMED,
                    GateStatus.Problem.INVALID_FRAME, true, clock.nanoTime()));
        } catch (Exception failure) {
            return finishStatus(GateStatus.simple(closed ? GateStatus.State.CLOSED : connected
                    ? GateStatus.State.REACHABLE_UNCONFIRMED : GateStatus.State.OFFLINE,
                    classify(failure), connected, clock.nanoTime()));
        } finally { release(connection, operationKey); busy.set(false); }
    }

    /** Exactly one CONTROL frame, at most once per ticket. Every uncertain send remains uncertain. */
    public ControlResult actuateOnce(ManualRequest request) {
        if (request == null || request.owner != this || !request.used.compareAndSet(false, true))
            return result(ControlResult.Outcome.INVALID_REQUEST, GateStatus.Problem.NONE);
        long age = clock.nanoTime() - request.created;
        if (age < 0 || age > TICKET_NS) return result(ControlResult.Outcome.INVALID_REQUEST, GateStatus.Problem.NONE);
        if (closed) return result(ControlResult.Outcome.CLOSED, GateStatus.Problem.CANCELLED);
        if (!profile.hasDocumentedReplayProfile()) return result(ControlResult.Outcome.NOT_CONFIGURED, GateStatus.Problem.NONE);
        if (!busy.compareAndSet(false, true)) return result(ControlResult.Outcome.BUSY, GateStatus.Problem.NONE);
        Transport connection = null; byte[] operationKey = null; SendState send = new SendState();
        try {
            if (!freshProof()) return result(ControlResult.Outcome.NEEDS_FRESH_STATUS, GateStatus.Problem.NONE);
            operationKey = operationKey();
            long deadline = clock.nanoTime() + TOTAL_NS;
            connection = open(Math.min(deadline, clock.nanoTime() + CONNECT_NS));
            // Recheck the user intent after connection; no queued/late pulse is emitted.
            if (clock.nanoTime() - request.created > TICKET_NS) return result(ControlResult.Outcome.INVALID_REQUEST, GateStatus.Problem.TIMEOUT);
            int seq = nextSequence();
            JSONObject payload = new JSONObject().put("devId", profile.deviceId).put("uid", profile.deviceId)
                    .put("t", Long.toString(System.currentTimeMillis() / 1000)).put("dps", profile.pulse.dps());
            byte[] encoded = Tuya33Codec.request(seq, Tuya33Codec.CONTROL,
                    payload.toString().getBytes(StandardCharsets.UTF_8), operationKey);
            try { writeAll(connection, encoded, deadline, send); }
            finally { Arrays.fill(encoded, (byte) 0); }
            for (int frames = 0; frames < 8; frames++) {
                Tuya33Codec.Frame reply = readFrame(connection, deadline);
                try {
                    if (reply.sequence != seq || reply.command != Tuya33Codec.CONTROL) continue;
                    if (reply.returnCode != 0) return result(ControlResult.Outcome.DEVICE_REJECTED, GateStatus.Problem.DEVICE_REJECTED);
                    if (reply.payload.length != 0) {
                        byte[] plain = Tuya33Codec.plaintext(reply.payload, operationKey);
                        GateStatus.Decoded decoded;
                        try { decoded = GateStatus.inspect(plain); } finally { Arrays.fill(plain, (byte) 0); }
                        if (decoded.rejected) return result(ControlResult.Outcome.DEVICE_REJECTED, GateStatus.Problem.DEVICE_REJECTED);
                        if (!decoded.keyConfirmed) return result(ControlResult.Outcome.UNCERTAIN, GateStatus.Problem.KEY_OR_VERSION);
                    }
                    // Transport ACK is never reported as physical movement or open/closed position.
                    return result(ControlResult.Outcome.ACKNOWLEDGED, GateStatus.Problem.NONE);
                } finally { Arrays.fill(reply.payload, (byte) 0); }
            }
            return result(ControlResult.Outcome.UNCERTAIN, GateStatus.Problem.INVALID_FRAME);
        } catch (Exception failure) {
            return result(send.mayHaveSent ? ControlResult.Outcome.UNCERTAIN : ControlResult.Outcome.NOT_SENT, classify(failure));
        } finally {
            // Require another user-visible status check after an uncertain/rejected control as well.
            lastKeyProof = Long.MIN_VALUE;
            release(connection, operationKey); busy.set(false);
        }
    }

    private boolean freshProof() {
        long proof = lastKeyProof, age = clock.nanoTime() - proof;
        return proof != Long.MIN_VALUE && age >= 0 && age <= PROOF_NS;
    }
    private GateStatus finishStatus(GateStatus status) {
        lastKeyProof = status.keyConfirmed ? status.observedAtNanos : Long.MIN_VALUE; return status;
    }
    private int nextSequence() { int value = sequence.getAndIncrement(); return value == 0 ? sequence.getAndIncrement() : value; }
    private byte[] operationKey() throws IOException {
        synchronized (lifecycle) { if (closed) throw new IOException("cancelled"); return key.clone(); }
    }
    private Transport open(long connectDeadline) throws Exception {
        Transport connection = factory.create(profile.address.clone());
        synchronized (lifecycle) {
            if (closed) { connection.close(); throw new IOException("cancelled"); }
            active = connection;
        }
        connection.connect(connectDeadline);
        return connection;
    }
    private void release(Transport connection, byte[] operationKey) {
        synchronized (lifecycle) {
            if (active != null) { try { active.close(); } catch (IOException ignored) {} active = null; }
        }
        if (connection != null) try { connection.close(); } catch (IOException ignored) {}
        if (operationKey != null) Arrays.fill(operationKey, (byte) 0);
    }
    @Override public void close() {
        synchronized (lifecycle) {
            closed = true; lastKeyProof = Long.MIN_VALUE; Arrays.fill(key, (byte) 0);
            if (active != null) try { active.close(); } catch (IOException ignored) {}
        }
    }
    private static void writeAll(Transport connection, byte[] bytes, long deadline, SendState state) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            boolean earlierSend = state.mayHaveSent;
            state.mayHaveSent = true; // An exception in write may occur after an unknown partial write.
            int count = connection.write(buffer, deadline);
            if (count == 0) state.mayHaveSent = earlierSend;
            if (count < 0) throw new EOFException("write_closed");
        }
    }
    private static Tuya33Codec.Frame readFrame(Transport connection, long deadline) throws Exception {
        ByteBuffer header = ByteBuffer.allocate(16); connection.readFully(header, deadline);
        int length = Tuya33Codec.frameLength(header.array());
        byte[] frame = Arrays.copyOf(header.array(), length);
        try {
            ByteBuffer body = ByteBuffer.wrap(frame, 16, length - 16);
            connection.readFully(body, deadline);
            return Tuya33Codec.response(frame);
        } finally { Arrays.fill(frame, (byte) 0); }
    }
    private GateStatus.Problem classify(Exception failure) {
        if (closed) return GateStatus.Problem.CANCELLED;
        if (failure instanceof SocketTimeoutException) return GateStatus.Problem.TIMEOUT;
        if (failure instanceof java.security.GeneralSecurityException) return GateStatus.Problem.KEY_OR_VERSION;
        if (failure instanceof org.json.JSONException || failure instanceof IllegalArgumentException
                || failure instanceof java.net.ProtocolException) return GateStatus.Problem.INVALID_FRAME;
        return GateStatus.Problem.IO;
    }
    private ControlResult result(ControlResult.Outcome outcome, GateStatus.Problem problem) {
        return new ControlResult(outcome, problem, profile.physicalReplayVerified());
    }

    public static final class ManualRequest {
        private final TuyaGateClient owner; private final long created; private final AtomicBoolean used = new AtomicBoolean();
        private ManualRequest(TuyaGateClient owner, long created) { this.owner = owner; this.created = created; }
    }
    public static final class ControlResult {
        public enum Outcome { ACKNOWLEDGED, UNCERTAIN, NOT_SENT, DEVICE_REJECTED, NOT_CONFIGURED,
            NEEDS_FRESH_STATUS, INVALID_REQUEST, BUSY, CLOSED }
        public final Outcome outcome; public final GateStatus.Problem problem;
        public final boolean physicalReplayVerified;
        private ControlResult(Outcome outcome, GateStatus.Problem problem, boolean verified) {
            this.outcome = outcome; this.problem = problem; physicalReplayVerified = verified;
        }
    }
    interface Clock { long nanoTime(); }
    interface TransportFactory { Transport create(byte[] address) throws Exception; }
    interface Transport extends Closeable {
        void connect(long deadline) throws Exception;
        int write(ByteBuffer source, long deadline) throws Exception;
        void readFully(ByteBuffer destination, long deadline) throws Exception;
    }
    private static final class SendState { boolean mayHaveSent; }
    private static final class NioFactory implements TransportFactory {
        public Transport create(byte[] address) throws Exception { return new NioTransport(address); }
    }
    private static final class NioTransport implements Transport {
        private final SocketChannel channel;
        private final Selector selector;
        private final InetSocketAddress target;
        NioTransport(byte[] address) throws Exception {
            target = new InetSocketAddress(InetAddress.getByAddress(address), 6668); // Numeric bytes: no DNS.
            channel = SocketChannel.open();
            Selector candidate = null;
            try {
                channel.configureBlocking(false); channel.socket().setTcpNoDelay(true);
                candidate = Selector.open(); channel.register(candidate, 0);
            } catch (Exception failure) { channel.close(); if (candidate != null) candidate.close(); throw failure; }
            selector = candidate;
        }
        public void connect(long deadline) throws Exception {
            if (!channel.connect(target)) while (!channel.finishConnect()) waitReady(SelectionKey.OP_CONNECT, deadline);
        }
        public int write(ByteBuffer source, long deadline) throws Exception {
            checkDeadline(deadline);
            int count = channel.write(source);
            if (count == 0) waitReady(SelectionKey.OP_WRITE, deadline);
            return count;
        }
        public void readFully(ByteBuffer destination, long deadline) throws Exception {
            while (destination.hasRemaining()) {
                checkDeadline(deadline);
                int count = channel.read(destination);
                if (count < 0) throw new EOFException("read_closed");
                if (count == 0) waitReady(SelectionKey.OP_READ, deadline);
            }
        }
        private void waitReady(int operation, long deadline) throws Exception {
            channel.keyFor(selector).interestOps(operation);
            while (true) {
                long remaining = checkDeadline(deadline);
                int selected = selector.select(Math.max(1, (remaining + 999999) / 1000000));
                selector.selectedKeys().clear();
                if (selected > 0) return;
            }
        }
        private static long checkDeadline(long deadline) throws SocketTimeoutException {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new SocketTimeoutException("deadline");
            return remaining;
        }
        public void close() throws IOException {
            try { channel.close(); } finally { selector.wakeup(); selector.close(); }
        }
    }
}
