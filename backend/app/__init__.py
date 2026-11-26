"""ProDL API server package."""

from __future__ import annotations

import sys
from pathlib import Path

__version__ = "2.1.0"

# Convenience for source checkouts: make ``packages/prodl-core`` importable
# without a separate install step (Docker images install it properly).
_CORE_SRC = Path(__file__).resolve().parents[2] / "packages" / "prodl-core"
if _CORE_SRC.is_dir() and str(_CORE_SRC) not in sys.path:
    sys.path.insert(0, str(_CORE_SRC))
