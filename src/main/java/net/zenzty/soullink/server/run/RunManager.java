package net.zenzty.soullink.server.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.mixin.server.EnderDragonFightAccessor;
import net.zenzty.soullink.mixin.server.RaidAccessor;
import net.zenzty.soullink.mixin.server.RaidManagerAccessor;
import net.zenzty.soullink.server.event.EventRegistry;
import net.zenzty.soullink.server.health.SharedStatsHandler;
import net.zenzty.soullink.server.inventory.SharedInventoryHandler;
import net.zenzty.soullink.server.manhunt.CompassTrackingHandler;
import net.zenzty.soullink.server.manhunt.ManhuntManager;
import net.zenzty.soullink.server.settings.Settings;
import net.zenzty.soullink.server.settings.SettingsPersistence;

public class RunManager {

    private static volatile RunManager instance;

    private final MinecraftServer server;
    private final WorldService worldService;
    private final TimerService timerService;
    private final SpawnFinder spawnFinder;
    private final PlayerTeleportService teleportService;

    // Pool Manager
    private final WorldPoolManager poolManager;

    private volatile RunState gameState = RunState.IDLE;
    private volatile boolean endInitialized = false;
    private final Set<UUID> participants = ConcurrentHashMap.newKeySet();
    private final Map<UUID, RunSavedData.ResumeLocation> resumeLocations = new ConcurrentHashMap<>();
    private boolean runManhuntMode;
    private boolean runHalfHeartMode;

    public static Component getPrefix() {
        return Component.empty()
                .append(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("SoulLink").withStyle(ChatFormatting.RED))
                .append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY));
    }

    public static Component formatMessage(String message) {
        return Component.empty()
                .append(getPrefix())
                .append(Component.literal(message).withStyle(ChatFormatting.GRAY));
    }

    public static Component formatMessageWithPlayer(String beforePlayer, String playerName, String afterPlayer) {
        return Component.empty()
                .append(getPrefix())
                .append(Component.literal(beforePlayer).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(playerName).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(afterPlayer).withStyle(ChatFormatting.GRAY));
    }

    public static Component formatClickable(String text, String command, String hoverText) {
        return Component.literal(text)
                .setStyle(Style.EMPTY
                        .withColor(ChatFormatting.GREEN)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal(hoverText).withStyle(ChatFormatting.GRAY))));
    }

    private RunManager(MinecraftServer server) {
        this.server = server;
        this.worldService = new WorldService(server);
        this.timerService = new TimerService();
        this.spawnFinder = new SpawnFinder();
        this.teleportService = new PlayerTeleportService(server);
        this.poolManager = new WorldPoolManager(this.worldService);
    }

    public static synchronized void init(MinecraftServer server) {
        if (instance != null) {
            SoulLink.LOGGER.warn("RunManager already initialized!");
            return;
        }
        instance = new RunManager(server);
        instance.tryRestorePersistedRun();
        EventRegistry.handleExistingPlayers(server);
    }

    public static RunManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("RunManager not initialized");
        }
        return instance;
    }

    public static synchronized void cleanup() {
        RunManager currentInstance = instance;
        if (currentInstance != null) {
            currentInstance.persistOnlineParticipants();
            currentInstance.persistRunState(true);
            SharedInventoryHandler.reset();
            ManhuntManager.getInstance().cleanupTeams(currentInstance.server);
            CompassTrackingHandler.reset();
            currentInstance.poolManager.cleanup();
            UUID persistRunId = null;
            RunSavedData saved = RunSavedData.get(currentInstance.server);
            if (saved.hasPersistableRun()) {
                persistRunId = saved.activeRunId;
            }
            currentInstance.worldService.detachOrDeleteForShutdown(persistRunId);
            instance = null;
        }
    }

    public static ServerLevel getPlayerWorld(ServerPlayer player) {
        return player.level();
    }

    // ==================== RUN LIFECYCLE ====================

    public void startRun() {
        if (gameState == RunState.RUNNING || gameState == RunState.GENERATING_WORLD) {
            SoulLink.LOGGER.warn("Attempted to start run while already running or generating!");
            return;
        }

        SoulLink.LOGGER.info("Starting new run...");
        EventRegistry.clearDelayedTasks();
        participants.clear();
        resumeLocations.clear();

        if (!Settings.getInstance().isManhuntModeForNextRun()) {
            ManhuntManager.getInstance().resetRoles();
        }

        clearEnderDragonBossbar();
        clearRaidBossbars();

        SharedInventoryHandler.reset();
        endInitialized = false;
        timerService.reset();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.setGameMode(GameType.SPECTATOR);
        }

        PooledRun nextRun = poolManager.claimNextRun();
        if (nextRun != null) {
            worldService.saveCurrentWorldsAsOld();
            worldService.adoptPooledRun(nextRun);
            spawnFinder.injectSpawnPos(nextRun.spawnPos());
            SoulLink.LOGGER.info("Storage full! Start");
            transitionToRunning();
        } else {
            server.getPlayerList().broadcastSystemMessage(formatMessage("Generating world..."), true);
            gameState = RunState.GENERATING_WORLD;
            SoulLink.LOGGER.info("Pool empty! Waiting for world generation");
        }
    }

    public void tick() {
        poolManager.tick(server);

        if (gameState == RunState.GENERATING_WORLD) {
            PooledRun nextRun = poolManager.claimNextRun();
            if (nextRun != null) {
                worldService.saveCurrentWorldsAsOld();
                worldService.adoptPooledRun(nextRun);
                spawnFinder.injectSpawnPos(nextRun.spawnPos());
                transitionToRunning();
            } else if (server.getTickCount() % 10 == 0) {
                Component statusText = Component.empty()
                        .append(Component.literal("⟳ ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal("Generating new world...").withStyle(ChatFormatting.GRAY));
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    player.sendOverlayMessage(statusText);
                }
            }
            return;
        }

        if (gameState != RunState.RUNNING) {
            return;
        }

        timerService.tick(server, this::isInRun, this::shouldSkipTimerActionBarFor);
        if (server.getTickCount() % 20 == 0) {
            persistRunState();
        }
    }

    private boolean shouldSkipTimerActionBarFor(ServerPlayer p) {
        if (!runManhuntMode) return false;
        if (!ManhuntManager.getInstance().isHunter(p)) return false;
        return CompassTrackingHandler.shouldSuppressTimerActionBar(p.getUUID(), server.getTickCount());
    }

    private void transitionToRunning() {
        ServerLevel overworld = worldService.getOverworld();
        BlockPos spawnPos = spawnFinder.getSpawnPos();

        if (overworld == null) return;
        if (spawnPos == null) spawnPos = new BlockPos(0, 64, 0);

        Settings.getInstance().applyPendingSettings();
        SettingsPersistence.save(server);
        runManhuntMode = Settings.getInstance().isManhuntMode();
        runHalfHeartMode = Settings.getInstance().isHalfHeartMode();
        SharedStatsHandler.reset();

        worldService.resetWeatherForNewRun(overworld);
        worldService.resetTimeForNewRun();
        teleportService.forceloadSpawnChunks(overworld, spawnPos);

        gameState = RunState.RUNNING;

        boolean manhunt = runManhuntMode;
        ManhuntManager manhuntManager = ManhuntManager.getInstance();

        if (manhunt) {
            disableLocatorBar();
            manhuntManager.createTeams(server);
            manhuntManager.assignPlayersToTeams(server);
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean syncToShared = !manhunt || manhuntManager.isSpeedrunner(player);
            teleportService.teleportToSpawn(player, overworld, spawnPos, timerService, syncToShared);
            addParticipant(player.getUUID());
        }

        if (manhunt) {
            CompassTrackingHandler.reset();
            for (UUID hunterId : manhuntManager.getHunters()) {
                ServerPlayer hunter = server.getPlayerList().getPlayer(hunterId);
                if (hunter != null) CompassTrackingHandler.giveTrackingCompass(hunter);
            }
            applyHeadStartEffects(manhuntManager);
        }

        persistRunState(true);
        worldService.deleteOldWorlds();
        worldService.cleanupOrphanedRunWorlds(worldService.getCurrentRunId());
        server.getPlayerList().broadcastSystemMessage(formatMessage("World ready! Good luck!"), false);
        SoulLink.LOGGER.info("World generation complete, run started");
    }

    private void disableLocatorBar() {
        for (ServerLevel world : server.getAllLevels()) {
            try {
                server.getCommands()
                        .getDispatcher()
                        .execute(
                                "execute in " + world.dimension().identifier() + " run gamerule locator_bar false",
                                server.createCommandSourceStack().withSuppressedOutput());
            } catch (Exception e) {
                SoulLink.LOGGER.warn(
                        "Could not disable locator_bar in {}: {}",
                        world.dimension().identifier(),
                        e.getMessage());
            }
        }
    }

    private static final int HEAD_START_SECONDS = 30;

    private void applyHeadStartEffects(ManhuntManager manhuntManager) {
        int durationTicks = HEAD_START_SECONDS * 20;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (manhuntManager.isHunter(player)) {
                player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, durationTicks, 0, false, false, true));
                player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, durationTicks, 255, false, false, true));
            } else if (manhuntManager.isSpeedrunner(player)) {
                player.addEffect(new MobEffectInstance(MobEffects.SPEED, durationTicks, 0, false, false, true));
            }
        }

        for (int i = HEAD_START_SECONDS; i >= 1; i--) {
            final int secondsRemaining = i;
            int delayTicks = (HEAD_START_SECONDS - i) * 20;

            EventRegistry.scheduleDelayed(delayTicks, () -> {
                if (gameState != RunState.RUNNING) return;
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (manhuntManager.isHunter(player)) {
                        player.connection.send(new ClientboundSetTitlesAnimationPacket(0, 25, 0));
                        ChatFormatting color = secondsRemaining <= 5 ? ChatFormatting.RED : ChatFormatting.GOLD;
                        player.connection.send(
                                new ClientboundSetTitleTextPacket(Component.literal(String.valueOf(secondsRemaining))
                                        .withStyle(color, ChatFormatting.BOLD)));
                        player.connection.send(new ClientboundSetSubtitleTextPacket(
                                Component.literal("Catch the Runners!").withStyle(ChatFormatting.GRAY)));
                    }
                }
            });
        }

        EventRegistry.scheduleDelayed(HEAD_START_SECONDS * 20, () -> {
            if (gameState != RunState.RUNNING) return;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (manhuntManager.isSpeedrunner(player)) {
                    player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 40, 20));
                    player.connection.send(new ClientboundSetTitleTextPacket(
                            Component.literal("HUNTERS RELEASED!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
                } else if (manhuntManager.isHunter(player)) {
                    player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 40, 20));
                    player.connection.send(new ClientboundSetTitleTextPacket(
                            Component.literal("GO!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));
                }
            }
        });
    }

    public void deleteWorlds(boolean teleportPlayers) {
        if (teleportPlayers) {
            List<ServerPlayer> allPlayers =
                    new ArrayList<>(server.getPlayerList().getPlayers());
            for (ServerPlayer player : allPlayers) {
                ServerLevel playerWorld = getPlayerWorld(player);
                if (playerWorld != null && isTemporaryWorld(playerWorld.dimension())) {
                    teleportService.teleportToVanillaSpawn(player);
                }
            }
        }
        worldService.deleteCurrentWorlds();
    }

    public void teleportPlayerToRun(ServerPlayer player) {
        if (gameState == RunState.GENERATING_WORLD) {
            player.setGameMode(GameType.SPECTATOR);
            player.getInventory().clearContent();
            player.removeAllEffects();
            player.sendSystemMessage(formatMessage("Finding spawn point, please wait..."));
            return;
        }

        if (gameState == RunState.RUNNING && spawnFinder.hasFoundSpawn()) {
            ServerLevel overworld = worldService.getOverworld();
            if (overworld != null) {
                if (runManhuntMode) {
                    player.setGameMode(GameType.SPECTATOR);
                    player.getInventory().clearContent();
                    player.removeAllEffects();
                    BlockPos spawnPos = spawnFinder.getSpawnPos();
                    if (spawnPos != null) {
                        player.teleportTo(
                                overworld,
                                spawnPos.getX() + 0.5,
                                spawnPos.getY() + 10,
                                spawnPos.getZ() + 0.5,
                                Set.of(),
                                0,
                                0,
                                true);
                    }
                    player.sendSystemMessage(formatMessage("A run is in progress. You are spectating until it ends."));
                } else {
                    teleportService.teleportToSpawn(player, overworld, spawnFinder.getSpawnPos(), timerService, true);
                    if (Settings.getInstance().isSyncedInventory()) {
                        SharedInventoryHandler.syncPlayerToShared(player);
                    }
                    player.sendSystemMessage(
                            formatMessageWithPlayer("", player.getName().getString(), " joined. Stats synced."));
                }
                addParticipant(player.getUUID());
                persistRunState();
            }
        }
    }

    public void returnPlayerToRunWorld(ServerPlayer player) {
        ServerLevel playerWorld = getPlayerWorld(player);
        if (playerWorld != null && isTemporaryWorld(playerWorld.dimension())) {
            return;
        }
        teleportToResumeLocation(player, gameState == RunState.GAMEOVER);
    }

    public void reconnectPlayerToRun(ServerPlayer player) {
        ServerLevel playerWorld = getPlayerWorld(player);
        boolean alreadyInRun = playerWorld != null && isTemporaryWorld(playerWorld.dimension());
        addParticipant(player.getUUID());

        if (gameState == RunState.GAMEOVER) {
            if (!alreadyInRun) {
                teleportToResumeLocation(player, true);
            } else {
                player.setGameMode(GameType.SPECTATOR);
            }
            player.sendSystemMessage(formatMessage("Run has ended. Use /start to begin a new run."));
            return;
        }

        if (gameState != RunState.RUNNING) {
            return;
        }

        boolean manhunt = runManhuntMode;
        ManhuntManager manhuntManager = ManhuntManager.getInstance();
        boolean unassignedSpectator =
                manhunt && !manhuntManager.isSpeedrunner(player) && !manhuntManager.isHunter(player);
        boolean syncToShared = !manhunt || manhuntManager.isSpeedrunner(player);

        if (unassignedSpectator) {
            player.setGameMode(GameType.SPECTATOR);
        } else {
            player.setGameMode(GameType.SURVIVAL);
        }

        if (!alreadyInRun) {
            teleportToResumeLocation(player, unassignedSpectator);
        }

        teleportService.applyRunAttributes(player, runHalfHeartMode && syncToShared);

        if (syncToShared) {
            SharedStatsHandler.syncPlayerToSharedStats(player);
            if (Settings.getInstance().isSyncedInventory()) {
                if (SharedInventoryHandler.hasItems()) {
                    SharedInventoryHandler.syncPlayerToShared(player);
                } else {
                    SharedInventoryHandler.copyFromPlayer(player);
                }
            }
            if (!timerService.hasStartedThisRun()) {
                timerService.beginWaitingForInput(player);
            }
        }

        if (manhunt && manhuntManager.isHunter(player) && !hasTrackingCompass(player)) {
            CompassTrackingHandler.giveTrackingCompass(player);
        }

        if (manhunt && (manhuntManager.isHunter(player) || manhuntManager.isSpeedrunner(player))) {
            manhuntManager.assignPlayersToTeams(server);
        }

        rememberResumeLocation(player);
        persistRunState();
        player.sendSystemMessage(formatMessage("Run resumed."));
        SoulLink.LOGGER.info("Reconnected {} to run", player.getName().getString());
    }

    private void teleportToResumeLocation(ServerPlayer player, boolean spectator) {
        RunSavedData.ResumeLocation location = resumeLocations.get(player.getUUID());
        ServerLevel world = worldForResumeDimension(location == null ? "ow" : location.dimension());
        if (world == null) {
            world = worldService.getOverworld();
        }
        if (world == null) {
            return;
        }
        if (spectator) {
            player.setGameMode(GameType.SPECTATOR);
        }
        if (location != null) {
            teleportService.teleportPreservingState(
                    player, world, location.x(), location.y(), location.z(), location.yaw(), location.pitch());
            return;
        }
        BlockPos spawnPos = spawnFinder.getSpawnPos();
        if (spawnPos == null) {
            spawnPos = new BlockPos(0, 64, 0);
        }
        teleportService.teleportPreservingState(player, world, spawnPos);
    }

    private ServerLevel worldForResumeDimension(String dimension) {
        if (dimension == null) {
            return worldService.getOverworld();
        }
        return switch (dimension) {
            case "nether" -> worldService.getNether();
            case "end" -> worldService.getEnd();
            default -> worldService.getOverworld();
        };
    }

    private void rememberResumeLocation(ServerPlayer player) {
        ServerLevel world = getPlayerWorld(player);
        if (world == null || !isTemporaryWorld(world.dimension())) {
            return;
        }
        String dimension = "ow";
        if (world.dimension().equals(worldService.getNetherKey())) {
            dimension = "nether";
        } else if (world.dimension().equals(worldService.getEndKey())) {
            dimension = "end";
        }
        resumeLocations.put(
                player.getUUID(),
                new RunSavedData.ResumeLocation(
                        dimension, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
    }

    private static boolean hasTrackingCompass(ServerPlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(Items.COMPASS)) {
                return true;
            }
        }
        return false;
    }

    public boolean isParticipant(UUID playerId) {
        return playerId != null && participants.contains(playerId);
    }

    public void addParticipant(UUID playerId) {
        if (playerId != null) {
            participants.add(playerId);
        }
    }

    public void rememberPlayerOnDisconnect(ServerPlayer player) {
        addParticipant(player.getUUID());
        rememberResumeLocation(player);
    }

    public synchronized void triggerGameOver() {
        if (gameState != RunState.RUNNING) return;

        SoulLink.LOGGER.info("Game Over triggered!");
        timerService.stop();
        gameState = RunState.GAMEOVER;

        ManhuntManager.getInstance().cleanupTeams(server);
        CompassTrackingHandler.reset();

        String finalTime = timerService.getFormattedTime();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isInRun(player)) {
                player.setGameMode(GameType.SPECTATOR);
                player.getInventory().clearContent();

                ServerLevel world = getPlayerWorld(player);
                if (world != null) {
                    world.playSound(
                            null,
                            player.getX(),
                            player.getY(),
                            player.getZ(),
                            SoundEvents.WITHER_DEATH,
                            SoundSource.PLAYERS,
                            0.5f,
                            0.8f);
                }

                player.connection.send(new ClientboundSetTitleTextPacket(
                        Component.literal("GAME OVER").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
                player.connection.send(new ClientboundSetSubtitleTextPacket(
                        Component.literal(finalTime).withStyle(ChatFormatting.WHITE)));
            }
        }

        Component restartMessage = Component.empty()
                .append(getPrefix())
                .append(Component.literal("All players are dead. Click ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("here")
                        .setStyle(Style.EMPTY
                                .withColor(ChatFormatting.BLUE)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand("/start"))
                                .withHoverEvent(new HoverEvent.ShowText(
                                        Component.literal("Start a new attempt").withStyle(ChatFormatting.GRAY)))))
                .append(Component.literal(" or use ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/start").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" to start a new attempt.").withStyle(ChatFormatting.GRAY));

        server.getPlayerList().broadcastSystemMessage(restartMessage, false);
        persistRunState(true);
    }

    public synchronized void triggerVictory() {
        if (gameState != RunState.RUNNING) return;

        SoulLink.LOGGER.info("Victory! Dragon defeated!");
        timerService.stop();
        gameState = RunState.GAMEOVER;

        ManhuntManager.getInstance().cleanupTeams(server);
        CompassTrackingHandler.reset();

        String finalTime = timerService.getFormattedTime();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel world = getPlayerWorld(player);
            if (world != null)
                world.playSound(
                        null,
                        player.getX(),
                        player.getY(),
                        player.getZ(),
                        SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                        SoundSource.PLAYERS,
                        1.0f,
                        1.0f);

            player.connection.send(new ClientboundSetTitleTextPacket(
                    Component.literal("VICTORY").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
            player.connection.send(new ClientboundSetSubtitleTextPacket(
                    Component.literal(finalTime).withStyle(ChatFormatting.WHITE)));
        }

        Component victoryMessage = Component.empty()
                .append(getPrefix())
                .append(Component.literal("Dragon defeated in ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(finalTime).withStyle(ChatFormatting.WHITE));
        server.getPlayerList().broadcastSystemMessage(victoryMessage, false);

        Component clickableHere = Component.literal("here")
                .setStyle(Style.EMPTY
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand("/start"))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal("Click to start a new run!").withStyle(ChatFormatting.GRAY))));
        Component restartMessage = Component.empty()
                .append(getPrefix())
                .append(Component.literal("Victory! Click ").withStyle(ChatFormatting.GRAY))
                .append(clickableHere)
                .append(Component.literal(" or use ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("/start").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" to challenge again.").withStyle(ChatFormatting.GRAY));
        server.getPlayerList().broadcastSystemMessage(restartMessage, false);
        persistRunState(true);
    }

    private void tryRestorePersistedRun() {
        SharedInventoryHandler.reset();
        RunSavedData saved = RunSavedData.get(server);
        RunState restoredState = RunSavedData.parseGameState(saved.gameState);
        if (saved.activeRunId == null || (restoredState != RunState.RUNNING && restoredState != RunState.GAMEOVER)) {
            ManhuntManager.getInstance().resetRoles();
            ManhuntManager.getInstance().cleanupTeams(server);
            worldService.cleanupOrphanedRunWorlds(null);
            return;
        }

        try {
            worldService.restoreRun(saved.activeRunId, saved.seed);
            spawnFinder.injectSpawnPos(new BlockPos(saved.spawnX, saved.spawnY, saved.spawnZ));
            gameState = restoredState;
            endInitialized = saved.endInitialized;
            runManhuntMode = saved.extrasPresent
                    ? saved.manhuntMode
                    : Settings.getInstance().isManhuntMode();
            runHalfHeartMode = saved.extrasPresent
                    ? saved.halfHeartMode
                    : Settings.getInstance().isHalfHeartMode();

            boolean timerStarted = saved.timerStarted;
            boolean timerRunning = saved.timerRunning;
            if (!saved.extrasPresent && restoredState == RunState.RUNNING) {
                timerStarted = true;
                timerRunning = true;
            }
            timerService.restore(saved.elapsedTimeMillis, timerStarted, timerRunning);
            float maxHealth = runHalfHeartMode ? 1.0f : 20.0f;
            SharedStatsHandler.restore(
                    saved.sharedHealth, saved.sharedHunger, saved.sharedSaturation, saved.sharedAbsorption, maxHealth);

            participants.clear();
            participants.addAll(saved.parseUuids(saved.participantIds));
            participants.addAll(saved.parseUuids(saved.runners));
            participants.addAll(saved.parseUuids(saved.hunters));
            resumeLocations.clear();
            resumeLocations.putAll(saved.resumeLocations);

            ManhuntManager manhuntManager = ManhuntManager.getInstance();
            if (runManhuntMode) {
                manhuntManager.restoreRoles(saved.parseUuids(saved.runners), saved.parseUuids(saved.hunters));
                disableLocatorBar();
                manhuntManager.createTeams(server);
                manhuntManager.assignPlayersToTeams(server);
            } else {
                manhuntManager.resetRoles();
                manhuntManager.cleanupTeams(server);
            }

            ServerLevel overworld = worldService.getOverworld();
            BlockPos spawnPos = spawnFinder.getSpawnPos();
            if (overworld != null && spawnPos != null) {
                teleportService.forceloadSpawnChunks(overworld, spawnPos);
            }

            if (restoredState == RunState.RUNNING && endInitialized) {
                EndFightInitializer.initialize(worldService.getEnd());
            }

            worldService.cleanupOrphanedRunWorlds(saved.activeRunId);
            SoulLink.LOGGER.info("Restored {} run {}", restoredState, saved.activeRunId);
        } catch (Exception e) {
            SoulLink.LOGGER.error("Failed to restore run", e);
            gameState = RunState.IDLE;
            participants.clear();
            resumeLocations.clear();
            timerService.reset();
            ManhuntManager.getInstance().resetRoles();
            ManhuntManager.getInstance().cleanupTeams(server);
            worldService.cleanupOrphanedRunWorlds(null);
        }
    }

    private void persistOnlineParticipants() {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isInRun(player)) {
                addParticipant(player.getUUID());
                rememberResumeLocation(player);
            }
        }
    }

    private void persistRunState() {
        persistRunState(false);
    }

    private void persistRunState(boolean flush) {
        if (gameState != RunState.RUNNING && gameState != RunState.GAMEOVER) {
            return;
        }
        UUID runId = worldService.getCurrentRunId();
        if (runId == null) {
            return;
        }
        persistOnlineParticipants();
        BlockPos spawn = spawnFinder.getSpawnPos();
        if (spawn == null) {
            spawn = new BlockPos(0, 64, 0);
        }

        RunSavedData data = RunSavedData.get(server);
        data.activeRunId = runId;
        data.seed = worldService.getCurrentSeed();
        data.elapsedTimeMillis = timerService.getElapsedTimeMillis();
        data.gameState = gameState.name();
        data.spawnX = spawn.getX();
        data.spawnY = spawn.getY();
        data.spawnZ = spawn.getZ();
        data.extrasPresent = true;
        data.timerStarted = timerService.hasStartedThisRun();
        data.timerRunning = timerService.isRunning();
        data.sharedHealth = SharedStatsHandler.getSharedHealth();
        data.sharedHunger = SharedStatsHandler.getSharedHunger();
        data.sharedSaturation = SharedStatsHandler.getSharedSaturation();
        data.sharedAbsorption = SharedStatsHandler.getSharedAbsorption();
        data.endInitialized = endInitialized;
        if (!participants.isEmpty()) {
            data.participantIds = RunSavedData.uuidsToStrings(participants);
        }
        data.manhuntMode = runManhuntMode;
        data.halfHeartMode = runHalfHeartMode;
        data.runners = RunSavedData.uuidsToStrings(ManhuntManager.getInstance().getRunners());
        data.hunters = RunSavedData.uuidsToStrings(ManhuntManager.getInstance().getHunters());
        data.resumeLocations = new LinkedHashMap<>(resumeLocations);
        data.setDirty();
        if (flush) {
            server.overworld().getDataStorage().saveAndJoin();
        }
    }

    public boolean isPlayerInRun(ServerPlayer player) {
        return isInRun(player);
    }

    private boolean isInRun(ServerPlayer player) {
        ServerLevel world = getPlayerWorld(player);
        return world != null && isTemporaryWorld(world.dimension());
    }

    private void clearEnderDragonBossbar() {
        for (ServerLevel world : server.getAllLevels()) {
            EnderDragonFight fight = world.getDragonFight();
            if (fight != null) {
                try {
                    ServerBossEvent bossBar = ((EnderDragonFightAccessor) fight).getBossBar();
                    if (bossBar != null) forceClearBossBar(bossBar);
                } catch (Exception e) {
                    SoulLink.LOGGER.warn("Failed to clear Ender Dragon bossbar: {}", e.getMessage());
                }
            }
        }
    }

    private void forceClearBossBar(ServerBossEvent bossBar) {
        bossBar.setVisible(false);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) bossBar.addPlayer(player);
        bossBar.removeAllPlayers();
    }

    private void clearRaidBossbars() {
        for (ServerLevel world : server.getAllLevels()) {
            Raids raidManager = world.getRaids();
            if (raidManager == null) continue;
            List<Raid> raidsToClean = new ArrayList<>(
                    ((RaidManagerAccessor) raidManager).getRaids().values());
            for (Raid raid : raidsToClean) {
                try {
                    raid.stop();
                    ServerBossEvent bossBar = ((RaidAccessor) raid).getBar();
                    if (bossBar != null) forceClearBossBar(bossBar);
                } catch (Exception e) {
                    SoulLink.LOGGER.warn("Failed to clear raid bossbar: {}", e.getMessage());
                }
            }
        }
    }

    public void teleportToVanillaSpawn(ServerPlayer player) {
        teleportService.teleportToVanillaSpawn(player);
    }

    public RunState getGameState() {
        return gameState;
    }

    public boolean isRunActive() {
        return gameState == RunState.RUNNING;
    }

    public boolean isGameOver() {
        return gameState == RunState.GAMEOVER;
    }

    public boolean isEndInitialized() {
        return endInitialized;
    }

    public void setEndInitialized(boolean initialized) {
        this.endInitialized = initialized;
        persistRunState(true);
    }

    public boolean isManhuntRun() {
        return runManhuntMode;
    }

    public ServerLevel getTemporaryOverworld() {
        return worldService.getOverworld();
    }

    public ServerLevel getTemporaryNether() {
        return worldService.getNether();
    }

    public ServerLevel getTemporaryEnd() {
        return worldService.getEnd();
    }

    public ResourceKey<Level> getTemporaryOverworldKey() {
        return worldService.getOverworldKey();
    }

    public ResourceKey<Level> getTemporaryNetherKey() {
        return worldService.getNetherKey();
    }

    public ResourceKey<Level> getTemporaryEndKey() {
        return worldService.getEndKey();
    }

    public boolean isTemporaryWorld(ResourceKey<Level> worldKey) {
        return worldService.isTemporaryWorld(worldKey);
    }

    public ServerLevel getLinkedNetherWorld(ServerLevel fromWorld) {
        return worldService.getLinkedNetherWorld(fromWorld);
    }

    public BlockPos getSpawnPos() {
        return spawnFinder != null ? spawnFinder.getSpawnPos() : null;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public String getFormattedTime() {
        return timerService.getFormattedTime();
    }

    public long getElapsedTimeMillis() {
        return timerService.getElapsedTimeMillis();
    }

    public void stopTimer() {
        timerService.stop();
    }
}
