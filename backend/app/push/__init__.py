"""推送命名空间：实时活动适配器（Huawei / OPPO）+ 旧通知模块的兼容层。

`app/push.py` 是旧的通知模块，`sync.py` 里写的是 `from .push import push_many`。
本目录成为同名包之后，`app/push.py` 不能再按 `app.push` 导入，所以这里按文件路径把它
加载成 `app._push_legacy`，对外仍然导出 `push_many`，旧调用点一行都不用改。
"""
from __future__ import annotations

import importlib.util as _ilu
import sys as _sys
from pathlib import Path as _Path

from .models import LiveUpdate

__all__ = ["LiveUpdate", "push_many"]

_LEGACY_NAME = "app._push_legacy"
_LEGACY_PATH = _Path(__file__).resolve().parent.parent / "push.py"


def _legacy():
    """惰性加载旧 push.py（它自己会 import config / db，别在包初始化时拉起来）。"""
    mod = _sys.modules.get(_LEGACY_NAME)
    if mod is not None:
        return mod
    spec = _ilu.spec_from_file_location(_LEGACY_NAME, _LEGACY_PATH)
    if spec is None or spec.loader is None:
        raise ImportError("app/push.py 缺失，旧通知链路不可用")
    mod = _ilu.module_from_spec(spec)
    _sys.modules[_LEGACY_NAME] = mod
    spec.loader.exec_module(mod)
    return mod


def push_many(*args, **kwargs):
    """兼容 `from .push import push_many`（见 app/sync.py）。"""
    return _legacy().push_many(*args, **kwargs)
