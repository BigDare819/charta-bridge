package dev.bridge.network;

import dev.bridge.BridgeMod;
import dev.bridge.game.BridgeMenu;
import dev.lucaargolo.charta.common.game.api.CardPlayer;
import dev.lucaargolo.charta.mixed.LivingEntityMixed;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;

/**
 * One call in the auction, from the bidding box to the server.
 *
 * <p>Everything else about the auction rides Charta's container-data sync; a call is the only thing the
 * client has to <em>send</em>, so this is the only payload the addon needs. It is registered straight
 * with Fabric's networking API rather than through Charta's packet manager, which keeps the addon free
 * of any dependency on Charta's packet internals.
 *
 * <p>The server validates everything: an illegal call simply becomes a pass, and a call from the wrong
 * seat is ignored, so a desynced or spamming client can never wedge a board.
 */
public record BridgeCallPayload(int containerId, int call) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BridgeCallPayload> TYPE =
            new CustomPacketPayload.Type<>(BridgeMod.id("call"));

    public static final StreamCodec<ByteBuf, BridgeCallPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            BridgeCallPayload::containerId,
            ByteBufCodecs.VAR_INT,
            BridgeCallPayload::call,
            BridgeCallPayload::new
    );

    public static void handleServer(BridgeCallPayload payload, ServerPlayer player, Executor executor) {
        executor.execute(() -> {
            if (!(player.containerMenu instanceof BridgeMenu menu) || menu.containerId != payload.containerId()) {
                return;
            }
            CardPlayer cardPlayer = ((LivingEntityMixed) player).charta_getCardPlayer();
            menu.getGame().submitCall(cardPlayer, payload.call());
        });
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
