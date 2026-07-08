package net.zenzty.soullink.server.run;

import net.minecraft.core.BlockPos;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;
import java.util.UUID;

public record PooledRun(
        UUID runId, 
        RuntimeLevelHandle overworld, 
        RuntimeLevelHandle nether, 
        RuntimeLevelHandle end, 
        long seed, 
        BlockPos spawnPos) {
        
    public PooledRun withSpawn(BlockPos newSpawn) {
        return new PooledRun(this.runId, this.overworld, this.nether, this.end, this.seed, newSpawn);
    }
}