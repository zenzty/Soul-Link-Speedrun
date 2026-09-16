package net.zenzty.soullink.mixin.player;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.event.EventRegistry;
import net.zenzty.soullink.server.manhunt.ManhuntManager;
import net.zenzty.soullink.server.run.RunManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for ServerPlayerEntity: Runners (and non-Manhunt) trigger game over on death. Hunters use
 * custom respawn (spectator, drop items, 5s countdown, respawn).
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerEntityMixin {

    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void onDeathHandler(DamageSource damageSource, CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        RunManager runManager = RunManager.getInstance();

        if (runManager == null || !runManager.isRunActive()) {
            return;
        }

        if (runManager.isManhuntRun() && ManhuntManager.getInstance().isHunter(player)) {
            SoulLink.LOGGER.info(
                    "Hunter {} died - triggering custom respawn",
                    player.getName().getString());
            ci.cancel();
            player.setHealth(player.getMaxHealth());
            EventRegistry.handleHunterDeath(player, damageSource, runManager);
            return;
        }

        SoulLink.LOGGER.info(
                "Player {} died during active run - triggering game over",
                player.getName().getString());

        ci.cancel();

        Component deathMessage = damageSource.getLocalizedDeathMessage(player);
        Component formattedDeathMessage = Component.empty()
                .append(RunManager.getPrefix())
                .append(Component.literal("☠ ").withStyle(ChatFormatting.DARK_RED))
                .append(deathMessage.copy().withStyle(ChatFormatting.RED));
        runManager.getServer().getPlayerList().broadcastSystemMessage(formattedDeathMessage, false);

        player.setHealth(player.getMaxHealth());

        List<Holder<MobEffect>> effectsToRemove = player.getActiveEffects().stream()
                .filter(effect -> !effect.getEffect().value().isBeneficial())
                .map(MobEffectInstance::getEffect)
                .toList();

        effectsToRemove.forEach(player::removeEffect);
        player.clearFire();

        if (!runManager.isGameOver()) {
            net.minecraft.server.MinecraftServer server = runManager.getServer();
            if (server != null) {
                server.execute(() -> {
                    if (!runManager.isGameOver()) {
                        runManager.triggerGameOver();
                    }
                });
            }
        }
    }
}
