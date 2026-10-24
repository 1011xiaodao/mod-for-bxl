//! 殖民地级幸福度公式。该模块只处理纯数值，不依赖 Minecraft 或存档对象。

/// 计算幸福度，公式与 Java 侧现有实现保持一致。
///
/// 供暖、食物、住房比率由调用方提供；调用方负责按游戏规则将比率限制在有效范围。
pub fn calculate_happiness(
    base: f64,
    heating_weight: f64,
    heating_rate: f64,
    food_weight: f64,
    food_rate: f64,
    housing_weight: f64,
    housing_rate: f64,
) -> f64 {
    let result = base
        + heating_weight * heating_rate
        + food_weight * food_rate
        + housing_weight * housing_rate;

    if result.is_finite() { result } else { 0.0 }
}

#[cfg(test)]
mod tests {
    use super::calculate_happiness;

    #[test]
    fn matches_default_colony_formula() {
        // 默认基础 25、供暖 35×0.5、食物 25×1、住房 15×1。
        assert_eq!(
            calculate_happiness(25.0, 35.0, 0.5, 25.0, 1.0, 15.0, 1.0),
            82.5
        );
    }

    #[test]
    fn returns_zero_for_non_finite_inputs() {
        assert_eq!(
            calculate_happiness(f64::NAN, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0),
            0.0
        );
    }
}
