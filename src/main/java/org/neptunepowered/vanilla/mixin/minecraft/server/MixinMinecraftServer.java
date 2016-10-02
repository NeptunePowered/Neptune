/*
 * This file is part of NeptuneVanilla, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2015-2016, Jamie Mansfield <https://github.com/jamierocks>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.neptunepowered.vanilla.mixin.minecraft.server;

import static net.minecraft.server.MinecraftServer.getCurrentTimeMillis;

import co.aikar.timings.NeptuneTimings;
import co.aikar.timings.TimingsManager;
import co.aikar.timings.WorldTimingsHandler;
import com.mojang.authlib.GameProfile;
import net.canarymod.Canary;
import net.canarymod.ToolBox;
import net.canarymod.api.CommandBlockLogic;
import net.canarymod.api.ConfigurationManager;
import net.canarymod.api.OfflinePlayer;
import net.canarymod.api.PlayerListData;
import net.canarymod.api.PlayerReference;
import net.canarymod.api.Server;
import net.canarymod.api.chat.ChatComponent;
import net.canarymod.api.entity.living.humanoid.Player;
import net.canarymod.api.gui.GUIControl;
import net.canarymod.api.inventory.recipes.CraftingRecipe;
import net.canarymod.api.inventory.recipes.Recipe;
import net.canarymod.api.inventory.recipes.SmeltRecipe;
import net.canarymod.api.nbt.CompoundTag;
import net.canarymod.api.world.DimensionType;
import net.canarymod.api.world.World;
import net.canarymod.api.world.WorldManager;
import net.canarymod.chat.MessageReceiver;
import net.canarymod.config.Configuration;
import net.canarymod.hook.command.ConsoleCommandHook;
import net.canarymod.hook.system.ServerTickHook;
import net.canarymod.tasks.ServerTask;
import net.minecraft.command.ICommandManager;
import net.minecraft.command.ICommandSender;
import net.minecraft.crash.CrashReport;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkSystem;
import net.minecraft.network.ServerStatusResponse;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.profiler.PlayerUsageSnooper;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerProfileCache;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ITickable;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ReportedException;
import net.minecraft.util.Util;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.SaveHandler;
import org.apache.logging.log4j.Logger;
import org.neptunepowered.vanilla.NeptuneOfflinePlayer;
import org.neptunepowered.vanilla.interfaces.minecraft.server.IMixinMinecraftServer;
import org.neptunepowered.vanilla.interfaces.minecraft.world.IMixinWorld;
import org.neptunepowered.vanilla.interfaces.minecraft.world.storage.IMixinSaveHandler;
import org.neptunepowered.vanilla.world.NeptuneWorldManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Intrinsic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.FutureTask;

@Mixin(MinecraftServer.class)
@Implements(@Interface(iface = Server.class, prefix = "server$"))
public abstract class MixinMinecraftServer implements Server, IMixinMinecraftServer {

    @Shadow private static Logger logger;
    @Shadow @Final public Profiler theProfiler;
    @Shadow @Final private PlayerUsageSnooper usageSnooper;
    @Shadow @Final private ServerStatusResponse statusResponse;
    @Shadow @Final private List<ITickable> playersOnline;
    @Shadow @Final private Random random;
    @Shadow public WorldServer[] worldServers;
    @Shadow public long[][] timeOfLastDimensionTick;
    @Shadow public long[] tickTimeArray;
    @Shadow protected Queue<FutureTask<?>> futureTaskQueue;
    @Shadow private int tickCounter;
    @Shadow private boolean startProfiling;
    @Shadow private boolean serverRunning;
    @Shadow private ServerConfigurationManager serverConfigManager;
    @Shadow private long nanoTimeSinceStatusRefresh;

    private WorldManager worldManager = new NeptuneWorldManager();
    private long previousTick = -1L;

    @Shadow
    public abstract void initiateShutdown();

    @Shadow
    public abstract String getMinecraftVersion();

    @Shadow
    public abstract String[] getAllUsernames();

    @Shadow
    public abstract PlayerProfileCache getPlayerProfileCache();

    @Shadow
    public abstract ICommandManager getCommandManager();

    @Shadow
    public abstract String getHostname();

    @Shadow
    protected abstract void setUserMessage(String message);

    @Shadow
    public abstract boolean isServerRunning();

    @Shadow
    protected abstract void outputPercentRemaining(String message, int percent);

    @Shadow
    protected abstract void clearCurrentTask();

    @Shadow
    public abstract boolean getAllowNether();

    @Shadow
    public abstract NetworkSystem getNetworkSystem();

    @Shadow
    public abstract int getCurrentPlayerCount();

    @Shadow
    protected abstract void saveAllWorlds(boolean dontLog);

    /**
     * @author Jamie Mansfield - 26th April 2016
     * @reason Use the Canary configuration.
     */
    @Overwrite
    public int getMaxPlayers() {
        return Configuration.getServerConfig().getMaxPlayers();
    }

    /**
     * @author jamierocks - 2nd October 2016
     * @reason Add timings calls
     */
    @Overwrite
    protected void tick() {
        TimingsManager.FULL_SERVER_TICK.startTiming(); // Neptune - timings

        long i = System.nanoTime();
        ++this.tickCounter;

        if (this.startProfiling) {
            this.startProfiling = false;
            this.theProfiler.profilingEnabled = true;
            this.theProfiler.clearProfiling();
        }

        this.theProfiler.startSection("root");
        this.updateTimeLightAndEntities();

        if (i - this.nanoTimeSinceStatusRefresh >= 5000000000L) {
            this.nanoTimeSinceStatusRefresh = i;
            this.statusResponse.setPlayerCountData(new ServerStatusResponse.PlayerCountData(this.getMaxPlayers(), this.getCurrentPlayerCount()));
            GameProfile[] agameprofile = new GameProfile[Math.min(this.getCurrentPlayerCount(), 12)];
            int j = MathHelper.getRandomIntegerInRange(this.random, 0, this.getCurrentPlayerCount() - agameprofile.length);

            for (int k = 0; k < agameprofile.length; ++k) {
                agameprofile[k] = this.serverConfigManager.getPlayerList().get(j + k).getGameProfile();
            }

            Collections.shuffle(Arrays.asList(agameprofile));
            this.statusResponse.getPlayerCountData().setPlayers(agameprofile);
        }

        if (this.tickCounter % 900 == 0) {
            this.theProfiler.startSection("save");
            NeptuneTimings.worldSaveTimer.startTiming(); // Neptune - timings
            this.serverConfigManager.saveAllPlayerData();
            this.saveAllWorlds(true);
            NeptuneTimings.worldSaveTimer.stopTiming(); // Neptune - timings
            this.theProfiler.endSection();
        }

        this.theProfiler.startSection("tallying");
        this.tickTimeArray[this.tickCounter % 100] = System.nanoTime() - i;
        this.theProfiler.endSection();
        this.theProfiler.startSection("snooper");

        if (!this.usageSnooper.isSnooperRunning() && this.tickCounter > 100) {
            this.usageSnooper.startSnooper();
        }

        if (this.tickCounter % 6000 == 0) {
            this.usageSnooper.addMemoryStatsToSnooper();
        }

        this.theProfiler.endSection();
        this.theProfiler.endSection();

        TimingsManager.FULL_SERVER_TICK.stopTiming(); // Neptune - timings
    }

    /**
     * @author jamierocks - 2nd October 2016
     * @reason Add timings calls
     */
    @Overwrite
    public void updateTimeLightAndEntities() {
        // Neptune - ServerTickHook start
        new ServerTickHook(this.previousTick).call();
        long curTrack = System.nanoTime();
        // Neptune - ServerTickHook end

        NeptuneTimings.schedulerTimer.startTiming(); // Neptune - timings
        this.theProfiler.startSection("jobs");

        synchronized (this.futureTaskQueue) {
            while (!this.futureTaskQueue.isEmpty()) {
                Util.runTask((FutureTask) this.futureTaskQueue.poll(), logger);
            }
        }
        NeptuneTimings.schedulerTimer.stopTiming(); // Neptune - timings

        this.theProfiler.endStartSection("levels");

        for (int j = 0; j < this.worldServers.length; ++j) {
            long i = System.nanoTime();

            if (j == 0 || this.getAllowNether()) {
                WorldServer worldserver = this.worldServers[j];
                final WorldTimingsHandler timings = ((IMixinWorld) worldserver).getTimings(); // Neptune - timings

                this.theProfiler.startSection(worldserver.getWorldInfo().getWorldName());

                if (this.tickCounter % 20 == 0) {
                    this.theProfiler.startSection("timeSync");
                    this.serverConfigManager.sendPacketToAllPlayersInDimension(
                            new S03PacketTimeUpdate(worldserver.getTotalWorldTime(), worldserver.getWorldTime(),
                                    worldserver.getGameRules().getBoolean("doDaylightCycle")), worldserver.provider.getDimensionId());
                    this.theProfiler.endSection();
                }

                this.theProfiler.startSection("tick");

                try {
                    timings.doTick.startTiming(); // Neptune - timings
                    worldserver.tick();
                    timings.doTick.stopTiming(); // Neptune - timings
                } catch (Throwable throwable1) {
                    CrashReport crashreport = CrashReport.makeCrashReport(throwable1, "Exception ticking world");
                    worldserver.addWorldInfoToCrashReport(crashreport);
                    throw new ReportedException(crashreport);
                }

                try {
                    timings.tickEntities.startTiming(); // Neptune - timings
                    worldserver.updateEntities();
                    timings.tickEntities.stopTiming(); // Neptune - timings
                } catch (Throwable throwable) {
                    CrashReport crashreport1 = CrashReport.makeCrashReport(throwable, "Exception ticking world entities");
                    worldserver.addWorldInfoToCrashReport(crashreport1);
                    throw new ReportedException(crashreport1);
                }

                this.theProfiler.endSection();
                this.theProfiler.startSection("tracker");
                worldserver.getEntityTracker().updateTrackedEntities();
                this.theProfiler.endSection();
                this.theProfiler.endSection();
            }

            this.timeOfLastDimensionTick[j][this.tickCounter % 100] = System.nanoTime() - i;
        }

        this.theProfiler.endStartSection("connection");
        NeptuneTimings.connectionTimer.startTiming(); // Neptune - timings
        this.getNetworkSystem().networkTick();
        NeptuneTimings.connectionTimer.stopTiming(); // Neptune - timings
        this.theProfiler.endStartSection("players");
        NeptuneTimings.playerListTimer.startTiming(); // Neptune - timings
        this.serverConfigManager.onTick();
        NeptuneTimings.playerListTimer.stopTiming(); // Neptune - timings
        this.theProfiler.endStartSection("tickables");

        NeptuneTimings.tickablesTimer.startTiming(); // Neptune - timings
        for (int k = 0; k < this.playersOnline.size(); ++k) {
            this.playersOnline.get(k).update();
        }
        NeptuneTimings.tickablesTimer.stopTiming(); // Neptune - timings

        this.theProfiler.endSection();

        this.previousTick = System.nanoTime() - curTrack; // Neptune - ServerTickHook
    }

    @Intrinsic
    public String server$getHostname() {
        return this.getHostname();
    }

    @Override
    public int getNumPlayersOnline() {
        return serverConfigManager.getCurrentPlayerCount();
    }

    @Intrinsic
    public int server$getMaxPlayers() {
        return this.getMaxPlayers();
    }

    @Override
    public String[] getPlayerNameList() {
        return serverConfigManager.getAllUsernames();
    }

    @Override
    public String[] getKnownPlayerNames() {
        return this.getAllUsernames();
    }

    @Override
    public List<Player> getPlayerList() {
        return (List) this.serverConfigManager.getPlayerList();
    }

    @Override
    public String getDefaultWorldName() {
        return Configuration.getServerConfig().getDefaultWorldName();
    }

    @Override
    public WorldManager getWorldManager() {
        return this.worldManager;
    }

    @Override
    public boolean consoleCommand(String command) {
        ConsoleCommandHook commandHook = (ConsoleCommandHook) new ConsoleCommandHook(this, command).call();
        if (commandHook.isCanceled()) {
            return true;
        }

        final String[] args = command.split(" ");

        String commandName = args[0];
        if (commandName.startsWith("/")) {
            commandName = commandName.substring(1);
        }

        if (!Canary.commands().parseCommand(this, commandName, args)) {
            return this.getCommandManager().executeCommand((ICommandSender) this, command) > 0;
        }

        return true;
    }

    @Override
    public boolean consoleCommand(String command, Player player) {
        ConsoleCommandHook commandHook = (ConsoleCommandHook) new ConsoleCommandHook(player, command).call();
        if (commandHook.isCanceled()) {
            return true;
        }

        final String[] args = command.split(" ");

        String commandName = args[0];
        if (commandName.startsWith("/")) {
            commandName = commandName.substring(1);
        }

        if (!Canary.commands().parseCommand(player, commandName, args)) {
            if (Canary.ops().isOpped(player) || player.hasPermission("canary.vanilla." + commandName)) {
                return this.getCommandManager().executeCommand((ICommandSender) player, command) > 0;
            }
            return false;
        }

        return true;
    }

    @Override
    public boolean consoleCommand(String command, CommandBlockLogic cmdBlockLogic) {
        ConsoleCommandHook commandHook = (ConsoleCommandHook) new ConsoleCommandHook(cmdBlockLogic, command).call();
        if (commandHook.isCanceled()) {
            return true;
        }

        final String[] args = command.split(" ");

        String commandName = args[0];
        if (commandName.startsWith("/")) {
            commandName = commandName.substring(1);
        }

        // Don't pass to vanilla as that is already handled by CommandBlockLogic
        // This is only called if Minecraft found no command
        return Canary.commands().parseCommand(cmdBlockLogic, commandName, args);
    }

    @Override
    public void executeVanillaCommand(MessageReceiver caller, String command) {
        this.getCommandManager().executeCommand((ICommandSender) caller, command);
    }

    @Override
    public void setTimer(String uniqueName, int time) {

    }

    @Override
    public boolean isTimerExpired(String uniqueName) {
        return false;
    }

    @Override
    public Player matchPlayer(String player) {
        player = player.toLowerCase();

        Player lastPlayer = null;
        for (Player cPlayer : getConfigurationManager().getAllPlayers()) {
            if (cPlayer.getName().toLowerCase().equals(player)) {
                // Perfect match found
                lastPlayer = cPlayer;
                break;
            }
            if (cPlayer.getName().toLowerCase().contains(player)) {
                // Partial match
                if (lastPlayer != null) {
                    // Found multiple
                    return null;
                }
                lastPlayer = cPlayer;
            }
        }

        return lastPlayer;
    }

    @Override
    public OfflinePlayer getOfflinePlayer(String player) {
        UUID uuid = ToolBox.uuidFromUsername(player);
        if (uuid == null) {
            return null;
        }
        return this.getOfflinePlayer(uuid);
    }

    @Override
    public OfflinePlayer getOfflinePlayer(UUID uuid) {
        ISaveHandler saveHandler = MinecraftServer.getServer().getEntityWorld().getSaveHandler();

        if (saveHandler instanceof SaveHandler) {
            NBTTagCompound tagCompound = ((IMixinSaveHandler) saveHandler).readPlayerData(uuid);

            if (tagCompound != null) {
                GameProfile profile = getPlayerProfileCache().getProfileByUUID(uuid);

                if (profile != null) {
                    return new NeptuneOfflinePlayer(profile.getName(), uuid, (CompoundTag) tagCompound);
                } else {
                    return new NeptuneOfflinePlayer("PLAYER_NAME_UNKNOWN", uuid, (CompoundTag) tagCompound);
                }
            }

            return null;
        } else {
            throw new RuntimeException("ISaveHandler is not of type SaveHandler! Failing to load playerdata");
        }
    }

    @Override
    public PlayerReference matchKnownPlayer(String player) {
        PlayerReference reference = this.matchPlayer(player);
        if (reference == null) {
            reference = this.getOfflinePlayer(player);
        }
        return reference;
    }

    @Override
    public PlayerReference matchKnownPlayer(UUID uuid) {
        PlayerReference reference = this.getPlayerFromUUID(uuid);
        if (reference == null) {
            reference = this.getOfflinePlayer(uuid);
        }
        return reference;
    }

    @Override
    public Player getPlayer(String player) {
        return this.getConfigurationManager().getPlayerByName(player);
    }

    @Override
    public Player getPlayerFromUUID(String uuid) {
        Player player = null;

        for (Player p : this.getConfigurationManager().getAllPlayers()) {
            if (p.getUUIDString().equals(uuid)) {
                player = p;
                break;
            }
        }

        return player;
    }

    @Override
    public Player getPlayerFromUUID(UUID uuid) {
        Player player = null;

        for (Player p : this.getConfigurationManager().getAllPlayers()) {
            if (p.getUUID().equals(uuid)) {
                player = p;
                break;
            }
        }

        return player;
    }

    @Override
    public void broadcastMessage(String message) {
        for (Player player : this.getPlayerList()) {
            player.message(message);
        }
        Canary.log.info(message);
    }

    @Override
    public void broadcastMessageToOps(String message) {
        for (Player player : this.getPlayerList()) {
            if (player.isOperator()) {
                player.message(message);
            }
        }
        Canary.log.info(message);
    }

    @Override
    public void broadcastMessageToAdmins(String message) {
        for (Player player : this.getPlayerList()) {
            if (player.isAdmin()) {
                player.message(message);
            }
        }
        Canary.log.info(message);
    }

    @Override
    public boolean loadWorld(String name, long seed) {
        return false;
    }

    @Override
    public World getWorld(String name) {
        return null;
    }

    @Override
    public World getDefaultWorld() {
        return null;
    }

    @Override
    public ConfigurationManager getConfigurationManager() {
        return (ConfigurationManager) this.serverConfigManager;
    }

    @Override
    public void initiateShutdown(String message) {
        initiateShutdown();
    }

    @Override
    public void restart(boolean reloadCanary) {

    }

    @Override
    public boolean isRunning() {
        return this.serverRunning;
    }

    @Override
    public Recipe addRecipe(CraftingRecipe recipe) {
        return null;
    }

    @Override
    public List<Recipe> getServerRecipes() {
        return (List) CraftingManager.getInstance().getRecipeList();
    }

    @Override
    public boolean removeRecipe(Recipe recipe) {
        return false;
    }

    @Override
    public void addSmeltingRecipe(SmeltRecipe recipe) {

    }

    @Override
    public List<SmeltRecipe> getServerSmeltRecipes() {
        return null;
    }

    @Override
    public boolean removeSmeltRecipe(SmeltRecipe recipe) {
        return false;
    }

    @Override
    public void addGUI(GUIControl gui) {

    }

    @Override
    public long[] getTickTimeArray() {
        return tickTimeArray;
    }

    @Override
    public String getCanaryModVersion() {
        return Canary.getImplementationVersion();
    }

    @Override
    public String getServerVersion() {
        return getMinecraftVersion();
    }

    @Override
    public int getProtocolVersion() {
        return this.statusResponse.getProtocolVersionInfo().getProtocol();
    }

    @Override
    public String getServerGUILog() {
        return null;
    }

    @Override
    public GUIControl getCurrentGUI() {
        return null;
    }

    @Override
    public boolean isHeadless() {
        return GraphicsEnvironment.isHeadless();
    }

    @Override
    public boolean addSynchronousTask(ServerTask task) {
        return false;
    }

    @Override
    public boolean removeSynchronousTask(ServerTask task) {
        return false;
    }

    @Override
    public void sendPlayerListData(PlayerListData data) {

    }

    @Override
    public int getCurrentTick() {
        return this.tickCounter;
    }

    @Override
    public float getTicksPerSecond() {
        // Thanks to Sponge for this, much nicer than the task that CanaryMod used.
        double nanoSPerTick = MathHelper.average(this.tickTimeArray);
        // Cap at 20 TPS
        return (float) (1000 / Math.max(50, nanoSPerTick / 1000000));
    }

    @Override
    public void showTitle(ChatComponent title) {

    }

    @Override
    public void showTitle(ChatComponent title, ChatComponent subtitle) {

    }

    @Override
    public String getLocale() {
        return Configuration.getServerConfig().getServerLocale();
    }

    /**
     * @author jamierocks - 18th May 2015
     * @reason Set the server mod name
     */
    @Overwrite
    public String getServerModName() {
        return "NeptuneVanilla";
    }

    @Override
    public void prepareSpawnArea(WorldServer worldServer) {
        int i1 = 0;
        this.setUserMessage("menu.generatingTerrain");
        logger.info("Preparing start region for level " + worldServer.provider.getDimensionId());
        BlockPos spawnPoint = worldServer.getSpawnPoint();
        long k1 = getCurrentTimeMillis();

        for (int l1 = -192; l1 <= 192 && this.isServerRunning(); l1 += 16) {
            for (int i2 = -192; i2 <= 192 && this.isServerRunning(); i2 += 16) {
                long j2 = getCurrentTimeMillis();

                if (j2 - k1 > 1000L) {
                    this.outputPercentRemaining("Preparing spawn area", i1 * 100 / 625);
                    k1 = j2;
                }

                ++i1;
                worldServer.theChunkProviderServer.loadChunk(spawnPoint.getX() + l1 >> 4, spawnPoint.getZ() + i2 >> 4);
            }
        }

        this.clearCurrentTask();
    }

    @Override
    public void loadWorld(String name, DimensionType dimensionType) {

    }

}
