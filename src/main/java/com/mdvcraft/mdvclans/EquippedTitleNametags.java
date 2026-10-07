package com.mdvcraft.mdvclans;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** A separate title line: never reads or modifies a player's scoreboard or name. */
final class EquippedTitleNametags implements Listener {
    private static final String CONFIG = "nametags.equipped-title-line.";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private final JavaPlugin plugin;
    private final Function<UUID, String> titleLookup;
    private final Map<UUID, TitleLine> lines = new HashMap<>();
    private final Map<UUID, Component> titles = new HashMap<>();
    private BukkitTask task;
    private boolean registered;
    private long tickNumber;
    private long refreshTicks;
    private double heightOffset;
    private double maxDistanceSquared;
    private String format;
    private boolean shadow;
    private boolean background;
    private Plugin socialPlugin;
    private Method titleMethod;
    private boolean warned;

    EquippedTitleNametags(JavaPlugin plugin) {
        this.plugin = plugin;
        this.titleLookup = this::readSocialTitle;
    }

    // Injection keeps the entity lifecycle testable without loading MDVSocial.
    EquippedTitleNametags(JavaPlugin plugin, Function<UUID, String> titleLookup) {
        this.plugin = plugin;
        this.titleLookup = titleLookup;
    }

    void reload() {
        close();
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("nametags.enabled", true)
                || !config.getBoolean(CONFIG + "enabled", true)) return;
        refreshTicks = Math.max(1L, config.getLong(CONFIG + "update-interval-ticks", 20L));
        double gap = finite(config.getDouble(CONFIG + "line-gap", 0.32), 0.32, 0.25, 2.0);
        boolean below = "below".equalsIgnoreCase(config.getString(CONFIG + "position", "above"));
        heightOffset = finite(config.getDouble(CONFIG + "name-height-offset", 0.5), 0.5, -2.0, 4.0)
                + (below ? -gap : gap);
        double distance = finite(config.getDouble(CONFIG + "max-distance-blocks", 32.0), 32.0, 1.0, 64.0);
        maxDistanceSquared = distance * distance;
        format = config.getString(CONFIG + "format", "{title}");
        if (format == null) format = "{title}";
        shadow = config.getBoolean(CONFIG + "shadow", true);
        background = config.getBoolean(CONFIG + "background", false);
        tickNumber = 0L;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        registered = true;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void close() {
        if (task != null) task.cancel();
        task = null;
        if (registered) HandlerList.unregisterAll(this);
        registered = false;
        for (TitleLine line : lines.values()) line.display.remove();
        lines.clear();
        titles.clear();
        socialPlugin = null;
        titleMethod = null;
        warned = false;
    }

    void tick() {
        List<Player> players = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        Set<UUID> online = new HashSet<>();
        Map<UUID, Location> locations = new HashMap<>();
        boolean refresh = tickNumber % refreshTicks == 0L;
        boolean refreshVisibility = tickNumber++ % 5L == 0L;
        for (Player player : players) {
            UUID uuid = player.getUniqueId();
            online.add(uuid);
            locations.put(uuid, player.getLocation());
        }
        for (Player target : players) {
            UUID uuid = target.getUniqueId();
            if (refresh || !titles.containsKey(uuid)) titles.put(uuid, titleText(titleLookup.apply(uuid)));
            update(target, players, locations, titles.get(uuid), refreshVisibility);
        }
        for (UUID uuid : new ArrayList<>(lines.keySet())) {
            if (!online.contains(uuid)) remove(uuid);
        }
        titles.keySet().retainAll(online);
    }

    private void update(Player target, List<Player> players, Map<UUID, Location> locations,
                        Component text, boolean refreshVisibility) {
        UUID uuid = target.getUniqueId();
        if (text == null || !target.isOnline() || target.isDead() || target.isSneaking()
                || target.isInvisible() || target.getGameMode() == GameMode.SPECTATOR) {
            removeLine(uuid);
            return;
        }

        Location targetLocation = locations.get(uuid);
        Location position = targetLocation.clone().add(0.0, target.getHeight() + heightOffset, 0.0);
        position.setYaw(0.0F);
        position.setPitch(0.0F);
        TitleLine line = lines.get(uuid);
        if (line != null && (!line.display.isValid() || line.display.getWorld() != target.getWorld())) {
            removeLine(uuid);
            line = null;
        }
        if (line == null) {
            TextDisplay display = target.getWorld().spawn(position, TextDisplay.class, entity -> {
                entity.setPersistent(false);
                entity.setVisibleByDefault(false);
                entity.setInvulnerable(true);
                entity.setGravity(false);
                entity.setSilent(true);
                entity.setBillboard(Display.Billboard.CENTER);
                entity.setTeleportDuration(1);
                entity.setViewRange(1.0F);
                entity.setAlignment(TextDisplay.TextAlignment.CENTER);
                entity.setLineWidth(Integer.MAX_VALUE);
                entity.setShadowed(shadow);
                entity.setSeeThrough(false);
                entity.setDefaultBackground(background);
                if (!background) entity.setBackgroundColor(Color.fromARGB(0));
                entity.text(text);
            });
            line = new TitleLine(display, text);
            lines.put(uuid, line);
            refreshVisibility = true;
        } else {
            if (!line.text.equals(text)) {
                line.display.text(text);
                line.text = text;
            }
            Location current = line.display.getLocation();
            if (current.distanceSquared(position) > 0.000001) {
                // Long teleports snap immediately rather than drawing the title across the map.
                line.display.setTeleportDuration(current.distanceSquared(position) > 16.0 ? 0 : 1);
                line.display.teleport(position);
            }
        }

        if (!refreshVisibility) return;
        Set<UUID> visible = new HashSet<>();
        for (Player viewer : players) {
            UUID viewerId = viewer.getUniqueId();
            Location viewerLocation = locations.get(viewerId);
            boolean shouldSee = !viewerId.equals(uuid) && viewerLocation.getWorld() == targetLocation.getWorld()
                    && viewer.canSee(target)
                    && viewerLocation.distanceSquared(targetLocation) <= maxDistanceSquared;
            if (shouldSee) {
                visible.add(viewerId);
                if (!line.visibleTo.contains(viewerId) || !viewer.canSee(line.display)) {
                    viewer.showEntity(plugin, line.display);
                }
            } else if (line.visibleTo.contains(viewerId)) {
                viewer.hideEntity(plugin, line.display);
            }
        }
        line.visibleTo.clear();
        line.visibleTo.addAll(visible);
    }

    private Component titleText(String title) {
        if (title == null || title.isBlank()) return null;
        Component equipped = LEGACY.deserialize(singleLine(title).replace('§', '&'));
        if (PlainTextComponentSerializer.plainText().serialize(equipped).isBlank()) return null;
        String value = singleLine(format).replace("{title}", singleLine(title));
        Component text = LEGACY.deserialize(value.replace('§', '&'));
        return PlainTextComponentSerializer.plainText().serialize(text).isBlank() ? null : text;
    }

    private String readSocialTitle(UUID uuid) {
        Plugin social = plugin.getServer().getPluginManager().getPlugin("MDVSocial");
        if (social == null || !social.isEnabled()) return "";
        if (social != socialPlugin) {
            socialPlugin = social;
            titleMethod = null;
            warned = false;
        }
        try {
            if (titleMethod == null) {
                Class<?> api = Class.forName("com.mdvcraft.mdvsocial.MDVSocialAPI", true,
                        social.getClass().getClassLoader());
                titleMethod = api.getMethod("getEquippedTitleColored", UUID.class);
            }
            Object value = titleMethod.invoke(null, uuid);
            return value instanceof String text ? text : "";
        } catch (ReflectiveOperationException | LinkageError ex) {
            if (!warned) {
                plugin.getLogger().warning("No se pudo leer el título equipado de MDVSocial: " + ex.getMessage());
                warned = true;
            }
            return "";
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        removeLine(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        // No passengers are attached, so titles never prevent another plugin's teleport.
        removeLine(event.getPlayer().getUniqueId());
    }

    private void remove(UUID uuid) {
        removeLine(uuid);
        titles.remove(uuid);
        for (TitleLine line : lines.values()) line.visibleTo.remove(uuid);
    }

    private void removeLine(UUID uuid) {
        TitleLine line = lines.remove(uuid);
        if (line != null) line.display.remove();
    }

    private static String singleLine(String text) {
        return text.replace('\n', ' ').replace('\r', ' ');
    }

    private static double finite(double value, double fallback, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    private static final class TitleLine {
        private final TextDisplay display;
        private final Set<UUID> visibleTo = new HashSet<>();
        private Component text;

        private TitleLine(TextDisplay display, Component text) {
            this.display = display;
            this.text = text;
        }
    }
}
