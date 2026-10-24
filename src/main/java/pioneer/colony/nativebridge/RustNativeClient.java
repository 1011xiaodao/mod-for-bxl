package pioneer.colony.nativebridge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 面向 Java 业务代码的 Rust 通用 JSON 客户端。
 *
 * <p>业务代码只需构造 JsonObject 并读取结构化响应，不直接拼接 JSON 字符串，
 * 也不需要了解 C 字符串内存释放、FFM MethodHandle 或 ABI 细节。</p>
 */
public final class RustNativeClient {
    private static final Gson GSON = new Gson();

    private RustNativeClient() {
    }

    /**
     * Rust 通用调用的统一结果。失败时 value 为 JsonNull，error 为稳定的错误标识。
     */
    public record Response(boolean success, JsonElement value, String error) {
        public Response {
            value = value == null ? JsonNull.INSTANCE : value;
            error = error == null ? "" : error;
        }

        public static Response success(JsonElement value) {
            return new Response(true, value, "");
        }

        public static Response failure(String error) {
            return new Response(false, JsonNull.INSTANCE, error);
        }

        public double doubleValueOr(double fallback) {
            if (!success || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                return fallback;
            }
            try {
                double result = value.getAsDouble();
                return Double.isFinite(result) ? result : fallback;
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }

        public JsonObject objectValueOrEmpty() {
            return success && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
        }
    }

    /**
     * 调用 Rust 操作。业务代码优先使用 JsonObject 重载；此重载适合配置文件或测试输入。
     */
    public static Response invoke(String operation, String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return Response.failure("invalid_java_argument");
        }
        try {
            JsonElement parsed = JsonParser.parseString(inputJson);
            if (!parsed.isJsonObject()) {
                return Response.failure("invalid_input_json");
            }
            return invoke(operation, parsed);
        } catch (RuntimeException error) {
            return Response.failure("invalid_input_json");
        }
    }

    /**
     * 调用 Rust 操作。
     *
     * @param operation Rust 分发器注册的操作名
     * @param input 结构化 JSON 输入；不得为 null
     */
    public static Response invoke(String operation, JsonElement input) {
        if (operation == null || operation.isBlank() || input == null || !input.isJsonObject()) {
            return Response.failure("invalid_java_argument");
        }

        final String rawResponse;
        try {
            rawResponse = RustBridge.invokeJson(operation, GSON.toJson(input));
        } catch (RuntimeException error) {
            return Response.failure("java_bridge_error");
        }

        return parseResponse(rawResponse);
    }

    /**
     * 数值计算便捷入口：任何桥接错误、Rust 错误或非法返回值都会使用调用方提供的回退值。
     */
    public static double invokeDouble(String operation, String inputJson, double fallback) {
        if (!Double.isFinite(fallback)) {
            fallback = 0.0;
        }
        return invoke(operation, inputJson).doubleValueOr(fallback);
    }

    public static double invokeDouble(String operation, JsonObject input, double fallback) {
        if (!Double.isFinite(fallback)) {
            fallback = 0.0;
        }
        return invoke(operation, input).doubleValueOr(fallback);
    }

    static Response parseResponse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return Response.failure("empty_native_response");
        }

        try {
            JsonElement parsed = JsonParser.parseString(rawResponse);
            if (!parsed.isJsonObject()) {
                return Response.failure("invalid_native_response");
            }

            JsonObject envelope = parsed.getAsJsonObject();
            JsonElement okElement = envelope.get("ok");
            if (okElement == null || !okElement.isJsonPrimitive()
                    || !okElement.getAsJsonPrimitive().isBoolean()) {
                return Response.failure("invalid_native_response");
            }

            if (!okElement.getAsBoolean()) {
                JsonElement errorElement = envelope.get("error");
                String error = errorElement != null && errorElement.isJsonPrimitive()
                        ? errorElement.getAsString() : "native_operation_failed";
                return Response.failure(error);
            }

            JsonElement value = envelope.get("value");
            if (value == null || value.isJsonNull()) {
                return Response.failure("missing_native_value");
            }
            return Response.success(value);
        } catch (RuntimeException error) {
            return Response.failure("invalid_native_response");
        }
    }
}
