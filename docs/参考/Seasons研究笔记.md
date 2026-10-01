# 四季模组研究笔记（Serene Seasons + 兼容桥）

> 调查日期 2026-09-05。来源：项目主提供的两个 jar 拆解（1.21.1 NeoForge，与本整合包基线完全匹配）。
> 用途：项目 12（四季联动）立项的技术依据；殖民地（06 §4.0/幸福度）、巨构拓展（太阳能季节加成）的接入锚点。

## 1. 选型结论

| 项 | 结论 |
|---|---|
| 四季本体 | **Serene Seasons 10.1.0.3**（modId `sereneseasons`，作者 Adubbz/Forstride） |
| 授权 | **All Rights Reserved**——原样进整合包使用没有问题，但**禁止魔改分发**；发布前核对 CurseForge 整合包政策 |
| 额外前置 | **glitchcore ≥2.1.0.0**（Glitchfiend 共享库，必装） |
| 兼容桥 | **Ecliptic Seasons Serene API Bridge**（patch11，MIT）——保险丝：若将来换用 Ecliptic Seasons（节气四季）模组，装桥即可让依赖 SS API 的模组继续工作 |
| 桥的作者 | teamtea（Ecliptic Seasons 作者）；桥内已 shade SS 的 API 类 |

## 2. 编程接入面（SS 官方 API，稳定层）

| API | 用途 |
|---|---|
| `SeasonHelper` + `ISeasonState` | 查询当前季节/子季节/季节内天数/周期（任意时刻可查） |
| `Season` + `Season$SubSeason` | 四季 × 早中晚 = **12 个子季节状态** |
| `Season$TropicalSeason` | 热带生物群系的雨季/旱季（热带=无供暖需求的判定依据） |
| `SeasonChangedEvent$Standard` | **换季事件**——供暖需求曲线切换、幸福度供暖因子更新、巨构太阳能加成调整都监听它 |
| `SSGameRules` | 季节相关游戏规则 |

**接入方式（本项目约定）**：监听 `SeasonChangedEvent.Standard` 驱动状态切换 + 按需查询 `ISeasonState`；供暖需求曲线按 12 子季节插值（冬季 late 最冷峰值）。

## 3. 配置与数据面

- `SeasonsConfig`：季节周期/起始季节等（数值以运行时生成的 config 为准，全可配 ✓）
- `FertilityConfig` + `ModFertility`：**农作物肥沃度**系统——按季节判定作物能否种植/生长
- 数据包标签：`infertile_biomes`（贫瘠生物群系）、`unbreakable_infertile_crops`——可用数据包定制
- **殖民地食物经济联动点**：冬季作物减产/不可种植 → 殖民地食物供给承压 + 供暖需求上升 = 冬季双重挑战（这正是季节玩法的核心张力）

## 4. 已知注意点

- 社区反馈：与个别程序化地形生成存在配色/群系错位的边缘案例（本包以原版地形为主，风险低，M12 联调实测）
- 供暖玩法实装批次（锅炉房/余热回收/幸福度供暖因子激活）与本项目 12 立项同批（见 06 §13 决策记录）

## 5. 待办（立项时）

1. 项目 12 立项 → 基于 06 文档的幸福度/供暖设计 + 本笔记 API 面
2. 整合包必装清单追加：`sereneseasons 10.1.0.3` + `glitchcore ≥2.1.0.0`（+ 可选桥 patch11）
3. 供暖需求曲线 12 子季节数值表 → 项目主数值设计
