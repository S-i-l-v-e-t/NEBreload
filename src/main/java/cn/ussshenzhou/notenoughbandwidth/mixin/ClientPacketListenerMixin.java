package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.aggregation.PacketAggregationPacket;
import io.netty.buffer.ByteBufAllocator;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.server.RunningOnDifferentThreadException;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Shadow @Final public Connection connection;
    @SuppressWarnings("unchecked")
    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void nebHandleAggregatedPayload(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            ClientboundCustomPayloadPacket copiedPacket;
            FriendlyByteBuf payload = packet.getData();
            FriendlyByteBuf wrapper = new FriendlyByteBuf(ByteBufAllocator.DEFAULT.buffer());
            try {
                wrapper.writeResourceLocation(packet.getIdentifier());
                wrapper.writeBytes(payload);
                copiedPacket = new ClientboundCustomPayloadPacket(wrapper);
            } finally {
                payload.release();
                wrapper.release();
            }
            minecraft.execute(() -> ((ClientPacketListener) (Object) this).handleCustomPayload(copiedPacket));
            ci.cancel();
            return;
        }

        if (!PacketAggregationPacket.TYPE.equals(packet.getIdentifier())) {
            return;
        }

        PacketAggregationPacket aggregationPacket = new PacketAggregationPacket(packet.getData(),this.connection);
        ArrayList<Packet<?>> packets = aggregationPacket.decodeToPackets(PacketFlow.CLIENTBOUND);
        ClientGamePacketListener listener = (ClientGamePacketListener) (Object) this;
        for (Packet<?> subPacket : packets) {
            try {
                ((Packet<net.minecraft.network.protocol.game.ClientGamePacketListener>) subPacket).handle(listener);
                }
            catch (Exception e) {
            }
        }
        ci.cancel();
    }
}
