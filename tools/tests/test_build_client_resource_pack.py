"""Behavior tests for the standalone unified-resource-pack build tool."""
from __future__ import annotations

import base64
import hashlib
import json
from pathlib import Path
import stat
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_client_resource_pack as builder


def png(*, level: int = -1, pixel: bytes = b"\x12\x34\x56\xff") -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)) \
        + chunk(b"IDAT", zlib.compress(b"\x00" + pixel, level)) + chunk(b"IEND", b"")


class ResourcePackBuildTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.png = png()
        self.source = builder.PNG_PREFIX + base64.b64encode(self.png).decode("ascii")

    def model(self, name: str = "first", *, source: str | None = None, pretty: bool = False) -> Path:
        value = {"name": "测试模型", "textures": [{"name": "skin.png", "source": source or self.source}],
                 "elements": [{"uuid": "bone", "from": [0, 0, 0], "to": [1, 1, 1]}],
                 "animations": [{"name": "idle", "length": 1, "expression": "ysm.head_yaw"}]}
        raw = json.dumps(value, ensure_ascii=False, indent=2 if pretty else None,
                         separators=None if pretty else (",", ":"))
        if pretty:
            raw = " \r\n" + raw.replace("\n", "\r\n") + "\r\n"
        path = self.root / f"{name}.bbmodel"
        path.write_bytes(raw.encode("utf-8"))
        return path

    def engine(self, entries: list[tuple[str | zipfile.ZipInfo, bytes]] = ()) -> Path:
        path = self.root / "engine.zip"
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("pack.mcmeta", b'{"pack":{"pack_format":71,"description":"Engine original"}}')
                for name, data in entries:
                    archive.writestr(name, data)
        return path

    def build(self, models: list[tuple[str, Path]], engine: Path | None = None, name: str = "output.zip") -> dict:
        return builder.build_pack(engine, models, self.root / name, pack_format=71)

    def test_preserves_engine_entries_reuses_texture_and_restores_exact_server_hash(self) -> None:
        source_model = self.model(pretty=True)
        engine = self.engine([("assets/modelengine/textures/skin.png", self.png),
                              ("assets/modelengine/models/body.json", b'{"parent":"item/generated"}'),
                              ("engine_folder/", b"")])
        original_engine = engine.read_bytes()
        result = self.build([("first", source_model)], engine)
        with zipfile.ZipFile(engine) as original, zipfile.ZipFile(result["output"]) as output:
            for name in original.namelist():
                self.assertEqual(original.read(name), output.read(name))
            index = json.loads(output.read(builder.INDEX_PATH))
            self.assertEqual(index["version"], 1)
            entry = index["models"][0]
            self.assertEqual(entry["hashSha256"], hashlib.sha256(source_model.read_bytes()).hexdigest())
            self.assertEqual(entry["modelResource"], "meplayeractions:models/first.bbmodel")
            metadata = output.read("assets/meplayeractions/models/first.bbmodel").decode("utf-8")
            reference = json.loads(metadata)["textures"][0]["source"]
            self.assertEqual(reference, "modelengine:textures/skin.png")
            restored = metadata.replace(json.dumps(reference), json.dumps(self.source)).encode("utf-8")
            self.assertEqual(restored, source_model.read_bytes())
            self.assertNotIn(builder.PNG_PREFIX, metadata)
            self.assertEqual([name for name in output.namelist() if name.endswith(".png")],
                             ["assets/modelengine/textures/skin.png"])
        self.assertEqual(original_engine, engine.read_bytes())

    def test_empty_engine_pack_deduplicates_textures_and_is_deterministic(self) -> None:
        one, two = self.model("one"), self.model("two")
        first = self.build([("two", two), ("one", one)], name="first.zip")
        second = self.build([("one", one), ("two", two)], name="second.zip")
        self.assertEqual(Path(first["output"]).read_bytes(), Path(second["output"]).read_bytes())
        with zipfile.ZipFile(first["output"]) as archive:
            pngs = [name for name in archive.namelist() if name.endswith(".png")]
            self.assertEqual(pngs, [f"assets/meplayeractions/textures/sha256/{builder.sha256(self.png)}.png"])
            self.assertEqual(json.loads(archive.read("pack.mcmeta"))["pack"]["pack_format"], 71)
            self.assertEqual([entry["modelId"] for entry in json.loads(archive.read(builder.INDEX_PATH))["models"]], ["one", "two"])
            self.assertTrue(all(info.date_time == builder.ZIP_TIME for info in archive.infolist()))

    def test_conflicting_engine_overlay_prevents_reuse_of_root_png(self) -> None:
        engine = self.engine([("assets/modelengine/textures/skin.png", self.png),
                              ("engine_overlay/assets/modelengine/textures/skin.png", b"different PNG")])
        result = self.build([("first", self.model())], engine)
        with zipfile.ZipFile(result["output"]) as archive:
            source = json.loads(archive.read("assets/meplayeractions/models/first.bbmodel"))["textures"][0]["source"]
            self.assertEqual(source, f"meplayeractions:textures/sha256/{builder.sha256(self.png)}.png")

    def test_pixel_equivalent_engine_encoding_is_normalized_and_recorded(self) -> None:
        optimized = png(level=0)
        self.assertNotEqual(optimized, self.png)
        engine = self.engine([("assets/modelengine/textures/skin.png", optimized),
                              ("overlay/assets/modelengine/textures/skin.png", optimized),
                              ("assets/modelengine/models/unchanged.json", b'{"parent":"item/generated"}')])
        original_engine = engine.read_bytes()
        model = self.model()
        result = self.build([("first", model)], engine)
        with zipfile.ZipFile(result["output"]) as archive:
            for path in ("assets/modelengine/textures/skin.png", "overlay/assets/modelengine/textures/skin.png"):
                self.assertEqual(archive.read(path), self.png)
            self.assertEqual(archive.read("assets/modelengine/models/unchanged.json"), b'{"parent":"item/generated"}')
            self.assertFalse(any(name.startswith("assets/meplayeractions/textures/") for name in archive.namelist()))
            metadata = archive.read("assets/meplayeractions/models/first.bbmodel").decode("utf-8")
            reference = json.loads(metadata)["textures"][0]["source"]
            self.assertEqual(reference, "modelengine:textures/skin.png")
            self.assertEqual(metadata.replace(json.dumps(reference), json.dumps(self.source)).encode(), model.read_bytes())
            normalization = json.loads(archive.read(builder.NORMALIZATION_PATH))
            self.assertEqual(normalization["enginePackSha256"], builder.sha256(original_engine))
            self.assertEqual(len(normalization["pngEncodingRewrites"]), 2)
            row = normalization["pngEncodingRewrites"][0]
            self.assertEqual(row["originalPngSha256"], builder.sha256(optimized))
            self.assertEqual(row["sourcePngSha256"], builder.sha256(self.png))
            self.assertEqual(row["rgbaSha256"], builder.sha256(b"\x12\x34\x56\xff"))
            self.assertTrue(row["pixelsEqual"])
        self.assertEqual(engine.read_bytes(), original_engine)

    def test_different_pixels_are_never_normalized(self) -> None:
        different = png(pixel=b"\x00\x00\x00\xff")
        engine = self.engine([("assets/modelengine/textures/skin.png", different)])
        result = self.build([("first", self.model())], engine)
        with zipfile.ZipFile(result["output"]) as archive:
            self.assertEqual(archive.read("assets/modelengine/textures/skin.png"), different)
            self.assertEqual(json.loads(archive.read(builder.NORMALIZATION_PATH))["pngEncodingRewrites"], [])
            self.assertEqual(len([name for name in archive.namelist() if name.endswith(".png")]), 2)

    def test_same_pixels_in_two_differently_encoded_models_preserve_both_raw_hashes(self) -> None:
        engine = self.engine([("assets/modelengine/textures/skin.png", png(level=0))])
        one = self.model("one")
        second_source = builder.PNG_PREFIX + base64.b64encode(png(level=1)).decode("ascii")
        two = self.model("two", source=second_source)
        result = self.build([("one", one), ("two", two)], engine)
        with zipfile.ZipFile(result["output"]) as archive:
            for name, model in (("one", one), ("two", two)):
                metadata = archive.read(f"assets/meplayeractions/models/{name}.bbmodel").decode()
                reference = json.loads(metadata)["textures"][0]["source"]
                namespace, path = reference.split(":", 1)
                source = builder.PNG_PREFIX + base64.b64encode(archive.read(f"assets/{namespace}/{path}")).decode("ascii")
                self.assertEqual(metadata.replace(json.dumps(reference), json.dumps(source)).encode(), model.read_bytes())

    def test_generated_namespace_conflicts_are_refused(self) -> None:
        for conflict in (builder.INDEX_PATH, "overlay/" + builder.INDEX_PATH,
                         "assets/meplayeractions/models/first.bbmodel", "assets/meplayeractions/models"):
            with self.subTest(conflict=conflict):
                engine = self.engine([(conflict, b"historical engine entry")])
                before = engine.read_bytes()
                with self.assertRaises(builder.PackError):
                    self.build([("first", self.model())], engine)
                self.assertEqual(engine.read_bytes(), before)
                self.assertFalse((self.root / "output.zip").exists())

    def test_unsafe_zip_paths_are_refused_without_writing_output(self) -> None:
        for name in ("../outside", "/absolute", "C:/drive", "a\\b", "a/../b", "a//b", "a//", "a/./b", "nul\x00file"):
            with self.subTest(name=name):
                # zipfile truncates NUL names itself; test this boundary directly.
                if "\x00" in name:
                    with self.assertRaises(builder.PackError):
                        builder.safe_entry_name(name)
                    continue
                engine = self.engine([("a/b" if "\\" in name else name, b"data")])
                if "\\" in name:
                    engine.write_bytes(engine.read_bytes().replace(b"a/b", b"a\\b"))
                with self.assertRaises(builder.PackError):
                    self.build([("first", self.model())], engine)
                self.assertFalse((self.root / "output.zip").exists())

    def test_duplicate_case_and_file_directory_zip_collisions_are_refused(self) -> None:
        for entries in ([('same', b'a'), ('same', b'b')], [('same', b'a'), ('SAME', b'b')],
                        [('folder', b'a'), ('folder/file', b'b')], [('folder/', b''), ('folder', b'b')]):
            with self.subTest(entries=entries):
                with self.assertRaises(builder.PackError):
                    self.build([("first", self.model())], self.engine(entries))

    def test_zip_symlink_is_refused(self) -> None:
        link = zipfile.ZipInfo("link")
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        with self.assertRaises(builder.PackError):
            self.build([("first", self.model())], self.engine([(link, b"../outside")]))

    def test_entry_total_entry_count_and_compressed_input_limits(self) -> None:
        engine = self.engine([("large", b"x" * 100)])
        for constant, limit in (("MAX_ENTRY_BYTES", 64), ("MAX_TOTAL_BYTES", 64),
                                ("MAX_ENTRIES", 1), ("MAX_ZIP_BYTES", 1)):
            with self.subTest(constant=constant), patch.object(builder, constant, limit):
                with self.assertRaises(builder.PackError):
                    self.build([("first", self.model())], engine)
                self.assertFalse((self.root / "output.zip").exists())
        with patch.object(builder, "MAX_MODEL_BYTES", 16):
            with self.assertRaises(builder.PackError):
                self.build([("first", self.model())])

    def test_source_escaping_and_noncanonical_base64_are_refused(self) -> None:
        path = self.model()
        path.write_bytes(path.read_bytes().replace(b"data:image/png", b"data:image\\/png"))
        with self.assertRaisesRegex(builder.PackError, "escaping"):
            self.build([("first", path)])
        with self.assertRaises(builder.PackError):
            self.build([("first", self.model(source=self.source + "="))])
        path = self.model()
        path.write_bytes(path.read_bytes().replace(b'"source"', b'"\\u0073ource"'))
        with self.assertRaisesRegex(builder.PackError, "ordinary JSON spelling"):
            self.build([("first", path)])

    def test_invalid_json_png_and_external_texture_are_refused(self) -> None:
        bad_png = self.png[:-1] + bytes([self.png[-1] ^ 1])
        with self.assertRaisesRegex(builder.PackError, "CRC"):
            self.build([("first", self.model(source=builder.PNG_PREFIX + base64.b64encode(bad_png).decode("ascii")))])
        with self.assertRaises(builder.PackError):
            self.build([("first", self.model(source="assets/engine/skin.png"))])
        path = self.root / "bad.bbmodel"
        for raw in (b'{"textures":[],"textures":[]}', b'{"textures":NaN}', b'{"textures":1e9999}', b'\xff',
                    ('{"textures":[{"source":' + json.dumps(self.source) + '}],"deep":' + '[' * 65 + '0' + ']' * 65 + '}').encode()):
            with self.subTest(raw=raw[:40]):
                path.write_bytes(raw)
                with self.assertRaises(builder.PackError):
                    self.build([("first", path)])

    def test_input_and_historical_outputs_are_never_overwritten(self) -> None:
        model = self.model()
        original_model = model.read_bytes()
        with self.assertRaises(builder.PackError):
            builder.build_pack(None, [("first", model)], model, pack_format=71)
        output = self.root / "historical.zip"
        output.write_bytes(b"preserve historical release")
        with self.assertRaises(builder.PackError):
            builder.build_pack(None, [("first", model)], output, pack_format=71)
        self.assertEqual(output.read_bytes(), b"preserve historical release")
        self.assertEqual(model.read_bytes(), original_model)

    def test_model_id_path_escape_and_duplicates_are_refused(self) -> None:
        model = self.model()
        for model_id in ("../escaped", "dir/model", "UPPER", "", "a:b", "a.b", "x" * 65):
            with self.subTest(model_id=model_id), self.assertRaises(builder.PackError):
                self.build([(model_id, model)])
        with self.assertRaises(builder.PackError):
            self.build([("first", model), ("first", model)])

    def test_standalone_requires_explicit_format_engine_metadata_stays_unchanged(self) -> None:
        with self.assertRaises(builder.PackError):
            builder.build_pack(None, [("first", self.model())], self.root / "output.zip")
        engine = self.engine()
        result = builder.build_pack(engine, [("first", self.model())], self.root / "output.zip", pack_format=999)
        with zipfile.ZipFile(engine) as original, zipfile.ZipFile(result["output"]) as archive:
            self.assertEqual(original.read("pack.mcmeta"), archive.read("pack.mcmeta"))


if __name__ == "__main__":
    unittest.main()
