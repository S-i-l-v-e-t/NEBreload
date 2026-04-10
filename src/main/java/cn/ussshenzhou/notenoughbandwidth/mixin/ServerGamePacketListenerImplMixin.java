package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.aggregation.PacketAggregationPacket;
import io.netty.buffer.ByteBufAllocator;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerImplMixin {
    @Shadow @Final public Connection connection;
    @SuppressWarnings("unchecked")
    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void nebHandleAggregatedPayload(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && !server.isSameThread()) {
            ServerboundCustomPayloadPacket copiedPacket;
            FriendlyByteBuf payload = packet.getData();
            FriendlyByteBuf wrapper = new FriendlyByteBuf(ByteBufAllocator.DEFAULT.buffer());
            try {
                wrapper.writeResourceLocation(packet.getIdentifier());
                payload.readerIndex(0);
                wrapper.writeBytes(payload);
                copiedPacket = new ServerboundCustomPayloadPacket(wrapper);
            } finally {
                payload.release();

            }
            server.execute(() -> {
                try{
                ((ServerGamePacketListenerImpl) (Object) this).handleCustomPayload(copiedPacket);
                }
                finally {
                    wrapper.release();
                }
            });
            ci.cancel();
            return;
        }

        if (!PacketAggregationPacket.TYPE.equals(packet.getIdentifier())) {
            return;
        }

        PacketAggregationPacket aggregationPacket = new PacketAggregationPacket(packet.getData(),this.connection);
        ArrayList<Packet<?>> packets = aggregationPacket.decodeToPackets(PacketFlow.SERVERBOUND);
        ServerGamePacketListener listener = (ServerGamePacketListener) (Object) this;
        for (Packet<?> subPacket : packets) {
            ((Packet<ServerGamePacketListener>) subPacket).handle(listener);
        }
        ci.cancel();
    }
}
