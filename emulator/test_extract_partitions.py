"""Independent synthetic GPT/liblp/sparse fixtures; no device or OEM firmware."""
import hashlib
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

SPEC = importlib.util.spec_from_file_location("extract", Path(__file__).with_name("extract_partitions.py"))
reader = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reader)


def liblp_fixture():
    raw = bytearray(65536)
    geo = bytearray(struct.pack("<II32sIII", 0x616C4467, 52, bytes(32), 4096, 2, 4096))
    geo[8:40] = hashlib.sha256(geo).digest()
    raw[4096:4096 + 52] = geo
    raw[8192:8192 + 52] = geo
    partitions = struct.pack("<36sIIII", b"system", 1, 0, 2, 0)
    partitions += struct.pack("<36sIIII", b"vendor", 1, 2, 1, 0)
    extents = struct.pack("<QIQI", 3, 0, 64, 0)
    extents += struct.pack("<QIQI", 1, 1, 0, 0)
    extents += struct.pack("<QIQI", 2, 0, 68, 0)
    groups = struct.pack("<36sIQ", b"default", 0, 0)
    devices = struct.pack("<QIIQ36sI", 64, 4096, 0, len(raw), b"super", 0)
    tables = partitions + extents + groups + devices
    header = bytearray(struct.pack("<IHHI32sI32s", 0x414C5030, 10, 0, 128, bytes(32), len(tables),
                                   hashlib.sha256(tables).digest()))
    for start, count, size in ((0, 2, 52), (len(partitions), 3, 24),
                               (len(partitions) + len(extents), 1, 48),
                               (len(partitions) + len(extents) + len(groups), 1, 64)):
        header.extend(struct.pack("<III", start, count, size))
    header[12:44] = hashlib.sha256(header).digest()
    for offset in (12288, 16384, 20480, 24576):
        raw[offset:offset + len(header + tables)] = header + tables
    raw[32768:34304] = bytes(i % 251 for i in range(1536))
    raw[34816:35840] = b"VNDR" * 256
    return bytes(raw)


def gpt_fixture():
    raw = bytearray(131072)
    table = bytearray(128 * 4)
    table[:16] = bytes.fromhex("a2a0d0ebe5b9334487c068b6b72699c7")
    table[16:32] = bytes.fromhex("00112233445566778899aabbccddeeff")
    struct.pack_into("<QQQ", table, 32, 64, 127, 0)
    name = "super".encode("utf-16-le")
    table[56:56 + len(name)] = name
    header = bytearray(512)
    header[:8] = b"EFI PART"
    struct.pack_into("<IIIIQQQQ16sQIII", header, 8, 0x10000, 92, 0, 0, 1, 255,
                     34, 222, bytes(16), 2, 4, 128, zlib.crc32(table))
    struct.pack_into("<I", header, 16, zlib.crc32(header[:92]))
    raw[512:1024] = header
    raw[1024:1536] = table
    raw[32768:65536] = b"GPT!" * 8192
    return bytes(raw)


class ExtractionChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def image(self, raw, name="input.img"):
        path = self.root / name
        path.write_bytes(raw)
        image = reader.Image(path, hashlib.sha256(raw).hexdigest())
        self.addCleanup(image.close)
        return image

    def test_gpt_crc_range_and_exact_extract(self):
        raw = gpt_fixture()
        image = self.image(raw)
        metadata = reader.gpt(image, 512)
        self.assertTrue(metadata["primary_header_crc_ok"])
        self.assertTrue(metadata["entries_crc_ok"])
        outputs = reader.extract(image, metadata, self.root / "output", ["super"])
        self.assertEqual(Path(outputs[0]["path"]).read_bytes(), raw[32768:65536])
        image.unchanged()

    def test_gpt_corrupt_header_rejected(self):
        raw = bytearray(gpt_fixture())
        raw[512 + 24] ^= 1
        with self.assertRaises(ValueError):
            reader.gpt(self.image(raw), 512)

    def test_gpt_corrupt_entry_table_rejected(self):
        raw = bytearray(gpt_fixture())
        raw[1024 + 90] ^= 1
        with self.assertRaises(ValueError):
            reader.gpt(self.image(raw), 512)

    def test_liblp_multiple_extents_and_zero_exact_non4k_length(self):
        raw = liblp_fixture()
        image = self.image(raw)
        metadata = reader.super_metadata(image, 0)
        self.assertEqual([p["size"] for p in metadata["partitions"]], [2048, 1024])
        reader.extract(image, metadata, self.root / "output", ["system", "vendor"])
        self.assertEqual((self.root / "output/system.img").read_bytes(), raw[32768:34304] + bytes(512))
        self.assertEqual((self.root / "output/vendor.img").read_bytes(), b"VNDR" * 256)

    def test_liblp_primary_corruption_falls_back_explicitly(self):
        raw = bytearray(liblp_fixture())
        raw[12288] ^= 1
        metadata = reader.super_metadata(self.image(raw), 0)
        self.assertEqual(metadata["metadata_copy"], "backup")
        self.assertTrue(metadata["fallback_errors"])

    def test_liblp_both_copies_bad_rejected(self):
        raw = bytearray(liblp_fixture())
        raw[12288] ^= 1
        raw[20480] ^= 1
        with self.assertRaises(ValueError):
            reader.super_metadata(self.image(raw), 0)

    def test_wrong_hash_rejected(self):
        path = self.root / "raw.img"
        path.write_bytes(gpt_fixture())
        with self.assertRaises(ValueError):
            reader.Image(path, "0" * 64)

    def test_sparse_nonzero_fill_and_cross_boundary(self):
        raw = struct.pack("<IHHHHIIII", 0xED26FF3A, 1, 0, 28, 12, 4096, 3, 3, 0)
        raw += struct.pack("<HHII", 0xCAC1, 0, 1, 4108) + b"A" * 4096
        raw += struct.pack("<HHII", 0xCAC2, 0, 1, 16) + b"FILL"
        raw += struct.pack("<HHII", 0xCAC3, 0, 1, 12)
        image = self.image(raw)
        self.assertEqual(image.read(0, 12288), b"A" * 4096 + b"FILL" * 1024 + bytes(4096))
        self.assertEqual(image.read(4097, 9), b"ILLFILLFI")

    def test_existing_output_not_overwritten(self):
        image = self.image(liblp_fixture())
        metadata = reader.super_metadata(image, 0)
        out = self.root / "already"
        out.mkdir()
        with self.assertRaises(FileExistsError):
            reader.extract(image, metadata, out, ["system"])


if __name__ == "__main__":
    unittest.main()
