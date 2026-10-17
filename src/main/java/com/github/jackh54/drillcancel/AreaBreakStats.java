package com.github.jackh54.drillcancel;

import me.serbob.mythictools.api.permission.PermissionHook;
import me.serbob.mythictools.api.permission.PermissionManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Credits the "blocks mined" statistic for blocks that MythicTools' area tools break around the hit block.
 *
 * <p>MythicTools removes the whole 3x3, the hit block included, with {@code Block.breakNaturally()}, which
 * credits no player, so the vanilla {@code MINE_BLOCK} statistic (what /stats shows) counted none of it. MythicTools asks
 * its permission hooks about every area block before breaking it; this hook never protects anything, it
 * notes the block and checks one tick later whether it is really gone (another hook, e.g. a WorldGuard
 * region or {@link SpawnerGuard}, may have kept it). Only then is the player credited, exactly as a normal
 * break would be.
 *
 * <p>Only references MythicTools classes, so it must only be loaded when MythicTools is installed.
 */
final class AreaBreakStats extends PermissionHook {

    private final DrillCancel plugin;
    // Blocks with a check already scheduled, so a block MythicTools asks about twice is counted once.
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    private AreaBreakStats(DrillCancel plugin) {
        this.plugin = plugin;
    }

    static void register(DrillCancel plugin) {
        PermissionManager.getInstance().addPermissionHook(new AreaBreakStats(plugin));
    }

    @Override
    public String pluginName() {
        return "";
    }

    @Override
    public boolean isProtected(Player player, Location location) {
        if (player == null) return false;
        Block block = location.getBlock();
        if (plugin.debugStats()) {
            plugin.getLogger().info("asked about " + block.getType() + " at " + block.getX() + "," + block.getY() + ","
                    + block.getZ() + " for " + player.getName() + (plugin.breakOf(block) != null ? " (hit block)" : ""));
        }
        // The hit block has its own break event. If that break goes ahead, vanilla counts it; MythicTools
        // usually cancels it and removes the block itself, and then nothing counts it. So the hit block is
        // only credited when its break event ended up cancelled (checked after every listener has run).
        BlockBreakEvent hit = plugin.breakOf(block);
        Material before = block.getType();
        if (!countable(before)) return false;
        String key = key(block);
        if (!pending.add(key)) return false;
        plugin.getServer().getRegionScheduler().runDelayed(plugin, location, task -> {
            pending.remove(key);
            if (plugin.debugStats() && hit != null) {
                plugin.getLogger().info("hit block now " + block.getType() + ", break cancelled=" + hit.isCancelled()
                        + ", gone at MONITOR=" + plugin.goneAtMonitor(hit));
            }
            if (block.getType() == before) return;
            if (hit != null && !hit.isCancelled() && !plugin.goneAtMonitor(hit)) return;
            if (plugin.debugStats()) {
                plugin.getLogger().info("credited " + before + " at " + block.getX() + "," + block.getY() + "," + block.getZ()
                        + " to " + player.getName());
            }
            player.getScheduler().run(plugin, t -> player.incrementStatistic(Statistic.MINE_BLOCK, before), null);
        }, 1L);
        return false;
    }

    /** Blocks a player can mine; air and fluids (e.g. the bucket tool) are not mining. */
    static boolean countable(Material type) {
        if (type == null || !type.isBlock() || type.isAir()) return false;
        return switch (type) {
            case WATER, LAVA, BUBBLE_COLUMN, BEDROCK -> false;
            default -> true;
        };
    }

    private static String key(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }
}
