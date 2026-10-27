"""Host-only rejection and parsing checks. No Android/device tools invoked."""
import base64
import importlib.util
from pathlib import Path
import struct
import unittest

SPEC = importlib.util.spec_from_file_location("boot_builder", Path(__file__).with_name("build_boot.py"))
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


def member(name, data=b"", mode=0o100644, ino=1):
    fields = (ino, mode, 0, 0, 1, 0, len(data), 0, 0, 0, 0, len(name) + 1, 0)
    raw = b"070701" + b"".join(f"{v:08x}".encode() for v in fields) + name.encode() + b"\0"
    raw += b"\0" * (-len(raw) % 4)
    raw += data
    return raw + b"\0" * (-len(raw) % 4)


class BootChecks(unittest.TestCase):
    def key(self):
        blob = struct.pack("<I", 64) + bytes(516) + struct.pack("<I", 65537)
        return base64.b64encode(blob)

    def test_public_comment_is_removed(self):
        self.assertEqual(builder.public_key(self.key() + b" local-comment\n"), self.key() + b"\n")

    def test_private_pem_rejected(self):
        with self.assertRaises(ValueError):
            builder.public_key(b"-----BEGIN PRIVATE KEY-----\n")

    def test_multiple_keys_rejected(self):
        with self.assertRaises(ValueError):
            builder.public_key(self.key() + b"\n" + self.key() + b"\n")

    def test_non_rsa2048_structure_rejected(self):
        raw = bytearray(base64.b64decode(self.key()))
        struct.pack_into("<I", raw, 0, 128)
        with self.assertRaises(ValueError):
            builder.public_key(base64.b64encode(raw))

    def test_cpio_preserves_content_and_mode(self):
        raw = member("init", b"init", 0o100750) + member("TRAILER!!!")
        parsed = builder.cpio(raw)
        self.assertEqual(parsed["init"]["data"], b"init")
        self.assertEqual(parsed["init"]["metadata"][0], 0o100750)

    def test_cpio_inode_can_be_reserialized(self):
        one = builder.cpio(member("init", b"same", ino=1) + member("TRAILER!!!"))
        two = builder.cpio(member("init", b"same", ino=100) + member("TRAILER!!!"))
        self.assertEqual(one, two)

    def test_cpio_duplicate_rejected(self):
        with self.assertRaises(ValueError):
            builder.cpio(member("init") + member("init") + member("TRAILER!!!"))

    def test_cpio_truncation_rejected(self):
        with self.assertRaises(ValueError):
            builder.cpio(member("init", b"payload")[:-3])

    def test_helper_matches_reviewed_source(self):
        helper = Path(__file__).with_name("x04g-adb-usb.sh").read_bytes()
        self.assertEqual(builder.sha(helper), builder.HELPER_SHA256)
        self.assertNotIn(b"\r", helper)
        self.assertNotIn(b"settings", helper)
        self.assertIn(b'ro.adb.secure', helper)
        self.assertIn(b'Enforcing', helper)


if __name__ == "__main__":
    unittest.main()
