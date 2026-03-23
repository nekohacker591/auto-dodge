package com.example.autododge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AutoDodgeSystem {
    private static final int SAFE_SEARCH_RADIUS = 40;
    private static final int SAFE_SEARCH_DEPTH = 40;
    private static final int MIN_SUPPORT_DEPTH = 10;
    private static final int RECURSION_TICKS = 6;

    private static final GameRules.Key<GameRules.BooleanValue> HOST_ONLY = GameRules.register(
            "autoDodgeHostOnly", GameRules.Category.PLAYER, GameRules.BooleanValue.create(false));
    private static final GameRules.Key<GameRules.BooleanValue> OPS_ONLY = GameRules.register(
            "autoDodgeOperatorsOnly", GameRules.Category.PLAYER, GameRules.BooleanValue.create(false));
    private static final GameRules.Key<GameRules.BooleanValue> TELEPORT_VISUAL = GameRules.register(
            "autoDodgeTeleport", GameRules.Category.PLAYER, GameRules.BooleanValue.create(true));

    private final Map<UUID, SafeLocation> lastSafeLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> teleportCooldowns = new ConcurrentHashMap<>();
    private final Set<UUID> activeResolution = ConcurrentHashMap.newKeySet();

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }

        tickCooldown(player.getUUID());

        if (player.isCreative() || player.isSpectator()) {
            return;
        }

        if (player.onGround() && isStandingSafely(player.serverLevel(), player.blockPosition()) && !player.isInLava() && !player.isUnderWater()) {
            lastSafeLocations.put(player.getUUID(), new SafeLocation(player.position(), player.getDeltaMovement()));
        }
    }

    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        if (event.getEntity() instanceof ServerPlayer newPlayer) {
            SafeLocation old = lastSafeLocations.get(event.getOriginal().getUUID());
            if (old != null) {
                lastSafeLocations.put(newPlayer.getUUID(), old);
            }
        }
    }

    @SubscribeEvent
    public void onLivingAttack(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (!shouldProtect(player)) {
            return;
        }

        event.setCanceled(true);
        if (!activeResolution.add(player.getUUID())) {
            return;
        }

        try {
            resolveAttack(player, event.getSource());
        } finally {
            activeResolution.remove(player.getUUID());
        }
    }

    private void resolveAttack(ServerPlayer player, DamageSource source) {
        if (teleportCooldowns.getOrDefault(player.getUUID(), 0) > 0) {
            return;
        }

        ServerLevel level = player.serverLevel();
        Vec3 originalVelocity = player.getDeltaMovement();
        SafeLocation remembered = lastSafeLocations.get(player.getUUID());
        Vec3 target = chooseDestination(player, source, remembered);
        if (target == null) {
            target = remembered != null ? remembered.position() : Vec3.atBottomCenterOf(player.blockPosition());
        }

        if (!level.getGameRules().getBoolean(TELEPORT_VISUAL)) {
            return;
        }

        player.teleportTo(target.x, target.y, target.z);
        player.setDeltaMovement(originalVelocity);
        player.hurtMarked = true;
        level.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
        level.gameEvent(GameEvent.TELEPORT, player.position(), GameEvent.Context.of(player));
        teleportCooldowns.put(player.getUUID(), RECURSION_TICKS);
    }

    private Vec3 chooseDestination(ServerPlayer player, DamageSource source, SafeLocation remembered) {
        ServerLevel level = player.serverLevel();
        Entity direct = source.getDirectEntity();
        Entity attacker = source.getEntity();

        if (source.is(DamageTypeTags.IS_DROWNING)) {
            Vec3 surfacing = findWaterEscape(player);
            if (surfacing != null) return surfacing;
        }

        if (source.is(DamageTypeTags.IS_FALL)) {
            Vec3 below = findSafeBelow(player, remembered);
            if (below != null) return below;
        }

        if (source.is(DamageTypeTags.IS_FIRE) || source.is(DamageTypeTags.IS_EXPLOSION)) {
            Vec3 weighted = findWeightedSafeSpot(level, player.blockPosition(), source.is(DamageTypeTags.IS_EXPLOSION) ? 12 : 6);
            if (weighted != null) return weighted;
        }

        if (attacker instanceof LivingEntity livingAttacker && !shouldSkipCounterTeleport(livingAttacker)) {
            Vec3 behind = findBehindTarget(player, livingAttacker);
            if (behind != null) return behind;
        }

        if (direct instanceof Projectile projectile) {
            Vec3 away = player.position().subtract(projectile.position());
            Vec3 direction = away.lengthSqr() > 1.0E-4 ? away.normalize() : velocityFacing(player);
            Vec3 forward = player.position().add(direction.scale(1.25D)).add(velocityFacing(player));
            Vec3 snapped = snapToSafeStand(level, BlockPos.containing(forward));
            if (snapped != null) return snapped;
        }

        if (remembered != null) {
            return remembered.position();
        }

        return findWeightedSafeSpot(level, player.blockPosition(), 2);
    }

    private boolean shouldSkipCounterTeleport(LivingEntity attacker) {
        return attacker instanceof ServerPlayer attackerPlayer && shouldProtect(attackerPlayer);
    }

    private Vec3 findBehindTarget(ServerPlayer player, LivingEntity attacker) {
        Vec3 look = attacker.getLookAngle();
        if (look.lengthSqr() < 1.0E-4) {
            look = player.position().subtract(attacker.position());
        }
        if (look.lengthSqr() < 1.0E-4) {
            look = new Vec3(0.0D, 0.0D, 1.0D);
        }
        Vec3 candidate = attacker.position().subtract(look.normalize().scale(1.5D));
        return snapToSafeStand(player.serverLevel(), BlockPos.containing(candidate));
    }

    private Vec3 findWaterEscape(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int y = origin.getY(); y < level.getMaxBuildHeight(); y++) {
            cursor.set(origin.getX(), y, origin.getZ());
            if (level.canSeeSky(cursor) && level.getBlockState(cursor).isAir() && level.getBlockState(cursor.above()).isAir()) {
                return Vec3.atBottomCenterOf(cursor);
            }
        }

        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (isThreeByThreeAir(level, pos) && isSafeFloor(level, pos.below())) {
                        return Vec3.atBottomCenterOf(pos);
                    }
                }
            }
        }
        return null;
    }

    private boolean isThreeByThreeAir(ServerLevel level, BlockPos center) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!level.getBlockState(center.offset(dx, 0, dz)).isAir() || !level.getBlockState(center.offset(dx, 1, dz)).isAir()) {
                    return false;
                }
            }
        }
        return true;
    }

    private Vec3 findSafeBelow(ServerPlayer player, SafeLocation remembered) {
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        for (int y = origin.getY(); y >= level.getMinBuildHeight(); y--) {
            Vec3 safe = snapToSafeStand(level, new BlockPos(origin.getX(), y, origin.getZ()));
            if (safe != null) {
                return safe;
            }
        }
        return remembered != null ? remembered.position() : null;
    }

    private Vec3 findWeightedSafeSpot(ServerLevel level, BlockPos origin, int minDistance) {
        Map<Block, Double> weights = new HashMap<>();
        weights.put(Blocks.GRASS_BLOCK, 0.0D);
        weights.put(Blocks.STONE, 1.0D);
        weights.put(Blocks.SAND, 2.0D);
        weights.put(Blocks.BEDROCK, 3.0D);

        return BlockPos.betweenClosedStream(
                        origin.offset(-SAFE_SEARCH_RADIUS, -SAFE_SEARCH_DEPTH, -SAFE_SEARCH_RADIUS),
                        origin.offset(SAFE_SEARCH_RADIUS, SAFE_SEARCH_DEPTH, SAFE_SEARCH_RADIUS))
                .map(BlockPos::immutable)
                .filter(pos -> pos.distManhattan(origin) >= minDistance)
                .filter(pos -> weights.containsKey(level.getBlockState(pos).getBlock()))
                .filter(pos -> hasSupportDepth(level, pos, MIN_SUPPORT_DEPTH))
                .filter(pos -> level.getBlockState(pos.above()).isAir() && level.getBlockState(pos.above(2)).isAir())
                .min(Comparator.comparingDouble(pos -> pos.distSqr(origin) + (weights.get(level.getBlockState(pos).getBlock()) * 16.0D)))
                .map(pos -> Vec3.atBottomCenterOf(pos.above()))
                .orElse(null);
    }

    private boolean hasSupportDepth(ServerLevel level, BlockPos pos, int depth) {
        for (int i = 0; i < depth; i++) {
            if (level.getBlockState(pos.below(i)).isAir()) {
                return false;
            }
        }
        return true;
    }

    private Vec3 snapToSafeStand(ServerLevel level, BlockPos pos) {
        for (int dy = -2; dy <= 2; dy++) {
            BlockPos stand = pos.offset(0, dy, 0);
            if (level.getBlockState(stand).isAir() && level.getBlockState(stand.above()).isAir() && isSafeFloor(level, stand.below())) {
                return Vec3.atBottomCenterOf(stand);
            }
        }
        return null;
    }

    private boolean isSafeFloor(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.isCollisionShapeFullBlock(level, pos) && !state.is(BlockTags.FIRE) && state.getFluidState().isEmpty();
    }

    private boolean isStandingSafely(ServerLevel level, BlockPos pos) {
        return snapToSafeStand(level, pos) != null;
    }

    private boolean shouldProtect(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (level.getGameRules().getBoolean(HOST_ONLY)) {
            return player.getServer() != null
                    && player.getServer().isSingleplayer()
                    && player.getServer().getSingleplayerProfile() != null
                    && player.getUUID().equals(player.getServer().getSingleplayerProfile().getId());
        }
        if (level.getGameRules().getBoolean(OPS_ONLY)) {
            return player.getServer() != null && player.getServer().getPlayerList().isOp(player.getGameProfile());
        }
        return true;
    }

    private void tickCooldown(UUID playerId) {
        teleportCooldowns.computeIfPresent(playerId, (id, ticks) -> ticks <= 1 ? null : ticks - 1);
    }

    private Vec3 velocityFacing(ServerPlayer player) {
        Vec3 velocity = player.getDeltaMovement();
        if (velocity.lengthSqr() > 1.0E-4) {
            return velocity.normalize();
        }
        Direction direction = Direction.fromYRot(player.getYRot());
        return new Vec3(direction.getStepX(), 0.0D, direction.getStepZ());
    }

    private record SafeLocation(Vec3 position, Vec3 velocity) {
    }
}
