"""Single-client, finite synthetic RTSP/TCP fixture. Importing never opens sockets.

Only explicit main()/serve() starts a listener. Media is generated testpattern
and test tone; this server neither receives camera URLs nor calls Android.
"""
from __future__ import annotations

import argparse
import base64
from collections import deque
from dataclasses import dataclass
import hashlib
import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import select
import signal
import socket
import struct
import sys
import time
from urllib.parse import urlsplit

HERE = Path(__file__).resolve().parent
VIDEO_NAME = "testpattern_640x360_15.h264"
AUDIO_NAME = "tone_440hz_8000.alaw"
MAX_QUEUE = 512 * 1024
MAX_REQUEST = 16 * 1024
PUBLIC_USER = "fixture"
PUBLIC_PASSWORD = "fixture-public"
REALM = "X04GFixture"
SESSION = "x04g-synthetic-v1"
FAULT_MODES = ("normal", "video-stall", "interleaved-drip")
FAULT_AFTER_ACCESS_UNITS = 30  # About two seconds of valid 15fps media.
DRIP_PAYLOAD_BYTES = 1024
DRIP_INTERVAL_SECONDS = 1.0
DRIP_DURATION_SECONDS = 12.0
MAX_SERVER_SECONDS = 1800


def split_annexb(data: bytes) -> list[bytes]:
    """Bounded whole fixture parse; AUD divides exactly 60 access units."""
    if not data or len(data) > 2 * 1024 * 1024:
        raise ValueError("invalid_fixture_size")
    matches = list(re.finditer(b"\x00\x00(?:\x00)?\x01", data))
    if not matches or matches[0].start() != 0:
        raise ValueError("annexb_required")
    nals = []
    for i, match in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else len(data)
        nal = data[match.end():end]
        if not nal or len(nal) > 1024 * 1024 or nal[0] & 0x80:
            raise ValueError("bad_fixture_nal")
        nals.append(nal)
    return nals


@dataclass(frozen=True)
class Media:
    access_units: tuple[tuple[bytes, ...], ...]
    audio: bytes
    sps: bytes
    pps: bytes

    @classmethod
    def load(cls, directory: Path) -> "Media":
        manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
        if manifest.get("synthetic_only") is not True:
            raise ValueError("synthetic_manifest_required")
        blobs = {}
        for name in (VIDEO_NAME, AUDIO_NAME):
            path = directory / name
            if path.stat().st_size > 2 * 1024 * 1024:
                raise ValueError("fixture_too_large")
            blob = path.read_bytes()
            expected = manifest["files"][name]
            if len(blob) != expected["bytes"] or hashlib.sha256(blob).hexdigest() != expected["sha256"]:
                raise ValueError("fixture_hash_mismatch")
            blobs[name] = blob
        nals = split_annexb(blobs[VIDEO_NAME])
        units, current, sps, pps = [], [], None, None
        for nal in nals:
            kind = nal[0] & 31
            if kind == 9 and current:
                units.append(tuple(current))
                current = []
            if kind == 7:
                sps = sps or nal
            elif kind == 8:
                pps = pps or nal
            current.append(nal)
        if current:
            units.append(tuple(current))
        if len(units) != 60 or not sps or not pps or len(blobs[AUDIO_NAME]) != 32000:
            raise ValueError("unexpected_fixture_shape")
        if not all(any(n[0] & 31 in (1, 5) for n in unit) for unit in units):
            raise ValueError("fixture_missing_vcl")
        return cls(tuple(units), blobs[AUDIO_NAME], sps, pps)

    def sdp(self) -> bytes:
        sets = base64.b64encode(self.sps).decode() + "," + base64.b64encode(self.pps).decode()
        profile = self.sps[1:4].hex()
        return ("v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\ns=X04G synthetic testpattern and tone\r\n"
                "c=IN IP4 0.0.0.0\r\nt=0 0\r\na=control:*\r\n"
                "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n"
                f"a=fmtp:96 packetization-mode=1;profile-level-id={profile};sprop-parameter-sets={sets}\r\n"
                "a=control:trackID=0\r\nm=audio 0 RTP/AVP 8\r\na=rtpmap:8 PCMA/8000/1\r\n"
                "a=control:trackID=1\r\n").encode("ascii")


def h264_payloads(nal: bytes, maximum: int = 1100) -> list[bytes]:
    if not nal or not 3 <= maximum <= 1400:
        raise ValueError("invalid_packetizer_argument")
    if len(nal) <= maximum:
        return [nal]
    kind, indicator = nal[0] & 31, (nal[0] & 0xE0) | 28
    payloads = []
    for start in range(1, len(nal), maximum - 2):
        stop = min(start + maximum - 2, len(nal))
        header = kind | (0x80 if start == 1 else 0) | (0x40 if stop == len(nal) else 0)
        payloads.append(bytes((indicator, header)) + nal[start:stop])
    return payloads


def interleaved(channel: int, pt: int, seq: int, stamp: int, ssrc: int,
                marker: bool, payload: bytes) -> bytes:
    rtp = struct.pack("!BBHII", 0x80, pt | (128 if marker else 0), seq & 65535,
                      stamp & 0xFFFFFFFF, ssrc) + payload
    if len(rtp) > 65535 or not 0 <= channel <= 255:
        raise ValueError("rtp_packet_too_large")
    return b"$" + struct.pack("!BH", channel, len(rtp)) + rtp


@dataclass(frozen=True)
class Request:
    method: str
    uri: str
    cseq: str
    headers: dict[str, str]


def take_request(buffer: bytearray) -> Request | None:
    # A client may send interleaved RTCP. Consume bounded frames, no interpretation.
    while buffer and buffer[0] == 36:
        if len(buffer) < 4:
            return None
        length = struct.unpack_from("!H", buffer, 2)[0]
        if length > MAX_REQUEST:
            raise ValueError("client_rtcp_too_large")
        if len(buffer) < length + 4:
            return None
        del buffer[:length + 4]
    end = buffer.find(b"\r\n\r\n")
    if end < 0:
        if len(buffer) > MAX_REQUEST:
            raise ValueError("request_too_large")
        return None
    if end > MAX_REQUEST:
        raise ValueError("request_too_large")
    text = bytes(buffer[:end]).decode("ascii")
    lines = text.split("\r\n")
    first = lines[0].split(" ")
    if len(first) != 3 or first[2] != "RTSP/1.0" or not re.fullmatch("[A-Z_]+", first[0]):
        raise ValueError("bad_request_line")
    if not first[1] or any(ord(c) <= 32 or ord(c) >= 127 for c in first[1]):
        raise ValueError("bad_request_uri")
    headers = {}
    for line in lines[1:]:
        if ":" not in line:
            raise ValueError("bad_header")
        key, value = line.split(":", 1)
        key = key.lower()
        if not re.fullmatch("[a-z-]+", key) or key in headers:
            raise ValueError("bad_or_duplicate_header")
        headers[key] = value.strip()
    sequence = headers.get("cseq", "")
    length = headers.get("content-length", "0")
    if not re.fullmatch("[0-9]{1,9}", sequence) or not re.fullmatch("[0-9]{1,5}", length):
        raise ValueError("bad_request_numbers")
    length_num = int(length)
    if length_num > MAX_REQUEST or "transfer-encoding" in headers:
        raise ValueError("request_body_too_large")
    total = end + 4 + length_num
    if len(buffer) < total:
        return None
    del buffer[:total]
    return Request(first[0], first[1], sequence, headers)


def response(request: Request, code: int, headers: dict[str, str] | None = None,
             body: bytes = b"") -> bytes:
    reasons = {200: "OK", 400: "Bad Request", 401: "Unauthorized", 404: "Not Found",
               405: "Method Not Allowed", 454: "Session Not Found", 455: "Method Not Valid in This State",
               461: "Unsupported Transport", 503: "Service Unavailable"}
    text = f"RTSP/1.0 {code} {reasons[code]}\r\nCSeq: {request.cseq}\r\n"
    for key, value in (headers or {}).items():
        text += f"{key}: {value}\r\n"
    return (text + f"Content-Length: {len(body)}\r\n\r\n").encode("ascii") + body


class FixtureSession:
    """Protocol state machine; pure methods allow host-only tests without socket."""
    def __init__(self, media: Media, auth: str, fault_mode: str = "normal"):
        if fault_mode not in FAULT_MODES:
            raise ValueError("invalid_fault_mode")
        self.media, self.auth = media, auth
        self.fault_mode = fault_mode
        self.fault_active = False
        self.fault_started_at = self.next_drip = 0.0
        self.partial_message_started = False
        self.nonce = secrets.token_hex(16)
        self.channels: dict[int, int] = {}
        self.playing = False
        self.closing = False
        self.video_index = self.audio_index = 0
        self.video_seq = self.audio_seq = 0
        self.video_stamp = self.audio_stamp = 0
        self.next_video = self.next_audio = 0.0
        self.last_nonce_count = 0
        self.counters = {"requests": 0, "play": 0, "pause": 0, "teardown": 0,
                         "video_access_units": 0, "video_packets": 0, "audio_packets": 0,
                         "fault_started": 0, "fault_drip_bytes": 0,
                         "suppressed_control_responses": 0}

    def authorized(self, request: Request) -> bool:
        if self.auth == "none":
            return True
        value = request.headers.get("authorization", "")
        if self.auth == "basic":
            expected = "Basic " + base64.b64encode((PUBLIC_USER + ":" + PUBLIC_PASSWORD).encode()).decode()
            return hmac.compare_digest(value, expected)
        if not value.startswith("Digest "):
            return False
        pairs = re.findall(r'([A-Za-z]+)=(?:"([^"\r\n]*)"|([^,\s]+))', value[7:])
        fields = {}
        for key, quoted, token in pairs:
            if key.lower() in fields:
                return False
            fields[key.lower()] = quoted or token
        if (fields.get("username") != PUBLIC_USER or fields.get("realm") != REALM
                or fields.get("nonce") != self.nonce or fields.get("uri") != request.uri
                or fields.get("qop") != "auth" or fields.get("algorithm", "MD5").upper() != "MD5"
                or not re.fullmatch("[0-9a-fA-F]{8}", fields.get("nc", ""))
                or not re.fullmatch("[A-Za-z0-9._~-]{1,128}", fields.get("cnonce", ""))):
            return False
        count = int(fields["nc"], 16)
        if count <= self.last_nonce_count:
            return False
        md5 = lambda text: hashlib.md5(text.encode("utf-8")).hexdigest()
        ha1 = md5(f"{PUBLIC_USER}:{REALM}:{PUBLIC_PASSWORD}")
        ha2 = md5(f"{request.method}:{request.uri}")
        expected = md5(f"{ha1}:{self.nonce}:{fields['nc']}:{fields['cnonce']}:auth:{ha2}")
        if not hmac.compare_digest(fields.get("response", ""), expected):
            return False
        self.last_nonce_count = count
        return True

    def handle(self, request: Request, now: float, enabled: bool = True) -> bytes:
        self.counters["requests"] += 1
        if not enabled:
            self.closing = True
            return response(request, 503)
        if not self.authorized(request):
            challenge = (f'Basic realm="{REALM}"' if self.auth == "basic" else
                         f'Digest realm="{REALM}", nonce="{self.nonce}", algorithm=MD5, qop="auth"')
            return response(request, 401, {"WWW-Authenticate": challenge})
        parsed = urlsplit(request.uri)
        if request.uri != "*" and (parsed.scheme != "rtsp" or parsed.username is not None
                                      or parsed.password is not None or parsed.query or parsed.fragment
                                      or parsed.path not in ("/stream2", "/stream2/", "/stream2/trackID=0", "/stream2/trackID=1")):
            return response(request, 404)
        method = request.method
        if method == "OPTIONS":
            return response(request, 200, {"Public": "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, GET_PARAMETER, TEARDOWN"})
        if method == "DESCRIBE":
            if parsed.path.rstrip("/") != "/stream2":
                return response(request, 404)
            base = request.uri.rstrip("/") + "/"
            return response(request, 200, {"Content-Type": "application/sdp", "Content-Base": base}, self.media.sdp())
        if method == "SETUP":
            track = 0 if parsed.path.endswith("trackID=0") else (1 if parsed.path.endswith("trackID=1") else -1)
            value = request.headers.get("transport", "")
            match = re.fullmatch(r"RTP/AVP/TCP;unicast;interleaved=([0-9]{1,3})-([0-9]{1,3})", value, re.I)
            if not match or track < 0:
                return response(request, 461)
            first, second = map(int, match.groups())
            if second != first + 1 or second > 255 or any(abs(first - existing) <= 1 for t, existing in self.channels.items() if t != track):
                return response(request, 461)
            if self.channels and request.headers.get("session", "").split(";", 1)[0] != SESSION:
                return response(request, 454)
            self.channels[track] = first
            return response(request, 200, {"Transport": f"RTP/AVP/TCP;unicast;interleaved={first}-{second}", "Session": SESSION + ";timeout=30"})
        if request.headers.get("session", "").split(";", 1)[0] != SESSION:
            return response(request, 454)
        headers = {"Session": SESSION}
        if method == "PLAY":
            if 0 not in self.channels:
                return response(request, 455)
            # Resume from next IDR so PAUSE/PLAY does not require stale reference frames.
            while not any(n[0] & 31 == 5 for n in self.media.access_units[self.video_index % len(self.media.access_units)]):
                self.video_index += 1
                self.video_stamp += 6000
            self.playing = True
            self.next_video = self.next_audio = now
            self.counters["play"] += 1
            return response(request, 200, headers)
        if method == "PAUSE":
            self.playing = False
            self.counters["pause"] += 1
            return response(request, 200, headers)
        if method == "TEARDOWN":
            self.playing = False
            self.closing = True
            self.counters["teardown"] += 1
            return response(request, 200, headers)
        if method == "GET_PARAMETER":
            return response(request, 200, headers)
        return response(request, 405)

    def due(self, now: float) -> list[bytes]:
        if not self.playing or self.closing:
            return []
        packets = []
        if (self.fault_mode != "normal" and not self.fault_active
                and self.counters["video_access_units"] >= FAULT_AFTER_ACCESS_UNITS
                and now >= self.next_video):
            self.fault_active = True
            self.fault_started_at = now
            self.counters["fault_started"] += 1
            if self.fault_mode == "interleaved-drip":
                # An incomplete RTP/TCP frame: no other bytes may be inserted
                # in this outbound stream once its advertised body has begun.
                self.partial_message_started = True
                self.next_drip = now + DRIP_INTERVAL_SECONDS
                self.counters["fault_drip_bytes"] = 1
                return [b"$" + struct.pack("!BH", self.channels[0], DRIP_PAYLOAD_BYTES) + b"\x80"]
        if self.partial_message_started:
            if (now - self.fault_started_at <= DRIP_DURATION_SECONDS
                    and now >= self.next_drip):
                # Never catch up with a burst after a slow event-loop tick.
                self.next_drip = now + DRIP_INTERVAL_SECONDS
                self.counters["fault_drip_bytes"] += 1
                return [b"\x00"]
            return []
        if not self.fault_active and now >= self.next_video:
            unit = self.media.access_units[self.video_index % len(self.media.access_units)]
            for nal_index, nal in enumerate(unit):
                payloads = h264_payloads(nal)
                for index, payload in enumerate(payloads):
                    marker = nal_index == len(unit) - 1 and index == len(payloads) - 1
                    packets.append(interleaved(self.channels[0], 96, self.video_seq, self.video_stamp, 0x58344701, marker, payload))
                    self.video_seq += 1
                    self.counters["video_packets"] += 1
            self.video_index += 1
            self.video_stamp += 6000
            self.counters["video_access_units"] += 1
            self.next_video += 1 / 15
            if self.next_video < now - 0.25:
                raise ValueError("fixture_video_scheduler_backpressure")
        if 1 in self.channels and now >= self.next_audio:
            begin = (self.audio_index * 160) % len(self.media.audio)
            packets.append(interleaved(self.channels[1], 8, self.audio_seq, self.audio_stamp, 0x58344702, False, self.media.audio[begin:begin + 160]))
            self.audio_index += 1
            self.audio_seq += 1
            self.audio_stamp += 160
            self.counters["audio_packets"] += 1
            self.next_audio += 0.02
            if self.next_audio < now - 0.25:
                raise ValueError("fixture_audio_scheduler_backpressure")
        return packets


class StatsFile:
    """Optional public host-side observations; never an input/control channel."""
    def __init__(self, path: Path | None):
        self.path = path
        if path is not None:
            # Refuse an existing destination, including a symlink or old run.
            with path.open("x", encoding="ascii") as output:
                output.write("{}\n")

    def publish(self, value: dict) -> None:
        if self.path is None:
            return
        data = (json.dumps(value, sort_keys=True) + "\n").encode("ascii")
        if len(data) > 64 * 1024:
            raise ValueError("stats_limit")
        temp = self.path.with_name(self.path.name + ".tmp")
        created = False
        try:
            with temp.open("xb") as output:
                created = True
                output.write(data)
                output.flush()
                os.fsync(output.fileno())
            temp.replace(self.path)
        finally:
            # Only this explicitly owned temporary path, never other files.
            if created and temp.is_file():
                temp.unlink()


def enabled_state(path: Path | None) -> bool:
    if path is None:
        return True
    if path.stat().st_size > 256:
        raise ValueError("state_too_large")
    value = json.loads(path.read_text(encoding="ascii"))
    if not isinstance(value, dict) or set(value) != {"enabled"} or not isinstance(value["enabled"], bool):
        raise ValueError("invalid_state")
    return value["enabled"]


def validate_bind(value: str) -> str:
    address = ipaddress.IPv4Address(value)
    private = (ipaddress.IPv4Network("10.0.0.0/8"), ipaddress.IPv4Network("172.16.0.0/12"), ipaddress.IPv4Network("192.168.0.0/16"))
    if not address.is_loopback and not any(address in block for block in private):
        raise ValueError("bind_must_be_loopback_or_rfc1918")
    return str(address)


def validate_server_options(bind: str, port: int, seconds: int, auth: str, fault_mode: str) -> None:
    validate_bind(bind)
    if (not 1024 <= port <= 65535 or not 1 <= seconds <= MAX_SERVER_SECONDS
            or auth not in ("none", "basic", "digest") or fault_mode not in FAULT_MODES):
        raise ValueError("invalid_server_options")


def session_close_reason(failure: Exception) -> str:
    """Fixed public labels only; never export arbitrary exception messages."""
    if isinstance(failure, EOFError):
        return {"client_eof": "client_eof", "send_eof": "send_eof",
                "teardown_complete": "teardown_complete"}.get(str(failure), "client_eof")
    if isinstance(failure, OSError):
        return "socket_error"
    if isinstance(failure, ValueError):
        return {"output_backpressure": "output_backpressure",
                "fixture_video_scheduler_backpressure": "video_scheduler_backpressure",
                "fixture_audio_scheduler_backpressure": "audio_scheduler_backpressure"}.get(str(failure), "protocol_error")
    return "protocol_error"


def serve(media: Media, bind: str, port: int, seconds: int, auth: str, state: Path | None,
          fault_mode: str = "normal", stats: Path | None = None) -> dict:
    """Explicit operator-only activation; one accepted client at a time."""
    validate_server_options(bind, port, seconds, auth, fault_mode)
    observations = StatsFile(stats)
    stopped = False

    def stop(_signum, _frame):
        nonlocal stopped
        stopped = True

    previous = {sig: signal.signal(sig, stop) for sig in (signal.SIGINT, signal.SIGTERM)}
    totals = {"accepted": 0, "closed": 0, "client_error": 0, "backpressure": 0, "bytes_sent": 0, "sessions": []}
    started = time.monotonic()
    end = started + seconds
    next_stats = started
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client = None
    session = None
    session_started = 0.0
    peak_queued = session_bytes_sent = 0
    last_send = 0.0
    running = False

    def session_snapshot(stopped_at: float | None = None, close_reason: str | None = None) -> dict:
        observed_at = time.monotonic() if stopped_at is None else stopped_at
        return {**session.counters, "ordinal": totals["accepted"], "fault_mode": fault_mode,
                "started_monotonic_ms": round(session_started * 1000),
                "stopped_monotonic_ms": None if stopped_at is None else round(stopped_at * 1000),
                "fault_started_monotonic_ms": round(session.fault_started_at * 1000) if session.fault_active else None,
                "partial_message_started": session.partial_message_started,
                "close_reason": close_reason, "peak_pending_bytes": peak_queued,
                "pending_bytes": queued, "bytes_sent": session_bytes_sent,
                "last_send_age_ms": None if not session_bytes_sent else max(0, round((observed_at - last_send) * 1000))}

    def snapshot(now: float) -> dict:
        return {"schema": "x04g.synthetic_fixture.observations.v1", "fault_mode": fault_mode,
                "port": port, "seconds": seconds, "elapsed_ms": round((now - started) * 1000),
                "running": running,
                "accepted": totals["accepted"], "closed": totals["closed"],
                "active": client is not None, "client_error": totals["client_error"],
                "backpressure": totals["backpressure"], "bytes_sent": totals["bytes_sent"],
                "sessions": totals["sessions"],
                "active_session": session_snapshot() if client is not None else None}
    try:
        # No SO_REUSEPORT/REUSEADDR and no LAN topology/firewall changes.
        listener.bind((bind, port))
        listener.listen(1)
        listener.setblocking(False)
        running = True
        print(json.dumps({"fixture_ready": True, "bind": bind, "port": port, "seconds": seconds,
                          "synthetic_only": True, "auth": auth, "fault_mode": fault_mode}), flush=True)
        incoming = bytearray()
        pending: deque[memoryview] = deque()
        queued = 0
        last_io = time.monotonic()
        close_deadline = 0.0
        while not stopped and time.monotonic() < end:
            now = time.monotonic()
            enabled = enabled_state(state)
            if client is not None and (not enabled or now - last_io > 35 or close_deadline and now >= close_deadline):
                reason = "disabled" if not enabled else ("idle_timeout" if now - last_io > 35 else "teardown_timeout")
                client.close()
                client = None
                totals["closed"] += 1
                totals["sessions"].append(session_snapshot(now, reason))
                if len(totals["sessions"]) >= 32:
                    break
            if now >= next_stats:
                observations.publish(snapshot(now))
                next_stats = now + 1.0
            readers = [listener] + ([client] if client is not None else [])
            writers = [client] if client is not None and pending else []
            readable, writable, _ = select.select(readers, writers, [], 0.01)
            if listener in readable:
                accepted, _address = listener.accept()
                if client is not None:
                    accepted.close()
                else:
                    client = accepted
                    client.setblocking(False)
                    client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                    client.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 65536)
                    session = FixtureSession(media, auth, fault_mode)
                    session_started = now
                    incoming.clear()
                    pending.clear()
                    queued = 0
                    peak_queued = session_bytes_sent = 0
                    last_send = now
                    close_deadline = 0.0
                    last_io = now
                    totals["accepted"] += 1
            if client is None:
                continue
            try:
                if client in readable:
                    chunk = client.recv(8192)
                    if not chunk:
                        raise EOFError("client_eof")
                    incoming.extend(chunk)
                    last_io = now
                    if len(incoming) > 2 * MAX_REQUEST:
                        raise ValueError("client_input_limit")
                    for _ in range(8):
                        request = take_request(incoming)
                        if request is None:
                            break
                        data = session.handle(request, now, enabled)
                        if not session.partial_message_started:
                            pending.append(memoryview(data))
                            queued += len(data)
                            peak_queued = max(peak_queued, queued)
                        else:
                            # Do not accidentally fill the unfinished drip body
                            # with an RTSP response. Requests are still consumed.
                            session.counters["suppressed_control_responses"] += 1
                        if session.closing:
                            close_deadline = now + 2
                            break
                for packet in session.due(now):
                    pending.append(memoryview(packet))
                    queued += len(packet)
                    peak_queued = max(peak_queued, queued)
                if queued > MAX_QUEUE:
                    totals["backpressure"] += 1
                    raise ValueError("output_backpressure")
                if client in writable and pending:
                    count = client.send(pending[0])
                    if count <= 0:
                        raise EOFError("send_eof")
                    last_io = now
                    last_send = now
                    totals["bytes_sent"] += count
                    session_bytes_sent += count
                    queued -= count
                    if count == len(pending[0]):
                        pending.popleft()
                    else:
                        pending[0] = pending[0][count:]
                if session.closing and not pending:
                    raise EOFError("teardown_complete")
            except BlockingIOError:
                pass
            except (OSError, ValueError, EOFError, UnicodeError) as failure:
                if not isinstance(failure, EOFError):
                    totals["client_error"] += 1
                client.close()
                client = None
                totals["closed"] += 1
                totals["sessions"].append(session_snapshot(now, session_close_reason(failure)))
                if len(totals["sessions"]) >= 32:
                    break
    finally:
        if client is not None:
            client.close()
            totals["closed"] += 1
            reason = "server_stopped" if stopped else ("server_lifetime" if time.monotonic() >= end else "server_error")
            totals["sessions"].append(session_snapshot(time.monotonic(), reason))
            client = None
        listener.close()
        running = False
        for sig, handler in previous.items():
            signal.signal(sig, handler)
        observations.publish(snapshot(time.monotonic()))
    return totals


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8554)
    parser.add_argument("--seconds", type=int, default=180)
    parser.add_argument("--auth", choices=("none", "basic", "digest"), default="digest")
    parser.add_argument("--fault-mode", choices=FAULT_MODES, default="normal")
    parser.add_argument("--stats", type=Path, help="New host-only public counter JSON; never a control input")
    parser.add_argument("--media", type=Path, default=HERE / "media")
    parser.add_argument("--state", type=Path, help='Optional ≤256B JSON file {"enabled":true|false}')
    args = parser.parse_args()
    media = Media.load(args.media)
    result = serve(media, args.bind, args.port, args.seconds, args.auth, args.state,
                   args.fault_mode, args.stats)
    print(json.dumps({"fixture_stopped": True, "counters": result}), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
