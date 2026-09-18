package net.zenzty.soullink.server.run;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public int spawnX = 0;
    public int spawnY = 64;
    public int spawnZ = 0;

    public boolean extrasPresent = false;
    public boolean timerStarted = false;
    public boolean timerRunning = false;
    public float sharedHealth = 20.0f;
    public int sharedHunger = 20;
    public float sharedSaturation = 5.0f;
    public float sharedAbsorption = 0.0f;
    public boolean endInitialized = false;
    public List<String> participantIds = new ArrayList<>();
    public boolean manhuntMode = false;
    public boolean halfHeartMode = false;
    public List<String> runners = new ArrayList<>();
    public List<String> hunters = new ArrayList<>();
    public Map<UUID, ResumeLocation> resumeLocations = new LinkedHashMap<>();

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
                    Codec.INT.optionalFieldOf("SpawnZ", 0).forGetter(d -> d.spawnZ),
                    Extra.CODEC.optionalFieldOf("Extras").forGetter(d -> Optional.of(d.toExtra())))
            .apply(instance, RunSavedData::fromCodec));

    public static final SavedDataType<RunSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("soullink", "run_state"), RunSavedData::new, CODEC, null);

    public static RunSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public static List<String> uuidsToStrings(Collection<UUID> ids) {
        List<String> values = new ArrayList<>();
        if (ids == null) {
            return values;
        }
        for (UUID id : ids) {
            if (id != null) {
                values.add(id.toString());
            }
        }
        return values;
    }

    public List<UUID> parseUuids(List<String> raw) {
        List<UUID> ids = new ArrayList<>();
        if (raw == null) {
            return ids;
        }
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                ids.add(UUID.fromString(value));
            } catch (IllegalArgumentException e) {
                SoulLink.LOGGER.warn("Invalid UUID in saved run data: {}", value);
            }
        }
        return ids;
    }

    public void clearActiveRun() {
        activeRunId = null;
        seed = 0;
        elapsedTimeMillis = 0;
        gameState = RunState.IDLE.name();
        spawnX = 0;
        spawnY = 64;
        spawnZ = 0;
        extrasPresent = false;
        timerStarted = false;
        timerRunning = false;
        sharedHealth = 20.0f;
        sharedHunger = 20;
        sharedSaturation = 5.0f;
        sharedAbsorption = 0.0f;
        endInitialized = false;
        participantIds.clear();
        manhuntMode = false;
        halfHeartMode = false;
        runners.clear();
        hunters.clear();
        resumeLocations.clear();
        setDirty();
    }

    public boolean hasPersistableRun() {
        if (activeRunId == null) {
            return false;
        }
        RunState state = parseGameState(gameState);
        return state == RunState.RUNNING || state == RunState.GAMEOVER;
    }

    public static RunState parseGameState(String raw) {
        if (raw == null || raw.isBlank()) {
            return RunState.IDLE;
        }
        try {
            return RunState.valueOf(raw);
        } catch (IllegalArgumentException e) {
            SoulLink.LOGGER.warn("Unknown saved run state: {}", raw);
            return RunState.IDLE;
        }
    }

    private Extra toExtra() {
        List<String> locations = new ArrayList<>();
        for (Map.Entry<UUID, ResumeLocation> entry : resumeLocations.entrySet()) {
            locations.add(entry.getValue().encode(entry.getKey()));
        }
        return new Extra(
                timerStarted,
                timerRunning,
                sharedHealth,
                sharedHunger,
                sharedSaturation,
                sharedAbsorption,
                endInitialized,
                List.copyOf(participantIds),
                manhuntMode,
                halfHeartMode,
                List.copyOf(runners),
                List.copyOf(hunters),
                List.copyOf(locations));
    }

    private static RunSavedData fromCodec(
            String uuidStr,
            Long seed,
            Long elapsedTimeMillis,
            String gameState,
            Integer spawnX,
            Integer spawnY,
            Integer spawnZ,
            Optional<Extra> extraOpt) {
        RunSavedData data = new RunSavedData();
        if (!uuidStr.isEmpty()) {
            try {
                data.activeRunId = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException ignored) {
                SoulLink.LOGGER.warn("Invalid UUID string in saved data: {}", uuidStr);
            }
        }
        data.seed = seed;
        data.elapsedTimeMillis = elapsedTimeMillis;
        data.gameState = gameState;
        data.spawnX = spawnX;
        data.spawnY = spawnY;
        data.spawnZ = spawnZ;
        if (extraOpt.isEmpty()) {
            return data;
        }
        Extra extra = extraOpt.get();
        data.extrasPresent = true;
        data.timerStarted = extra.timerStarted();
        data.timerRunning = extra.timerRunning();
        data.sharedHealth = extra.sharedHealth();
        data.sharedHunger = extra.sharedHunger();
        data.sharedSaturation = extra.sharedSaturation();
        data.sharedAbsorption = extra.sharedAbsorption();
        data.endInitialized = extra.endInitialized();
        data.participantIds = new ArrayList<>(extra.participantIds());
        data.manhuntMode = extra.manhuntMode();
        data.halfHeartMode = extra.halfHeartMode();
        data.runners = new ArrayList<>(extra.runners());
        data.hunters = new ArrayList<>(extra.hunters());
        data.resumeLocations = ResumeLocation.parseAll(extra.locations());
        return data;
    }

    public record ResumeLocation(String dimension, double x, double y, double z, float yaw, float pitch) {
        public String encode(UUID playerId) {
            return playerId + "|" + dimension + "|" + x + "|" + y + "|" + z + "|" + yaw + "|" + pitch;
        }

        public static Map<UUID, ResumeLocation> parseAll(List<String> raw) {
            Map<UUID, ResumeLocation> locations = new LinkedHashMap<>();
            if (raw == null) {
                return locations;
            }
            for (String value : raw) {
                parse(value, locations);
            }
            return locations;
        }

        private static ResumeLocation parse(String value, Map<UUID, ResumeLocation> into) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String[] parts = value.split("\\|", 7);
            if (parts.length != 7) {
                SoulLink.LOGGER.warn("Invalid resume location: {}", value);
                return null;
            }
            try {
                UUID id = UUID.fromString(parts[0]);
                ResumeLocation location = new ResumeLocation(
                        parts[1],
                        Double.parseDouble(parts[2]),
                        Double.parseDouble(parts[3]),
                        Double.parseDouble(parts[4]),
                        Float.parseFloat(parts[5]),
                        Float.parseFloat(parts[6]));
                into.put(id, location);
                return location;
            } catch (IllegalArgumentException e) {
                SoulLink.LOGGER.warn("Invalid resume location: {}", value);
                return null;
            }
        }
    }

    private record Extra(
            boolean timerStarted,
            boolean timerRunning,
            float sharedHealth,
            int sharedHunger,
            float sharedSaturation,
            float sharedAbsorption,
            boolean endInitialized,
            List<String> participantIds,
            boolean manhuntMode,
            boolean halfHeartMode,
            List<String> runners,
            List<String> hunters,
            List<String> locations) {
        static final Codec<Extra> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.BOOL.optionalFieldOf("TimerStarted", false).forGetter(Extra::timerStarted),
                        Codec.BOOL.optionalFieldOf("TimerRunning", false).forGetter(Extra::timerRunning),
                        Codec.FLOAT.optionalFieldOf("SharedHealth", 20.0f).forGetter(Extra::sharedHealth),
                        Codec.INT.optionalFieldOf("SharedHunger", 20).forGetter(Extra::sharedHunger),
                        Codec.FLOAT.optionalFieldOf("SharedSaturation", 5.0f).forGetter(Extra::sharedSaturation),
                        Codec.FLOAT.optionalFieldOf("SharedAbsorption", 0.0f).forGetter(Extra::sharedAbsorption),
                        Codec.BOOL.optionalFieldOf("EndInitialized", false).forGetter(Extra::endInitialized),
                        Codec.STRING
                                .listOf()
                                .optionalFieldOf("ParticipantIds", List.of())
                                .forGetter(Extra::participantIds),
                        Codec.BOOL.optionalFieldOf("ManhuntMode", false).forGetter(Extra::manhuntMode),
                        Codec.BOOL.optionalFieldOf("HalfHeartMode", false).forGetter(Extra::halfHeartMode),
                        Codec.STRING
                                .listOf()
                                .optionalFieldOf("Runners", List.of())
                                .forGetter(Extra::runners),
                        Codec.STRING
                                .listOf()
                                .optionalFieldOf("Hunters", List.of())
                                .forGetter(Extra::hunters),
                        Codec.STRING
                                .listOf()
                                .optionalFieldOf("ResumeLocations", List.of())
                                .forGetter(Extra::locations))
                .apply(instance, Extra::new));
    }
}
