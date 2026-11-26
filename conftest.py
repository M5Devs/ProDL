"""Make the monorepo importable when running pytest from a fresh checkout."""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent

for path in (ROOT / "backend", ROOT / "packages" / "prodl-core"):
    if path.is_dir() and str(path) not in sys.path:
        sys.path.insert(0, str(path))
