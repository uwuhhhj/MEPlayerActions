"""Build two appearance models with restored YSM clips and numeric ME counterparts."""
from __future__ import annotations
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import uuid

PROJECT = Path(__file__).resolve().parents[1]
RESEARCH = PROJECT.parent
WORKSPACE = PROJECT.parents[1]
ORIGINAL = WORKSPACE / "dist/ysm_07_jk.bbmodel"
REFERENCE = RESEARCH / "JK酒狐/JK酒狐_ME通用精简.bbmodel"
APPEARANCE = RESEARCH / "JK酒狐/银灰蓝眼_贴图版_v9/ysm_01_jk.bbmodel"
RAW = PROJECT / "examples/models"
ME = PROJECT / "examples/blueprints"
NS = uuid.UUID("1aac73cf-2df6-4d79-a097-b6e8551d2dc7")

def load(path): return json.loads(path.read_text(encoding="utf-8-sig"))
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8"))
def bones(model):
    found = {}
    def walk(nodes):
        for node in nodes:
            if isinstance(node, dict): found[node["uuid"]] = node; walk(node.get("children", []))
    walk(model["outliner"]); return found
def renamed(animation, name):
    result = copy.deepcopy(animation); result["name"] = name
    result["uuid"] = str(uuid.uuid5(NS, "restored:" + name))
    return result

def main():
    source = load(ORIGINAL)
    authored = load(PROJECT / "tools/authored_actions.json")
    aliases = {"hurt":"attacked", "crouch_walk":"sneak", "crouch_idle":"sneaking", "swim_idle":"swim_stand",
               "crawl_walk":"climb", "crawl_idle":"climbing", "attack":"swing_hand"}
    # Gameplay clips are preserved; absent bones from the original full model are audited and removed.
    wanted = ["gui", "hover_fadeout", "hover", "focus", "elytra_fly", "ride", "ride_pig", "boat"] + [f"extra{i}" for i in range(8)]
    reports = {}
    for model_id, appearance in [("ysm_01_jk", APPEARANCE), ("ysm_02_jk", REFERENCE)]:
        model = copy.deepcopy(load(appearance)); model["name"] = model_id; model["model_identifier"] = model_id
        ids = bones(model); animations = {a["name"]: copy.deepcopy(a) for a in model["animations"]}
        source_animations = {a["name"]: a for a in source["animations"]}
        for name in wanted:
            if name in source_animations: animations[name] = copy.deepcopy(source_animations[name])
        for name, original_name in aliases.items(): animations[name] = renamed(animations[original_name], name)
        # Keep real ladder motion distinct from YSM's land crawling clips.
        for name, original_name in [("ladder_up", "climb"), ("ladder_stillness", "climbing")]:
            action = renamed(animations[original_name], name)
            root = next(a for a in action["animators"].values() if a.get("name") == "Root")
            for frame in root["keyframes"]:
                if frame["channel"] in ("rotation", "position"): frame["data_points"] = [{"x":"0","y":"0","z":"0"}]
            animations[name] = action
        animations["fall"] = renamed(animations["jump"], "fall")
        animations["minecart"] = renamed(animations["sit"], "minecart")
        animations["mining"] = renamed(animations["swing_hand"], "mining")
        for name in ["wave", "nod", "talk", "smile", "surprised", "bed_sleep", "player_jump"]:
            action = renamed(next(a for a in authored if a["name"] == name), name)
            # Undo the former NPC uniform scale for translations in these newly authored clips.
            for animator in action["animators"].values():
                if animator.get("name") == "hi_Head": animator["name"] = "Head"
                for frame in animator.get("keyframes", []):
                    if frame["channel"] == "position":
                        for point in frame["data_points"]:
                            for axis in ("x","y","z"): point[axis] = str(float(point[axis]) / .6379681288451754)
            animations[name] = action
        if model_id == "ysm_02_jk":
            # The reference's back garment is thicker than appearance 01's; settle it on the mattress.
            root = next(t for t in animations["bed_sleep"]["animators"].values() if t.get("name") == "Root")
            for frame in root["keyframes"]:
                if frame["channel"] == "position":
                    for point in frame["data_points"]: point["y"] = str(float(point["y"]) - .919194)
        names = {bone["name"]: bone_id for bone_id, bone in ids.items()}
        assert len(names) == len(ids), "Retargeting requires unique bone names"
        removed, corrected, retargeted = [], [], []
        for animation in animations.values():
            animation.pop("path", None)
            for bone_id, animator in list(animation.get("animators", {}).items()):
                if animator.get("type", "bone") == "bone" and bone_id not in ids:
                    target_id = names.get(animator.get("name"))
                    if target_id is None:
                        removed.append([animation["name"], animator.get("name")]); del animation["animators"][bone_id]; continue
                    if target_id in animation["animators"]: raise ValueError("Duplicate retargeted track: " + animator["name"])
                    del animation["animators"][bone_id]; animation["animators"][target_id] = animator
                    retargeted.append([animation["name"], animator["name"], bone_id, target_id]); bone_id = target_id
                if bone_id in ids: animator["name"] = ids[bone_id]["name"]
                if animator.get("name") == "Head" and animation["name"] in {
                    "idle", "walk", "run", "jump", "player_jump", "fall", "sit", "minecart",
                    "boat", "ride", "ride_pig", "ladder_up", "ladder_stillness", "sneak", "sneaking",
                    "crouch_walk", "crouch_idle"
                }:
                    for frame in animator.get("keyframes", []):
                        if frame["channel"] == "rotation":
                            for point in frame["data_points"]:
                                for axis, query in [("x", "ysm.head_pitch"), ("y", "ysm.head_yaw")]:
                                    value = str(point.get(axis, "0"))
                                    if "ysm.head_" not in value: point[axis] = f"({value})+{query}"
                for frame in animator.get("keyframes", []):
                    # Original endpoint 0.0101 belongs to the commit phase of a 10 ms physics cycle.
                    if animation["name"] == "parallel2" and frame["time"] > animation["length"]: frame["time"] = animation["length"]
                    for point in frame.get("data_points", []):
                        for axis in ("x","y","z"):
                            if axis in point and str(point[axis]).count(")") > str(point[axis]).count("("):
                                value = str(point[axis])
                                if value == "(-7.5+(1-0.7)*ysm.head_pitch))+(ctrl.elytra_fly==true?-30:0)":
                                    point[axis] = value.replace("head_pitch))", "head_pitch)"); corrected.append(value)
                                else: raise ValueError("Unrecognized malformed source expression: " + value)
            if animation.get("length", 0) == 0 and animation["name"] in {"sit","minecart","climbing","crawl_idle","sneaking","crouch_idle"}:
                animation["length"] = .5; animation["loop"] = "hold"
            # The source blink has return keys at 8 s despite its declared 6.8333 s duration.
            max_time = max((frame["time"] for animator in animation["animators"].values()
                            for frame in animator.get("keyframes", [])), default=0)
            if max_time > animation.get("length", 0): animation["length"] = max_time
        model["animations"] = list(animations.values())
        for texture in model["textures"]: texture.pop("path", None); texture["relative_path"] = ""
        model["mpa_runtime"] = {"physics_step_seconds":.01,"original_size":True,"source":"ysm_07_jk"}
        assert all(model[k] == load(appearance)[k] for k in ("elements", "outliner", "resolution"))
        raw_path = RAW / (model_id + ".bbmodel"); write(raw_path, model)
        reports[model_id] = {"model_id":model_id,"appearance_source":str(appearance.relative_to(RESEARCH)),
            "appearance_sha256":sha(appearance),"animation_source_sha256":sha(ORIGINAL),"raw_sha256":sha(raw_path),
            "geometry_and_original_size_preserved":True,"animations":list(animations),"removed_orphan_tracks":removed,
            "retargeted_exact_name_tracks":retargeted,"source_syntax_repairs":corrected}
        write(RAW / (model_id + ".manifest.json"), reports[model_id])
    print(json.dumps({key:{"animations":len(value["animations"]),"orphan_tracks":len(value["removed_orphan_tracks"])} for key,value in reports.items()}))

if __name__ == "__main__": main()
