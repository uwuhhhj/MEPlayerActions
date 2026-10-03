"""Build one bounded, deterministic resource pack for engine and client assets.

The optional engine ZIP is read only. Model metadata retains the original JSON
text except for canonical embedded-PNG source tokens, so restoring those tokens
reproduces the exact input bytes and their server asset SHA-256.
"""
from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import io
import json
import math
import re
import stat
import struct
from pathlib import Path
from typing import Iterable
import warnings
import zipfile
import zlib

VERSION = 1
INDEX_PATH = "assets/meplayeractions/models/index.json"
NORMALIZATION_PATH = "assets/meplayeractions/models/normalization.json"
PNG_PREFIX = "data:image/png;base64,"
ZIP_TIME = (1980, 1, 1, 0, 0, 0)
MAX_ZIP_BYTES = 256 * 1024 * 1024
MAX_TOTAL_BYTES = 256 * 1024 * 1024
MAX_ENTRY_BYTES = 32 * 1024 * 1024
MAX_ENTRIES = 65_536
MAX_MODEL_BYTES = 8 * 1024 * 1024
MAX_MODELS = 128
MAX_TEXTURES = 16
MAX_TEXTURE_BYTES = 6 * 1024 * 1024
MAX_TEXTURE_PIXELS = 16_777_216
MAX_JSON_DEPTH = 64
MODEL_ID = re.compile(r"[a-z0-9_-]{1,64}\Z")
RESOURCE_PATH = re.compile(r"assets/([a-z0-9_.-]+)/([a-z0-9_./-]+)\Z")


class PackError(ValueError):
    """An input cannot safely produce the version-one pack contract."""


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise PackError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def _invalid_constant(value: str) -> None:
    raise PackError(f"Non-finite JSON value: {value}")


def _finite_float(value: str) -> float:
    result = float(value)
    if not math.isfinite(result):
        raise PackError(f"Non-finite JSON number: {value}")
    return result


def parse_json(data: bytes, label: str) -> tuple[str, dict]:
    try:
        text = data.decode("utf-8", errors="strict")
        value = json.loads(text, object_pairs_hook=_object, parse_constant=_invalid_constant, parse_float=_finite_float)
    except (UnicodeError, ValueError, RecursionError) as failure:
        raise PackError(f"Invalid UTF-8 JSON in {label}: {failure}") from failure
    if not isinstance(value, dict):
        raise PackError(f"{label} must contain a JSON object")
    return text, value


def safe_entry_name(name: str) -> None:
    if not name or len(name) > 512 or name.startswith("/") or "\\" in name or ":" in name \
            or any(ord(character) < 32 or ord(character) == 127 for character in name):
        raise PackError(f"Unsafe ZIP entry path: {name!r}")
    parts = (name[:-1] if name.endswith("/") else name).split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise PackError(f"Unsafe ZIP entry path: {name!r}")


def read_engine_pack(path: Path | None) -> dict[str, bytes]:
    if path is None:
        return {}
    if path.stat().st_size > MAX_ZIP_BYTES:
        raise PackError("Engine ZIP exceeds 256 MiB")
    entries: dict[str, bytes] = {}
    names: set[str] = set()
    total = 0
    try:
        with zipfile.ZipFile(path) as archive:
            if len(archive.infolist()) > MAX_ENTRIES:
                raise PackError("Engine ZIP has too many entries")
            for info in archive.infolist():
                # ZipInfo normalizes Windows separators and truncates NUL in
                # filename; the central-directory spelling must be checked too.
                safe_entry_name(info.orig_filename)
                safe_entry_name(info.filename)
                collision_key = info.filename.rstrip("/").casefold()
                if collision_key in names:
                    raise PackError(f"Duplicate or case-colliding ZIP entry: {info.filename}")
                names.add(collision_key)
                mode = (info.external_attr >> 16) & 0xFFFF
                if stat.S_ISLNK(mode) or (mode and stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR)):
                    raise PackError(f"Non-regular ZIP entry: {info.filename}")
                if info.flag_bits & 1 or info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                    raise PackError(f"Unsupported encrypted/compressed ZIP entry: {info.filename}")
                total += info.file_size
                if info.file_size > MAX_ENTRY_BYTES or total > MAX_TOTAL_BYTES:
                    raise PackError("Engine ZIP exceeds entry or expanded-size limits")
                with archive.open(info) as source:
                    data = source.read(MAX_ENTRY_BYTES + 1)
                if len(data) != info.file_size or len(data) > MAX_ENTRY_BYTES:
                    raise PackError(f"ZIP entry size mismatch: {info.filename}")
                if info.is_dir() and data:
                    raise PackError(f"Nonempty ZIP directory: {info.filename}")
                entries[info.filename] = data
    except (zipfile.BadZipFile, RuntimeError, NotImplementedError, OSError) as failure:
        raise PackError(f"Cannot read engine ZIP: {failure}") from failure
    if "pack.mcmeta" not in entries:
        raise PackError("Engine resource pack must contain root pack.mcmeta")
    _, metadata = parse_json(entries["pack.mcmeta"], "pack.mcmeta")
    if not isinstance(metadata.get("pack"), dict):
        raise PackError("pack.mcmeta is missing its pack object")
    # A file cannot also be the parent of another ZIP entry.
    files = {name.casefold() for name in entries if not name.endswith("/")}
    for name in entries:
        parts = name.rstrip("/").split("/")
        if any("/".join(parts[:index]).casefold() in files for index in range(1, len(parts))):
            raise PackError(f"ZIP file/directory collision: {name}")
    return entries


def _texture_spans(text: str) -> dict[int, tuple[int, int]]:
    """Locate texture.source tokens without reserializing other model bytes."""
    decoder = json.JSONDecoder()
    spans: dict[int, tuple[int, int]] = {}

    def skip(index: int) -> int:
        while index < len(text) and text[index] in " \r\n\t":
            index += 1
        return index

    def walk(index: int, path: tuple, depth: int) -> int:
        if depth > MAX_JSON_DEPTH:
            raise PackError("Model JSON exceeds 64 levels")
        index = skip(index)
        marker = text[index]
        if marker == "{":
            index = skip(index + 1)
            if text[index] == "}":
                return index + 1
            while True:
                key, end = decoder.raw_decode(text, index)
                if len(path) == 2 and path[0] == "textures" and type(path[1]) is int and key == "source" \
                        and text[index:end] != '"source"':
                    raise PackError("Texture source key must use its ordinary JSON spelling")
                index = walk(skip(end) + 1, path + (key,), depth + 1)
                index = skip(index)
                if text[index] == "}":
                    return index + 1
                index = skip(index + 1)
        if marker == "[":
            index = skip(index + 1)
            if text[index] == "]":
                return index + 1
            item = 0
            while True:
                index = walk(index, path + (item,), depth + 1)
                index = skip(index)
                if text[index] == "]":
                    return index + 1
                index = skip(index + 1)
                item += 1
        _, end = decoder.raw_decode(text, index)
        if len(path) == 3 and path[0] == "textures" and type(path[1]) is int and path[2] == "source":
            spans[path[1]] = (index, end)
        return end

    walk(0, (), 0)
    return spans


def png_pixels(data: bytes) -> int:
    if len(data) > MAX_TEXTURE_BYTES or not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise PackError("Texture must be a bounded PNG")
    index = 8
    pixels = 0
    saw_data = False
    while index + 12 <= len(data):
        length = struct.unpack_from(">I", data, index)[0]
        end = index + 12 + length
        if end > len(data):
            raise PackError("Truncated PNG chunk")
        kind = data[index + 4:index + 8]
        payload = data[index + 8:index + 8 + length]
        crc = struct.unpack_from(">I", data, index + 8 + length)[0]
        if zlib.crc32(kind + payload) & 0xFFFFFFFF != crc:
            raise PackError("PNG chunk CRC mismatch")
        if index == 8:
            if kind != b"IHDR" or length != 13:
                raise PackError("PNG must begin with one IHDR chunk")
            width, height = struct.unpack_from(">II", payload)
            if not 1 <= width <= 4096 or not 1 <= height <= 4096:
                raise PackError("PNG dimensions exceed client limits")
            pixels = width * height
        elif kind == b"IHDR":
            raise PackError("Duplicate PNG IHDR")
        saw_data |= kind == b"IDAT"
        if kind == b"IEND":
            if length or end != len(data) or not saw_data or pixels > MAX_TEXTURE_PIXELS:
                raise PackError("Invalid PNG ending or pixel limit")
            return pixels
        index = end
    raise PackError("PNG is missing IEND")


def resource_id(path: str) -> str | None:
    match = RESOURCE_PATH.fullmatch(path)
    return f"{match[1]}:{match[2]}" if match else None


def decoded_png_identity(data: bytes) -> tuple[int, int, str]:
    """Bounded full PNG decode; equality concerns rendered RGBA, not encoding."""
    png_pixels(data)
    try:
        from PIL import Image
    except ImportError as failure:
        raise PackError("PNG pixel verification requires Pillow (python -m pip install Pillow)") from failure
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(data), formats=("PNG",)) as image:
                image.load()
                rgba = image.convert("RGBA")
                return rgba.width, rgba.height, sha256(rgba.tobytes())
    except (OSError, ValueError, Image.DecompressionBombError, Image.DecompressionBombWarning) as failure:
        raise PackError(f"Cannot safely decode PNG pixels: {failure}") from failure


def _shadowed(entries: dict[str, bytes], name: str, data: bytes) -> bool:
    return any(path.endswith("/" + name) and value != data for path, value in entries.items())


def _add_entry(entries: dict[str, bytes], name: str, data: bytes, allow_identical: bool = False) -> None:
    safe_entry_name(name)
    for existing in entries:
        key = existing.rstrip("/").casefold()
        if key == name.casefold() and not (allow_identical and existing == name and entries[existing] == data):
            raise PackError(f"Generated entry collides with engine entry: {name}")
        if name.casefold().startswith(key + "/") and not existing.endswith("/") \
                or key.startswith(name.casefold() + "/"):
            raise PackError(f"Generated file/directory collision: {name}")
    if _shadowed(entries, name, data):
        raise PackError(f"Generated entry is overridden by an engine overlay: {name}")
    entries[name] = data


def model_metadata(raw: bytes, entries: dict[str, bytes], *, rewrites: list[dict] | None = None,
                   pixel_cache: dict[str, tuple[int, int, str] | None] | None = None,
                   referenced_paths: dict[str, str] | None = None) -> tuple[bytes, list[str]]:
    if len(raw) > MAX_MODEL_BYTES:
        raise PackError("Model exceeds 8 MiB")
    text, model = parse_json(raw, "bbmodel")
    textures = model.get("textures")
    if not isinstance(textures, list) or not 1 <= len(textures) <= MAX_TEXTURES:
        raise PackError("Model requires 1..16 textures")
    spans = _texture_spans(text)
    replacements = []
    references = []
    total_pixels = 0
    rewrites = rewrites if rewrites is not None else []
    pixel_cache = pixel_cache if pixel_cache is not None else {}
    referenced_paths = referenced_paths if referenced_paths is not None else {}

    def identity(data: bytes, required: bool = False) -> tuple[int, int, str] | None:
        digest = sha256(data)
        if digest not in pixel_cache:
            try:
                pixel_cache[digest] = decoded_png_identity(data)
            except PackError:
                if required:
                    raise
                pixel_cache[digest] = None
        if required and pixel_cache[digest] is None:
            raise PackError("Model PNG cannot be decoded safely")
        return pixel_cache[digest]

    for index, texture in enumerate(textures):
        source = texture.get("source") if isinstance(texture, dict) else None
        if not isinstance(source, str) or not source.startswith(PNG_PREFIX) or index not in spans:
            raise PackError("Model texture.source must be an embedded PNG data URI")
        start, end = spans[index]
        if text[start:end] != json.dumps(source, ensure_ascii=False):
            raise PackError("Texture source JSON escaping cannot restore the exact original asset hash")
        encoded = source[len(PNG_PREFIX):]
        try:
            png = base64.b64decode(encoded, validate=True)
        except (ValueError, binascii.Error) as failure:
            raise PackError("Invalid PNG base64") from failure
        if base64.b64encode(png).decode("ascii") != encoded:
            raise PackError("Texture PNG base64 must be canonical")
        total_pixels += png_pixels(png)
        if total_pixels > MAX_TEXTURE_PIXELS:
            raise PackError("Model textures exceed aggregate 16,777,216 pixels")
        digest = sha256(png)
        pixels = identity(png, required=True)
        # Resource IDs address the root assets tree. An overlay with different
        # bytes at the same resource ID would invalidate reconstructed hashes.
        candidates = sorted(name for name, value in entries.items() if name.endswith(".png")
                            and resource_id(name) is not None and value == png and not _shadowed(entries, name, png))
        if candidates:
            path = candidates[0]
        else:
            path = ""
            # Engine optimizers commonly replace RGBA PNGs with indexed PNGs.
            # Keep one resource by rewriting only pixel-equivalent engine PNGs
            # in the final copy to the original model bytes. This preserves the
            # exact server model hash after the client restores its source.
            for candidate in sorted(name for name in entries if name.endswith(".png") and resource_id(name)):
                affected = [candidate] + sorted(name for name in entries if name.endswith("/" + candidate))
                if any(name in referenced_paths and referenced_paths[name] != digest for name in affected):
                    continue
                if all(identity(entries[name]) == pixels for name in affected):
                    for name in affected:
                        if entries[name] != png:
                            rewrites.append({"assetPath": name, "originalPngSha256": sha256(entries[name]),
                                             "sourcePngSha256": digest, "width": pixels[0], "height": pixels[1],
                                             "rgbaSha256": pixels[2], "pixelsEqual": True})
                            entries[name] = png
                    path = candidate
                    break
            if not path:
                path = f"assets/meplayeractions/textures/sha256/{digest}.png"
                _add_entry(entries, path, png, allow_identical=True)
        referenced_paths[path] = digest
        reference = resource_id(path)
        assert reference is not None
        replacements.append((start, end, json.dumps(reference, ensure_ascii=False)))
        references.append(reference)
    for start, end, replacement in sorted(replacements, reverse=True):
        text = text[:start] + replacement + text[end:]
    return text.encode("utf-8"), references


def build_pack(engine_zip: Path | None, models: Iterable[tuple[str, Path]], output: Path,
               *, pack_format: int | None = None, description: str = "MEPlayerActions client models") -> dict:
    models = sorted(list(models), key=lambda item: item[0])
    if not 1 <= len(models) <= MAX_MODELS:
        raise PackError("Supply 1..128 models")
    ids = [model_id for model_id, _ in models]
    if len(set(ids)) != len(ids) or any(not MODEL_ID.fullmatch(model_id) for model_id in ids):
        raise PackError("Model IDs must be unique names matching [a-z0-9_-]{1,64}")
    output = Path(output).resolve()
    inputs = [Path(path).resolve() for _, path in models] + ([Path(engine_zip).resolve()] if engine_zip else [])
    if output in inputs or output.exists():
        raise PackError("Output must be a new path; input and historical packs are never overwritten")
    entries = read_engine_pack(Path(engine_zip) if engine_zip else None)
    if engine_zip is None:
        if type(pack_format) is not int or not 1 <= pack_format <= 32767:
            raise PackError("An empty standalone pack requires explicit --pack-format (1..32767)")
        entries["pack.mcmeta"] = (json.dumps({"pack": {"pack_format": pack_format, "description": description}},
                                            ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
    index = {"version": VERSION, "models": []}
    rewrites: list[dict] = []
    pixel_cache: dict[str, tuple[int, int, str] | None] = {}
    referenced_paths: dict[str, str] = {}
    for model_id, path in models:
        path = Path(path)
        if path.stat().st_size > MAX_MODEL_BYTES:
            raise PackError(f"Model exceeds 8 MiB: {path}")
        raw = path.read_bytes()
        metadata, _ = model_metadata(raw, entries, rewrites=rewrites, pixel_cache=pixel_cache,
                                     referenced_paths=referenced_paths)
        model_path = f"assets/meplayeractions/models/{model_id}.bbmodel"
        _add_entry(entries, model_path, metadata)
        index["models"].append({"modelId": model_id, "hashSha256": sha256(raw),
                                "modelResource": resource_id(model_path)})
    _add_entry(entries, INDEX_PATH, (json.dumps(index, ensure_ascii=False, sort_keys=True,
                                              separators=(",", ":")) + "\n").encode("utf-8"))
    normalization = {"version": VERSION, "enginePackSha256": sha256(Path(engine_zip).read_bytes()) if engine_zip else None,
                     "pngEncodingRewrites": sorted(rewrites, key=lambda row: row["assetPath"])}
    _add_entry(entries, NORMALIZATION_PATH, (json.dumps(normalization, ensure_ascii=False, sort_keys=True,
                                                      separators=(",", ":")) + "\n").encode("utf-8"))
    if len(entries) > MAX_ENTRIES or sum(map(len, entries.values())) > MAX_TOTAL_BYTES:
        raise PackError("Final pack exceeds expanded-size or entry-count limits")
    output.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation protects both source archives and existing deliveries.
    with output.open("xb") as destination:
        with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name, data in sorted(entries.items()):
                info = zipfile.ZipInfo(name, ZIP_TIME)
                info.create_system = 3
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = ((stat.S_IFDIR | 0o755) << 16 | 0x10) if name.endswith("/") else (stat.S_IFREG | 0o644) << 16
                archive.writestr(info, data, compresslevel=9)
    if output.stat().st_size > MAX_ZIP_BYTES:
        raise PackError("Final ZIP exceeds 256 MiB")
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None or archive.namelist() != sorted(entries):
            raise PackError("Final ZIP integrity verification failed")
        for name, expected in entries.items():
            if archive.read(name) != expected:
                raise PackError(f"Final ZIP entry verification failed: {name}")
    return {"output": str(output), "sha256": sha256(output.read_bytes()), "entries": len(entries),
            "bytes": output.stat().st_size, "models": index["models"],
            "pngEncodingRewrites": normalization["pngEncodingRewrites"]}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine-pack", type=Path, help="Existing complete engine resource-pack ZIP (read only)")
    parser.add_argument("--model", action="append", required=True, metavar="MODEL_ID=BBMODEL", help="Repeat for each server model")
    parser.add_argument("--output", type=Path, required=True, help="New output ZIP path; existing files are refused")
    parser.add_argument("--pack-format", type=int, help="Required only when building without an engine pack")
    parser.add_argument("--description", default="MEPlayerActions client models")
    args = parser.parse_args()
    try:
        models = []
        for specification in args.model:
            model_id, separator, path = specification.partition("=")
            if not separator or not path:
                raise PackError("--model requires MODEL_ID=BBMODEL")
            models.append((model_id, Path(path)))
        result = build_pack(args.engine_pack, models, args.output, pack_format=args.pack_format, description=args.description)
    except (PackError, OSError) as failure:
        parser.exit(2, f"resource-pack build failed: {failure}\n")
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
