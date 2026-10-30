"""Make app imports independent of whether pytest is run at repo or backend root."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
