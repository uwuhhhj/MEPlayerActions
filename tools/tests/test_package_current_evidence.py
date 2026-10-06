"""Synthetic packaging gates; fixture commands are not build or game evidence."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


TOOL = Path(__file__).resolve().parents[1] / "package_current.py"
SPEC = importlib.util.spec_from_file_location("mpa_package_current_evidence", TOOL)
PACKAGE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(PACKAGE)


class PackageEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="mpa-package-evidence-")
        self.validation = Path(self.temporary.name)
        self.patched = patch.object(PACKAGE, "VALIDATION", self.validation)
        self.patched.start()
        self.addCleanup(self.patched.stop)
        self.addCleanup(self.temporary.cleanup)

    def stage(self, name, cases, exit_code=0, **counts):
        directory = self.validation / name
        directory.mkdir()
        metadata = {"version": PACKAGE.VERSION,
                    "commands": [{"command": ["synthetic-fixture-check", name], "exit_code": exit_code}]}
        (directory / "stage.json").write_text(json.dumps(metadata), encoding="utf-8")
        if cases is not None:
            suite = ET.Element("testsuite", name="Fixture.Suite", tests=str(len(cases)),
                               failures=str(sum(outcome == "failure" for _, outcome in cases)),
                               errors=str(sum(outcome == "error" for _, outcome in cases)),
                               skipped=str(sum(outcome == "skipped" for _, outcome in cases)))
            for key, value in counts.items():
                suite.set(key, str(value))
            for case_name, outcome in cases:
                case = ET.SubElement(suite, "testcase", name=case_name, classname="Fixture.Suite")
                if outcome:
                    ET.SubElement(case, outcome, message="synthetic outcome")
            ET.ElementTree(suite).write(directory / "TEST-Fixture.Suite.xml", encoding="utf-8", xml_declaration=True)
        return directory

    def basic(self):
        self.stage("server-base", [("server_case", "")])

    def test_failed_then_success_keeps_original_attempt_and_does_not_repeat_passed_case(self):
        self.basic()
        failed = self.stage("client-first", [("unchanged_pass", ""), ("fixed_case", "failure")], 1)
        self.stage("client-fixed", [("fixed_case", "")])
        summary, evidence = PACKAGE.test_evidence(["server-base", "client-first", "client-fixed"])
        self.assertEqual(3, summary["distinct_targeted_cases"])
        self.assertEqual({"client": 2, "server": 1}, summary["counts_by_side"])
        self.assertEqual(1, summary["failed_command_attempts"])
        self.assertEqual(1, summary["earlier_report_failures"])
        self.assertEqual(1, summary["stages"][1]["commands"][0]["exit_code"])
        self.assertFalse(summary["stages"][1]["commands_all_succeeded"])
        self.assertEqual((failed / "stage.json").read_bytes(), evidence["client-first/stage.json"])
        self.assertEqual((failed / "TEST-Fixture.Suite.xml").read_bytes(), evidence["client-first/TEST-Fixture.Suite.xml"])
        unchanged = next(case for case in summary["latest_cases"] if case["name"] == "unchanged_pass")
        self.assertEqual("client-first", unchanged["stage"])

    def test_latest_failure_cannot_be_hidden_by_earlier_pass(self):
        self.basic()
        self.stage("client-first", [("same_case", "")])
        self.stage("client-last", [("same_case", "failure")], 1)
        with self.assertRaisesRegex(RuntimeError, "Latest targeted outcomes"):
            PACKAGE.test_evidence(["server-base", "client-first", "client-last"])

    def test_latest_error_is_not_a_pass(self):
        self.basic()
        self.stage("client-error", [("unresolved", "error")], 1)
        with self.assertRaisesRegex(RuntimeError, "Latest targeted outcomes"):
            PACKAGE.test_evidence(["server-base", "client-error"])

    def test_latest_skip_is_not_a_pass(self):
        self.basic()
        self.stage("client-skip", [("unresolved", "skipped")])
        with self.assertRaisesRegex(RuntimeError, "Latest targeted outcomes"):
            PACKAGE.test_evidence(["server-base", "client-skip"])

    def test_failure_counters_must_match_failure_records(self):
        self.basic()
        self.stage("client-count", [("bad_count", "failure")], 1, failures=0)
        with self.assertRaisesRegex(RuntimeError, "outcomes/counts disagree"):
            PACKAGE.test_evidence(["server-base", "client-count"])

    def test_one_case_cannot_claim_multiple_outcomes(self):
        self.basic()
        directory = self.stage("client-ambiguous", [("ambiguous", "failure")], 1, errors=1)
        path = directory / "TEST-Fixture.Suite.xml"
        report = ET.parse(path)
        ET.SubElement(report.getroot().find("testcase"), "error", message="synthetic second outcome")
        report.write(path, encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "Invalid test outcome records"):
            PACKAGE.test_evidence(["server-base", "client-ambiguous"])

    def test_both_sides_are_required(self):
        self.basic()
        with self.assertRaisesRegex(RuntimeError, "Both current client/server"):
            PACKAGE.test_evidence(["server-base"])

    def test_missing_or_boolean_exit_code_is_not_real_command_proof(self):
        for code in [None, False, "1"]:
            with self.subTest(code=code), self.assertRaisesRegex(RuntimeError, "integer exit code"):
                PACKAGE.recorded_commands({"commands": [{"command": "fixture", "exit_code": code}]}, "Fixture")

    def test_failed_compile_attempt_without_reports_is_retained_but_not_counted_as_pass(self):
        self.stage("client-compile-failed", None, 1)
        self.basic()
        self.stage("client-compiled", [("client_case", "")])
        summary, evidence = PACKAGE.test_evidence(["client-compile-failed", "server-base", "client-compiled"])
        self.assertEqual(2, summary["distinct_targeted_cases"])
        self.assertEqual([], summary["stages"][0]["suites"])
        self.assertEqual(1, summary["failed_command_attempts"])
        self.assertIn("client-compile-failed/stage.json", evidence)

    def test_successful_test_stage_requires_reports(self):
        self.stage("client-empty", None)
        self.basic()
        with self.assertRaisesRegex(RuntimeError, "Successful test stage is missing reports"):
            PACKAGE.test_evidence(["server-base", "client-empty"])

    def test_final_build_commands_still_all_have_to_succeed(self):
        proof = {"version": PACKAGE.VERSION, "commands": [
            {"command": "mvn -DskipTests package", "exit_code": 1},
            {"command": "gradle build -x test", "exit_code": 0}]}
        path = self.validation / "build-commands.json"
        path.write_text(json.dumps(proof), encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "Final build: command failed"):
            PACKAGE.build_commands()
        proof["commands"][0]["exit_code"] = 0
        path.write_text(json.dumps(proof), encoding="utf-8")
        self.assertEqual(proof, PACKAGE.build_commands())


if __name__ == "__main__":
    unittest.main()
