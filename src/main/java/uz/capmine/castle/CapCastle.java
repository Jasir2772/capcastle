package uz.capmine.castle;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CapCastle extends JavaPlugin implements CommandExecutor, Listener {

    private final Map<String, Integer> blockHits = new HashMap<>();
    private final Map<String, String> blockTypes = new HashMap<>();
    private final Map<String, List<ArmorStand>> holograms = new HashMap<>();
    private final Set<String> activeBlocks = new HashSet<>();
    private final Map<UUID, String> editingLoot = new HashMap<>();
    
    private boolean eventActive = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);
        saveResource("shulkers.yml", false);
        saveResource("loots.yml", false);

        this.getCommand("capcastle").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("CapCastle tizimi muvaffaqiyatli yoqildi!");
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
        // Eski bloklarni tozalash
        for (String locStr : activeBlocks) {
            Location loc = stringToLocation(locStr);
            if (loc != null && loc.getBlock().getType() != Material.AIR) {
                loc.getBlock().setType(Material.AIR);
            }
            if (holograms.containsKey(locStr)) {
                for (ArmorStand as : holograms.get(locStr)) as.remove();
                holograms.remove(locStr);
            }
        }
        activeBlocks.clear();
        blockHits.clear();
        blockTypes.clear();

        List<String> rawLocs = getConfig().getStringList("spawn-locations");
        if (rawLocs.isEmpty()) return;

        FileConfiguration shulkersFile = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "shulkers.yml"));

        for (String rawLoc : rawLocs) {
            String[] parts = rawLoc.split(":");
            if (parts.length == 5) { // world:x:y:z:type
                World world = Bukkit.getWorld(parts[0]);
                if (world == null) continue;
                
                double x = Double.parseDouble(parts[1]);
                double y = Double.parseDouble(parts[2]);
                double z = Double.parseDouble(parts[3]);
                String type = parts[4];
                
                Location loc = new Location(world, x, y, z);
                String locStr = locationToString(loc);
                
                String materialStr = shulkersFile.getString("types." + type + ".material", "SHULKER_BOX");
                loc.getBlock().setType(Material.valueOf(materialStr));
                
                activeBlocks.add(locStr);
                blockHits.put(locStr, 0);
                blockTypes.put(locStr, type);

                createHologram(loc, type, 0);
            }
        }
    }

    private void createHologram(Location loc, String type, int current) {
        String locStr = locationToString(loc);
        
        if (holograms.containsKey(locStr)) {
            for (ArmorStand as : holograms.get(locStr)) as.remove();
        }

        List<ArmorStand> stands = new ArrayList<>();
        
        // 1-qator (Nomi)
        ArmorStand nameStand = (ArmorStand) loc.getWorld().spawnEntity(loc.clone().add(0.5, 1.4, 0.5), EntityType.ARMOR_STAND);
        nameStand.setCustomNameVisible(true);
        nameStand.setInvulnerable(true);
        nameStand.setGravity(false);
        nameStand.setMarker(true);
        stands.add(nameStand);

        // 2-qator (Sindirish)
        ArmorStand countStand = (ArmorStand) loc.getWorld().spawnEntity(loc.clone().add(0.5, 1.1, 0.5), EntityType.ARMOR_STAND);
        countStand.setCustomNameVisible(true);
        countStand.setInvulnerable(true);
        countStand.setGravity(false);
        countStand.setMarker(true);
        stands.add(countStand);
        
        holograms.put(locStr, stands);
        updateHologram(locStr, type, current);
    }

    private void updateHologram(String locStr, String type, int current) {
        if (!holograms.containsKey(locStr)) return;
        
        FileConfiguration msg = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        List<String> lines = msg.getStringList("messages.shulker-hologram");
        int maxHits = getMaxHits(type);
        String typeName = getTypeName(type);

        if (lines.size() >= 2 && holograms.get(locStr).size() >= 2) {
            String line1 = color(lines.get(0).replace("{type_name}", typeName));
            String line2 = color(lines.get(1).replace("{current}", String.valueOf(current)).replace("{max}", String.valueOf(maxHits)));
            
            holograms.get(locStr).get(0).setCustomName(line1);
            holograms.get(locStr).get(1).setCustomName(line2);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        String locStr = locationToString(block.getLocation());

        if (!activeBlocks.contains(locStr)) return;

        Player player = event.getPlayer();
        ItemStack itemInHand = player.getInventory().getItemInMainHand();

        if (itemInHand.getType() != Material.AIR && !itemInHand.getType().name().endsWith("_PICKAXE")) {
            event.setCancelled(true);
            FileConfiguration msg = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
            player.sendMessage(color(msg.getString("messages.only-pickaxe", "&cFaqat pickaxe!")));
            return;
        }

        String type = blockTypes.getOrDefault(locStr, "oddiy");
        int currentHits = blockHits.getOrDefault(locStr, 0) + 1;
        int maxHits = getMaxHits(type);

        blockHits.put(locStr, currentHits);
        block.getWorld().playSound(block.getLocation(), Sound.BLOCK_STONE_HIT, 1f, 1f);
        
        updateHologram(locStr, type, currentHits);

        if (currentHits < maxHits) {
            event.setCancelled(true);
        } else {
            event.setDropItems(false); 
            activeBlocks.remove(locStr);
            blockHits.remove(locStr);
            blockTypes.remove(locStr);
            
            if (holograms.containsKey(locStr)) {
                for (ArmorStand as : holograms.get(locStr)) as.remove();
                holograms.remove(locStr);
            }
            
            block.setType(Material.AIR);
            block.getWorld().playSound(block.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 1f, 1f);
            
            dropLoot(block.getLocation(), type);
            
            FileConfiguration msg = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
            String brokenMsg = msg.getString("messages.shulker-broken", "&e{player} qutini sindirdi!")
                    .replace("{player}", player.getName()).replace("{type_name}", getTypeName(type));
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

    // --- GUI (MENU) TIZIMI ---
    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!title.contains("Loot Manager:")) return;
        
        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        String type = editingLoot.get(player.getUniqueId());
        if (type == null || event.getCurrentItem() == null) return;

        if (event.getCurrentItem().getType() == Material.BARRIER) {
            player.closeInventory();
            return;
        }

        if (event.getCurrentItem().getType() == Material.CHEST) { // Add Loot button
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType() == Material.AIR) {
                player.sendMessage(color("&cQo'lingizda narsa yo'q!"));
                return;
            }
            FileConfiguration loots = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "loots.yml"));
            List<String> list = loots.getStringList("loots." + type);
            String itemStr = hand.getType().name() + ":" + hand.getAmount() + ":50"; // Default 50%
            list.add(itemStr);
            loots.set("loots." + type, list);
            try { loots.save(new File(getDataFolder(), "loots.yml")); } catch (Exception ignored) {}
            
            player.sendMessage(color("&a" + hand.getType().name() + " 50% foiz bilan qo'shildi!"));
            openLootMenu(player, type); // Refresh
            return;
        }

        // Delete loot on click
        if (event.getCurrentItem().getType() != Material.AIR && event.getCurrentItem().getType() != Material.CHEST && event.getCurrentItem().getType() != Material.BARRIER) {
            FileConfiguration loots = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "loots.yml"));
            List<String> list = loots.getStringList("loots." + type);
            
            // Find item in list
            int indexToRemove = -1;
            for (int i = 0; i < list.size(); i++) {
                String matName = list.get(i).split(":")[0];
                if (matName.equals(event.getCurrentItem().getType().name())) {
                    indexToRemove = i;
                    break;
                }
            }
            
            if (indexToRemove != -1) {
                list.remove(indexToRemove);
                loots.set("loots." + type, list);
                try { loots.save(new File(getDataFolder(), "loots.yml")); } catch (Exception ignored) {}
                player.sendMessage(color("&cLoot o'chirildi!"));
                openLootMenu(player, type);
            }
        }
    }

    private void openLootMenu(Player p, String type) {
        editingLoot.put(p.getUniqueId(), type);
        Inventory inv = Bukkit.createInventory(null, 54, "Loot Manager: " + getTypeName(type));
        
        FileConfiguration loots = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "loots.yml"));
        List<String> items = loots.getStringList("loots." + type);

        int slot = 0;
        for (String itemStr : items) {
            String[] parts = itemStr.split(":");
            if (parts.length == 3) {
                Material mat = Material.getMaterial(parts[0].toUpperCase());
                int amount = Integer.parseInt(parts[1]);
                int chance = Integer.parseInt(parts[2]);
                if (mat != null) {
                    ItemStack item = new ItemStack(mat, amount);
                    ItemMeta meta = item.getItemMeta();
                    meta.setDisplayName(color("&e" + mat.name()));
                    meta.setLore(Arrays.asList(color("&fFoiz: &a" + chance + "%"), color("&cO'chirish uchun bosing!")));
                    item.setItemMeta(meta);
                    inv.setItem(slot++, item);
                }
            }
        }

        ItemStack addBtn = new ItemStack(Material.CHEST);
        ItemMeta addMeta = addBtn.getItemMeta();
        addMeta.setDisplayName(color("&aYangi Loot Qo'shish"));
        addMeta.setLore(Arrays.asList(color("&fQo'lizda narsani ushlab bosing."), color("&fAvtomatik 50% bo'ladi.")));
        addBtn.setItemMeta(addMeta);
        inv.setItem(49, addBtn);

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = close.getItemMeta();
        closeMeta.setDisplayName(color("&cYopish"));
        close.setItemMeta(closeMeta);
        inv.setItem(53, close);

        p.openInventory(inv);
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

    private String locationToString(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getX() + ":" + loc.getY() + ":" + loc.getZ();
    }

    private Location stringToLocation(String str) {
        String[] parts = str.split(":");
        if(parts.length < 4) return null;
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) return null;
        return new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
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
            p.sendMessage(ChatColor.YELLOW + "/capcastle setloc <tur> " + ChatColor.WHITE + "- Quti qo'yish");
            p.sendMessage(ChatColor.YELLOW + "/capcastle start " + ChatColor.WHITE + "- Eventni boshlash");
            p.sendMessage(ChatColor.YELLOW + "/capcastle stop " + ChatColor.WHITE + "- To'xtatish");
            p.sendMessage(ChatColor.YELLOW + "/capcastle menu " + ChatColor.WHITE + "- Lootlarni sozlash");
            p.sendMessage(ChatColor.YELLOW + "/capcastle reload " + ChatColor.WHITE + "- Yangilash");
            p.sendMessage(ChatColor.GOLD + "===============================");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "setloc":
                if (args.length < 2) {
                    p.sendMessage(color("&cTurini yozing! Masalan: /capcastle setloc oddiy"));
                    return true;
                }
                String type = args[1].toLowerCase();
                FileConfiguration shulkers = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "shulkers.yml"));
                if (!shulkers.contains("types." + type)) {
                    p.sendMessage(color("&cBunday tur yo'q!"));
                    return true;
                }
                
                String locStr = p.getWorld().getName() + ":" + p.getLocation().getX() + ":" + p.getLocation().getY() + ":" + p.getLocation().getZ() + ":" + type;
                List<String> locs = getConfig().getStringList("spawn-locations");
                locs.add(locStr);
                getConfig().set("spawn-locations", locs);
                saveConfig();
                
                // Darhol o'sha joyga quti qo'yish
                p.getLocation().getBlock().setType(Material.valueOf(shulkers.getString("types." + type + ".material")));
                createHologram(p.getLocation(), type, 0);
                activeBlocks.add(locationToString(p.getLocation()));
                blockHits.put(locationToString(p.getLocation()), 0);
                blockTypes.put(locationToString(p.getLocation()), type);

                p.sendMessage(color("&aJoy saqlandi va quti qo'yildi! Turi: " + type));
                break;
                
            case "start":
                startEvent();
                p.sendMessage(color("&aEvent boshlandi!"));
                break;
                
            case "stop":
                eventActive = false;
                for (String lStr : activeBlocks) {
                    Location l = stringToLocation(lStr);
                    if (l != null) l.getBlock().setType(Material.AIR);
                    if (holograms.containsKey(lStr)) {
                        for (ArmorStand as : holograms.get(lStr)) as.remove();
                        holograms.remove(lStr);
                    }
                }
                activeBlocks.clear();
                p.sendMessage(color("&cEvent to'xtatildi va qutilar o'chirildi."));
                break;
                
            case "menu":
                if (args.length < 2) {
                    p.sendMessage(color("&cTurini yozing! Masalan: /capcastle menu qonli"));
                    return true;
                }
                openLootMenu(p, args[1].toLowerCase());
                break;
                
            case "reload":
                reloadConfig();
                p.sendMessage(color("&aFayllar yangilandi!"));
                break;
            default:
                p.sendMessage(color("&cBunday komanda yo'q."));
                break;
        }
        return true;
    }

    // HEX ranglarini to'g'ri o'qish
    private String color(String text) {
        if (text == null) return "";
        Pattern pattern = Pattern.compile("&#([A-Fa-f0-9]{6})");
        Matcher matcher = pattern.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) replacement.append("§").append(c);
            matcher.appendReplacement(buffer, replacement.toString());
        }
        matcher.appendTail(buffer);
        return net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }
            }
