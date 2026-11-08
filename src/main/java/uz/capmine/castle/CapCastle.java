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
import org.bukkit.event.inventory.ClickType;
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
    
    // 5 xil shalker ro'yxati
    private final List<String> allTypes = Arrays.asList("oddiy", "sehrli", "qonli", "afsonaviy", "qora_imperator");

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);
        saveResource("shulkers.yml", false);
        saveResource("loots.yml", false);

        this.getCommand("capcastle").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("CapCastle tizimi muvaffaqiyatli yoqildi!");
        
        startParticleTask(); // Nur effektini yoqish
        loadInitialBlocks(); // Server yoqilganda qutilarni tiklash
    }

    // NUR EFFEKTI (Shalkerlarni oson topish uchun)
    private void startParticleTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (String locStr : activeBlocks) {
                    Location loc = stringToLocation(locStr);
                    if (loc != null) {
                        // Har yarim sekundda oq yulduzchalar (END_ROD) sochiladi
                        loc.getWorld().spawnParticle(Particle.END_ROD, loc.clone().add(0.5, 1.5, 0.5), 3, 0.3, 0.3, 0.3, 0.01);
                    }
                }
            }
        }.runTaskTimer(this, 0L, 10L); 
    }

    private void loadInitialBlocks() {
        List<String> rawLocs = getConfig().getStringList("spawn-locations");
        for (String rawLoc : rawLocs) {
            String[] parts = rawLoc.split(":");
            if (parts.length == 5) {
                World world = Bukkit.getWorld(parts[0]);
                if (world == null) continue;
                
                double x = Double.parseDouble(parts[1]);
                double y = Double.parseDouble(parts[2]);
                double z = Double.parseDouble(parts[3]);
                String type = parts[4];
                
                Location loc = new Location(world, Math.floor(x), Math.floor(y), Math.floor(z));
                spawnShulker(loc, type);
            }
        }
    }

    private void spawnShulker(Location loc, String type) {
        if (type.equalsIgnoreCase("random")) {
            type = allTypes.get(new Random().nextInt(allTypes.size()));
        }

        String locStr = locationToString(loc);
        FileConfiguration shulkersFile = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "shulkers.yml"));
        
        String materialStr = shulkersFile.getString("types." + type + ".material", "SHULKER_BOX");
        loc.getBlock().setType(Material.valueOf(materialStr));
        
        activeBlocks.add(locStr);
        blockHits.put(locStr, 0);
        blockTypes.put(locStr, type);

        createHologram(loc, type, 0);
    }

    private void createHologram(Location blockLoc, String type, int currentHits) {
        String locStr = locationToString(blockLoc);
        
        if (holograms.containsKey(locStr)) {
            for (ArmorStand as : holograms.get(locStr)) as.remove();
        }

        List<ArmorStand> stands = new ArrayList<>();
        
        Location loc1 = blockLoc.clone().add(0.5, 1.3, 0.5);
        ArmorStand nameStand = (ArmorStand) blockLoc.getWorld().spawnEntity(loc1, EntityType.ARMOR_STAND);
        nameStand.setCustomNameVisible(true);
        nameStand.setInvulnerable(true);
        nameStand.setGravity(false);
        nameStand.setMarker(true);
        nameStand.setVisible(false); 
        stands.add(nameStand);

        Location loc2 = blockLoc.clone().add(0.5, 1.0, 0.5);
        ArmorStand countStand = (ArmorStand) blockLoc.getWorld().spawnEntity(loc2, EntityType.ARMOR_STAND);
        countStand.setCustomNameVisible(true);
        countStand.setInvulnerable(true);
        countStand.setGravity(false);
        countStand.setMarker(true);
        countStand.setVisible(false); 
        stands.add(countStand);
        
        holograms.put(locStr, stands);
        updateHologram(locStr, type, currentHits);
    }

    private void updateHologram(String locStr, String type, int currentHits) {
        if (!holograms.containsKey(locStr)) return;
        
        FileConfiguration msg = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        List<String> lines = msg.getStringList("messages.shulker-hologram");
        int maxHits = getMaxHits(type);
        String typeName = getTypeName(type);

        int remainingHits = maxHits - currentHits;
        if (remainingHits < 0) remainingHits = 0;

        if (lines.size() >= 2 && holograms.get(locStr).size() >= 2) {
            String line1 = color(lines.get(0).replace("{type_name}", typeName));
            String line2 = color(lines.get(1).replace("{current}", String.valueOf(remainingHits)).replace("{max}", String.valueOf(maxHits)));
            
            holograms.get(locStr).get(0).setCustomName(line1);
            holograms.get(locStr).get(1).setCustomName(line2);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        String locStr = locationToString(block.getLocation());

        if (!activeBlocks.contains(locStr)) return;

        event.setCancelled(true); // Blokni o'yin o'zi sindirishini to'xtatamiz

        Player player = event.getPlayer();
        ItemStack itemInHand = player.getInventory().getItemInMainHand();

        if (itemInHand.getType() != Material.AIR && !itemInHand.getType().name().endsWith("_PICKAXE")) {
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

        // Agar qolgan urishlar 0 ga teng bo'lsa (sinish vaqti kelgan bo'lsa)
        if (currentHits >= maxHits) {
            activeBlocks.remove(locStr);
            blockHits.remove(locStr);
            blockTypes.remove(locStr);
            
            if (holograms.containsKey(locStr)) {
                for (ArmorStand as : holograms.get(locStr)) as.remove();
                holograms.remove(locStr);
            }
            
            block.setType(Material.AIR);
            block.getWorld().playSound(block.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 1f, 1f);
            
            dropLoot(block.getLocation().add(0.5, 0.5, 0.5), type);
            
            FileConfiguration msg = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
            String brokenMsg = msg.getString("messages.shulker-broken", "&e{player} qutini sindirdi!")
                    .replace("{player}", player.getName()).replace("{type_name}", getTypeName(type));
            
            Bukkit.broadcastMessage(color(brokenMsg));

            // 5 DAQIQADAN KEYIN QAYTA TIKLASH TIZIMI
            Location respawnLoc = block.getLocation();
            long respawnTicks = getConfig().getLong("settings.respawn-minutes", 5) * 60 * 20;
            
            new BukkitRunnable() {
                @Override
                public void run() {
                    // Yangi tasodifiy tur bilan qayta tiklaymiz
                    String newType = allTypes.get(new Random().nextInt(allTypes.size()));
                    spawnShulker(respawnLoc, newType);
                }
            }.runTaskLater(this, respawnTicks);
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
                    loc.getWorld().dropItemNaturally(loc, new ItemStack(mat, amount));
                }
            }
        }
    }

    // --- OSonLASHTIRILGAN MENYU (GUI) ---
    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!title.contains("Loot Manager:")) return;
        
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;
        
        Player player = (Player) event.getWhoClicked();
        String type = editingLoot.get(player.getUniqueId());
        ItemStack clicked = event.getCurrentItem();
        
        if (type == null || clicked == null || clicked.getType() == Material.AIR) return;

        if (clicked.getType() == Material.BARRIER) {
            player.closeInventory();
            return;
        }

        // Narsa qo'shish (Chest tugmasi)
        if (clicked.getType() == Material.CHEST) { 
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType() == Material.AIR) {
                player.sendMessage(color("&cQo'lingizda narsa yo'q!"));
                return;
            }
            FileConfiguration loots = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "loots.yml"));
            List<String> list = loots.getStringList("loots." + type);
            String itemStr = hand.getType().name() + ":" + hand.getAmount() + ":50";
            list.add(itemStr);
            loots.set("loots." + type, list);
            try { loots.save(new File(getDataFolder(), "loots.yml")); } catch (Exception ignored) {}
            
            player.sendMessage(color("&a" + hand.getType().name() + " 50% bilan qo'shildi!"));
            openLootMenu(player, type); 
            return;
        }

        // Lootni tahrirlash yoki o'chirish
        if (clicked.getType() != Material.CHEST && clicked.getType() != Material.BARRIER && event.getSlot() < 45) {
            FileConfiguration loots = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "loots.yml"));
            List<String> list = loots.getStringList("loots." + type);
            
            int index = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).split(":")[0].equals(clicked.getType().name())) {
                    index = i;
                    break;
                }
            }
            
            if (index != -1) {
                String[] parts = list.get(index).split(":");
                int currentChance = Integer.parseInt(parts[2]);

                // O'NG TUGMA - O'CHIRISH
                if (event.getClick() == ClickType.RIGHT || event.getClick() == ClickType.SHIFT_RIGHT) {
                    list.remove(index);
                    player.sendMessage(color("&cLoot o'chirildi!"));
                } 
                // CHAP TUGMA - FOIZNI O'ZGARTIRISH
                else if (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.SHIFT_LEFT) {
                    if (currentChance == 10) currentChance = 25;
                    else if (currentChance == 25) currentChance = 50;
                    else if (currentChance == 50) currentChance = 75;
                    else if (currentChance == 75) currentChance = 100;
                    else currentChance = 10;
                    
                    list.set(index, parts[0] + ":" + parts[1] + ":" + currentChance);
                    player.sendMessage(color("&aFoiz o'zgartirildi: " + currentChance + "%"));
                }
                
                loots.set("loots." + type, list);
                try { loots.save(new File(getDataFolder(), "loots.yml")); } catch (Exception ignored) {}
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
            if (parts.length == 3 && slot < 45) {
                Material mat = Material.getMaterial(parts[0].toUpperCase());
                int amount = Integer.parseInt(parts[1]);
                int chance = Integer.parseInt(parts[2]);
                if (mat != null) {
                    ItemStack item = new ItemStack(mat, amount);
                    ItemMeta meta = item.getItemMeta();
                    meta.setDisplayName(color("&e" + mat.name()));
                    meta.setLore(Arrays.asList(
                            color("&fTushish foizi: &a" + chance + "%"),
                            color("&7 "),
                            color("&eChap tugma &7- Foizni o'zgartirish"),
                            color("&cO'ng tugma &7- O'chirish")
                    ));
                    item.setItemMeta(meta);
                    inv.setItem(slot++, item);
                }
            }
        }

        ItemStack addBtn = new ItemStack(Material.CHEST);
        ItemMeta addMeta = addBtn.getItemMeta();
        addMeta.setDisplayName(color("&aYangi Loot Qo'shish"));
        addMeta.setLore(Arrays.asList(color("&fQo'lingizda narsani ushlab bosing."), color("&fAvtomatik 50% qilib qo'shiladi.")));
        addBtn.setItemMeta(addMeta);
        inv.setItem(49, addBtn);

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = close.getItemMeta();
        closeMeta.setDisplayName(color("&cYopish"));
        close.setItemMeta(closeMeta);
        inv.setItem(53, close);

        p.openInventory(inv);
    }

    private String getTypeName(String type) {
        FileConfiguration shulkersFile = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "shulkers.yml"));
        return color(shulkersFile.getString("types." + type + ".name", type));
    }

    private int getMaxHits(String type) {
        FileConfiguration shulkersFile = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "shulkers.yml"));
        return shulkersFile.getInt("types." + type + ".break-count", 20);
    }

    private String locationToString(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
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
            p.sendMessage(ChatColor.YELLOW + "/capcastle setspawn " + ChatColor.WHITE + "- Tasodifiy shalker qo'yish");
            p.sendMessage(ChatColor.YELLOW + "/capcastle setloc <tur> " + ChatColor.WHITE + "- Ma'lum turdagi quti qo'yish");
            p.sendMessage(ChatColor.YELLOW + "/capcastle start " + ChatColor.WHITE + "- Eventni boshlash");
            p.sendMessage(ChatColor.YELLOW + "/capcastle stop " + ChatColor.WHITE + "- To'xtatish");
            p.sendMessage(ChatColor.YELLOW + "/capcastle menu <tur> " + ChatColor.WHITE + "- Lootlarni sozlash");
            p.sendMessage(ChatColor.YELLOW + "/capcastle reload " + ChatColor.WHITE + "- Yangilash");
            p.sendMessage(ChatColor.GOLD + "===============================");
            return true;
        }

        switch (args[0].toLowerCase()) {
            // YANGI KOMANDA: Tasodifiy quti chiqarish
            case "setspawn":
                Location pLoc = p.getLocation();
                String locStrRandom = pLoc.getWorld().getName() + ":" + pLoc.getX() + ":" + pLoc.getY() + ":" + pLoc.getZ() + ":random";
                List<String> locsRandom = getConfig().getStringList("spawn-locations");
                locsRandom.add(locStrRandom);
                getConfig().set("spawn-locations", locsRandom);
                saveConfig();
                
                spawnShulker(pLoc, "random");
                p.sendMessage(color("&aJoy saqlandi va tasodifiy shalker qo'yildi!"));
                break;

            case "setloc":
                if (args.length < 2) {
                    p.sendMessage(color("&cTurini yozing! Masalan: /capcastle setloc qonli"));
                    return true;
                }
                String type = args[1].toLowerCase();
                FileConfiguration shulkers = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "shulkers.yml"));
                if (!shulkers.contains("types." + type)) {
                    p.sendMessage(color("&cBunday tur yo'q!"));
                    return true;
                }
                
                Location playerLoc = p.getLocation();
                String locStr = playerLoc.getWorld().getName() + ":" + playerLoc.getX() + ":" + playerLoc.getY() + ":" + playerLoc.getZ() + ":" + type;
                List<String> locs = getConfig().getStringList("spawn-locations");
                locs.add(locStr);
                getConfig().set("spawn-locations", locs);
                saveConfig();
                
                spawnShulker(playerLoc, type);
                p.sendMessage(color("&aJoy saqlandi! Turi: " + type));
                break;
                
            case "start":
                loadInitialBlocks();
                p.sendMessage(color("&aEvent boshlandi! Barcha qutilar yuklandi."));
                break;
                
            case "stop":
                for (String lStr : activeBlocks) {
                    Location l = stringToLocation(lStr);
                    if (l != null) l.getBlock().setType(Material.AIR);
                    if (holograms.containsKey(lStr)) {
                        for (ArmorStand as : holograms.get(lStr)) as.remove();
                        holograms.remove(lStr);
                    }
                }
                activeBlocks.clear();
                p.sendMessage(color("&cBarcha qutilar o'chirildi."));
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
