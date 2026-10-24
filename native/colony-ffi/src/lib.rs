//! Java 25 FFM 使用的 C ABI 边界。
//! 跨边界只传递固定宽度标量或由调用方管理生命周期的 C 字符串。
//! 所有通用业务调用使用 JSON，新增 Rust 算法无需为每个函数增加 Java MethodHandle。

use std::ffi::{CStr, CString, c_char};
use std::panic::{AssertUnwindSafe, catch_unwind};

use serde_json::{Value, json};

const ABI_VERSION: u32 = 2;

#[unsafe(no_mangle)]
pub extern "C" fn colony_core_abi_version() -> u32 {
    ABI_VERSION
}

/// 兼容高频、固定签名调用的幸福度函数。
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

/// 兼容固定签名的市场合理价格函数。
#[unsafe(no_mangle)]
pub extern "C" fn colony_core_calculate_market_fair_price(
    base_price: f64,
    market_score: f64,
    sector_score: f64,
) -> f64 {
    colony_core::market::calculate_fair_price(base_price, market_score, sector_score)
}

/// 通用 Rust 业务入口。
///
/// operation 与 input_json 必须是有效的 UTF-8、NUL 结尾字符串，由调用方保证指针有效。
/// 返回的 JSON 字符串由 Rust 分配，调用方必须使用 colony_core_free_string 释放。
/// 返回结构：成功 {"ok":true,"value":...}；失败 {"ok":false,"error":"..."}。
///
/// # Safety
/// 两个参数必须为空指针或指向有效的、以 NUL 结尾的 UTF-8 C 字符串；调用期间内存必须保持有效。
#[unsafe(no_mangle)]
pub unsafe extern "C" fn colony_core_call_json(
    operation: *const c_char,
    input_json: *const c_char,
) -> *mut c_char {
    if operation.is_null() || input_json.is_null() {
        return json_to_c_string(json!({"ok": false, "error": "null_argument"}));
    }

    let result = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: 调用方契约要求两个参数均为有效的 NUL 结尾 C 字符串。
        let operation = unsafe { CStr::from_ptr(operation) }
            .to_str()
            .map_err(|_| "operation_not_utf8".to_string())?;
        // SAFETY: 同上；input_json 在本次同步调用期间保持有效。
        let input_json = unsafe { CStr::from_ptr(input_json) }
            .to_str()
            .map_err(|_| "input_not_utf8".to_string())?;
        let input: Value =
            serde_json::from_str(input_json).map_err(|_| "invalid_json".to_string())?;
        dispatch_json(operation, input)
    }));

    let response = match result {
        Ok(Ok(value)) => json!({"ok": true, "value": value}),
        Ok(Err(error)) => json!({"ok": false, "error": error}),
        Err(_) => json!({"ok": false, "error": "rust_panic"}),
    };
    json_to_c_string(response)
}

/// 释放 colony_core_call_json 返回的字符串。每个返回指针必须恰好释放一次。
///
/// # Safety
/// pointer 必须为空指针，或是本库此前返回且尚未释放的字符串指针。
#[unsafe(no_mangle)]
pub unsafe extern "C" fn colony_core_free_string(pointer: *mut c_char) {
    if !pointer.is_null() {
        // SAFETY: 该指针必须来自本库的 colony_core_call_json，且尚未释放。
        unsafe { drop(CString::from_raw(pointer)) };
    }
}

fn json_to_c_string(value: Value) -> *mut c_char {
    match CString::new(value.to_string()) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn required_number(input: &Value, key: &str) -> Result<f64, String> {
    input
        .get(key)
        .and_then(Value::as_f64)
        .filter(|value| value.is_finite())
        .ok_or_else(|| format!("missing_or_invalid_number:{key}"))
}

/// 通过操作名将 JSON 请求分发到纯 Rust 业务函数。
///
/// 新增算法时只需在 colony-core 实现函数，并在这里添加一个分支；
/// Java 桥接层的 C ABI 签名无需改变。
fn dispatch_json(operation: &str, input: Value) -> Result<Value, String> {
    match operation {
        "calculate_happiness" => {
            let value = colony_core::happiness::calculate_happiness(
                required_number(&input, "base")?,
                required_number(&input, "heatingWeight")?,
                required_number(&input, "heatingRate")?,
                required_number(&input, "foodWeight")?,
                required_number(&input, "foodRate")?,
                required_number(&input, "housingWeight")?,
                required_number(&input, "housingRate")?,
            );
            Ok(json!(value))
        }
        "calculate_market_fair_price" => Ok(json!(colony_core::market::calculate_fair_price(
            required_number(&input, "basePrice")?,
            required_number(&input, "marketScore")?,
            required_number(&input, "sectorScore")?,
        ))),
        other => Err(format!("unknown_operation:{other}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exposes_expected_abi_version() {
        assert_eq!(colony_core_abi_version(), ABI_VERSION);
    }

    #[test]
    fn fixed_happiness_export_matches_core_formula() {
        assert_eq!(
            colony_core_calculate_happiness(25.0, 35.0, 0.5, 25.0, 1.0, 15.0, 1.0),
            82.5
        );
    }

    #[test]
    fn json_dispatch_calculates_happiness() {
        let input = json!({
            "base": 25.0,
            "heatingWeight": 35.0,
            "heatingRate": 0.5,
            "foodWeight": 25.0,
            "foodRate": 1.0,
            "housingWeight": 15.0,
            "housingRate": 1.0
        });
        assert_eq!(
            dispatch_json("calculate_happiness", input).unwrap(),
            json!(82.5)
        );
    }

    #[test]
    fn json_dispatch_rejects_unknown_operation() {
        assert_eq!(
            dispatch_json("not_registered", json!({})).unwrap_err(),
            "unknown_operation:not_registered"
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
