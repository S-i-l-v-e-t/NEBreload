package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.chunk.CachedChunkTrackingView;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author USS_Shenzhou
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    public abstract DistanceManager getDistanceManager();

    @Shadow
    int viewDistance;

    private static final Map<Integer, TicketType<Integer>> NEB_CACHE_TICKETS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> NEB_INTERNAL_TRACKING = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> NEB_UPDATE_PLAYER_STATUS_ADDED = new ThreadLocal<>();

    private static TicketType<Integer> getCacheTicketType(int ticks) {
        return NEB_CACHE_TICKETS.computeIfAbsent(ticks,
                t -> TicketType.create("neb_cache_" + t, Integer::compare, t));
    }

    private static int nebCacheTicketTicks() {
        return Math.max(1, cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthLegacyConfig.get().getDccTimeoutSafeSeconds() * 20);
    }

    private static boolean nebCacheEnabled() {
        return cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthLegacyConfig.get().isDelayedChunkCachingUsable();
    }

    private void nebRemoveCacheTicket(ChunkPos pos) {
        for (Map.Entry<Integer, TicketType<Integer>> entry : NEB_CACHE_TICKETS.entrySet()) {
            getDistanceManager().removeRegionTicket(entry.getValue(), pos, 1, entry.getKey());
        }
    }

    private void nebTickChunkCache(ServerPlayer player) {
        if (!nebCacheEnabled()) {
            CachedChunkTrackingView.clear(player, pos ->
            {
                nebRemoveCacheTicket(pos);
                nebCallVanillaUpdateChunkTracking(player, pos, new MutableObject<>(), true, false);
            });
            return;
        }
        CachedChunkTrackingView.tick(player, player.chunkPosition(), pos ->
        {
            nebRemoveCacheTicket(pos);
            nebCallVanillaUpdateChunkTracking(player, pos, new MutableObject<>(), true, false);
        });
    }

    @Inject(method = "updatePlayerStatus", at = @At("HEAD"))
    private void nebBeforeUpdatePlayerStatus(ServerPlayer player, boolean added, CallbackInfo ci) {
        NEB_UPDATE_PLAYER_STATUS_ADDED.set(added);
    }

    @Inject(method = "updatePlayerStatus", at = @At("TAIL"))
    private void nebOnUpdatePlayerStatus(ServerPlayer player, boolean added, CallbackInfo ci) {
        try {
            if (!added) {
                CachedChunkTrackingView.clear(player, pos ->
                {
                    nebRemoveCacheTicket(pos);
                    nebCallVanillaUpdateChunkTracking(player, pos, new MutableObject<>(), true, false);
                });
                return;
            }
            nebTickChunkCache(player);
        } finally {
            NEB_UPDATE_PLAYER_STATUS_ADDED.remove();
        }
    }

    @Inject(method = "move", at = @At("TAIL"))
    private void nebOnMove(ServerPlayer player, CallbackInfo ci) {
        nebTickChunkCache(player);
    }

    @Inject(method = "setViewDistance", at = @At("TAIL"))
    private void nebOnSetViewDistance(int viewDistance, CallbackInfo ci) {
        for (ServerPlayer player : this.level.players()) {
            nebTickChunkCache(player);
        }
    }

    @Redirect(
            method = "updatePlayerStatus",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;updateChunkTracking(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;Lorg/apache/commons/lang3/mutable/MutableObject;ZZ)V"
            )
    )
    private void nebRedirectUpdateChunkTrackingInUpdatePlayerStatus(
            ChunkMap instance,
            ServerPlayer player,
            ChunkPos pos,
            MutableObject<ClientboundLevelChunkWithLightPacket> packetHolder,
            boolean wasInRange,
            boolean isInRange
    ) {
        if (Boolean.FALSE.equals(NEB_UPDATE_PLAYER_STATUS_ADDED.get())) {
            nebCallVanillaUpdateChunkTracking(player, pos, packetHolder, wasInRange, isInRange);
            return;
        }
        nebHandleChunkTracking(player, pos, packetHolder, wasInRange, isInRange);
    }

    @Redirect(
            method = "move",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;updateChunkTracking(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;Lorg/apache/commons/lang3/mutable/MutableObject;ZZ)V"
            )
    )
    private void nebRedirectUpdateChunkTrackingInMove(
            ChunkMap instance,
            ServerPlayer player,
            ChunkPos pos,
            MutableObject<ClientboundLevelChunkWithLightPacket> packetHolder,
            boolean wasInRange,
            boolean isInRange
    ) {
        nebHandleChunkTracking(player, pos, packetHolder, wasInRange, isInRange);
    }

    @Unique
    private static final ThreadLocal<Boolean> NEB_IN_SET_VIEW_DISTANCE = ThreadLocal.withInitial(() -> false);

    @Inject(method = "setViewDistance", at = @At("HEAD"))
    private void nebOnSetViewDistanceHead(int viewDistance, CallbackInfo ci) {
        NEB_IN_SET_VIEW_DISTANCE.set(true);
    }
    @Inject(method = "setViewDistance", at = @At("RETURN"))
    private void nebOnSetViewDistanceReturn(int viewDistance, CallbackInfo ci) {
        NEB_IN_SET_VIEW_DISTANCE.set(false);
    }
    @Inject(
            method = "updateChunkTracking",
            at = @At("HEAD"),
            cancellable = true
    )
    private void nebContextualUpdateChunkTracking(
            ServerPlayer player,
            ChunkPos pos,
            MutableObject<ClientboundLevelChunkWithLightPacket> packetHolder,
            boolean wasInRange,
            boolean isInRange,
            CallbackInfo ci
    ) {
        if (NEB_IN_SET_VIEW_DISTANCE.get()) {
            nebHandleChunkTracking(player, pos, packetHolder, wasInRange, isInRange);

            ci.cancel();
        }
    }

    @Unique
    private void nebHandleChunkTracking(
            ServerPlayer player,
            ChunkPos pos,
            MutableObject<ClientboundLevelChunkWithLightPacket> packetHolder,
            boolean wasInRange,
            boolean isInRange
    ) {
        if (NEB_INTERNAL_TRACKING.get()) {
            updateChunkTracking(player, pos, packetHolder, wasInRange, isInRange);
            return;
        }

        if (!nebCacheEnabled()) {
            nebCallVanillaUpdateChunkTracking(player, pos, packetHolder, wasInRange, isInRange);
            return;
        }

        if (player.level() != this.level) {
            return;
        }

        if (!wasInRange && isInRange) {
            if (CachedChunkTrackingView.onChunkEnter(player, pos)) {
                nebRemoveCacheTicket(pos);
                return;
            }
            nebCallVanillaUpdateChunkTracking(player, pos, packetHolder, false, true);
            return;
        }

        if (wasInRange && !isInRange) {
            if (CachedChunkTrackingView.onChunkLeave(player, pos, player.chunkPosition())) {
                int ticks = nebCacheTicketTicks();
                TicketType<Integer> type = getCacheTicketType(ticks);
                getDistanceManager().addRegionTicket(type, pos, 1, ticks);
                return;
            }
            nebCallVanillaUpdateChunkTracking(player, pos, packetHolder, true, false);
            return;
        }

        nebCallVanillaUpdateChunkTracking(player, pos, packetHolder, wasInRange, isInRange);
    }

    private void nebCallVanillaUpdateChunkTracking(
            ServerPlayer player,
            ChunkPos pos,
            MutableObject<ClientboundLevelChunkWithLightPacket> packetHolder,
            boolean wasInRange,
            boolean isInRange
    ) {
        NEB_INTERNAL_TRACKING.set(true);
        try {
            updateChunkTracking(player, pos, packetHolder, wasInRange, isInRange);
        } finally {
            NEB_INTERNAL_TRACKING.set(false);
        }
    }

    @Shadow
    protected abstract void updateChunkTracking(ServerPlayer player, ChunkPos pos, MutableObject<ClientboundLevelChunkWithLightPacket> packetHolder, boolean wasInRange, boolean isInRange);
}
