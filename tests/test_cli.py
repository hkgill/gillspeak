import subprocess
import sys

import pytest

from murmur import cli


def test_cli_import_is_light():
    code = "import sys, murmur.cli; bad = [m for m in ('numpy', 'sherpa_onnx', 'httpx', 'sounddevice') if m in sys.modules]; print(bad)"
    out = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, check=True).stdout.strip()
    assert out == "[]"


def test_not_running_exit_code(capsys):
    with pytest.raises(SystemExit) as e:
        cli.main(["toggle"])
    assert e.value.code == 2
    assert "murmurd is not running — systemctl --user start murmurd" in capsys.readouterr().err


def test_parser():
    a = cli.build_parser().parse_args(["toggle", "--mode", "formal", "--chord", "ctrl+shift+v"])
    assert (a.mode, a.raw, a.chord) == ("formal", False, "ctrl+shift+v")
    a = cli.build_parser().parse_args(["toggle", "--raw"])
    assert a.raw is True
