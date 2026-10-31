package pioneer.colony.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import pioneer.colony.PioneerColony;
import pioneer.colony.colony.Colony;
import pioneer.colony.colony.ColonyManager;
import pioneer.colony.market.MarketSavedData;
import pioneer.colony.market.MarketSystem;

/** 贸易站 GUI 的服务端操作请求；服务端仍执行全部权限、价格、库存与资金校验。 */
public record MarketActionPayload(Action action, String item, long quantity, long price, int days, long orderId)
        implements CustomPacketPayload {
    public enum Action { BUY, SELL, CANCEL }

    public static final Type<MarketActionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PioneerColony.MODID, "market_action"));
    public static final StreamCodec<FriendlyByteBuf, MarketActionPayload> STREAM_CODEC =
            CustomPacketPayload.codec(MarketActionPayload::write, MarketActionPayload::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static void write(MarketActionPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.action().ordinal());
        buf.writeUtf(payload.item() == null ? "" : payload.item(), 256);
        buf.writeLong(payload.quantity());
        buf.writeLong(payload.price());
        buf.writeVarInt(payload.days());
        buf.writeLong(payload.orderId());
    }

    private static MarketActionPayload read(FriendlyByteBuf buf) {
        int ordinal = buf.readVarInt();
        Action action = ordinal >= 0 && ordinal < Action.values().length ? Action.values()[ordinal] : Action.CANCEL;
        return new MarketActionPayload(action, buf.readUtf(256), buf.readLong(), buf.readLong(),
                buf.readVarInt(), buf.readLong());
    }

    public static void handle(MarketActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var server = player.getServer();
            if (server == null) return;
            // 自定义包可被伪造：必须验证玩家当前确实打开了有权限的贸易站市场页。
            if (!(player.containerMenu instanceof pioneer.colony.menu.ColonyCoreMenu menu)
                    || !menu.canPlayerUseMarket(player)) {
                player.displayClientMessage(Component.literal("请在有访问权限的贸易站市场页面中操作。"), true);
                return;
            }
            Colony colony = ColonyManager.colonyOf(server, player.getUUID());
            MarketSystem.Result result;
            if (payload.action() == Action.CANCEL) {
                result = MarketSystem.cancelOrder(server, colony, player, payload.orderId());
            } else {
                MarketSavedData.Side side = payload.action() == Action.BUY
                        ? MarketSavedData.Side.BUY : MarketSavedData.Side.SELL;
                result = MarketSystem.placeOrder(server, colony, player, side, payload.item(),
                        payload.quantity(), payload.price(), payload.days());
            }
            player.displayClientMessage(Component.literal(result.message()), true);
        });
    }
}
