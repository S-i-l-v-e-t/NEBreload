package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthLegacyConfig;
import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
import cn.ussshenzhou.notenoughbandwidth.util.PacketUtil;
import io.netty.channel.local.LocalAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.net.SocketAddress;

/**
 * @author USS_Shenzhou
 */
@Mixin(value = Connection.class, priority = 1)
public abstract class ConnectionMixin {

    @Shadow
    @Nullable
    private volatile PacketListener packetListener;


    @Shadow
    public abstract void send(Packet<?> packet, @Nullable PacketSendListener listener);

    @Shadow
    public abstract SocketAddress getRemoteAddress();

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"), cancellable = true)
    private void nebwPacketAggregate(Packet<?> packet, @Nullable PacketSendListener listener, CallbackInfo ci) {
         // :< due to ysm is a closed source mod i cannot figure out where goes wrong cause the syncing process hanging, so just hardcoding to skip it
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket customPacket) {

            net.minecraft.resources.ResourceLocation id = customPacket.getIdentifier();
            String namespace = id.getNamespace();
            if (namespace.equals("ysm") || namespace.equals("yes_steve_model")) {
                return;
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket customPacket) {
            net.minecraft.resources.ResourceLocation id = customPacket.getIdentifier();
            String namespace = id.getNamespace();

            if (namespace.equals("ysm") || namespace.equals("yes_steve_model")) {
                return;
            }
        }
        // only work on play
        if (this.getRemoteAddress() instanceof LocalAddress) {
            return;
        }
        if (this.packetListener == null) {
            return;
        }
        // de-bundle: in Forge 1.20.1 BundlePacket exists as a vanilla concept
        // Attempt to detect bundle packets via class name (may not exist on all Forge 1.20.1 builds)
        if (packet instanceof net.minecraft.network.protocol.BundlePacket<?> bundlePacket) {
            for (Packet<?> p : bundlePacket.subPackets()) {
                this.send(p, listener);
            }
            ci.cancel();
            return;
        }
        if (!isPlayPacket(packet)) {
            return;
        }
        var packetType = PacketUtil.getTrueType(packet);
        // compatibility and avoid infinite loop
        if (NotEnoughBandwidthLegacyConfig.skipType(packetType.toString())) {
            // flush to ensure packet order
            AggregationManager.flushConnection((Connection) (Object) this);
            return;
        }
        AggregationManager.takeOver(packet, (Connection) (Object) this);
        ci.cancel();
    }


    private static boolean isPlayPacket(Packet<?> packet) {
        return ConnectionProtocol.getProtocolForPacket(packet) == ConnectionProtocol.PLAY;
    }

}
