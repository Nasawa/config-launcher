"""Single source of truth for applicationId (PKG) and Kotlin namespace (NS) (#242 / #243).

Component class names live under NS, not PKG. Always use "$PKG/$NS.MainActivity" /
"$PKG/$NS.ReloadReceiver" (or broadcast -a "$PKG.RELOAD" -p "$PKG"). A slash-dot
after the applicationId expands to a class that does not exist.
"""
from pathlib import Path
_ENV = Path(__file__).with_name("pkg.env")
_lines = _ENV.read_text().splitlines()
PKG = next(line.split("=", 1)[1].strip() for line in _lines if line.startswith("PKG="))
NS = next(line.split("=", 1)[1].strip() for line in _lines if line.startswith("NS="))
PKG_TAIL = PKG.rsplit(".", 1)[-1]
