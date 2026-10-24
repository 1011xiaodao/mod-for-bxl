# Rust 原生核心开发说明

本项目采用 **Java 25 + 稳定版 FFM（Foreign Function & Memory API）+ Rust C ABI**。不使用 JNI，不需要生成 JNI 头文件。

目标是让业务算法尽量只写在 Rust。Java 侧桥接入口和 Minecraft 生命周期适配由框架维护；增加普通的数值/规则算法时，通常只需要修改 Rust 的核心实现与 Rust 分发注册表。

## 目录职责

- `colony-core`：纯 Rust 业务计算。经济、幸福度、市场、市民、Gauge 和贸易算法放这里；不要依赖 Minecraft/NeoForge。
- `colony-ffi`：薄 C ABI 适配层，包含通用 JSON 分发入口。新增 Rust 算法在这里登记操作名即可，不需要为每个算法增加 Java FFM 符号。
- `src/main/java/pioneer/colony/nativebridge/RustBridge.java`：底层 FFM 入口，负责加载动态库、验证 ABI 版本、调用导出函数、管理返回字符串和固定公式回退。
- `RustNativeClient.java`：Java 业务代码使用的结构化 JSON 客户端，负责序列化请求、解析成功/失败响应和数值回退。
- `RustEconomyAdapter.java` / `RustMarketAdapter.java`：业务适配层，负责输入校验、Java 回退公式，以及把价格区间等游戏规则留在 Java。
- Java 现有 Minecraft 生命周期、实体、存档、网络、GUI 仍由 Java 管理。不要把 Minecraft 对象或 JVM 对象直接传入 Rust。

## 构建与测试

在项目根目录执行：

```bash
cargo fmt --manifest-path native/Cargo.toml --all -- --check
cargo test --manifest-path native/Cargo.toml --workspace
cargo clippy --manifest-path native/Cargo.toml --workspace --all-targets -- -D warnings
./gradlew build
```

Gradle 的 `buildRustNative` 任务会自动尝试编译当前平台的 Rust 动态库，并打包到 jar 的 `META-INF/native/<系统>-<架构>/` 目录。安装了 Cargo 时，正常执行 `./gradlew build` 就会编译并打包；未安装 Cargo 时会跳过原生库打包，模组仍可用 Java 回退公式运行。

`check` 会运行 `nativeBridgeSmokeTest`，由 Java 25 真正加载动态库并检查 ABI、固定签名、结构化 JSON 调用、错误回退和适配层行为。

当前只会构建**运行 Gradle 的本机平台**。要发布给其他操作系统或 CPU 架构，需要分别在对应目标平台构建，并把相应动态库资源一起打包；不能把 Linux 的 `.so` 当作 Windows 的 `.dll` 使用。

## 现有调用

固定签名接口适用于特别高频、结构简单的计算；游戏经济 tick 通过 `RustEconomyAdapter` 调用：

```java
double happiness = RustEconomyAdapter.calculateHappiness(
        new RustEconomyAdapter.HappinessInput(
                base, heatingWeight, heatingRate,
                foodWeight, foodRate,
                housingWeight, housingRate));
```

通用接口适用于新算法。游戏业务代码优先使用 `RustNativeClient`，不用自己拼接 JSON，也不用手动解析错误：

```java
JsonObject input = new JsonObject();
input.addProperty("basePrice", 100.0);
input.addProperty("marketScore", 0.2);
input.addProperty("sectorScore", 0.5);

RustNativeClient.Response response = RustNativeClient.invoke(
        "calculate_market_fair_price", input);
if (response.success()) {
    double fairPrice = response.doubleValueOr(0.0);
} else {
    // 按业务需求记录错误或采用 Java 回退值
    String errorCode = response.error();
}
```

也提供字符串输入重载，适用于小型测试。数值型业务可使用 `invokeDouble(operation, input, fallback)`，统一处理失败与非法返回值。

响应格式：

- 成功：`{"ok":true,"value":123.45}`
- 失败：`{"ok":false,"error":"unknown_operation:xxx"}`

JSON 接口目前提供 `calculate_happiness` 和 `calculate_market_fair_price` 两个操作示例。幸福度已接入现有经济 tick；Java 的 `MarketPrices.price(item, marketScore, sectorScore)` 已提供市场合理价计算入口并应用数据包价格上下限，但当前市场撮合尚未调用该方法。袭击威胁估值继续使用 `basePrice`，避免行情波动影响袭击难度。

## 新增 Rust 算法（推荐工作流）

1. 在 `native/colony-core/src/` 相应模块里写纯 Rust 函数并增加单元测试。
2. 在 `native/colony-ffi/src/lib.rs` 的 `dispatch_json` 中添加操作名分支，解析 JSON 参数并调用你的核心函数。
3. Java 通过 `RustNativeClient.invoke("你的操作名", inputJson)` 调用。通用 C ABI 符号不变，**新增普通算法不需要修改 Java FFM 方法句柄或导出新 C 符号**。
4. 对 Minecraft 世界、实体、存档或网络的读写仍由 Java 适配层负责：Java 收集输入快照，Rust 返回计算结果，Java 验证并应用结果。
5. Rust 响应使用 `{"ok":true,"value":...}` / `{"ok":false,"error":"..."}` 包装。参数名和 JSON 结构属于接口契约，修改时同步更新 Rust 单元测试。

## FFI 安全约定

- Java/Rust 边界只传 C 字符串和固定宽度标量；禁止传递 `String`、`Vec`、Rust 引用、Java 对象或由一侧分配而另一侧不按约定释放的指针。
- `colony_core_call_json` 返回的字符串必须且只能使用 `colony_core_free_string` 释放一次。
- FFI 不允许 panic 穿越 C ABI；输入格式错误、未知操作和 Rust panic 都应转换为错误 JSON。
- 只在 ABI 签名/内存约定不兼容时更新 ABI 版本；当前 ABI 版本为 2。
- 保留 Java 回退路径；Rust 业务函数尽量纯函数、确定性、无副作用，便于测试。
- 可通过 JVM 参数指定调试用的外部库：`-Dpioneer_colony.native.path=/绝对路径/libcolony_ffi.so`。Windows/macOS 库名不同。
