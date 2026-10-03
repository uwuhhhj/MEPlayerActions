"""Validate the latest Maven build and package only this project's deliverables."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import xml.etree.ElementTree as ET
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
WORKSPACE = PROJECT.parents[1]
DIST = WORKSPACE / "dist"
NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
ZIP_TIME = (2026, 10, 3, 0, 0, 0)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_zip(path: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, data)
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None, f"Corrupt ZIP: {path}"
        assert set(archive.namelist()) == set(entries)
        for name, expected in entries.items():
            assert archive.read(name) == expected, f"Stale entry: {name}"


def main() -> None:
    version = ET.parse(PROJECT / "pom.xml").findtext("m:version", namespaces=NAMESPACE)
    assert version and re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?", version)
    name = f"MEPlayerActions-{version}"
    jar = PROJECT / "target" / f"{name}.jar"
    assert jar.is_file(), "Run mvn package first"

    compiler_inputs = [PROJECT / "pom.xml"]
    compiler_inputs += sorted((PROJECT / "src/main/java").rglob("*.java"))
    compiler_inputs += sorted((PROJECT / "src/main/resources").rglob("*"))
    compiler_inputs = [p for p in compiler_inputs if p.is_file()]
    assert all(p.stat().st_mtime_ns <= jar.stat().st_mtime_ns for p in compiler_inputs), \
        "Source changed after the JAR; rerun mvn package"

    reports = sorted((PROJECT / "target/surefire-reports").glob("TEST-*.xml"))
    assert reports, "Test reports are required"
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    suites = []
    for report in reports:
        suite = ET.parse(report).getroot()
        suites.append(suite.attrib["name"])
        for key in totals:
            totals[key] += int(suite.attrib.get(key, "0"))
    assert totals["tests"] > 0 and not any(totals[k] for k in ("failures", "errors", "skipped")), totals
    for source in (PROJECT / "src/test/java").rglob("*Test.java"):
        relative = source.relative_to(PROJECT / "src/test/java").with_suffix("")
        suite_name = ".".join(relative.parts)
        report = PROJECT / "target/surefire-reports" / f"TEST-{suite_name}.xml"
        assert report.is_file() and report.stat().st_mtime_ns >= source.stat().st_mtime_ns, \
            f"Missing or outdated test report: {source.name}"

    with zipfile.ZipFile(jar) as archive:
        assert archive.testzip() is None
        descriptor = archive.read("plugin.yml").decode("utf-8")
        assert f"version: '{version}'" in descriptor
        assert "main: com.simmc.meplayeractions.MEPlayerActionsPlugin" in descriptor
        assert "api-version: '1.21.11'" in descriptor
        assert "depend: [ModelEngine]" in descriptor
        assert "authors: [SIMMC, Loliiiico]" in descriptor, "Missing updated author credit"
        assert "mact.disguise.effects:" in descriptor, "Missing disguise effect permission"
        assert "  meplayeractions:" in descriptor and "usage: /meplayeractions help" in descriptor
        assert "  mact:" not in descriptor and "aliases:" not in descriptor, "Legacy command aliases were retained"
        assert "${" not in descriptor, "Unfiltered plugin descriptor"
        expected_descriptor = (PROJECT / "src/main/resources/plugin.yml").read_bytes().replace(
            b"${version}", version.encode("utf-8"))
        assert archive.read("plugin.yml") == expected_descriptor, "Stale plugin metadata"
        assert archive.read("config.yml") == (PROJECT / "src/main/resources/config.yml").read_bytes()
        for model_file in (PROJECT / "examples/blueprints/npc").glob("*.bbmodel"):
            assert archive.read(f"models/{model_file.name}") == model_file.read_bytes(), "Stale server client asset"
        classes = [n for n in archive.namelist() if n.endswith(".class")]
        assert "com/simmc/meplayeractions/MEPlayerActionsPlugin.class" in classes
        assert "com/simmc/meplayeractions/action/DisguiseOptions.class" in classes
        assert "com/simmc/meplayeractions/gameplay/DisguiseEffects.class" in classes
        assert "com/simmc/meplayeractions/me/LegacyNpcAnimations.class" in classes
        assert all(n.startswith("com/simmc/meplayeractions/") for n in classes), "Dependency classes were bundled"
        assert not any(n.endswith(".jar") for n in archive.namelist()), "Dependency JAR was bundled"
        for class_name in classes:
            data = archive.read(class_name)
            magic, minor, major = struct.unpack(">IHH", data[:8])
            assert magic == 0xCAFEBABE and major == 65 and minor == 0, f"Not Java 21: {class_name}"
            compiled = PROJECT / "target/classes" / class_name
            assert compiled.read_bytes() == data, f"Stale compiled class: {class_name}"

    model = PROJECT / "examples/blueprints/npc/ysm_01_jk_player.bbmodel"
    manifest = PROJECT / "examples/blueprints/npc/ysm_01_jk_player.manifest.json"
    facts = json.loads(manifest.read_text(encoding="utf-8"))
    assert digest(model) == facts["derived_model"]["sha256"]
    parsed_model = json.loads(model.read_text(encoding="utf-8"))
    animation_names = [a["name"] for a in parsed_model["animations"]]
    assert len(animation_names) == len(set(animation_names)) == 29
    assert {"idle", "walk", "sit", "crawl_idle", "crawl_walk", "wave", "fly", "hover", "bed_sleep"} <= set(animation_names)
    for source_name in ("npc_source", "original_v9_source"):
        source = PROJECT.parent / facts[source_name]["path"]
        if source.exists():
            assert digest(source) == facts[source_name]["sha256"], "Original model changed"

    npc_model = PROJECT / "examples/blueprints/npc/ysm_01_jk_npc.bbmodel"
    npc_manifest = npc_model.with_suffix(".manifest.json")
    npc_facts = json.loads(npc_manifest.read_text(encoding="utf-8"))
    assert digest(npc_model) == npc_facts["derived_model"]["sha256"]
    npc_parsed = json.loads(npc_model.read_text(encoding="utf-8"))
    npc_names = [a["name"] for a in npc_parsed["animations"]]
    assert len(npc_names) == len(set(npc_names)) == 30
    assert {"climb", "climb_idle", "crawl_idle", "crawl_walk", "player_jump", "bed_sleep"} <= set(npc_names)
    npc_source = PROJECT.parent / npc_facts["npc_source"]["path"]
    if npc_source.exists():
        original_npc = json.loads(npc_source.read_text(encoding="utf-8"))
        assert npc_parsed["animations"][:26] == original_npc["animations"]
        assert all(npc_parsed[f] == original_npc[f] for f in ("elements", "outliner", "textures", "resolution"))

    # Record the user-supplied crawl reference when available in this workspace.
    # It is evidence, not an installation dependency or an asset to overwrite.
    reference_path = PROJECT.parent / "JK酒狐/JK酒狐_ME通用精简.bbmodel"
    reference_comparison = None
    if reference_path.is_file():
        reference_hash = digest(reference_path)
        reference_model = json.loads(reference_path.read_text(encoding="utf-8"))
        def root_pose(parsed: dict, clip_name: str) -> dict:
            clip = next(a for a in parsed["animations"] if a["name"] == clip_name)
            track = next(t for t in clip["animators"].values() if t["name"] == "Root")
            return {channel: [float(next(f for f in track["keyframes"] if f["channel"] == channel)
                                   ["data_points"][0][axis]) for axis in ("x", "y", "z")]
                    for channel in ("rotation", "position")}
        scale = facts["position_scale_derived_from_sit"]
        compared = {}
        for source_name, target_name in (("climbing", "crawl_idle"), ("climb", "crawl_walk")):
            source_pose = root_pose(reference_model, source_name)
            target_pose = root_pose(npc_parsed, target_name)
            assert source_pose["rotation"] == target_pose["rotation"]
            assert all(abs(a * scale - b) < 0.0000001 for a, b in
                       zip(source_pose["position"], target_pose["position"]))
            compared[target_name] = {"reference_clip": source_name, "reference_root": source_pose,
                                     "npc_root": target_pose}
        assert digest(reference_path) == reference_hash
        reference_comparison = {"source": reference_path.relative_to(PROJECT.parent).as_posix(), "sha256": reference_hash,
                                "npc_position_scale": scale, "root_tracks_match": True, "clips": compared}

    protocol = PROJECT / "src/main/java/com/simmc/meplayeractions/client/CLIENT_PROTOCOL.md"
    reference = PROJECT / "examples/blueprints/npc/ysm_02_jk.bbmodel"
    reference_manifest = json.loads(reference.with_suffix(".manifest.json").read_text(encoding="utf-8"))
    reference_source = PROJECT.parent / reference_manifest["source"]["path"]
    assert digest(reference_source) == reference_manifest["source"]["sha256"]
    assert digest(reference) == reference_manifest["derived_model"]["sha256"]
    reference_original = json.loads(reference_source.read_text(encoding="utf-8"))
    reference_export = json.loads(reference.read_text(encoding="utf-8"))
    assert reference_export["model_identifier"] == "ysm_02_jk"
    assert all(reference_original.get(key) == reference_export.get(key) for key in ("elements", "outliner", "textures", "animations"))
    install = {
        f"plugins/{name}.jar": jar.read_bytes(),
        "plugins/MEPlayerActions/config.yml": (PROJECT / "src/main/resources/config.yml").read_bytes(),
        "plugins/ModelEngine/blueprints/npc/ysm_01_jk_player.bbmodel": model.read_bytes(),
        "plugins/ModelEngine/blueprints/npc/ysm_01_jk_npc.bbmodel": npc_model.read_bytes(),
        "plugins/ModelEngine/blueprints/npc/ysm_02_jk.bbmodel": (PROJECT / "examples/blueprints/npc/ysm_02_jk.bbmodel").read_bytes(),
        "README.md": (PROJECT / "README.md").read_bytes(),
        "docs/CLIENT_PROTOCOL.md": protocol.read_bytes(),
        "docs/ysm_01_jk_player.manifest.json": manifest.read_bytes(),
        "docs/ysm_01_jk_npc.manifest.json": npc_manifest.read_bytes(),
        "docs/ysm_02_jk.manifest.json": (PROJECT / "examples/blueprints/npc/ysm_02_jk.manifest.json").read_bytes(),
    }
    # The tutorial link remains usable after unpacking the installation ZIP.
    install["README.md"] = install["README.md"].replace(
        b"src/main/java/com/simmc/meplayeractions/client/CLIENT_PROTOCOL.md", b"docs/CLIENT_PROTOCOL.md")
    architecture = PROJECT / "ARCHITECTURE.md"
    if architecture.is_file():
        install["ARCHITECTURE.md"] = architecture.read_bytes()
    source_entries = {}
    for root_name in ("src", "examples", "tools"):
        for path in sorted((PROJECT / root_name).rglob("*")):
            if not path.is_file() or "__pycache__" in path.parts or path.suffix in (".pyc", ".log"):
                continue
            source_entries[f"MEPlayerActions/{path.relative_to(PROJECT).as_posix()}"] = path.read_bytes()
    for filename in ("pom.xml", "README.md", ".gitignore"):
        source_entries[f"MEPlayerActions/{filename}"] = (PROJECT / filename).read_bytes()
    if architecture.is_file():
        source_entries["MEPlayerActions/ARCHITECTURE.md"] = architecture.read_bytes()

    client = PROJECT / "client"
    client_jar = client / "build/libs" / f"MEPlayerActions-Client-{version}.jar"
    assert client_jar.is_file(), "Run client gradlew build first"
    client_inputs = [client / p for p in ("build.gradle", "settings.gradle", "gradle.properties")]
    client_inputs += [p for p in (client / "src/main").rglob("*") if p.is_file()]
    client_inputs += list((PROJECT / "examples/blueprints/npc").glob("*.bbmodel"))
    assert all(p.stat().st_mtime_ns <= client_jar.stat().st_mtime_ns for p in client_inputs), "Client source changed after JAR"
    client_totals = dict(tests=0, failures=0, errors=0, skipped=0)
    client_reports = list((client / "build/test-results/test").glob("TEST-*.xml"))
    assert client_reports, "Client test reports required"
    client_report_names = set()
    for report in client_reports:
        suite = ET.parse(report).getroot()
        client_report_names.add(suite.attrib["name"])
        for key in client_totals:
            client_totals[key] += int(suite.attrib.get(key, "0"))
    assert client_totals["tests"] > 0 and not any(client_totals[k] for k in ("failures", "errors", "skipped")), client_totals
    for test_source in (client / "src/test/java").rglob("*Test.java"):
        suite_name = ".".join(test_source.relative_to(client / "src/test/java").with_suffix("").parts)
        assert suite_name in client_report_names, f"Missing client test suite: {suite_name}"
        assert (client / "build/test-results/test" / f"TEST-{suite_name}.xml").stat().st_mtime_ns >= test_source.stat().st_mtime_ns
    with zipfile.ZipFile(client_jar) as archive:
        assert archive.testzip() is None
        metadata = json.loads(archive.read("fabric.mod.json"))
        assert metadata["version"] == version and metadata["id"] == "meplayeractions"
        assert metadata["environment"] == "client" and metadata["depends"]["minecraft"] == "~1.21.11"
        assert metadata["depends"]["java"] == ">=21"
        assert not any(n.endswith(".jar") for n in archive.namelist()), "Unexpected bundled client dependency"
        for model_file in (PROJECT / "examples/blueprints/npc").glob("*.bbmodel"):
            assert archive.read(f"assets/meplayeractions/models/{model_file.name}") == model_file.read_bytes()
        client_classes = [n for n in archive.namelist() if n.endswith(".class")]
        assert "com/simmc/meplayeractions/client/MEPlayerActionsClient.class" in client_classes
        for name_in_jar in client_classes:
            magic, minor, major = struct.unpack(">IHH", archive.read(name_in_jar)[:8])
            assert magic == 0xCAFEBABE and minor == 0 and major == 65, f"Not Java 21: {name_in_jar}"
    for path in client.rglob("*"):
        relative = path.relative_to(client)
        if not path.is_file() or any(part in {"build", ".gradle", "run", "__pycache__"} for part in relative.parts):
            continue
        if path.suffix in {".log", ".pyc"}:
            continue
        source_entries[f"MEPlayerActions/client/{relative.as_posix()}"] = path.read_bytes()

    DIST.mkdir(parents=True, exist_ok=True)
    delivered_jar = DIST / jar.name
    shutil.copyfile(jar, delivered_jar)
    install_zip = DIST / f"{name}-install.zip"
    source_zip = DIST / f"{name}-source.zip"
    write_zip(install_zip, install)
    write_zip(source_zip, source_entries)
    assert digest(delivered_jar) == digest(jar)
    delivered_client = DIST / client_jar.name
    shutil.copyfile(client_jar, delivered_client)
    client_install_zip = DIST / f"MEPlayerActions-Client-{version}-install.zip"
    write_zip(client_install_zip, {f"mods/{client_jar.name}": client_jar.read_bytes(),
                                   "README.md": (PROJECT / "README.md").read_bytes()})
    assert digest(delivered_client) == digest(client_jar)
    game_report_path = client / "build/e2e/results.json"
    game_report = json.loads(game_report_path.read_text(encoding="utf-8")) if game_report_path.is_file() else None
    observer_report_path = client / "build/observer-test/results/observer-results.json"
    observer_report = json.loads(observer_report_path.read_text(encoding="utf-8")) if observer_report_path.is_file() else None
    tested_at = max(jar.stat().st_mtime_ns, client_jar.stat().st_mtime_ns)
    game_current = bool(game_report and game_report_path.stat().st_mtime_ns >= tested_at)
    observer_current = bool(observer_report and observer_report_path.stat().st_mtime_ns >= tested_at)
    game_passed = bool(game_current and game_report.get("passed") is True)
    observer_passed = bool(observer_current and observer_report.get("passed") is True
                           and observer_report.get("installedMpa") is False)
    # A release must carry proof from these exact JARs, not an earlier successful run.
    game_launch_path = game_report_path.parent / "launch.json"
    observer_launch_path = observer_report_path.parent / "launch.json"
    assert game_passed and observer_passed, "Fresh, passing two-client game reports are required"
    game_checks = {check["name"]: check["passed"] for check in game_report["checks"]}
    observer_checks = {check["name"]: check["passed"] for check in observer_report["checks"]}
    for check_name in (
            "other-undisguiseRestoresNativePlayer", "undisguisedRemoteKeepsMoving",
            "other-undisguise-againRestoresNativePlayer", "other-second-modelReferenceModelFallsBackToMe",
            "other-second-undisguiseRestoresNativePlayer", "other-first-modelLocalModelReturns",
            "own-second-modelReferenceModelFallsBackToMe", "ownSecondUndisguiseRestoresPlayer",
            "ownFirstModelReturnsAfterReference"):
        assert game_checks.get(check_name) is True, f"Missing lifecycle regression proof: {check_name}"
    assert observer_checks.get("AUndisguiseRestoresNativePlayerForUnmoddedB") is True
    game_launch = json.loads(game_launch_path.read_text(encoding="utf-8"))
    observer_launch = json.loads(observer_launch_path.read_text(encoding="utf-8"))
    assert game_launch["clientJarSha256"].lower() == digest(client_jar), "Game run used another client JAR"
    assert game_launch["serverJarSha256"].lower() == digest(jar), "Game run used another server JAR"
    assert game_report["testedClientSha256"].lower() == digest(client_jar), "Loaded client differs from launch proof"
    assert game_report["testedServerSha256"].lower() == digest(jar), "Server report differs from launch proof"
    assert not any("MEPlayerActions-Client" in mod for mod in observer_launch["mods"]), \
        "Observer run accidentally installed the client mod"
    evidence = {}
    for label, report_path, report, current in (
            ("client", game_report_path, game_report, game_current),
            ("observer-without-mpa", observer_report_path, observer_report, observer_current)):
        if not current:
            continue
        evidence[f"{label}/results.json"] = report_path.read_bytes()
        evidence[f"{label}/launch.json"] = (report_path.parent / "launch.json").read_bytes()
        for relative in report.get("screenshots", []):
            assert isinstance(relative, str)
            screenshot = (report_path.parent / relative).resolve()
            assert screenshot.is_relative_to(report_path.parent.resolve()) and screenshot.suffix == ".png"
            assert screenshot.is_file(), f"Missing validation screenshot: {relative}"
            assert screenshot.stat().st_mtime_ns >= tested_at, f"Stale validation screenshot: {relative}"
            evidence[f"{label}/{relative}"] = screenshot.read_bytes()
    evidence_zip = None
    if evidence:
        for filename in ("model-bounds-probe.txt", "model-bounds-corrected.txt", "animation-axes-research.md"):
            evidence_file = client / "build/observer-test" / filename
            if evidence_file.is_file():
                evidence[f"model-coordinate-checks/{filename}"] = evidence_file.read_bytes()
        server_proof_path = PROJECT / "build/e2e-server/matrix-proof.json"
        if server_proof_path.is_file():
            server_proof = json.loads(server_proof_path.read_text(encoding="utf-8"))
            assert server_proof["serverJarSha256"].lower() == digest(jar), "Stale server matrix proof"
            assert server_proof["passed"] is True, "Server matrix proof failed"
            evidence["server/matrix-proof.json"] = server_proof_path.read_bytes()
        evidence["tested-artifacts.json"] = (json.dumps({
            "minecraft": "1.21.11", "server_sha256": digest(jar), "client_sha256": digest(client_jar),
            "client_passed": game_passed, "observer_without_mpa_passed": observer_passed,
        }, indent=2) + "\n").encode("utf-8")
        evidence_zip = DIST / f"{name}-tests.zip"
        write_zip(evidence_zip, evidence)

    def report_summary(report: dict | None) -> dict | None:
        if report is None:
            return None
        return {key: report[key] for key in ("passed", "complete", "minecraft", "clientVersion",
                "installedMpa", "testedClientSha256", "testedServerSha256", "artifactProof",
                "checks", "screenshots", "enabledPacks") if key in report}

    fingerprint = hashlib.sha256()
    for path in compiler_inputs:
        fingerprint.update(path.relative_to(PROJECT).as_posix().encode())
        fingerprint.update(b"\0")
        fingerprint.update(path.read_bytes())
    validation = {
        "plugin": "MEPlayerActions", "version": version, "authors": ["SIMMC", "Loliiiico"],
        "target": {"paper": "1.21.11", "modelengine": "R4.1.1", "optional_gsit": "3.5.1 (tested; public posture API checked at runtime)", "java_release": 21},
        "test_totals": totals, "test_suites": suites, "client_test_totals": client_totals,
        "client_target": {"minecraft": "1.21.11", "loader": "Fabric", "java_release": 21,
                          "renderer": "RenderCommandQueue custom geometry", "protocol": 3},
        "compiled_source_fingerprint": fingerprint.hexdigest(),
        "compiled_class_count": len(classes), "animations": animation_names,
        "npc_animations": npc_names, "npc_original_animations_preserved": True,
        "legacy_npc_session_compatibility": ["crawl_idle", "crawl_walk", "player_jump"],
        "owned_disguise_anchor": "shared visual history; player feet, native bed center/top, or GSit seat contact plane; no player mounting",
        "owned_disguise_audience": {"show_self_default": True, "strict_distance_blocks_default": 8,
                                    "max_other_viewers_default": 10, "selection": "nearest tracked eligible players, UUID tie break",
                                    "update_ticks": 1, "protocol_recipient_filter": True},
        "disguise_effect_allowlist": ["slowness"], "disguise_model_argument_required_first": True,
        "root_command": "meplayeractions", "command_aliases": [],
        "organized_subcommands": ["help", "disguise", "undisguise", "models", "attach", "menu", "animations",
                                   "play", "stop", "reset", "pose", "sync", "status", "reload"],
        "bundled_animation_labels": "Chinese defaults, with configuration overrides and custom action label precedence",
        "crawl_reference_comparison": reference_comparison,
        "compile_dependency_hashes": {p.name: digest(p) for p in
            [PROJECT.parent / "ModelEngine-R4.1.1.jar", *sorted((PROJECT / "build/deps").glob("paper-api-*.jar"))]
            if p.is_file()},
        "artifacts": {p.name: {"sha256": digest(p), "bytes": p.stat().st_size}
                      for p in (delivered_jar, install_zip, source_zip, delivered_client, client_install_zip, evidence_zip)
                      if p is not None},
        "zip_integrity_and_content_match": True, "dependency_jars_included": False,
        "in_game_verified": game_passed,
        "two_client_in_game_verified": game_passed and observer_passed,
        "game_validation_current_for_artifacts": game_current,
        "observer_validation_current_for_artifacts": observer_current,
        "game_validation": report_summary(game_report),
        "observer_game_validation": report_summary(observer_report),
        "resource_pack_in_install_zip": False,
        "client_local_rendering_implemented": True,
    }
    validation_path = DIST / f"{name}-validation.json"
    validation_path.write_text(json.dumps(validation, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"version": version, "tests": totals, "jar": str(delivered_jar),
                      "install_zip": str(install_zip), "source_zip": str(source_zip),
                      "client_jar": str(delivered_client), "client_install_zip": str(client_install_zip),
                      "validation": str(validation_path), "test_evidence_zip": str(evidence_zip) if evidence_zip else None},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
