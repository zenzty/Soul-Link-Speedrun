package net.zenzty.soullink.server.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.storage.LevelResource;
import net.zenzty.soullink.SoulLink;

/**
 * Handles loading and saving Soul Link settings to a JSON file in the world save directory.
 * Settings persist across server restarts. The file format is extensible: missing keys on load keep
 * current defaults; new settings can be added by extending the data class and load/save logic.
 */
public final class SettingsPersistence {

    private static final String FILENAME = "soullink_settings.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SettingsPersistence() {}

    /**
     * Loads settings from the world save. If the file is missing or invalid, settings keep their
     * in-memory defaults. Call on SERVER_STARTED before RunManager.init.
     */
    public static void load(MinecraftServer server) {
        Path path = getSettingsPath(server);
        if (!Files.isRegularFile(path)) {
            SoulLink.LOGGER.debug("No settings file at {}, using defaults", path);
            return;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            Object parsed = parseSettingsData(json);
            if (parsed instanceof SettingsData data) {
                applyToSettings(data);
                restorePending(data);
                SoulLink.LOGGER.info("Loaded Soul Link settings from {}", path);
            }
        } catch (IOException e) {
            SoulLink.LOGGER.warn("Could not read settings file {}: {}", path, e.getMessage());
        } catch (Exception e) {
            SoulLink.LOGGER.warn("Could not parse settings file {}: {}", path, e.getMessage());
        }
    }

    /**
     * Saves current settings to the world save. Call when settings are changed (e.g. from /settings
     * or /chaos) or on SERVER_STOPPING as a safety net.
     */
    public static void save(MinecraftServer server) {
        Path path = getSettingsPath(server);
        try {
            SettingsData data = fromSettings();
            String json = GSON.toJson(data);
            Files.createDirectories(path.getParent());
            Files.writeString(path, json, StandardCharsets.UTF_8);
            SoulLink.LOGGER.debug("Saved Soul Link settings to {}", path);
        } catch (IOException e) {
            SoulLink.LOGGER.warn("Could not write settings file {}: {}", path, e.getMessage());
        }
    }

    private static Path getSettingsPath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(FILENAME);
    }

    /**
     * Parses JSON into SettingsData. Returns Object so the result is not interpreted as @NonNull;
     * Gson.fromJson can return null (e.g. for JSON "null") and Gson has no null annotations. Caller
     * must use instanceof to check before using as SettingsData.
     */
    private static Object parseSettingsData(String json) {
        return GSON.fromJson(json, SettingsData.class);
    }

    private static void applyToSettings(SettingsData data) {
        Settings s = Settings.getInstance();
        if (data.damageLogEnabled != null) {
            s.setDamageLogEnabled(data.damageLogEnabled);
        }
        if (data.difficulty != null && !data.difficulty.isBlank()) {
            try {
                Difficulty d = Difficulty.valueOf(data.difficulty.toUpperCase());
                s.setDifficulty(d);
            } catch (IllegalArgumentException ignored) {
                // keep default
            }
        }
        if (data.halfHeartMode != null) {
            s.setHalfHeartMode(data.halfHeartMode);
        }
        if (data.sharedPotions != null) {
            s.setSharedPotions(data.sharedPotions);
        }
        if (data.sharedJumping != null) {
            s.setSharedJumping(data.sharedJumping);
        }
        if (data.manhuntMode != null) {
            s.setManhuntMode(data.manhuntMode);
        }
        if (data.syncedInventory != null) {
            s.setSyncedInventory(data.syncedInventory);
        }
    }

    private static SettingsData fromSettings() {
        Settings s = Settings.getInstance();
        SettingsData data = new SettingsData();
        data.damageLogEnabled = s.isDamageLogEnabled();
        Settings.SettingsSnapshot applied = s.createSnapshot();
        data.difficulty = applied.difficulty().name();
        data.halfHeartMode = applied.halfHeartMode();
        data.sharedPotions = applied.sharedPotions();
        data.sharedJumping = applied.sharedJumping();
        data.manhuntMode = applied.manhuntMode();
        data.syncedInventory = applied.syncedInventory();

        Settings.SettingsSnapshot pending = s.getPendingSnapshotOrNull();
        if (pending != null) {
            data.pending = true;
            data.pendingDifficulty = pending.difficulty().name();
            data.pendingHalfHeartMode = pending.halfHeartMode();
            data.pendingSharedPotions = pending.sharedPotions();
            data.pendingSharedJumping = pending.sharedJumping();
            data.pendingManhuntMode = pending.manhuntMode();
            data.pendingSyncedInventory = pending.syncedInventory();
        }
        return data;
    }

    private static void restorePending(SettingsData data) {
        if (!Boolean.TRUE.equals(data.pending)) {
            return;
        }
        Settings s = Settings.getInstance();
        Difficulty difficulty = s.getDifficulty();
        if (data.pendingDifficulty != null && !data.pendingDifficulty.isBlank()) {
            try {
                difficulty = Difficulty.valueOf(data.pendingDifficulty.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // keep applied
            }
        }
        s.restorePendingSnapshot(new Settings.SettingsSnapshot(
                difficulty,
                data.pendingHalfHeartMode != null ? data.pendingHalfHeartMode : s.isHalfHeartMode(),
                data.pendingSharedPotions != null ? data.pendingSharedPotions : s.isSharedPotions(),
                data.pendingSharedJumping != null ? data.pendingSharedJumping : s.isSharedJumping(),
                data.pendingManhuntMode != null ? data.pendingManhuntMode : s.isManhuntMode(),
                data.pendingSyncedInventory != null ? data.pendingSyncedInventory : s.isSyncedInventory()));
    }

    /**
     * DTO for JSON. Use boxed types so we can omit null on save and detect missing keys on load.
     * When adding a new setting: add the field here, in applyToSettings, and in fromSettings.
     * Fields are read and written by Gson via reflection, so they appear unused to the compiler.
     */
    private static class SettingsData {
        Boolean damageLogEnabled;
        String difficulty;
        Boolean halfHeartMode;
        Boolean sharedPotions;
        Boolean sharedJumping;
        Boolean manhuntMode;
        Boolean syncedInventory;
        Boolean pending;
        String pendingDifficulty;
        Boolean pendingHalfHeartMode;
        Boolean pendingSharedPotions;
        Boolean pendingSharedJumping;
        Boolean pendingManhuntMode;
        Boolean pendingSyncedInventory;
    }
}
