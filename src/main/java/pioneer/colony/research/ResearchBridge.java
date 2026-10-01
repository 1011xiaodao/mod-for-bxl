package pioneer.colony.research;

import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/**
 * [研究台]（pioneer_research）集成桥（06 §5，M6.3）。
 * 本模组不实现研究逻辑——桥把「研究树/进度/推进」委托给研究系统：
 * <ul>
 *   <li>[研究台] 在位（M8.4 联调后）：经其 ResearchApi（pioneer.research.api）接入；</li>
 *   <li>[研究台] 缺席 + 研究桩开启（开发联调）：{@code StubResearchSystem.Bridge} 按
 *       [研究台] 文档相同的数据格式（data/&lt;ns&gt;/pioneer_research/research/*.json）提供等效实现；</li>
 *   <li>二者皆无：桥为 null，市政厅研究页隐藏，其余功能不受影响。</li>
 * </ul>
 * 契约注意：[研究台] §6 公开的 ResearchApi 尚无「发起研究」方法——发起/取消契约需 M8.4 联调时补齐
 * （startViaColony 即我方所需的最小发起面，见回报-M6.3）。
 */
public interface ResearchBridge {
    boolean isDone(MinecraftServer server, UUID player, String researchId);

    /** 玩家可见的研究列表（colony_only 仅对拥有殖民地的玩家可见）。 */
    List<ResearchInfo> visibleResearches(MinecraftServer server, UUID player);

    /** colony 通道发起研究（调用方先完成扣费：仓库扣料 + 组织费）。返回 null=成功，否则为失败原因。 */
    String startViaColony(MinecraftServer server, UUID player, String researchId);

    /** 注册 colony 通道（并行槽位/速度乘数/效果实现）。 */
    void registerColonyChannel(ColonyResearchChannel channel);
}
