package pioneer.colony.research;

import java.util.List;
import java.util.Map;
import pioneer.colony.colony.ItemCost;

/**
 * 研究条目展示信息（UI/命令共用）。state 语义：
 * AVAILABLE 可发起｜ACTIVE 进行中｜DONE 已完成｜LOCKED_PARENT 前置未完成｜LOCKED_NO_COLONY 仅殖民地可研而玩家无殖民地。
 */
public record ResearchInfo(String id, String name, String channel, int timeMinutes,
                           List<ItemCost> cost, String parent, Map<String, Object> effects,
                           State state, int remainingSeconds) {

    public enum State {
        AVAILABLE, ACTIVE, DONE, LOCKED_PARENT, LOCKED_NO_COLONY
    }

    public String stateText() {
        return switch (state) {
            case AVAILABLE -> "可发起";
            case ACTIVE -> "进行中";
            case DONE -> "已完成";
            case LOCKED_PARENT -> "前置未完成";
            case LOCKED_NO_COLONY -> "需要殖民地";
        };
    }
}
