# [殖民地经营] Colony Sim（pioneer_colony）

「星球殖民」主题 Minecraft 整合包（四部曲·第一部）自创模组系列第 6 号项目：**策略经营式殖民地模拟模组**（取代 MineColonies）。

- 基线：**MC 1.21.1 + NeoForge 21.1.x + Java 25**（Gradle 9.1 / ModDevGradle 2.0.146）
- 核心设计：基地运营以纯数据公式为主（60s 经济 tick + 离线补算）；领地保护、建筑施工、市民日程、袭击、食堂烹饪、四季供暖、星联市场、城市间贸易协定与通用 Gauge/API。
- 技术分工：Java 25 + NeoForge 管理 Minecraft 生命周期/实体/GUI/存档；Rust 通过稳定版 FFM 承担纯数值公式；市场/协定数据由服务端 SavedData 持久化。
- 当前代码路线：M6.1～M6.5 基础已存在；M6.6～M6.9 核心代码已接入，真实整合包联测与 10×20 公开服压测仍待验收，详见 `docs/M6.6-M6.9系统验收手册.md`。

## 构建

```bash
python tools/neoforged_maven_proxy.py   # 开发机网络适配：先启动 NeoForge maven 本地反代
./gradlew build                         # 产物 build/libs/pioneer_colony-0.6.1.jar
```

- 开发机网络说明（Java TLS 直连 maven.neoforged.net 被重置的规避方案）：`tools/README.md`
- 数值全部参数化：建筑/袭击/市场走 datapack JSON，全局参数走 config——调值见 `docs/数值速调手册.md`
- 服务端冒烟/验收工具：`tools/rcon.py`
- Rust 核心与 Java 25 FFM 桥接开发说明：`native/README.md`（`colony-core` 写纯 Rust 业务计算，`colony-ffi` 负责 C ABI，Java 业务代码优先使用 `RustNativeClient` 与领域适配器）

## 文档

| 文档 | 内容 |
|---|---|
| `docs/06-殖民地经营.md` | 开发主文档（自包含：架构/系统设计/里程碑） |
| `docs/01-项目指导与规范.md` | 硬约束/历轮指令/回报规范 |
| `docs/数值速调手册.md` | 数值速调指南（改 JSON → /reload 生效） |
| `docs/M6.6-M6.9系统验收手册.md` | 食物/供暖、市场、城际协定、Gauge/API 的命令与验收步骤 |
| `回报-M6.x-*.md` | 各里程碑交付回报（6 章节固定结构） |

## 许可

All Rights Reserved（整合包配套自研模组，未获授权请勿分发）。
