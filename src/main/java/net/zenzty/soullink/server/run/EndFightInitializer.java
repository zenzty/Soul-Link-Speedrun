package net.zenzty.soullink.server.run;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.mixin.server.ServerWorldAccessor;

public final class EndFightInitializer {

    private EndFightInitializer() {}

    public static boolean initialize(ServerLevel endWorld) {
        if (endWorld == null) {
            return false;
        }

        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                endWorld.getChunk(x, z);
            }
        }

        EnderDragonFight existingFight = endWorld.getDragonFight();
        if (existingFight != null) {
            return true;
        }

        try {
            EnderDragonFight dragonFight = EnderDragonFight.createDefault();
            dragonFight.init(endWorld, endWorld.getSeed(), BlockPos.ZERO);
            ((ServerWorldAccessor) endWorld).setEnderDragonFight(dragonFight);
            return endWorld.getDragonFight() != null;
        } catch (Exception e) {
            SoulLink.LOGGER.error("Failed to create EnderDragonFight", e);
            return false;
        }
    }
}
