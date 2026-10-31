package pioneer.colony.gauge;

/** 扩展资源定义；短缺时效率下限为 0～1。 */
public record GaugeDefinition(String id, String displayName, String unit, double shortageEfficiencyFloor) {
    public GaugeDefinition {
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Gauge ID 必须是 namespace:path：" + id);
        }
        if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("Gauge 名称不能为空");
        if (unit == null || unit.isBlank()) throw new IllegalArgumentException("Gauge 单位不能为空");
        if (!Double.isFinite(shortageEfficiencyFloor) || shortageEfficiencyFloor < 0 || shortageEfficiencyFloor > 1) {
            throw new IllegalArgumentException("Gauge 短缺效率下限必须在 0～1");
        }
    }
}
