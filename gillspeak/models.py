"""Model registry and downloader (`gillspeak download-models`)."""

from __future__ import annotations

import hashlib
import json
import logging
import shutil
import sys
import tarfile
import tempfile
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger(__name__)

RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
MANIFEST = "gillspeak-manifest.json"


@dataclass(frozen=True)
class ModelSpec:
    name: str          # config asr.engine / vad name
    archive: str       # release asset file name
    sha256: str        # of the release asset, pinned
    dirname: str       # extracted directory ("" for single files)
    files: dict[str, str]  # role -> file name inside dirname
    source: str = ""   # full download URL when it isn't a sherpa-onnx release asset

    @property
    def url(self) -> str:
        return self.source or f"{RELEASE}/{self.archive}"


# SHA256 values pinned from the sherpa-onnx `asr-models` release on 2026-09-26.
MODELS: dict[str, ModelSpec] = {
    "parakeet-tdt-0.6b-v3-int8": ModelSpec(
        "parakeet-tdt-0.6b-v3-int8",
        "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
        "5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf",
        "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        {"encoder": "encoder.int8.onnx", "decoder": "decoder.int8.onnx", "joiner": "joiner.int8.onnx", "tokens": "tokens.txt"},
    ),
    "moonshine-base": ModelSpec(
        "moonshine-base",
        "sherpa-onnx-moonshine-base-en-int8.tar.bz2",
        "21870cecaa2e44e4e2bf63e02d1072bed183ccd10284871353bd9d24dad14e5e",
        "sherpa-onnx-moonshine-base-en-int8",
        {
            "preprocessor": "preprocess.onnx",
            "encoder": "encode.int8.onnx",
            "uncached_decoder": "uncached_decode.int8.onnx",
            "cached_decoder": "cached_decode.int8.onnx",
            "tokens": "tokens.txt",
        },
    ),
    "whisper-base.en": ModelSpec(
        "whisper-base.en",
        "sherpa-onnx-whisper-base.en.tar.bz2",
        "475bc7052ce299c007f6d5d5407ba8601f819a2867f6eecee510ed17df581542",
        "sherpa-onnx-whisper-base.en",
        {"encoder": "base.en-encoder.int8.onnx", "decoder": "base.en-decoder.int8.onnx", "tokens": "base.en-tokens.txt"},
    ),
    "silero-vad": ModelSpec(
        "silero-vad",
        "silero_vad.onnx",
        "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
        "",
        {"model": "silero_vad.onnx"},
    ),
}

ASR_ENGINES = [k for k in MODELS if k != "silero-vad"]

# Local clean-up (llm.provider = "local"): llama.cpp's server and a small instruct model, both pinned.
LLAMA_BUILD = "b11223"
_LLAMA_RELEASE = f"https://github.com/ggml-org/llama.cpp/releases/download/{LLAMA_BUILD}"
LLAMA_SERVERS: dict[str, ModelSpec] = {
    arch: ModelSpec(
        "llama-server",
        f"llama-{LLAMA_BUILD}-bin-ubuntu-{asset}.tar.gz",
        sha,
        f"llama-{LLAMA_BUILD}",
        {"server": "llama-server"},
        f"{_LLAMA_RELEASE}/llama-{LLAMA_BUILD}-bin-ubuntu-{asset}.tar.gz",
    )
    for arch, asset, sha in (
        ("x86_64", "x64", "9ce07ebd35ccdbae598ef34d21607564c4a21956cf075409f82e4f4fe871ea5e"),
        ("aarch64", "arm64", "e4280e5c71369a04bef5a171e50ab77fd923f6c86318106b19942397c4ded75f"),
    )
}

# Apache-2.0. Pinned to a Hugging Face commit so the file can't change underneath the checksum.
LLM_MODELS: dict[str, ModelSpec] = {
    "qwen3.5-2b": ModelSpec(
        "qwen3.5-2b",
        "Qwen3.5-2B-Q4_K_M.gguf",
        "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
        "",
        {"model": "Qwen3.5-2B-Q4_K_M.gguf"},
        "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/f6d5376be1edb4d416d56da11e5397a961aca8ae/Qwen3.5-2B-Q4_K_M.gguf",
    ),
}


def llama_server_spec() -> ModelSpec | None:
    """The llama-server build for this CPU, or None where there's no prebuilt one."""
    import platform

    return LLAMA_SERVERS.get(platform.machine())


def model_files(spec: ModelSpec, model_dir: Path) -> dict[str, Path]:
    base = model_dir / spec.dirname if spec.dirname else model_dir
    return {role: base / name for role, name in spec.files.items()}


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def _manifest_path(model_dir: Path) -> Path:
    return model_dir / MANIFEST


def load_manifest(model_dir: Path) -> dict[str, str]:
    try:
        return json.loads(_manifest_path(model_dir).read_text())
    except (OSError, ValueError):
        return {}


def _save_manifest(model_dir: Path, entries: dict[str, str]) -> None:
    manifest = load_manifest(model_dir)
    manifest.update(entries)
    _manifest_path(model_dir).write_text(json.dumps(manifest, indent=2, sort_keys=True))


def verify(spec: ModelSpec, model_dir: Path) -> tuple[bool, str]:
    """Check files exist and match the checksums recorded at download time."""
    manifest = load_manifest(model_dir)
    for path in model_files(spec, model_dir).values():
        if not path.exists():
            return False, f"missing {path}"
        rel = str(path.relative_to(model_dir))
        expected = manifest.get(rel)
        if expected is None:
            return False, f"no checksum recorded for {rel} (re-run `gillspeak download-models`)"
        if sha256_file(path) != expected:
            return False, f"checksum mismatch for {rel}"
    return True, "ok"


def download(spec: ModelSpec, model_dir: Path, *, force: bool = False) -> None:
    import httpx

    files = model_files(spec, model_dir)
    if not force and all(p.exists() for p in files.values()) and verify(spec, model_dir)[0]:
        print(f"✅ {spec.name}: already present")
        return
    model_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=model_dir) as tmp:
        dest = Path(tmp) / spec.archive
        h = hashlib.sha256()
        with httpx.stream("GET", spec.url, follow_redirects=True, timeout=httpx.Timeout(30.0)) as r:
            r.raise_for_status()
            total = int(r.headers.get("content-length", 0))
            done = 0
            with open(dest, "wb") as f:
                for block in r.iter_bytes(1 << 20):
                    f.write(block)
                    h.update(block)
                    done += len(block)
                    if total and sys.stderr.isatty():
                        print(f"\r  {spec.name}: {done * 100 // total:3d}% of {total >> 20} MB", end="", file=sys.stderr)
        if sys.stderr.isatty():
            print(file=sys.stderr)
        digest = h.hexdigest()
        if digest != spec.sha256:
            raise RuntimeError(f"{spec.archive}: SHA256 mismatch (got {digest}, expected {spec.sha256})")
        if spec.archive.endswith((".tar.bz2", ".tar.gz", ".tgz")):
            with tarfile.open(dest) as tar:
                tar.extractall(tmp, filter="data")
            src = Path(tmp) / spec.dirname
            target = model_dir / spec.dirname
            if target.exists():
                shutil.rmtree(target)
            shutil.move(str(src), str(target))
        else:
            shutil.move(str(dest), str(files[next(iter(files))]))
    _save_manifest(model_dir, {str(p.relative_to(model_dir)): sha256_file(p) for p in files.values()})
    print(f"✅ {spec.name}: installed in {model_dir}")
