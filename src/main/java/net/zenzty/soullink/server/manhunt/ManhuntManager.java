package net.zenzty.soullink.server.manhunt;

import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;
import net.zenzty.soullink.SoulLink;

/**
 * Manages player roles in Manhunt mode. Runners share health/stats (Soul Link mechanic). Hunters
 * operate with vanilla mechanics.
 */
public class ManhuntManager {

    private static volatile ManhuntManager instance;

    // Player roles
    private final Set<UUID> runners = new HashSet<>();
    private final Set<UUID> hunters = new HashSet<>();

    // Team names for scoreboard
    public static final String RUNNERS_TEAM = "soullink_runners";
    public static final String HUNTERS_TEAM = "soullink_hunters";

    private ManhuntManager() {}

    public static synchronized ManhuntManager getInstance() {
        if (instance == null) {
            instance = new ManhuntManager();
        }
        return instance;
    }

    /**
     * Resets all player roles. Called on server start and when a new run begins.
     */
    public void resetRoles() {
        runners.clear();
        hunters.clear();
        SoulLink.LOGGER.info("Manhunt roles reset");
    }

    public void restoreRoles(Collection<UUID> restoredRunners, Collection<UUID> restoredHunters) {
        runners.clear();
        hunters.clear();
        runners.addAll(restoredRunners);
        hunters.addAll(restoredHunters);
        SoulLink.LOGGER.info("Restored manhunt roles: {} runners, {} hunters", runners.size(), hunters.size());
    }

    /**
     * Sets a player as a Runner (shares health/stats).
     */
    public void setRunner(UUID playerId) {
        hunters.remove(playerId);
        runners.add(playerId);
        SoulLink.LOGGER.debug("Player {} set as Runner", playerId);
    }

    /**
     * Sets a player as a Hunter (vanilla mechanics).
     */
    public void setHunter(UUID playerId) {
        runners.remove(playerId);
        hunters.add(playerId);
        SoulLink.LOGGER.debug("Player {} set as Hunter", playerId);
    }

    /**
     * Toggles player role and returns the new role (true = runner, false = hunter).
     */
    public boolean toggleRole(UUID playerId) {
        if (runners.contains(playerId)) {
            setHunter(playerId);
            return false;
        } else {
            setRunner(playerId);
            return true;
        }
    }

    /**
     * Checks if a player is a Runner (participates in Soul Link).
     */
    public boolean isSpeedrunner(ServerPlayer player) {
        return player != null && runners.contains(player.getUUID());
    }

    /**
     * Checks if a player is a Runner by UUID.
     */
    public boolean isSpeedrunner(UUID playerId) {
        return playerId != null && runners.contains(playerId);
    }

    /**
     * Checks if a player is a Hunter (vanilla mechanics).
     */
    public boolean isHunter(ServerPlayer player) {
        return player != null && hunters.contains(player.getUUID());
    }

    /**
     * Checks if a player is a Hunter by UUID.
     */
    public boolean isHunter(UUID playerId) {
        return playerId != null && hunters.contains(playerId);
    }

    /**
     * Gets all Runner UUIDs.
     */
    public Set<UUID> getRunners() {
        return new HashSet<>(runners);
    }

    /**
     * Gets all Hunter UUIDs.
     */
    public Set<UUID> getHunters() {
        return new HashSet<>(hunters);
    }

    /**
     * Checks if any runners have been assigned.
     */
    public boolean hasRunners() {
        return !runners.isEmpty();
    }

    /**
     * Checks if any hunters have been assigned.
     */
    public boolean hasHunters() {
        return !hunters.isEmpty();
    }

    /**
     * Creates scoreboard teams for Runners and Hunters. Teams provide colored name tags and
     * prefixes.
     */
    public void createTeams(MinecraftServer server) {
        if (server == null) return;

        Scoreboard scoreboard = server.getScoreboard();

        // Create or get Runners team
        PlayerTeam runnersTeam = scoreboard.getPlayerTeam(RUNNERS_TEAM);
        if (runnersTeam == null) {
            runnersTeam = scoreboard.addPlayerTeam(RUNNERS_TEAM);
        }
        runnersTeam.setDisplayName(Component.literal("Runners"));
        runnersTeam.setColor(Optional.of(TeamColor.WHITE));
        runnersTeam.setPlayerPrefix(Component.empty()
                .append(Component.literal("Runner").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                .append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY)));

        // Create or get Hunters team
        PlayerTeam huntersTeam = scoreboard.getPlayerTeam(HUNTERS_TEAM);
        if (huntersTeam == null) {
            huntersTeam = scoreboard.addPlayerTeam(HUNTERS_TEAM);
        }
        huntersTeam.setDisplayName(Component.literal("Hunters"));
        huntersTeam.setColor(Optional.of(TeamColor.WHITE));
        huntersTeam.setPlayerPrefix(Component.empty()
                .append(Component.literal("Hunter").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
                .append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY)));

        SoulLink.LOGGER.info("Manhunt teams created/updated");
    }

    /**
     * Assigns all players to their respective scoreboard teams.
     */
    public void assignPlayersToTeams(MinecraftServer server) {
        if (server == null) return;

        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam runnersTeam = scoreboard.getPlayerTeam(RUNNERS_TEAM);
        PlayerTeam huntersTeam = scoreboard.getPlayerTeam(HUNTERS_TEAM);

        if (runnersTeam == null || huntersTeam == null) {
            createTeams(server);
            runnersTeam = scoreboard.getPlayerTeam(RUNNERS_TEAM);
            huntersTeam = scoreboard.getPlayerTeam(HUNTERS_TEAM);
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String playerName = player.getGameProfile().name();

            // Remove from any existing team first
            PlayerTeam currentTeam = scoreboard.getPlayersTeam(playerName);
            if (currentTeam != null) {
                scoreboard.removePlayerFromTeam(playerName, currentTeam);
            }

            // Add to appropriate team
            if (runners.contains(player.getUUID())) {
                scoreboard.addPlayerToTeam(playerName, runnersTeam);
            } else if (hunters.contains(player.getUUID())) {
                scoreboard.addPlayerToTeam(playerName, huntersTeam);
            }
        }

        SoulLink.LOGGER.info("Players assigned to teams: {} runners, {} hunters", runners.size(), hunters.size());
    }

    /**
     * Cleans up teams when run ends.
     */
    public void cleanupTeams(MinecraftServer server) {
        if (server == null) return;

        Scoreboard scoreboard = server.getScoreboard();

        // Remove all players from teams
        PlayerTeam runnersTeam = scoreboard.getPlayerTeam(RUNNERS_TEAM);
        PlayerTeam huntersTeam = scoreboard.getPlayerTeam(HUNTERS_TEAM);

        if (runnersTeam != null) {
            for (String member : new HashSet<>(runnersTeam.getPlayers())) {
                scoreboard.removePlayerFromTeam(member, runnersTeam);
            }
        }

        if (huntersTeam != null) {
            for (String member : new HashSet<>(huntersTeam.getPlayers())) {
                scoreboard.removePlayerFromTeam(member, huntersTeam);
            }
        }
    }
}
