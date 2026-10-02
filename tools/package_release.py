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
    assert len(animation_names) == len(set(animation_names)) == 28
    assert {"idle", "walk", "sit", "crawl_idle", "crawl_walk", "wave", "fly", "hover"} <= set(animation_names)
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
    assert len(npc_names) == len(set(npc_names)) == 29
    assert {"climb", "climb_idle", "crawl_idle", "crawl_walk", "player_jump"} <= set(npc_names)
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
        reference_comparison = {"source": str(reference_path), "sha256": reference_hash,
                                "npc_position_scale": scale, "root_tracks_match": True, "clips": compared}

    protocol = PROJECT / "src/main/java/com/simmc/meplayeractions/client/CLIENT_PROTOCOL.md"
    install = {
        f"plugins/{name}.jar": jar.read_bytes(),
        "plugins/MEPlayerActions/config.yml": (PROJECT / "src/main/resources/config.yml").read_bytes(),
        "plugins/ModelEngine/blueprints/npc/ysm_01_jk_player.bbmodel": model.read_bytes(),
        "plugins/ModelEngine/blueprints/npc/ysm_01_jk_npc.bbmodel": npc_model.read_bytes(),
        "README.md": (PROJECT / "README.md").read_bytes(),
        "docs/CLIENT_PROTOCOL.md": protocol.read_bytes(),
        "docs/ysm_01_jk_player.manifest.json": manifest.read_bytes(),
        "docs/ysm_01_jk_npc.manifest.json": npc_manifest.read_bytes(),
    }
    # The tutorial link remains usable after unpacking the installation ZIP.
    install["README.md"] = install["README.md"].replace(
        b"src/main/java/com/simmc/meplayeractions/client/CLIENT_PROTOCOL.md", b"docs/CLIENT_PROTOCOL.md")
    source_entries = {}
    for root_name in ("src", "examples", "tools"):
        for path in sorted((PROJECT / root_name).rglob("*")):
            if not path.is_file() or "__pycache__" in path.parts or path.suffix in (".pyc", ".log"):
                continue
            source_entries[f"MEPlayerActions/{path.relative_to(PROJECT).as_posix()}"] = path.read_bytes()
    for filename in ("pom.xml", "README.md", ".gitignore"):
        source_entries[f"MEPlayerActions/{filename}"] = (PROJECT / filename).read_bytes()

    DIST.mkdir(parents=True, exist_ok=True)
    delivered_jar = DIST / jar.name
    shutil.copyfile(jar, delivered_jar)
    install_zip = DIST / f"{name}-install.zip"
    source_zip = DIST / f"{name}-source.zip"
    write_zip(install_zip, install)
    write_zip(source_zip, source_entries)
    assert digest(delivered_jar) == digest(jar)
    fingerprint = hashlib.sha256()
    for path in compiler_inputs:
        fingerprint.update(path.relative_to(PROJECT).as_posix().encode())
        fingerprint.update(b"\0")
        fingerprint.update(path.read_bytes())
    validation = {
        "plugin": "MEPlayerActions", "version": version, "authors": ["SIMMC", "Loliiiico"],
        "target": {"paper": "1.21.11", "modelengine": "R4.1.1", "optional_gsit": "3.x (public API checked at runtime)", "java_release": 21},
        "test_totals": totals, "test_suites": suites,
        "compiled_source_fingerprint": fingerprint.hexdigest(),
        "compiled_class_count": len(classes), "animations": animation_names,
        "npc_animations": npc_names, "npc_original_animations_preserved": True,
        "legacy_npc_session_compatibility": ["crawl_idle", "crawl_walk", "player_jump"],
        "owned_disguise_anchor": "independent ME display pivot, anchored to player feet; no player mounting",
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
                      for p in (delivered_jar, install_zip, source_zip)},
        "zip_integrity_and_content_match": True, "dependency_jars_included": False,
        "in_game_verified": False, "resource_pack_generated": False,
        "client_local_rendering_implemented": False,
    }
    validation_path = DIST / f"{name}-validation.json"
    validation_path.write_text(json.dumps(validation, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"version": version, "tests": totals, "jar": str(delivered_jar),
                      "install_zip": str(install_zip), "source_zip": str(source_zip),
                      "validation": str(validation_path)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
