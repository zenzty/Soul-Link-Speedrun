package net.zenzty.soullink.mixin.player;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import net.zenzty.soullink.server.health.SharedStatsHandler;
import net.zenzty.soullink.server.run.RunManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for HungerManager to sync hunger changes between players.
 */
@Mixin(FoodData.class)
public abstract class HungerManagerMixin {

    @Shadow
    private int foodLevel;

    @Shadow
    private float saturationLevel;

    @Unique private int previousFoodLevel = 20;

    @Unique private float previousSaturation = 5.0f;

    @Unique private boolean hungerBaselineReady;

    @Inject(method = "tick", at = @At("HEAD"))
    private void captureHungerBaseline(ServerPlayer player, CallbackInfo ci) {
        if (!this.hungerBaselineReady) {
            this.previousFoodLevel = this.foodLevel;
            this.previousSaturation = this.saturationLevel;
            this.hungerBaselineReady = true;
        }
    }

    /**
     * After each hunger update tick, check if values changed and sync. Note: In 1.21.11,
     * HungerManager.update() takes ServerPlayerEntity directly.
     *
     * Hunger drain from natural regeneration is divided by player count to prevent Nx drain rate.
     * Hunger gains from eating are synced normally.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void afterHungerUpdate(ServerPlayer player, CallbackInfo ci) {
        // Skip if syncing to prevent loops
        if (SharedStatsHandler.isSyncing()) {
            previousFoodLevel = this.foodLevel;
            previousSaturation = this.saturationLevel;
            return;
        }

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive()) {
            previousFoodLevel = this.foodLevel;
            previousSaturation = this.saturationLevel;
            return;
        }

        // Get the player's world - ServerPlayerEntity.getEntityWorld() returns ServerWorld directly
        ServerLevel serverWorld = player.level();

        if (!runManager.isTemporaryWorld(serverWorld.dimension())) {
            previousFoodLevel = this.foodLevel;
            previousSaturation = this.saturationLevel;
            return;
        }

        // Check if hunger/saturation changed
        boolean foodChanged = this.foodLevel != previousFoodLevel;
        boolean satChanged = Math.abs(this.saturationLevel - previousSaturation) > 0.01f;

        if (foodChanged || satChanged) {
            // Determine if this is hunger drain (decrease) or hunger gain (eating)
            boolean isHungerDrain =
                    this.foodLevel < previousFoodLevel || this.saturationLevel < previousSaturation - 0.01f;

            if (isHungerDrain) {
                // Calculate the drain amounts (clamped to non-negative)
                int foodDrain = Math.max(0, previousFoodLevel - this.foodLevel);
                float satDrain = Math.max(0f, previousSaturation - this.saturationLevel);

                // Use normalized hunger drain (divided by player count)
                SharedStatsHandler.onNaturalHungerDrain(player, foodDrain, satDrain);
            } else {
                // Hunger gain (eating) - sync normally
                SharedStatsHandler.onPlayerHungerChanged(player, this.foodLevel, this.saturationLevel);
            }

            previousFoodLevel = this.foodLevel;
            previousSaturation = this.saturationLevel;
        }
    }

    /**
     * Track when food level is directly set.
     */
    @Inject(method = "setFoodLevel", at = @At("TAIL"))
    private void afterSetFoodLevel(int foodLevel, CallbackInfo ci) {
        previousFoodLevel = this.foodLevel;
    }

    /**
     * Track when saturation is directly set.
     */
    @Inject(method = "setSaturation", at = @At("TAIL"))
    private void afterSetSaturation(float saturationLevel, CallbackInfo ci) {
        previousSaturation = this.saturationLevel;
    }
}
