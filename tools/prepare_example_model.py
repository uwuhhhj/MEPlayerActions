#!/usr/bin/env python3
"""Derive the player demo from the existing NPC without modifying either source.

Uses only the Python standard library. Run from any working directory:
    python tools/prepare_example_model.py
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
import uuid


PACKAGE_ROOT = Path(__file__).resolve().parents[1]
RESEARCH_ROOT = PACKAGE_ROOT.parent
DEFAULT_NPC = RESEARCH_ROOT / "MythicMobs酒狐NPC/plugins/ModelEngine/blueprints/npc/ysm_01_jk_npc.bbmodel"
DEFAULT_ORIGINAL = RESEARCH_ROOT / "JK酒狐/银灰蓝眼_贴图版_v9/ysm_01_jk.bbmodel"
OUTPUT = PACKAGE_ROOT / "examples/blueprints/npc/ysm_01_jk_player.bbmodel"
MANIFEST = OUTPUT.with_suffix(".manifest.json")
NPC_OUTPUT = OUTPUT.with_name("ysm_01_jk_npc.bbmodel")
NPC_MANIFEST = NPC_OUTPUT.with_suffix(".manifest.json")
MODEL_ID = "ysm_01_jk_player"
DERIVED_UUID_NAMESPACE = uuid.UUID("1aac73cf-2df6-4d79-a097-b6e8551d2dc7")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def animation(model: dict, name: str) -> dict:
    matches = [item for item in model.get("animations", []) if item.get("name") == name]
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one animation named {name!r}, got {len(matches)}")
    return matches[0]


def root_animator(anim: dict) -> tuple[str, dict]:
    matches = [(key, item) for key, item in anim.get("animators", {}).items() if item.get("name") == "Root"]
    if len(matches) != 1:
        raise ValueError(f"Animation {anim.get('name')!r} must have exactly one Root animator")
    return matches[0]


def finite_number(value: object) -> float:
    if isinstance(value, bool):
        raise ValueError("Boolean is not a numeric keyframe coordinate")
    try:
        number = float(value)
    except (ValueError, TypeError) as error:
        raise ValueError(f"Non-numeric/Molang keyframe coordinate: {value!r}") from error
    if not math.isfinite(number):
        raise ValueError(f"Non-finite keyframe coordinate: {value!r}")
    return number


def numeric_text(value: float) -> str:
    if abs(value) < 0.000000005:
        return "0"
    return format(value, ".8f").rstrip("0").rstrip(".") or "0"


def restore_crawl_root(target: dict, original: dict, scale: float) -> None:
    target_id, target_root = root_animator(target)
    original_id, original_root = root_animator(original)
    if target_id != original_id:
        raise ValueError("Root UUID differs between original and NPC model")
    for channel in ("rotation", "position"):
        source_frames = [frame for frame in original_root["keyframes"] if frame["channel"] == channel]
        target_frames = [frame for frame in target_root["keyframes"] if frame["channel"] == channel]
        if len(source_frames) != 1 or len(target_frames) != 1:
            raise ValueError(f"Expected one static Root {channel} frame")
        points = copy.deepcopy(source_frames[0]["data_points"])
        for point in points:
            for axis in ("x", "y", "z"):
                number = finite_number(point[axis])
                point[axis] = numeric_text(number * scale if channel == "position" else number)
        # Preserve NPC frame UUID, timing, interpolation and the already-prepared limb tracks.
        target_frames[0]["data_points"] = points


def validate(model: dict) -> dict:
    names = [item["name"] for item in model["animations"]]
    if len(set(names)) != len(names):
        raise ValueError("Duplicate animation names")
    frames_checked = 0
    coordinates_checked = 0
    for anim in model["animations"]:
        finite_number(anim.get("length", 0))
        for animator in anim.get("animators", {}).values():
            for frame in animator.get("keyframes", []):
                finite_number(frame["time"])
                if frame["channel"] not in ("position", "rotation", "scale"):
                    raise ValueError(f"Unsupported/script channel: {frame['channel']!r}")
                for point in frame.get("data_points", []):
                    if "script" in point:
                        raise ValueError("Server script keyframes must not be sent to the client example")
                    for axis in ("x", "y", "z"):
                        finite_number(point[axis])
                        coordinates_checked += 1
                frames_checked += 1
    return {
        "animation_names_unique": True,
        "numeric_keyframes_checked": frames_checked,
        "numeric_coordinates_checked": coordinates_checked,
        "non_numeric_or_molang_coordinates": 0,
        "script_channels": 0,
    }


def source_label(path: Path) -> str:
    try:
        return path.relative_to(RESEARCH_ROOT).as_posix()
    except ValueError:
        return path.name


def write_npc_variant(npc: dict, player: dict, npc_path: Path, npc_hash: str) -> None:
    """Add player posture tracks while retaining every existing NPC track and all geometry."""
    result = copy.deepcopy(npc)
    for name in ("crawl_idle", "crawl_walk", "player_jump", "bed_sleep"):
        added = copy.deepcopy(animation(player, name))
        added["uuid"] = str(uuid.uuid5(DERIVED_UUID_NAMESPACE, f"npc:animation:{name}"))
        for bone_id, animator in added["animators"].items():
            for index, frame in enumerate(animator.get("keyframes", [])):
                frame["uuid"] = str(uuid.uuid5(DERIVED_UUID_NAMESPACE, f"npc:{name}:{bone_id}:{index}"))
        result["animations"].append(added)
    if result["animations"][:len(npc["animations"])] != npc["animations"]:
        raise ValueError("An existing NPC animation was modified")
    if any(result.get(field) != npc.get(field) for field in ("elements", "outliner", "textures", "resolution")):
        raise ValueError("NPC geometry changed")
    checks = validate(result)
    NPC_OUTPUT.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    NPC_MANIFEST.write_text(json.dumps({
        "model_id": "ysm_01_jk_npc",
        "npc_source": {"path": source_label(npc_path), "sha256": npc_hash},
        "derived_model": {"path": NPC_OUTPUT.relative_to(PACKAGE_ROOT).as_posix(), "sha256": sha256(NPC_OUTPUT)},
        "added_animations": ["crawl_idle", "crawl_walk", "player_jump", "bed_sleep"],
        "original_animation_count": len(npc["animations"]),
        "original_animations_and_geometry_preserved": True,
        "crawl_root_rotation_degrees": [90.0, 0.0, 0.0],
        "bed_sleep": bed_sleep_contract(),
        "validation": checks,
        "in_game_verified": False,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def player_jump(model: dict) -> dict:
    """Create a full pose cycle without root lift: delayed real motion supplies the jump arc."""
    result = copy.deepcopy(animation(model, "jump"))
    result["name"] = "player_jump"
    result["loop"] = "once"
    result["uuid"] = str(uuid.uuid5(DERIVED_UUID_NAMESPACE, "player:animation:player_jump"))
    phases = ((0.0, 0.0), (0.10, 0.35), (0.30, 1.0), (0.50, 0.75), (0.65, 0.3), (0.75, 0.0))
    for bone_id, animator in result["animators"].items():
        rotations = [frame for frame in animator["keyframes"] if frame["channel"] == "rotation"]
        if not rotations:
            continue
        template = rotations[0]
        frames = [frame for frame in animator["keyframes"] if frame["channel"] != "rotation"]
        for index, (time, weight) in enumerate(phases):
            frame = copy.deepcopy(template)
            frame["time"] = time
            frame["uuid"] = str(uuid.uuid5(DERIVED_UUID_NAMESPACE, f"player_jump:{bone_id}:{index}"))
            frame["interpolation"] = "linear"
            for point in frame["data_points"]:
                for axis in ("x", "y", "z"):
                    point[axis] = numeric_text(finite_number(point[axis]) * weight)
            frames.append(frame)
        animator["keyframes"] = frames
    root_id, crawl_root = root_animator(animation(model, "crawl_idle"))
    root = copy.deepcopy(crawl_root)
    for index, frame in enumerate(root["keyframes"]):
        frame["uuid"] = str(uuid.uuid5(DERIVED_UUID_NAMESPACE, f"player_jump:root:{index}"))
        frame["data_points"] = [{axis: "1" if frame["channel"] == "scale" else "0" for axis in ("x", "y", "z")}]
    result["animators"][root_id] = root
    return result


def bed_sleep_contract() -> dict:
    return {
        "anchor": "Two-block bed center, mattress top at block Y + 0.5625; floor lay uses floor anchor",
        "local_head_direction": "-Z",
        "local_back_contact_y_blocks": 0.0,
        "legacy_root_rotation_degrees": [-90.0, -180.0, 0.0],
        "renderer": "World Y rotation 180 - bodyYaw only; no native-pose X/Z rotation",
        "original_sleep_preserved": True,
    }


def bed_sleep(model: dict) -> dict:
    """Author a supine pose in the geometry itself, independent of YSM's sleeping renderer.

    The source is BB 4.x: animated X/Y rotations and position X are negated by
    preview/rendering. Effective Ry(180)Rx(90) puts the nose upward and the head
    toward local -Z. Root translation centers its roughly 1.8-block body on a
    two-block mattress instead of pivoting the body around the player's feet.
    """
    if model.get("meta", {}).get("format_version") != "4.10":
        raise ValueError("Bed demo authoring expects the known Blockbench 4.10 source")
    roots = [node for node in model["outliner"] if isinstance(node, dict) and node.get("name") == "Root"]
    if len(roots) != 1:
        raise ValueError("Expected one geometry Root for bed pose")
    bones: list[dict] = []
    def collect(node: dict) -> None:
        bones.append(node)
        for child in node.get("children", []):
            if isinstance(child, dict): collect(child)
    collect(roots[0])
    result = {
        "uuid": str(uuid.uuid5(DERIVED_UUID_NAMESPACE, "player:animation:bed_sleep")),
        "name": "bed_sleep", "loop": "hold", "override": True, "length": 0.5,
        "snapping": 20, "selected": False, "saved": True,
        "anim_time_update": "", "blend_weight": "", "start_delay": "", "loop_delay": "",
        "animators": {},
    }
    rotations = {
        "Root": (-90, -180, 0),
        # Bring the tail from behind the torso onto the bed above the legs.
        "Tail": (-170, 0, 0),
        # Relax the standing hair bends against the pillow; X/Y animation axes
        # are legacy-inverted, while Z must cancel the rest roll directly.
        "bone20": (-40, 0, 0),
        "bone21": (-19.59657, -4.07774, 11.29549),
        "bone25": (-19.59657, 4.07774, -11.29549),
        "LeftArm": (0, 0, -8), "RightArm": (0, 0, 8),
        "LeftForeArm": (-10, 0, 0), "RightForeArm": (-10, 0, 0),
        "LeftLowerLeg": (4, 0, 0), "RightLowerLeg": (4, 0, 0),
    }
    positions = {"Root": (0, 3.2, 14.4), "LongHair": (0, 0, -.75),
                 "LongLeftHair": (0, 0, -.5), "LongRightHair": (0, 0, -.5), "BaseHair": (0, 0, -1)}
    for bone in bones:
        bone_id, name = bone["uuid"], bone["name"]
        frames = []
        for channel, values in (("position", positions.get(name, (0, 0, 0))),
                                ("rotation", rotations.get(name, (0, 0, 0))), ("scale", (1, 1, 1))):
            frames.append({
                "uuid": str(uuid.uuid5(DERIVED_UUID_NAMESPACE, f"bed_sleep:{bone_id}:{channel}")),
                "channel": channel, "time": 0, "color": -1, "interpolation": "linear",
                "data_points": [{axis: numeric_text(value) for axis, value in zip(("x", "y", "z"), values)}],
            })
        result["animators"][bone_id] = {"name": name, "type": "bone", "keyframes": frames}
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--npc", type=Path, default=DEFAULT_NPC)
    parser.add_argument("--original", type=Path, default=DEFAULT_ORIGINAL)
    args = parser.parse_args()
    npc_path = args.npc.resolve(strict=True)
    original_path = args.original.resolve(strict=True)
    if any(path.resolve() in (npc_path, original_path) for path in (OUTPUT, MANIFEST, NPC_OUTPUT, NPC_MANIFEST)):
        raise ValueError("The example output must never overwrite a source model")
    npc_hash = sha256(npc_path)
    original_hash = sha256(original_path)
    npc = json.loads(npc_path.read_text(encoding="utf-8"))
    original = json.loads(original_path.read_text(encoding="utf-8"))
    result = copy.deepcopy(npc)
    if len(npc["animations"]) != 26:
        raise ValueError("Expected the prepared NPC model with 26 animations")
    _, npc_sit_root = root_animator(animation(npc, "sit"))
    _, original_sit_root = root_animator(animation(original, "sit"))
    npc_sit_position = next(frame for frame in npc_sit_root["keyframes"] if frame["channel"] == "position")
    original_sit_position = next(frame for frame in original_sit_root["keyframes"] if frame["channel"] == "position")
    scale = finite_number(npc_sit_position["data_points"][0]["y"]) / finite_number(original_sit_position["data_points"][0]["y"])
    if not 0.0 < scale < 1.0:
        raise ValueError(f"Unexpected NPC uniform scale: {scale}")

    result["name"] = MODEL_ID
    result["model_identifier"] = MODEL_ID
    changes = (("climb", "climb", "crawl_walk"), ("climb_idle", "climbing", "crawl_idle"))
    for npc_name, original_name, target_name in changes:
        target = animation(result, npc_name)
        restore_crawl_root(target, animation(original, original_name), scale)
        target["name"] = target_name

    hover = copy.deepcopy(animation(result, "fly"))
    hover["name"] = "hover"
    hover["uuid"] = str(uuid.uuid5(DERIVED_UUID_NAMESPACE, f"{MODEL_ID}:animation:hover"))
    for animator_id, animator in hover.get("animators", {}).items():
        for index, frame in enumerate(animator.get("keyframes", [])):
            frame["uuid"] = str(uuid.uuid5(DERIVED_UUID_NAMESPACE, f"{MODEL_ID}:hover:{animator_id}:{index}"))
    result["animations"].append(hover)
    result["animations"].append(player_jump(result))
    result["animations"].append(bed_sleep(result))
    checks = validate(result)
    if len(result["animations"]) != 29:
        raise ValueError("Expected exactly 29 player animations")
    preserved = [item["name"] for item in npc["animations"] if item["name"] not in ("climb", "climb_idle")]
    if any(animation(result, name) != animation(npc, name) for name in preserved):
        raise ValueError("An unrelated original NPC animation was modified")
    if any(result.get(field) != npc.get(field) for field in ("elements", "outliner", "textures", "resolution")):
        raise ValueError("NPC geometry, hierarchy, texture or UV layout changed")

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    write_npc_variant(npc, result, npc_path, npc_hash)
    if sha256(npc_path) != npc_hash or sha256(original_path) != original_hash:
        raise ValueError("Source models changed during the derivation")
    manifest = {
        "model_id": MODEL_ID,
        "target_minecraft": "1.21.11",
        "target_modelengine": "R4.1.1",
        "npc_source": {"path": source_label(npc_path), "sha256": npc_hash},
        "original_v9_source": {"path": source_label(original_path), "sha256": original_hash},
        "derived_model": {"path": OUTPUT.relative_to(PACKAGE_ROOT).as_posix(), "sha256": sha256(OUTPUT)},
        "position_scale_derived_from_sit": scale,
        "geometry_height_and_textures_preserved_from_npc": True,
        "renamed_actions": {"climb": "crawl_walk", "climb_idle": "crawl_idle"},
        "crawl_root_rotation_degrees": [90.0, 0.0, 0.0],
        "crawl_root_position_model_units": [0.0, round(4.0 * scale, 8), round(19.0 * scale, 8)],
        "hover": "Same numeric pose and motion as fly; duplicated animation/keyframe UUIDs replaced deterministically",
        "player_jump": "Complete takeoff/airborne/landing pose cycle; zero root lift to avoid double jumping with delayed real position",
        "bed_sleep": bed_sleep_contract(),
        "elytra": "Not generated; configure a fallback to fly until a dedicated action is authored",
        "animations": [{"name": item["name"], "length_seconds": item.get("length", 0), "loop": item.get("loop")} for item in result["animations"]],
        "validation": {**checks, "sources_unchanged": True, "unrelated_animations_preserved": len(preserved)},
        "resource_pack_generated": False,
        "in_game_verified": False,
    }
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"model_id": MODEL_ID, "animation_count": len(result["animations"]), "sha256": manifest["derived_model"]["sha256"], "validation": manifest["validation"]}, ensure_ascii=True))


if __name__ == "__main__":
    main()
