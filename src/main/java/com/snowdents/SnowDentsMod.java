package com.snowdents;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SnowBlock;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Snow Dents: следы в снегу из ванильных блоков.
 * Мод ничего не рендерит сам, поэтому работает с любыми шейдерами и на серверах.
 */
public class SnowDentsMod implements ModInitializer {

	// ---------- НАСТРОЙКИ ----------
	/** Радиус вокруг игроков, в котором существа оставляют следы. */
	private static final double RADIUS = 48.0;
	/** Через сколько блоков пути появляется новый след. */
	private static final double STEP_DISTANCE = 0.65;
	/** Если слой снега один, убирать его совсем (виден след на земле). */
	private static final boolean REMOVE_THIN_SNOW = true;
	/** Сколько тиков восстанавливается один слой снега. */
	private static final int RECOVER_TICKS = 2400;
	/** То же самое во время дождя/снегопада. */
	private static final int RECOVER_TICKS_PRECIP = 400;
	/** Следы оставляют и игроки. */
	private static final boolean PLAYERS_LEAVE_DENTS = true;
	// --------------------------------

	private static final class Dent {
		final int originalLayers;
		long restoreAt;
		Dent(int originalLayers, long restoreAt) {
			this.originalLayers = originalLayers;
			this.restoreAt = restoreAt;
		}
	}

	private final Map<RegistryKey<World>, Map<BlockPos, Dent>> dents = new HashMap<>();
	private final Map<UUID, Vec3d> lastStep = new HashMap<>();

	@Override
	public void onInitialize() {
		ServerTickEvents.END_WORLD_TICK.register(this::onWorldTick);
	}

	private void onWorldTick(ServerWorld world) {
		Map<BlockPos, Dent> worldDents = dents.computeIfAbsent(world.getRegistryKey(), k -> new HashMap<>());

		// 1. Новые следы
		Set<LivingEntity> seen = new HashSet<>();
		for (ServerPlayerEntity player : world.getPlayers()) {
			Box area = player.getBoundingBox().expand(RADIUS);
			seen.addAll(world.getEntitiesByClass(LivingEntity.class, area, e -> true));
		}
		for (LivingEntity entity : seen) {
			handleEntity(world, worldDents, entity);
		}
		if (lastStep.size() > 4000) {
			lastStep.clear();
		}

		// 2. Восстановление снега
		if (world.getTime() % 20 == 0 && !worldDents.isEmpty()) {
			recover(world, worldDents);
		}
	}

	private void handleEntity(ServerWorld world, Map<BlockPos, Dent> worldDents, LivingEntity entity) {
		if (!entity.isOnGround() || entity.isSpectator() || entity.isSneaking() || entity.isTouchingWater()) {
			return;
		}
		if (entity instanceof PlayerEntity && !PLAYERS_LEAVE_DENTS) {
			return;
		}

		Vec3d now = new Vec3d(entity.getX(), 0, entity.getZ());
		Vec3d last = lastStep.get(entity.getUuid());
		if (last == null) {
			lastStep.put(entity.getUuid(), now);
			return;
		}
		if (now.squaredDistanceTo(last) < STEP_DISTANCE * STEP_DISTANCE) {
			return;
		}
		lastStep.put(entity.getUuid(), now);

		BlockPos pos = BlockPos.ofFloored(entity.getX(), entity.getY() + 0.01, entity.getZ());
		BlockState state = world.getBlockState(pos);
		if (!state.isOf(Blocks.SNOW)) {
			return;
		}

		int layers = state.get(SnowBlock.LAYERS);
		BlockPos key = pos.toImmutable();
		long time = world.getTime();
		int recover = world.isRaining() ? RECOVER_TICKS_PRECIP : RECOVER_TICKS;

		if (layers >= 2) {
			world.setBlockState(pos, state.with(SnowBlock.LAYERS, layers - 1), Block.NOTIFY_ALL);
		} else if (REMOVE_THIN_SNOW) {
			world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
		} else {
			return;
		}

		Dent existing = worldDents.get(key);
		if (existing != null) {
			existing.restoreAt = time + recover;
		} else {
			worldDents.put(key, new Dent(layers, time + recover));
		}
	}

	private void recover(ServerWorld world, Map<BlockPos, Dent> worldDents) {
		long time = world.getTime();
		int interval = world.isRaining() ? RECOVER_TICKS_PRECIP : RECOVER_TICKS;

		Iterator<Map.Entry<BlockPos, Dent>> it = worldDents.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<BlockPos, Dent> entry = it.next();
			BlockPos pos = entry.getKey();
			Dent dent = entry.getValue();

			if (time < dent.restoreAt) {
				continue;
			}
			if (!world.isChunkLoaded(pos)) {
				continue;
			}

			BlockState state = world.getBlockState(pos);
			int current;
			if (state.isAir()) {
				current = 0;
			} else if (state.isOf(Blocks.SNOW)) {
				current = state.get(SnowBlock.LAYERS);
			} else {
				it.remove(); // блок заменили, забываем
				continue;
			}

			int next = current + 1;
			if (next > dent.originalLayers || next > 8) {
				it.remove();
				continue;
			}

			BlockState newState = Blocks.SNOW.getDefaultState().with(SnowBlock.LAYERS, next);
			if (!newState.canPlaceAt(world, pos)) {
				it.remove();
				continue;
			}
			world.setBlockState(pos, newState, Block.NOTIFY_ALL);

			if (next >= dent.originalLayers) {
				it.remove();
			} else {
				dent.restoreAt = time + interval;
			}
		}
	}
}
