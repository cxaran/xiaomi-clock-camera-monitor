"""Host-only fixtures. No sockets, subprocesses, Android, or real camera data."""
import base64
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

import fixture_server as fixture


class FixtureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.media = fixture.Media.load(Path(__file__).resolve().parent / "media")

    def request(self, method, path="/stream2", **headers):
        uri = "rtsp://10.0.2.2:8554" + path
        return fixture.Request(method, uri, "7", headers)

    def setup(self, session):
        for track, first in ((0, 0), (1, 2)):
            headers = {"transport": f"RTP/AVP/TCP;unicast;interleaved={first}-{first+1}"}
            if track:
                headers["session"] = fixture.SESSION
            reply = session.handle(self.request("SETUP", "/stream2/trackID=" + str(track), **headers), 0)
            self.assertTrue(reply.startswith(b"RTSP/1.0 200"))

    def test_generated_media_and_sdp(self):
        self.assertEqual(len(self.media.access_units), 60)
        self.assertEqual(len(self.media.audio), 32000)
        self.assertEqual(self.media.sps[0] & 31, 7)
        self.assertEqual(self.media.pps[0] & 31, 8)
        self.assertEqual(self.media.sps[1], 66)  # baseline profile_idc
        sdp = self.media.sdp()
        self.assertIn(b"H264/90000", sdp)
        self.assertIn(b"PCMA/8000/1", sdp)
        self.assertIn(base64.b64encode(self.media.sps), sdp)
        self.assertEqual(sum(any(n[0] & 31 == 5 for n in au) for au in self.media.access_units), 4)

    def test_fu_packet_roundtrip(self):
        original = b"\x65" + bytes(range(256)) * 20
        parts = fixture.h264_payloads(original)
        self.assertTrue(all(len(p) <= 1100 for p in parts))
        self.assertEqual(parts[0][1] & 0x80, 0x80)
        self.assertEqual(parts[-1][1] & 0x40, 0x40)
        reconstructed = bytes(((parts[0][0] & 0xE0) | (parts[0][1] & 31),)) + b"".join(p[2:] for p in parts)
        self.assertEqual(original, reconstructed)
        frame = fixture.interleaved(2, 8, 65536, 0x100000000, 1, True, b"abc")
        self.assertEqual(frame[:4], b"$\x02\x00\x0f")
        self.assertEqual(struct.unpack("!BBHII", frame[4:16]), (0x80, 136, 0, 0, 1))

    def test_play_pause_resume_teardown_and_markers(self):
        s = fixture.FixtureSession(self.media, "none")
        self.setup(s)
        play = self.request("PLAY", session=fixture.SESSION)
        self.assertTrue(s.handle(play, 0).startswith(b"RTSP/1.0 200"))
        packets = s.due(0)
        video = [p for p in packets if p[1] == 0]
        audio = [p for p in packets if p[1] == 2]
        self.assertTrue(video and audio)
        self.assertEqual(sum(bool(p[5] & 128) for p in video), 1)
        self.assertTrue(video[-1][5] & 128)
        self.assertEqual(len(audio[0]) - 16, 160)
        self.assertEqual(s.counters["video_access_units"], 1)
        self.assertTrue(s.handle(self.request("PAUSE", session=fixture.SESSION), 0.01).startswith(b"RTSP/1.0 200"))
        self.assertEqual(s.due(3), [])
        self.assertTrue(s.handle(play, 3).startswith(b"RTSP/1.0 200"))
        resumed = s.due(3)
        self.assertTrue(resumed)
        self.assertTrue(any((p[16] & 31 == 5) or (p[16] & 31 == 28 and p[17] & 31 == 5) for p in resumed if p[1] == 0))
        self.assertTrue(s.handle(self.request("TEARDOWN", session=fixture.SESSION), 4).startswith(b"RTSP/1.0 200"))
        self.assertTrue(s.closing)
        self.assertEqual(s.due(4), [])

    def test_digest_and_basic_public_only(self):
        s = fixture.FixtureSession(self.media, "digest")
        request = self.request("DESCRIBE")
        self.assertTrue(s.handle(request, 0).startswith(b"RTSP/1.0 401"))
        md5 = lambda text: hashlib.md5(text.encode()).hexdigest()
        ha1 = md5(f"{fixture.PUBLIC_USER}:{fixture.REALM}:{fixture.PUBLIC_PASSWORD}")
        ha2 = md5("DESCRIBE:" + request.uri)
        result = md5(f"{ha1}:{s.nonce}:00000001:testnonce:auth:{ha2}")
        header = (f'Digest username="{fixture.PUBLIC_USER}", realm="{fixture.REALM}", nonce="{s.nonce}", '
                  f'uri="{request.uri}", qop=auth, nc=00000001, cnonce="testnonce", response="{result}"')
        authenticated = self.request("DESCRIBE", authorization=header)
        self.assertTrue(s.handle(authenticated, 0).startswith(b"RTSP/1.0 200"))
        self.assertFalse(s.authorized(authenticated))  # replay nc
        basic = fixture.FixtureSession(self.media, "basic")
        auth = "Basic " + base64.b64encode(b"fixture:fixture-public").decode()
        self.assertTrue(basic.authorized(self.request("DESCRIBE", authorization=auth)))
        self.assertFalse(basic.authorized(request))

    def test_partial_requests_interleaved_and_bad_headers(self):
        data = bytearray(b"$\x01\x00\x02\x80\xc9OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r")
        self.assertIsNone(fixture.take_request(data))
        data.extend(b"\n")
        request = fixture.take_request(data)
        self.assertEqual((request.method, request.uri, request.cseq), ("OPTIONS", "*", "1"))
        self.assertEqual(data, bytearray())
        with self.assertRaises(ValueError):
            fixture.take_request(bytearray(b"OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nCSeq: 2\r\n\r\n"))
        with self.assertRaises(ValueError):
            fixture.take_request(bytearray(b"A" * (fixture.MAX_REQUEST + 1)))
        with self.assertRaises(ValueError):
            fixture.take_request(bytearray(b"DESCRIBE rtsp://10.0.2.2/stream2\nInjected RTSP/1.0\r\nCSeq: 1\r\n\r\n"))
        self.assertEqual(fixture.validate_bind("127.0.0.1"), "127.0.0.1")
        self.assertEqual(fixture.validate_bind("10.203.11.1"), "10.203.11.1")
        for address in ("0.0.0.0", "8.8.8.8", "224.0.0.251"):
            with self.assertRaises(ValueError):
                fixture.validate_bind(address)

    def test_session_overlap_offline_and_deadline(self):
        s = fixture.FixtureSession(self.media, "none")
        self.setup(s)
        overlap = self.request("SETUP", "/stream2/trackID=1", transport="RTP/AVP/TCP;unicast;interleaved=1-2", session=fixture.SESSION)
        self.assertTrue(s.handle(overlap, 0).startswith(b"RTSP/1.0 461"))
        self.assertTrue(s.handle(self.request("GET_PARAMETER", session="wrong"), 0).startswith(b"RTSP/1.0 454"))
        s.handle(self.request("PLAY", session=fixture.SESSION), 0)
        with self.assertRaises(ValueError):
            s.due(2)  # fail instead of burst/unbounded catch-up
        self.assertTrue(s.handle(self.request("DESCRIBE"), 0, False).startswith(b"RTSP/1.0 503"))
        self.assertTrue(s.closing)

    def test_fault_modes_preserve_auth_and_normal_default(self):
        for mode in fixture.FAULT_MODES:
            session = fixture.FixtureSession(self.media, "digest", mode)
            self.assertTrue(session.handle(self.request("DESCRIBE"), 0).startswith(b"RTSP/1.0 401"))
        implicit = fixture.FixtureSession(self.media, "none")
        explicit = fixture.FixtureSession(self.media, "none", "normal")
        for session in (implicit, explicit):
            self.setup(session)
            session.handle(self.request("PLAY", session=fixture.SESSION), 0)
        for tick in range(420):
            self.assertEqual(implicit.due(tick / 100), explicit.due(tick / 100))
        self.assertGreater(implicit.counters["video_access_units"], fixture.FAULT_AFTER_ACCESS_UNITS)
        self.assertFalse(implicit.fault_active)
        fixture.validate_server_options("127.0.0.1", 18556, 1800, "digest", "normal")
        for seconds, mode in ((1801, "normal"), (0, "normal"), (1800, "invented")):
            with self.assertRaises(ValueError):
                fixture.validate_server_options("127.0.0.1", 18556, seconds, "digest", mode)

    def fault_session(self, mode):
        session = fixture.FixtureSession(self.media, "none", mode)
        self.setup(session)
        session.handle(self.request("PLAY", session=fixture.SESSION), 0)
        return session

    def test_video_stall_keeps_audio_and_tcp_session_progress(self):
        session = self.fault_session("video-stall")
        last_video, last_audio = None, None
        for tick in range(1201):
            now = tick / 100
            packets = session.due(now)
            if any(p[1] == 0 for p in packets):
                last_video = now
            if any(p[1] == 2 for p in packets):
                last_audio = now
        self.assertEqual(session.counters["video_access_units"], fixture.FAULT_AFTER_ACCESS_UNITS)
        self.assertGreaterEqual(session.counters["audio_packets"], 550)
        self.assertLess(last_video, 2.1)
        self.assertGreater(last_audio, 11.9)
        self.assertFalse(session.closing)
        self.assertFalse(session.partial_message_started)
        self.assertEqual(session.counters["fault_started"], 1)
        keepalive = session.handle(self.request("GET_PARAMETER", session=fixture.SESSION), 12.1)
        self.assertTrue(keepalive.startswith(b"RTSP/1.0 200"))

    def test_drip_exceeds_absolute_budget_without_completing_or_audio_interleaving(self):
        session = self.fault_session("interleaved-drip")
        fault_output, at = [], []
        for tick in range(1601):
            now = tick / 100
            packets = session.due(now)
            if session.partial_message_started and packets:
                fault_output.extend(packets)
                at.extend([now] * len(packets))
        self.assertEqual(session.counters["video_access_units"], fixture.FAULT_AFTER_ACCESS_UNITS)
        self.assertEqual(fault_output[0][:4], b"$\x00\x04\x00")
        self.assertEqual(fault_output[0][4:], b"\x80")
        self.assertTrue(all(p == b"\x00" for p in fault_output[1:]))
        body = b"".join(fault_output)[4:]
        self.assertLess(len(body), fixture.DRIP_PAYLOAD_BYTES)
        self.assertGreater(at[-1] - at[0], 8)
        self.assertLess(max(b - a for a, b in zip(at, at[1:])), 1.02)
        self.assertLess(at[-1] - at[0], 12.1)
        self.assertFalse(session.closing)
        self.assertEqual(session.counters["fault_drip_bytes"], len(body))

    def test_public_stats_atomic_and_do_not_remove_foreign_temporary_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "public.json"
            stats = fixture.StatsFile(path)
            stats.publish({"active": True, "accepted": 1, "closed": 0})
            self.assertEqual(json.loads(path.read_text())["active"], True)
            with self.assertRaises(FileExistsError):
                fixture.StatsFile(path)
            temp = path.with_name(path.name + ".tmp")
            temp.write_bytes(b"previous-owned-by-someone-else")
            with self.assertRaises(FileExistsError):
                stats.publish({"active": False})
            self.assertEqual(temp.read_bytes(), b"previous-owned-by-someone-else")
            self.assertTrue(json.loads(path.read_text())["active"])

    def run_fake_server(self, mode, request_methods, seconds=4, send_limit=65536, late_control=False):
        """Exercise the real event loop using only in-memory socket doubles."""
        class Clock:
            value = 100.0
            def now(self): return self.value
        clock = Clock()
        uri = "rtsp://127.0.0.1:18556/stream2"
        requests = []
        for index, method in enumerate(request_methods):
            if method == "SETUP_VIDEO":
                first, path, header = "SETUP", uri + "/trackID=0", "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n"
            elif method == "SETUP_AUDIO":
                first, path, header = "SETUP", uri + "/trackID=1", "Transport: RTP/AVP/TCP;unicast;interleaved=2-3\r\nSession: " + fixture.SESSION + "\r\n"
            else:
                first, path, header = method, uri, "Session: " + fixture.SESSION + "\r\n"
            requests.append(f"{first} {path} RTSP/1.0\r\nCSeq: {index+1}\r\n{header}\r\n".encode())
        class Client:
            closed = False
            sent = bytearray()
            incoming = [b"".join(requests)]
            def setblocking(self, *_): pass
            def setsockopt(self, *_): pass
            def recv(self, _): return self.incoming.pop(0)
            def send(self, data):
                size = min(send_limit, len(data)); self.sent.extend(data[:size]); return size
            def close(self): self.closed = True
        client = Client()
        class Listener:
            accepted = closed = False
            def bind(self, *_): pass
            def listen(self, *_): pass
            def setblocking(self, *_): pass
            def accept(self): self.accepted = True; return client, ("127.0.0.1", 1)
            def close(self): self.closed = True
        listener = Listener()
        late_sent = False
        def select(readers, writers, _errors, timeout):
            nonlocal late_sent
            clock.value += .005
            if late_control and not late_sent and clock.value >= 102.25:
                late_sent = True
                client.incoming.append((f"GET_PARAMETER {uri} RTSP/1.0\r\nCSeq: 99\r\nSession: {fixture.SESSION}\r\n\r\n").encode())
            ready = []
            if listener in readers and not listener.accepted:
                ready.append(listener)
            if client in readers and client.incoming:
                ready.append(client)
            return ready, list(writers), []
        with tempfile.TemporaryDirectory() as directory:
            stats = Path(directory) / "stats.json"
            with patch.object(fixture.socket, "socket", return_value=listener), \
                    patch.object(fixture.select, "select", side_effect=select), \
                    patch.object(fixture.time, "monotonic", side_effect=clock.now), \
                    patch.object(fixture.signal, "signal"), patch("builtins.print"):
                totals = fixture.serve(self.media, "127.0.0.1", 18556, seconds, "none", None, mode, stats)
            snapshot = json.loads(stats.read_text())
        self.assertTrue(listener.closed and client.closed)
        self.assertEqual((totals["accepted"], totals["closed"]), (1, 1))
        self.assertFalse(snapshot["active"])
        self.assertFalse(snapshot["running"])
        self.assertIsNone(snapshot["active_session"])
        self.assertGreaterEqual(snapshot["elapsed_ms"], seconds * 1000)
        self.assertLess(snapshot["elapsed_ms"], seconds * 1000 + 20)
        return totals, snapshot, bytes(client.sent)

    def test_event_loop_drains_teardown_reply_with_partial_writes(self):
        totals, snapshot, wire = self.run_fake_server("normal", ["SETUP_VIDEO", "SETUP_AUDIO", "PLAY", "TEARDOWN"], seconds=3, send_limit=7)
        self.assertIn(b"CSeq: 4\r\n", wire)
        self.assertTrue(wire.endswith(b"Content-Length: 0\r\n\r\n"))
        self.assertEqual(wire.count(b"RTSP/1.0 200"), 4)
        self.assertEqual(totals["client_error"], 0)
        self.assertEqual(snapshot["sessions"][0]["teardown"], 1)

    def test_event_loop_lifetime_closes_during_unfinished_drip(self):
        totals, snapshot, wire = self.run_fake_server("interleaved-drip", ["SETUP_VIDEO", "SETUP_AUDIO", "PLAY"])
        session = snapshot["sessions"][0]
        self.assertTrue(session["partial_message_started"])
        self.assertEqual(session["video_access_units"], fixture.FAULT_AFTER_ACCESS_UNITS)
        self.assertGreaterEqual(session["fault_drip_bytes"], 2)
        self.assertLess(session["fault_drip_bytes"], fixture.DRIP_PAYLOAD_BYTES)
        self.assertEqual(totals["client_error"], 0)
        self.assertIn(b"$\x00\x04\x00\x80", wire)
        self.assertIsNotNone(session["stopped_monotonic_ms"])

    def test_event_loop_consumes_control_without_filling_unfinished_drip(self):
        totals, snapshot, wire = self.run_fake_server("interleaved-drip", ["SETUP_VIDEO", "SETUP_AUDIO", "PLAY"], late_control=True)
        self.assertEqual(snapshot["sessions"][0]["requests"], 4)
        self.assertEqual(snapshot["sessions"][0]["suppressed_control_responses"], 1)
        self.assertNotIn(b"CSeq: 99", wire)
        self.assertEqual(totals["client_error"], 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
