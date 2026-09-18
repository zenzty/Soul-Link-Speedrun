package net.zenzty.soullink.server.health;

import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.manhunt.ManhuntManager;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.settings.Settings;

/**
 * Handles shared health, hunger, and saturation between all players. Implements the "Soul Link"
 * mechanic where all players share the same vital stats. In Manhunt mode, only Runners participate.
 */
public class SharedStatsHandler {

    // Master stat values
    private static volatile float sharedHealth = 20.0f;
    private static volatile int sharedHunger = 20;
    private static volatile float sharedSaturation = 5.0f;
    private static volatile float sharedAbsorption = 0.0f; // Absorption hearts (golden apples, etc)

    // Prevent infinite sync loops
    private static volatile boolean isSyncing = false;

    // Accumulator for fractional natural regen (since we divide by player count)
    private static volatile float regenAccumulator = 0.0f;

    // Accumulator for fractional regeneration effect healing (since we divide by player count)
    private static volatile float regenerationHealAccumulator = 0.0f;

    // Accumulators for fractional hunger/saturation drain (since we divide by player count)
    private static volatile float hungerDrainAccumulator = 0.0f;
    private static volatile float saturationDrainAccumulator = 0.0f;

    // Accumulator for fractional damage (Poison/Wither)
    private static volatile float damageAccumulator = 0.0f;

    /**
     * Gets the current max health based on settings.
     */
    private static float getMaxHealth() {
        return Settings.getInstance().isHalfHeartMode() ? 1.0f : 20.0f;
    }

    /**
     * Returns whether this player participates in shared Soul Link stats. When Manhunt is off, all
     * players in the run participate. When Manhunt is on, only Runners participate; Hunters use
     * vanilla mechanics.
     *
     * @param player the player to check
     * @return true if the player should share health, hunger, and related stats
     */
    private static boolean shouldParticipateInSoulLink(ServerPlayer player) {
        RunManager runManager;
        try {
            runManager = RunManager.getInstance();
        } catch (IllegalStateException e) {
            return false;
        }
        if (!runManager.isRunActive()) return false;
        if (!runManager.isManhuntRun()) return true;
        return ManhuntManager.getInstance().isSpeedrunner(player);
    }

    /**
     * Resets all shared stats to default values. Called when starting a new run.
     */
    public static void reset() {
        // Use half heart mode max health if enabled
        float maxHealth = getMaxHealth();

        sharedHealth = maxHealth;
        sharedHunger = 20;
        sharedSaturation = 5.0f;
        sharedAbsorption = 0.0f;
        isSyncing = false;
        regenAccumulator = 0.0f;
        regenerationHealAccumulator = 0.0f;
        hungerDrainAccumulator = 0.0f;
        saturationDrainAccumulator = 0.0f;
        damageAccumulator = 0.0f;

        // Also reset other shared handlers
        SharedPotionHandler.reset();
        SharedJumpHandler.reset();

        SoulLink.LOGGER.info("Shared stats reset to defaults (maxHealth={})", maxHealth);
    }

    public static void restore(float health, int hunger, float saturation, float absorption, float maxHealth) {
        sharedHealth = Mth.clamp(health, 0.0f, maxHealth);
        sharedHunger = Mth.clamp(hunger, 0, 20);
        sharedSaturation = Math.max(0.0f, saturation);
        sharedAbsorption = Math.max(0.0f, absorption);
        isSyncing = false;
        regenAccumulator = 0.0f;
        regenerationHealAccumulator = 0.0f;
        hungerDrainAccumulator = 0.0f;
        saturationDrainAccumulator = 0.0f;
        damageAccumulator = 0.0f;
        SoulLink.LOGGER.info("Shared stats restored (maxHealth={})", maxHealth);
    }

    /**
     * Syncs a player's stats to the current shared values. Used for late joiners and reconnecting
     * players.
     */
    public static void syncPlayerToSharedStats(ServerPlayer player) {
        if (isSyncing) return;

        isSyncing = true;
        try {
            player.setHealth(sharedHealth);
            player.setAbsorptionAmount(sharedAbsorption);
            player.getFoodData().setFoodLevel(sharedHunger);
            player.getFoodData().setSaturation(sharedSaturation);
            SoulLink.LOGGER.debug(
                    "Synced {} to shared stats: HP={}, Absorption={}, Food={}, Sat={}",
                    player.getName().getString(),
                    sharedHealth,
                    sharedAbsorption,
                    sharedHunger,
                    sharedSaturation);
        } finally {
            isSyncing = false;
        }
    }

    /**
     * Gets the ServerWorld for a player. In Yarn 1.21.11, ServerPlayerEntity.getEntityWorld()
     * returns ServerWorld directly.
     */
    private static ServerLevel getPlayerWorld(ServerPlayer player) {
        return player.level();
    }

    /**
     * Called when a player's health changes after taking damage. Updates the master health and
     * syncs to all other players with visual feedback.
     *
     * @param damagedPlayer The player who took damage
     * @param newHealth The player's health AFTER damage was applied (armor already calculated)
     * @param damageSource The source of the damage
     */
    public static void onPlayerHealthChanged(ServerPlayer damagedPlayer, float newHealth, DamageSource damageSource) {
        if (isSyncing) return;
        if (!shouldParticipateInSoulLink(damagedPlayer)) return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive()) return;

        ServerLevel playerWorld = getPlayerWorld(damagedPlayer);
        if (playerWorld == null) return;

        if (!runManager.isTemporaryWorld(playerWorld.dimension())) return;

        isSyncing = true;
        try {
            float oldHealth = sharedHealth;
            float currentDamageAmount = oldHealth - newHealth;

            // Handle periodic damage (Poison/Wither) - normalize by player count
            // Without this, N players poisoned = Nx damage speed
            String damageType = damageSource.getMsgId();
            if ("poison".equals(damageType) || "wither".equals(damageType)) {
                handlePeriodicDamage(damagedPlayer, currentDamageAmount);
                return;
            }

            // Update the master health to match the damaged player's health
            sharedHealth = Mth.clamp(newHealth, 0.0f, getMaxHealth());

            // Check for death condition
            if (sharedHealth <= 0) {
                SoulLink.LOGGER.info("Shared health depleted - triggering game over");
                runManager.triggerGameOver();
                return;
            }

            // Only sync to other players if health actually decreased
            if (sharedHealth < oldHealth) {
                MinecraftServer server = runManager.getServer();
                if (server == null) return;

                float syncedDamageAmount = oldHealth - sharedHealth;
                List<ServerPlayer> players = server.getPlayerList().getPlayers();

                // Broadcast damage notification to all players (if combat log is enabled)
                if (Settings.getInstance().isDamageLogEnabled()) {
                    // Convert from half-hearts to full hearts for display (Minecraft stores health
                    // as
                    // 0-20, where 1 heart = 2). Round to nearest 0.5 hearts and ensure minimum 0.5.
                    float damageInHearts = syncedDamageAmount / 2.0f;
                    float roundedDamage = Math.max(0.5f, Math.round(damageInHearts * 2.0f) / 2.0f);
                    String damageText = String.format(Locale.US, "%.1f", roundedDamage);
                    Component damageNotification = Component.empty()
                            .append(RunManager.getPrefix())
                            .append(Component.literal(damagedPlayer.getName().getString())
                                    .withStyle(ChatFormatting.WHITE))
                            .append(Component.literal(" has taken ").withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(damageText + " ❤").withStyle(ChatFormatting.RED))
                            .append(Component.literal(" damage.").withStyle(ChatFormatting.GRAY));
                    server.getPlayerList().broadcastSystemMessage(damageNotification, false);
                }

                for (ServerPlayer player : players) {
                    if (player == damagedPlayer || player.isSpectator() || player.isCreative()) continue;
                    if (!shouldParticipateInSoulLink(player)) continue;

                    ServerLevel otherWorld = getPlayerWorld(player);
                    if (otherWorld == null) continue;

                    if (!runManager.isTemporaryWorld(otherWorld.dimension())) continue;

                    // Apply actual damage to trigger all client-side effects (red flash, screen
                    // shake, sound)
                    // Use the world's damage sources for correct API usage
                    DamageSource syncDamage = otherWorld.damageSources().generic();

                    // Apply damage using the world-aware damage method
                    // The isSyncing flag prevents onPlayerHealthChanged from recursing
                    player.hurtServer(otherWorld, syncDamage, syncedDamageAmount);

                    // Safety check: if player "died" due to local damage but shared health remains,
                    // restore them
                    if (!player.isAlive() && sharedHealth > 0) {
                        player.setHealth(Math.max(1.0f, sharedHealth));
                    } else {
                        // Ensure health is exactly what we expect (in case of any rounding)
                        player.setHealth(sharedHealth);
                    }
                }

                SoulLink.LOGGER.debug(
                        "Health synced: {} -> {} (from {})",
                        oldHealth,
                        sharedHealth,
                        damagedPlayer.getName().getString());
            }

        } finally {
            isSyncing = false;
        }
    }

    /**
     * Handles periodic damage (Poison/Wither) by normalizing it by player count and using an
     * accumulator.
     */
    private static void handlePeriodicDamage(ServerPlayer damagedPlayer, float damageAmount) {
        RunManager runManager = RunManager.getInstance();
        MinecraftServer server = runManager.getServer();
        if (server == null) return;

        int playerCount = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!shouldParticipateInSoulLink(player)) continue;
            ServerLevel world = getPlayerWorld(player);
            if (world != null && runManager.isTemporaryWorld(world.dimension())) {
                playerCount++;
            }
        }

        if (playerCount == 0) return;

        float normalizedDamage = damageAmount / playerCount;
        damageAccumulator += normalizedDamage;

        SoulLink.LOGGER.debug(
                "[DAMAGE DEBUG] Player {} took {} periodic damage, normalized to {} ({} players), accumulator now {}",
                damagedPlayer.getName().getString(),
                damageAmount,
                normalizedDamage,
                playerCount,
                damageAccumulator);

        // Only apply damage when we've accumulated at least 0.5 HP
        if (damageAccumulator >= 0.5f) {
            float damageToApply = damageAccumulator;
            damageAccumulator = 0.0f;

            float oldHealth = sharedHealth;
            sharedHealth = Mth.clamp(sharedHealth - damageToApply, 0.0f, getMaxHealth());

            // Sync to all players
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!shouldParticipateInSoulLink(player)) continue;
                ServerLevel otherWorld = getPlayerWorld(player);
                if (otherWorld == null || !runManager.isTemporaryWorld(otherWorld.dimension())) continue;

                player.setHealth(sharedHealth);
            }

            SoulLink.LOGGER.debug(
                    "[DAMAGE DEBUG] Applied {} periodic damage: {} -> {}", damageToApply, oldHealth, sharedHealth);

            if (sharedHealth <= 0) {
                runManager.triggerGameOver();
            }
        } else {
            // Revert the damage to the player since it hasn't reached the threshold yet
            damagedPlayer.setHealth(sharedHealth);
        }
    }

    /**
     * Called when a player heals (potions, etc.) Updates the master health and syncs to all
     * players.
     *
     * Note: Regeneration effect healing is handled separately by onRegenerationHeal() to normalize
     * by player count.
     */
    public static void onPlayerHealed(ServerPlayer healedPlayer, float newHealth) {
        if (isSyncing) return;
        if (!shouldParticipateInSoulLink(healedPlayer)) return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive()) return;

        ServerLevel playerWorld = getPlayerWorld(healedPlayer);
        if (playerWorld == null) return;

        if (!runManager.isTemporaryWorld(playerWorld.dimension())) return;

        isSyncing = true;
        try {
            float oldHealth = sharedHealth;
            sharedHealth = Mth.clamp(newHealth, 0.0f, getMaxHealth());

            if (sharedHealth > oldHealth) {
                MinecraftServer server = runManager.getServer();
                if (server == null) return;

                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (player == healedPlayer) continue;
                    if (player.isSpectator() || player.isCreative()) continue;
                    if (!shouldParticipateInSoulLink(player)) continue;

                    if (player.isSpectator() || player.isCreative()) continue;

                    ServerLevel otherWorld = getPlayerWorld(player);
                    if (otherWorld == null) continue;

                    if (!runManager.isTemporaryWorld(otherWorld.dimension())) continue;

                    player.setHealth(sharedHealth);
                }

                SoulLink.LOGGER.debug("Healing synced: {} -> {}", oldHealth, sharedHealth);
            }

        } finally {
            isSyncing = false;
        }
    }

    /**
     * Called when a player heals from a regeneration effect. The healing amount is divided by the
     * number of players in the run to normalize regen speed.
     *
     * Without this, N players with regeneration = Nx healing speed since each player's regen would
     * stack.
     */
    public static void onRegenerationHeal(ServerPlayer regenPlayer, float healAmount) {
        if (isSyncing) return;
        if (!shouldParticipateInSoulLink(regenPlayer)) return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive()) return;

        ServerLevel playerWorld = getPlayerWorld(regenPlayer);
        if (playerWorld == null) return;

        if (!runManager.isTemporaryWorld(playerWorld.dimension())) return;

        MinecraftServer server = runManager.getServer();
        if (server == null) return;

        int playerCount = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!shouldParticipateInSoulLink(player)) continue;
            ServerLevel world = getPlayerWorld(player);
            if (world != null && runManager.isTemporaryWorld(world.dimension())) {
                playerCount++;
            }
        }

        if (playerCount == 0) return;

        // Divide the heal amount by player count and accumulate
        float normalizedHeal = healAmount / playerCount;
        regenerationHealAccumulator += normalizedHeal;

        SoulLink.LOGGER.debug(
                "[REGEN EFFECT DEBUG] Player {} healed {} HP from regeneration, normalized to {} ({} players), accumulator now {}",
                regenPlayer.getName().getString(),
                healAmount,
                normalizedHeal,
                playerCount,
                regenerationHealAccumulator);

        // Only apply healing when we've accumulated at least 0.5 HP (prevents constant tiny
        // updates)
        if (regenerationHealAccumulator >= 0.5f) {
            float healToApply = regenerationHealAccumulator;
            regenerationHealAccumulator = 0.0f;

            isSyncing = true;
            try {
                float oldHealth = sharedHealth;
                sharedHealth = Mth.clamp(sharedHealth + healToApply, 0.0f, getMaxHealth());

                if (sharedHealth > oldHealth) {
                    // Sync to all players
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        if (!shouldParticipateInSoulLink(player)) continue;
                        ServerLevel otherWorld = getPlayerWorld(player);
                        if (otherWorld == null) continue;

                        if (!runManager.isTemporaryWorld(otherWorld.dimension())) continue;

                        player.setHealth(sharedHealth);
                    }

                    SoulLink.LOGGER.debug(
                            "[REGEN EFFECT DEBUG] Applied {} HP healing from regeneration: {} -> {} ({} players in run)",
                            healToApply,
                            oldHealth,
                            sharedHealth,
                            playerCount);
                }
            } finally {
                isSyncing = false;
            }
        } else {
            // Revert the healing to the player since it hasn't reached the threshold yet
            regenPlayer.setHealth(sharedHealth);
        }
    }

    /**
     * Called when a player's absorption amount changes (from golden apples, etc). Updates the
     * master absorption and syncs to all other players.
     */
    public static void onAbsorptionChanged(ServerPlayer changedPlayer, float newAbsorption) {
        if (isSyncing) return;
        if (!shouldParticipateInSoulLink(changedPlayer)) return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive()) return;

        ServerLevel playerWorld = getPlayerWorld(changedPlayer);
        if (playerWorld == null) return;

        if (!runManager.isTemporaryWorld(playerWorld.dimension())) return;

        if (Math.abs(newAbsorption - sharedAbsorption) < 0.1f) return;

        isSyncing = true;
        try {
            float oldAbsorption = sharedAbsorption;
            sharedAbsorption = Mth.clamp(newAbsorption, 0.0f, 20.0f);

            MinecraftServer server = runManager.getServer();
            if (server == null) return;

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player == changedPlayer) continue;
                if (!shouldParticipateInSoulLink(player)) continue;

                ServerLevel otherWorld = getPlayerWorld(player);
                if (otherWorld == null) continue;

                if (!runManager.isTemporaryWorld(otherWorld.dimension())) continue;

                player.setAbsorptionAmount(sharedAbsorption);
            }

            SoulLink.LOGGER.debug(
                    "Absorption synced: {} -> {} (from {})",
                    oldAbsorption,
                    sharedAbsorption,
                    changedPlayer.getName().getString());

        } finally {
            isSyncing = false;
        }
    }

    /**
     * Called when a player naturally regenerates health (from saturation/hunger). The healing
     * amount is divided by the number of players in the run to normalize regen speed.
     *
     * Without this, N players = Nx regen speed since each player's regen would stack.
     */
    public static void onNaturalRegen(ServerPlayer regenPlayer, float healAmount) {
        if (isSyncing) return;
        if (!shouldParticipateInSoulLink(regenPlayer)) return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive()) return;

        ServerLevel playerWorld = getPlayerWorld(regenPlayer);
        if (playerWorld == null) return;

        if (!runManager.isTemporaryWorld(playerWorld.dimension())) return;

        MinecraftServer server = runManager.getServer();
        if (server == null) return;

        int playerCount = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!shouldParticipateInSoulLink(player)) continue;
            ServerLevel world = getPlayerWorld(player);
            if (world != null && runManager.isTemporaryWorld(world.dimension())) {
                playerCount++;
            }
        }

        if (playerCount == 0) return;

        // Divide the heal amount by player count and accumulate
        float normalizedHeal = healAmount / playerCount;
        regenAccumulator += normalizedHeal;

        SoulLink.LOGGER.debug(
                "[REGEN DEBUG] Player {} healed {} HP, normalized to {} ({} players), accumulator now {}",
                regenPlayer.getName().getString(),
                healAmount,
                normalizedHeal,
                playerCount,
                regenAccumulator);

        // Only apply healing when we've accumulated at least 0.5 HP (prevents constant tiny
        // updates)
        if (regenAccumulator >= 0.5f) {
            float healToApply = regenAccumulator;
            regenAccumulator = 0.0f;

            isSyncing = true;
            try {
                float oldHealth = sharedHealth;
                sharedHealth = Mth.clamp(sharedHealth + healToApply, 0.0f, getMaxHealth());

                if (sharedHealth > oldHealth) {
                    // Sync to all players
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        if (!shouldParticipateInSoulLink(player)) continue;
                        ServerLevel otherWorld = getPlayerWorld(player);
                        if (otherWorld == null) continue;

                        if (!runManager.isTemporaryWorld(otherWorld.dimension())) continue;

                        player.setHealth(sharedHealth);
                    }

                    SoulLink.LOGGER.debug(
                            "[REGEN DEBUG] Applied {} HP healing: {} -> {} ({} players in run)",
                            healToApply,
                            oldHealth,
                            sharedHealth,
                            playerCount);
                }
            } finally {
                isSyncing = false;
            }
        }
    }

    /**
     * Called when a player's hunger changes. Updates master values and syncs to all other players.
     */
    public static void onPlayerHungerChanged(ServerPlayer player, int newFoodLevel, float newSaturation) {
        if (isSyncing) return;
        if (!shouldParticipateInSoulLink(player)) return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive()) return;

        ServerLevel playerWorld = getPlayerWorld(player);
        if (playerWorld == null) return;

        if (!runManager.isTemporaryWorld(playerWorld.dimension())) return;

        isSyncing = true;
        try {
            boolean foodChanged = newFoodLevel != sharedHunger;
            boolean satChanged = Math.abs(newSaturation - sharedSaturation) > 0.01f;

            if (!foodChanged && !satChanged) {
                return;
            }

            sharedHunger = Mth.clamp(newFoodLevel, 0, 20);
            sharedSaturation = Mth.clamp(newSaturation, 0.0f, 20.0f);

            MinecraftServer server = runManager.getServer();
            if (server == null) return;

            for (ServerPlayer otherPlayer : server.getPlayerList().getPlayers()) {
                if (otherPlayer == player) continue;
                if (!shouldParticipateInSoulLink(otherPlayer)) continue;

                ServerLevel otherWorld = getPlayerWorld(otherPlayer);
                if (otherWorld == null) continue;

                if (!runManager.isTemporaryWorld(otherWorld.dimension())) continue;

                otherPlayer.getFoodData().setFoodLevel(sharedHunger);
                otherPlayer.getFoodData().setSaturation(sharedSaturation);
            }

            SoulLink.LOGGER.debug("Hunger synced: Food={}, Saturation={}", sharedHunger, sharedSaturation);

        } finally {
            isSyncing = false;
        }
    }

    /**
     * Called when a player's hunger/saturation drains from natural regeneration. The drain is
     * divided by the number of players to normalize drain rate.
     *
     * Without this, N players = Nx hunger drain since each player's regen consumes hunger.
     */
    public static void onNaturalHungerDrain(ServerPlayer drainPlayer, int foodDrain, float satDrain) {
        if (isSyncing) return;
        if (!shouldParticipateInSoulLink(drainPlayer)) return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive()) return;

        ServerLevel playerWorld = getPlayerWorld(drainPlayer);
        if (playerWorld == null) return;

        if (!runManager.isTemporaryWorld(playerWorld.dimension())) return;

        MinecraftServer server = runManager.getServer();
        if (server == null) return;

        int playerCount = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!shouldParticipateInSoulLink(player)) continue;
            ServerLevel world = getPlayerWorld(player);
            if (world != null && runManager.isTemporaryWorld(world.dimension())) {
                playerCount++;
            }
        }

        if (playerCount == 0) return;

        // Divide the drain by player count and accumulate
        float normalizedFoodDrain = (float) foodDrain / playerCount;
        float normalizedSatDrain = satDrain / playerCount;

        hungerDrainAccumulator += normalizedFoodDrain;
        saturationDrainAccumulator += normalizedSatDrain;

        // Check if we should apply the accumulated drain
        boolean shouldApply = hungerDrainAccumulator >= 1.0f || saturationDrainAccumulator >= 0.5f;

        if (shouldApply) {
            isSyncing = true;
            try {
                // Apply accumulated food drain (whole numbers only)
                int foodToApply = (int) hungerDrainAccumulator;
                if (foodToApply > 0) {
                    sharedHunger = Mth.clamp(sharedHunger - foodToApply, 0, 20);
                    hungerDrainAccumulator -= foodToApply;
                }

                // Apply accumulated saturation drain
                if (saturationDrainAccumulator >= 0.1f) {
                    float satToApply = saturationDrainAccumulator;
                    sharedSaturation = Mth.clamp(sharedSaturation - satToApply, 0.0f, 20.0f);
                    saturationDrainAccumulator = 0.0f;
                }

                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (!shouldParticipateInSoulLink(player)) continue;
                    ServerLevel otherWorld = getPlayerWorld(player);
                    if (otherWorld == null) continue;

                    if (!runManager.isTemporaryWorld(otherWorld.dimension())) continue;

                    player.getFoodData().setFoodLevel(sharedHunger);
                    player.getFoodData().setSaturation(sharedSaturation);
                }

                SoulLink.LOGGER.debug(
                        "Natural hunger drain applied: Food={}, Sat={} (from {} players)",
                        sharedHunger,
                        sharedSaturation,
                        playerCount);
            } finally {
                isSyncing = false;
            }
        }
    }

    /**
     * Periodic sync check - ensures all players stay in sync. Called from server tick event.
     */
    public static void tickSync(MinecraftServer server) {
        if (isSyncing) return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive()) return;

        // Only run every 20 ticks (1 second)
        if (server.getTickCount() % 20 != 0) return;

        isSyncing = true;
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!shouldParticipateInSoulLink(player)) continue;
                ServerLevel playerWorld = getPlayerWorld(player);
                if (playerWorld == null) continue;

                if (!runManager.isTemporaryWorld(playerWorld.dimension())) continue;

                float playerHealth = player.getHealth();
                float playerAbsorption = player.getAbsorptionAmount();
                int playerFood = player.getFoodData().getFoodLevel();
                float playerSat = player.getFoodData().getSaturationLevel();

                if (Math.abs(playerHealth - sharedHealth) > 0.5f) {
                    player.setHealth(sharedHealth);
                }
                if (Math.abs(playerAbsorption - sharedAbsorption) > 0.5f) {
                    player.setAbsorptionAmount(sharedAbsorption);
                }
                if (playerFood != sharedHunger) {
                    player.getFoodData().setFoodLevel(sharedHunger);
                }
                if (Math.abs(playerSat - sharedSaturation) > 0.5f) {
                    player.getFoodData().setSaturation(sharedSaturation);
                }
            }
        } finally {
            isSyncing = false;
        }
    }

    /**
     * Checks if the system is currently syncing (to prevent loops).
     */
    public static boolean isSyncing() {
        return isSyncing;
    }

    /**
     * Sets the syncing flag. Used by other shared handlers (like SharedPotionHandler) to prevent
     * heal/damage operations from triggering additional syncs.
     */
    public static void setSyncing(boolean syncing) {
        isSyncing = syncing;
    }

    /**
     * Executes a task with syncing temporarily disabled.
     */
    public static void withSyncingDisabled(Runnable task) {
        boolean wasSyncing = isSyncing;
        setSyncing(true); // "Syncing" means we are currently applying a sync, so ignore local
        // changes
        try {
            task.run();
        } finally {
            setSyncing(wasSyncing);
        }
    }

    // Getters

    public static float getSharedHealth() {
        return sharedHealth;
    }

    public static int getSharedHunger() {
        return sharedHunger;
    }

    public static float getSharedSaturation() {
        return sharedSaturation;
    }

    public static float getSharedAbsorption() {
        return sharedAbsorption;
    }

    /**
     * Force sets the shared health (for admin/debug purposes).
     */
    public static void setSharedHealth(float health, MinecraftServer server) {
        float clampedHealth = Mth.clamp(health, 0.0f, getMaxHealth());

        if (server == null) {
            sharedHealth = clampedHealth;
            return;
        }

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive()) {
            sharedHealth = clampedHealth;
            return;
        }
        sharedHealth = clampedHealth;

        isSyncing = true;
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ServerLevel playerWorld = getPlayerWorld(player);
                if (playerWorld == null) continue;

                if (runManager.isTemporaryWorld(playerWorld.dimension())) {
                    // Skip spectators and creative mode players for health sync
                    if (player.isSpectator() || player.isCreative()) {
                        continue;
                    }
                    player.setHealth(sharedHealth);
                }
            }
        } finally {
            isSyncing = false;
        }
    }
}
