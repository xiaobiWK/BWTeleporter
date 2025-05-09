package bwaddon.returnscroll;


import me.BedWars1058.API.BedWars;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ReturnScroll extends JavaPlugin implements Listener {

    private BedWars bedwarsAPI;
    // 存储玩家正在进行的传送任务
    private final Map<UUID, BukkitTask> teleportTasks = new HashMap<>();
    // 存储玩家开始传送时的初始位置(用于检测移动)
    private final Map<UUID, Location> originalLocations = new HashMap<>();

    @Override
    public void onEnable() {
        // 检查BedWars1058插件是否存在
        if (Bukkit.getPluginManager().getPlugin("BedWars1058") == null) {
            getLogger().severe("未找到BedWars1058插件，正在禁用本插件...");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 获取BedWars API实例
        bedwarsAPI = Bukkit.getServicesManager().getRegistration(BedWars.class).getProvider();

        // 注册事件监听器
        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("BWTeleport插件已启用!");
    }

    @Override
    public void onDisable() {
        // 取消所有进行中的传送任务
        teleportTasks.values().forEach(BukkitTask::cancel);
        teleportTasks.clear();
        originalLocations.clear();

        getLogger().info("BWTeleport插件已禁用!");
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // 只处理右键点击事件(无论是点击空气还是方块)
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        // 检查玩家手持的是否是纸
        if (item == null || item.getType() != org.bukkit.Material.PAPER) {
            return;
        }

        // 检查玩家是否正在玩起床战争
        if (!bedwarsAPI.getArenaUtil().isPlaying(player)) {
            player.sendMessage(ChatColor.RED + "只能在起床战争游戏中使用此功能!");
            return;
        }

        // 取消该玩家已有的传送任务(防止重复)
        cancelTeleport(player);

        // 获取玩家所在的竞技场和队伍
        me.BedWars1058.API.Arena.Arena arena = bedwarsAPI.getArenaUtil().getArenaByPlayer(player);
        me.BedWars1058.API.Team.Team team = arena.getTeam(player);

        // 获取队伍出生点位置
        Location spawnLocation = team.getSpawn();

        if (spawnLocation == null) {
            player.sendMessage(ChatColor.RED + "无法找到你队伍的出生点!");
            return;
        }

        // 记录玩家当前位置(用于后续移动检测)
        originalLocations.put(player.getUniqueId(), player.getLocation());

        // 开始传送倒计时
        startTeleportCountdown(player, spawnLocation);
    }

    /**
     * 开始传送倒计时
     * @param player 要传送的玩家
     * @param destination 目标位置(队伍出生点)
     */
    private void startTeleportCountdown(Player player, Location destination) {
        UUID playerId = player.getUniqueId();
        Location originalLocation = originalLocations.get(playerId);

        // 创建倒计时任务
        BukkitTask task = new BukkitRunnable() {
            int countdown = 5; // 5秒倒计时

            @Override
            public void run() {
                if (countdown <= 0) {
                    // 倒计时结束，执行传送
                    player.teleport(destination);
                    player.sendMessage(ChatColor.GREEN + "已传送至队伍出生点!");

                    // 从玩家背包移除1张纸
                    removeOnePaper(player);

                    // 清理任务数据
                    teleportTasks.remove(playerId);
                    originalLocations.remove(playerId);
                    return;
                }

                // 检查玩家是否移动
                if (hasMoved(player, originalLocation)) {
                    player.sendMessage(ChatColor.RED + "传送已取消，因为你移动了!");
                    cancelTeleport(player);
                    return;
                }

                // 显示剩余时间
                player.sendMessage(ChatColor.YELLOW + "传送将在 " + countdown + " 秒后执行...");
                countdown--;
            }
        }.runTaskTimer(this, 0L, 20L); // 每20ticks(1秒)执行一次

        // 记录玩家的传送任务
        teleportTasks.put(playerId, task);
    }

    /**
     * 检查玩家是否移动
     * @param player 要检查的玩家
     * @param originalLocation 原始位置
     * @return 是否移动了
     */
    private boolean hasMoved(Player player, Location originalLocation) {
        Location currentLocation = player.getLocation();
        // 比较坐标是否变化
        return currentLocation.getX() != originalLocation.getX() ||
                currentLocation.getY() != originalLocation.getY() ||
                currentLocation.getZ() != originalLocation.getZ();
    }

    /**
     * 取消玩家的传送任务
     * @param player 要取消传送的玩家
     */
    private void cancelTeleport(Player player) {
        UUID playerId = player.getUniqueId();
        if (teleportTasks.containsKey(playerId)) {
            teleportTasks.get(playerId).cancel();
            teleportTasks.remove(playerId);
            originalLocations.remove(playerId);
        }
    }

    /**
     * 从玩家背包移除1张纸
     * @param player 目标玩家
     */
    private void removeOnePaper(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == org.bukkit.Material.PAPER) {
                int amount = item.getAmount();
                if (amount > 1) {
                    // 如果有多张纸，只减少数量
                    item.setAmount(amount - 1);
                } else {
                    // 如果只有1张纸，直接移除
                    player.getInventory().remove(item);
                }
                break;
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // 检查玩家是否有进行中的传送任务
        if (teleportTasks.containsKey(playerId)) {
            Location from = event.getFrom();
            Location to = event.getTo();

            // 检查是否真的移动了(不仅仅是转头)
            if (to != null && (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ())) {
                player.sendMessage(ChatColor.RED + "传送已取消，因为你移动了!");
                cancelTeleport(player);
            }
        }
    }
}
