#!/usr/bin/env python3
"""Self-check for scripts/term-check: one throwaway repo per case."""
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parent / "term-check"
GLOSSARY = """# Ctx

## Language

**Board**:
The tool.
_Avoid_: whiteboard?, dashboard, 看板

**AC**:
One criterion.
_Avoid_: test case
"""


def run(files):
    with tempfile.TemporaryDirectory() as tmp:
        for rel, text in files.items():
            p = pathlib.Path(tmp, rel)
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text)
        subprocess.run(["git", "init", "-q"], cwd=tmp, check=True)
        subprocess.run(["git", "add", "-A"], cwd=tmp, check=True)
        r = subprocess.run([sys.executable, SCRIPT], cwd=tmp, capture_output=True, text=True)
        return r.returncode, r.stdout + r.stderr


class TermCheck(unittest.TestCase):
    def test_skips_without_glossary(self):
        code, out = run({"README.md": "a dashboard\n"})
        self.assertEqual(code, 0)
        self.assertIn("skipped", out)

    def test_hard_hit_fails_soft_reports_allow_exempts(self):
        files = {"CONTEXT.md": GLOSSARY,
                 "docs/a.md": "Open the dashboard.\nThe whiteboard shows it.\nA test case here.\nA dashboard <!-- term-check: allow -->\n看板上\n",
                 "src/x.py": "# the dashboard\ndashboard = 1  # not a comment hit: identifier line\n"}
        code, out = run(files)
        self.assertEqual(code, 1, out)
        self.assertIn('docs/a.md:1 "dashboard" -> use "Board"', out)
        self.assertIn('docs/a.md:2 "whiteboard" -> use "Board"? (soft)', out)
        self.assertIn('docs/a.md:3 "test case" -> use "AC"', out)
        self.assertNotIn("docs/a.md:4", out)
        self.assertIn('docs/a.md:5 "看板" -> use "Board"', out)
        self.assertIn('src/x.py:1 "dashboard"', out)
        self.assertIn("4 hard, 1 soft", out)

    def test_file_names_and_identifiers_do_not_hit(self):
        files = {"CONTEXT.md": GLOSSARY,
                 "docs/a.md": "See whiteboard.config.yaml and .whiteboard/ and my_dashboard_x.\n"}
        code, out = run(files)
        self.assertEqual(code, 0, out)
        self.assertIn("0 hard, 0 soft", out)


if __name__ == "__main__":
    unittest.main()
