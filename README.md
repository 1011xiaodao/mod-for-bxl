# [殖民地经营] Colony Sim（pioneer_colony）

「星球殖民」主题 Minecraft 整合包（四部曲·第一部）自创模组系列第 6 号项目：**策略经营式殖民地模拟模组**（取代 MineColonies）。

- 基线：**MC 1.21.1 + NeoForge 21.1.x + Java 21**（Gradle 8.10 / ModDevGradle 2.0.146）
- 核心设计：基地运营 = 纯数据公式运算（60s 经济 tick + 离线补算，零常驻实体 AI）；领地购买与保护；建筑多方块一次成形；研究通道（对接 [研究台]）；袭击系统（威胁值/方向预警/市民参战）
- 里程碑：`M6.1` 数据模型+经济 → `M6.2` 领地+建筑 → `M6.3` 研究分院 → `M6.4` 袭击系统（进行中，详见 `回报-M6.x-*.md`）

## 构建

```bash
python tools/neoforged_maven_proxy.py   # 开发机网络适配：先启动 NeoForge maven 本地反代
./gradlew build                         # 产物 build/libs/pioneer_colony-0.6.1.jar
```

- 开发机网络说明（Java TLS 直连 maven.neoforged.net 被重置的规避方案）：`tools/README.md`
- 数值全部参数化：建筑/袭击/市场走 datapack JSON，全局参数走 config——调值见 `docs/数值速调手册.md`
- 服务端冒烟/验收工具：`tools/rcon.py`

## 文档

| 文档 | 内容 |
|---|---|
| `docs/06-殖民地经营.md` | 开发主文档（自包含：架构/系统设计/里程碑） |
| `docs/01-项目指导与规范.md` | 硬约束/历轮指令/回报规范 |
| `docs/数值速调手册.md` | 数值速调指南（改 JSON → /reload 生效） |
| `回报-M6.x-*.md` | 各里程碑交付回报（6 章节固定结构） |

## 许可

All Rights Reserved（整合包配套自研模组，未获授权请勿分发）。
