package pl.bwteleporter;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BWTeleporter extends JavaPlugin {

    private BedWarsAPI api;
    private final Map<UUID, TeleportTask> teleportingPlayers = new HashMap<>();

    @Override
    public void onEnable() {
        try {
            Class<?> bedWarsClass = Class.forName("com.andrei1058.bedwars.api.BedWars");
            Object provider = Bukkit.getServicesManager().getRegistration(bedWarsClass).getProvider();

            this.api = new BedWarsAPI() {
                private final Object bwInstance = provider;
                private Method getArenaUtilMethod;
                private Method getArenaByPlayerMethod;
                private Method getTeamMethod;
                private Method getSpawnMethod;
                private Method isPlayingMethod;

                {
                    try {
                        getArenaUtilMethod = bedWarsClass.getMethod("getArenaUtil");
                        Class<?> arenaUtilClass = Class.forName("com.andrei1058.bedwars.api.arena.IArena");
                        getArenaByPlayerMethod = getArenaUtilMethod.getReturnType().getMethod("getArenaByPlayer", Player.class);
                        getTeamMethod = arenaUtilClass.getMethod("getTeam", Player.class);
                        Class<?> teamClass = Class.forName("com.andrei1058.bedwars.api.arena.team.ITeam");
                        getSpawnMethod = teamClass.getMethod("getSpawn");
                        isPlayingMethod = getArenaUtilMethod.getReturnType().getMethod("isPlaying", Player.class);
                    } catch (Exception e) {
                        getLogger().severe("初始化BedWars API反射方法失败: " + e.getMessage());
                    }
                }

                @Override
                public Object getArenaUtil() throws Exception {
                    return getArenaUtilMethod.invoke(bwInstance);
                }

                @Override
                public boolean isPlaying(Player player) throws Exception {
                    return (boolean) isPlayingMethod.invoke(getArenaUtil(), player);
                }

                @Override
                public Object getArenaByPlayer(Player player) throws Exception {
                    return getArenaByPlayerMethod.invoke(getArenaUtil(), player);
                }

                @Override
                public Object getTeam(Object arena, Player player) throws Exception {
                    return getTeamMethod.invoke(arena, player);
                }

                @Override
                public Location getSpawnLocation(Object team) throws Exception {
                    return (Location) getSpawnMethod.invoke(team);
                }
            };

            Bukkit.getPluginManager().registerEvents(new BWListener(this), this);
            getLogger().info("BWTeleporter 已成功加载!");

        } catch (ClassNotFoundException e) {
            getLogger().severe("未找到BedWars1058插件，正在禁用...");
            Bukkit.getPluginManager().disablePlugin(this);
        } catch (Exception e) {
            getLogger().severe("初始化BedWars API失败: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    public interface BedWarsAPI {
        Object getArenaUtil() throws Exception;

        boolean isPlaying(Player player) throws Exception;

        Object getArenaByPlayer(Player player) throws Exception;

        Object getTeam(Object arena, Player player) throws Exception;

        Location getSpawnLocation(Object team) throws Exception;
    }

    public static class BWListener implements Listener {
        private final BWTeleporter plugin;

        public BWListener(BWTeleporter plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onPlayerInteract(PlayerInteractEvent event) {
            Player player = event.getPlayer();
            ItemStack item = event.getItem();

            if (item != null && item.getType() == Material.PAPER) {
                try {
                    if (plugin.api.isPlaying(player)) {
                        Object arena = plugin.api.getArenaByPlayer(player);
                        Object team = plugin.api.getTeam(arena, player);

                        if (team != null) {
                            Location spawnLocation = plugin.api.getSpawnLocation(team);

                            if (spawnLocation != null) {
                                plugin.cancelTeleport(player);
                                plugin.startTeleport(player, spawnLocation);
                            }
                        }
                    }
                } catch (Exception e) {
                    player.sendMessage(ChatColor.RED + "传送功能暂时不可用");
                    plugin.getLogger().warning("处理玩家交互时出错: " + e.getMessage());
                }
            }
        }

        @EventHandler
        public void onPlayerMove(PlayerMoveEvent event) {
            // 只检查X/Y/Z坐标变化，忽略视角转动
            Location from = event.getFrom();
            Location to = event.getTo();

            // 使用Vector比较位置变化，忽略方向变化
            if (!from.toVector().equals(to.toVector())) {
                plugin.handlePlayerMove(event);
            }
        }
    }

    private void handlePlayerMove(PlayerMoveEvent event) {
        if (teleportingPlayers.containsKey(event.getPlayer().getUniqueId())) {
            cancelTeleport(event.getPlayer());
        }
    }

    private void startTeleport(Player player, Location destination) {
        // 检查玩家手中的物品是否为纸
        ItemStack itemInHand = player.getInventory().getItemInHand();
        if (itemInHand == null || itemInHand.getType() != Material.PAPER) {
            player.sendMessage(ChatColor.RED + "你必须手持纸才能使用传送功能！");
            return;
        }

        player.sendMessage(ChatColor.GREEN + "5秒后将传送回出生点，请不要移动位置...");

        // 记录玩家初始位置
        Location initialLocation = player.getLocation().clone();

        TeleportTask task = new TeleportTask(player, destination, initialLocation, itemInHand);
        task.runTaskTimer(this, 20L, 20L);

        teleportingPlayers.put(player.getUniqueId(), task);
    }

    private void cancelTeleport(Player player) {
        TeleportTask task = teleportingPlayers.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            player.sendMessage(ChatColor.RED + "传送已取消！");
        }
    }

    private class TeleportTask extends BukkitRunnable {
        private final Player player;
        private final Location destination;
        private final Location initialLocation;
        private final ItemStack paperItem;
        private int secondsRemaining = 5;

        public TeleportTask(Player player, Location destination, Location initialLocation, ItemStack paperItem) {
            this.player = player;
            this.destination = destination;
            this.initialLocation = initialLocation;
            this.paperItem = paperItem.clone(); // 保存纸物品的副本
        }

        @Override
        public void run() {
            // 检查玩家是否移动了位置（忽略视角转动）
            if (!player.getLocation().toVector().equals(initialLocation.toVector())) {
                cancelTeleport(player);
                return;
            }

            // 检查玩家是否仍然持有纸
            if (!player.getInventory().containsAtLeast(paperItem, 1)) {
                player.sendMessage(ChatColor.RED + "传送取消：你不再持有纸！");
                cancelTeleport(player);
                return;
            }

            if (secondsRemaining <= 0) {
                try {
                    player.teleport(destination);

                    // 移除一个纸物品
                    ItemStack currentItem = player.getInventory().getItemInHand();
                    if (currentItem != null && currentItem.isSimilar(paperItem)) {
                        if (currentItem.getAmount() > 1) {
                            currentItem.setAmount(currentItem.getAmount() - 1);
                        } else {
                            player.getInventory().setItemInHand(null);
                        }
                    } else {
                        // 如果主手中的物品不是纸了，尝试从整个背包中移除
                        for (ItemStack item : player.getInventory().getContents()) {
                            if (item != null && item.isSimilar(paperItem)) {
                                if (item.getAmount() > 1) {
                                    item.setAmount(item.getAmount() - 1);
                                } else {
                                    player.getInventory().removeItem(item);
                                }
                                break;
                            }
                        }
                    }

                    player.sendMessage(ChatColor.GREEN + "已传送回出生点！");
                    teleportingPlayers.remove(player.getUniqueId());
                    this.cancel();
                } catch (Exception e) {
                    player.sendMessage(ChatColor.RED + "传送过程中出现错误！");
                    getLogger().warning("传送过程中出错: " + e.getMessage());
                }
            } else {
                player.sendMessage(ChatColor.YELLOW + "剩余时间: " + secondsRemaining + "秒");
                secondsRemaining--;
            }
        }
    }
}