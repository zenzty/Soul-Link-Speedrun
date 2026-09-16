package net.zenzty.soullink.server.run;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.common.SoulLinkConstants;
import net.zenzty.soullink.server.settings.Settings;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;

public class WorldService {

    private static final String OVERWORLD_PREFIX = "run_ow_";
    private static final String NETHER_PREFIX = "run_nether_";
    private static final String END_PREFIX = "run_end_";

    private final MinecraftServer server;
    private final Fantasy fantasy;

    private RuntimeLevelHandle overworldHandle;
    private RuntimeLevelHandle netherHandle;
    private RuntimeLevelHandle endHandle;

    private RuntimeLevelHandle oldOverworldHandle;
    private RuntimeLevelHandle oldNetherHandle;
    private RuntimeLevelHandle oldEndHandle;

    private long currentSeed;
    private UUID currentRunId;
    private UUID oldRunId;

    public WorldService(MinecraftServer server) {
        this.server = server;
        this.fantasy = Fantasy.get(server);
    }

    /**
     * The background worker calls this to secretly create worlds for the pool.
     */
    public PooledRun buildBackgroundWorlds() {
        return openRunWorlds(UUID.randomUUID(), new Random().nextLong());
    }

    public void restoreRun(UUID runId, long seed) {
        adoptPooledRun(openRunWorlds(runId, seed));
        SoulLink.LOGGER.info("Restored run dimensions for {}", runId);
    }

    private PooledRun openRunWorlds(UUID runId, long seed) {
        Difficulty serverDifficulty = Settings.getInstance().getDifficulty();

        ServerLevel vanillaOverworld = server.overworld();
        RuntimeLevelConfig overworldConfig = new RuntimeLevelConfig()
                .setDimensionType(BuiltinDimensionTypes.OVERWORLD)
                .setDifficulty(serverDifficulty)
                .setMirrorOverworldClocks(true)
                .setGameRule(GameRules.ADVANCE_TIME, true)
                .setSeed(seed)
                .setGenerator(vanillaOverworld.getChunkSource().getGenerator());
        RuntimeLevelHandle overworld =
                fantasy.getOrOpenPersistentLevel(dimensionId(runId, OVERWORLD_PREFIX), overworldConfig);

        ServerLevel vanillaNether = server.getLevel(Level.NETHER);
        RuntimeLevelHandle nether = null;
        if (vanillaNether != null) {
            RuntimeLevelConfig netherConfig = new RuntimeLevelConfig()
                    .setDimensionType(BuiltinDimensionTypes.NETHER)
                    .setDifficulty(serverDifficulty)
                    .setSeed(seed)
                    .setGenerator(vanillaNether.getChunkSource().getGenerator());
            nether = fantasy.getOrOpenPersistentLevel(dimensionId(runId, NETHER_PREFIX), netherConfig);
        }

        ServerLevel vanillaEnd = server.getLevel(Level.END);
        RuntimeLevelHandle end = null;
        if (vanillaEnd != null) {
            RuntimeLevelConfig endConfig = new RuntimeLevelConfig()
                    .setDimensionType(BuiltinDimensionTypes.END)
                    .setDifficulty(serverDifficulty)
                    .setSeed(seed)
                    .setGenerator(vanillaEnd.getChunkSource().getGenerator());
            end = fantasy.getOrOpenPersistentLevel(dimensionId(runId, END_PREFIX), endConfig);
        }

        return new PooledRun(runId, overworld, nether, end, seed, null);
    }

    private static Identifier dimensionId(UUID runId, String prefix) {
        return Identifier.fromNamespaceAndPath(SoulLinkConstants.MOD_ID, prefix + runId);
    }

    /**
     * When the /start command is executed, WorldService adopts the pooled world.
     */
    public void adoptPooledRun(PooledRun run) {
        this.overworldHandle = run.overworld();
        this.netherHandle = run.nether();
        this.endHandle = run.end();
        this.currentSeed = run.seed();
        this.currentRunId = run.runId();
    }

    /**
     * Clears rain and thunder for a fresh run. In 26.1 weather lives on {@link MinecraftServer},
     * not on individual Fantasy worlds, so it survives world swaps unless reset explicitly.
     */
    public void resetWeatherForNewRun(ServerLevel overworld) {
        if (!overworld.canHaveWeather()) {
            return;
        }

        server.setWeatherParameters(-1, ServerLevel.RAIN_DELAY.sample(overworld.getRandom()), false, false);
        overworld.setRainLevel(0.0f);
        overworld.setThunderLevel(0.0f);
    }

    /**
     * Resets the shared overworld clock to sunrise (0) for a fresh run, matching a new vanilla
     * world. Fantasy overworlds mirror this clock, so the active run world resets with it.
     */
    public void resetTimeForNewRun() {
        ServerLevel overworld = server.overworld();
        var clock = overworld.dimensionTypeRegistration().value().defaultClock().orElseThrow();
        server.clockManager().setTotalTicks(clock, 0L);
    }

    public void saveCurrentWorldsAsOld() {
        oldOverworldHandle = overworldHandle;
        oldNetherHandle = netherHandle;
        oldEndHandle = endHandle;
        overworldHandle = null;
        netherHandle = null;
        endHandle = null;
        oldRunId = currentRunId;
        currentRunId = null;
    }

    public void detachCurrentWorlds() {
        overworldHandle = null;
        netherHandle = null;
        endHandle = null;
    }

    public void detachOldWorlds() {
        oldOverworldHandle = null;
        oldNetherHandle = null;
        oldEndHandle = null;
        oldRunId = null;
    }

    private boolean safeDeleteWorld(RuntimeLevelHandle handle, String worldName) {
        if (handle == null) {
            return true;
        }
        try {
            handle.delete();
            SoulLink.LOGGER.info("Deleted {}", worldName);
            return true;
        } catch (Exception e) {
            SoulLink.LOGGER.error("Failed to delete {}", worldName, e);
            return false;
        }
    }

    public void deleteOldWorlds() {
        boolean deleted = true;
        deleted &= safeDeleteWorld(oldOverworldHandle, "old run overworld");
        deleted &= safeDeleteWorld(oldNetherHandle, "old run nether");
        deleted &= safeDeleteWorld(oldEndHandle, "old run end");
        if (deleted) {
            oldOverworldHandle = null;
            oldNetherHandle = null;
            oldEndHandle = null;
            oldRunId = null;
        }
    }

    public void deleteCurrentWorlds() {
        boolean deleted = true;
        deleted &= safeDeleteWorld(overworldHandle, "run overworld");
        deleted &= safeDeleteWorld(netherHandle, "run nether");
        deleted &= safeDeleteWorld(endHandle, "run end");
        if (deleted) {
            overworldHandle = null;
            netherHandle = null;
            endHandle = null;
            currentRunId = null;
        }
    }

    public void detachOrDeleteForShutdown(UUID persistRunId) {
        if (persistRunId == null) {
            deleteOldWorlds();
            deleteCurrentWorlds();
            return;
        }
        if (persistRunId.equals(currentRunId)) {
            detachCurrentWorlds();
        } else {
            deleteCurrentWorlds();
        }
        if (persistRunId.equals(oldRunId)) {
            detachOldWorlds();
        } else {
            deleteOldWorlds();
        }
    }

    public void cleanupOrphanedRunWorlds(UUID keepRunId) {
        Path soullinkDims =
                server.getWorldPath(LevelResource.ROOT).resolve("dimensions").resolve(SoulLinkConstants.MOD_ID);
        if (!Files.isDirectory(soullinkDims)) {
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(soullinkDims)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                if (!isRunDimensionFolder(name)) {
                    continue;
                }
                if (keepRunId != null && name.endsWith(keepRunId.toString())) {
                    continue;
                }
                deleteDirectory(child);
                SoulLink.LOGGER.info("Deleted orphaned run dimension folder {}", child);
            }
        } catch (IOException e) {
            SoulLink.LOGGER.warn("Could not scan for orphaned run worlds in {}: {}", soullinkDims, e.getMessage());
        }
    }

    private static boolean isRunDimensionFolder(String name) {
        return name.startsWith(OVERWORLD_PREFIX) || name.startsWith(NETHER_PREFIX) || name.startsWith(END_PREFIX);
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    SoulLink.LOGGER.warn("Could not delete {}: {}", path, e.getMessage());
                }
            });
        }
    }

    public boolean isTemporaryWorld(ResourceKey<Level> worldKey) {
        if (worldKey == null) return false;
        ResourceKey<Level> tempOverworld = getOverworldKey();
        ResourceKey<Level> tempNether = getNetherKey();
        ResourceKey<Level> tempEnd = getEndKey();
        return worldKey.equals(tempOverworld) || worldKey.equals(tempNether) || worldKey.equals(tempEnd);
    }

    public ServerLevel getOverworld() {
        return overworldHandle != null ? overworldHandle.asLevel() : null;
    }

    public ServerLevel getNether() {
        return netherHandle != null ? netherHandle.asLevel() : null;
    }

    public ServerLevel getEnd() {
        return endHandle != null ? endHandle.asLevel() : null;
    }

    public ResourceKey<Level> getOverworldKey() {
        return overworldHandle != null ? overworldHandle.getRegistryKey() : null;
    }

    public ResourceKey<Level> getNetherKey() {
        return netherHandle != null ? netherHandle.getRegistryKey() : null;
    }

    public ResourceKey<Level> getEndKey() {
        return endHandle != null ? endHandle.getRegistryKey() : null;
    }

    public long getCurrentSeed() {
        return currentSeed;
    }

    public UUID getCurrentRunId() {
        return currentRunId;
    }

    public ServerLevel getLinkedNetherWorld(ServerLevel fromWorld) {
        if (fromWorld == null) return null;
        ResourceKey<Level> fromKey = fromWorld.dimension();
        if (fromKey.equals(getOverworldKey())) return getNether();
        else if (fromKey.equals(getNetherKey())) return getOverworld();
        return null;
    }
}
