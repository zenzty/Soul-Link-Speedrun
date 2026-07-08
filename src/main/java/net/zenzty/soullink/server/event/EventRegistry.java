package net.zenzty.soullink.server.event;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelData;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.common.SoulLinkConstants;
import net.zenzty.soullink.server.health.SharedJumpHandler;
import net.zenzty.soullink.server.health.SharedStatsHandler;
import net.zenzty.soullink.server.manhunt.CompassTrackingHandler;
import net.zenzty.soullink.server.manhunt.ManhuntManager;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.run.RunState;
import net.zenzty.soullink.server.settings.Settings;
import net.zenzty.soullink.server.settings.SettingsPersistence;

/**
 * Registers all Fabric events: server lifecycle, player connections, tick updates, entity events.
 */
public class EventRegistry {

    private static class DelayedTask {
        int remainingTicks;
        final Runnable task;

        DelayedTask(int remainingTicks, Runnable task) {
            this.remainingTicks = remainingTicks;
            this.task = task;
        }
    }

    // Track delayed tasks (list of tasks with remaining ticks)
    private static final List<DelayedTask> DELAYED_TASKS = new ArrayList<>();

    /**
     * Registers all events for the SoulLink mod.
     */
    public static void registerAll() {
        registerServerEvents();
        registerConnectionEvents();
        registerTickEvents();
        registerEntityEvents();
        registerUseEvents();
        CompassTrackingHandler.register();
    }

    /**
     * Block/item use events. Delayed sync (UseBlockCallback/UseItemCallback + scheduleDelayed) was
     * causing "invalid player data" when the task ran during disconnect/save. Disabled; block
     * placement sync is best fixed by hooking the exact place vanilla consumes the item.
     */
    private static void registerUseEvents() {
        // No delayed sync - causes invalid player data when player disconnects or saves.
    }

    /**
     * Registers server lifecycle events for initialization and cleanup.
     */
    private static void registerServerEvents() {
        // Server started - initialize RunManager and load persisted settings
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SoulLink.LOGGER.info("Server started - initializing RunManager");
            RunManager.init(server);
            SettingsPersistence.load(server);
            ManhuntManager.getInstance().resetRoles();
            ManhuntManager.getInstance().cleanupTeams(server);
        });

        // Server stopping - save settings, then cleanup worlds
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SoulLink.LOGGER.info("Server stopping - saving settings and cleaning up temporary worlds");
            SettingsPersistence.save(server);
            DELAYED_TASKS.clear(); // Clear pending tasks
            RunManager.cleanup();
        });
    }

    /**
     * Registers player connection events for player connections and disconnects.
     */
    private static void registerConnectionEvents() {
        // Player joins - show welcome or handle late join / reconnection
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();

            RunManager runManager;
            try {
                runManager = RunManager.getInstance();
            } catch (IllegalStateException e) {
                return;
            }

            if (runManager == null) {
                return;
            }

            // IMMEDIATELY teleport if IDLE to prevent suffocation damage in vanilla spawn
            if (runManager.getGameState() == RunState.IDLE) {
                runManager.teleportToVanillaSpawn(player);
            }

            // Delay other handling to ensure player is fully loaded
            scheduleDelayed(10, () -> {
                // Return early if player has disconnected in the meantime
                if (player.isRemoved()) {
                    return;
                }

                RunState state = runManager.getGameState();

                switch (state) {
                    case IDLE:
                        sendWelcomeMessage(player);
                        break;

                    case GENERATING_WORLD:
                    case RUNNING:
                        // --- UPDATED RECONNECTION LOGIC ---
                        ServerLevel playerWorld = player.level();
                        if (playerWorld == null) {
                            return;
                        }

                        // If the player logged back into the Vanilla Overworld but a run is active,
                        // this means the server safely dropped them here during reboot. We need to
                        // pull them back into the Fantasy Dimension!
                        if (!runManager.isTemporaryWorld(playerWorld.dimension())) {
                            SoulLink.LOGGER.info(
                                    "Reconnecting player detected: {} - teleporting back to run",
                                    player.getName().getString());
                            runManager.teleportPlayerToRun(player);
                        }
                        break;

                    case GAMEOVER:
                        player.sendSystemMessage(
                                RunManager.formatMessage("Run has ended. Use /start to begin a new run."));
                        break;
                }
            });
        });

        // Player disconnects - log for debugging
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();

            RunManager runManager;
            try {
                runManager = RunManager.getInstance();
            } catch (IllegalStateException e) {
                return;
            }

            if (runManager != null && runManager.isRunActive()) {
                SoulLink.LOGGER.info(
                        "Player {} disconnected during active run",
                        player.getName().getString());
            }
        });
    }

    /**
     * Sends the welcome message to a player explaining the mod.
     */
    private static void sendWelcomeMessage(ServerPlayer player) {
        // Title - Show beta version info only if version contains "beta"
        var container = FabricLoader.getInstance().getModContainer(SoulLinkConstants.MOD_ID);
        if (container.isPresent()) {
            String version = container.get().getMetadata().getVersion().getFriendlyString();
            if (version.contains("beta")) {
                player.sendSystemMessage(Component.empty()
                        .append(Component.literal("SOUL LINK SPEEDRUN - BETA RELEASE " + version)
                                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
            } else {
                player.sendSystemMessage(Component.empty()
                        .append(Component.literal("SOUL LINK SPEEDRUN")
                                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
            }
        } else {
            player.sendSystemMessage(Component.empty()
                    .append(Component.literal("SOUL LINK SPEEDRUN")
                            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        }

        // Empty line
        player.sendSystemMessage(Component.empty());

        // Soul Link info
        player.sendSystemMessage(Component.empty()
                .append(Component.literal("❤ ").withStyle(ChatFormatting.RED))
                .append(Component.literal("Soul Link").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" - All players share health and hunger.")
                        .withStyle(ChatFormatting.GRAY)));

        // Goal info
        player.sendSystemMessage(Component.empty()
                .append(Component.literal("⚔ ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("Goal").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" - Defeat the Ender Dragon together.")
                        .withStyle(ChatFormatting.GRAY)));

        // Death info
        player.sendSystemMessage(Component.empty()
                .append(Component.literal("☠ ").withStyle(ChatFormatting.DARK_RED))
                .append(Component.literal("Death").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" - If anyone dies, the run ends for all.")
                        .withStyle(ChatFormatting.GRAY)));

        // Empty line
        player.sendSystemMessage(Component.empty());

        // Start command
        player.sendSystemMessage(Component.empty()
                .append(Component.literal("Use ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/start").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" or ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("click here")
                        .setStyle(Style.EMPTY
                                .withColor(ChatFormatting.BLUE)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand("/start"))
                                .withHoverEvent(new HoverEvent.ShowText(
                                        Component.literal("Start a new run").withStyle(ChatFormatting.GRAY)))))
                .append(Component.literal(" to begin.").withStyle(ChatFormatting.GRAY)));

        // Empty line
        player.sendSystemMessage(Component.empty());

        // Settings tip
        player.sendSystemMessage(Component.empty()
                .append(Component.literal("TIP: ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("Customize your next run with ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/chaos")
                        .setStyle(Style.EMPTY
                                .withColor(ChatFormatting.GOLD)
                                .withClickEvent(new ClickEvent.RunCommand("/chaos"))
                                .withHoverEvent(new HoverEvent.ShowText(
                                        Component.literal("Open run options").withStyle(ChatFormatting.GRAY)))))
                .append(Component.literal(".").withStyle(ChatFormatting.GRAY)));

        player.sendSystemMessage(Component.empty()
                .append(Component.literal("Having troubles? ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/settings")
                        .setStyle(Style.EMPTY
                                .withColor(ChatFormatting.AQUA)
                                .withClickEvent(new ClickEvent.RunCommand("/settings"))
                                .withHoverEvent(new HoverEvent.ShowText(
                                        Component.literal("Open info settings").withStyle(ChatFormatting.GRAY))))));
    }

    /**
     * Registers tick events for timer updates and periodic sync.
     */
    private static void registerTickEvents() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            processDelayedTasks(server);

            RunManager runManager;
            try {
                runManager = RunManager.getInstance();
            } catch (IllegalStateException e) {
                return;
            }

            if (runManager != null) {
                runManager.tick();
                if (runManager.isRunActive() && Settings.getInstance().isManhuntMode()) {
                    CompassTrackingHandler.tick(server);
                }
            }

            SharedJumpHandler.processJumpsAtTickEnd(server);
            SharedStatsHandler.tickSync(server);
        });
    }

    /**
     * Registers entity events for death handling and dragon victory.
     */
    private static void registerEntityEvents() {
        // Handle entity death - check for dragon
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof EnderDragon dragon) {
                RunManager runManager;
                try {
                    runManager = RunManager.getInstance();
                } catch (IllegalStateException e) {
                    return;
                }

                if (runManager == null || !runManager.isRunActive()) {
                    return;
                }

                if (dragon.level() instanceof ServerLevel dragonWorld
                        && runManager.isTemporaryWorld(dragonWorld.dimension())) {
                    SoulLink.LOGGER.info("Ender Dragon killed in temporary End - triggering victory!");
                    runManager.triggerVictory();
                }
            }
        });

        // Handle damage - intercept lethal damage to prevent death screen
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayer player)) {
                return true;
            }

            RunManager runManager;
            try {
                runManager = RunManager.getInstance();
            } catch (IllegalStateException e) {
                return true;
            }

            if (runManager == null) {
                return true;
            }

            // Block ALL damage during game over state
            if (runManager.isGameOver()) {
                return false;
            }

            if (!runManager.isRunActive()) {
                return true;
            }

            if (SharedStatsHandler.isSyncing()) {
                return true;
            }

            if (player.isBlocking()) {
                return true;
            }

            // Allow all damage through - the actual death check happens in ServerPlayerEntityMixin
            return true;
        });

        // After damage is applied
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
            if (!(entity instanceof ServerPlayer player)) {
                return;
            }

            RunManager runManager;
            try {
                runManager = RunManager.getInstance();
            } catch (IllegalStateException e) {
                return;
            }

            if (runManager == null || !runManager.isRunActive()) {
                return;
            }

            if (damageTaken <= 0) {
                return;
            }

            if (player.getHealth() <= 0) {
                SoulLink.LOGGER.warn(
                        "Player {} reached 0 health despite mixin check - triggering death handler",
                        player.getName().getString());
                if (Settings.getInstance().isManhuntMode()
                        && ManhuntManager.getInstance().isHunter(player)) {
                    handleHunterDeath(player, source, runManager);
                } else {
                    handlePlayerDeath(player, source, runManager);
                }
                return;
            }

            ServerLevel playerWorld = player.level();
            if (playerWorld == null) {
                return;
            }

            if (!runManager.isTemporaryWorld(playerWorld.dimension())) {
                return;
            }

            if (Settings.getInstance().isManhuntMode()
                    && ManhuntManager.getInstance().isHunter(player)) {
                return;
            }

            SharedStatsHandler.onPlayerHealthChanged(player, player.getHealth(), source);
        });
    }

    /**
     * Handles player death logic (broadcast message, reset health, trigger game over).
     */
    private static void handlePlayerDeath(ServerPlayer player, DamageSource source, RunManager runManager) {
        Component deathMessage = source.getLocalizedDeathMessage(player);
        Component formattedDeathMessage = Component.empty()
                .append(RunManager.getPrefix())
                .append(Component.literal("☠ ").withStyle(ChatFormatting.DARK_RED))
                .append(deathMessage.copy().withStyle(ChatFormatting.RED));
        runManager.getServer().getPlayerList().broadcastSystemMessage(formattedDeathMessage, false);

        player.setHealth(player.getMaxHealth());
        runManager.triggerGameOver();
    }

    /**
     * Handles Hunter death in Manhunt
     */
    public static void handleHunterDeath(ServerPlayer player, DamageSource source, RunManager runManager) {
        MinecraftServer server = runManager.getServer();
        if (server == null) return;

        Component deathMessage = source.getLocalizedDeathMessage(player);
        Component formatted = Component.empty()
                .append(RunManager.getPrefix())
                .append(Component.literal("☠ ").withStyle(ChatFormatting.DARK_RED))
                .append(deathMessage.copy().withStyle(ChatFormatting.RED));
        server.getPlayerList().broadcastSystemMessage(formatted, false);

        List<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>> toRemove =
                player.getActiveEffects().stream()
                        .filter(e -> !e.getEffect().value().isBeneficial())
                        .map(e -> e.getEffect())
                        .toList();
        toRemove.forEach(player::removeEffect);

        player.setGameMode(GameType.SPECTATOR);

        ServerLevel world = player.level();
        double x = player.getX(), y = player.getY(), z = player.getZ();

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && !stack.is(Items.COMPASS)) {
                ItemEntity ent = new ItemEntity(world, x, y, z, stack.copy());
                ent.setDeltaMovement(
                        world.getRandom().nextGaussian() * 0.05,
                        world.getRandom().nextGaussian() * 0.05 + 0.2,
                        world.getRandom().nextGaussian() * 0.05);
                world.addFreshEntity(ent);
            }
        }

        player.getInventory().clearContent();
        player.getEnderChestInventory().clearContent();

        for (int i = 5; i >= 1; i--) {
            final int c = i;
            scheduleDelayed((5 - i) * 20, () -> {
                if (player.isRemoved() || !runManager.isRunActive()) return;
                player.connection.send(new ClientboundSetTitleTextPacket(
                        Component.literal(String.valueOf(c)).withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
                player.connection.send(new ClientboundSetSubtitleTextPacket(
                        Component.literal("Respawning...").withStyle(ChatFormatting.GRAY)));
            });
        }

        scheduleDelayed(5 * 20, () -> {
            if (player.isRemoved() || !runManager.isRunActive()) return;

            player.connection.send(new ClientboundSetTitleTextPacket(
                    Component.literal("RESPAWN").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));

            player.setGameMode(GameType.SURVIVAL);

            ServerLevel targetWorld = runManager.getTemporaryOverworld();
            BlockPos targetPos = runManager.getSpawnPos();

            ServerPlayer.RespawnConfig resp = player.getRespawnConfig();
            if (resp != null) {
                var data = resp.respawnData();
                if (data != null && runManager.isTemporaryWorld(data.dimension())) {
                    ServerLevel sw = server.getLevel(data.dimension());
                    if (sw != null) {
                        targetWorld = sw;
                        targetPos = data.pos();
                    }
                }
            }

            if (targetWorld != null && targetPos != null) {
                LevelData.RespawnData sp = LevelData.RespawnData.of(targetWorld.dimension(), targetPos, 0.0f, 0.0f);
                player.setRespawnPosition(new ServerPlayer.RespawnConfig(sp, true), false);
                player.teleportTo(
                        targetWorld,
                        targetPos.getX() + 0.5,
                        targetPos.getY(),
                        targetPos.getZ() + 0.5,
                        Set.of(),
                        0.0f,
                        0.0f,
                        true);
            }

            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5.0f);

            CompassTrackingHandler.giveTrackingCompass(player);

            SoulLink.LOGGER.info(
                    "Hunter {} respawned after death", player.getName().getString());
        });
    }

    /**
     * Schedule a task to run after a delay in ticks.
     */
    public static void scheduleDelayed(int delayTicks, Runnable task) {
        DELAYED_TASKS.add(new DelayedTask(delayTicks, task));
    }

    /**
     * Clears all pending delayed tasks.
     */
    public static void clearDelayedTasks() {
        DELAYED_TASKS.clear();
    }

    /**
     * Process any delayed tasks that are ready to run.
     */
    private static void processDelayedTasks(MinecraftServer server) {
        Iterator<DelayedTask> iterator = DELAYED_TASKS.iterator();
        while (iterator.hasNext()) {
            DelayedTask task = iterator.next();
            task.remainingTicks--;
            if (task.remainingTicks <= 0) {
                try {
                    task.task.run();
                } catch (Exception e) {
                    SoulLink.LOGGER.error("Error running delayed task", e);
                }
                iterator.remove();
            }
        }
    }
}
