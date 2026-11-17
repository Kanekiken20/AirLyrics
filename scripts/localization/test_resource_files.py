"""Exercise the checker against Android's merged resource-file namespace."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

CHECKER = Path(__file__).with_name('check.py').resolve()
LOCALES = ('values', 'values-es', 'values-zh-rCN', 'values-zh-rTW')


class ResourceFileChecksTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for locale in LOCALES:
            path = self.root / 'app/src/main/res' / locale
            path.mkdir(parents=True)
            (path / 'strings.xml').write_text('<resources><string name="ui_main">Main</string></resources>')
            (path / 'native_typography.xml').write_text('<resources><string name="ui_font">Font</string></resources>')

    def run_check(self):
        return subprocess.run([sys.executable, '-B', str(CHECKER)], cwd=self.root,
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def test_separate_files_are_merged_and_nontranslatable_samples_need_no_locale_copy(self):
        default = self.root / 'app/src/main/res/values/native_typography.xml'
        default.write_text('<resources><string name="ui_font">Font</string>'
                           '<string name="ui_sample" translatable="false">Sample</string></resources>')
        result = self.run_check()
        self.assertEqual(0, result.returncode, result.stdout)

    def test_duplicate_across_files_is_rejected(self):
        (self.root / 'app/src/main/res/values/another.xml').write_text(
            '<resources><string name="ui_font">Duplicate</string></resources>')
        result = self.run_check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("duplicate string names: ['ui_font']", result.stdout)

    def test_missing_translation_in_separate_file_is_rejected(self):
        (self.root / 'app/src/main/res/values-es/native_typography.xml').unlink()
        result = self.run_check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("missing keys from default resources: ['ui_font']", result.stdout)

    def test_placeholders_are_checked_in_separate_files(self):
        (self.root / 'app/src/main/res/values-es/native_typography.xml').write_text(
            '<resources><string name="ui_font">Font %1$s</string></resources>')
        result = self.run_check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('placeholder mismatch for ui_font', result.stdout)


if __name__ == '__main__':
    unittest.main()
