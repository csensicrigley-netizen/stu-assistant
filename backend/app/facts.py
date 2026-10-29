"""每天 5 条冷知识：搞笑、有趣、又确实鲜为人知。

设计取舍

* **本地词库兜底**：不管模型接口通不通，用户每天都能看到 5 条。宁可少点花活，
  不能开天窗。词库按日期轮转，同一天固定、第二天换一批。
* **模型增强**：环境里有 GLM 密钥时，让模型每天现写 5 条（要求：真实、具体、
  冷门、有一点好笑），写成功就用它；429/超时/格式不对就静默回退词库。
* 一天只生成一次，结果落 DB（kind="fact"，source="facts"，external_id
  带日期），第二天自然换新的一批，历史仍留在库里。
"""
from __future__ import annotations

import datetime
import hashlib
import json
import logging
import os
import random
from pathlib import Path

import httpx

log = logging.getLogger("stu.facts")

COUNT = 5

# ---- 本地词库 --------------------------------------------------------------
# 30 条一轮，按日期哈希取 5 条；同一天稳定，隔天不同。
_SEED: tuple[str, ...] = (
    "蜂蜜永远不会变质：考古学家在埃及古墓里挖出过三千年前的蜂蜜，仍然能吃。",
    "袋熊拉的是立方体便便，因为肠道末端的弹性不一样，能把便便压成六个面。",
    "章鱼有三颗心脏、蓝色的血，而且断掉的触手还能自己找东西吃。",
    "香蕉有轻微的放射性，因为它富含钾-40；坐一次飞机的辐射量大约等于吃几十根香蕉。",
    "你的胃酸能溶解刀片这类金属的薄边，胃每三天左右就把自己的内壁换一遍，所以不会把自己消化掉。",
    "长颈鹿的舌头是紫黑色的，长约 45 厘米，主要用来卷树叶，也用来掏自己的耳朵。",
    "海獭睡觉时会手拉手，怕被水流冲散；有的还会把海藻缠在身上当安全带。",
    "龙虾的牙齿长在胃里，它的“牙”是一组叫胃磨的硬结构。",
    "企鹅求婚送的是石头：挑一块最圆最光滑的，放到心仪对象脚边。",
    "你在打喷嚏时心脏会停跳一拍的说法是错的，只是胸腔压力变化让血流节奏变了。",
    "人的鼻子能记住大约一万亿种气味，比眼睛能分辨的颜色种类多得多。",
    "猫对着你慢慢眨眼，是在表达信任；这在猫的语言里接近人类的微笑。",
    "雨滴不是泪滴形，小于 2 毫米的接近球形，大的会被气流压成汉堡包形状。",
    "南极洲有一块叫干谷的地方，两百万年没下过雨，是地球上最像火星的地方。",
    "蜂蜜的 pH 值约 3.9，加上低含水量，细菌在里面活不下来，这是它不坏的原因。",
    "彩虹其实是完整的圆，只是地面挡住了下半圈；在飞机上有时能看到整个圆。",
    "考拉的指纹和人类的几乎无法用肉眼区分，连法医都曾被它骗过。",
    "蜂鸟是唯一能倒着飞的鸟，而且心跳每分钟可达一千次以上。",
    "你的骨头比钢筋还抗压：同等重量下，骨头的强度大约是混凝土的四倍。",
    "一颗方糖大小的一茶匙中子星物质，重量大约是十亿吨。",
    "金鱼的记忆只有七秒是彻底的谣言，它们能记住几个月前学会的东西。",
    "打哈欠会传染，连狗看人打哈欠都会跟着打；越亲近的人之间越容易互相传染。",
    "蜗牛有上万颗牙，长在一条叫齿舌的“舌头”上，像锉刀一样磨食物。",
    "树懒一周只下树一次上厕所，而且那一次能排掉体重三分之一的排泄物。",
    "微波炉加热不均是因为驻波，转盘转动就是为了躲开那些冷点和热点。",
    "吉尼斯世界纪录里最长的科学实验是“沥青滴落实验”，从 1927 年跑到今天只滴了几滴。",
    "北极熊的皮肤是黑色的，毛是透明中空的；看起来白是因为光的散射。",
    "切洋葱流泪不是因为味道，而是它释放的含硫气体碰到眼中的水变成了稀硫酸。",
    "海马是爸爸怀孕：雌海马把卵交给雄海马，由雄海马负责孵化。",
    "一个成年人体内的细菌数量大约和人体细胞数量相当，重量约一两公斤。",
    "打嗝停不下来时，医生会用一个叫“直肠指压按摩”的办法，比憋气有效。",
    "蝴蝶用脚尝味道，落在叶子上先“踩一踩”才知道能不能吃。",
    "牡蛎会一天之内改变性别，一年能换好几次。",
    "你的小肠绒毛摊开来，总面积约 30 平方米，比一间单人宿舍还大。",
    "人在太空会长高 3-5 厘米，因为椎间盘不用再被压扁，回地球后又会缩回去。",
    "一滴海水里有上千万个病毒，全球海洋病毒总量比银河系的星星还多几个数量级。",
    "树懒的毛里长着专属的绿藻和蛾子，一身的生态系统比它的动作还热闹。",
    "骆驼的驼峰里装的是脂肪不是水，瘦下来的驼峰会软软地倒向一边。",
    "水獭会收集自己最喜欢的石头，夹在腋下随身带着用一辈子。",
    "蟑螂断头还能活一周，最后是饿死的——它没有血压，也不靠头呼吸。",
    "一只南极磷虾的总生物量，可能超过全人类加起来的总重量。",
    "打火机比火柴更早被发明出来，顺序和我们以为的相反。",
    "你在梦里读不了书：负责阅读的脑区在快速眼动睡眠里基本停工。",
    "出生时你有大约 300 块骨头，成年后只剩 206 块，其余都长在一起了。",
    "云的重量很实在：一朵普通的积云约 500 吨，相当于一百头大象飘在天上。",
    "海星没有大脑，靠遍布全身的神经网活动；有的被切开还能长出完整个体。",
    "同一片雪花下落时，两片完全相同的晶体在自然条件下几乎不存在。",
    "咖啡的苦味里有一部分来自绿原酸分解，但“越苦越提神”纯属错觉。",
    "你的左肺比右肺小，因为要给心脏腾出一块地方。",
    "睡满八小时不一定刚好：基因决定有人 6 小时就够，有人 9 小时还困。",
    "马不能呕吐，所以它的肠胃一堵就是急症。",
    "蚂蚁不睡觉但会打盹：一天几百次几秒的微型睡眠，加起来约四小时。",
    "过山车最刺激的那一秒其实是下坡开始，不是最高点——失重感来自加速度突变。",
    "有的鸟类一年只落地一次，雨燕可以在空中睡觉、交配、吃喝。",
    "指甲长得比脚趾甲快约四倍，中指指甲是十指里长得最快的那个。",
    "人类是唯一会因为情绪而流泪的动物，其他动物的眼泪只负责润滑和冲洗。",
    "地球上的黄金大部分来自中子星碰撞，你手上戒指的年龄比太阳还老。",
    "橡皮擦能擦掉铅笔字，是因为它把石墨黏走了，不是磨掉的。",
    "把耳机线装兜里就必打结，这不是倒霉，是绳子越长越容易自发形成复杂结点。",
)


def _today() -> str:
    return datetime.date.today().isoformat()


def _pick_local(day: str) -> list[str]:
    seed = int(hashlib.sha256(day.encode()).hexdigest()[:8], 16)
    rng = random.Random(seed)
    pool = list(_SEED)
    rng.shuffle(pool)
    return pool[:COUNT]


def _glm_key() -> str:
    for name in ("STU_FACT_GLM_KEY", "GLM_API_KEY"):
        value = os.environ.get(name, "").strip()
        if value:
            return value
    for path in ("/etc/glm_key", str(Path.home() / ".glm_key")):
        try:
            text = Path(path).read_text(encoding="utf-8").strip()
            if text:
                return text
        except OSError:
            continue
    return ""


def _generate(day: str) -> list[str]:
    """让模型现写 5 条。任何一步不对就返回空列表，交给词库。"""
    key = _glm_key()
    if not key:
        return []
    prompt = (
        "写 5 条冷知识，每条一行，不要编号，不要解释。要求：\n"
        "1) 必须真实准确，有具体数字或细节；\n"
        "2) 鲜为人知，不要网络烂梗；\n"
        "3) 中文口语化，30-70 字，读起来有一点好笑或让人“啊居然这样”；\n"
        f"4) 不要和 {day} 之前常见的科普重复。"
    )
    for attempt in range(3):
        try:
            response = httpx.post(
                "https://open.bigmodel.cn/api/paas/v4/chat/completions",
                headers={"Authorization": f"Bearer {key}"},
                json={
                    "model": "GLM-4.7-Flash",
                    "messages": [{"role": "user", "content": prompt}],
                    "temperature": 0.9,
                    "max_tokens": 700,
                },
                timeout=25,
            )
            if response.status_code == 429:
                continue
            if response.status_code != 200:
                log.info("facts 生成跳过：HTTP %s", response.status_code)
                return []
            content = response.json()["choices"][0]["message"]["content"]
            lines = []
            for raw in content.splitlines():
                line = raw.strip().lstrip("-·•0123456789.、) ").strip()
                if len(line) >= 12:
                    lines.append(line)
            return lines[:COUNT] if len(lines) >= COUNT else []
        except Exception as exc:  # noqa: BLE001 - 生成只是锦上添花
            log.info("facts 生成失败：%s", type(exc).__name__)
            return []
    return []


def daily(uid: str, count: int = COUNT) -> list[dict]:
    """今天这 5 条。第一次会生成并入库，之后直接读。"""
    from .config import settings
    from .db import DB

    db = DB(settings.db_path)
    day = _today()
    rows = [r for r in db.items_of(uid, "fact", source="facts", limit=60)
            if (r.get("payload") or {}).get("day") == day]
    rows.sort(key=lambda r: (r.get("payload") or {}).get("index", 0))
    if len(rows) >= count:
        return [r["payload"] for r in rows[:count]]

    texts = _generate(day) or _pick_local(day)
    source = "glm" if texts and texts != _pick_local(day) else "local"
    records = [
        {
            "id": f"{day}#{i + 1}",
            "external_id": f"{day}#{i + 1}",
            "title": text,
            "text": text,
            "day": day,
            "index": i + 1,
            "from": source,
        }
        for i, text in enumerate(texts[:count])
    ]
    db.upsert_items(uid, "facts", "fact", records)
    log.info("facts %s 生成 %d 条（%s）", uid[:6], len(records), source)
    return records


def payload_json(rows: list[dict]) -> dict:
    return {"day": _today(), "items": rows, "count": len(rows)}
