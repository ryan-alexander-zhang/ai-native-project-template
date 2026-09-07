#!/usr/bin/env python3
"""Self-check for scripts/trace-check: builds a throwaway repo per case, asserts exit code and message."""
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parent / "trace-check"
SPEC = "spec-00001-pay"
PLAN = "plan-00001-pay"
REC = "record-00001-pay"
AC = "spec-00001-AC-1.1"


def sfx(ac):  # built at runtime so this file never carries a literal AC suffix
    return "__" + ac.replace("-", "_").replace(".", "_")


TEST = "test_pays" + sfx(AC)


def spec(extra_ac=""):
    return f"""---
id: {SPEC}
type: spec
status: active
---
## 4. System Requirements
- **spec-00001-FR-1** (Event) When paid, the system shall mark it.

- **spec-00001-AC-1.1** (spec-00001-FR-1)
  Given a
  When b
  Then c
{extra_ac}"""


def plan(status="resolved", implements=SPEC):
    return f"---\nid: {PLAN}\ntype: plan\nstatus: {status}\nimplements: [{implements}]\n---\n"


def record(test=TEST, evidence=""):
    return f"""---
id: {REC}
type: record
status: active
parent: {PLAN}
verifies: [{AC}]
---
| GWT / requirement id | Test | Result | Evidence |
| --- | --- | --- | --- |
| {AC} | `{test}` | pass | {evidence} |
"""


SPEC_README = "# Specs\n\n## Relations\n\n- `parent` — a prd.\n\n## Exclude\n"


def code(name=TEST):
    return f"def {name}():\n    assert True\n"


class TraceCheck(unittest.TestCase):
    def run_check(self, files, *args):
        with tempfile.TemporaryDirectory() as tmp:
            for rel, text in files.items():
                p = pathlib.Path(tmp, rel)
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(text)
            subprocess.run(["git", "init", "-q"], cwd=tmp, check=True)
            subprocess.run(["git", "add", "-A"], cwd=tmp, check=True)
            r = subprocess.run([sys.executable, SCRIPT, *args], cwd=tmp, capture_output=True, text=True)
            return r.returncode, r.stdout + r.stderr

    def base(self, **over):
        files = {
            f"docs/spec/{SPEC}.md": spec(),
            f"docs/plan/{PLAN}.md": plan(),
            f"docs/record/{REC}.md": record(),
            "tests/test_pay.py": code(),
        }
        files.update(over)
        return files

    def test_clean_repo_passes(self):
        code_, out = self.run_check(self.base())
        self.assertEqual(code_, 0, out)
        self.assertIn(f"{PLAN}: COVERED 1/1", out)

    def test_resolved_plan_with_untested_ac_fails(self):
        files = self.base(**{"tests/test_pay.py": code("test_pays"), f"docs/record/{REC}.md": record("test_pays")})
        code_, out = self.run_check(files)
        self.assertEqual(code_, 1)
        self.assertIn("UNCOVERED", out)
        self.assertIn("does not carry", out)

    def test_open_plan_with_untested_ac_only_reports(self):
        files = self.base(**{f"docs/plan/{PLAN}.md": plan("open")})
        del files[f"docs/record/{REC}.md"]
        del files["tests/test_pay.py"]
        code_, out = self.run_check(files)
        self.assertEqual(code_, 0, out)
        self.assertIn("UNCOVERED", out)

    def test_spec_amended_after_resolved_fails_gate(self):
        new_ac = "- **spec-00001-AC-1.2** (spec-00001-FR-1)\n  Given x\n  When y\n  Then z\n"
        code_, out = self.run_check(self.base(**{f"docs/spec/{SPEC}.md": spec(new_ac)}))
        self.assertEqual(code_, 1)
        self.assertIn("no test carries spec-00001-AC-1.2", out)
        self.assertIn("resolved without a passing record row for spec-00001-AC-1.2", out)

    def test_renamed_test_breaks_record_evidence(self):
        code_, out = self.run_check(self.base(**{"tests/test_pay.py": code("test_renamed" + sfx(AC))}))
        self.assertEqual(code_, 1)
        self.assertIn("not found in any tracked file", out)

    def test_orphan_suffix_fails(self):
        extra = code("test_ghost" + sfx("spec-00001-AC-9.9"))
        code_, out = self.run_check(self.base(**{"tests/test_ghost.py": extra}))
        self.assertEqual(code_, 1)
        self.assertIn("ORPHANED", out)

    def test_bad_front_matter(self):
        files = self.base()
        files[f"docs/spec/{SPEC}.md"] = spec().replace("status: active\n", "status: active\nimplements: [x]\n")
        files["docs/spec/spec-00001-dup.md"] = spec().replace(SPEC, "spec-00001-dup")
        files["docs/spec/README.md"] = SPEC_README
        code_, out = self.run_check(files)
        self.assertEqual(code_, 1)
        self.assertIn("not carried by type spec", out)
        self.assertIn("malformed id 'x'", out)
        self.assertIn("number spec-00001 already used", out)

    def test_yaml_matrix_must_match_readme(self):
        files = self.base(**{"docs/spec/README.md": SPEC_README,
                             "whiteboard.config.yaml": "carries:\n  spec: [parent, informs]\n\nflow:\n"})
        code_, out = self.run_check(files)
        self.assertEqual(code_, 1)
        self.assertIn("whiteboard.config.yaml carries: spec", out)

    def test_ac_hash_marks_changed_ac_suspect(self):
        _, out = self.run_check(self.base(), "--hash", AC)
        h = out.split()[1]
        self.assertRegex(h, r"^ac:[0-9a-f]{8}$")
        code_, out = self.run_check(self.base(**{f"docs/record/{REC}.md": record(evidence=h)}))
        self.assertEqual(code_, 0, out)
        self.assertNotIn("SUSPECT", out)
        changed = spec().replace("Then c", "Then not c")
        code_, out = self.run_check(self.base(**{f"docs/record/{REC}.md": record(evidence=h), f"docs/spec/{SPEC}.md": changed}))
        self.assertEqual(code_, 1)
        self.assertIn(f"SUSPECT {AC} changed", out)


    def test_architecture_boundaries(self):
        arch = ("# Architecture Overview\n\n## 5. Building Block View\n\n**Boundaries**\n\n"
                "| Rule | Enforced by |\n| --- | --- |\n"
                "| domain imports no infra | `tests/arch.py` |\n"
                "| only gateway calls http | Unenforced: no lint yet |\n")
        code_, out = self.run_check(self.base(**{"ARCHITECTURE.md": arch, "tests/arch.py": "x = 1\n"}))
        self.assertEqual(code_, 0, out)
        self.assertIn("1 boundary rule(s) marked Unenforced", out)
        broken = arch.replace("`tests/arch.py`", "`tests/gone.py`") + "| empty cell | |\n"
        code_, out = self.run_check(self.base(**{"ARCHITECTURE.md": broken, "tests/arch.py": "x = 1\n"}))
        self.assertEqual(code_, 1)
        self.assertIn("tests/gone.py, which is not a tracked file", out)
        self.assertIn("has no Enforced by", out)


if __name__ == "__main__":
    os.environ.setdefault("GIT_AUTHOR_NAME", "t")
    unittest.main()
