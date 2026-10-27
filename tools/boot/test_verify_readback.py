import importlib.util
from pathlib import Path
import struct
import unittest

SPEC = importlib.util.spec_from_file_location("readback", Path(__file__).with_name("verify_readback.py"))
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class ReadbackChecks(unittest.TestCase):
    def setUp(self):
        self.boot = b"ANDROID!" + bytes(16 * 1024 * 1024 - 8)
        self.stock = bytearray(256 * 1024)
        struct.pack_into("<7I", self.stock, 0, 0x4D4D4D4D, 4, 60, 1, 1, 0, 0x45454545)
        self.unlocked = bytearray(self.stock)
        struct.pack_into("<I", self.unlocked, 12, 3)

    def test_exact_boot_and_sector_only_unlock(self):
        result = module.verify(self.boot, self.boot, self.stock, self.unlocked)
        self.assertTrue(result["boot_readback_matches"])
        self.assertFalse(result["seccfg_hardware_authentication_verified"])

    def test_boot_mismatch_rejected(self):
        with self.assertRaises(ValueError):
            module.verify(self.boot, self.boot[:-1] + b"x", self.stock, self.unlocked)

    def test_locked_readback_rejected(self):
        with self.assertRaises(ValueError):
            module.verify(self.boot, self.boot, self.stock, self.stock)

    def test_tail_damage_rejected(self):
        self.unlocked[4096] = 1
        with self.assertRaises(ValueError):
            module.verify(self.boot, self.boot, self.stock, self.unlocked)

    def test_unknown_layout_rejected(self):
        self.stock[4] = 5
        with self.assertRaises(ValueError):
            module.verify(self.boot, self.boot, self.stock, self.unlocked)


if __name__ == "__main__":
    unittest.main()
