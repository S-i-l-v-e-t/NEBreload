package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthLegacyConfig;
import cn.ussshenzhou.notenoughbandwidth.indextype.CustomPacketPrefixHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * @author USS_Shenzhou
 */
@Mixin({ClientboundCustomPayloadPacket.class, ServerboundCustomPayloadPacket.class})
public class CustomPacketPayloadMixin {

    @Redirect(method = "write(Lnet/minecraft/network/FriendlyByteBuf;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/FriendlyByteBuf;writeResourceLocation(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/network/FriendlyByteBuf;"))
    private FriendlyByteBuf nebwIndexedHeaderEncode(FriendlyByteBuf targetBuf, ResourceLocation id, FriendlyByteBuf packetBuf) {
        String namespace = id.getNamespace();
        if (namespace.equals("minecraft") || namespace.equals("fml") || namespace.equals("nebl") || NotEnoughBandwidthLegacyConfig.skipType(id.toString())) {
            targetBuf.writeResourceLocation(id);
            return targetBuf;
        }
        if (NotEnoughBandwidthLegacyConfig.skipType(id.toString())) {
            targetBuf.writeResourceLocation(id);
            return targetBuf;
        }
        CustomPacketPrefixHelper.get()
                .index(id)
                .save(targetBuf);
        return targetBuf;
    }


    @Redirect(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/FriendlyByteBuf;readResourceLocation()Lnet/minecraft/resources/ResourceLocation;"))
    private ResourceLocation nebwIndexedHeaderDecode(FriendlyByteBuf targetBuf, FriendlyByteBuf packetBuf) {
        int firstByte = targetBuf.getUnsignedByte(targetBuf.readerIndex());
        if (firstByte > 0 && firstByte < 128) {
            return targetBuf.readResourceLocation();
        }
        try {
            var tryRead = new FriendlyByteBuf(targetBuf.retainedDuplicate());
            var tryType = tryRead.readResourceLocation();
            boolean isNebUnindexedDummy = (firstByte == 0 && tryType.getPath().isEmpty());
            if (!isNebUnindexedDummy) {
                String namespace = tryType.getNamespace();
                if (namespace.equals("minecraft") || namespace.equals("fml") || namespace.equals("nebl") || NotEnoughBandwidthLegacyConfig.skipType(tryType.toString())) {
                    return targetBuf.readResourceLocation();
                }
            }
            if (NotEnoughBandwidthLegacyConfig.skipType(tryType.toString())) {
                return targetBuf.readResourceLocation();
            }
        } catch (Exception ignored) {
        }
        return CustomPacketPrefixHelper.getType(targetBuf);
    }
}
