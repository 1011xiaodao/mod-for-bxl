# Rust 原生核心开发说明

本项目采用 **Java 25 + 稳定版 FFM（Foreign Function & Memory API）+ Rust C ABI**。不使用 JNI，不需要编写 JNI 头文件或 Java native 方法。

## 目录职责

- `colony-core`：纯 Rust 业务计算。经济、幸福度、市场、市民、Gauge 和贸易算法放这里；不要依赖 Minecraft/NeoForge。
- `colony-ffi`：很薄的 C ABI 适配层。只负责导出函数、校验/转换参数并调用 `colony-core`。
- `src/main/java/pioneer/colony/nativebridge/RustBridge.java`：Java 侧唯一桥接入口，负责加载动态库、验证 ABI 版本、调用导出函数和回退。
- Java 现有 Minecraft 生命周期、实体、存档、网络、GUI 仍由 Java 管理。不要把 Minecraft 对象或 JVM 对象直接传入 Rust。

## 构建与测试

在项目根目录执行：

```bash
cargo test --manifest-path native/Cargo.toml --workspace
cargo fmt --manifest-path native/Cargo.toml --all -- --check
cargo clippy --manifest-path native/Cargo.toml --workspace --all-targets -- -D warnings
./gradlew build
```

Gradle 的 `buildRustNative` 任务会自动尝试编译当前操作系统/CPU 架构的 Rust 动态库，并打包到 jar 的 `META-INF/native/<系统>-<架构>/` 目录。安装了 Cargo 时，正常执行 `./gradlew build` 就会编译并打包；未安装 Cargo 时会跳过原生库打包，模组仍可用 Java 回退公式运行。

当前只会构建**运行 Gradle 的本机平台**。要发布给其他操作系统或 CPU 架构，需要分别在对应目标平台构建，并把相应动态库资源一起打包；不能把 Linux 的 `.so` 当作 Windows 的 `.dll` 使用。

## Java 侧怎么调用 Rust

在 Java 代码中调用 `RustBridge`，不要自行重复加载动态库：

```java
double happiness = RustBridge.calculateHappinessOrFallback(
        base, heatingWeight, heatingRate,
        foodWeight, foodRate,
        housingWeight, housingRate);
```

该调用已经用于现有经济 tick 的幸福度计算。Rust 库未找到、ABI 不匹配或调用失败时，自动执行相同的 Java 公式。启动日志会显示当前是否启用 Rust。

可通过 JVM 参数显式指定外部原生库（用于本地调试）：

```text
-Dpioneer_colony.native.path=/绝对路径/libcolony_ffi.so
```

路径要替换成当前系统的真实库文件；Windows/macOS 文件名不同。开发运行配置已添加 `--enable-native-access=ALL-UNNAMED`，发布整合包时也应在 Java 25 启动参数中保留该参数。

## 增加一个 Rust 函数的约定

1. 在 `colony-core/src/` 对应业务模块实现**纯计算函数**，先写 Rust 单元测试。
2. 在 `colony-ffi/src/lib.rs` 添加 `#[unsafe(no_mangle)] pub extern "C" fn` 导出函数。
3. FFI 只使用明确的 C ABI 标量类型（`u32/i32/u64/i64/f32/f64`）或以后专门定义的 `#[repr(C)]` 数据结构。禁止跨边界传递 `String`、`Vec`、Rust 引用、Java 对象或由一侧分配、另一侧释放的裸指针。
4. 在 `RustBridge.java` 为符号声明对应的 `FunctionDescriptor` 和精确的 `MethodHandle.invokeExact` 调用。参数数量、顺序、宽度和返回类型必须与 Rust 签名完全一致。
5. 新增或改变 ABI 约定时，更新 `EXPECTED_ABI_VERSION` 与 `colony_core_abi_version()`，避免 Java 错误调用不兼容的库。
6. 保留 Java 回退路径；原生计算不得直接访问 Minecraft 世界、实体、存档或服务端对象。

## 已接入的示例

- `colony_core_calculate_happiness`：现有幸福度公式，已从经济 tick 调用。
- `colony_core_calculate_market_fair_price`：市场合理价格公式的桥接示例，暂未接入现有市场交易流程。
- `colony_core_abi_version`：桥接版本校验。

所有计算函数都应保持确定性，且尽量不产生副作用，方便 Rust 单元测试与 Java 回退行为核对。
