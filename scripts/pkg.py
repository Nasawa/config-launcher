"""Single source of truth for the Android applicationId (#242)."""
from pathlib import Path
_ENV = Path(__file__).with_name("pkg.env")
PKG = next(line.split("=", 1)[1].strip() for line in _ENV.read_text().splitlines() if line.startswith("PKG="))
PKG_TAIL = PKG.rsplit(".", 1)[-1]
NS = next(line.split("=", 1)[1].strip() for line in _ENV.read_text().splitlines() if line.startswith("NS="))
