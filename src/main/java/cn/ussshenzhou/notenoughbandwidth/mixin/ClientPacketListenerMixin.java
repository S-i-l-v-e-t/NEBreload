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
        org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
        for (Packet<?> subPacket : packets) {

            // 🌟 探针 1：在执行前拦截实体生成包，读取它的内存数据
            if (subPacket instanceof net.minecraft.network.protocol.game.ClientboundAddEntityPacket addPkt) {
                LOGGER.warn("[实体探针] 发现实体生成包! 准备生成: 类型={}, ID={}, 坐标=({} , {} , {})",
                        addPkt.getType(), addPkt.getId(), addPkt.getX(), addPkt.getY(), addPkt.getZ());
            }

            try {
                // 照常执行
                ((Packet<net.minecraft.network.protocol.game.ClientGamePacketListener>) subPacket).handle(listener);

                // 🌟 探针 2：验证是否被静默丢弃
                if (subPacket instanceof net.minecraft.network.protocol.game.ClientboundAddEntityPacket addPkt) {
                    LOGGER.warn("[实体探针] 实体 ID: {} 的 handle() 方法已无异常执行完毕!", addPkt.getId());
                }
            } catch (Exception e) {
                // 🌟 探针 3：抓出任何把实体包搞崩溃的罪魁祸首
                LOGGER.error("[实体探针] ❌ 处理包 {} 时发生致命异常!", subPacket.getClass().getSimpleName(), e);
            }
        }
        ci.cancel();
    }
}
