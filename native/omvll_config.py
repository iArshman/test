from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Dict, List, Optional

import omvll

_CONFIG_PATH = Path(__file__).with_name("obfuscation.yml")


def _parse_settings() -> Dict[str, List[str] | Optional[int]]:
    settings: Dict[str, List[str] | Optional[int]] = {
        "seed": None,
        "functions": [],
        "passes": [],
    }
    current: Optional[str] = None
    if not _CONFIG_PATH.exists():
        return settings

    for raw_line in _CONFIG_PATH.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("seed:"):
            value = line.split(":", 1)[1].strip()
            try:
                settings["seed"] = int(value, 0)
            except ValueError:
                settings["seed"] = None
            continue
        if line.startswith("functions"):
            current = "functions"
            continue
        if line.startswith("passes"):
            current = "passes"
            continue
        if line.startswith("-") and current in ("functions", "passes"):
            entry = line[1:].strip().strip('"').strip("'")
            if entry:
                settings[current].append(entry)
    return settings


class PurrfectConfig(omvll.ObfuscationConfig):
    def __init__(self, settings: Dict[str, List[str] | Optional[int]]):
        super().__init__()
        self._targets = set(settings.get("functions") or [])
        self._passes = set(settings.get("passes") or [])

    def _should_obfuscate(self, func: omvll.Function) -> bool:
        if not self._targets:
            return True
        return func.demangled_name in self._targets or func.name in self._targets

    def _pass_enabled(self, name: str) -> bool:
        return not self._passes or name in self._passes

    def obfuscate_arithmetic(self, mod: omvll.Module, func: omvll.Function) -> bool:
        return self._should_obfuscate(func) and self._pass_enabled("InstructionsSubstitution")

    def flatten_cfg(self, mod: omvll.Module, func: omvll.Function) -> bool:
        return self._should_obfuscate(func) and self._pass_enabled("ControlFlowFlattening")

    def bogus_control_flow(self, mod: omvll.Module, func: omvll.Function) -> bool:
        return self._should_obfuscate(func) and self._pass_enabled("BogusControlFlow")

    def obfuscate_string(
        self,
        mod: omvll.Module,
        func: omvll.Function,
        string: bytes,
    ):
        if self._should_obfuscate(func) and self._pass_enabled("StringsEncryption"):
            return omvll.StringEncOptGlobal()
        return False


@lru_cache(maxsize=1)
def omvll_get_config() -> omvll.ObfuscationConfig:
    """Return an instance of the obfuscation configuration used by O-MVLL."""
    return PurrfectConfig(_parse_settings())
