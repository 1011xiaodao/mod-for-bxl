//! C ABI 边界：只允许传递固定宽度标量，不能跨边界传 Rust/Java 对象、String 或 Vec。
//! Java 25 通过稳定版 Foreign Function & Memory API 调用这些导出函数。

/// 每次修改导出函数签名或语义时，按兼容策略更新 ABI 版本。
#[unsafe(no_mangle)]
pub extern "C" fn colony_core_abi_version() -> u32 {
    1
}

/// 幸福度计算。参数顺序必须与 Java RustBridge 中的 FunctionDescriptor 一致。
#[unsafe(no_mangle)]
pub extern "C" fn colony_core_calculate_happiness(
    base: f64,
    heating_weight: f64,
    heating_rate: f64,
    food_weight: f64,
    food_rate: f64,
    housing_weight: f64,
    housing_rate: f64,
) -> f64 {
    colony_core::happiness::calculate_happiness(
        base,
        heating_weight,
        heating_rate,
        food_weight,
        food_rate,
        housing_weight,
        housing_rate,
    )
}

/// 市场合理价格计算。此接口已就位，供后续 Rust 市场算法接入。
#[unsafe(no_mangle)]
pub extern "C" fn colony_core_calculate_market_fair_price(
    base_price: f64,
    market_score: f64,
    sector_score: f64,
) -> f64 {
    colony_core::market::calculate_fair_price(base_price, market_score, sector_score)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exposes_expected_abi_version() {
        assert_eq!(colony_core_abi_version(), 1);
    }

    #[test]
    fn exported_happiness_matches_core_formula() {
        assert_eq!(
            colony_core_calculate_happiness(25.0, 35.0, 0.5, 25.0, 1.0, 15.0, 1.0),
            82.5
        );
    }

    #[test]
    fn exported_market_formula_is_callable() {
        assert_eq!(
            colony_core_calculate_market_fair_price(100.0, 0.0, 0.0),
            100.0
        );
    }
}
