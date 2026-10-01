# tools/ 开发机工具说明

本机网络环境下 Java TLS 直连 `maven.neoforged.net` 会被连接重置（curl 正常，其余镜像源均正常），
Gradle/NeoFormRuntime 对该域名的所有请求经本地缓存反代转发。

## 1. neoforged_maven_proxy.py（构建前置，必须先启动）

```bash
python tools/neoforged_maven_proxy.py          # 监听 127.0.0.1:8528，缓存落 H:/work/.tools/proxy_cache
```

- 首次构建会经它拉取 NeoForge 工具链全部构件（约几分钟），之后命中磁盘缓存、离线可用。
- settings.gradle 已将仓库指向 `http://127.0.0.1:8528`（`allowInsecureProtocol`，纯 HTTP 本地回环，无安全暴露面）。
- 若换了网络环境（Java 直连恢复），把 settings.gradle 中两个「本地反代」仓库条目改回
  `https://maven.neoforged.net` 并删除 `PREFER_SETTINGS` 模式即可回归规范模板。

## 2. rcon.py（服务端冒烟/验收用）

```bash
python tools/rcon.py "colony create smoke_base" "colony building add farm 3" "colony info"
```

- 前提：run/server.properties 已开 RCON（端口 25575，密码 pioneer_colony_dev）。
- 注意：原版 RCON 对**请求方向**的中文支持不稳定，命令参数一律用 ASCII（物品 id 用引号包住，如 `"minecraft:bread"`，Brigadier 非引号字符串不认冒号）；服务端响应方向的中文为 UTF-8，正常。

## 3. 常用冒烟命令序列

```
colony create smoke_base                  # 建殖民地（控制台执行为调试无主殖民地，位于世界出生点）
colony building add farm 3                # 调试加建筑
colony buffer add "minecraft:bread" 100   # 缓冲注入（可验证容量钳制）
colony credits add 1000                   # 信用点调整
colony tick                               # 强制结算一次（附主线程耗时）
colony backdate 480                       # 回拨 8 小时 → colony tick 触发离线补算
colony summary                            # 查看待领取的离线汇总
colony info / colony list                 # 总览
```
