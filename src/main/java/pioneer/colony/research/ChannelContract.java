package pioneer.colony.research;

import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/**
 * colony 通道契约——形状对齐 [研究台] 文档 §6 的 ChannelExtension
 * （id/maxParallel/speedMultiplier/accepts/onCompleted）。
 * 与真实 API 的差异：我方签名带 MinecraftServer（并行/加速需要读殖民地数据），
 * M8.4 联调时由适配器映射到 ResearchApi.ChannelExtension。
 */
public interface ChannelContract {
    String id();

    int maxParallel(MinecraftServer server, UUID player);

    double speedMultiplier(MinecraftServer server, UUID player);

    /** colony_only 条件（研究系统按定义的 channel 字段过滤，此处恒 true 占位）。 */
    default boolean accepts(String researchId) {
        return true;
    }

    void onCompleted(MinecraftServer server, UUID player, String researchId, Map<String, Object> effects);
}
