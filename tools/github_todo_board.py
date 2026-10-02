#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""GitHub Project 看板一键搭建（Projects v2：To do / In progress / Done）。

本机直连 api.github.com 不通，故做成脚本：在能访问 api.github.com 的网络下运行
（开代理后先设环境变量 HTTPS_PROXY）。令牌取环境变量 GITHUB_TOKEN，或 git 已保存凭据。

用法：
    python tools/github_todo_board.py setup      # 建「殖民地经营 TODO」看板 + 7 张 issue 卡
    python tools/github_todo_board.py doing 8    # 8 号 issue 卡片移到 In progress
    python tools/github_todo_board.py done 8     # 关闭 8 号 issue，卡片移到 Done
"""
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

REPO = "1011xiaodao/mod-for-bxl"
OWNER_LOGIN = "1011xiaodao"
API = "https://api.github.com"
PROJECT_TITLE = "殖民地经营 TODO"
STATUS_NAMES = [("To do", "BLUE"), ("In progress", "YELLOW"), ("Done", "GREEN")]

LABELS = {
    "milestone": "1e76ff",
    "deferred": "d93f0b",
    "待项目主决策": "7a5c9e",
}

BASELINE = "> 基线：M6.1~M6.4 已完成（tag M6.1~M6.4，详见各回报文件）。\n"

ISSUES = [
    ("M6.5 市民日程模拟", "milestone", BASELINE + """
- [ ] 常驻市民实体（原版村民机制、跟随模拟距离）
- [ ] 家→工坊→食堂→家通勤
- [ ] 就餐消耗联动
- [ ] 验收：32+ 市民寻路帧耗报告
"""),
    ("M6.6 食物生产链 + 幸福度 + 四季供暖", "milestone", BASELINE + """
- [ ] 食堂烹饪配方（cooking/*.json）
- [ ] 食物点体系定稿
- [ ] 幸福度公式定稿
- [ ] 热能 Gauge（锅炉房 + 余热回收）
- [ ] sereneseasons 软依赖（装/不装均中性）
"""),
    ("M6.7 经济与商贸", "milestone", BASELINE + """
- [ ] 星联市场：挂单撮合 / 部分成交 / 市价单 / 动态价格 / 手续费
- [ ] 贸易站 GUI
- [ ] 商队表现层（价目表已随 M6.4 就位）
"""),
    ("M6.8 城市间贸易协定", "milestone", BASELINE + """
- [ ] 签约 / 7 天离线确认 / 解约 3 日通知期
- [ ] 路线加成 + 城际商队
- [ ] 防滥用三重兜底
"""),
    ("M6.9 扩展资源框架 + 收尾", "milestone", BASELINE + """
- [ ] Gauge 通用行为（热能为首个内置用例）
- [ ] ColonyApi 正式注册
- [ ] 巨构拓展接口核对（hasBuilt / workerFillRate）
- [ ] 10 玩家×20 建筑公开服压测 + 存档体积实测
"""),
    ("DEFERRED：整合包统一验收阶段", "deferred", BASELINE + """
- [ ] 真实玩家联测：领地保护六用例（破坏/放置/点燃/爆炸/活塞/开 GUI）、GUI 全页签、研究页渲染、袭击战斗调优、建筑地基物品放置路径
- [ ] [研究台] M8.3 交付后：真实 ResearchApi 适配器 + M8.4 双通道同账本联调
- [ ] 供暖因子激活（接入 sereneseasons 后幸福度供暖项从 0.5 中性切真值）
"""),
    ("待项目主决策 / 供给", "待项目主决策", BASELINE + """
- [ ] 数值定稿：建筑 10 栋全字段、研究树 4 项、袭击波次表 5 档、威胁值系数、食物点/市民日耗
- [ ] 契约缺口转研究台侧：研究「发起/取消」最小 API（M8.4 联调前置）
- [ ] 巨构拓展三巨构建筑 JSON 与 ColonyApi 桩（原开发机实体件）
- [ ] 美术资产：GUI 底图、建筑正式蓝图（结构方块）、市民正式皮肤/名字池、商队造型
- [ ] 解散退款去向、战利品形式（入仓库 vs 掉落）、地基物品合成配方等回报 §5 事项
"""),
]


def get_token():
    tok = os.environ.get("GITHUB_TOKEN", "").strip()
    if tok:
        return tok
    try:
        p = subprocess.run(
            ["git", "credential", "fill"],
            input="protocol=https\nhost=github.com\n\n",
            capture_output=True, text=True, timeout=15,
        )
        for line in p.stdout.splitlines():
            if line.startswith("password="):
                return line.split("=", 1)[1].strip()
    except Exception:
        pass
    sys.exit("未找到 GitHub 凭据：请设环境变量 GITHUB_TOKEN（需 repo + project 权限）")


def _die(method, path, code, detail):
    hint = ""
    if code in (401, 403, 404):
        hint = "\n提示：401/403 多为令牌权限不足（需要 repo + project）；若连不上则是本网络仍未放行 api.github.com。"
    sys.exit(f"GitHub API {method} {path} 失败：HTTP {code}\n{detail[:400]}{hint}")


def rest(tok, method, path, body=None):
    req = urllib.request.Request(API + path, method=method)
    req.add_header("Authorization", f"token {tok}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "pioneer-colony-todo-board")
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        req.add_header("Content-Type", "application/json; charset=utf-8")
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as r:
            raw = r.read().decode("utf-8")
            return json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as e:
        _die(method, path, e.code, e.read().decode("utf-8", "replace"))


def gql(tok, query, variables=None):
    body = json.dumps({"query": query, "variables": variables or {}}).encode("utf-8")
    req = urllib.request.Request(API + "/graphql", data=body, method="POST")
    req.add_header("Authorization", "bearer " + tok)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    req.add_header("User-Agent", "pioneer-colony-todo-board")
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as r:
            data = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        _die("POST", "/graphql", e.code, e.read().decode("utf-8", "replace"))
    if data.get("errors"):
        sys.exit("GraphQL 返回错误：\n" + json.dumps(data["errors"], ensure_ascii=False, indent=2))
    return data["data"]


def norm(name):
    return name.lower().replace(" ", "").replace("_", "")


def find_project_and_status(tok):
    d = gql(tok, 'query($l:String!){ user(login:$l){ id '
                  'projectV2s(first:20, includePublic:false){ nodes{ id title '
                  'fields(first:20){ nodes{ ... on ProjectV2SingleSelectField '
                  '{ id name options{ id name } } } } } } } }', {"l": OWNER_LOGIN})
    user = d["user"]
    proj = next((p for p in user["projectV2s"]["nodes"] if p["title"] == PROJECT_TITLE), None)
    if proj is None:
        d2 = gql(tok, 'mutation($o:ID!,$t:String!){ createProjectV2(input:{ownerId:$o,title:$t})'
                      '{ projectV2{ id } } }', {"o": user["id"], "t": PROJECT_TITLE})
        proj_id = d2["createProjectV2"]["projectV2"]["id"]
        print(f"  + 已创建项目「{PROJECT_TITLE}」")
        status = None
    else:
        proj_id = proj["id"]
        status = next((f for f in proj["fields"]["nodes"] if f and f.get("name") == "Status"), None)
        print(f"  = 项目「{PROJECT_TITLE}」已存在")
    if status is None:
        opts = [{"name": n, "color": c} for n, c in STATUS_NAMES]
        d3 = gql(tok, 'mutation($p:ID!,$o:[ProjectV2SingleSelectOptionInput!]){ '
                      'createProjectV2Field(input:{projectId:$p, dataType:SINGLE_SELECT, '
                      'name:"Status", singleSelectOptions:$o}){ projectV2Field{ '
                      '... on ProjectV2SingleSelectField{ id options{ id name } } } } }',
                 {"p": proj_id, "o": opts})
        status = d3["createProjectV2Field"]["projectV2Field"]
        print("  + 已创建 Status 字段")
    else:
        # 缺哪个列名就补哪个（update 会整体替换，需传全量）
        have = {norm(o["name"]) for o in status["options"]}
        missing = [n for n, _ in STATUS_NAMES if norm(n) not in have]
        if missing:
            opts = [{"name": o["name"]} for o in status["options"]]
            opts += [{"name": n, "color": c} for n, c in STATUS_NAMES if norm(n) not in have]
            d4 = gql(tok, 'mutation($f:ID!,$o:[ProjectV2SingleSelectOptionInput!]){ '
                          'updateProjectV2Field(input:{fieldId:$f, singleSelectOptions:$o}){ '
                          'projectV2Field{ ... on ProjectV2SingleSelectField{ id options{ id name } } } } }',
                     {"f": status["id"], "o": opts})
            status = d4["updateProjectV2Field"]["projectV2Field"]
            print(f"  + Status 字段补齐列：{'、'.join(missing)}")
    opt_by_key = {norm(o["name"]): o["id"] for o in status["options"]}
    return proj_id, status["id"], opt_by_key


def list_items(tok, proj_id):
    d = gql(tok, 'query($id:ID!){ node(id:$id){ ... on ProjectV2 { '
                  'items(first:100){ nodes{ id content{ __typename ... on Issue{ number } } } } } } }',
            {"id": proj_id})
    return {n["content"].get("number"): n["id"]
            for n in d["node"]["items"]["nodes"]
            if n["content"].get("__typename") == "Issue"}


def set_status(tok, proj_id, field_id, opt_by_key, item_id, name):
    key = norm(name)
    if key not in opt_by_key:
        sys.exit(f"Status 列「{name}」不存在，现有：{list(opt_by_key)}")
    gql(tok, 'mutation($p:ID!,$i:ID!,$f:ID!,$o:ID!){ updateProjectV2ItemFieldValue('
             'input:{projectId:$p, itemId:$i, fieldId:$f, value:{singleSelectOptionId:$o}})'
             '{ projectV2Item{ id } } }',
        {"p": proj_id, "i": item_id, "f": field_id, "o": opt_by_key[key]})


def cmd_setup(tok):
    have = {l["name"] for l in rest(tok, "GET", f"/repos/{REPO}/labels?per_page=100")}
    for name, color in LABELS.items():
        if name not in have:
            rest(tok, "POST", f"/repos/{REPO}/labels", {"name": name, "color": color})
            print(f"  + 标签 {name}")
    by_title = {i["title"]: i for i in rest(tok, "GET", f"/repos/{REPO}/issues?state=all&per_page=100")
                if "pull_request" not in i}
    cards = []
    for title, label, body in ISSUES:
        if title in by_title:
            it = by_title[title]
            print(f"  = issue #{it['number']} 已存在：{title}")
        else:
            it = rest(tok, "POST", f"/repos/{REPO}/issues",
                      {"title": title, "body": body, "labels": [label]})
            print(f"  + issue #{it['number']} 已创建：{title}")
        cards.append({"number": it["number"], "node_id": it["node_id"], "title": title})
    proj_id, field_id, opt_by_key = find_project_and_status(tok)
    items = list_items(tok, proj_id)
    for c in cards:
        if c["number"] in items:
            print(f"  = 卡片已在看板：#{c['number']} {c['title']}")
            item_id = items[c["number"]]
        else:
            d = gql(tok, 'mutation($p:ID!,$c:ID!){ addProjectV2ItemById('
                         'input:{projectId:$p, contentId:$c}){ item{ id } } }',
                    {"p": proj_id, "c": c["node_id"]})
            item_id = d["addProjectV2ItemById"]["item"]["id"]
            print(f"  + 卡片已上板：#{c['number']} {c['title']}")
        set_status(tok, proj_id, field_id, opt_by_key, item_id, "To do")
    print(f"\n完成：打开 https://github.com/users/{OWNER_LOGIN}/projects → 「{PROJECT_TITLE}」"
          f"→ 右上视图切换为 Board 即是三列看板。")


def _locate(tok):
    proj_id, field_id, opt_by_key = find_project_and_status(tok)
    items = list_items(tok, proj_id)
    return proj_id, field_id, opt_by_key, items


def cmd_move(tok, number, column, close=False):
    proj_id, field_id, opt_by_key, items = _locate(tok)
    if number not in items:
        sys.exit(f"看板上没有 #{number} 的卡片（先跑 setup，或确认编号）")
    set_status(tok, proj_id, field_id, opt_by_key, items[number], column)
    if close:
        rest(tok, "PATCH", f"/repos/{REPO}/issues/{number}", {"state": "closed"})
        print(f"  #{number} 已关闭，卡片移到「{column}」")
    else:
        print(f"  #{number} 卡片移到「{column}」")


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in ("setup", "doing", "done"):
        print(__doc__)
        sys.exit(1)
    tok = get_token()
    cmd = sys.argv[1]
    if cmd == "setup":
        cmd_setup(tok)
    else:
        if len(sys.argv) < 3 or not sys.argv[2].isdigit():
            sys.exit(f"用法：python {sys.argv[0]} {cmd} <issue编号>")
        cmd_move(tok, int(sys.argv[2]), "In progress" if cmd == "doing" else "Done",
                 close=(cmd == "done"))


if __name__ == "__main__":
    main()
