package com.mdvcraft.mdvclans;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Native second nametag line. Only its own BELOW_NAME objective is modified. */
final class EquippedTitleNametags implements Listener {
    private static final String CONFIG = "nametags.equipped-title-line.";
    private static final String OBJECTIVE_NAME = "mdvc_title";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private final JavaPlugin plugin;
    private final Function<UUID, String> titleLookup;
    private final Map<Scoreboard, BoardTitles> boards = new IdentityHashMap<>();
    private final Set<Scoreboard> warnedBoards = Collections.newSetFromMap(new IdentityHashMap<>());
    private BukkitTask task;
    private boolean registered;
    private boolean enabled;
    private boolean warnedPosition;
    private String format = "{title}";
    private Plugin socialPlugin;
    private Method titleMethod;
    private boolean warnedSocial;

    EquippedTitleNametags(JavaPlugin plugin) {
        this.plugin = plugin;
        this.titleLookup = this::readSocialTitle;
    }

    // Injection allows testing native scoreboard updates without loading MDVSocial.
    EquippedTitleNametags(JavaPlugin plugin, Function<UUID, String> titleLookup) {
        this.plugin = plugin;
        this.titleLookup = titleLookup;
    }

    void reload() {
        close();
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("nametags.enabled", true)
                || !config.getBoolean(CONFIG + "enabled", true)) return;
        if (!"below".equalsIgnoreCase(config.getString(CONFIG + "position", "below"))
                && !warnedPosition) {
            plugin.getLogger().warning("El título nativo solo admite position: below; se mostrará debajo del nombre.");
            warnedPosition = true;
        }
        format = config.getString(CONFIG + "format", "{title}");
        if (format == null) format = "{title}";
        long refreshTicks = Math.max(1L, config.getLong(CONFIG + "update-interval-ticks", 20L));
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        registered = true;
        enabled = true;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, refreshTicks);
    }

    void close() {
        enabled = false;
        if (task != null) task.cancel();
        task = null;
        if (registered) HandlerList.unregisterAll(this);
        registered = false;
        for (Map.Entry<Scoreboard, BoardTitles> entry : boards.entrySet()) {
            unregisterOwned(entry.getKey(), entry.getValue());
        }
        boards.clear();
        warnedBoards.clear();
        socialPlugin = null;
        titleMethod = null;
        warnedSocial = false;
    }

    void tick() {
        if (!enabled) return;
        List<Player> players = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        Set<Scoreboard> activeBoards = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<String, Component> titles = new HashMap<>();
        for (Player player : players) {
            Scoreboard board = player.getScoreboard();
            if (board != null) activeBoards.add(board);
            Component title = titleText(titleLookup.apply(player.getUniqueId()));
            if (title != null) titles.put(player.getName(), title);
        }

        for (Scoreboard board : new ArrayList<>(boards.keySet())) {
            if (!activeBoards.contains(board) || titles.isEmpty()) {
                unregisterOwned(board, boards.remove(board));
            }
        }
        warnedBoards.retainAll(activeBoards);
        if (titles.isEmpty()) return;

        // Shared boards are updated once; every target keeps their own fixed title text.
        for (Scoreboard board : activeBoards) {
            BoardTitles state = acquire(board);
            if (state == null) continue;
            for (String name : new ArrayList<>(state.applied.keySet())) {
                if (!titles.containsKey(name)) {
                    state.objective.getScore(name).resetScore();
                    state.applied.remove(name);
                }
            }
            for (Map.Entry<String, Component> entry : titles.entrySet()) {
                Score score = state.objective.getScore(entry.getKey());
                if (!score.isScoreSet() || !entry.getValue().equals(state.applied.get(entry.getKey()))) {
                    score.numberFormat(NumberFormat.fixed(entry.getValue()));
                    state.applied.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private BoardTitles acquire(Scoreboard board) {
        BoardTitles state = boards.get(board);
        Objective named = board.getObjective(OBJECTIVE_NAME);
        if (state != null && !owns(board, state)) {
            // Never adopt replacements or move an objective another plugin repurposed.
            boards.remove(board);
            state = null;
        }
        Objective belowName = board.getObjective(DisplaySlot.BELOW_NAME);
        if (belowName != null && (state == null || !state.objective.equals(belowName))) {
            if (state != null) {
                unregisterOwned(board, state);
                boards.remove(board);
            }
            warnConflict(board);
            return null;
        }
        if (state == null) {
            if (named != null) {
                warnConflict(board);
                return null;
            }
            Objective objective = board.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY,
                    Component.empty(), RenderType.INTEGER);
            // Blank fallback avoids numeric zero for entries without an equipped title.
            objective.numberFormat(NumberFormat.blank());
            objective.setDisplaySlot(DisplaySlot.BELOW_NAME);
            state = new BoardTitles(objective);
            boards.put(board, state);
        } else if (belowName == null) {
            state.objective.setDisplaySlot(DisplaySlot.BELOW_NAME);
        }
        return state;
    }

    private void warnConflict(Scoreboard board) {
        if (warnedBoards.add(board)) {
            plugin.getLogger().warning("No se muestra el título en un scoreboard: otro plugin ocupa BELOW_NAME o mdvc_title.");
        }
    }

    private boolean owns(Scoreboard board, BoardTitles state) {
        // CraftBukkit returns new Objective wrappers; equals checks the underlying objective.
        if (!state.objective.equals(board.getObjective(OBJECTIVE_NAME))) return false;
        DisplaySlot slot = state.objective.getDisplaySlot();
        return slot == null || slot == DisplaySlot.BELOW_NAME;
    }

    private void unregisterOwned(Scoreboard board, BoardTitles state) {
        if (owns(board, state)) state.objective.unregister();
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
            warnedSocial = false;
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
            if (!warnedSocial) {
                plugin.getLogger().warning("No se pudo leer el título equipado de MDVSocial: " + ex.getMessage());
                warnedSocial = true;
            }
            return "";
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        String name = event.getPlayer().getName();
        for (Map.Entry<Scoreboard, BoardTitles> entry : boards.entrySet()) {
            BoardTitles state = entry.getValue();
            if (owns(entry.getKey(), state) && state.applied.remove(name) != null) {
                state.objective.getScore(name).resetScore();
            }
        }
    }

    private static String singleLine(String text) {
        return text.replace('\n', ' ').replace('\r', ' ');
    }

    private static final class BoardTitles {
        private final Objective objective;
        private final Map<String, Component> applied = new HashMap<>();

        private BoardTitles(Objective objective) {
            this.objective = objective;
        }
    }
}
