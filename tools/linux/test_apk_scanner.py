import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import apk_scanner


class FakeInstruction:
    def __init__(self, name, output):
        self.name = name
        self.output = output

    def get_name(self):
        return self.name

    def get_output(self):
        return self.output


class LinuxScannerTests(unittest.TestCase):
    def test_instruction_text_keeps_opcode_and_operands(self):
        ins = FakeInstruction("const/4", "v0, 0x1")
        self.assertEqual(apk_scanner.instruction_text(ins), "const/4 v0, 0x1")

    def test_instruction_text_handles_no_operands(self):
        ins = FakeInstruction("return-void", "")
        self.assertEqual(apk_scanner.instruction_text(ins), "return-void")

    def test_missing_apk_fails_before_optional_dependency_import(self):
        with self.assertRaises(FileNotFoundError):
            apk_scanner.scan_apk(Path("/does/not/exist.apk"), None, None, 20, False)

    def test_non_zip_apk_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk = Path(tmp) / "bad.apk"
            apk.write_text("not an apk", encoding="utf-8")
            with self.assertRaises(ValueError):
                apk_scanner.scan_apk(apk, None, None, 20, False)


if __name__ == "__main__":
    unittest.main()
