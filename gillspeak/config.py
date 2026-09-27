"""Configuration: TOML on disk, dataclasses in memory, defaults written on first run."""

from __future__ import annotations

import dataclasses
import json
import logging
import tomllib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, get_type_hints

from . import paths

log = logging.getLogger(__name__)


class ConfigError(Exception):
    pass


DEFAULT_CONFIG_TOML = """\
# gillspeak configuration. Reload without restarting: `gillspeak reload`
# ([audio] and [asr] changes need `systemctl --user restart gillspeakd`).

[audio]
device = "default"          # "default" or a substring of the input device name
keep_mic_open = false       # true keeps a 300 ms pre-roll, but the mic (and GNOME's indicator) stays on
max_seconds = 300

[vad]
engine = "silero"           # silero | energy
min_speech_s = 0.4

[asr]
engine = "parakeet-tdt-0.6b-v3-int8"   # | moonshine-base | whisper-base.en
model_dir = "~/.local/share/gillspeak/models"
num_threads = 4
hotwords = true             # bias ASR towards [bias] terms from dictionary.toml

[rules]
aggressive_fillers = false  # also strip "like" / "you know"
spoken_commands = true
disabled_commands = []      # e.g. ["period", "bullet point"]

[gate]
min_words = 12

# Optional AI clean-up in the cloud. Off by default: with "none", nothing leaves this computer (speech
# recognition is always local; the rules still remove fillers and apply spoken commands and the dictionary).
# "gemini" sends the transcript *text* (never audio) of longer or corrected dictations to Google.
[llm]
provider = "none"                         # none | gemini | proxy
model = "gemini-3.5-flash-lite"           # pinned; `gillspeak doctor` lists the IDs your key can use
timeout_s = 2.5
connect_timeout_s = 1.0
always = false
instructions = "Australian English spelling. Never use em dashes."
extra_generation_config = '{}'            # e.g. '{"thinkingConfig": {"thinkingBudget": 0}}'
check_bias_terms = false                  # reject output that drops a [bias] term
# proxy_url = "https://dictate.example.com/v1/clean"   # v2

[modes]
default = "Keep the speaker's tone."
formal = "Rewrite in a professional, polite tone suitable for work email. Keep all facts."
casual = "Keep it short and conversational."

[inject]
method = "paste"                          # paste | type
paste_chord = "ctrl+v"                    # ctrl+v | ctrl+shift+v
settle_ms = 60
restore_clipboard = true
restore_delay_ms = 400

[hotkey]
hold_to_talk = true                       # hold Right Ctrl + Right Alt; needs gillspeak-keyd (scripts/install-keyd.sh)
keyd_socket = "/run/gillspeak-keyd/socket"

[history]
keep_days = 30                            # 0 = metrics only, no text stored

[sounds]
enabled = true

[notify]
enabled = true

[pricing]                                 # USD per million tokens, for `gillspeak stats`
input_per_m = 0.10
output_per_m = 0.40

[debug]
save_audio = false                        # writes to ~/.cache/gillspeak/audio/, purged after 7 days
log_level = "INFO"
"""

DEFAULT_DICTIONARY_TOML = """\
# Spoken -> written replacements (case-insensitive, whole phrase, longest match first).
[replace]
"super base" = "Supabase"
"get hub" = "GitHub"
"cube control" = "kubectl"

# Terms passed to ASR hotwords and to the LLM as preferred spellings.
[bias]
terms = ["Supabase", "Fedora", "Parakeet"]
"""


@dataclass
class AudioConfig:
    device: str = "default"
    keep_mic_open: bool = False
    max_seconds: float = 300.0


@dataclass
class VadConfig:
    engine: str = "silero"
    min_speech_s: float = 0.4


@dataclass
class AsrConfig:
    engine: str = "parakeet-tdt-0.6b-v3-int8"
    model_dir: str = "~/.local/share/gillspeak/models"
    num_threads: int = 4
    hotwords: bool = True

    @property
    def model_path(self) -> Path:
        return Path(self.model_dir).expanduser()


@dataclass
class RulesConfig:
    aggressive_fillers: bool = False
    spoken_commands: bool = True
    disabled_commands: list[str] = field(default_factory=list)


@dataclass
class GateConfig:
    min_words: int = 12


@dataclass
class LlmConfig:
    provider: str = "none"  # local only unless the user opts in to cloud clean-up
    model: str = "gemini-3.5-flash-lite"
    timeout_s: float = 2.5
    connect_timeout_s: float = 1.0
    always: bool = False
    instructions: str = ""
    extra_generation_config: str = "{}"
    check_bias_terms: bool = False
    proxy_url: str = ""

    def extra_generation_dict(self) -> dict[str, Any]:
        try:
            value = json.loads(self.extra_generation_config or "{}")
        except json.JSONDecodeError as e:
            raise ConfigError(f"llm.extra_generation_config is not valid JSON: {e}") from e
        if not isinstance(value, dict):
            raise ConfigError("llm.extra_generation_config must be a JSON object")
        return value


@dataclass
class InjectConfig:
    method: str = "paste"
    paste_chord: str = "ctrl+v"
    settle_ms: int = 60
    restore_clipboard: bool = True
    restore_delay_ms: int = 400


@dataclass
class HotkeyConfig:
    hold_to_talk: bool = True
    keyd_socket: str = "/run/gillspeak-keyd/socket"


@dataclass
class HistoryConfig:
    keep_days: int = 30


@dataclass
class SoundsConfig:
    enabled: bool = True


@dataclass
class NotifyConfig:
    enabled: bool = True


@dataclass
class PricingConfig:
    input_per_m: float = 0.10
    output_per_m: float = 0.40


@dataclass
class DebugConfig:
    save_audio: bool = False
    log_level: str = "INFO"


def _default_modes() -> dict[str, str]:
    return {
        "default": "Keep the speaker's tone.",
        "formal": "Rewrite in a professional, polite tone suitable for work email. Keep all facts.",
        "casual": "Keep it short and conversational.",
    }


@dataclass
class Config:
    audio: AudioConfig = field(default_factory=AudioConfig)
    vad: VadConfig = field(default_factory=VadConfig)
    asr: AsrConfig = field(default_factory=AsrConfig)
    rules: RulesConfig = field(default_factory=RulesConfig)
    gate: GateConfig = field(default_factory=GateConfig)
    llm: LlmConfig = field(default_factory=LlmConfig)
    modes: dict[str, str] = field(default_factory=_default_modes)
    inject: InjectConfig = field(default_factory=InjectConfig)
    hotkey: HotkeyConfig = field(default_factory=HotkeyConfig)
    history: HistoryConfig = field(default_factory=HistoryConfig)
    sounds: SoundsConfig = field(default_factory=SoundsConfig)
    notify: NotifyConfig = field(default_factory=NotifyConfig)
    pricing: PricingConfig = field(default_factory=PricingConfig)
    debug: DebugConfig = field(default_factory=DebugConfig)

    def validate(self) -> None:
        if self.llm.provider not in ("gemini", "proxy", "none"):
            raise ConfigError(f"llm.provider must be gemini, proxy or none (got {self.llm.provider!r})")
        if self.llm.provider == "proxy" and not self.llm.proxy_url:
            raise ConfigError("llm.provider = 'proxy' requires llm.proxy_url")
        if self.inject.method not in ("paste", "type"):
            raise ConfigError(f"inject.method must be paste or type (got {self.inject.method!r})")
        if self.inject.paste_chord not in ("ctrl+v", "ctrl+shift+v"):
            raise ConfigError(f"inject.paste_chord must be ctrl+v or ctrl+shift+v (got {self.inject.paste_chord!r})")
        if self.vad.engine not in ("silero", "energy"):
            raise ConfigError(f"vad.engine must be silero or energy (got {self.vad.engine!r})")
        if "default" not in self.modes:
            raise ConfigError("[modes] must define 'default'")
        self.llm.extra_generation_dict()


def _coerce(name: str, value: Any, typ: Any) -> Any:
    if typ is float and isinstance(value, int) and not isinstance(value, bool):
        return float(value)
    origin = getattr(typ, "__origin__", None)
    if origin is list:
        if not isinstance(value, list):
            raise ConfigError(f"{name} must be a list")
        return value
    if origin is dict:
        if not isinstance(value, dict) or not all(isinstance(v, str) for v in value.values()):
            raise ConfigError(f"{name} must be a table of strings")
        return dict(value)
    if typ in (bool, int, float, str) and not (isinstance(value, typ) and (typ is bool or not isinstance(value, bool))):
        raise ConfigError(f"{name} must be {typ.__name__} (got {value!r})")
    return value


def _build(cls: type, data: dict[str, Any], prefix: str) -> Any:
    hints = get_type_hints(cls)
    kwargs: dict[str, Any] = {}
    for key, value in data.items():
        if key not in hints:
            log.warning("unknown config key %s%s (ignored)", prefix, key)
            continue
        typ = hints[key]
        if dataclasses.is_dataclass(typ):
            if not isinstance(value, dict):
                raise ConfigError(f"[{prefix}{key}] must be a table")
            kwargs[key] = _build(typ, value, f"{prefix}{key}.")
        else:
            kwargs[key] = _coerce(f"{prefix}{key}", value, typ)
    return cls(**kwargs)


def from_dict(data: dict[str, Any]) -> Config:
    data = dict(data)
    if "modes" in data:
        data["modes"] = {**_default_modes(), **data["modes"]}
    cfg = _build(Config, data, "")
    cfg.validate()
    return cfg


def ensure_defaults() -> None:
    """Create config.toml and dictionary.toml with defaults if missing."""
    paths.config_dir().mkdir(parents=True, exist_ok=True)
    for path, text in ((paths.config_file(), DEFAULT_CONFIG_TOML), (paths.dictionary_file(), DEFAULT_DICTIONARY_TOML)):
        if not path.exists():
            path.write_text(text, encoding="utf-8")


def load(path: Path | None = None) -> Config:
    if path is None:
        ensure_defaults()
        path = paths.config_file()
    try:
        with open(path, "rb") as f:
            data = tomllib.load(f)
    except FileNotFoundError:
        data = {}
    except tomllib.TOMLDecodeError as e:
        raise ConfigError(f"{path}: {e}") from e
    return from_dict(data)


@dataclass
class Dictionary:
    replace: dict[str, str] = field(default_factory=dict)
    bias: list[str] = field(default_factory=list)


def load_dictionary(path: Path | None = None) -> Dictionary:
    path = path or paths.dictionary_file()
    try:
        with open(path, "rb") as f:
            data = tomllib.load(f)
    except FileNotFoundError:
        return Dictionary()
    except tomllib.TOMLDecodeError as e:
        raise ConfigError(f"{path}: {e}") from e
    replace = data.get("replace", {})
    bias = data.get("bias", {}).get("terms", [])
    if not isinstance(replace, dict) or not all(isinstance(v, str) for v in replace.values()):
        raise ConfigError(f"{path}: [replace] must map strings to strings")
    if not isinstance(bias, list) or not all(isinstance(t, str) for t in bias):
        raise ConfigError(f"{path}: [bias] terms must be a list of strings")
    return Dictionary(replace=dict(replace), bias=list(bias))
