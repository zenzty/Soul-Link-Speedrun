package net.zenzty.soullink.server.run;

import java.util.Set;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.health.SharedStatsHandler;
import net.zenzty.soullink.server.settings.Settings;

/**
 * Handles player teleportation and reset logic for speedruns.
 */
public class PlayerTeleportService {

    private final MinecraftServer server;

    public PlayerTeleportService(MinecraftServer server) {
        this.server = server;
    }

    /**
     * Teleports a player to the spawn position and sets up for gameplay.
     *
     * @param player The player to teleport
     * @param world The target world
     * @param spawnPos The spawn position
     * @param timerService The timer service for input tracking
     * @param syncToShared When true, syncs to shared stats and starts timer on input. When false
     *        (hunters in Manhunt), uses vanilla mechanics.
     */
    public void teleportToSpawn(
            ServerPlayer player,
            ServerLevel world,
            BlockPos spawnPos,
            TimerService timerService,
            boolean syncToShared) {
        if (player == null || world == null || spawnPos == null || timerService == null) {
            SoulLink.LOGGER.error("Failed to teleport to spawn: null parameter(s)");
            return;
        }

        resetPlayer(player);

        player.teleportTo(world, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, Set.of(), 0, 0, true);

        if (player.connection != null) {
            player.connection.send(new ClientboundClearTitlesPacket(false));
        }

        if (syncToShared) {
            SharedStatsHandler.syncPlayerToSharedStats(player);
            timerService.beginWaitingForInput(player);
        }

        world.playSound(
                null,
                player.getX(),
                player.getY(),
                player.getZ(),
                SoundEvents.BEACON_ACTIVATE,
                SoundSource.PLAYERS,
                1.0f,
                1.5f);
    }

    public void teleportPreservingState(ServerPlayer player, ServerLevel world, BlockPos spawnPos) {
        if (spawnPos == null) {
            SoulLink.LOGGER.error("Failed to teleport preserving state: null parameter(s)");
            return;
        }
        teleportPreservingState(player, world, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0, 0);
    }

    public void teleportPreservingState(
            ServerPlayer player, ServerLevel world, double x, double y, double z, float yaw, float pitch) {
        if (player == null || world == null) {
            SoulLink.LOGGER.error("Failed to teleport preserving state: null parameter(s)");
            return;
        }

        player.teleportTo(world, x, y, z, Set.of(), yaw, pitch, true);

        if (player.connection != null) {
            player.connection.send(new ClientboundClearTitlesPacket(false));
        }
    }

    public void applyRunAttributes(ServerPlayer player, boolean halfHeart) {
        if (player == null) {
            return;
        }
        var maxHealthAttr = player.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealthAttr == null) {
            return;
        }
        maxHealthAttr.setBaseValue(halfHeart ? 1.0 : 20.0);
    }

    /**
     * Teleports a player to the vanilla overworld spawn.
     */
    public void teleportToVanillaSpawn(ServerPlayer player) {
        if (player == null || server == null) return;

        ServerLevel overworld = server.overworld();
        if (overworld == null) return;

        net.minecraft.world.level.storage.LevelData.RespawnData spawn =
                overworld.getLevelData().getRespawnData();

        if (spawn == null || spawn.globalPos() == null) {
            SoulLink.LOGGER.error("Could not find vanilla spawn point!");
            return;
        }

        BlockPos spawnPos = spawn.globalPos().pos();

        player.teleportTo(
                overworld,
                spawnPos.getX() + 0.5,
                spawnPos.getY(),
                spawnPos.getZ() + 0.5,
                Set.of(),
                player.getYRot(),
                player.getXRot(),
                true);
    }

    /**
     * Forceloads chunks around spawn for smooth teleport without blocking the main server thread.
     */
    public void forceloadSpawnChunks(ServerLevel world, BlockPos spawnPos) {
        ChunkPos chunkPos = new ChunkPos(spawnPos.getX() >> 4, spawnPos.getZ() >> 4);

        world.getChunkSource().addTicketAndLoadWithRadius(TicketType.SPAWN_SEARCH, chunkPos, 2);
    }

    /**
     * Fully resets a player for a new run.
     */
    private void resetPlayer(ServerPlayer player) {
        player.getInventory().clearContent();
        player.removeAllEffects();
        player.setExperienceLevels(0);
        player.setExperiencePoints(0);

        Settings settings = Settings.getInstance();
        var maxHealthAttr = player.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealthAttr != null) {
            if (settings.isHalfHeartMode()) {
                maxHealthAttr.setBaseValue(1.0);
                player.setHealth(1.0f);
                SoulLink.LOGGER.info(
                        "Half Heart Mode enabled for {}", player.getName().getString());
            } else {
                maxHealthAttr.setBaseValue(20.0);
                player.setHealth(player.getMaxHealth());
            }
        }

        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0f);
        player.getEnderChestInventory().clearContent();
        player.setRemainingFireTicks(0);
        player.setTicksFrozen(0);

        resetPlayerAdvancements(player);
        player.setGameMode(GameType.SURVIVAL);

        SoulLink.LOGGER.info("Reset player {} for new run", player.getName().getString());
    }

    /**
     * Resets all advancements for a player.
     */
    private void resetPlayerAdvancements(ServerPlayer player) {
        PlayerAdvancements tracker = player.getAdvancements();

        for (AdvancementHolder advancement : server.getAdvancements().getAllAdvancements()) {
            AdvancementProgress progress = tracker.getOrStartProgress(advancement);

            for (String criterion : progress.getCompletedCriteria()) {
                tracker.revoke(advancement, criterion);
            }
        }

        SoulLink.LOGGER.info(
                "Reset advancements for player {}", player.getName().getString());
    }
}
