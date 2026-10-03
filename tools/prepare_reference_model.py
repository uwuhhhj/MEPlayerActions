"""Give the supplied reference a stable ME ID without altering its geometry or animation."""
from pathlib import Path
import hashlib
import json

PROJECT = Path(__file__).resolve().parents[1]
SOURCE = PROJECT.parent / "JK酒狐/JK酒狐_ME通用精简.bbmodel"
OUTPUT = PROJECT / "examples/blueprints/npc/ysm_02_jk.bbmodel"


def main():
    original = json.loads(SOURCE.read_text(encoding="utf-8"))
    model = dict(original, model_identifier="ysm_02_jk")
    OUTPUT.write_bytes((json.dumps(model, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    assert all(model.get(key) == original.get(key) for key in ("elements", "outliner", "textures", "animations"))
    manifest = {
        "model_id": "ysm_02_jk", "source": {"path": str(SOURCE.relative_to(PROJECT.parent)).replace("\\", "/"),
        "sha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest()},
        "derived_model": {"sha256": hashlib.sha256(OUTPUT.read_bytes()).hexdigest()},
        "changes": ["Only model_identifier changed; geometry, textures and animations preserved"],
        "client_limitation": "YSM expressions and effect scripts require ModelEngine fallback with the current client parser",
    }
    OUTPUT.with_suffix(".manifest.json").write_bytes((json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    print("Prepared ysm_02_jk reference model")


if __name__ == "__main__":
    main()
