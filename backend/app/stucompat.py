"""进程启动时安装的两处 stu-mcp 0.7.3 解析器修补。

两处都是"学校页面变了、上游判据太窄"，不是安全策略问题：

1. `jw` 课表：一门课跨两个大节（页面写 `[06-07-08-09节]` 且在两大节各印一份）时，
   上游按单行校验 + 拒重复，整份课表作废。见 `app/jw_timetable.py`。
2. `mystu` 课程清单：同一门课的不同学年/学期会以相同 id 出现，上游按 id 判重，
   登录直接 `schema_changed`，于是作业永远读不到。见 `app/mystu_parser.py`。

升级 stu-mcp 时请逐个对照上游源码再重打这两个补丁；补丁失败只会退回上游行为，
不会让服务起不来。
"""
from __future__ import annotations

import logging

log = logging.getLogger("stu_app.compat")

_installed = False


def install() -> None:
    global _installed
    if _installed:
        return
    _installed = True

    # ---- jw 课表 ----
    try:
        from stu_mcp.features.jw import adapter as jw_adapter

        from . import jw_timetable

        jw_adapter.parse_timetable = jw_timetable.parse_timetable
        from stu_mcp.features.jw import service as jw_service

        if hasattr(jw_service, "parse_timetable"):
            jw_service.parse_timetable = jw_timetable.parse_timetable
    except Exception as exc:  # noqa: BLE001 - 上游改动时安静退回
        log.warning("jw 课表补丁未生效：%s", exc)

    # ---- mystu 课程清单 ----
    # 注意：login_http → cas.acquire_state 里用的是 **它自己模块里绑定的**
    # parse_inventory，所以要逐个模块覆盖，光改 parser 模块不够。
    try:
        from stu_mcp.features.mystu import parser as mystu_parser

        from . import mystu_parser as patched

        mystu_parser.parse_inventory = patched.parse_inventory
        # cas.py 在导入时就把 parse_inventory 绑到自己的命名空间了，必须单独改
        from stu_mcp.auth_http import cas as mystu_cas

        if hasattr(mystu_cas, "parse_inventory"):
            mystu_cas.parse_inventory = patched.parse_inventory
        from stu_mcp.features.mystu import adapter as mystu_adapter

        if hasattr(mystu_adapter, "parse_inventory"):
            mystu_adapter.parse_inventory = patched.parse_inventory
    except Exception as exc:  # noqa: BLE001
        log.warning("mystu 清单补丁未生效：%s", exc)
