package uz.capmine.castle;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.*;

public class CapCastle extends JavaPlugin implements CommandExecutor, Listener {

    private final Map<String, Integer> blockHits = new HashMap<>();
    private final Map<String, String> blockTypes = new HashMap<>(); // LocStr -> Type
    private final Map<String, ArmorStand> holograms = new HashMap<>();
    
    private final Set<String> activeBlocks = new HashSet<>();
    private boolean eventActive = false;
    
    private final String[] typeList = {"oddiy", "sehrli", "qonli", "afsonaviy", "qora_imperator"};

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);
        saveResource("shulkers.yml", false);
        saveResource("loots.yml", false);

        this.getCommand("capcastle").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("CapCastle tizimi yoqildi!");
    }

    public void startEvent() {
        if (eventActive) return;
        eventActive = true;
        
        long refreshMinutes = getConfig().getLong("settings.refresh-minutes", 5);
        long ticks = refreshMinutes * 60 * 20;

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!eventActive) { this.cancel(); return; }
                refreshBlocks();
            }
        }.runTaskTimer(this, 0L, ticks);
        
        refreshBlocks();
    }

    private void refreshBlocks() {
        for (String locStr : activeBlocks) {
            Location loc = stringToLocation(locStr);
            if (loc != null && loc.getBlock().getType() != Material.AIR) {
                loc.getBlock().setType(Material.AIR);
            }
            if (holograms.containsKey(locStr)) {
                holograms.get(locStr).remove();
                holograms.remove(locStr);
            }
        }
        activeBlocks.clear();
        blockHits.clear();
        blockTypes.clear();

        List<Location> locations = getSpawnLocations();
        if (locations.isEmpty()) return;

        int maxShulkers = getConfig().getInt("settings.max-shulkers", 40);
        Random random = new Random();
        
        FileConfiguration shulkersFile = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "shulkers.yml"));

        for (int i = 0; i < maxShulkers; i++) {
            Location loc = locations.get(random.nextInt(locations.size())).clone().add(random.nextDouble() * 10 - 5, 0, random.nextDouble() * 10 - 5);
            
            String type = typeList[random.nextInt(typeList.length)];
            String materialStr = shulkersFile.getString("types." + type + ".material", "SHULKER_BOX");
            
            loc.getBlock().setType(Material.valueOf(materialStr));
            
            String locStr = locationToString(loc);
            
            activeBlocks.add(locStr);
            blockHits.put(locStr, 0);
            blockTypes.put(locStr, type);

            ArmorStand holo = (ArmorStand) loc.getWorld().spawnEntity(loc.clone().add(0.5, 1.2, 0.5), EntityType.ARMOR_STAND);
            holo.setCustomNameVisible(true);
            holo.setCustomName(getHoloText(type, 0));
            holo.setInvulnerable(true);
            holo.setGravity(false);
            holo.setMarker(true);
            holograms.put(locStr, holo);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        String locStr = locationToString(block.getLocation());

        if (!activeBlocks.contains(locStr)) return;

        Player player = event.getPlayer();
        ItemStack itemInHand = player.getInventory().getItemInMainHand();

        // Faqat pickaxe yoki qo'l
        if (itemInHand.getType() != Material.AIR && !itemInHand.getType().name().endsWith("_PICKAXE")) {
            event.setCancelled(true);
            player.sendMessage(color(YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml")).getString("messages.only-pickaxe", "&cFaqat pickaxe!")));
            return;
        }

        String type = blockTypes.getOrDefault(locStr, "oddiy");
        int currentHits = blockHits.getOrDefault(locStr, 0) + 1;
        int maxHits = getMaxHits(type);

        blockHits.put(locStr, currentHits);

        block.getWorld().playSound(block.getLocation(), Sound.BLOCK_STONE_HIT, 1f, 1f);
        
        if (holograms.containsKey(locStr)) {
            holograms.get(locStr).setCustomName(getHoloText(type, currentHits));
        }

        if (currentHits < maxHits) {
            event.setCancelled(true);
            // Blokning vizual sindirilishini tozalash
            player.sendBlockDamage(block.getLocation(), 0f);
        } else {
            event.setDropItems(false); 
            activeBlocks.remove(locStr);
            blockHits.remove(locStr);
            blockTypes.remove(locStr);
            
            if (holograms.containsKey(locStr)) {
                holograms.get(locStr).remove();
                holograms.remove(locStr);
            }
            
            block.setType(Material.AIR);
            block.getWorld().playSound(block.getLocation(), Sound.ENTITY_SHULKER_BOX_OPEN, 1f, 1f);
            
            dropLoot(block.getLocation(), type);
            
            FileConfiguration msg = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
            String typeName = getTypeName(type);
            String brokenMsg = msg.getString("messages.shulker-broken", "&e{player} qutini sindirdi!")
                    .replace("{player}", player.getName()).replace("{type_name}", typeName);
            Bukkit.broadcastMessage(color(msg.getString("messages.prefix", "") + brokenMsg));
        }
    }

    private void dropLoot(Location loc, String type) {
        FileConfiguration loots = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "loots.yml"));
        List<String> items = loots.getStringList("loots." + type);
        Random rand = new Random();

        for (String itemStr : items) {
            String[] parts = itemStr.split(":");
            if (parts.length == 3) {
                Material mat = Material.getMaterial(parts[0].toUpperCase());
                int amount = Integer.parseInt(parts[1]);
                int chance = Integer.parseInt(parts[2]);

                if (mat != null && rand.nextInt(100) < chance) {
                    loc.getWorld().dropItemNaturally(loc.add(0.5, 0.5, 0.5), new ItemStack(mat, amount));
                }
            }
        }
    }

    // --- YORDAMCHI METODLAR ---
    private String getTypeName(String type) {
        FileConfiguration shulkersFile = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "shulkers.yml"));
        return color(shulkersFile.getString("types." + type + ".name", type));
    }

    private int getMaxHits(String type) {
        FileConfiguration shulkersFile = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "shulkers.yml"));
        return shulkersFile.getInt("types." + type + ".break-count", 20);
    }

    private String getHoloText(String type, int current) {
        FileConfiguration msg = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        List<String> lines = msg.getStringList("messages.shulker-hologram");
        StringBuilder sb = new StringBuilder();
        
        String typeName = getTypeName(type);
        int maxHits = getMaxHits(type);
        
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i)
                    .replace("{type_name}", typeName)
                    .replace("{current}", String.valueOf(current))
                    .replace("{max}", String.valueOf(maxHits));
            sb.append(color(line));
            if (i < lines.size() - 1) sb.append("\n");
        }
        return sb.toString();
    }

    private String locationToString(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private Location stringToLocation(String str) {
        String[] parts = str.split(":");
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) return null;
        return new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
    }

    private List<Location> getSpawnLocations() {
        List<Location> locs = new ArrayList<>();
        for (String s : getConfig().getStringList("spawn-locations")) {
            Location loc = stringToLocation(s);
            if (loc != null) locs.add(loc);
        }
        return locs;
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player) || !sender.hasPermission("capcastle.admin")) {
            sender.sendMessage(ChatColor.RED + "Ruxsat yo'q!");
            return true;
        }
        Player p = (Player) sender;

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            p.sendMessage(ChatColor.GOLD + "========== CapCastle ==========");
            p.sendMessage(ChatColor.YELLOW + "/capcastle setloc " + ChatColor.WHITE + "- Qutilar chiqadigan joyni belgilash");
            p.sendMessage(ChatColor.YELLOW + "/capcastle start " + ChatColor.WHITE + "- Eventni boshlash");
            p.sendMessage(ChatColor.YELLOW + "/capcastle stop " + ChatColor.WHITE + "- Eventni to'xtatish");
            p.sendMessage(ChatColor.YELLOW + "/capcastle reload " + ChatColor.WHITE + "- Fayllarni yangilash");
            p.sendMessage(ChatColor.GOLD + "===============================");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "setloc":
                List<String> locs = getConfig().getStringList("spawn-locations");
                String locStr = locationToString(p.getLocation());
                if (!locs.contains(locStr)) {
                    locs.add(locStr);
                    getConfig().set("spawn-locations", locs);
                    saveConfig();
                    p.sendMessage(ChatColor.GREEN + "Joy saqlandi! Jami: " + locs.size());
                } else {
                    p.sendMessage(ChatColor.RED + "Bu joy allaqachon bor.");
                }
                break;
            case "start":
                if (eventActive) {
                    p.sendMessage(ChatColor.RED + "Event allaqachon ishlayapti!");
                } else {
                    startEvent();
                    p.sendMessage(ChatColor.GREEN + "Event boshlandi! Qutilar paydo bo'ldi.");
                }
                break;
            case "stop":
                if (!eventActive) {
                    p.sendMessage(ChatColor.RED + "Event ishlamayapti.");
                } else {
                    eventActive = false;
                    for (String lStr : activeBlocks) {
                        Location l = stringToLocation(lStr);
                        if (l != null) l.getBlock().setType(Material.AIR);
                        if (holograms.containsKey(lStr)) {
                            holograms.get(lStr).remove();
                            holograms.remove(lStr);
                        }
                    }
                    activeBlocks.clear();
                    p.sendMessage(ChatColor.RED + "Event to'xtatildi va qutilar o'chirildi.");
                }
                break;
            case "reload":
                reloadConfig();
                p.sendMessage(ChatColor.GREEN + "Barcha fayllar qayta yuklandi!");
                break;
            default:
                p.sendMessage(ChatColor.RED + "Bunday komanda yo'q. /capcastle help");
                break;
        }
        return true;
    }
  }
