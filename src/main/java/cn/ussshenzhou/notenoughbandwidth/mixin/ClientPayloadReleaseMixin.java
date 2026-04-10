package cn.ussshenzhou.notenoughbandwidth.mixin;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientboundCustomPayloadPacket.class)
public class ClientPayloadReleaseMixin {

    @Redirect(
            method = "handle(Lnet/minecraft/network/protocol/game/ClientGamePacketListener;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/FriendlyByteBuf;release()Z")
    )
    private boolean nebwSafeReleaseClient(FriendlyByteBuf instance) {
        if (instance.refCnt() > 0) {
            return instance.release();
        }
        return false;
    }
}