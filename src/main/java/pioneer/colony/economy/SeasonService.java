package pioneer.colony.economy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import pioneer.colony.config.Config;
import pioneer.colony.PioneerColony;

/**
 * Serene Seasons 软依赖适配。通过反射查询官方 SeasonHelper，不在编译期依赖四季模组。
 * 未安装或 API 不可用时，供暖项回到 0.5 中性、农场产量保持 100%。
 */
public final class SeasonService {
    private static boolean warnedApiFailure;

    private SeasonService() {
    }

    public record Snapshot(boolean available, String seasonName,
                           double heatingDemandMultiplier, double farmOutputMultiplier) {
        public static Snapshot neutral() {
            return new Snapshot(false, "neutral", 0.0, 1.0);
        }
    }

    public static Snapshot snapshot(MinecraftServer server) {
        if (server == null || !ModList.get().isLoaded("sereneseasons")) {
            return Snapshot.neutral();
        }
        try {
            ServerLevel level = server.overworld();
            Class<?> helper = Class.forName("sereneseasons.api.season.SeasonHelper");
            Object state = invokeSeasonState(helper, level);
            if (state == null) throw new ReflectiveOperationException("SeasonHelper 未返回季节状态");

            Object subSeason = invokeNoArg(state, "getSubSeason");
            Object season = invokeNoArg(state, "getSeason");
            String name = String.valueOf(subSeason != null ? subSeason : season).toUpperCase(Locale.ROOT);
            if (name.equals("NULL") || name.isBlank()) throw new ReflectiveOperationException("季节状态为空");

            double heating = curve(Config.HEATING_DEMAND_CURVE.get(), name, heatingDemand(name));
            double farm = curve(Config.FARM_SEASON_OUTPUT_CURVE.get(), name, farmOutput(name));
            return new Snapshot(true, name.toLowerCase(Locale.ROOT), heating, farm);
        } catch (ReflectiveOperationException | LinkageError error) {
            if (!warnedApiFailure) {
                warnedApiFailure = true;
                PioneerColony.LOGGER.warn("[殖民地经营] 已检测到 Serene Seasons，但无法读取季节 API；本会话使用中性回退：{}",
                        error.getMessage());
            }
            return Snapshot.neutral();
        }
    }

    private static Object invokeSeasonState(Class<?> helper, ServerLevel level) throws ReflectiveOperationException {
        for (Method method : helper.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || !method.getName().equals("getSeasonState")
                    || method.getParameterCount() != 1) continue;
            if (method.getParameterTypes()[0].isInstance(level)) {
                return method.invoke(null, level);
            }
        }
        throw new NoSuchMethodException("SeasonHelper.getSeasonState(Level)");
    }

    private static Object invokeNoArg(Object target, String name) throws ReflectiveOperationException {
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static double curve(java.util.List<? extends String> values, String season, double fallback) {
        String normalizedSeason = season.replaceAll("[^A-Z]", "");
        for (String entry : values) {
            int equals = entry.indexOf('=');
            if (equals <= 0) continue;
            String key = entry.substring(0, equals).trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
            if (!normalizedSeason.contains(key)) continue;
            try {
                double value = Double.parseDouble(entry.substring(equals + 1).trim());
                if (Double.isFinite(value)) return Math.max(0.0, Math.min(2.0, value));
            } catch (NumberFormatException ignored) {
                // 无效配置项忽略，继续尝试其他条目。
            }
        }
        return fallback;
    }

    private static double heatingDemand(String season) {
        if (season.contains("WINTER")) {
            if (season.contains("LATE")) return 1.00;
            if (season.contains("MID")) return 0.85;
            return 0.65;
        }
        if (season.contains("AUTUMN") || season.contains("FALL")) {
            if (season.contains("LATE")) return 0.50;
            if (season.contains("MID")) return 0.35;
            return 0.20;
        }
        if (season.contains("SPRING")) {
            if (season.contains("EARLY")) return 0.35;
            if (season.contains("MID")) return 0.25;
            return 0.15;
        }
        return 0.05;
    }

    private static double farmOutput(String season) {
        if (season.contains("WINTER")) return 0.55;
        if (season.contains("SPRING") && season.contains("EARLY")) return 0.75;
        if (season.contains("AUTUMN") || season.contains("FALL")) return 0.90;
        return 1.0;
    }
}
