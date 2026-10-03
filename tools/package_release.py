"""Validate the latest Maven build and package only this project's deliverables."""
from __future__ import annotations

import hashlib
import json
import math
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
MODEL_IDS = ("ysm_01_jk", "ysm_02_jk")


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path: Path) -> dict:
    # Acceptance helpers may run in either Windows PowerShell or PowerShell 7.
    return json.loads(path.read_text(encoding="utf-8-sig"))


def numeric_axis(value: object) -> bool:
    # Blockbench stores channel values as numeric strings; expressions must be absent in ME assets.
    if type(value) not in (int, float, str):
        return False
    if isinstance(value, str) and not re.fullmatch(r"[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?", value.strip()):
        return False
    try:
        return math.isfinite(float(value))
    except (ValueError, OverflowError):
        return False


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
    compiler_inputs += sorted((PROJECT / "examples/models").glob("*.bbmodel"))
    compiler_inputs = [p for p in compiler_inputs if p.is_file()]
    assert all(p.stat().st_mtime_ns <= jar.stat().st_mtime_ns for p in compiler_inputs), \
        "Source changed after the JAR; rerun mvn package"

    reports = sorted((PROJECT / "target/surefire-reports").glob("TEST-*.xml"))
    assert reports, "Test reports are required"
    assert all(report.stat().st_mtime_ns >= max(p.stat().st_mtime_ns for p in compiler_inputs)
               for report in reports), "Server tests predate the current build inputs"
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
        for model_file in (PROJECT / "examples/models").glob("*.bbmodel"):
            assert archive.read(f"models/{model_file.name}") == model_file.read_bytes(), "Stale server client asset"
        classes = [n for n in archive.namelist() if n.endswith(".class")]
        assert "com/simmc/meplayeractions/MEPlayerActionsPlugin.class" in classes
        assert "com/simmc/meplayeractions/action/DisguiseOptions.class" in classes
        assert "com/simmc/meplayeractions/gameplay/DisguiseEffects.class" in classes
        assert "com/simmc/meplayeractions/me/YsmAnimations.class" in classes
        assert all(n.startswith("com/simmc/meplayeractions/") for n in classes), "Dependency classes were bundled"
        assert not any(n.endswith(".jar") for n in archive.namelist()), "Dependency JAR was bundled"
        for class_name in classes:
            data = archive.read(class_name)
            magic, minor, major = struct.unpack(">IHH", data[:8])
            assert magic == 0xCAFEBABE and major == 65 and minor == 0, f"Not Java 21: {class_name}"
            compiled = PROJECT / "target/classes" / class_name
            assert compiled.read_bytes() == data, f"Stale compiled class: {class_name}"

    model_facts = {}
    model_ids = MODEL_IDS
    for directory in ("models", "blueprints"):
        assert {p.stem for p in (PROJECT / "examples" / directory).glob("*.bbmodel")} == set(model_ids), \
            f"Unexpected example models in {directory}"
    for model_id in model_ids:
        raw_path = PROJECT / f"examples/models/{model_id}.bbmodel"
        fact = read_json(raw_path.with_suffix(".manifest.json"))
        raw_model = read_json(raw_path)
        baked_path = PROJECT / f"examples/blueprints/{model_id}.bbmodel"
        baked = read_json(baked_path)
        assert fact["model_id"] == model_id and raw_model["model_identifier"] == model_id
        assert digest(raw_path) == fact["raw_sha256"], f"Stale manifest: {model_id}"
        assert fact["geometry_and_original_size_preserved"] is True
        assert raw_model["mpa_runtime"]["original_size"] is True
        assert raw_model["mpa_runtime"]["physics_step_seconds"] == .01
        assert len(raw_model["animations"]) == 60 and len(baked["animations"]) == 57
        raw_names = [action["name"] for action in raw_model["animations"]]
        baked_names = [action["name"] for action in baked["animations"]]
        assert raw_names == fact["animations"] and len(set(raw_names)) == len(raw_names)
        assert len(set(baked_names)) == len(baked_names)
        assert set(baked_names) == set(raw_names) - {"pre_parallel0", "parallel1", "parallel2"}
        assert all(raw_model[key] == baked[key] for key in ("elements", "outliner", "textures", "resolution"))
        for action in baked["animations"]:
            for animator in action["animators"].values():
                for frame in animator["keyframes"]:
                    for point in frame["data_points"]:
                        for axis in ("x", "y", "z"):
                            assert numeric_axis(point[axis]), \
                                f"Non-numeric ME keyframe: {model_id}/{action['name']}/{axis}"
        appearance = PROJECT.parent / fact["appearance_source"]
        if appearance.exists():
            assert digest(appearance) == fact["appearance_sha256"], f"Appearance source changed: {model_id}"
            source_appearance = read_json(appearance)
            assert all(raw_model[key] == source_appearance[key] for key in ("elements", "outliner", "resolution")), \
                f"Original model geometry was changed: {model_id}"
        animation_source = WORKSPACE / "dist/ysm_07_jk.bbmodel"
        if animation_source.exists():
            assert digest(animation_source) == fact["animation_source_sha256"], "Original animation source changed"
        fact["baked_sha256"] = digest(baked_path)
        model_facts[model_id] = fact
    animation_names = model_facts["ysm_01_jk"]["animations"]
    assert len(set(animation_names)) == 60
    protocol = PROJECT / "src/main/java/com/simmc/meplayeractions/client/CLIENT_PROTOCOL.md"
    install = {
        f"plugins/{name}.jar": jar.read_bytes(),
        "plugins/MEPlayerActions/config.yml": (PROJECT / "src/main/resources/config.yml").read_bytes(),
        "README.md": (PROJECT / "README.md").read_bytes(),
        "docs/CLIENT_PROTOCOL.md": protocol.read_bytes(),
        "docs/CLIENT_RESOURCE_PACK.md": (PROJECT / "docs/CLIENT_RESOURCE_PACK.md").read_bytes(),
        "THIRD_PARTY_NOTICES.md": (PROJECT / "THIRD_PARTY_NOTICES.md").read_bytes(),
        "tools/build_client_resource_pack.py": (PROJECT / "tools/build_client_resource_pack.py").read_bytes(),
    }
    for model_id in model_ids:
        install[f"plugins/MEPlayerActions/models/{model_id}.bbmodel"] = (PROJECT / f"examples/models/{model_id}.bbmodel").read_bytes()
        install[f"plugins/ModelEngine/blueprints/meplayeractions/{model_id}.bbmodel"] = (PROJECT / f"examples/blueprints/{model_id}.bbmodel").read_bytes()
        install[f"docs/{model_id}.manifest.json"] = (PROJECT / f"examples/models/{model_id}.manifest.json").read_bytes()
    # The tutorial link remains usable after unpacking the installation ZIP.
    install["README.md"] = install["README.md"].replace(
        b"src/main/java/com/simmc/meplayeractions/client/CLIENT_PROTOCOL.md", b"docs/CLIENT_PROTOCOL.md")
    architecture = PROJECT / "ARCHITECTURE.md"
    if architecture.is_file():
        install["ARCHITECTURE.md"] = architecture.read_bytes()
    source_entries = {}
    for root_name in ("src", "examples", "tools", "docs"):
        for path in sorted((PROJECT / root_name).rglob("*")):
            if not path.is_file() or "__pycache__" in path.parts or path.suffix in (".pyc", ".log"):
                continue
            source_entries[f"MEPlayerActions/{path.relative_to(PROJECT).as_posix()}"] = path.read_bytes()
    for filename in ("pom.xml", "README.md", ".gitignore", "THIRD_PARTY_NOTICES.md"):
        source_entries[f"MEPlayerActions/{filename}"] = (PROJECT / filename).read_bytes()
    if architecture.is_file():
        source_entries["MEPlayerActions/ARCHITECTURE.md"] = architecture.read_bytes()
    source_entries["MEPlayerActions/docs/CLIENT_PROTOCOL.md"] = protocol.read_bytes()

    client = PROJECT / "client"
    client_jar = client / "build/libs" / f"MEPlayerActions-Client-{version}.jar"
    assert client_jar.is_file(), "Run client gradlew build first"
    client_inputs = [client / p for p in ("build.gradle", "settings.gradle", "gradle.properties")]
    client_inputs += [p for p in (client / "src/main").rglob("*") if p.is_file()]
    client_inputs += list((PROJECT / "examples/models").glob("*.bbmodel"))
    client_inputs += list((PROJECT / "src/main/java/com/simmc/meplayeractions/expression").glob("*.java"))
    client_inputs += [PROJECT / "src/main/java/com/simmc/meplayeractions/config/AnimationLabels.java"]
    assert all(p.stat().st_mtime_ns <= client_jar.stat().st_mtime_ns for p in client_inputs), "Client source changed after JAR"
    client_totals = dict(tests=0, failures=0, errors=0, skipped=0)
    client_reports = list((client / "build/test-results/test").glob("TEST-*.xml"))
    assert client_reports, "Client test reports required"
    assert all(report.stat().st_mtime_ns >= max(p.stat().st_mtime_ns for p in client_inputs)
               for report in client_reports), "Client tests predate the current build inputs"
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
        assert set(metadata["depends"]) == {"fabricloader", "minecraft", "java", "fabric-api"}, \
            "Independent client acquired a server engine dependency"
        assert not any(n.endswith(".jar") for n in archive.namelist()), "Unexpected bundled client dependency"
        for model_file in (PROJECT / "examples/models").glob("*.bbmodel"):
            assert f"assets/meplayeractions/models/{model_file.name}" not in archive.namelist(), "Server model leaked into the independent client"
        for asset in (client / "src/main/resources/assets/meplayeractions/builtin").rglob("*"):
            if asset.is_file():
                assert archive.read(asset.relative_to(client / "src/main/resources").as_posix()) == asset.read_bytes()
        assert "com/simmc/meplayeractions/client/ui/AnimationWheelScreen.class" in archive.namelist()
        assert "com/simmc/meplayeractions/client/ui/ModelSettingsScreen.class" in archive.namelist()
        client_classes = [n for n in archive.namelist() if n.endswith(".class")]
        assert "com/simmc/meplayeractions/client/MEPlayerActionsClient.class" in client_classes
        for name_in_jar in client_classes:
            magic, minor, major = struct.unpack(">IHH", archive.read(name_in_jar)[:8])
            assert magic == 0xCAFEBABE and minor == 0 and major == 65, f"Not Java 21: {name_in_jar}"
    for path in client.rglob("*"):
        relative = path.relative_to(client)
        if not path.is_file() or any(part in {"build", ".gradle", "run", "logs", "__pycache__"} for part in relative.parts):
            continue
        if path.suffix in {".log", ".pyc"}:
            continue
        source_entries[f"MEPlayerActions/client/{relative.as_posix()}"] = path.read_bytes()

    delivered_jar = DIST / jar.name
    install_zip = DIST / f"{name}-install.zip"
    source_zip = DIST / f"{name}-source.zip"
    delivered_client = DIST / client_jar.name
    client_install_zip = DIST / f"MEPlayerActions-Client-{version}-install.zip"
    game_report_path = client / "build/e2e/results.json"
    game_report = read_json(game_report_path) if game_report_path.is_file() else None
    observer_report_path = client / "build/observer-test/results/observer-results.json"
    observer_report = read_json(observer_report_path) if observer_report_path.is_file() else None
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
    assert game_report["clientVersion"] == version and game_report["minecraft"] == "1.21.11"
    assert observer_report.get("complete") is True and observer_report["minecraft"] == "1.21.11"
    assert game_report["artifactProof"]["clientFromJar"] is True, "Client acceptance ran from development classes"
    game_checks = {check["name"]: check["passed"] for check in game_report["checks"]}
    observer_checks = {check["name"]: check["passed"] for check in observer_report["checks"]}
    assert game_checks and all(check["passed"] is True for check in game_report["checks"]), "Client report contains a failed check"
    assert observer_checks and all(check["passed"] is True for check in observer_report["checks"]), "Observer report contains a failed check"
    for check_name in (
            "other-undisguiseRestoresNativePlayer", "undisguisedRemoteKeepsMoving",
            "other-undisguise-againRestoresNativePlayer", "other-second-modelReferenceModelLocalWithExpressions",
            "other-second-undisguiseRestoresNativePlayer", "other-first-modelLocalModelReturns",
            "own-second-modelReferenceModelLocalWithExpressions", "ownSecondUndisguiseRestoresPlayer",
            "ownFirstModelReturnsAfterReference", "boatDedicatedLocalClip", "extraDedicatedLocalClip", "hungerExpressionRuntime",
            "firstPersonAccessoryTimelineRuns", "thirdPersonAccessoryStatePersists",
            "hiddenSelfAccessoryTimelineRuns", "hiddenSelfAccessoryStatePersists",
            "firstPersonAccessoryStartsClear", "firstPersonAccessoryNotPremature",
            "firstPersonAccessoryEventDeadline", "firstPersonAccessoryActionCompleted",
            "hiddenSelfAccessoryStartsClear", "hiddenSelfAccessoryNotPremature",
            "hiddenSelfAccessoryEventDeadline", "hiddenSelfAccessoryActionCompleted",
            "remoteAccessoryStateBeforeRange", "remoteAccessoryStateAfterRange",
            "localAppearanceServerBaseline", "local-appearance-ownPrivateModelAndTransform",
            "local-appearance-ownRemoteBindingUnchanged", "local-appearance-ownNoServerRequest",
            "local-appearance-actionPrivateModelAndTransform", "local-appearance-actionRemoteBindingUnchanged",
            "local-appearance-actionNoServerRequest", "localAppearanceLocalActionLayer",
            "localAppearanceRestoresServerBinding", "localAppearanceDisableNoServerRequest"):
        assert game_checks.get(check_name) is True, f"Missing lifecycle regression proof: {check_name}"
    for check_name in ("packFallbackPrerequisites", "packDisableReloadCompleted",
                       "packDisabledRemovesInstalledIndex", "packDisabledDropsPreviousAsset",
                       "packDisabledNoLocalOwnRendering", "packDisabledServerFallbackSameInstance",
                       "packRestoreReloadCompleted", "packRestoredExactProfiles",
                       "packRestoredHashRevalidated", "packRestoredOwnerLeaseSameInstance",
                       "packRestoredFreshFrames"):
        assert game_checks.get(check_name) is True, f"Missing unified-resource-pack lifecycle proof: {check_name}"
    assert observer_checks.get("AUndisguiseRestoresNativePlayerForUnmoddedB") is True
    for check_name in ("privateAppearanceBServerModelBaseline",
                       "local-appearance-ownBServerModelAndTransformUnchanged",
                       "local-appearance-actionBServerModelAndTransformUnchanged",
                       "local-appearance-offBServerModelAndTransformUnchanged"):
        assert observer_checks.get(check_name) is True, f"Missing private appearance observer proof: {check_name}"
    game_launch = read_json(game_launch_path)
    observer_launch = read_json(observer_launch_path)
    layout_path = game_report_path.parent / "window-layout.json"
    layout = read_json(layout_path)
    windows = {row["client"]: row for row in layout}
    assert set(windows) == {"A", "B"}, "Missing two-client window arrangement"
    for label, report_path in (("A", game_report_path), ("B", observer_report_path)):
        assert windows[label]["pid"] == read_json(report_path.parent / "process.json")["pid"], "Window proof used another client"
    sizes = [(windows[label]["right"] - windows[label]["left"], windows[label]["bottom"] - windows[label]["top"])
             for label in ("A", "B")]
    assert sizes[0] == sizes[1] and min(sizes[0]) > 0 \
        and windows["A"]["right"] <= windows["B"]["left"], "Client windows differ in size or overlap"
    assert layout_path.stat().st_mtime_ns // 1_000_000 >= max(game_launch["startedAtMillis"], observer_launch["startedAtMillis"]), \
        "Window arrangement proof predates this run"
    assert game_launch["clientJarSha256"].lower() == digest(client_jar), "Game run used another client JAR"
    assert game_launch["serverJarSha256"].lower() == digest(jar), "Game run used another server JAR"
    assert game_report["testedClientSha256"].lower() == digest(client_jar), "Loaded client differs from launch proof"
    assert game_report["testedServerSha256"].lower() == digest(jar), "Server report differs from launch proof"
    assert game_report["artifactProof"]["clientArtifactSha256"].lower() == digest(client_jar)
    assert game_report["artifactProof"]["serverArtifactSha256"].lower() == digest(jar)
    assert observer_launch["serverJarSha256"].lower() == digest(jar), "Observer run used another server JAR"
    assert observer_launch.get("mpaClientInstalled") is False
    assert observer_report["observerHelperSha256"].lower() == observer_launch["helperJarSha256"].lower(), \
        "Observer loaded another acceptance helper"
    observed_hashes = observer_report["observedArtifactHashes"]
    assert observed_hashes["clientArtifactSha256"].lower() == digest(client_jar)
    assert observed_hashes["serverArtifactSha256"].lower() == digest(jar)
    assert not any("MEPlayerActions-Client" in mod for mod in observer_launch["mods"]), \
        "Observer run accidentally installed the client mod"
    standalone_report_path = client / "build/standalone-e2e/results.json"
    assert standalone_report_path.is_file(), "Independent client acceptance report required"
    standalone_report = read_json(standalone_report_path)
    standalone_tested_at = client_jar.stat().st_mtime_ns
    assert standalone_report_path.stat().st_mtime_ns >= standalone_tested_at, "Stale independent client report"
    assert standalone_report.get("passed") is True and standalone_report.get("checks") \
        and all(check.get("passed") is True for check in standalone_report["checks"]), "Independent client acceptance failed"
    assert standalone_report.get("testedClientSha256", "").lower() == digest(client_jar), "Independent client used another JAR"
    assert standalone_report.get("scope") == "standalone-no-server-plugins" \
        and standalone_report.get("runtimeServerConnected") is False \
        and standalone_report.get("serverChannelAvailable") is False \
        and standalone_report.get("artifactProof", {}).get("clientFromJar") is True, "Independent run used a server bridge"
    standalone_checks = {check["name"]: check["passed"] for check in standalone_report["checks"]}
    for check_name in ("standaloneNoServerChannel", "standaloneNoServerBridge", "standaloneLocalModelVisible",
                       "transformStandaloneTransform", "standaloneLocalActionLayer", "standaloneProfileSaved",
                       "standaloneSavedProfileReloaded", "standaloneResourceReload", "standaloneDisableRestoresNative",
                       "standaloneAllStagesCaptured", "standaloneBridgeStayedDisconnected",
                       "standaloneSettingsUiScreen", "standaloneSettingsUiProfileAndModel",
                       "settings-uiStandaloneControlsInBounds", "standaloneActionsUiSeparateModes",
                       "local-actions-uiStandaloneControlsInBounds", "standaloneActionsUiUsesLocalApi",
                       "standaloneFirstPersonNativeArm", "standaloneGalleryFixturesAvailable",
                       "standaloneGallerySearchField", "settings-uiStandaloneGalleryPreview",
                       "standaloneGallerySearchAndDraft", "standaloneGalleryFavoriteFilter",
                       "standaloneGalleryFavoriteSearchRebuild", "standaloneGalleryPagination",
                       "standaloneGalleryPreviousPage", "standaloneGalleryDraftNeverApplied",
                       "gallery-browse-uiStandaloneControlsInBounds", "gallery-browse-uiStandaloneGalleryPreview",
                       "standaloneModelSettingsUiScreen", "model-settings-uiStandaloneControlsInBounds",
                       "standaloneModelSettingsHeaddressGeometry", "standaloneModelSettingsHeaddressRestored",
                       "standaloneModelSettingsSaveCallback", "standaloneModelSettingsPreview",
                       "standaloneWheelPageAndLock", "standaloneWheelHover", "standaloneWheelUsesLocalApi",
                       "standaloneWheelNumberConsumesHold", "standaloneWheelStopButton", "standaloneWheelStopped",
                       "standaloneWheelReleaseSelectsOnce", "standaloneWheelLockOnlyKeepsUi",
                       "standaloneWheelUiSeparateModes", "standaloneWheelLabelsAndWidgetsSeparate",
                       "wheel-uiStandaloneControlsInBounds", "standaloneGalleryPreviewAfterReload",
                       "ui-reload-uiStandaloneControlsInBounds", "ui-reload-uiStandaloneGalleryPreview"):
        assert standalone_checks.get(check_name) is True, f"Missing independent-client regression proof: {check_name}"
    standalone_launch = read_json(standalone_report_path.parent / "launch.json")
    assert standalone_launch["clientJarSha256"].lower() == digest(client_jar), "Independent launch used another JAR"
    standalone_server_path = PROJECT / "build/standalone-server/standalone-proof.json"
    standalone_server = read_json(standalone_server_path)
    assert standalone_server_path.stat().st_mtime_ns >= standalone_tested_at, "Stale empty-server proof"
    assert standalone_server["pluginJarCount"] == 0 and standalone_server["unexpectedNestedJarCount"] == 0 \
        and standalone_server["forbiddenPluginsAbsentFromRuntimeLog"] is True and standalone_server["zeroPluginsConfirmedByLog"] is True \
        and standalone_server["serverStartupComplete"] is True and standalone_server["listenersLoopbackOnly"] is True, \
        "Independent client must be tested on a running empty-plugin server"
    evidence = {"standalone/server-proof.json": standalone_server_path.read_bytes()}
    evidence["two-client/window-layout.json"] = layout_path.read_bytes()
    for label, report_path, report, current in (
            ("client", game_report_path, game_report, game_current),
            ("observer-without-mpa", observer_report_path, observer_report, observer_current),
            ("standalone", standalone_report_path, standalone_report, True)):
        if not current:
            continue
        evidence[f"{label}/results.json"] = report_path.read_bytes()
        evidence[f"{label}/launch.json"] = (report_path.parent / "launch.json").read_bytes()
        screenshots = report.get("screenshots", [])
        assert screenshots, f"No validation screenshots: {label}"
        for relative in screenshots:
            assert isinstance(relative, str)
            screenshot = (report_path.parent / relative).resolve()
            assert screenshot.is_relative_to(report_path.parent.resolve()) and screenshot.suffix == ".png"
            assert screenshot.is_file(), f"Missing validation screenshot: {relative}"
            screenshot_minimum_time = standalone_tested_at if label == "standalone" else tested_at
            assert screenshot.stat().st_mtime_ns >= screenshot_minimum_time, f"Stale validation screenshot: {relative}"
            pixels = screenshot.read_bytes()
            assert pixels.startswith(b"\x89PNG\r\n\x1a\n"), f"Invalid PNG screenshot: {relative}"
            evidence[f"{label}/{relative}"] = pixels
    evidence_zip = None
    if evidence:
        for filename in ("model-bounds-probe.txt", "model-bounds-corrected.txt", "animation-axes-research.md"):
            evidence_file = client / "build/observer-test" / filename
            if evidence_file.is_file() and evidence_file.stat().st_mtime_ns >= tested_at:
                evidence[f"model-coordinate-checks/{filename}"] = evidence_file.read_bytes()
        server_proof_path = PROJECT / "build/e2e-server/matrix-proof.json"
        assert server_proof_path.is_file(), "Server acceptance matrix proof required"
        server_proof = read_json(server_proof_path)
        assert server_proof_path.stat().st_mtime_ns >= tested_at, "Stale server matrix proof timestamp"
        assert server_proof["serverJarSha256"].lower() == digest(jar), "Stale server matrix proof"
        assert server_proof["clientJarSha256"].lower() == digest(client_jar), "Server matrix used another client JAR"
        assert server_proof["serverTests"] == totals["tests"], "Server matrix has outdated unit test totals"
        assert server_proof["passed"] is True and server_proof["checks"] \
            and all(check["passed"] is True for check in server_proof["checks"]), "Server matrix proof failed"
        evidence["server/matrix-proof.json"] = server_proof_path.read_bytes()
        evidence["tested-artifacts.json"] = (json.dumps({
            "minecraft": "1.21.11", "server_sha256": digest(jar), "client_sha256": digest(client_jar),
            "client_passed": game_passed, "observer_without_mpa_passed": observer_passed,
        }, indent=2) + "\n").encode("utf-8")
        for relative in ("client/build/e2e/launch_client.py", "build/demo/arrange-windows.ps1", "client/build/observer-test/src/ObserverHarness.java",
                         "client/build/observer-test/build_observer.ps1", "build/e2e-server/write-matrix-proof.ps1",
                         "build/standalone-server/start-standalone.ps1", "build/standalone-server/write-standalone-proof.ps1",
                         "build/e2e-helper/MPATestHelper.java", "build/e2e-helper/build_helper.ps1",
                         "build/e2e-helper/plugin.yml", "build/e2e-helper/classpath.txt"):
            fixture = PROJECT / relative
            assert fixture.is_file(), f"Missing acceptance fixture: {relative}"
            evidence[f"fixtures/{relative}"] = fixture.read_bytes()
        evidence_zip = DIST / f"{name}-tests.zip"

    import subprocess, sys
    subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", str(PROJECT / "tools/tests"), "-v"], check=True, cwd=PROJECT)
    full_pack = PROJECT / f"build/unified-resource-pack/MEPlayerActions-{version}-e2e-unified.zip"
    pack_audit = PROJECT / f"build/unified-resource-pack/MEPlayerActions-{version}-e2e-unified.audit.json"
    assert full_pack.is_file() and pack_audit.is_file(), "Tested unified resource pack required"
    audit = read_json(pack_audit)
    assert audit["version"] == 1 and audit["packFile"] == full_pack.name \
        and audit["packSha256"] == digest(full_pack), "Resource-pack audit describes another ZIP"
    assert audit["zipIntegrity"] is True and audit["deterministicReverseModelOrder"] is True \
        and audit["clientDuplicatePngEntries"] == 0, "Resource-pack audit failed"
    assert {model["modelId"] for model in audit["models"]} == set(MODEL_IDS) \
        and all(model["rawHashVerified"] is True and model["restoredOriginalBytes"] is True
                for model in audit["models"]), "Incomplete model hash/reconstruction audit"
    assert all(change["pixelsEqual"] is True for change in audit["pixelEquivalentEncodingChanges"]) \
        and audit["preservedEngineEntries"] + len(audit["pixelEquivalentEncodingChanges"]) == audit["engineEntryCount"], \
        "Unaccounted changes to engine assets"
    preserved_engine = Path(audit["preservedCurrentEngineZip"])
    assert preserved_engine.is_file() and digest(preserved_engine) == audit["engineInputSha256"], "Missing audited engine input"
    with zipfile.ZipFile(full_pack) as pack:
        assert pack.testzip() is None
        normalization = json.loads(pack.read("assets/meplayeractions/models/normalization.json"))
        assert normalization["version"] == 1 and normalization["enginePackSha256"] == audit["engineInputSha256"] \
            and normalization["pngEncodingRewrites"] == audit["pixelEquivalentEncodingChanges"], "Stale PNG normalization audit"
        from build_client_resource_pack import decoded_png_identity
        rewrites = {change["assetPath"]: change for change in audit["pixelEquivalentEncodingChanges"]}
        with zipfile.ZipFile(preserved_engine) as original:
            original_names = [name for name in original.namelist() if not name.endswith("/")]
            assert len(original_names) == audit["engineEntryCount"]
            for asset_name in original_names:
                before, after = original.read(asset_name), pack.read(asset_name)
                if asset_name not in rewrites:
                    assert before == after, f"Changed engine resource: {asset_name}"
                else:
                    change = rewrites[asset_name]
                    assert hashlib.sha256(before).hexdigest() == change["originalPngSha256"] \
                        and hashlib.sha256(after).hexdigest() == change["sourcePngSha256"]
                    assert decoded_png_identity(before) == decoded_png_identity(after) \
                        == (change["width"], change["height"], change["rgbaSha256"]), f"Changed texture pixels: {asset_name}"
        index = json.loads(pack.read("assets/meplayeractions/models/index.json"))
        assert index["version"] == 1
        indexed = {entry["modelId"]: entry for entry in index["models"]}
        for model_id in MODEL_IDS:
            assert indexed[model_id]["hashSha256"] == digest(PROJECT / f"examples/models/{model_id}.bbmodel")
        assert not any(name.startswith("assets/meplayeractions/textures/") for name in pack.namelist()), "Duplicated example textures"
    for launch in (game_launch, observer_launch):
        assert launch.get("resourcePackSha256") == digest(full_pack), "Game acceptance did not use this unified resource pack"
    install[f"resourcepacks/MEPlayerActions-{version}-resource-pack.zip"] = full_pack.read_bytes()
    evidence["resource-pack/audit.json"] = pack_audit.read_bytes()
    delivered_pack = DIST / f"MEPlayerActions-{version}-resource-pack.zip"

    # Do not create apparently deliverable artifacts until every acceptance gate passes.
    DIST.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(jar, delivered_jar)
    shutil.copyfile(client_jar, delivered_client)
    shutil.copyfile(full_pack, delivered_pack)
    write_zip(install_zip, install)
    write_zip(source_zip, source_entries)
    write_zip(client_install_zip, {f"mods/{client_jar.name}": client_jar.read_bytes(),
                                   "README.md": install["README.md"],
                                   "ARCHITECTURE.md": architecture.read_bytes(),
                                   "docs/CLIENT_PROTOCOL.md": protocol.read_bytes(),
                                   "docs/CLIENT_RESOURCE_PACK.md": (PROJECT / "docs/CLIENT_RESOURCE_PACK.md").read_bytes(),
                                   "THIRD_PARTY_NOTICES.md": (PROJECT / "THIRD_PARTY_NOTICES.md").read_bytes()})
    assert digest(delivered_jar) == digest(jar)
    assert digest(delivered_client) == digest(client_jar)
    assert evidence_zip is not None, "Missing two-client evidence bundle"
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
        "models": model_facts,
        "unified_resource_pack": {"path": str(delivered_pack), "sha256": digest(delivered_pack), "shared_example_textures": True, "tool_tests": 16},
        "ysm_physics_step_seconds": .01,
        "runtime_expressions": "Per-instance bounded interpreter; frame-based client and tick-based ModelEngine",
        "compatibility_modpack": game_launch.get("sourceModpack"),
        "compatibility_mods": game_launch.get("mods"),
        "compatibility_runtime_overrides": game_launch.get("compatibilityOverrides", []),
        "owned_disguise_anchor": "shared visual history; player feet, native bed center/top, or GSit seat contact plane; no player mounting",
        "owned_disguise_audience": {"show_self_default": True, "strict_distance_blocks_default": 8,
                                    "max_other_viewers_default": 10, "selection": "nearest tracked eligible players, UUID tie break",
                                    "update_ticks": 1, "protocol_recipient_filter": True},
        "disguise_effect_allowlist": ["slowness"], "disguise_model_argument_required_first": True,
        "root_command": "meplayeractions", "command_aliases": [],
        "organized_subcommands": ["help", "disguise", "undisguise", "models", "attach", "menu", "animations",
                                   "play", "stop", "reset", "pose", "sync", "status", "reload"],
        "bundled_animation_labels": "Chinese defaults, with configuration overrides and custom action label precedence",
        "compile_dependency_hashes": {p.name: digest(p) for p in
            [PROJECT.parent / "ModelEngine-R4.1.1.jar", *sorted((PROJECT / "build/deps").glob("paper-api-*.jar"))]
            if p.is_file()},
        "artifacts": {p.name: {"sha256": digest(p), "bytes": p.stat().st_size}
                      for p in (delivered_jar, install_zip, source_zip, delivered_client, client_install_zip, evidence_zip, delivered_pack)
                      if p is not None},
        "zip_integrity_and_content_match": True, "dependency_jars_included": False,
        "in_game_verified": game_passed,
        "two_client_in_game_verified": game_passed and observer_passed,
        "standalone_client_verified": True,
        "standalone_game_validation": report_summary(standalone_report),
        "game_validation_current_for_artifacts": game_current,
        "observer_validation_current_for_artifacts": observer_current,
        "game_validation": report_summary(game_report),
        "observer_game_validation": report_summary(observer_report),
        "resource_pack_in_install_zip": True,
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
