"""Model downloads: pinned SHA256, extraction, manifest and tamper detection. HTTP is mocked."""

import hashlib
import io
import tarfile

import httpx
import pytest
import respx

from gillspeak import models
from gillspeak.models import MODELS, ModelSpec, download, load_manifest, verify


def _tarball(dirname, files):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:bz2") as tar:
        for name, data in files.items():
            info = tarfile.TarInfo(f"{dirname}/{name}")
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    return buf.getvalue()


def _spec(archive, payload, dirname="m", files=None):
    return ModelSpec("test-model", archive, hashlib.sha256(payload).hexdigest(), dirname, files or {"model": "model.onnx"})


def test_registry_is_pinned_and_consistent():
    for name, spec in MODELS.items():
        assert spec.name == name
        assert len(spec.sha256) == 64 and int(spec.sha256, 16) >= 0
        assert spec.url.startswith("https://github.com/k2-fsa/sherpa-onnx/releases/download/")
        assert spec.files
    assert "parakeet-tdt-0.6b-v3-int8" in models.ASR_ENGINES and "silero-vad" not in models.ASR_ENGINES


@respx.mock
def test_download_archive_extracts_and_records_checksums(tmp_path):
    payload = _tarball("m", {"model.onnx": b"weights", "tokens.txt": b"a b"})
    spec = _spec("m.tar.bz2", payload, files={"model": "model.onnx", "tokens": "tokens.txt"})
    respx.get(spec.url).mock(return_value=httpx.Response(200, content=payload))
    download(spec, tmp_path)
    assert (tmp_path / "m" / "model.onnx").read_bytes() == b"weights"
    assert verify(spec, tmp_path) == (True, "ok")
    assert set(load_manifest(tmp_path)) == {"m/model.onnx", "m/tokens.txt"}
    assert not [p for p in tmp_path.iterdir() if p.name.startswith("tmp")]  # temp dir cleaned up


@respx.mock
def test_download_single_file(tmp_path):
    payload = b"vad weights"
    spec = _spec("vad.onnx", payload, dirname="", files={"model": "vad.onnx"})
    respx.get(spec.url).mock(return_value=httpx.Response(200, content=payload))
    download(spec, tmp_path)
    assert (tmp_path / "vad.onnx").read_bytes() == payload and verify(spec, tmp_path)[0]


@respx.mock
def test_sha_mismatch_installs_nothing(tmp_path):
    spec = _spec("vad.onnx", b"expected", dirname="", files={"model": "vad.onnx"})
    respx.get(spec.url).mock(return_value=httpx.Response(200, content=b"tampered"))
    with pytest.raises(RuntimeError, match="SHA256 mismatch"):
        download(spec, tmp_path)
    assert not (tmp_path / "vad.onnx").exists() and load_manifest(tmp_path) == {}


@respx.mock
def test_http_error_raises(tmp_path):
    spec = _spec("vad.onnx", b"x", dirname="", files={"model": "vad.onnx"})
    respx.get(spec.url).mock(return_value=httpx.Response(404))
    with pytest.raises(httpx.HTTPStatusError):
        download(spec, tmp_path)


@respx.mock
def test_already_present_skips_network(tmp_path, capsys):
    payload = b"vad"
    spec = _spec("vad.onnx", payload, dirname="", files={"model": "vad.onnx"})
    route = respx.get(spec.url).mock(return_value=httpx.Response(200, content=payload))
    download(spec, tmp_path)
    download(spec, tmp_path)
    assert route.call_count == 1 and "already present" in capsys.readouterr().out
    download(spec, tmp_path, force=True)
    assert route.call_count == 2


@respx.mock
def test_archive_path_traversal_is_rejected(tmp_path):
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:bz2") as tar:
        info = tarfile.TarInfo("../../escape.txt")
        info.size = 1
        tar.addfile(info, io.BytesIO(b"x"))
    payload = buf.getvalue()
    spec = _spec("evil.tar.bz2", payload)
    respx.get(spec.url).mock(return_value=httpx.Response(200, content=payload))
    with pytest.raises(tarfile.TarError):
        download(spec, tmp_path / "models")
    assert not (tmp_path / "escape.txt").exists()


def test_verify_reports_missing_unrecorded_and_tampered(tmp_path):
    spec = _spec("vad.onnx", b"", dirname="", files={"model": "vad.onnx"})
    assert verify(spec, tmp_path)[1].startswith("missing")
    (tmp_path / "vad.onnx").write_bytes(b"weights")
    assert "no checksum recorded" in verify(spec, tmp_path)[1]
    models._save_manifest(tmp_path, {"vad.onnx": models.sha256_file(tmp_path / "vad.onnx")})
    assert verify(spec, tmp_path) == (True, "ok")
    (tmp_path / "vad.onnx").write_bytes(b"swapped")
    assert "checksum mismatch" in verify(spec, tmp_path)[1]


def test_corrupt_manifest_is_treated_as_empty(tmp_path):
    (tmp_path / models.MANIFEST).write_text("{not json")
    assert load_manifest(tmp_path) == {}
