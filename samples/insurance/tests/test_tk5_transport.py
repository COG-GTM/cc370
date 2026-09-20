"""Binary framing controls independent of the required real-guest round trips."""

from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))

from tk5 import Dataset, read_tape, write_tape  # noqa: E402
from tk5_decks import cards  # noqa: E402
from tk5_job import execute  # noqa: E402


class TransportTests(unittest.TestCase):
    def test_aws_roundtrip_preserves_empty_files_and_blank_records(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tape.aws"
            mixed = bytes(range(128)) + bytes(128)
            write_tape(path, [[mixed], [], [b"\x40" * 96]])
            self.assertEqual(read_tape(path, 3, [128, 40, 96]),
                             [mixed, b"", b"\x40" * 96])
            with self.assertRaises(FileExistsError):
                write_tape(path, [])

    def test_aws_rejects_incomplete_headers_data_flags_and_record_boundaries(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tape.aws"
            mark = struct.pack("<HHBB", 0, 0, 0x40, 0)
            malformed = [
                b"\0", struct.pack("<HHBB", 128, 0, 0xA0, 0) + bytes(127),
                struct.pack("<HHBB", 0, 1, 0x40, 0) + mark,
                struct.pack("<HHBB", 0, 0, 0x40, 1) + mark,
                struct.pack("<HHBB", 0, 0, 0x80, 0) + mark,
            ]
            for raw in malformed:
                path.write_bytes(raw)
                with self.subTest(raw=raw.hex()), self.assertRaises(ValueError):
                    read_tape(path, 1, [128])
            path.unlink()
            write_tape(path, [[bytes(64), bytes(64)]])
            with self.assertRaisesRegex(ValueError, "partial fixed record"):
                read_tape(path, 1, [128])

    def test_dataset_rejects_partial_or_invalid_dcb(self) -> None:
        for lrecl, blocksize, data in [(128, 1280, bytes(127)), (40, 0, b""),
                                       (96, 128, b""), (0, 80, b"")]:
            with self.subTest(lrecl=lrecl, blocksize=blocksize), self.assertRaises(ValueError):
                Dataset("IBMUSER.TEST.DATA", lrecl, blocksize, data)
        with self.assertRaises(ValueError):
            Dataset("IBMUSER.TEST.DATA(BAD)INJECT", 80, 3200)

    def test_deck_comparison_excludes_only_declared_columns_and_end(self) -> None:
        txt = b"\x02" + "TXT".encode("cp037") + bytes(76)
        end = b"\x02" + "END".encode("cp037") + bytes(76)
        self.assertEqual(cards(txt + end), ([txt[:72]], [end]))
        for raw in (txt, txt + end + end, txt + end + b"\x40" * 80, (txt + end)[:-1]):
            with self.assertRaises(ValueError):
                cards(raw)

    def test_control_failure_preserves_raw_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "evidence"
            output.mkdir()
            console = root / "console.log"
            console.write_bytes(b"before\nraw\xffguest\n")
            with patch("tk5_job.control", side_effect=RuntimeError("control failed")):
                with self.assertRaisesRegex(RuntimeError, "control failed"):
                    execute("test", ["command"], output, [console], {console: 7},
                            "TESTJOB", None, 1, 0)
            self.assertEqual((output / "console.log").read_bytes(), b"raw\xffguest\n")

    def test_timeout_is_not_completion(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "evidence"
            output.mkdir()
            console = root / "console.log"
            console.write_bytes(b"job still active\n")
            self.assertFalse(execute("test", [], output, [console], {console: 0},
                                     "TESTJOB", None, 1, 0))
            self.assertEqual((output / "console.log").read_bytes(), console.read_bytes())


if __name__ == "__main__":
    unittest.main()
