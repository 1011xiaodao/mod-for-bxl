package pioneer.colony.network;

import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import pioneer.colony.PioneerColony;

/** 贸易站 GUI 的价目表与本殖民地挂单快照（服务端→客户端）。 */
public record ColonyMarketPayload(List<PriceEntry> prices, List<OrderEntry> orders) implements CustomPacketPayload {
    public static final Type<ColonyMarketPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PioneerColony.MODID, "market_snapshot"));
    public static final StreamCodec<FriendlyByteBuf, ColonyMarketPayload> STREAM_CODEC =
            CustomPacketPayload.codec(ColonyMarketPayload::write, ColonyMarketPayload::read);

    public record PriceEntry(String item, long price, double min, double max) { }
    public record OrderEntry(long id, String side, String item, long remaining, long limitPrice, long expiresAt) { }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static void write(ColonyMarketPayload payload, FriendlyByteBuf buf) {
        buf.writeCollection(payload.prices(), (b, e) -> {
            b.writeUtf(e.item(), 256);
            b.writeLong(e.price());
            b.writeDouble(e.min());
            b.writeDouble(e.max());
        });
        buf.writeCollection(payload.orders(), (b, e) -> {
            b.writeLong(e.id());
            b.writeUtf(e.side(), 16);
            b.writeUtf(e.item(), 256);
            b.writeLong(e.remaining());
            b.writeLong(e.limitPrice());
            b.writeLong(e.expiresAt());
        });
    }

    private static ColonyMarketPayload read(FriendlyByteBuf buf) {
        List<PriceEntry> prices = buf.readList(b ->
                new PriceEntry(b.readUtf(256), b.readLong(), b.readDouble(), b.readDouble()));
        List<OrderEntry> orders = buf.readList(b ->
                new OrderEntry(b.readLong(), b.readUtf(16), b.readUtf(256), b.readLong(), b.readLong(), b.readLong()));
        return new ColonyMarketPayload(prices, orders);
    }
}
