package net.zenzty.soullink.server.run;

import java.util.Random;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.gamerules.GameRules;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.settings.Settings;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;

public class WorldService {

    private final MinecraftServer server;
    private final Fantasy fantasy;

    private RuntimeLevelHandle overworldHandle;
    private RuntimeLevelHandle netherHandle;
    private RuntimeLevelHandle endHandle;

    private RuntimeLevelHandle oldOverworldHandle;
    private RuntimeLevelHandle oldNetherHandle;
    private RuntimeLevelHandle oldEndHandle;

    private long currentSeed;

    public WorldService(MinecraftServer server) {
        this.server = server;
        this.fantasy = Fantasy.get(server);
    }

    /**
     * The background worker calls this to secretly create worlds for the pool.
     */
    public PooledRun buildBackgroundWorlds() {
        long backgroundSeed = new Random().nextLong();
        Difficulty serverDifficulty = Settings.getInstance().getDifficulty();

        // Overworld
        ServerLevel vanillaOverworld = server.overworld();
        RuntimeLevelConfig overworldConfig =
                new RuntimeLevelConfig().setDimensionType(BuiltinDimensionTypes.OVERWORLD)
                        .setDifficulty(serverDifficulty).setMirrorOverworldClocks(true)
                        .setGameRule(GameRules.ADVANCE_TIME, true).setSeed(backgroundSeed)
                .setGenerator(vanillaOverworld.getChunkSource().getGenerator());

        RuntimeLevelHandle tempOverworld = fantasy.openTemporaryLevel(overworldConfig);

        // Nether
        ServerLevel vanillaNether = server.getLevel(Level.NETHER);
        RuntimeLevelHandle tempNether = null;
        if (vanillaNether != null) {
            RuntimeLevelConfig netherConfig = new RuntimeLevelConfig()
                    .setDimensionType(BuiltinDimensionTypes.NETHER)
                    .setDifficulty(serverDifficulty)
                    .setSeed(backgroundSeed)
                    .setGenerator(vanillaNether.getChunkSource().getGenerator());
            tempNether = fantasy.openTemporaryLevel(netherConfig);
        }

        // End
        ServerLevel vanillaEnd = server.getLevel(Level.END);
        RuntimeLevelHandle tempEnd = null;
        if (vanillaEnd != null) {
            RuntimeLevelConfig endConfig = new RuntimeLevelConfig()
                    .setDimensionType(BuiltinDimensionTypes.END)
                    .setDifficulty(serverDifficulty)
                    .setSeed(backgroundSeed)
                    .setGenerator(vanillaEnd.getChunkSource().getGenerator());
            tempEnd = fantasy.openTemporaryLevel(endConfig);
        }

        return new PooledRun(tempOverworld, tempNether, tempEnd, backgroundSeed, null);
    }

    /**
     * When the /start command is executed, WorldService adopts the pooled world.
     */
    public void adoptPooledRun(PooledRun run) {
        this.overworldHandle = run.overworld();
        this.netherHandle = run.nether();
        this.endHandle = run.end();
        this.currentSeed = run.seed();
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
     * Resets the shared overworld clock to the normal starting time for a fresh run. In 26.1
     * Fantasy overworlds mirror the server clock, so resetting it also resets the time in the
     * active Fantasy world.
     */
    public void resetTimeForNewRun() {
        ServerLevel overworld = server.overworld();
        var clock = overworld.dimensionTypeRegistration().value().defaultClock().orElseThrow();
        server.clockManager().setTotalTicks(clock, 1000L);
    }

    public void saveCurrentWorldsAsOld() {
        oldOverworldHandle = overworldHandle;
        oldNetherHandle = netherHandle;
        oldEndHandle = endHandle;
        overworldHandle = null;
        netherHandle = null;
        endHandle = null;
    }

    private void safeDeleteWorld(RuntimeLevelHandle handle, String worldName) {
        if (handle != null) {
            try {
                handle.delete();
                SoulLink.LOGGER.info("Deleted {}", worldName);
            } catch (Exception e) {
                SoulLink.LOGGER.error("Failed to delete {}", worldName, e);
            }
        }
    }

    public void deleteOldWorlds() {
        safeDeleteWorld(oldOverworldHandle, "old temporary overworld");
        oldOverworldHandle = null;
        safeDeleteWorld(oldNetherHandle, "old temporary nether");
        oldNetherHandle = null;
        safeDeleteWorld(oldEndHandle, "old temporary end");
        oldEndHandle = null;
    }

    public void deleteCurrentWorlds() {
        safeDeleteWorld(overworldHandle, "temporary overworld");
        overworldHandle = null;
        safeDeleteWorld(netherHandle, "temporary nether");
        netherHandle = null;
        safeDeleteWorld(endHandle, "temporary end");
        endHandle = null;
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

    public ServerLevel getLinkedNetherWorld(ServerLevel fromWorld) {
        if (fromWorld == null) return null;
        ResourceKey<Level> fromKey = fromWorld.dimension();
        if (fromKey.equals(getOverworldKey())) return getNether();
        else if (fromKey.equals(getNetherKey())) return getOverworld();
        return null;
    }
}
