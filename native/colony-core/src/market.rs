//! 市场数值公式。只负责确定性计算，不执行订单撮合或修改游戏状态。

/// 根据大盘与行业得分计算合理价格。
///
/// 价格因子被限制在 0.55～1.85；无效输入返回最低参考价，避免 NaN 传播。
pub fn calculate_fair_price(base_price: f64, market_score: f64, sector_score: f64) -> f64 {
    if !base_price.is_finite() || !market_score.is_finite() || !sector_score.is_finite() {
        return 0.01;
    }

    let factor = (1.0 + 0.22 * market_score + 0.48 * sector_score).clamp(0.55, 1.85);
    (base_price * factor).max(0.01)
}

#[cfg(test)]
mod tests {
    use super::calculate_fair_price;

    #[test]
    fn neutral_market_preserves_base_price() {
        assert_eq!(calculate_fair_price(100.0, 0.0, 0.0), 100.0);
    }

    #[test]
    fn clamps_market_factor() {
        assert!((calculate_fair_price(100.0, 1.0, 1.0) - 170.0).abs() < 1e-9);
        assert!((calculate_fair_price(100.0, -1.0, -1.0) - 55.0).abs() < 1e-9);
    }

    #[test]
    fn invalid_price_has_minimum_value() {
        assert_eq!(calculate_fair_price(f64::NAN, 0.0, 0.0), 0.01);
    }
}
