package net.zenzty.soullink.server.run;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.zenzty.soullink.SoulLink;

public class RunSavedData extends SavedData {
    public UUID activeRunId = null;
    public long seed = 0;
    public long elapsedTimeMillis = 0;
    public String gameState = RunState.IDLE.name();

    // NEW: Save spawn coordinates
    public int spawnX = 0;
    public int spawnY = 64;
    public int spawnZ = 0;

    public static final Codec<RunSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.STRING
                            .optionalFieldOf("ActiveRunId", "")
                            .forGetter(d -> d.activeRunId == null ? "" : d.activeRunId.toString()),
                    Codec.LONG.optionalFieldOf("Seed", 0L).forGetter(d -> d.seed),
                    Codec.LONG.optionalFieldOf("ElapsedTimeMillis", 0L).forGetter(d -> d.elapsedTimeMillis),
                    Codec.STRING
                            .optionalFieldOf("GameState", RunState.IDLE.name())
                            .forGetter(d -> d.gameState),
                    Codec.INT.optionalFieldOf("SpawnX", 0).forGetter(d -> d.spawnX),
                    Codec.INT.optionalFieldOf("SpawnY", 64).forGetter(d -> d.spawnY),
                    Codec.INT.optionalFieldOf("SpawnZ", 0).forGetter(d -> d.spawnZ))
            .apply(instance, (uuidStr, seed, elapsedTimeMillis, gameState, spawnX, spawnY, spawnZ) -> {
                RunSavedData data = new RunSavedData();

                if (!uuidStr.isEmpty()) {
                    try {
                        data.activeRunId = UUID.fromString(uuidStr);
                    } catch (IllegalArgumentException ignored) {
                        SoulLink.LOGGER.warn("Invalid UUID string in saved data: " + uuidStr);
                    }
                }
                data.seed = seed;
                data.elapsedTimeMillis = elapsedTimeMillis;
                data.gameState = gameState;
                data.spawnX = spawnX;
                data.spawnY = spawnY;
                data.spawnZ = spawnZ;

                return data;
            }));

    public static final SavedDataType<RunSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("soullink", "run_state"), RunSavedData::new, CODEC, null);

    public static RunSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }
}
