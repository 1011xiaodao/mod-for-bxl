package pioneer.colony.network;

import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import pioneer.colony.PioneerColony;

/**
 * 市政厅研究页数据同步（服务端 → 客户端，M6.3）。
 * 研究列表是动态文本（名称/费用/状态），超出 data slots 能力，用自定义 payload 推送。
 */
public record ColonyResearchPayload(List<Entry> entries, int slotsUsed, int slotsMax,
                                    int speedPercent, int orgFee) implements CustomPacketPayload {

    public static final ColonyResearchPayload.Type TYPE =
            new ColonyResearchPayload.Type(ResourceLocation.fromNamespaceAndPath(PioneerColony.MODID, "research_list"));

    public static final StreamCodec<FriendlyByteBuf, ColonyResearchPayload> STREAM_CODEC =
            CustomPacketPayload.codec(ColonyResearchPayload::write, ColonyResearchPayload::read);

    public record Entry(String id, String name, String channel, String costText,
                        String stateText, boolean startable, int remainingSeconds) {
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(ColonyResearchPayload payload, FriendlyByteBuf buf) {
        buf.writeCollection(payload.entries, (b, e) -> {
            b.writeUtf(e.id(), 256);
            b.writeUtf(e.name(), 256);
            b.writeUtf(e.channel(), 32);
            b.writeUtf(e.costText(), 256);
            b.writeUtf(e.stateText(), 32);
            b.writeBoolean(e.startable());
            b.writeVarInt(e.remainingSeconds());
        });
        buf.writeVarInt(payload.slotsUsed());
        buf.writeVarInt(payload.slotsMax());
        buf.writeVarInt(payload.speedPercent());
        buf.writeVarInt(payload.orgFee());
    }

    private static ColonyResearchPayload read(FriendlyByteBuf buf) {
        List<Entry> entries = buf.readList(b -> new Entry(
                b.readUtf(256), b.readUtf(256), b.readUtf(32), b.readUtf(256),
                b.readUtf(32), b.readBoolean(), b.readVarInt()));
        return new ColonyResearchPayload(entries,
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }
}
