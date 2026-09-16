package net.zenzty.soullink.server.settings;

import net.minecraft.world.Difficulty;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.run.RunState;

/**
 * Holds all configurable settings for the Soul Link mod. Settings are applied on the next run when
 * confirmed in the settings GUI.
 */
public class Settings {

    private static final Settings INSTANCE = new Settings();

    // Current active settings (used during runs)
    private Difficulty difficulty = Difficulty.NORMAL;
    private boolean halfHeartMode = false;
    private boolean sharedPotions = false;
    private boolean sharedJumping = false;
    private boolean manhuntMode = false;
    private boolean syncedInventory = false;
    private boolean damageLogEnabled = true; // Combat log - can be toggled immediately

    // Pending settings to be applied on next run
    private SettingsSnapshot pendingSnapshot = null;

    private Settings() {}

    public static Settings getInstance() {
        return INSTANCE;
    }

    // ==================== DIFFICULTY ====================

    public Difficulty getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(Difficulty difficulty) {
        // Normalize Peaceful to Easy since mod doesn't support it
        this.difficulty = (difficulty == Difficulty.PEACEFUL) ? Difficulty.EASY : difficulty;
    }

    /**
     * Cycles to the next difficulty level. Order: EASY -> NORMAL -> HARD -> EASY (no Peaceful)
     */
    public Difficulty getNextDifficulty() {
        return switch (difficulty) {
            case PEACEFUL, EASY -> Difficulty.NORMAL;
            case NORMAL -> Difficulty.HARD;
            case HARD -> Difficulty.EASY;
        };
    }

    // ==================== HALF HEART MODE ====================

    public boolean isHalfHeartMode() {
        return halfHeartMode;
    }

    public void setHalfHeartMode(boolean halfHeartMode) {
        this.halfHeartMode = halfHeartMode;
    }

    // ==================== SHARED POTIONS ====================

    public boolean isSharedPotions() {
        return sharedPotions;
    }

    public void setSharedPotions(boolean sharedPotions) {
        this.sharedPotions = sharedPotions;
    }

    // ==================== SHARED JUMPING ====================

    public boolean isSharedJumping() {
        return sharedJumping;
    }

    public void setSharedJumping(boolean sharedJumping) {
        this.sharedJumping = sharedJumping;
    }

    // ==================== MANHUNT MODE ====================

    /**
     * Whether Manhunt mode is enabled. When true, a Runner/Hunter selector is shown before the run.
     * Runners share Soul Link (health, hunger); Hunters use vanilla mechanics and get tracking
     * compasses.
     */
    public boolean isManhuntMode() {
        return manhuntMode;
    }

    /**
     * Whether Manhunt will be enabled for the next run. If the player confirmed changes in /chaos
     * during an active run, those are pending and this returns the pending Manhunt value; otherwise
     * the current setting. Use this when deciding to open the Runner/Hunter selector before
     * startRun, because applyPendingSettings runs when the new run actually begins.
     */
    public boolean isManhuntModeForNextRun() {
        return pendingSnapshot != null ? pendingSnapshot.manhuntMode() : manhuntMode;
    }

    public void setManhuntMode(boolean manhuntMode) {
        this.manhuntMode = manhuntMode;
    }

    // ==================== SYNCED INVENTORY ====================

    /**
     * Whether Synced Inventory mode is enabled. When true, all players in the run share the same
     * inventory (main, hotbar, armor, offhand); changes by one player appear for everyone.
     */
    public boolean isSyncedInventory() {
        return syncedInventory;
    }

    public void setSyncedInventory(boolean syncedInventory) {
        this.syncedInventory = syncedInventory;
    }

    // ==================== DAMAGE LOG ====================

    public boolean isDamageLogEnabled() {
        return damageLogEnabled;
    }

    public void setDamageLogEnabled(boolean damageLogEnabled) {
        this.damageLogEnabled = damageLogEnabled;
    }

    // ==================== UTILITY ====================

    /**
     * Returns the pending snapshot if one exists (changes confirmed in /chaos during an active
     * run). Used by the Chaos GUI to pre-fill with pending values so the player sees what is
     * already queued instead of the in-memory (current-run) values.
     */
    public SettingsSnapshot getPendingSnapshotOrNull() {
        return pendingSnapshot;
    }

    /**
     * Creates a copy of the current settings for temporary editing in the GUI.
     */
    public SettingsSnapshot createSnapshot() {
        return new SettingsSnapshot(
                difficulty, halfHeartMode, sharedPotions, sharedJumping, manhuntMode, syncedInventory);
    }

    /**
     * Applies settings from a snapshot. If a run is active, all changes except difficulty are
     * deferred until the next run.
     */
    public void applySnapshot(SettingsSnapshot snapshot) {
        boolean runActive = isChaosLocked();

        if (runActive) {
            // Already queued this exact snapshot (e.g. re-confirm without changing) – no-op
            if (pendingSnapshot != null && snapshot.equals(pendingSnapshot)) {
                return;
            }
            // User reverted to the current applied state – clear pending
            SettingsSnapshot current = createSnapshot();
            if (snapshot.equals(current)) {
                this.pendingSnapshot = null;
                return;
            }

            // Defer all changes until next run
            this.pendingSnapshot = snapshot;
            SoulLink.LOGGER.info("Settings changes queued for next run: {}", snapshot);
        } else {
            // No active run - apply immediately
            applySnapshotInternal(snapshot);
            this.pendingSnapshot = null;
        }
    }

    /**
     * Internal method to apply all settings from a snapshot immediately.
     */
    private void applySnapshotInternal(SettingsSnapshot snapshot) {
        setDifficulty(snapshot.difficulty());
        this.halfHeartMode = snapshot.halfHeartMode();
        this.sharedPotions = snapshot.sharedPotions();
        this.sharedJumping = snapshot.sharedJumping();
        this.manhuntMode = snapshot.manhuntMode();
        this.syncedInventory = snapshot.syncedInventory();

        SoulLink.LOGGER.info(
                "Settings applied: Difficulty={}, HalfHeart={}, SharedPotions={}, SharedJumping={}, Manhunt={}, SyncedInventory={}",
                difficulty,
                halfHeartMode,
                sharedPotions,
                sharedJumping,
                manhuntMode,
                syncedInventory);
    }

    /**
     * Applies any pending settings. Called when a new run starts.
     */
    public void applyPendingSettings() {
        if (pendingSnapshot != null) {
            SoulLink.LOGGER.info("Applying pending settings for new run...");
            applySnapshotInternal(pendingSnapshot);
            pendingSnapshot = null;
        }
    }

    public void restorePendingSnapshot(SettingsSnapshot snapshot) {
        this.pendingSnapshot = snapshot;
    }

    private static boolean isChaosLocked() {
        try {
            RunManager runManager = RunManager.getInstance();
            RunState state = runManager.getGameState();
            return state == RunState.RUNNING || state == RunState.GENERATING_WORLD || state == RunState.GAMEOVER;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * Immutable snapshot of settings for comparison and temporary editing.
     */
    public record SettingsSnapshot(
            Difficulty difficulty,
            boolean halfHeartMode,
            boolean sharedPotions,
            boolean sharedJumping,
            boolean manhuntMode,
            boolean syncedInventory) {}
}
