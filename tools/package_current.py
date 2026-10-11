"""Package server/client 0.6.1 after saved targeted checks and final builds.

This helper does not run a build, a test, Minecraft, or the old runtime release gate.
Both sides must be freshly built. Earlier stages of this work may be explicitly retained
with their original version, without claiming they were rerun for the final version.
Existing dist artifacts are never replaced. The version and evidence contract
must match the release being built.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import posixpath
import re
import struct
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
CLIENT = PROJECT / "client"
VERSION = "0.6.1"
SERVER_VERSION = VERSION
VALIDATION = PROJECT / "build" / f"validation-{VERSION}"
EVIDENCE_VERSIONS = {VERSION, "0.6.0"}
REFERENCE_REVISION = "0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85"
REFERENCE = PROJECT.parent / "ModelEngine玩家动作研究/sources/OpenYSM-Updated"
SPEC = importlib.util.spec_from_file_location("mpa_release_helpers", PROJECT / "tools/package_release.py")
RELEASE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(RELEASE)
DIST = RELEASE.DIST
INCLUDES = {"zstd-jni-1.5.7-6.jar": ("com.github.luben", "zstd-jni", "1.5.7-6"),
            "webp-0.2.0.jar": ("org.glavo", "webp", "0.2.0")}
EXCLUDED_PARTS = {"build", "target", ".gradle", ".git", "run", "logs", "cache", "caches",
                  "__pycache__", ".idea", ".vscode", "node_modules", "dist", "secrets", "private-incoming",
                  "private-fixture", "private-fixtures", "privatefixtures", "private-models", "user-models", "local-models", "model-probe", "private-source-data"}
EXCLUDED_SUFFIXES = {".pyc", ".log", ".lock", ".db", ".sqlite", ".sqlite3", ".jks", ".p12", ".pem", ".key", ".ysm", ".zip", ".7z", ".rar", ".tar", ".gz"}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def files(root: Path) -> list[Path]:
    return sorted(path for path in root.rglob("*") if path.is_file())


def facts(path: Path) -> dict:
    return {"sha256": RELEASE.digest(path), "bytes": path.stat().st_size}


def fingerprint(paths: list[Path]) -> dict:
    value = hashlib.sha256()
    for path in sorted(set(paths)):
        value.update(path.relative_to(PROJECT).as_posix().encode("utf-8"))
        value.update(b"\0")
        value.update(path.read_bytes())
    return {"sha256": value.hexdigest(), "input_count": len(set(paths))}


def fresh(jar: Path, inputs: list[Path]) -> None:
    require(jar.is_file(), f"Build artifact missing: {jar}")
    stale = [path.relative_to(PROJECT).as_posix() for path in inputs if path.stat().st_mtime_ns > jar.stat().st_mtime_ns]
    require(not stale, f"Build predates current inputs: {jar.name}: {stale}")


def open_jar(path: Path) -> zipfile.ZipFile:
    archive = zipfile.ZipFile(path)
    require(archive.testzip() is None, f"Corrupt JAR: {path}")
    require(len(archive.namelist()) == len(set(archive.namelist())), f"Duplicate JAR entries: {path}")
    return archive


def java21(archive: zipfile.ZipFile) -> int:
    classes = [name for name in archive.namelist() if name.endswith(".class")]
    require(bool(classes), "No compiled project classes")
    for name in classes:
        require(name.startswith("com/simmc/meplayeractions/"), f"Unexpected outer dependency class: {name}")
        require(len(archive.read(name)) >= 8, f"Truncated class: {name}")
        require(struct.unpack(">IHH", archive.read(name)[:8]) == (0xCAFEBABE, 0, 65), f"Not Java 21: {name}")
    return len(classes)


def validate_server(jar: Path) -> dict:
    with open_jar(jar) as archive:
        count = java21(archive)
        require(not any(name.endswith(".jar") for name in archive.namelist()), "Server dependency JAR was bundled")
        for path in files(PROJECT / "src/main/resources"):
            expected = path.read_bytes().replace(b"${version}", SERVER_VERSION.encode())
            require(archive.read(path.relative_to(PROJECT / "src/main/resources").as_posix()) == expected,
                    f"Stale server resource: {path.name}")
        descriptor = archive.read("plugin.yml").decode("utf-8")
        require(f"version: '{SERVER_VERSION}'" in descriptor and "${" not in descriptor, "Server descriptor version mismatch")
        require(not re.search(r"(?m)^depend\s*:", descriptor), "Server unexpectedly requires ModelEngine")
        require("softdepend: [ModelEngine, GSit]" in descriptor, "Optional server integrations mismatch")
        require("main: com.simmc.meplayeractions.MEPlayerActionsPlugin" in descriptor and "api-version: '1.21.11'" in descriptor,
                "Server entrypoint/API metadata mismatch")
        require("mact.private.upload:" in descriptor and "mact.private.view:" in descriptor, "Private sync permissions absent")
        originals = {}
        for model in sorted((PROJECT / "examples/models").glob("*.bbmodel")):
            require(archive.read(f"models/{model.name}") == model.read_bytes(), f"Stale original server model: {model.name}")
            originals[model.name] = facts(model)
        require(set(originals) == {"ysm_01_jk.bbmodel", "ysm_02_jk.bbmodel"}, "Bundled server model set differs")
        return {"class_count": count, "optional_modelengine": True, "resources_match": True, "original_models": originals}


def validate_include_payload(data: bytes, processed: Path, original: Path, version: str) -> dict:
    require(sha(data) == RELEASE.digest(processed), f"Nested JAR differs from Gradle include output: {processed.name}")
    with zipfile.ZipFile(io.BytesIO(data)) as nested, open_jar(original) as raw:
        require(nested.testzip() is None and len(nested.namelist()) == len(set(nested.namelist())), "Invalid nested JAR")
        raw_entries = {name for name in raw.namelist() if not name.endswith("/")}
        nested_entries = {name for name in nested.namelist() if not name.endswith("/")}
        require(nested_entries == raw_entries | {"fabric.mod.json"}, "Loom changed nested dependency contents beyond mod metadata")
        for name in raw_entries:
            require(nested.read(name) == raw.read(name), f"Modified original dependency payload: {original.name}: {name}")
        metadata = json.loads(nested.read("fabric.mod.json"))
        require(metadata.get("version") == version and not metadata.get("jars"), f"Unexpected nested dependency metadata: {original.name}")
        require(not any(name.endswith(".jar") for name in nested_entries), "Unapproved transitive nested dependency")
    return {"gradle_include_output": processed.relative_to(PROJECT).as_posix(), **facts(processed),
            "original_maven_artifact_sha256": RELEASE.digest(original), "original_payload_matches": True}


def validate_client(jar: Path, gradle_cache: Path, reference: Path) -> dict:
    with open_jar(jar) as archive:
        count = java21(archive)
        resources = CLIENT / "src/main/resources"
        expected = json.loads((resources / "fabric.mod.json").read_text(encoding="utf-8").replace("${version}", VERSION))
        actual = json.loads(archive.read("fabric.mod.json"))
        includes = actual.pop("jars", [])
        require(actual == expected, "Client Fabric metadata differs from current source")
        require(actual["environment"] == "client" and actual["version"] == VERSION and actual["id"] == "meplayeractions", "Client identity mismatch")
        require(set(actual["depends"]) == {"fabricloader", "minecraft", "java", "fabric-api"}, "Client has an unwanted server/engine dependency")
        require(actual["depends"]["minecraft"] == "~1.21.11" and actual["depends"]["java"] == ">=21", "Client MC/Java mismatch")
        jar_entries = {name for name in archive.namelist() if name.endswith(".jar")}
        declared = {entry["file"] for entry in includes}
        require(jar_entries == declared and len(includes) == len(declared) == 2, "Unexpected or undeclared nested libraries")
        include_proof = {}
        for entry in sorted(declared):
            filename = posixpath.basename(entry)
            require(filename in INCLUDES and entry.startswith("META-INF/jars/"), f"Unapproved nested library: {entry}")
            group, artifact, version = INCLUDES[filename]
            outputs = list((CLIENT / "build/processIncludeJars").rglob(filename))
            require(len(outputs) == 1, f"Expected exactly one Gradle processIncludeJars output: {filename}")
            originals = list((gradle_cache / "modules-2/files-2.1" / group / artifact / version).rglob(filename))
            require(bool(originals), f"Original included dependency unavailable in Gradle cache: {filename}")
            require(len({RELEASE.digest(path) for path in originals}) == 1, f"Ambiguous cached original dependency: {filename}")
            include_proof[filename] = validate_include_payload(archive.read(entry), outputs[0], originals[0], version)
        for path in files(resources):
            if path.name != "fabric.mod.json":
                require(archive.read(path.relative_to(resources).as_posix()) == path.read_bytes(), f"Stale client resource: {path}")
        require(not any(name.lower().endswith(".ysm") for name in archive.namelist()), "Do not bundle private YSM binary models in the independent client")
        mixins = json.loads(archive.read("meplayeractions.client.mixins.json"))
        require(mixins["compatibilityLevel"] == "JAVA_21" and mixins["required"] is True, "Mixin JVM requirements mismatch")
        prefix = mixins["package"].replace(".", "/") + "/"
        for name in mixins["client"]:
            require(prefix + name + ".class" in archive.namelist(), f"Mixin class absent: {name}")
        for model in (PROJECT / "examples/models").glob("*.bbmodel"):
            require(not any(name.endswith("/" + model.name) for name in archive.namelist()), "Server original model bundled in independent client")
        return {"class_count": count, "resources_match": True, "mixins": mixins["client"], "included_libraries": include_proof,
                "gui": RELEASE.validate_gui_assets(CLIENT, archive, True),
                "wine_fox": RELEASE.validate_wine_fox_assets(CLIENT, archive, True),
                "cc0_assets": validate_cc0(archive, reference)}


def reference_bytes(reference: Path, relative: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(reference), "show", REFERENCE_REVISION + ":" + relative])


def reference_files(reference: Path, relative: str) -> set[str]:
    data = subprocess.check_output(["git", "-C", str(reference), "ls-tree", "-r", "--name-only", REFERENCE_REVISION, relative]).decode("utf-8")
    prefix = relative.rstrip("/") + "/"
    return {name.removeprefix(prefix) for name in data.splitlines() if name.startswith(prefix)}


def validate_cc0(archive: zipfile.ZipFile, reference: Path) -> dict:
    revision = subprocess.check_output(["git", "-C", str(reference), "rev-parse", "HEAD"]).decode().strip()
    require(revision == REFERENCE_REVISION, "Reference checkout is not the pinned OpenYSM revision")
    resources = CLIENT / "src/main/resources"
    base = "common/src/main/resources/assets/yes_steve_model/builtin/"
    reports = {}
    all_line_ending_paths = []
    for destination, source, expected_count in [("misc/1_alex", "misc/1_alex", 11), ("misc/2_steve", "misc/2_steve", 11),
                                                ("openysm_default", "default", None)]:
        folder = resources / "assets/meplayeractions/builtin" / destination
        originals = {path.relative_to(folder).as_posix(): path for path in files(folder)
                     if path.name not in {"NOTICE.md", "LICENSE.OpenYSM.txt"}}
        source_entries = reference_files(reference, base + source)
        if expected_count is not None:
            require(set(originals) == source_entries and len(originals) == expected_count, f"Incomplete original CC0 folder: {destination}")
        else:
            avatars = {name for name in source_entries if name.startswith("avatar/")}
            require(avatars == {name for name in originals if name.startswith("avatar/")} and len(avatars) == 6,
                    "Default model author avatars are incomplete")
        total = 0
        fixed_git_total = 0
        manifest = {}
        line_ending_paths = []
        for name, path in sorted(originals.items()):
            require(name in source_entries, f"Unexpected original model resource: {destination}/{name}")
            relative = base + source + "/" + name
            fixed_git_data = reference_bytes(reference, relative)
            checkout_data = (reference / relative).read_bytes()
            distributed_data = path.read_bytes()
            require(distributed_data == checkout_data == archive.read(path.relative_to(resources).as_posix()),
                    f"Distributed CC0 bytes differ from reference checkout: {destination}/{name}")
            git_tree_byte_exact = distributed_data == fixed_git_data
            if not git_tree_byte_exact:
                # Git's Windows checkout can convert JSON LF to CRLF. Permit only
                # that text conversion; other JSON edits and all binary edits fail.
                require(path.suffix.lower() == ".json"
                        and distributed_data.replace(b"\r\n", b"\n") == fixed_git_data.replace(b"\r\n", b"\n"),
                        f"CC0 bytes differ beyond JSON line endings from fixed Git tree: {destination}/{name}")
                require(json.loads(distributed_data) == json.loads(fixed_git_data),
                        f"CC0 JSON semantics differ from fixed Git tree: {destination}/{name}")
                line_ending_paths.append(name)
                all_line_ending_paths.append(destination + "/" + name)
            manifest[name] = {"sha256": sha(distributed_data), "bytes": len(distributed_data),
                              "fixed_git_sha256": sha(fixed_git_data), "fixed_git_bytes": len(fixed_git_data),
                              "checkoutBytesPreserved": True, "gitTreeByteExact": git_tree_byte_exact}
            total += len(distributed_data)
            fixed_git_total += len(fixed_git_data)
        raw = json.loads(originals["ysm.json"].read_text(encoding="utf-8"))
        require(raw["metadata"]["license"]["type"] in {"CC0", "CC 0"}, "Original model CC0 declaration mismatch")
        reports[destination] = {"file_count": len(originals), "bytes": total, "fixed_git_bytes": fixed_git_total,
                                "files": manifest, "checkoutBytesPreserved": True,
                                "gitTreeByteExact": not line_ending_paths, "onlyLineEndingPaths": line_ending_paths}
    return {"upstream": "https://github.com/IzumiiKonata/OpenYSM-Updated", "revision": REFERENCE_REVISION,
            "models": reports, "default_avatars_complete": True, "checkoutBytesPreserved": True,
            "gitTreeByteExact": not all_line_ending_paths, "onlyLineEndingPaths": all_line_ending_paths,
            "fixed_git_tree_content_preserved": True}


def evidence_stages(stage_order: list[str] | None, manifest_path: Path | None = None) -> tuple[list[dict], bytes | None]:
    if manifest_path is not None:
        require(not stage_order, "Manifest defines its own complete stage order")
        raw = manifest_path.read_bytes()
        manifest = json.loads(raw.decode("utf-8-sig"))
        require(isinstance(manifest, dict) and manifest.get("release_version") == VERSION,
                "Evidence manifest must identify the current release")
        declared = manifest.get("stages")
        require(isinstance(declared, list) and bool(declared), "Evidence manifest needs actual targeted stages")
        result, identities, directories = [], set(), set()
        for stage in declared:
            require(isinstance(stage, dict) and stage.get("version") in EVIDENCE_VERSIONS,
                    "Evidence stage version is outside this release's accepted current-work scope")
            value, version = stage.get("directory"), stage["version"]
            require(isinstance(value, str) and bool(value.strip()), "Evidence stage directory required")
            directory = Path(value)
            if not directory.is_absolute():
                directory = PROJECT / directory
            directory = directory.resolve()
            require(directory.is_dir() and directory.parent.name == f"validation-{version}",
                    f"Evidence directory must retain its original validation-{version} root: {value}")
            identity = f"{version}/{directory.name}"
            require(identity not in identities and directory not in directories, "Evidence manifest repeats a stage")
            reason = stage.get("reason", "")
            require(isinstance(reason, str) and (version == VERSION or bool(reason.strip())),
                    "Earlier current-work stage needs an explicit preservation reason")
            side = stage.get("side", "")
            require(side in {"", "client", "server", "integration"}, "Evidence stage side must be client/server/integration")
            identities.add(identity); directories.add(directory)
            result.append({"directory": directory, "stage": identity, "version": version,
                           "reason": reason, "side": side, "archive": f"stages/{identity}"})
        return result, raw
    require(VALIDATION.is_dir(), f"Current validation directory missing: {VALIDATION}")
    directories = [path for path in VALIDATION.iterdir() if path.is_dir()
                   and ((path / "stage.json").is_file() or list(path.rglob("TEST-*.xml")))]
    require(bool(directories), f"No saved {VERSION} targeted test stages")
    def stamp(path: Path) -> int:
        metadata = path / "stage.json"
        return metadata.stat().st_mtime_ns if metadata.is_file() else max(xml.stat().st_mtime_ns for xml in path.rglob("TEST-*.xml"))
    directories.sort(key=lambda path: (stamp(path), path.name))
    if stage_order:
        require(len(stage_order) == len(set(stage_order)) and set(stage_order) == {path.name for path in directories}, "Explicit stage order must include each saved stage exactly once")
        directories = [VALIDATION / name for name in stage_order]
    return [{"directory": path, "stage": path.name, "version": VERSION, "reason": "", "archive": path.name}
            for path in directories], None


def test_evidence(stage_order: list[str] | None, manifest_path: Path | None = None) -> tuple[dict, dict[str, bytes]]:
    selections, manifest_raw = evidence_stages(stage_order, manifest_path)
    latest = {}
    stages = []
    evidence = {}
    for selection in selections:
        directory, stage_id = selection["directory"], selection["stage"]
        declared_side = selection.get("side", "")
        side = declared_side if declared_side in {"client", "server"} else "server" if directory.name.startswith("server") else "client" if directory.name.startswith("client") else ""
        require(bool(side) or declared_side == "integration" or directory.name.startswith("integration"), f"Stage needs a client/server/integration identity: {directory.name}")
        metadata = directory / "stage.json"
        require(metadata.is_file(), f"Current stage command proof missing: {directory.name}/stage.json")
        stage_proof = json.loads(metadata.read_text(encoding="utf-8-sig"))
        require(isinstance(stage_proof, dict) and stage_proof.get("version") == selection["version"],
                f"Stage proof version does not match its original identity: {stage_id}")
        commands = recorded_commands(stage_proof, f"Stage {directory.name}")
        require(all(not record.get("side") or not side or record["side"] == side for record in commands),
                f"Stage command side differs from its evidence identity: {stage_id}")
        suites = []
        outcomes = {}
        reports = sorted(directory.rglob("TEST-*.xml"))
        require(reports or any(command["exit_code"] != 0 for command in commands),
                f"Successful test stage is missing reports: {directory.name}")
        for path in reports:
            report_side = side
            if not report_side:
                prefix = path.relative_to(directory).parts[0]
                report_side = "server" if prefix.startswith("server") else "client" if prefix.startswith("client") else ""
            require(bool(report_side), f"Integration report must be under server/ or client/: {path.relative_to(directory)}")
            root = ET.parse(path).getroot()
            roots = [root] if root.tag == "testsuite" else root.findall("testsuite")
            require(bool(roots), f"Unknown test report structure: {path}")
            for suite in roots:
                cases = suite.findall("testcase")
                require(len(cases) == int(suite.attrib["tests"]), f"Test report count mismatch: {path}")
                counts = {key: int(suite.attrib.get(key, "0")) for key in ["tests", "failures", "errors", "skipped"]}
                require(all(value >= 0 for value in counts.values())
                        and all(sum(case.find(name) is not None for name in ["failure", "error", "skipped"]) <= 1
                                for case in cases), f"Invalid test outcome records: {path}")
                require(counts["failures"] == sum(case.find("failure") is not None for case in cases)
                        and counts["errors"] == sum(case.find("error") is not None for case in cases)
                        and counts["skipped"] == sum(case.find("skipped") is not None for case in cases), f"Test outcomes/counts disagree: {path}")
                suites.append({"side": report_side, "suite": suite.attrib["name"], **counts, "sha256": RELEASE.digest(path)})
                for case in cases:
                    passed = all(case.find(name) is None for name in ["failure", "error", "skipped"])
                    original_name = case.attrib["name"]
                    original_key = (report_side, suite.attrib["name"], original_name)
                    record = outcomes.setdefault(original_key, {"stage": stage_id, "passed": True,
                            "original_test_name": original_name, "occurrences": 0})
                    record["passed"] = record["passed"] and passed
                    record["occurrences"] += 1
            evidence[selection["archive"] + "/" + path.relative_to(directory).as_posix()] = path.read_bytes()
        for key, record in outcomes.items():
            previous = latest.get(key)
            # Parameterized display names can repeat across methods. A smaller later
            # selection cannot prove every earlier ambiguous failing method was fixed.
            if previous and not previous["passed"] and previous["occurrences"] > record["occurrences"]:
                record["passed"] = False
                record["unresolved_ambiguous_previous_stage"] = previous["stage"]
                record["occurrences"] = previous["occurrences"]
            latest[key] = record
        evidence[selection["archive"] + "/stage.json"] = metadata.read_bytes()
        stages.append({"stage": stage_id, "side": side or "integration", "version": selection["version"],
                       "source_directory": str(directory), "preservation_reason": selection["reason"], "commands": commands,
                       "commands_all_succeeded": all(command["exit_code"] == 0 for command in commands), "suites": suites})
    require({key[0] for key in latest} == {"client", "server"}, "Both current client/server targeted stages are required")
    failed = [dict(side=key[0], suite=key[1], name=key[2], **value) for key, value in latest.items() if not value["passed"]]
    require(not failed, f"Latest targeted outcomes must all pass: {failed}")
    summary = {"profile": "new-and-affected-targeted-unit-and-compile-package", "full_suite_run": False,
               "in_game_verified": False, "old_game_matrix_reused_as_fresh": False,
               "previous_version_test_evidence_reused": any(stage["version"] != VERSION for stage in stages),
               "evidence_versions": sorted({stage["version"] for stage in stages}),
               "counting_rule": "Conservative distinct side/suite/display-name identities; repeated names are ANDed within each stage",
               "raw_reported_testcases": sum(suite["tests"] for stage in stages for suite in stage["suites"]),
               "distinct_targeted_cases": len(latest), "all_latest_outcomes_pass": True,
               "failed_command_attempts": sum(command["exit_code"] != 0 for stage in stages for command in stage["commands"]),
               "earlier_report_failures": sum(suite["failures"] for stage in stages for suite in stage["suites"]),
               "earlier_report_errors": sum(suite["errors"] for stage in stages for suite in stage["suites"]),
               "counts_by_side": {side: sum(key[0] == side for key in latest) for side in ["client", "server"]},
               "stage_order": [selection["stage"] for selection in selections], "stages": stages,
               "latest_cases": [dict(side=key[0], suite=key[1], name=key[2], **value) for key, value in sorted(latest.items())]}
    evidence["summary.json"] = (json.dumps(summary, ensure_ascii=False, indent=2) + "\n").encode()
    if manifest_raw is not None:
        evidence["evidence-manifest.json"] = manifest_raw
    evidence["fixtures/tools/package_current.py"] = Path(__file__).read_bytes()
    evidence["fixtures/tools/package_release.py"] = (PROJECT / "tools/package_release.py").read_bytes()
    evidence["README.md"] = (f"本包保存 {VERSION} 两端本轮新增与受影响的定向单元检查与打包证据。"
                             "summary.json 按 side/suite/name 取最后结果，早期失败命令、真实退出码及报告原样保留；"
                             "打包要求每个已报告用例的最后结果通过，以及两端最终构建成功，不声称所有早期尝试都通过；"
                             "各阶段保留实际执行版本与原始 XML；升级前的本轮检查不冒充新版重跑。"
                             "参数化重名用例只作保守去重统计，raw_reported_testcases 保留原报告数量（含重跑）。"
                             "不复用旧游戏矩阵或真实私人模型输入。"
                             "未复跑完整旧矩阵，未运行 Minecraft 或服务器实机复验；"
                             "没有 TPS、帧率或网络场景性能实测证明。"
                             "JAR、资源、许可和 ZIP 完整性检查不能代替实机验证。\n").encode()
    return summary, evidence


def source_entries() -> dict[str, bytes]:
    entries = {}
    for directory in ["src", "examples", "tools", "docs", "client"]:
        for path in files(PROJECT / directory):
            relative = path.relative_to(PROJECT)
            if any(part.lower() in EXCLUDED_PARTS or part.lower().startswith("private-fixture") for part in relative.parts) or path.suffix.lower() in EXCLUDED_SUFFIXES:
                continue
            if tuple(part.lower() for part in relative.parts[:2]) in {
                    ("client", "config"), ("client", "saves"), ("client", "resourcepacks"), ("client", "shaderpacks")}:
                continue
            if path.name.startswith(".env") or path.name.lower() in {"settings.xml", "credentials.json", "token.json", "meplayeractions-client.json", "players.yml"}:
                continue
            require(path.resolve().is_relative_to(PROJECT.resolve()), f"Source link escapes the project: {path}")
            entries["MEPlayerActions/" + relative.as_posix()] = path.read_bytes()
    for path in PROJECT.iterdir():
        if path.is_file() and path.name in {"README.md", "CONTRIBUTING.md", "ARCHITECTURE.md", "THIRD_PARTY_NOTICES.md",
                                          "pom.xml", ".gitignore", ".gitattributes"}:
            entries["MEPlayerActions/" + path.name] = path.read_bytes()
    require("MEPlayerActions/docs/CLIENT_PROTOCOL.md" in entries, "Source ZIP is missing the canonical protocol document")
    require("MEPlayerActions/tools/package_current.py" in entries, "Source ZIP is missing the tracked package tool")
    require("MEPlayerActions/client/gradle/wrapper/gradle-wrapper.jar" in entries, "Source ZIP is missing the Gradle wrapper")
    return entries


def docs_entries() -> dict[str, bytes]:
    docs = RELEASE.documentation_entries()
    docs.update({name: (PROJECT / name).read_bytes() for name in ["README.md", "ARCHITECTURE.md", "THIRD_PARTY_NOTICES.md"]})
    require("docs/CLIENT_PROTOCOL.md" in docs, "Installation ZIP is missing the canonical protocol document")
    return docs


def installer_links(entries: dict[str, bytes]) -> None:
    # Do not invent a published release tag. Missing source links remain explicit source paths.
    link = re.compile(r"\[([^\]\n]+)\]\(([^\s)]+)\)")
    for name, data in list(entries.items()):
        if not name.endswith(".md"):
            continue
        def replace(match: re.Match) -> str:
            label, href = match.group(1), match.group(2)
            if ":" in href or href.startswith("#"):
                return match.group(0)
            path, _, fragment = href.partition("#")
            target = posixpath.normpath(posixpath.join(posixpath.dirname(name), path))
            if target in entries or any(value.startswith(target.rstrip("/") + "/") for value in entries):
                return match.group(0)
            if target == "src/main/java/com/simmc/meplayeractions/client/CLIENT_PROTOCOL.md":
                corrected = posixpath.relpath("docs/CLIENT_PROTOCOL.md", posixpath.dirname(name))
                return f"[{label}]({corrected}{'#' + fragment if fragment else ''})"
            original = (PROJECT / posixpath.dirname(name) / path).resolve()
            require(original.exists() and original.is_relative_to(PROJECT), f"Unresolved current documentation reference: {name}: {href}")
            return f"{label}（完整源码包：`{original.relative_to(PROJECT).as_posix()}`）"
        entries[name] = link.sub(replace, data.decode("utf-8")).encode("utf-8")


def recorded_commands(proof: dict, context: str, *, require_success: bool = False) -> list[dict]:
    records = proof.get("commands", [])
    require(isinstance(records, list) and bool(records), f"{context}: actual command proof required")
    for record in records:
        require(isinstance(record, dict) and type(record.get("exit_code")) is int,
                f"{context}: actual integer exit code required")
        require(not require_success or record["exit_code"] == 0, f"{context}: command failed")
        command = record.get("command", "")
        require((isinstance(command, str) and bool(command.strip()))
                or (isinstance(command, list) and bool(command) and all(isinstance(value, str) and value for value in command)),
                f"{context}: actual command missing")
    return records


def successful_commands(proof: dict, context: str) -> list[dict]:
    return recorded_commands(proof, context, require_success=True)


def build_commands() -> dict:
    path = VALIDATION / "build-commands.json"
    require(path.is_file(), f"Root must save final Gradle/Maven command outcomes in validation-{VERSION}/build-commands.json")
    proof = json.loads(path.read_text(encoding="utf-8-sig"))
    require(isinstance(proof, dict) and proof.get("version") == VERSION, "Build command proof must identify the current release")
    records = successful_commands(proof, "Final build")
    require(len(records) >= 2, "Build command proof needs both sides")
    found = set()
    for record in records:
        command = record.get("command", "")
        if isinstance(command, list):
            command = " ".join(command)
        if "build" in command and "-x test" in command and ("gradle" in command.lower() or "GradleWrapperMain" in command):
            found.add("client")
        if "package" in command and "-DskipTests" in command and ("mvn" in command.lower() or "maven" in command.lower()):
            found.add("server")
    require(found == {"client", "server"}, "Expected final Gradle build -x test and Maven package -DskipTests proofs")
    return proof


def output_paths(server: Path, client: Path, suffix: str = "") -> dict[str, Path]:
    require(isinstance(suffix, str) and (not suffix or re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", suffix)),
            "Artifact suffix must be a short lowercase filename token")
    token = "-" + suffix if suffix else ""
    return {"server_jar": DIST / f"{server.stem}{token}.jar", "client_jar": DIST / f"{client.stem}{token}.jar",
            "server_install": DIST / f"MEPlayerActions-{VERSION}{token}-install.zip",
            "client_install": DIST / f"MEPlayerActions-Client-{VERSION}{token}-install.zip",
            "source": DIST / f"MEPlayerActions-{VERSION}{token}-source.zip",
            "tests": DIST / f"MEPlayerActions-{VERSION}{token}-tests.zip",
            "build_report": DIST / f"MEPlayerActions-{VERSION}{token}-build.json"}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path, default=REFERENCE)
    parser.add_argument("--gradle-cache", type=Path, default=Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches")
    parser.add_argument("--stage-order", nargs="+")
    parser.add_argument("--evidence-manifest", type=Path, help="Ordered current-work stages with their original versions; cannot be combined with --stage-order")
    parser.add_argument("--artifact-suffix", default="", help="Fresh filename suffix; existing deliveries are never replaced")
    args = parser.parse_args()
    server_version = ET.parse(PROJECT / "pom.xml").findtext("m:version", namespaces=RELEASE.NAMESPACE)
    client_version = re.search(r"(?m)^version = '([^']+)'", (CLIENT / "build.gradle").read_text(encoding="utf-8")).group(1)
    require(server_version == client_version == VERSION, f"Current two-sided versions must be {VERSION}")
    server = PROJECT / "target" / f"MEPlayerActions-{VERSION}.jar"
    client = CLIENT / "build/libs" / f"MEPlayerActions-Client-{VERSION}.jar"
    outputs = output_paths(server, client, args.artifact_suffix)
    require(not any(path.exists() for path in outputs.values()), "Preserve old deliveries: an output path already exists")
    server_inputs = [PROJECT / "pom.xml", *files(PROJECT / "src/main"), *sorted((PROJECT / "examples/models").glob("*.bbmodel"))]
    client_inputs = [CLIENT / name for name in ["build.gradle", "settings.gradle", "gradle.properties", "gradle/wrapper/gradle-wrapper.properties"]]
    client_inputs += files(CLIENT / "src/main")
    client_inputs += sorted((PROJECT / "src/main/java/com/simmc/meplayeractions/expression").glob("*.java"))
    client_inputs += [path for path in sorted((PROJECT / "src/main/java/com/simmc/meplayeractions/config").glob("*.java"))
                      if path.name not in {"Settings.java", "PerformanceSettings.java"}]
    fresh(server, server_inputs)
    fresh(client, client_inputs)
    command_proof = build_commands()
    server_proof = validate_server(server)
    client_proof = validate_client(client, args.gradle_cache, args.reference)
    tests, evidence = test_evidence(args.stage_order, args.evidence_manifest)
    sources = source_entries()
    server_install = {f"plugins/{server.name}": server.read_bytes(),
                      "plugins/MEPlayerActions/config.yml": (PROJECT / "src/main/resources/config.yml").read_bytes(), **docs_entries()}
    for model_id in ["ysm_01_jk", "ysm_02_jk"]:
        for relative in [f"models/{model_id}.bbmodel", f"models/{model_id}.manifest.json", f"blueprints/{model_id}.bbmodel"]:
            server_install[f"examples/{relative}"] = (PROJECT / "examples" / relative).read_bytes()
        server_install[f"plugins/ModelEngine/blueprints/meplayeractions/{model_id}.bbmodel"] = (PROJECT / f"examples/blueprints/{model_id}.bbmodel").read_bytes()
    client_install = {f"mods/{client.name}": client.read_bytes(), **docs_entries()}
    for path in files(CLIENT / "src/main/resources"):
        if path.suffix == ".txt" or path.name == "NOTICE.md" or path.name.startswith("LICENSE"):
            client_install[path.relative_to(PROJECT).as_posix()] = path.read_bytes()
    delivery_note = (f"# MEPlayerActions {VERSION} 安装\n\n"
                     f"服务器插件与客户端 MOD 均为 {VERSION}。服务器 JAR 放入 plugins/，客户端 JAR 放入 mods/；替换各自旧 JAR，保留其他插件、模组和已有配置。"
                     "首次安装可使用包内默认 config.yml；升级保留自己的配置，新 performance 与 resource-protection 字段缺失时自动采用默认值，勿直接覆盖整份配置。"
                     "客户端需 Minecraft 1.21.11 / Fabric / Java 21；服务器 ModelEngine 与 GSit 为可选接入。"
                     "仅私人分享无需 ModelEngine 或服务器资源包。使用服务器伪装时，示例蓝图由 ModelEngine 导入并生成原版资源，CraftEngine 继续负责原有资源包合并与下发。\n\n"
                     "客户端接管只下发管理员明确放入 plugins/MEPlayerActions/models/ 的同名完整 BBModel；不会自动回退内置文件或 ME 蓝图。需要两套示例的客户端接管时，可主动复制 examples/models/ 原文件到该目录；没有原文件仍可服务器伪装。\n\n"
                     "三条路径分别为本地私人外观、服务器中继的私人模型分享、服务器模型伪装。私人模型默认仅自己可见，多人共享需玩家明确开启及服务器协商、上传/观看权限；分享不创建 ME 伪装，未安装模组的玩家仍看见原版角色。服务器伪装与私人模型互斥；解除服务器伪装后须显式重新使用私人模型。服务器 private-models/ 属于私人运行时数据，不随源码或安装包分发。\n\n"
                     f"两端分阶段定向单元检查最后结果保守去重共 {tests['distinct_targeted_cases']} 个显示名身份通过；"
                     f"原报告共记录 {tests['raw_reported_testcases']} 项执行（含阶段重跑与参数化重名），详见证据；编译与包内容校验通过。"
                     "本轮未运行完整旧矩阵或游戏/服务器实机复验；未实测 TPS、帧率或网络性能，画面与多人行为由用户测试。交付包不包含私人模型、源数据或用户配置。"
                     f"完整当前源码见 {outputs['source'].name}，证据见 {outputs['tests'].name} / {outputs['build_report'].name}。\n").encode("utf-8")
    server_install["INSTALL.md"] = client_install["INSTALL.md"] = delivery_note
    installer_links(server_install)
    installer_links(client_install)
    report = {"plugin": "MEPlayerActions", "server_version": VERSION, "client_version": VERSION,
              "release_scope": "server-and-client", "new_server_artifacts_created": True,
              "previous_version_test_evidence_reused": tests["previous_version_test_evidence_reused"], "performance_benchmark_run": False,
              "validation_profile": tests["profile"], "in_game_verified": False, "in_game_omitted_at_user_request": True,
              "full_suite_run": False, "old_game_matrix_reused_as_fresh": False,
              "tests": tests, "build_commands": command_proof,
              "server": {**server_proof, "compiler_inputs": fingerprint(server_inputs), **facts(server)},
              "client": {**client_proof, "compiler_inputs": fingerprint(client_inputs), **facts(client)},
              "source_entry_count": len(sources), "zip_integrity_and_content_match": True, "java_release": 21, "minecraft": "1.21.11"}
    evidence["build-commands.json"] = (VALIDATION / "build-commands.json").read_bytes()
    evidence["artifact-build-proof.json"] = (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode()
    # All read-only validation completes before preparing or publishing deliverable files.
    staging = PROJECT / "build" / f"package-{VERSION}-{time.time_ns()}"
    staging.mkdir(parents=True, exist_ok=False)
    prepared = {key: staging / path.name for key, path in outputs.items()}
    prepared["server_jar"].write_bytes(server.read_bytes())
    prepared["client_jar"].write_bytes(client.read_bytes())
    for key, entries in [("server_install", server_install), ("client_install", client_install), ("source", sources), ("tests", evidence)]:
        RELEASE.write_zip(prepared[key], entries)
    report["artifacts"] = {outputs[key].name: facts(path) for key, path in prepared.items() if key != "build_report"}
    prepared["build_report"].write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    DIST.mkdir(parents=True, exist_ok=True)
    require(not any(path.exists() for path in outputs.values()), "An output appeared during validation; refuse replacement")
    for key, output in outputs.items():
        with output.open("xb") as stream:
            stream.write(prepared[key].read_bytes())
        require(facts(output) == facts(prepared[key]), f"Published bytes differ: {output}")
    print(json.dumps({"version": VERSION, "artifactPaths": {key: str(path) for key, path in outputs.items()},
                      "counts": {"targeted_cases": tests["distinct_targeted_cases"], **tests["counts_by_side"],
                                 "server_classes": server_proof["class_count"], "client_classes": client_proof["class_count"],
                                 "source_entries": len(sources)}, "in_game_verified": False}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
