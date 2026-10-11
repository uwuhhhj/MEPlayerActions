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

    def stage(self, name, cases, exit_code=0, *, root=None, version=None, **counts):
        directory = (root or self.validation) / name
        directory.mkdir(parents=True)
        metadata = {"version": version or PACKAGE.VERSION,
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

    def manifest(self, stages, **fields):
        path = self.validation / "evidence-manifest.json"
        value = {"release_version": PACKAGE.VERSION, "stages": stages, **fields}
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def declared(self, directory, version, reason=""):
        return {"directory": str(directory), "version": version, "reason": reason}

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

    def test_explicit_current_work_manifest_preserves_the_original_version_and_report_bytes(self):
        old = self.stage("client-fix", [("client_case", "")], root=self.validation / "validation-0.6.0", version="0.6.0")
        new = self.stage("server-fix", [("server_case", "")], root=self.validation / f"validation-{PACKAGE.VERSION}")
        manifest = self.manifest([self.declared(old, "0.6.0", "Affected checks completed in this work before the version bump"),
                                  self.declared(new, PACKAGE.VERSION)])
        summary, evidence = PACKAGE.test_evidence(None, manifest)
        self.assertEqual(2, summary["distinct_targeted_cases"])
        self.assertTrue(summary["previous_version_test_evidence_reused"])
        self.assertEqual(["0.6.0", PACKAGE.VERSION], summary["evidence_versions"])
        self.assertEqual("0.6.0", summary["stages"][0]["version"])
        self.assertEqual((old / "stage.json").read_bytes(), evidence["stages/0.6.0/client-fix/stage.json"])
        self.assertEqual((old / "TEST-Fixture.Suite.xml").read_bytes(), evidence["stages/0.6.0/client-fix/TEST-Fixture.Suite.xml"])
        self.assertEqual(manifest.read_bytes(), evidence["evidence-manifest.json"])

    def test_manifest_cannot_relabel_old_stage_proof_as_current_version(self):
        current = self.stage("client-fix", [("client_case", "")], root=self.validation / f"validation-{PACKAGE.VERSION}", version="0.6.0")
        manifest = self.manifest([self.declared(current, PACKAGE.VERSION)])
        with self.assertRaisesRegex(RuntimeError, "proof version does not match"):
            PACKAGE.test_evidence(None, manifest)

    def test_preserved_earlier_stage_requires_a_reason_and_original_directory_identity(self):
        old = self.stage("client-fix", [("client_case", "")], root=self.validation / "validation-0.6.0", version="0.6.0")
        manifest = self.manifest([self.declared(old, "0.6.0")])
        with self.assertRaisesRegex(RuntimeError, "preservation reason"):
            PACKAGE.test_evidence(None, manifest)
        manifest = self.manifest([self.declared(old, PACKAGE.VERSION)])
        with self.assertRaisesRegex(RuntimeError, "original validation"):
            PACKAGE.test_evidence(None, manifest)

    def test_manifest_cannot_import_arbitrary_previous_release_evidence(self):
        old = self.stage("client-fix", [("client_case", "")], root=self.validation / "validation-0.5.1", version="0.5.1")
        manifest = self.manifest([self.declared(old, "0.5.1", "Earlier release")])
        with self.assertRaisesRegex(RuntimeError, "accepted current-work scope"):
            PACKAGE.test_evidence(None, manifest)

    def test_manifest_needs_current_release_identity_and_unique_explicit_order(self):
        current = self.stage("client-fix", [("client_case", "")], root=self.validation / f"validation-{PACKAGE.VERSION}")
        declared = self.declared(current, PACKAGE.VERSION)
        with self.assertRaisesRegex(RuntimeError, "current release"):
            PACKAGE.test_evidence(None, self.manifest([declared], release_version="0.6.0"))
        with self.assertRaisesRegex(RuntimeError, "repeats a stage"):
            PACKAGE.test_evidence(None, self.manifest([declared, declared]))
        with self.assertRaisesRegex(RuntimeError, "own complete stage order"):
            PACKAGE.test_evidence(["client-fix"], self.manifest([declared]))

    def test_manifest_side_preserves_an_original_diagnostic_directory_name(self):
        directory = self.stage("same-asset-diagnostic", [("client_case", "")], root=self.validation / f"validation-{PACKAGE.VERSION}")
        server = self.stage("server-affected", [("server_case", "")], root=self.validation / f"validation-{PACKAGE.VERSION}")
        declared = {**self.declared(directory, PACKAGE.VERSION), "side": "client"}
        manifest = self.manifest([declared, self.declared(server, PACKAGE.VERSION)])
        summary, _ = PACKAGE.test_evidence(None, manifest)
        self.assertEqual({"client": 1, "server": 1}, summary["counts_by_side"])
        metadata = json.loads((directory / "stage.json").read_text(encoding="utf-8"))
        metadata["commands"][0]["side"] = "server"
        (directory / "stage.json").write_text(json.dumps(metadata), encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "command side differs"):
            PACKAGE.test_evidence(None, manifest)

    def test_repeated_parameterized_names_are_preserved_and_conservatively_counted(self):
        self.basic()
        directory = self.stage("client-repeat", [("[1] 4.10", ""), ("[1] 4.10", "")])
        summary, evidence = PACKAGE.test_evidence(["server-base", "client-repeat"])
        self.assertEqual(3, summary["raw_reported_testcases"])
        self.assertEqual(2, summary["distinct_targeted_cases"])
        self.assertEqual(2, next(case for case in summary["latest_cases"] if case["side"] == "client")["occurrences"])
        self.assertEqual((directory / "TEST-Fixture.Suite.xml").read_bytes(), evidence["client-repeat/TEST-Fixture.Suite.xml"])

    def test_a_passing_duplicate_cannot_hide_another_failure_in_the_same_report(self):
        self.basic()
        self.stage("client-repeat", [("[1] 4.10", "failure"), ("[1] 4.10", "")], 1)
        with self.assertRaisesRegex(RuntimeError, "Latest targeted outcomes"):
            PACKAGE.test_evidence(["server-base", "client-repeat"])

    def test_a_smaller_rerun_cannot_silently_resolve_ambiguous_parameterized_methods(self):
        self.basic()
        self.stage("client-repeat", [("[1] 4.10", "failure"), ("[1] 4.10", "")], 1)
        self.stage("client-subset", [("[1] 4.10", "")])
        with self.assertRaisesRegex(RuntimeError, "unresolved_ambiguous_previous_stage"):
            PACKAGE.test_evidence(["server-base", "client-repeat", "client-subset"])
        self.stage("client-affected-group", [("[1] 4.10", ""), ("[1] 4.10", "")])
        summary, _ = PACKAGE.test_evidence(["server-base", "client-repeat", "client-subset", "client-affected-group"])
        self.assertEqual(2, summary["distinct_targeted_cases"])
        self.assertEqual(1, summary["earlier_report_failures"])

    def test_final_build_metadata_must_still_identify_the_new_version(self):
        path = self.validation / "build-commands.json"
        path.write_text(json.dumps({"version": "0.6.0", "commands": [
            {"command": "mvn -DskipTests package", "exit_code": 0},
            {"command": "gradle build -x test", "exit_code": 0}]}), encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "current release"):
            PACKAGE.build_commands()

    def test_a_fresh_suffix_changes_only_delivery_names_and_cannot_escape_dist(self):
        server = Path(f"MEPlayerActions-{PACKAGE.VERSION}.jar")
        client = Path(f"MEPlayerActions-Client-{PACKAGE.VERSION}.jar")
        paths = PACKAGE.output_paths(server, client, "fresh-20261011-01")
        self.assertTrue(all(path.parent == PACKAGE.DIST and "fresh-20261011-01" in path.name for path in paths.values()))
        self.assertEqual(f"MEPlayerActions-{PACKAGE.VERSION}.jar", PACKAGE.output_paths(server, client)["server_jar"].name)
        for suffix in ["../old", "a/b", "A", ".hidden", "a" * 65]:
            with self.subTest(suffix=suffix), self.assertRaisesRegex(RuntimeError, "filename token"):
                PACKAGE.output_paths(server, client, suffix)


if __name__ == "__main__":
    unittest.main()
