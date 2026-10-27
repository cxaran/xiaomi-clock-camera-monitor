import importlib.util
import io
import os
from pathlib import Path
import tarfile
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("kernel_builder", Path(__file__).with_name("build_kernel.py"))
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


class KernelArchiveChecks(unittest.TestCase):
    @unittest.skipIf(os.name == "nt", "Symlink extraction requires Linux/WSL2")
    def test_valid_symlink_spelling_preserved(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = root / "source.tar.gz"
            with tarfile.open(archive, "w:gz") as bundle:
                file = tarfile.TarInfo("a/file")
                file.size = 3
                file.mode = 0o644
                bundle.addfile(file, io.BytesIO(b"abc"))
                link = tarfile.TarInfo("a/link")
                link.type = tarfile.SYMTYPE
                link.linkname = ".././a/file"
                bundle.addfile(link)
            builder.safe_extract(archive, root / "source")
            self.assertEqual(os.readlink(root / "source/a/link"), ".././a/file")
            self.assertEqual((root / "source/a/link").read_bytes(), b"abc")

    def test_traversal_is_rejected_before_extraction(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = root / "source.tar.gz"
            with tarfile.open(archive, "w:gz") as bundle:
                valid = tarfile.TarInfo("valid")
                valid.size = 1
                bundle.addfile(valid, io.BytesIO(b"a"))
                bad = tarfile.TarInfo("../escape")
                bad.size = 1
                bundle.addfile(bad, io.BytesIO(b"b"))
            with self.assertRaises(tarfile.FilterError):
                builder.safe_extract(archive, root / "source")
            self.assertFalse((root / "source/valid").exists())
            self.assertFalse((root / "escape").exists())

    def test_git_tree_ignores_no_files(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / ".gitignore").write_bytes(b"*.orig\n")
            before = builder.source_tree(root)
            (root / "retained.orig").write_bytes(b"source\r\n")
            self.assertNotEqual(builder.source_tree(root), before)
            (root / "retained.orig").write_bytes(b"source\n")
            self.assertNotEqual(builder.source_tree(root), before)


if __name__ == "__main__":
    unittest.main()
