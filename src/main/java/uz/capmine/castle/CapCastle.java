package uz.capmine.castle;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
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
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
    
    private final List<String> allTypes = Arrays.asList("oddiy", "sehrli", "qonli", "afsonaviy", "qora_imperator");
    private boolean eventActive = true;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);
        saveResource("shulkers.yml", false);
        saveResource("loots.yml", false);

        this.getCommand("capcastle").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("CapCastle tizimi muvaffaqiyatli yoqildi!");
        
        startParticleTask();
        loadInitialBlocks();
    }

    private void startParticleTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (String locStr : activeBlocks) {
                    Location loc = stringToLocation(locStr);
                    if (loc != null) {
                        loc.getWorld().spawnParticle(Particle.END_ROD, loc.clone().add(0.5, 1.5, 0.5), 3, 0.3, 0.3, 0.3, 0.01);
                    }
                }
            }
        }.runTaskTimer(this, 0L, 10L); 
    }

    private void loadInitialBlocks() {
        for (String locStr : activeBlocks) {
            Location loc = stringToLocation(locStr);
            if (loc != null && loc.getBlock().getType() != Material.AIR) {
                loc.getBlock().setType(Material.AIR);
            }
            removeHolograms(locStr);
        }
        activeBlocks.clear();
        blockHits.clear();
        blockTypes.clear();
        holograms.clear();

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
        
        if (activeBlocks.contains(locStr)) {
            loc.getBlock().setType(Material.AIR);
            removeHolograms(locStr);
            activeBlocks.remove(locStr);
            blockHits.remove(locStr);
            blockTypes.remove(locStr);
        }

        FileConfiguration shulkersFile = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "shulkers.yml"));
        String materialStr = shulkersFile.getString("types." + type + ".material", "SHULKER_BOX");
        loc.getBlock().setType(Material.valueOf(materialStr));
        
        activeBlocks.add(locStr);
        blockHits.put(locStr, 0);
        blockTypes.put(locStr, type);

        createHologram(loc, type, 0);
    }

    private void removeHolograms(String locStr) {
        if (holograms.containsKey(locStr)) {
            for (ArmorStand as : holograms.get(locStr)) {
                if (as.isValid()) as.remove();
            }
            holograms.remove(locStr);
        }
        
        Location loc = stringToLocation(locStr);
        if (loc != null) {
            for (Entity e : loc.getWorld().getNearbyEntities(loc, 2, 2, 2)) {
                if (e instanceof ArmorStand) {
                    ArmorStand as = (ArmorStand) e;
                    if (!as.isVisible()) {
                        as.remove();
                    }
                }
            }
        }
    }

    private void createHologram(Location blockLoc, String type, int currentHits) {
        String locStr = locationToString(blockLoc);
        removeHolograms(locStr);

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

        event.setCancelled(true);

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

        if (currentHits >= maxHits) {
            activeBlocks.remove(locStr);
            blockHits.remove(locStr);
            blockTypes.remove(locStr);
            removeHolograms(locStr);
            
            block.setType(Material.AIR);
            block.getWorld().playSound(block.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, 1f, 1f);
            
            dropLoot(block.getLocation().add(0.5, 0.5, 0.5), type);
            
            FileConfiguration msg = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
            String brokenMsg = msg.getString("messages.shulker-broken", "&e{player} qutini sindirdi!")
                    .replace("{player}", player.getName()).replace("{type_name}", getTypeName(type));
            
            Bukkit.broadcastMessage(color(brokenMsg));

            Location respawnLoc = block.getLocation();
            long respawnTicks = getConfig().getLong("settings.respawn-minutes", 5) * 60 * 20;
            
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!eventActive) return;
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
            ItemStack item = null;
            int chance = 100;

            // Yangi Base64 formati (item:chance) yoki eski format (MATERIAL:AMOUNT:CHANCE)
            if (itemStr.contains(":") && itemStr.split(":").length == 3) {
                String[] parts = itemStr.split(":");
                Material mat = Material.getMaterial(parts[0].toUpperCase());
                if (mat != null) {
                    item = new ItemStack(mat, Integer.parseInt(parts[1]));
                    chance = Integer.parseInt(parts[2]);
                }
            } else if (itemStr.contains(":")) {
                String[] parts = itemStr.split(":");
                item = itemFromBase64(parts[0]);
                chance = Integer.parseInt(parts[1]);
            }

            if (item != null && rand.nextInt(100) < chance) {
                loc.getWorld().dropItemNaturally(loc, item);
            }
        }
    }

    // --- GUI (MENU) TIZIMI ---
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

        // HAMMA NARSANI QO'SHISH (Custom narsalar ham saqlanadi)
        if (clicked.getType() == Material.CHEST) { 
            FileConfiguration loots = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "loots.yml"));
            List<String> list = loots.getStringList("loots." + type);
            
            int addedCount = 0;
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && item.getType() != Material.AIR) {
                    // Narsani Base64 ga o'giramiz, shunda uning nomi, NBTsi, sehrlari saqlanadi
                    String base64 = itemToBase64(item);
                    if (base64 != null) {
                        String itemStr = base64 + ":50"; // 50% default
                        list.add(itemStr);
                        addedCount++;
                    }
                }
            }
            
            if (addedCount > 0) {
                loots.set("loots." + type, list);
                try { loots.save(new File(getDataFolder(), "loots.yml")); } catch (Exception ignored) {}
                player.sendMessage(color("&aInventaringizdagi " + addedCount + " xil narsa 50% foiz bilan qo'shildi!"));
            } else {
                player.sendMessage(color("&cInventaringizda qo'shish uchun yangi narsa topilmadi."));
            }
            
            openLootMenu(player, type); 
            return;
        }

        // FOIZNI O'ZGARTIRISH YOKI O'CHIRISH
        if (clicked.getType() != Material.CHEST && clicked.getType() != Material.BARRIER && event.getSlot() < 45) {
            FileConfiguration loots = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "loots.yml"));
            List<String> list = loots.getStringList("loots." + type);
            
            int index = -1;
            // Narsani ro'yxatdan topish (hozirgi slotga qarab emas, itemga qarab)
            // Buning uchun menyudagi narsaning lore'idan yoki boshqa usuldan foydalanish kerak.
            // Eng oson yo'li: Tugmalarni boshqacha qilish.
            
            // Menyudagi narsani topish qiyin bo'lgani uchun, biz vaqtincha indeksni topamiz
            // Aslida indexni to'g'ri topish uchun ro'yxatdagi barcha narsalarni solishtirish kerak, lekin bu qiyin.
            // Eng yaxshisi: har bir narsani o'z slotiga qo'yish va slot orqali indeksni topish.
            
            // Soddalashtiramiz: 0-slotdan boshlab ro'yxatni to'ldirganmiz, demak event.getSlot() indeksga teng.
            // Lekin o'chirilganda o'rinlar siljiydi. Shuning uchun itemni o'zini tekshiramiz.
            
            // Vaqtincha eng oson usul: Itemni bazadan o'chirish o'rniga, ro'yxatni yangilash.
            // Qani, to'g'riroq yo'li:
            int targetSlot = event.getSlot();
            if (targetSlot < list.size()) {
                String itemStr = list.get(targetSlot);
                
                if (itemStr.contains(":") && itemStr.split(":").length == 3) {
                    // Eski format
                    String[] parts = itemStr.split(":");
                    int chance = Integer.parseInt(parts[2]);
                    
                    if (event.getClick() == ClickType.LEFT) { chance = Math.min(100, chance + 1); }
                    else if (event.getClick() == ClickType.RIGHT) { chance = Math.max(1, chance - 1); }
                    else if (event.getClick() == ClickType.SHIFT_LEFT) { chance = Math.min(100, chance + 10); }
                    else if (event.getClick() == ClickType.SHIFT_RIGHT) { chance = Math.max(1, chance - 10); }
                    else if (event.getClick() == ClickType.DROP || event.getClick() == ClickType.MIDDLE) {
                        list.remove(targetSlot);
                        loots.set("loots." + type, list);
                        try { loots.save(new File(getDataFolder(), "loots.yml")); } catch (Exception ignored) {}
                        player.sendMessage(color("&cLoot o'chirildi!"));
                        openLootMenu(player, type);
                        return;
                    }
                    
                    list.set(targetSlot, parts[0] + ":" + parts[1] + ":" + chance);
                } else {
                    // Yangi Base64 format
                    String[] parts = itemStr.split(":");
                    int chance = Integer.parseInt(parts[1]);
                    
                    if (event.getClick() == ClickType.LEFT) { chance = Math.min(100, chance + 1); }
                    else if (event.getClick() == ClickType.RIGHT) { chance = Math.max(1, chance - 1); }
                    else if (event.getClick() == ClickType.SHIFT_LEFT) { chance = Math.min(100, chance + 10); }
                    else if (event.getClick() == ClickType.SHIFT_RIGHT) { chance = Math.max(1, chance - 10); }
                    else if (event.getClick() == ClickType.DROP || event.getClick() == ClickType.MIDDLE) {
                        list.remove(targetSlot);
                        loots.set("loots." + type, list);
                        try { loots.save(new File(getDataFolder(), "loots.yml")); } catch (Exception ignored) {}
                        player.sendMessage(color("&cLoot o'chirildi!"));
                        openLootMenu(player, type);
                        return;
                    }
                    
                    list.set(targetSlot, parts[0] + ":" + chance);
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

        for (int i = 0; i < items.size(); i++) {
            if (i >= 45) break;
            String itemStr = items.get(i);
            ItemStack item = null;
            int chance = 100;

            if (itemStr.split(":").length == 3) {
                // Eski format
                String[] parts = itemStr.split(":");
                Material mat = Material.getMaterial(parts[0].toUpperCase());
                if (mat != null) {
                    item = new ItemStack(mat, Integer.parseInt(parts[1]));
                    chance = Integer.parseInt(parts[2]);
                }
            } else {
                // Base64 format
                String[] parts = itemStr.split(":");
                item = itemFromBase64(parts[0]);
                chance = Integer.parseInt(parts[1]);
            }

            if (item != null) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    List<String> lore = new ArrayList<>();
                    if (meta.hasLore()) lore.addAll(meta.getLore());
                    lore.add(color("&7 "));
                    lore.add(color("&fTushish foizi: &a" + chance + "%"));
                    lore.add(color("&eChap tugma &7- +1%"));
                    lore.add(color("&eO'ng tugma &7- -1%"));
                    lore.add(color("&6Shift+Chap &7- +10%"));
                    lore.add(color("&6Shift+O'ng &7- -10%"));
                    lore.add(color("&cQ (Drop) &7- O'chirish"));
                    meta.setLore(lore);
                    item.setItemMeta(meta);
                }
                inv.setItem(i, item);
            }
        }

        ItemStack addBtn = new ItemStack(Material.CHEST);
        ItemMeta addMeta = addBtn.getItemMeta();
        addMeta.setDisplayName(color("&aHamma narsani qo'shish"));
        addMeta.setLore(Arrays.asList(color("&fInventaringizdagi barcha narsalarni"), color("&fAvtomatik 50% foiz bilan qo'shadi.")));
        addBtn.setItemMeta(addMeta);
        inv.setItem(49, addBtn);

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = close.getItemMeta();
        closeMeta.setDisplayName(color("&cYopish"));
        close.setItemMeta(closeMeta);
        inv.setItem(53, close);

        p.openInventory(inv);
    }

    // BASE64 KODLASH (Custom narsalarni saqlash uchun)
    private String itemToBase64(ItemStack item) {
        try {
            ByteArrayOutputStream io = new ByteArrayOutputStream();
            BukkitObjectOutputStream os = new BukkitObjectOutputStream(io);
            os.writeObject(item);
            os.flush();
            return Base64.getEncoder().encodeToString(io.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    private ItemStack itemFromBase64(String base64) {
        try {
            ByteArrayInputStream io = new ByteArrayInputStream(Base64.getDecoder().decode(base64));
            BukkitObjectInputStream is = new BukkitObjectInputStream(io);
            return (ItemStack) is.readObject();
        } catch (Exception e) {
            return null;
        }
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
            p.sendMessage(ChatColor.YELLOW + "/capcastle del " + ChatColor.WHITE + "- Qaragan shalkeringizni o'chirish");
            p.sendMessage(ChatColor.YELLOW + "/capcastle start " + ChatColor.WHITE + "- Eventni boshlash");
            p.sendMessage(ChatColor.YELLOW + "/capcastle stop " + ChatColor.WHITE + "- To'xtatish");
            p.sendMessage(ChatColor.YELLOW + "/capcastle menu <tur> " + ChatColor.WHITE + "- Lootlarni sozlash");
            p.sendMessage(ChatColor.YELLOW + "/capcastle reload " + ChatColor.WHITE + "- Yangilash");
            p.sendMessage(ChatColor.GOLD + "===============================");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "del":
                Block target = p.getTargetBlockExact(5);
                if (target != null) {
                    String locStr = locationToString(target.getLocation());
                    if (activeBlocks.contains(locStr)) {
                        target.setType(Material.AIR);
                        removeHolograms(locStr);
                        activeBlocks.remove(locStr);
                        blockHits.remove(locStr);
                        blockTypes.remove(locStr);
                        
                        // Configdan ham o'chirish
                        List<String> rawLocs = getConfig().getStringList("spawn-locations");
                        Iterator<String> iter = rawLocs.iterator();
                        while (iter.hasNext()) {
                            if (iter.next().startsWith(target.getWorld().getName() + ":" + target.getX() + ":")) {
                                iter.remove();
                            }
                        }
                        getConfig().set("spawn-locations", rawLocs);
                        saveConfig();
                        
                        p.sendMessage(color("&aShalker muvaffaqiyatli o'chirildi!"));
                    } else {
                        p.sendMessage(color("&cSiz qaragan blok shalker emas!"));
                    }
                } else {
                    p.sendMessage(color("&cSiz blokka qaramayapsiz (juda uzoqda)!"));
                }
                break;

            case "setspawn":
                eventActive = true;
                Location pLoc = p.getLocation();
                String locStrRandom = pLoc.getWorld().getName() + ":" + pLoc.getX() + ":" + pLoc.getY() + ":" + pLoc.getZ() + ":random";
                List<String> locsRandom = getConfig().getStringList("spawn-locations");
                
                if (!locsRandom.contains(locStrRandom)) {
                    locsRandom.add(locStrRandom);
                    getConfig().set("spawn-locations", locsRandom);
                    saveConfig();
                }
                
                spawnShulker(pLoc, "random");
                p.sendMessage(color("&aJoy saqlandi va tasodifiy shalker qo'yildi!"));
                break;

            case "setloc":
                if (args.length < 2) {
                    p.sendMessage(color("&cTurini yozing! Masalan: /capcastle setloc qonli"));
                    return true;
                }
                eventActive = true;
                String type = args[1].toLowerCase();
                FileConfiguration shulkers = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "shulkers.yml"));
                if (!shulkers.contains("types." + type)) {
                    p.sendMessage(color("&cBunday tur yo'q!"));
                    return true;
                }
                
                Location playerLoc = p.getLocation();
                String locStr = playerLoc.getWorld().getName() + ":" + playerLoc.getX() + ":" + playerLoc.getY() + ":" + playerLoc.getZ() + ":" + type;
                List<String> locs = getConfig().getStringList("spawn-locations");
                
                if (!locs.contains(locStr)) {
                    locs.add(locStr);
                    getConfig().set("spawn-locations", locs);
                    saveConfig();
                }
                
                spawnShulker(playerLoc, type);
                p.sendMessage(color("&aJoy saqlandi! Turi: " + type));
                break;
                
            case "start":
                eventActive = true;
                loadInitialBlocks();
                p.sendMessage(color("&aEvent boshlandi! Eski qutilar tozalanib, yangilari yuklandi."));
                break;
                
            case "stop":
                eventActive = false;
                for (String lStr : activeBlocks) {
                    Location l = stringToLocation(lStr);
                    if (l != null) l.getBlock().setType(Material.AIR);
                    removeHolograms(lStr);
                }
                activeBlocks.clear();
                p.sendMessage(color("&cEvent to'xtatildi va barcha qutilar o'chirildi."));
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
        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }
    }
