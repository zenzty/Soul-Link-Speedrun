package net.zenzty.soullink.server.run;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.zenzty.soullink.SoulLink;

/**
 * Manages the speedrun timer including start, stop, pause, and time formatting. Tracks player input
 * to start the timer on first movement.
 */
public class TimerService {

    private long startTimeMillis;
    private long elapsedTimeMillis;
    private boolean timerRunning;
    private boolean timerStartedThisRun;

    // Timer start: wait for player input (movement or camera)
    private boolean waitingForInput;
    private java.util.UUID trackedPlayerId;
    private double trackedX, trackedZ;
    private float trackedYaw, trackedPitch;

    /**
     * Resets the timer state for a new run.
     */
    public void reset() {
        timerStartedThisRun = false;
        timerRunning = false;
        waitingForInput = false;
        trackedPlayerId = null;
        elapsedTimeMillis = 0;
        startTimeMillis = 0;
    }

    /**
     * Starts waiting for player input to begin the timer.
     *
     * @param player The player to track for input
     */
    public void beginWaitingForInput(ServerPlayer player) {
        if (!timerStartedThisRun && !waitingForInput) {
            waitingForInput = true;
            trackedPlayerId = player.getUUID();
            trackedX = player.getX();
            trackedZ = player.getZ();
            trackedYaw = player.getYRot();
            trackedPitch = player.getXRot();
        }
    }

    /**
     * Stops the game timer.
     */
    public void stop() {
        if (timerRunning) {
            elapsedTimeMillis = System.currentTimeMillis() - startTimeMillis;
            timerRunning = false;
        }
    }

    /**
     * Gets the current elapsed time in milliseconds.
     */
    public long getElapsedTimeMillis() {
        if (timerRunning) {
            long elapsed = System.currentTimeMillis() - startTimeMillis;
            return Math.max(0, elapsed);
        }
        return elapsedTimeMillis;
    }

    /**
     * Formats the elapsed time as HH:MM:SS.
     */
    public String getFormattedTime() {
        long elapsed = getElapsedTimeMillis();
        long seconds = (elapsed / 1000) % 60;
        long minutes = (elapsed / (1000 * 60)) % 60;
        long hours = (elapsed / (1000 * 60 * 60));
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    /**
     * Called every server tick to check for input and update timer display.
     *
     * @param server The Minecraft server
     * @param isInRunCheck Function to check if a player is in the run
     * @param skipActionBarFor When this returns true for a player, the action bar (timer or ready
     *        message) is not sent so other messages (e.g. compass tracking in Manhunt) can stay
     *        visible
     * @return true if timer is running, false otherwise
     */
    public boolean tick(
            MinecraftServer server,
            java.util.function.Predicate<ServerPlayer> isInRunCheck,
            java.util.function.Predicate<ServerPlayer> skipActionBarFor) {
        // Wait for player input (movement or camera) to start timer
        if (waitingForInput) {
            if (checkForInput(server, isInRunCheck)) {
                // Player moved or looked around - START THE TIMER!
                waitingForInput = false;
                timerStartedThisRun = true;
                startTimeMillis = System.currentTimeMillis();
                timerRunning = true;
                trackedPlayerId = null;
                SoulLink.LOGGER.info("Player input detected! Timer started at 00:00:00");
            } else {
                // Show ready message - timer at 00:00:00 waiting for input
                if (server.getTickCount() % 10 == 0) {
                    Component readyText = Component.empty()
                            .append(Component.literal("00:00:00").withStyle(ChatFormatting.WHITE))
                            .append(Component.literal(" - Move to start").withStyle(ChatFormatting.GRAY));
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        if (isInRunCheck.test(player) && !skipActionBarFor.test(player)) {
                            player.sendOverlayMessage(readyText);
                        }
                    }
                }
            }
            return false;
        }

        if (!timerRunning) {
            return false;
        }

        // Update action bar every 10 ticks (0.5 seconds) for performance
        if (server.getTickCount() % 10 == 0) {
            Component actionBarText = Component.literal(getFormattedTime()).withStyle(ChatFormatting.WHITE);

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (isInRunCheck.test(player) && !skipActionBarFor.test(player)) {
                    player.sendOverlayMessage(actionBarText);
                }
            }
        }

        return true;
    }

    /**
     * Check if the player has moved or looked around to start the timer.
     */
    private boolean checkForInput(MinecraftServer server, java.util.function.Predicate<ServerPlayer> isInRunCheck) {
        ServerPlayer trackedPlayer = null;
        if (trackedPlayerId != null) {
            trackedPlayer = server.getPlayerList().getPlayer(trackedPlayerId);
        }

        if (trackedPlayer == null || trackedPlayer.hasDisconnected()) {
            // Find a new player to track
            var players = server.getPlayerList().getPlayers().stream()
                    .filter(isInRunCheck)
                    .toList();
            if (players.isEmpty()) {
                return false;
            }
            trackedPlayer = players.get(0);
            trackedPlayerId = trackedPlayer.getUUID();
            // Re-capture their position
            trackedX = trackedPlayer.getX();
            trackedZ = trackedPlayer.getZ();
            trackedYaw = trackedPlayer.getYRot();
            trackedPitch = trackedPlayer.getXRot();
            return false;
        }

        // Check for horizontal movement
        double dx = Math.abs(trackedPlayer.getX() - trackedX);
        double dz = Math.abs(trackedPlayer.getZ() - trackedZ);

        // Check for look direction change
        float dYaw = Math.abs(trackedPlayer.getYRot() - trackedYaw);
        float dPitch = Math.abs(trackedPlayer.getXRot() - trackedPitch);

        // Handle yaw wrapping (e.g., 359 to 1 degrees)
        if (dYaw > 180) {
            dYaw = 360 - dYaw;
        }

        // Thresholds for detecting intentional input
        boolean hasMoved = dx > 0.05 || dz > 0.05;
        boolean hasLooked = dYaw > 1.0f || dPitch > 1.0f;

        return hasMoved || hasLooked;
    }

    public boolean isRunning() {
        return timerRunning;
    }

    public boolean isWaitingForInput() {
        return waitingForInput;
    }

    public boolean hasStartedThisRun() {
        return timerStartedThisRun;
    }
    /**
     * Sets the elapsed time in milliseconds. Used when restoring a saved run state.
     * Automatically recalibrates the start time if the timer is actively running.
     */
    public void setElapsedTimeMillis(long timeMillis) {
        this.elapsedTimeMillis = timeMillis;
        if (this.timerRunning) {
            this.startTimeMillis = System.currentTimeMillis() - timeMillis;
        }
    }

    /**
     * Forcefully restores the execution state of the timer when loading data.
     */
    public void setRunning(boolean running) {
        this.timerRunning = running;
        if (running) {
            this.timerStartedThisRun = true;
            this.waitingForInput = false;
            this.startTimeMillis = System.currentTimeMillis() - this.elapsedTimeMillis;
        }
    }
}
