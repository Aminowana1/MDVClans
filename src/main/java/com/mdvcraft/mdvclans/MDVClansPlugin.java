package com.mdvcraft.mdvclans;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class MDVClansPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String REL_ALLY = "ALLY";
    private static final String REL_ENEMY = "ENEMY";
    private static final String REL_ALLY_REQUEST = "ALLY_REQUEST";
    private static final String REL_NEUTRAL = "NEUTRAL";
    private static final String MAIL_ALLY_REQUEST = "ALLY_REQUEST";
    private static final String MAIL_NEUTRAL_REQUEST = "NEUTRAL_REQUEST";
    private static final String HIDE_LINE = "__MDVCLANS_HIDE_LINE__";

    private Connection connection;
    private Economy economy;
    private Pattern idPattern;
    private FileConfiguration messages;
    private File messagesFile;
    private FileConfiguration nativeMenus;
    private File nativeMenusFile;

    private static final int[] PAGE_CONTENT_SLOTS = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
    private static final int GUI_PAGE_SIZE = PAGE_CONTENT_SLOTS.length;
    private static final int STORAGE_NAV_SLOT = 53;
    private static final int STORAGE_PAGE_ITEM_SLOTS = 53;
    private static final int BANNER_EDITOR_CANCEL_SLOT = 2;
    private static final int BANNER_EDITOR_INPUT_SLOT = 4;
    private static final int BANNER_EDITOR_CONFIRM_SLOT = 6;

    private final Map<UUID, PendingTeleport> pendingTeleports = new ConcurrentHashMap<>();
    private final Map<UUID, Long> baseCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> friendlyFireMessageCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> openStorageViewers = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingClanCreateName = new ConcurrentHashMap<>();
    private final Set<UUID> pendingBoardEdit = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingClanNameEdit = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingClanTagEdit = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> pendingRoleNameEdit = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingClanMailReply = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingPersonalMail = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> pendingBankAmount = new ConcurrentHashMap<>();
    private final Set<UUID> pendingDescriptionEdit = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> currentLogFilter = new ConcurrentHashMap<>();
    private final Map<UUID, Scoreboard> personalNametagBoards = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, String>> nametagAppliedTeams = new ConcurrentHashMap<>();
    private final Map<UUID, Long> bannerCopyCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, String> currentNativeMenu = new ConcurrentHashMap<>();
    private final Map<UUID, String> previousNativeMenu = new ConcurrentHashMap<>();
    private final Set<UUID> suppressHistoryOnce = ConcurrentHashMap.newKeySet();
    private int nametagTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("messages.yml");
        saveNativeMenuResources();
        loadMessages();
        loadNativeMenus();
        reloadLocalSettings();
        setupEconomy();

        try {
            openDatabase();
            createTables();
            cleanupExpiredInvites();
            cleanupAllClanLogs();
        } catch (Exception e) {
            getLogger().severe("No se pudo iniciar SQLite: " + e.getMessage());
            e.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        if (getCommand("clan") != null) {
            getCommand("clan").setExecutor(this);
            getCommand("clan").setTabCompleter(this);
        }
        if (getCommand("mdvclans") != null) {
            getCommand("mdvclans").setExecutor(this);
            getCommand("mdvclans").setTabCompleter(this);
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        startNametagTask();

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new MDVClansExpansion().register();
            getLogger().info("PlaceholderAPI detectado: placeholders registrados.");
        }

        getLogger().info("MDVClans 1.10.16 habilitado.");
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof BannerEditorHolder bannerHolder && !bannerHolder.completed) {
                returnSubmittedBanner(player, bannerHolder);
                bannerHolder.completed = true;
                player.closeInventory();
            }
        }
        for (PendingTeleport pending : pendingTeleports.values()) {
            Bukkit.getScheduler().cancelTask(pending.taskId());
        }
        pendingTeleports.clear();
        if (nametagTaskId != -1) {
            Bukkit.getScheduler().cancelTask(nametagTaskId);
            nametagTaskId = -1;
        }
        personalNametagBoards.clear();
        nametagAppliedTeams.clear();
        closeDatabase();
    }

    private void loadMessages() {
        messagesFile = new File(getDataFolder(), "messages.yml");
        messages = YamlConfiguration.loadConfiguration(messagesFile);
    }

    private void loadNativeMenus() {
        nativeMenusFile = new File(getDataFolder(), "NativeMenus");
        nativeMenus = new YamlConfiguration();

        File folder = nativeMenusFile;
        boolean loadedFolder = false;
        if (folder.exists() && folder.isDirectory()) {
            File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml") || name.toLowerCase(Locale.ROOT).endsWith(".yaml"));
            if (files != null && files.length > 0) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File file : files) {
                    mergeYaml(nativeMenus, YamlConfiguration.loadConfiguration(file));
                }
                loadedFolder = true;
            }
        }

        // Compatibilidad: solo usa el native-menus.yml viejo si no hay carpeta NativeMenus/*.yml.
        File legacy = new File(getDataFolder(), "native-menus.yml");
        if (!loadedFolder && legacy.exists()) {
            mergeYaml(nativeMenus, YamlConfiguration.loadConfiguration(legacy));
        }
    }

    private void mergeYaml(FileConfiguration target, FileConfiguration source) {
        for (String key : source.getKeys(true)) {
            if (source.isConfigurationSection(key)) continue;
            target.set(key, source.get(key));
        }
    }

    private String msgConfig(String path, String def) {
        if (messages == null) return def;
        return messages.getString(path, def);
    }

    private void reloadLocalSettings() {
        String regex = getConfig().getString("creation.id-regex", "^[A-Za-z0-9]+$");
        idPattern = Pattern.compile(regex);
    }

    private void setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }

    private void openDatabase() throws Exception {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        Class.forName("org.sqlite.JDBC");
        File file = new File(getDataFolder(), getConfig().getString("storage.sqlite-file", "Data/clans.db"));
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA journal_mode = WAL");
        }
    }

    private void closeDatabase() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    private synchronized void createTables() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS clans (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "tag TEXT NOT NULL UNIQUE COLLATE NOCASE," +
                    "name TEXT NOT NULL," +
                    "owner_uuid TEXT NOT NULL," +
                    "open INTEGER NOT NULL DEFAULT 0," +
                    "created_at INTEGER NOT NULL," +
                    "tier INTEGER NOT NULL DEFAULT 0," +
                    "bank_balance REAL NOT NULL DEFAULT 0," +
                    "banner TEXT," +
                    "storage TEXT," +
                    "board_message TEXT," +
                    "description TEXT," +
                    "base_world TEXT," +
                    "base_x REAL," +
                    "base_y REAL," +
                    "base_z REAL," +
                    "base_yaw REAL," +
                    "base_pitch REAL" +
                    ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS members (" +
                    "clan_id INTEGER NOT NULL," +
                    "uuid TEXT NOT NULL PRIMARY KEY," +
                    "name TEXT NOT NULL," +
                    "role INTEGER NOT NULL," +
                    "joined_at INTEGER NOT NULL," +
                    "chat_toggle INTEGER NOT NULL DEFAULT 0," +
                    "FOREIGN KEY(clan_id) REFERENCES clans(id) ON DELETE CASCADE" +
                    ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS role_names (" +
                    "clan_id INTEGER NOT NULL," +
                    "role INTEGER NOT NULL," +
                    "name TEXT NOT NULL," +
                    "PRIMARY KEY(clan_id, role)," +
                    "FOREIGN KEY(clan_id) REFERENCES clans(id) ON DELETE CASCADE" +
                    ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS clan_role_permissions (" +
                    "clan_id INTEGER NOT NULL," +
                    "permission_key TEXT NOT NULL," +
                    "role INTEGER NOT NULL," +
                    "allowed INTEGER NOT NULL DEFAULT 0," +
                    "PRIMARY KEY(clan_id, permission_key, role)," +
                    "FOREIGN KEY(clan_id) REFERENCES clans(id) ON DELETE CASCADE" +
                    ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS mdvclans_meta (" +
                    "meta_key TEXT NOT NULL PRIMARY KEY," +
                    "meta_value TEXT"
                    + ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS clan_bases (" +
                    "clan_id INTEGER NOT NULL," +
                    "base_number INTEGER NOT NULL," +
                    "world TEXT NOT NULL," +
                    "x REAL NOT NULL," +
                    "y REAL NOT NULL," +
                    "z REAL NOT NULL," +
                    "yaw REAL NOT NULL," +
                    "pitch REAL NOT NULL," +
                    "PRIMARY KEY(clan_id, base_number)," +
                    "FOREIGN KEY(clan_id) REFERENCES clans(id) ON DELETE CASCADE" +
                    ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS invites (" +
                    "clan_id INTEGER NOT NULL," +
                    "target_uuid TEXT NOT NULL," +
                    "inviter_uuid TEXT NOT NULL," +
                    "expires_at INTEGER NOT NULL," +
                    "PRIMARY KEY(clan_id, target_uuid)," +
                    "FOREIGN KEY(clan_id) REFERENCES clans(id) ON DELETE CASCADE" +
                    ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS join_requests (" +
                    "clan_id INTEGER NOT NULL," +
                    "target_uuid TEXT NOT NULL," +
                    "target_name TEXT NOT NULL," +
                    "requested_at INTEGER NOT NULL," +
                    "PRIMARY KEY(clan_id, target_uuid)," +
                    "FOREIGN KEY(clan_id) REFERENCES clans(id) ON DELETE CASCADE" +
                    ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS relations (" +
                    "clan_id INTEGER NOT NULL," +
                    "target_clan_id INTEGER NOT NULL," +
                    "relation TEXT NOT NULL," +
                    "created_at INTEGER NOT NULL," +
                    "PRIMARY KEY(clan_id, target_clan_id)," +
                    "FOREIGN KEY(clan_id) REFERENCES clans(id) ON DELETE CASCADE," +
                    "FOREIGN KEY(target_clan_id) REFERENCES clans(id) ON DELETE CASCADE" +
                    ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS clan_logs (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "clan_id INTEGER NOT NULL," +
                    "time INTEGER NOT NULL," +
                    "actor_uuid TEXT," +
                    "actor_name TEXT," +
                    "action TEXT NOT NULL," +
                    "detail TEXT," +
                    "FOREIGN KEY(clan_id) REFERENCES clans(id) ON DELETE CASCADE" +
                    ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS clan_kills (" +
                    "attacker_clan_id INTEGER NOT NULL," +
                    "victim_clan_id INTEGER NOT NULL," +
                    "kills INTEGER NOT NULL DEFAULT 0," +
                    "last_kill_at INTEGER NOT NULL," +
                    "PRIMARY KEY(attacker_clan_id, victim_clan_id)," +
                    "FOREIGN KEY(attacker_clan_id) REFERENCES clans(id) ON DELETE CASCADE," +
                    "FOREIGN KEY(victim_clan_id) REFERENCES clans(id) ON DELETE CASCADE" +
                    ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS kill_cooldowns (" +
                    "attacker_uuid TEXT NOT NULL," +
                    "victim_uuid TEXT NOT NULL," +
                    "last_kill_at INTEGER NOT NULL," +
                    "PRIMARY KEY(attacker_uuid, victim_uuid)" +
                    ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS clan_mails (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "from_clan_id INTEGER NOT NULL," +
                    "to_clan_id INTEGER NOT NULL," +
                    "sender_uuid TEXT," +
                    "sender_name TEXT," +
                    "sent_at INTEGER NOT NULL," +
                    "message TEXT NOT NULL," +
                    "mail_type TEXT NOT NULL DEFAULT 'NORMAL'," +
                    "relation_clan_id INTEGER NOT NULL DEFAULT 0," +
                    "deleted INTEGER NOT NULL DEFAULT 0," +
                    "FOREIGN KEY(from_clan_id) REFERENCES clans(id) ON DELETE CASCADE," +
                    "FOREIGN KEY(to_clan_id) REFERENCES clans(id) ON DELETE CASCADE" +
                    ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS player_profiles (" +
                    "uuid TEXT NOT NULL PRIMARY KEY," +
                    "name TEXT," +
                    "level TEXT," +
                    "race TEXT," +
                    "updated_at INTEGER NOT NULL DEFAULT 0" +
                    ")");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_members_clan ON members(clan_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_clan_bases_clan ON clan_bases(clan_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_invites_target ON invites(target_uuid)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_join_requests_target ON join_requests(target_uuid)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_logs_clan ON clan_logs(clan_id, time DESC)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_kills_victim ON clan_kills(victim_clan_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_mails_to ON clan_mails(to_clan_id, sent_at DESC)");
            try {
                st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_clans_name_unique ON clans(name COLLATE NOCASE)");
            } catch (SQLException duplicateNames) {
                getLogger().warning("No se pudo crear índice único para nombres de clanes. Revisa si existen nombres duplicados en clans.db");
            }
        }
        addColumnIfMissing("clans", "tier", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing("clans", "bank_balance", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing("clans", "banner", "TEXT");
        addColumnIfMissing("clans", "storage", "TEXT");
        addColumnIfMissing("clans", "board_message", "TEXT");
        addColumnIfMissing("clans", "description", "TEXT");
        addColumnIfMissing("clan_mails", "mail_type", "TEXT NOT NULL DEFAULT 'NORMAL'");
        addColumnIfMissing("clan_mails", "relation_clan_id", "INTEGER NOT NULL DEFAULT 0");
        migrateRolesToV19();
        migrateBasesToV110();
        ensureRoleNamesForAllClans();
        ensureSingleLeaderForAllClans();
        ensureSingleLeaderIndex();
    }

    private synchronized void addColumnIfMissing(String table, String column, String definition) {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException ignored) {
            // SQLite throws duplicate column name when upgrading an existing database. That is expected.
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("mdvclans")) {
            return handleAdminCommand(sender, args);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&cEste comando solo puede usarlo un jugador."));
            return true;
        }
        if (!player.hasPermission("mdvclans.use")) {
            msg(player, "&cNo tienes permiso.");
            return true;
        }

        if (args.length == 0 || equalsAny(args[0], "ayuda", "help", "?")) {
            sendHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            if (!canUseClanCommand(player, sub, args)) {
                msg(player, msgConfig("menu.requires-clan", "&cNo perteneces a ningún clan. Solo puedes abrir la lista de clanes, ver información pública, enviar solicitudes, aceptar invitaciones o crear un clan."));
                return true;
            }
            switch (sub) {
                case "crear", "create" -> handleCreate(player, args);
                case "menu", "gui", "menú", "abrir", "ui", "interfaz" -> handleClanMenuCommand(player, args);
                case "info" -> handleInfo(player, args);
                case "lista", "list" -> handleList(player, args);
                case "invitar", "invite" -> handleInvite(player, args);
                case "aceptar", "accept" -> handleAccept(player, args);
                case "rechazar", "declinar", "reject", "deny" -> handleRejectInvite(player, args);
                case "unirse", "join" -> handleJoin(player, args);
                case "abierto", "open" -> handleOpen(player, args);
                case "salir", "leave" -> handleLeave(player);
                case "expulsar", "kick" -> handleKick(player, args);
                case "promover", "promote" -> handlePromote(player, args);
                case "degradar", "demote" -> handleDemote(player, args);
                case "setrango", "setrank" -> handleSetRank(player, args);
                case "lider", "líder", "leader", "transferirlider", "transferir-lider", "transferleader" -> handleLeaderTransfer(player, args);
                case "rol", "rango", "rolnombre" -> handleRoleName(player, args);
                case "chat" -> handleClanChatCommand(player, args, 1);
                case "c" -> handleClanChatCommand(player, args, 1);
                case "setbase" -> handleSetBase(player, args);
                case "base", "home" -> handleBase(player, args);
                case "relacion", "relation" -> handleRelation(player, args);
                case "banco" -> handleBank(player, args);
                case "mejorar", "upgrade", "tier" -> handleTierUpgrade(player);
                case "depositar" -> handleBankShortcut(player, args, true);
                case "retirar" -> handleBankShortcut(player, args, false);
                case "almacen", "almacén" -> handleStorage(player);
                case "estandarte", "banner" -> handleBanner(player, args);
                case "logs", "registro", "registros" -> handleLogs(player, args);
                case "tablero" -> handleBoard(player, args);
                case "descripcion", "descripción", "desc" -> handleDescription(player, args);
                case "correo", "correos" -> handleClanMail(player, args);
                case "editar", "ajustes" -> handleEditClan(player, args);
                case "solicitudes" -> handleJoinRequests(player, args);
                case "top" -> handleTop(player, args);
                case "bajas", "estadisticas", "estadísticas" -> handleKillStats(player, args);
                case "disolver", "disband" -> handleDisband(player, args);
                default -> {
                    msg(player, "&cSubcomando desconocido. Usa &e/clan ayuda&c.");
                }
            }
        } catch (SQLException e) {
            getLogger().severe("Error SQL en /clan " + sub + ": " + e.getMessage());
            e.printStackTrace();
            msg(player, "&cOcurrió un error interno de base de datos.");
        }
        return true;
    }

    private boolean canUseClanCommand(Player player, String sub, String[] args) throws SQLException {
        if (getMember(player.getUniqueId()).isPresent()) return true;
        String normalized = normalizeUiName(sub);
        if (equalsAny(normalized, "ayuda", "help", "?", "crear", "create", "lista", "list", "info", "unirse", "join", "aceptar", "accept", "rechazar", "declinar", "reject", "deny")) return true;
        if (equalsAny(normalized, "menu", "gui", "menu", "abrir", "ui", "interfaz")) {
            if (args.length <= 1) return true;
            return isUiAllowedWithoutClan(args[1]);
        }
        return false;
    }

    private boolean handleAdminCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mdvclans.admin")) {
            sender.sendMessage(color("&cNo tienes permiso."));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadMessages();
            loadNativeMenus();
            reloadLocalSettings();
            if (nametagTaskId != -1) Bukkit.getScheduler().cancelTask(nametagTaskId);
            nametagTaskId = -1;
            startNametagTask();
            requestNametagSync(2L);
            sender.sendMessage(color("&aMDVClans recargado."));
            return true;
        }
        if (args.length > 0 && equalsAny(args[0], "wipebases", "limpiarbases", "borrarbase", "borrarbases")) {
            if (args.length < 2 || !equalsAny(args[1], "confirmar", "confirm", "si", "yes")) {
                sender.sendMessage(color("&cConfirmación requerida: &e/mdvclans wipebases confirmar"));
                sender.sendMessage(color("&7Esto borra la base de &ctodos &7los clanes. Útil antes/después de resetear mundo."));
                return true;
            }
            try {
                int affected = clearAllClanBases();
                sender.sendMessage(color("&aBases de clanes limpiadas. Clanes afectados: &e" + affected));
                Bukkit.getOnlinePlayers().forEach(p -> msg(p, "&eUn administrador limpió todas las bases de clanes."));
            } catch (SQLException e) {
                getLogger().severe("Error limpiando bases de clan: " + e.getMessage());
                sender.sendMessage(color("&cNo se pudieron limpiar las bases. Revisa consola."));
            }
            return true;
        }
        sender.sendMessage(color("&6MDVClans admin:"));
        sender.sendMessage(color("&e/mdvclans reload &7- Recarga config, mensajes, menús y nametags."));
        sender.sendMessage(color("&e/mdvclans wipebases confirmar &7- Borra todas las bases de clanes."));
        return true;
    }


    private void sendHelp(Player player) {
        msg(player, "&6MDVClans &7comandos:");
        player.sendMessage(color("&e/clan crear &7- Inicia creación guiada por chat."));
        player.sendMessage(color("&e/clan info [ID] &7- Muestra información."));
        player.sendMessage(color("&e/clan lista &7- Lista los clanes."));
        player.sendMessage(color("&e/clan invitar <jugador> &7- Invita a alguien."));
        player.sendMessage(color("&e/clan aceptar [ID] &7- Acepta invitación."));
        player.sendMessage(color("&e/clan rechazar [ID] &7- Rechaza invitación."));
        player.sendMessage(color("&e/clan unirse <ID> &7- Entra a un clan abierto."));
        player.sendMessage(color("&e/clan abierto <on/off> &7- Cambia entrada libre."));
        player.sendMessage(color("&e/clan chat [mensaje] &7- Alterna o habla por clan."));
        player.sendMessage(color("&e/clan setbase &7/ &e/clan base &7- Base del clan."));
        player.sendMessage(color("&e/clan relacion <ID> <neutral|aliado|enemigo>"));
        player.sendMessage(color("&e/clan rol <0-4> <nombre> &7- Nombra un rango."));
        player.sendMessage(color("&e/clan lider <jugador> &7- Transfiere el liderazgo."));
        player.sendMessage(color("&e/clan editar <nombre|id> <valor> &7- Ajustes del clan."));
        player.sendMessage(color("&e/clan solicitudes &7- Solicitudes pendientes."));
        player.sendMessage(color("&e/clan banco &7- Banco del clan."));
        player.sendMessage(color("&e/clan mejorar &7- Mejora el tier del clan usando el banco."));
        player.sendMessage(color("&e/clan almacen &7- Almacén compartido."));
        player.sendMessage(color("&e/clan estandarte set/ver &7- Banner oficial."));
        player.sendMessage(color("&e/clan top [fuerza|kills|banco] &7- Ranking."));
        player.sendMessage(color("&e/clan bajas &7- Estadísticas de kills."));
        player.sendMessage(color("&e/clan logs &7- Registro básico."));
        player.sendMessage(color("&e/clan abrir <ui> &7- Abre una UI dinámica para MDVSocial."));
        player.sendMessage(color("&8UIs: auto, gestion, sinclan, miembros, info, relaciones, almacen, lista, correo, top, bajas, logs, ajustes, solicitudes."));
        player.sendMessage(color("&e/clan tablero set <texto> &7- Edita el tablero."));
        player.sendMessage(color("&e/clan descripcion <ver|set|limpiar> &7- Descripción pública."));
        player.sendMessage(color("&e/clan correo clan <ID> <mensaje> &7- Envía correo a otro clan."));
    }

    private void startClanCreateNamePrompt(Player player) throws SQLException {
        if (!getConfig().getBoolean("creation.enabled", true)) {
            msg(player, "&cLa creación de clanes está desactivada.");
            return;
        }
        if (getMember(player.getUniqueId()).isPresent()) {
            msg(player, "&cYa perteneces a un clan.");
            return;
        }
        if (countClans() >= getConfig().getInt("limits.max-clans", 60)) {
            msg(player, "&cEl servidor alcanzó el máximo de clanes.");
            return;
        }
        pendingClanCreateName.put(player.getUniqueId(), "");
        player.closeInventory();
        msg(player, msgConfig("creation.chat-name-prompt", "&7Escribe el &enombre completo &7del clan en el chat. Ejemplo: &fMedieval Craft&7. Escribe &ccancelar &7para cancelar."));
    }

    private void handleCreate(Player player, String[] args) throws SQLException {
        if (!getConfig().getBoolean("creation.enabled", true)) {
            msg(player, "&cLa creación de clanes está desactivada.");
            return;
        }
        if (args.length < 3) {
            startClanCreateNamePrompt(player);
            return;
        }
        if (getMember(player.getUniqueId()).isPresent()) {
            msg(player, "&cYa perteneces a un clan.");
            return;
        }
        if (countClans() >= getConfig().getInt("limits.max-clans", 60)) {
            msg(player, "&cEl servidor alcanzó el máximo de clanes.");
            return;
        }

        String tag = normalizeTag(args[1]);
        String name = join(args, 2);
        String validation = validateClanIdentity(tag, name);
        if (validation != null) {
            msg(player, validation);
            return;
        }
        if (getClanByTag(tag).isPresent()) {
            msg(player, "&cYa existe un clan con ese ID.");
            return;
        }
        if (getClanByName(name).isPresent()) {
            msg(player, "&cYa existe un clan con ese nombre.");
            return;
        }

        double cost = getConfig().getDouble("creation.cost", 10.0);
        if (getConfig().getBoolean("creation.cost-enabled", false) && cost > 0) {
            if (economy == null) {
                msg(player, "&cNo hay economía Vault disponible para cobrar la creación.");
                return;
            }
            if (!economy.has(player, cost)) {
                msg(player, "&cNecesitas &e" + cost + " &cmonedas para crear un clan.");
                return;
            }
            economy.withdrawPlayer(player, cost);
        }

        long now = System.currentTimeMillis();
        int clanId;
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO clans(tag,name,owner_uuid,open,created_at) VALUES(?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, tag);
                ps.setString(2, name);
                ps.setString(3, player.getUniqueId().toString());
                ps.setInt(4, 0);
                ps.setLong(5, now);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (!rs.next()) throw new SQLException("No se pudo obtener ID de clan creado.");
                    clanId = rs.getInt(1);
                }
            }
            addMemberUnsafe(clanId, player.getUniqueId(), player.getName(), maxRole(), now, 0);
            seedDefaultRoleNames(clanId);
        }
        logAction(clanId, player, "CREAR", "Clan creado: " + tag + " / " + name);
        msg(player, "&aClan creado: &8[&b" + tag + "&8] &f" + name);
    }

    private void handleInfo(Player player, String[] args) throws SQLException {
        Optional<Clan> clanOpt;
        if (args.length >= 2) clanOpt = getClanByTag(args[1]);
        else clanOpt = getPlayerClan(player.getUniqueId());
        if (clanOpt.isEmpty()) {
            msg(player, "&cClan no encontrado.");
            return;
        }
        Clan clan = clanOpt.get();
        List<Member> members = getMembers(clan.id());
        player.sendMessage(color("&8&m-----------------------------"));
        player.sendMessage(color("&6Clan: &8[&b" + clan.tag() + "&8] &f" + clan.name() + " &8- &d" + tierName(clan.tier())));
        player.sendMessage(color("&7Miembros: &e" + members.size() + "&7/&e" + maxMembersForClan(clan)));
        player.sendMessage(color("&7Entrada: " + (clan.open() ? "&aAbierta" : "&cCon invitación")));
        player.sendMessage(color("&7Descripción: &f" + clanDescription(clan)));
        player.sendMessage(color("&7Tier: &d" + tierName(clan.tier()) + " &8(" + clan.tier() + ")"));
        player.sendMessage(color("&7Bases: &e" + countClanBases(clan.id()) + "&7/&e" + maxBasesForClan(clan)));
        player.sendMessage(color("&7Banco: &e" + formatNumber(clan.bankBalance()) + " &7| Fuerza: &e" + formatNumber(calculateStrength(clan))));
        player.sendMessage(color("&7Kills de clan: &e" + getTotalKillsByClan(clan.id()) + " &7| Bajas sufridas: &c" + getTotalDeathsByClan(clan.id())));
        player.sendMessage(color("&7Estandarte: " + (clan.hasBanner() ? "&aDefinido" : "&cNo definido")));
        player.sendMessage(color("&7Relación contigo: " + relationText(getRelationBetween(getOwnClanId(player.getUniqueId()), clan.id()))));
        StringJoiner sj = new StringJoiner("&7, ");
        for (Member m : members) {
            sj.add("&e" + m.name() + " &8(" + getRoleName(clan.id(), m.role()) + "&8)");
        }
        player.sendMessage(color("&7Miembros: " + sj));
        player.sendMessage(color("&8&m-----------------------------"));
    }

    private void handleList(Player player, String[] args) throws SQLException {
        int page = 1;
        if (args.length >= 2) {
            try { page = Math.max(1, Integer.parseInt(args[1])); } catch (NumberFormatException ignored) {}
        }
        int pageSize = 8;
        List<Clan> clans = listClans();
        int pages = Math.max(1, (int) Math.ceil(clans.size() / (double) pageSize));
        page = Math.min(page, pages);
        player.sendMessage(color("&8&m-----&r &6Clanes &7(" + page + "/" + pages + ") &8&m-----"));
        int start = (page - 1) * pageSize;
        for (int i = start; i < Math.min(start + pageSize, clans.size()); i++) {
            Clan c = clans.get(i);
            int count = countMembers(c.id());
            player.sendMessage(color("&8[&b" + c.tag() + "&8] &f" + c.name() + " &8- &d" + tierName(c.tier()) + " &7- &e" + count + " &7miembros &8| &6Fuerza &e" + formatNumber(calculateStrength(c))));
        }
        if (clans.isEmpty()) player.sendMessage(color("&7No hay clanes creados todavía."));
    }

    private void handleInvite(Player player, String[] args) throws SQLException {
        if (args.length < 2) {
            msg(player, "&cUso: &e/clan invitar <jugador>");
            return;
        }
        Member inviter = requireMember(player);
        if (inviter == null) return;
        if (!hasRank(player, inviter, "invite")) return;
        Clan clan = getClan(inviter.clanId()).orElseThrow();
        if (countMembers(clan.id()) >= maxMembersForClan(clan)) {
            msg(player, "&cTu clan ya alcanzó el límite de miembros.");
            return;
        }

        String targetNameInput = args[1];
        Player onlineTarget = Bukkit.getPlayerExact(targetNameInput);
        OfflinePlayer target = onlineTarget != null ? onlineTarget : Bukkit.getOfflinePlayer(targetNameInput);
        UUID targetUuid = target.getUniqueId();
        String targetName = target.getName() != null && !target.getName().isBlank() ? target.getName() : targetNameInput;

        if (targetUuid.equals(player.getUniqueId())) {
            msg(player, "&cNo puedes invitarte a tu propio clan.");
            return;
        }
        if (getMember(targetUuid).isPresent()) {
            msg(player, "&cEse jugador ya pertenece a un clan.");
            return;
        }

        long expires = System.currentTimeMillis() + inviteExpireMillis();
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO invites(clan_id,target_uuid,inviter_uuid,expires_at) VALUES(?,?,?,?)")) {
                ps.setInt(1, clan.id());
                ps.setString(2, targetUuid.toString());
                ps.setString(3, player.getUniqueId().toString());
                ps.setLong(4, expires);
                ps.executeUpdate();
            }
        }
        msg(player, "&aInvitaste a &e" + targetName + " &aal clan." + (onlineTarget == null ? " &7(offline)" : ""));
        boolean sentMail = sendMDVSocialClanInviteMail(player, targetUuid, targetName, clan, expires);
        if (onlineTarget != null) {
            msg(onlineTarget, "&aRecibiste una invitación del clan &8[&b" + clan.tag() + "&8] &f" + clan.name() + "&a.");
            if (sentMail) {
                onlineTarget.sendMessage(color("&7También llegó a tu &e/correo&7 personal."));
            } else {
                onlineTarget.sendMessage(color("&7Usa &e/clan aceptar " + clan.tag() + " &7para entrar."));
            }
        } else if (sentMail) {
            msg(player, "&7La invitación quedó guardada en su &e/correo &7personal para cuando vuelva.");
        } else {
            msg(player, "&7La invitación interna quedó guardada. Cuando vuelva podrá usar &e/clan aceptar " + clan.tag() + "&7.");
        }
    }

    private long inviteExpireMillis() {
        if (getConfig().isSet("invites.expire-days")) {
            double days = Math.max(0.01D, getConfig().getDouble("invites.expire-days", 3.0D));
            return (long) (days * 86_400_000L);
        }
        long seconds = getConfig().getLong("invites.expire-seconds", 259200L);
        return Math.max(1L, seconds) * 1000L;
    }

    private boolean sendMDVSocialClanInviteMail(Player inviter, UUID targetUuid, String targetName, Clan clan, long expiresAt) {
        if (!getConfig().getBoolean("integrations.mdvsocial.clan-invite-mail.enabled", true)) return false;
        if (Bukkit.getPluginManager().getPlugin("MDVSocial") == null || !Bukkit.getPluginManager().isPluginEnabled("MDVSocial")) return false;
        try {
            Class<?> api = Class.forName("com.mdvcraft.mdvsocial.MDVSocialAPI");
            String fromName = applyClanInvitePlaceholders(
                    getConfig().getString("integrations.mdvsocial.clan-invite-mail.from-name", "{inviter}"),
                    inviter, targetUuid, targetName, clan
            );
            String message = applyClanInvitePlaceholders(
                    getConfig().getString("integrations.mdvsocial.clan-invite-mail.message", "{inviter} te invitó al clan {clan_name} [{clan_id}]. Abre esta carta para aceptar o rechazar."),
                    inviter, targetUuid, targetName, clan
            );
            String bannerData = clan.banner() == null ? "" : clan.banner();
            try {
                java.lang.reflect.Method method = api.getMethod("sendClanInviteMail", UUID.class, String.class, UUID.class, String.class, String.class, String.class, String.class, String.class, long.class);
                Object result = method.invoke(null, targetUuid, targetName, inviter.getUniqueId(), fromName, clan.tag(), clan.name(), message, bannerData, expiresAt);
                return Boolean.TRUE.equals(result);
            } catch (NoSuchMethodException ignored) {
                java.lang.reflect.Method method = api.getMethod("sendClanInviteMail", UUID.class, String.class, UUID.class, String.class, String.class, String.class, String.class, long.class);
                Object result = method.invoke(null, targetUuid, targetName, inviter.getUniqueId(), fromName, clan.tag(), clan.name(), message, expiresAt);
                return Boolean.TRUE.equals(result);
            }
        } catch (Throwable ex) {
            if (getConfig().getBoolean("integrations.mdvsocial.clan-invite-mail.debug", false)) {
                getLogger().warning("No se pudo enviar invitación a MDVSocial: " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
            }
            return false;
        }
    }

    private String applyClanInvitePlaceholders(String input, Player inviter, UUID targetUuid, String targetName, Clan clan) {
        String out = input == null ? "" : input;
        return out
                .replace("{clan_name}", clan.name())
                .replace("{clan_id}", clan.tag())
                .replace("{clan_tag}", clan.tag())
                .replace("{inviter}", inviter.getName())
                .replace("{inviter_name}", inviter.getName())
                .replace("{inviter_uuid}", inviter.getUniqueId().toString())
                .replace("{player}", targetName)
                .replace("{player_name}", targetName)
                .replace("{target}", targetName)
                .replace("{target_name}", targetName)
                .replace("{target_uuid}", targetUuid.toString());
    }

    private void handleAccept(Player player, String[] args) throws SQLException {
        if (getMember(player.getUniqueId()).isPresent()) {
            msg(player, "&cYa perteneces a un clan.");
            return;
        }
        cleanupExpiredInvites();
            cleanupAllClanLogs();
        Optional<Clan> clanOpt = args.length >= 2 ? getClanByTag(args[1]) : getLatestInvite(player.getUniqueId());
        if (clanOpt.isEmpty()) {
            msg(player, "&cNo tienes una invitación válida para ese clan.");
            return;
        }
        Clan clan = clanOpt.get();
        if (!hasInvite(clan.id(), player.getUniqueId())) {
            msg(player, "&cNo tienes una invitación válida para ese clan.");
            return;
        }
        if (joinClan(player, clan, "&aAceptaste la invitación y entraste al clan &8[&b" + clan.tag() + "&8]&a.")) {
            removeInvite(clan.id(), player.getUniqueId());
        }
    }

    private void handleRejectInvite(Player player, String[] args) throws SQLException {
        cleanupExpiredInvites();
        Optional<Clan> clanOpt = args.length >= 2 ? getClanByTag(args[1]) : getLatestInvite(player.getUniqueId());
        if (clanOpt.isEmpty()) {
            msg(player, "&cNo tienes una invitación válida para ese clan.");
            return;
        }
        Clan clan = clanOpt.get();
        if (!hasInvite(clan.id(), player.getUniqueId())) {
            msg(player, "&cNo tienes una invitación válida para ese clan.");
            return;
        }
        removeInvite(clan.id(), player.getUniqueId());
        msg(player, "&7Rechazaste la invitación del clan &8[&b" + clan.tag() + "&8]&7.");
        logAction(clan.id(), player, "INVITACION", player.getName() + " rechazó una invitación al clan.");
    }

    private void handleJoin(Player player, String[] args) throws SQLException {
        if (args.length < 2) {
            msg(player, "&cUso: &e/clan unirse <ID>");
            return;
        }
        if (getMember(player.getUniqueId()).isPresent()) {
            msg(player, "&cYa perteneces a un clan.");
            return;
        }
        Optional<Clan> clanOpt = getClanByTag(args[1]);
        if (clanOpt.isEmpty()) {
            msg(player, "&cClan no encontrado.");
            return;
        }
        Clan clan = clanOpt.get();
        if (!clan.open()) {
            createJoinRequest(clan.id(), player.getUniqueId(), player.getName());
            logAction(clan.id(), player, "SOLICITUD", "Solicitó unirse al clan");
            msg(player, "&aSolicitud enviada al clan &8[&b" + clan.tag() + "&8]&a. Un rango alto deberá aceptarte.");
            notifyClan(clan.id(), "&e" + player.getName() + " &7solicitó unirse al clan. Revisa &e/clan menu info&7.");
            return;
        }
        joinClan(player, clan, "&aEntraste al clan &8[&b" + clan.tag() + "&8]&a.");
    }

    private boolean joinClan(Player player, Clan clan, String successMessage) throws SQLException {
        if (countMembers(clan.id()) >= maxMembersForClan(clan)) {
            msg(player, "&cEse clan ya alcanzó el límite de miembros.");
            return false;
        }
        synchronized (this) {
            addMemberUnsafe(clan.id(), player.getUniqueId(), player.getName(), minRole(), System.currentTimeMillis(), 0);
            deleteJoinRequestsForPlayerUnsafe(player.getUniqueId());
        }
        msg(player, successMessage);
        broadcastToClan(clan.id(), "&e" + player.getName() + " &ase unió al clan.");
        logAction(clan.id(), player, "UNIRSE", player.getName() + " se unió al clan.");
        return true;
    }

    private void handleOpen(Player player, String[] args) throws SQLException {
        if (args.length < 2) {
            msg(player, "&cUso: &e/clan abierto <on/off>");
            return;
        }
        Member member = requireMember(player);
        if (member == null) return;
        if (!hasRank(player, member, "open")) return;
        boolean open = parseBoolean(args[1]);
        setClanOpen(member.clanId(), open);
        broadcastToClan(member.clanId(), "&7Entrada del clan: " + (open ? "&aabierta" : "&ccon invitación") + "&7.");
        logAction(member.clanId(), player, "ABIERTO", "Entrada " + (open ? "abierta" : "cerrada"));
    }

    private boolean handleLeave(Player player) throws SQLException {
        Member member = requireMember(player);
        if (member == null) return false;
        Clan clan = getClan(member.clanId()).orElseThrow();

        if (member.role() >= maxRole()) {
            if (countMembers(clan.id()) > 1) {
                msg(player, "&cEres líder. Pasa el liderazgo o disuelve el clan antes de salir.");
                return false;
            }

            disbandClan(clan.id());
            if (getMember(player.getUniqueId()).isPresent() || getClan(clan.id()).isPresent()) {
                throw new SQLException("El clan o su miembro líder siguen presentes después de disolver el clan " + clan.id());
            }
            msg(player, "&cDisolviste tu clan al salir.");
            return true;
        }

        logAction(clan.id(), player, "SALIR", player.getName() + " salió del clan.");
        removeMember(player.getUniqueId());
        if (getMember(player.getUniqueId()).isPresent()) {
            throw new SQLException("El miembro sigue presente después de salir del clan " + clan.id());
        }
        msg(player, "&aSaliste del clan.");
        broadcastToClan(clan.id(), "&e" + player.getName() + " &7salió del clan.");
        return true;
    }

    private void handleKick(Player player, String[] args) throws SQLException {
        if (args.length < 2) {
            msg(player, "&cUso: &e/clan expulsar <jugador>");
            return;
        }
        Member actor = requireMember(player);
        if (actor == null) return;
        if (!hasRank(player, actor, "kick")) return;
        Optional<Member> targetOpt = getMemberByNameInClan(actor.clanId(), args[1]);
        if (targetOpt.isEmpty()) {
            msg(player, "&cEse jugador no está en tu clan.");
            return;
        }
        Member target = targetOpt.get();
        if (target.uuid().equals(player.getUniqueId())) {
            msg(player, "&cNo puedes expulsarte a ti mismo.");
            return;
        }
        if (target.role() >= actor.role()) {
            msg(player, "&cNo puedes expulsar a alguien de rango igual o mayor.");
            return;
        }
        logAction(actor.clanId(), player, "EXPULSAR", "Expulsó a " + target.name());
        removeMember(target.uuid());
        msg(player, "&aExpulsaste a &e" + target.name() + "&a.");
        Player online = Bukkit.getPlayer(target.uuid());
        if (online != null) msg(online, "&cFuiste expulsado del clan.");
        broadcastToClan(actor.clanId(), "&e" + target.name() + " &cfue expulsado del clan.");
    }

    private void handlePromote(Player player, String[] args) throws SQLException {
        changeRoleByStep(player, args, +1);
    }

    private void handleDemote(Player player, String[] args) throws SQLException {
        changeRoleByStep(player, args, -1);
    }

    private void changeRoleByStep(Player player, String[] args, int step) throws SQLException {
        if (args.length < 2) {
            msg(player, "&cUso: &e/clan " + (step > 0 ? "promover" : "degradar") + " <jugador>");
            return;
        }
        Member actor = requireMember(player);
        if (actor == null) return;
        if (!hasRank(player, actor, step > 0 ? "promote" : "demote")) return;
        Optional<Member> targetOpt = getMemberByNameInClan(actor.clanId(), args[1]);
        if (targetOpt.isEmpty()) {
            msg(player, "&cEse jugador no está en tu clan.");
            return;
        }
        Member target = targetOpt.get();
        if (target.role() >= actor.role()) {
            msg(player, "&cNo puedes modificar a alguien de rango igual o mayor.");
            return;
        }
        int newRole = Math.max(minRole(), Math.min(maxRole() - 1, target.role() + step));
        setMemberRole(target.uuid(), newRole);
        logAction(actor.clanId(), player, step > 0 ? "PROMOVER" : "DEGRADAR", target.name() + " a rango " + newRole);
        broadcastToClan(actor.clanId(), "&e" + target.name() + " &7ahora es &b" + getRoleName(actor.clanId(), newRole) + "&7.");
    }

    private void handleSetRank(Player player, String[] args) throws SQLException {
        if (args.length < 3) {
            msg(player, "&cUso: &e/clan setrango <jugador> <0-" + (maxRole() - 1) + ">");
            msg(player, "&7Para transferir líder usa &e/clan lider <jugador>&7.");
            return;
        }
        Member actor = requireMember(player);
        if (actor == null) return;
        if (!hasRank(player, actor, "set-rank")) return;
        Optional<Member> targetOpt = getMemberByNameInClan(actor.clanId(), args[1]);
        if (targetOpt.isEmpty()) {
            msg(player, "&cEse jugador no está en tu clan.");
            return;
        }
        int role;
        try { role = Integer.parseInt(args[2]); } catch (NumberFormatException e) {
            msg(player, "&cEl rango debe ser un número.");
            return;
        }
        if (role < minRole() || role > maxRole()) {
            msg(player, "&cRango inválido. Usa " + minRole() + "-" + maxRole() + ".");
            return;
        }
        if (role >= maxRole()) {
            msg(player, "&cNo puedes asignar el rango de líder con /clan setrango.");
            msg(player, "&7Para mantener un único líder usa &e/clan lider <jugador>&7.");
            return;
        }
        Member target = targetOpt.get();
        if (target.role() >= maxRole()) {
            msg(player, "&cNo puedes cambiar el rango del líder con /clan setrango.");
            msg(player, "&7Primero transfiere el liderazgo con &e/clan lider <jugador>&7.");
            return;
        }
        if (!target.uuid().equals(player.getUniqueId()) && target.role() >= actor.role()) {
            msg(player, "&cNo puedes modificar a alguien de rango igual o mayor.");
            return;
        }
        setMemberRole(target.uuid(), role);
        logAction(actor.clanId(), player, "SETRANGO", target.name() + " a rango " + role);
        broadcastToClan(actor.clanId(), "&e" + target.name() + " &7ahora es &b" + getRoleName(actor.clanId(), role) + "&7.");
    }

    private void handleLeaderTransfer(Player player, String[] args) throws SQLException {
        if (args.length < 2) {
            msg(player, "&cUso: &e/clan lider <jugador>");
            return;
        }
        Member actor = requireMember(player);
        if (actor == null) return;
        if (actor.role() < maxRole()) {
            msg(player, "&cSolo el líder actual puede transferir el liderazgo.");
            return;
        }
        Optional<Member> targetOpt = getMemberByNameInClan(actor.clanId(), args[1]);
        if (targetOpt.isEmpty()) {
            msg(player, "&cEse jugador no está en tu clan.");
            return;
        }
        Member target = targetOpt.get();
        if (target.uuid().equals(player.getUniqueId())) {
            msg(player, "&cYa eres el líder de este clan.");
            return;
        }
        transferLeadership(actor.clanId(), target.uuid());
        logAction(actor.clanId(), player, "LIDER", "Transfirió el liderazgo a " + target.name());
        broadcastToClan(actor.clanId(), "&6" + target.name() + " &ees ahora el líder del clan.");
    }

    private void handleRoleName(Player player, String[] args) throws SQLException {
        if (args.length < 3) {
            msg(player, "&cUso: &e/clan rol <0-4> <nombre>");
            return;
        }
        Member member = requireMember(player);
        if (member == null) return;
        if (!hasRank(player, member, "rename-role")) return;
        int role;
        try { role = Integer.parseInt(args[1]); } catch (NumberFormatException e) {
            msg(player, "&cEl rango debe ser un número.");
            return;
        }
        if (role < minRole() || role > maxRole()) {
            msg(player, "&cRango inválido. Usa " + minRole() + "-" + maxRole() + ".");
            return;
        }
        String name = join(args, 2);
        if (name.length() > 16) {
            msg(player, "&cEl nombre del rol no puede superar 16 caracteres.");
            return;
        }
        setRoleName(member.clanId(), role, name);
        logAction(member.clanId(), player, "ROL", "Rango " + role + " = " + name);
        broadcastToClan(member.clanId(), "&7El rango &e" + role + " &7ahora se llama &b" + name + "&7.");
    }

    private void handleClanChatCommand(Player player, String[] args, int messageStart) throws SQLException {
        Member member = requireMember(player);
        if (member == null) return;
        if (args.length <= messageStart) {
            boolean now = !member.chatToggle();
            setChatToggle(player.getUniqueId(), now);
            msg(player, now ? "&aChat de clan activado." : "&7Chat normal activado.");
            return;
        }
        sendClanChat(player, join(args, messageStart));
    }

    private void handleSetBase(Player player, String[] args) throws SQLException {
        Member member = requireMember(player);
        if (member == null) return;
        if (!hasRank(player, member, "setbase")) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        int baseNumber = parseBaseNumber(args, 1);
        int maxBases = maxBasesForClan(clan);
        if (baseNumber < 1 || baseNumber > maxBases) {
            msg(player, "&cTu clan solo puede tener bases del &e1 &cal &e" + maxBases + "&c. Tier actual: &d" + tierName(clan.tier()) + "&c.");
            return;
        }
        if (!isWorldAllowed(player.getWorld())) {
            msg(player, "&cNo puedes fijar base de clan en este mundo.");
            return;
        }
        setClanBase(member.clanId(), baseNumber, player.getLocation());
        logAction(member.clanId(), player, "SETBASE", "Base " + baseNumber + " actualizada en " + player.getWorld().getName());
        broadcastToClan(member.clanId(), "&aLa base &e#" + baseNumber + " &adel clan fue actualizada por &e" + player.getName() + "&a.");
    }

    private void handleBase(Player player, String[] args) throws SQLException {
        Member member = requireMember(player);
        if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        int baseNumber = parseBaseNumber(args, 1);
        Optional<ClanBase> baseOpt = getClanBase(clan.id(), baseNumber);
        if (baseOpt.isEmpty()) {
            int totalBases = countClanBases(clan.id());
            if (totalBases <= 0) msg(player, "&cTu clan no tiene base definida.");
            else msg(player, "&cLa base &e#" + baseNumber + " &cno está definida. Bases disponibles: &e" + totalBases + "&c.");
            return;
        }
        ClanBase base = baseOpt.get();
        World world = Bukkit.getWorld(base.world());
        if (world == null) {
            msg(player, "&cEl mundo de la base no está cargado.");
            return;
        }
        long now = System.currentTimeMillis();
        long cooldownMs = getConfig().getLong("base.cooldown-seconds", 60) * 1000L;
        long last = baseCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < cooldownMs) {
            msg(player, "&cDebes esperar &e" + ((cooldownMs - (now - last)) / 1000L) + "s &cpara volver a usar la base.");
            return;
        }
        int delay = Math.max(0, getConfig().getInt("base.teleport-delay-seconds", 10));
        Location destination = new Location(world, base.x(), base.y(), base.z(), base.yaw(), base.pitch());
        if (delay <= 0) {
            player.teleport(destination);
            baseCooldowns.put(player.getUniqueId(), now);
            msg(player, "&aTeletransportado a la base &e#" + baseNumber + "&a del clan.");
            return;
        }
        if (pendingTeleports.containsKey(player.getUniqueId())) {
            msg(player, "&cYa tienes un teletransporte pendiente.");
            return;
        }
        Location start = player.getLocation().clone();
        int taskId = Bukkit.getScheduler().runTaskLater(this, () -> {
            pendingTeleports.remove(player.getUniqueId());
            if (!player.isOnline()) return;
            player.teleport(destination);
            baseCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
            msg(player, "&aTeletransportado a la base &e#" + baseNumber + "&a del clan.");
        }, delay * 20L).getTaskId();
        pendingTeleports.put(player.getUniqueId(), new PendingTeleport(taskId, start));
        msg(player, "&7Teletransporte en &e" + delay + "s&7. No te muevas ni recibas daño.");
    }

    private void handleRelation(Player player, String[] args) throws SQLException {
        if (args.length < 3) {
            msg(player, "&cUso: &e/clan relacion <ID> <neutral|aliado|enemigo>");
            return;
        }
        Member member = requireMember(player);
        if (member == null) return;
        if (!hasRank(player, member, "relation")) return;
        Optional<Clan> ownOpt = getClan(member.clanId());
        Optional<Clan> targetOpt = getClanByTag(args[1]);
        if (ownOpt.isEmpty() || targetOpt.isEmpty()) {
            msg(player, "&cClan no encontrado.");
            return;
        }
        Clan own = ownOpt.get();
        Clan target = targetOpt.get();
        if (own.id() == target.id()) {
            msg(player, "&cNo puedes cambiar relación con tu propio clan.");
            return;
        }
        String mode = args[2].toLowerCase(Locale.ROOT);
        boolean enemies = REL_ENEMY.equals(getRelationBetween(own.id(), target.id()));

        if (equalsAny(mode, "neutral", "neutro")) {
            if (enemies) {
                if (hasPendingRelationMail(own.id(), target.id(), MAIL_NEUTRAL_REQUEST)) {
                    acceptNeutrality(own, target, player);
                    return;
                }
                if (hasPendingRelationMail(target.id(), own.id(), MAIL_NEUTRAL_REQUEST)) {
                    msg(player, "&eYa enviaste una solicitud de paz a &6" + target.tag() + "&e. Espera su respuesta.");
                    return;
                }
                createNeutralityRequestMail(own, target, player);
                logAction(own.id(), player, "DIPLOMACIA", "Solicitud de paz a " + target.tag());
                msg(player, "&aSolicitud de paz enviada al buzón del clan &e" + target.tag() + "&a.");
                notifyClan(target.id(), "&7El clan &e" + own.tag() + " &7les envió una solicitud de paz al buzón del clan.");
                return;
            }
            removeRelation(own.id(), target.id());
            removeRelation(target.id(), own.id());
            cleanupAllRelationRequestMails(own.id(), target.id());
            logAction(own.id(), player, "RELACION", "Neutral con " + target.tag());
            logAction(target.id(), player == null ? null : player.getUniqueId(), player == null ? "Sistema" : player.getName(), "RELACION", "Neutral con " + own.tag());
            msg(player, "&7Relación con &e" + target.tag() + " &7establecida como neutral.");
            notifyClan(target.id(), "&7El clan &e" + own.tag() + " &7estableció relación neutral con ustedes.");
            return;
        }
        if (equalsAny(mode, "enemigo", "enemy")) {
            setRelation(own.id(), target.id(), REL_ENEMY);
            setRelation(target.id(), own.id(), REL_ENEMY);
            cleanupAllRelationRequestMails(own.id(), target.id());
            logAction(own.id(), player, "RELACION", "Enemigo: " + target.tag());
            logAction(target.id(), player == null ? null : player.getUniqueId(), player == null ? "Sistema" : player.getName(), "RELACION", "Enemigo: " + own.tag());
            broadcastToClan(own.id(), "&cTu clan declaró enemigo a &e" + target.tag() + "&c.");
            notifyClan(target.id(), "&cEl clan &e" + own.tag() + " &clos declaró enemigos. La enemistad quedó registrada en ambos clanes.");
            return;
        }
        if (equalsAny(mode, "aliado", "ally")) {
            if (enemies) {
                if (hasPendingRelationMail(own.id(), target.id(), MAIL_ALLY_REQUEST)) {
                    acceptAlliance(own, target, player);
                    return;
                }
                if (hasPendingRelationMail(target.id(), own.id(), MAIL_ALLY_REQUEST)) {
                    msg(player, "&eYa enviaste una solicitud de alianza a &6" + target.tag() + "&e. Espera su respuesta.");
                    return;
                }
                if (!canAcceptNewAlly(own, target.id())) {
                    msg(player, "&cTu clan alcanzó el máximo de aliados para su tier (&e" + countAllies(own.id()) + "&c/&e" + maxAlliesForClan(own) + "&c). Mejora el clan para tener más alianzas.");
                    return;
                }
                if (!canAcceptNewAlly(target, own.id())) {
                    msg(player, "&cEse clan ya alcanzó el máximo de aliados de su tier.");
                    return;
                }
                createAllianceRequestMail(own, target, player);
                logAction(own.id(), player, "DIPLOMACIA", "Solicitud de alianza a " + target.tag() + " desde enemistad");
                msg(player, "&aSolicitud de alianza enviada al buzón del clan &e" + target.tag() + "&a. La enemistad seguirá activa hasta que acepten.");
                notifyClan(target.id(), "&dEl clan &e" + own.tag() + " &dles envió una solicitud de alianza al buzón del clan. La enemistad seguirá activa hasta aceptar.");
                return;
            }

            boolean needsAccept = getConfig().getBoolean("relations.ally-requires-accept", true);
            if (!needsAccept || getRelation(target.id(), own.id()).equals(REL_ALLY_REQUEST)) {
                acceptAlliance(own, target, player);
            } else {
                if (REL_ALLY_REQUEST.equals(getRelation(own.id(), target.id())) || hasPendingRelationMail(target.id(), own.id(), MAIL_ALLY_REQUEST)) {
                    msg(player, "&eYa tienes una solicitud de alianza pendiente con &6" + target.tag() + "&e.");
                    return;
                }
                if (!canAcceptNewAlly(own, target.id())) {
                    msg(player, "&cTu clan alcanzó el máximo de aliados para su tier (&e" + countAllies(own.id()) + "&c/&e" + maxAlliesForClan(own) + "&c). Mejora el clan para tener más alianzas.");
                    return;
                }
                if (!canAcceptNewAlly(target, own.id())) {
                    msg(player, "&cEse clan ya alcanzó el máximo de aliados de su tier.");
                    return;
                }
                setRelation(own.id(), target.id(), REL_ALLY_REQUEST);
                createAllianceRequestMail(own, target, player);
                logAction(own.id(), player, "DIPLOMACIA", "Solicitud de alianza a " + target.tag());
                msg(player, "&aSolicitud de alianza enviada al buzón del clan &e" + target.tag() + "&a.");
                notifyClan(target.id(), "&dEl clan &e" + own.tag() + " &dles envió una solicitud de alianza al buzón del clan.");
            }
            return;
        }
        msg(player, "&cRelación inválida: usa neutral, aliado o enemigo.");
    }


    private void createAllianceRequestMail(Clan from, Clan to, Player actor) throws SQLException {
        String message = getConfig().getString("relations.ally-request-mail-message", "El clan {from_name} [{from_id}] solicita una alianza con tu clan.")
                .replace("{from_name}", from.name())
                .replace("{from_id}", from.tag())
                .replace("{to_name}", to.name())
                .replace("{to_id}", to.tag());
        createClanMail(from.id(), to.id(), actor == null ? null : actor.getUniqueId(), actor == null ? "Sistema" : actor.getName(), message, MAIL_ALLY_REQUEST, from.id());
    }

    private void createNeutralityRequestMail(Clan from, Clan to, Player actor) throws SQLException {
        String message = getConfig().getString("relations.neutral-request-mail-message", "El clan {from_name} [{from_id}] solicita un tratado de paz para volver a neutralidad.")
                .replace("{from_name}", from.name())
                .replace("{from_id}", from.tag())
                .replace("{to_name}", to.name())
                .replace("{to_id}", to.tag());
        createClanMail(from.id(), to.id(), actor == null ? null : actor.getUniqueId(), actor == null ? "Sistema" : actor.getName(), message, MAIL_NEUTRAL_REQUEST, from.id());
    }

    private void acceptAlliance(Clan own, Clan target, Player actor) throws SQLException {
        if (!canAcceptNewAlly(own, target.id())) {
            if (actor != null) msg(actor, "&cTu clan alcanzó el máximo de aliados para su tier (&e" + countAllies(own.id()) + "&c/&e" + maxAlliesForClan(own) + "&c).");
            return;
        }
        if (!canAcceptNewAlly(target, own.id())) {
            if (actor != null) msg(actor, "&cEl otro clan alcanzó el máximo de aliados de su tier.");
            return;
        }
        setRelation(own.id(), target.id(), REL_ALLY);
        setRelation(target.id(), own.id(), REL_ALLY);
        requestNametagSync(5L);
        requestNametagSync(30L);
        cleanupAllRelationRequestMails(own.id(), target.id());
        logAction(own.id(), actor, "DIPLOMACIA", "Alianza aceptada con " + target.tag());
        logAction(target.id(), actor == null ? null : actor.getUniqueId(), actor == null ? "Sistema" : actor.getName(), "DIPLOMACIA", "Alianza aceptada con " + own.tag());
        broadcastToClan(own.id(), "&9Ahora son aliados del clan &e" + target.tag() + "&9.");
        notifyClan(target.id(), "&9Ahora son aliados del clan &e" + own.tag() + "&9.");
    }

    private void rejectAllianceRequest(Clan own, Clan requester, Player actor) throws SQLException {
        removeRelation(requester.id(), own.id(), REL_ALLY_REQUEST);
        cleanupAllianceMails(own.id(), requester.id());
        logAction(own.id(), actor, "DIPLOMACIA", "Rechazó alianza de " + requester.tag());
        logAction(requester.id(), actor == null ? null : actor.getUniqueId(), actor == null ? "Sistema" : actor.getName(), "DIPLOMACIA", "Solicitud de alianza rechazada por " + own.tag());
        broadcastToClan(own.id(), "&cSolicitud de alianza de &e" + requester.tag() + " &crechazada.");
        notifyClan(requester.id(), "&cEl clan &e" + own.tag() + " &crechazó la solicitud de alianza.");
    }

    private void acceptNeutrality(Clan own, Clan requester, Player actor) throws SQLException {
        removeRelation(own.id(), requester.id());
        removeRelation(requester.id(), own.id());
        cleanupAllRelationRequestMails(own.id(), requester.id());
        requestNametagSync(5L);
        requestNametagSync(30L);
        logAction(own.id(), actor, "DIPLOMACIA", "Tratado de paz aceptado con " + requester.tag());
        logAction(requester.id(), actor == null ? null : actor.getUniqueId(), actor == null ? "Sistema" : actor.getName(), "DIPLOMACIA", "Tratado de paz aceptado con " + own.tag());
        broadcastToClan(own.id(), "&7Tu clan aceptó el tratado de paz con &e" + requester.tag() + "&7. Ahora son neutrales.");
        notifyClan(requester.id(), "&7El clan &e" + own.tag() + " &7aceptó el tratado de paz. Ahora son neutrales.");
    }

    private void rejectNeutralityRequest(Clan own, Clan requester, Player actor) throws SQLException {
        cleanupNeutralityMails(own.id(), requester.id());
        logAction(own.id(), actor, "DIPLOMACIA", "Rechazó paz de " + requester.tag());
        logAction(requester.id(), actor == null ? null : actor.getUniqueId(), actor == null ? "Sistema" : actor.getName(), "DIPLOMACIA", "Solicitud de paz rechazada por " + own.tag());
        broadcastToClan(own.id(), "&cSolicitud de paz de &e" + requester.tag() + " &crechazada.");
        notifyClan(requester.id(), "&cEl clan &e" + own.tag() + " &crechazó la solicitud de paz. La enemistad continúa.");
    }





    private void handleClanMenuCommand(Player player, String[] args) throws SQLException {
        if (args.length <= 1) {
            openMainMenu(player);
            return;
        }
        String sub = normalizeUiName(args[1]);
        int page = 1;
        if (args.length >= 3) {
            try { page = Math.max(1, Integer.parseInt(args[2])); } catch (NumberFormatException ignored) { }
        }
        resetNativeHistoryForExternalOpen(player);
        openDynamicClanUi(player, sub, page);
    }

    private void resetNativeHistoryForExternalOpen(Player player) {
        UUID uuid = player.getUniqueId();
        currentNativeMenu.remove(uuid);
        previousNativeMenu.remove(uuid);
        suppressHistoryOnce.remove(uuid);
    }

    private String normalizeUiName(String raw) {
        if (raw == null || raw.isBlank()) return "auto";
        return raw.toLowerCase(Locale.ROOT)
                .replace('á', 'a')
                .replace('é', 'e')
                .replace('í', 'i')
                .replace('ó', 'o')
                .replace('ú', 'u')
                .replace('ñ', 'n')
                .replace('-', '_')
                .replace(' ', '_');
    }

    private void openDynamicClanUi(Player player, String ui, int page) throws SQLException {
        if (ui == null || ui.isBlank() || ui.equals("auto")) {
            openMainMenu(player);
            return;
        }

        boolean hasClan = getMember(player.getUniqueId()).isPresent();
        boolean allowedWithoutClan = isUiAllowedWithoutClan(ui);
        if (!hasClan && !allowedWithoutClan) {
            msg(player, msgConfig("menu.requires-clan", "&cNo perteneces a ningún clan. Solo puedes abrir la lista de clanes o crear uno."));
            return;
        }

        switch (ui) {
            case "principal", "completo", "panel", "hub", "gestion", "conclan", "con_clan", "clan_con_clan", "sinclan", "sin_clan", "clan_sin_clan", "crear", "creacion", "crear_clan", "nuevo_clan" -> openLegacyMainOrRedirect(player, ui);
            case "miembros", "miembro", "members" -> openMembersMenu(player, page);
            case "info", "tablero", "informacion", "informacion_clan" -> openClanInfoMenu(player);
            case "relaciones", "relation", "relations" -> openRelationsMenu(player);
            case "relaciones_lista", "lista_relaciones", "aliados_enemigos" -> openRelationsListMenu(player, page);
            case "almacen", "almacen_banco", "banco_almacen", "recursos" -> openStorageHubMenu(player);
            case "base", "bases", "bases_clan", "base_clan" -> openBasesMenu(player);
            case "lista", "clanes", "lista_clanes", "lista_con_clan", "lista_sinclan", "lista_sin_clan" -> openClanListMenu(player, page);
            case "correo", "correos", "buzon", "buzon_clan" -> openMailboxMenu(player, page);
            case "top", "ranking", "fuerza", "top_fuerza", "ranking_fuerza" -> openTopGui(player, "fuerza");
            case "top_kills", "ranking_kills" -> openTopGui(player, "kills");
            case "top_banco", "ranking_banco" -> openTopGui(player, "banco");
            case "bajas", "kills", "estadisticas" -> openKillStatsGui(player);
            case "logs", "registro", "registros" -> openLogsGui(player, page);
            case "ajustes", "settings", "configuracion" -> openSettingsMenu(player);
            case "rangos", "roles" -> openRoleSettingsMenu(player);
            case "permisos", "permissions" -> openPermissionsMenu(player, page);
            case "permisos_editar", "editar_permisos", "permissions_edit", "permissionsedit" -> openPermissionsEditMenu(player, page);
            case "solicitudes", "requests", "join_requests" -> openJoinRequestsMenu(player, page);
            default -> openMainMenu(player);
        }
    }

    private boolean isUiAllowedWithoutClan(String ui) {
        String normalized = normalizeUiName(ui);
        return switch (normalized) {
            case "sinclan", "sin_clan", "clan_sin_clan", "lista", "clanes", "lista_clanes", "lista_sinclan", "lista_sin_clan", "crear", "creacion", "crear_clan", "nuevo_clan" -> true;
            default -> false;
        };
    }

    private void handleBoard(Player player, String[] args) throws SQLException {
        Member member = requireMember(player);
        if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        if (args.length == 1 || equalsAny(args[1], "ver", "info")) {
            msg(player, "&6Tablero del clan &8[&b" + clan.tag() + "&8]&7:");
            for (String line : boardLines(clan.boardMessage())) player.sendMessage(color("&8- &f" + line));
            return;
        }
        if (equalsAny(args[1], "set", "poner", "editar")) {
            if (!hasRank(player, member, "board-edit")) return;
            if (args.length < 3) {
                pendingBoardEdit.add(player.getUniqueId());
                player.closeInventory();
                msg(player, "&7Escribe en el chat el nuevo texto del tablero. Usa &e| &7para separar líneas. Escribe &ccancelar &7para cancelar.");
                return;
            }
            String text = join(args, 2);
            setClanBoard(member.clanId(), text);
            logAction(member.clanId(), player, "TABLERO", "Actualizó el tablero");
            broadcastToClan(member.clanId(), "&e" + player.getName() + " &aactualizó el tablero del clan.");
            return;
        }
        if (equalsAny(args[1], "limpiar", "clear")) {
            if (!hasRank(player, member, "board-edit")) return;
            setClanBoard(member.clanId(), null);
            logAction(member.clanId(), player, "TABLERO", "Limpió el tablero");
            broadcastToClan(member.clanId(), "&7El tablero del clan fue limpiado.");
            return;
        }
        msg(player, "&cUso: &e/clan tablero <ver|set|limpiar>");
    }

    private void handleDescription(Player player, String[] args) throws SQLException {
        Member member = requireMember(player);
        if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        if (args.length == 1 || equalsAny(args[1], "ver", "info")) {
            msg(player, "&6Descripción pública del clan &8[&d" + clan.tag() + "&8]&7:");
            for (String line : descriptionLines(clan.description())) player.sendMessage(color("&8- &f" + line));
            return;
        }
        if (equalsAny(args[1], "set", "poner", "editar")) {
            if (!hasRank(player, member, "description-edit")) return;
            if (args.length < 3) {
                beginDescriptionEdit(player);
                return;
            }
            String text = join(args, 2).trim();
            int max = getConfig().getInt("clan-description.max-length", 180);
            if (text.length() > max) {
                msg(player, "&cLa descripción es demasiado larga. Máximo: &e" + max + " &ccaracteres.");
                return;
            }
            setClanDescription(member.clanId(), text);
            logAction(member.clanId(), player, "DESCRIPCION", "Actualizó la descripción pública");
            broadcastToClan(member.clanId(), "&e" + player.getName() + " &aactualizó la descripción pública del clan.");
            return;
        }
        if (equalsAny(args[1], "limpiar", "clear")) {
            if (!hasRank(player, member, "description-edit")) return;
            setClanDescription(member.clanId(), null);
            logAction(member.clanId(), player, "DESCRIPCION", "Limpió la descripción pública");
            broadcastToClan(member.clanId(), "&7La descripción pública del clan fue limpiada.");
            return;
        }
        msg(player, "&cUso: &e/clan descripcion <ver|set|limpiar>");
    }

    private void handleClanMail(Player player, String[] args) throws SQLException {
        Member member = requireMember(player);
        if (member == null) return;
        if (args.length == 1 || equalsAny(args[1], "ver", "buzon", "buzón")) {
            openMailboxMenu(player, 1);
            return;
        }
        if (equalsAny(args[1], "clan", "enviar")) {
            if (!hasRank(player, member, "mail-send")) return;
            if (args.length < 4) {
                msg(player, "&cUso: &e/clan correo clan <ID> <mensaje>");
                return;
            }
            Optional<Clan> targetOpt = getClanByTag(args[2]);
            if (targetOpt.isEmpty()) {
                msg(player, "&cClan destino no encontrado.");
                return;
            }
            Clan own = getClan(member.clanId()).orElseThrow();
            Clan target = targetOpt.get();
            if (own.id() == target.id()) {
                msg(player, "&cNo puedes enviar correo a tu propio clan desde aquí.");
                return;
            }
            String message = join(args, 3);
            createClanMail(own.id(), target.id(), player.getUniqueId(), player.getName(), message);
            logAction(own.id(), player, "CORREO", "Envió correo a " + target.tag());
            notifyClan(target.id(), "&dEl clan &e" + own.tag() + " &dles envió un correo de clan.");
            msg(player, "&aCorreo enviado al clan &e" + target.tag() + "&a.");
            return;
        }
        if (equalsAny(args[1], "borrar", "eliminar")) {
            if (!hasRank(player, member, "mail-delete")) return;
            if (args.length < 3) {
                msg(player, "&cUso: &e/clan correo borrar <id>");
                return;
            }
            int id;
            try { id = Integer.parseInt(args[2]); } catch (NumberFormatException e) { msg(player, "&cID inválido."); return; }
            if (deleteClanMail(member.clanId(), id)) {
                logAction(member.clanId(), player, "CORREO", "Eliminó correo #" + id);
                msg(player, "&aCorreo eliminado.");
            } else msg(player, "&cNo se encontró ese correo para tu clan.");
            return;
        }
        msg(player, "&cUso: &e/clan correo <ver|clan|borrar>");
    }

    private void handleBankShortcut(Player player, String[] args, boolean deposit) throws SQLException {
        if (args.length < 2) {
            msg(player, "&cUso: &e/clan " + (deposit ? "depositar" : "retirar") + " <cantidad>");
            return;
        }
        handleBank(player, new String[]{"banco", deposit ? "depositar" : "retirar", args[1]});
    }

    private void handleBank(Player player, String[] args) throws SQLException {
        if (!getConfig().getBoolean("bank.enabled", true)) {
            msg(player, "&cEl banco de clan está desactivado.");
            return;
        }
        Member member = requireMember(player);
        if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        if (args.length == 1) {
            msg(player, "&6Banco del clan &8[&b" + clan.tag() + "&8]&7: &e" + formatNumber(clan.bankBalance()) + " &7monedas.");
            player.sendMessage(color("&7Usa &e/clan banco depositar <cantidad> &7o &e/clan banco retirar <cantidad>&7."));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (equalsAny(action, "log", "logs", "registro")) {
            showLogs(player, member.clanId(), 1, "BANCO");
            return;
        }
        if (!equalsAny(action, "depositar", "retirar")) {
            msg(player, "&cUso: &e/clan banco <depositar|retirar|log> [cantidad]");
            return;
        }
        if (economy == null) {
            msg(player, "&cNo hay economía Vault disponible.");
            return;
        }
        if (args.length < 3) {
            msg(player, "&cDebes indicar una cantidad.");
            return;
        }
        double amount;
        try { amount = Double.parseDouble(args[2].replace(",", ".")); } catch (NumberFormatException e) {
            msg(player, "&cCantidad inválida.");
            return;
        }
        if (amount <= 0) {
            msg(player, "&cLa cantidad debe ser mayor a 0.");
            return;
        }
        if (equalsAny(action, "depositar")) {
            if (!hasRank(player, member, "bank-deposit")) return;
            if (!economy.has(player, amount)) {
                msg(player, "&cNo tienes suficiente dinero.");
                return;
            }
            economy.withdrawPlayer(player, amount);
            addBankBalance(member.clanId(), amount);
            logAction(member.clanId(), player, "BANCO", "Depositó " + formatNumber(amount));
            broadcastToClan(member.clanId(), "&e" + player.getName() + " &adepositó &e" + formatNumber(amount) + " &aen el banco del clan.");
            return;
        }
        if (!hasRank(player, member, "bank-withdraw")) return;
        clan = getClan(member.clanId()).orElseThrow();
        if (clan.bankBalance() < amount) {
            msg(player, "&cEl banco del clan no tiene suficiente dinero.");
            return;
        }
        addBankBalance(member.clanId(), -amount);
        economy.depositPlayer(player, amount);
        logAction(member.clanId(), player, "BANCO", "Retiró " + formatNumber(amount));
        broadcastToClan(member.clanId(), "&e" + player.getName() + " &cretiró &e" + formatNumber(amount) + " &cdel banco del clan.");
    }

    private void handleStorage(Player player) throws SQLException {
        openClanStorage(player, 1);
    }

    private void openClanStorage(Player player, int page) throws SQLException {
        if (!getConfig().getBoolean("storage-chest.enabled", true)) {
            msg(player, "&cEl almacén de clan está desactivado.");
            return;
        }
        Member member = requireMember(player);
        if (member == null) return;
        if (!hasRank(player, member, "storage-open")) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        int totalSlots = storageSlotsForClan(clan);
        int pages = storagePagesForSlots(totalSlots);
        page = Math.max(1, Math.min(page, pages));
        int invSize = storageInventorySize(totalSlots);
        Map<String, String> ph = clanPlaceholders(clan);
        ph.put("storage_page", String.valueOf(page));
        ph.put("storage_max_page", String.valueOf(pages));
        String title = color(applyPlaceholders(getConfig().getString("storage-chest.title", "&8Almacén {id} &7{storage_page}/{storage_max_page}"), ph));
        Inventory inv = Bukkit.createInventory(new StorageInventoryHolder(clan.id(), page, totalSlots), invSize, title);
        ItemStack[] allItems = deserializeInventory(clan.storage(), totalSlots);
        putStoragePage(inv, allItems, page, totalSlots);
        if (pages > 1) inv.setItem(STORAGE_NAV_SLOT, storageNavItem(page, pages));
        openNativeInventory(player, inv);
        logAction(clan.id(), player, "ALMACEN", "Abrió almacén página " + page + "/" + pages);
    }

    private void handleBanner(Player player, String[] args) throws SQLException {
        Member member = requireMember(player);
        if (member == null) return;
        if (args.length < 2) {
            msg(player, "&cUso: &e/clan estandarte <set|ver|quitar>");
            return;
        }
        Clan clan = getClan(member.clanId()).orElseThrow();
        String action = args[1].toLowerCase(Locale.ROOT);
        if (equalsAny(action, "set", "poner", "guardar")) {
            if (!hasRank(player, member, "banner-set")) return;
            openBannerEditor(player);
            return;
        }
        if (equalsAny(action, "ver", "view")) {
            if (!clan.hasBanner()) {
                msg(player, "&cTu clan no tiene estandarte oficial.");
                return;
            }
            try {
                ItemStack bannerTemplate = singleItem(itemFromBase64(clan.banner()));
                BannerViewHolder holder = new BannerViewHolder(clan.id(), bannerTemplate);
                Inventory inv = Bukkit.createInventory(holder, 9, color("&8Estandarte " + clan.tag()));
                fill(inv);

                ItemStack preview = bannerTemplate.clone();
                ItemMeta previewMeta = preview.getItemMeta();
                if (previewMeta != null) {
                    List<String> lore = previewMeta.hasLore() && previewMeta.getLore() != null
                            ? new ArrayList<>(previewMeta.getLore())
                            : new ArrayList<>();
                    lore.add("");
                    int cooldownSeconds = Math.max(0, getConfig().getInt("banners.copy-cooldown-seconds", 10));
                    lore.add(color("&eHaz clic para obtener una copia."));
                    lore.add(color(cooldownSeconds > 0
                            ? "&7Espera &f" + cooldownSeconds + "s &7entre cada copia."
                            : "&7Puedes sacar todas las que necesites."));
                    previewMeta.setLore(lore);
                    preview.setItemMeta(previewMeta);
                }
                inv.setItem(BANNER_EDITOR_INPUT_SLOT, preview);
                openNativeInventory(player, inv);
            } catch (Exception e) {
                msg(player, "&cNo se pudo cargar el estandarte guardado.");
            }
            return;
        }
        if (equalsAny(action, "quitar", "remove")) {
            if (!hasRank(player, member, "banner-set")) return;
            setClanBanner(member.clanId(), null);
            logAction(member.clanId(), player, "ESTANDARTE", "Estandarte oficial eliminado");
            broadcastToClan(member.clanId(), "&7El estandarte oficial del clan fue eliminado.");
            return;
        }
        msg(player, "&cUso: &e/clan estandarte <set|ver|quitar>");
    }

    private void openBannerEditor(Player player) throws SQLException {
        Member member = requireMember(player);
        if (member == null) return;
        if (!hasRank(player, member, "banner-set")) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        Map<String, String> ph = clanPlaceholders(clan);
        String title = nativeTitle("banner-editor", "&8Cambiar estandarte &b{id}", ph);
        BannerEditorHolder holder = new BannerEditorHolder(clan.id(), clan.banner());
        Inventory inv = Bukkit.createInventory(holder, 9, title);
        fill(inv);

        int inputSlot = bannerEditorInputSlot();
        inv.setItem(inputSlot, null);
        if (clan.hasBanner()) {
            try {
                ItemStack current = itemFromBase64(clan.banner());
                current.setAmount(1);
                inv.setItem(inputSlot, current);
            } catch (Exception e) {
                getLogger().warning("No se pudo mostrar el banner actual del clan " + clan.id() + ": " + e.getMessage());
            }
        }

        inv.setItem(bannerEditorCancelSlot(), nativeItem(
                "menus.banner-editor.items.cancel",
                Material.RED_STAINED_GLASS_PANE,
                "&c&lRechazar cambio",
                List.of("", "&7No modifica el estandarte", "&7actual del clan.", "", "&eClick para volver."),
                ph));
        inv.setItem(bannerEditorConfirmSlot(), nativeItem(
                "menus.banner-editor.items.confirm",
                Material.LIME_STAINED_GLASS_PANE,
                "&a&lConfirmar estandarte",
                List.of("", "&7Coloca un estandarte en", "&7el espacio central.", "", "&eClick para confirmar."),
                ph));
        player.openInventory(inv);
    }

    private int bannerEditorCancelSlot() {
        return Math.max(0, Math.min(8, nativeSlot("menus.banner-editor.items.cancel.slot", BANNER_EDITOR_CANCEL_SLOT)));
    }

    private int bannerEditorInputSlot() {
        return Math.max(0, Math.min(8, nativeSlot("menus.banner-editor.items.input.slot", BANNER_EDITOR_INPUT_SLOT)));
    }

    private int bannerEditorConfirmSlot() {
        return Math.max(0, Math.min(8, nativeSlot("menus.banner-editor.items.confirm.slot", BANNER_EDITOR_CONFIRM_SLOT)));
    }

    private boolean isBanner(ItemStack item) {
        return item != null && item.getType() != Material.AIR && item.getType().name().endsWith("_BANNER") && !item.getType().name().endsWith("_WALL_BANNER");
    }

    private ItemStack singleItem(ItemStack item) {
        if (item == null) return null;
        ItemStack copy = item.clone();
        copy.setAmount(1);
        return copy;
    }

    private void showOriginalBanner(Inventory inv, BannerEditorHolder holder) {
        int inputSlot = bannerEditorInputSlot();
        inv.setItem(inputSlot, null);
        if (holder.originalBannerData == null || holder.originalBannerData.isBlank()) return;
        try {
            inv.setItem(inputSlot, singleItem(itemFromBase64(holder.originalBannerData)));
        } catch (Exception e) {
            getLogger().warning("No se pudo restaurar la vista previa del estandarte: " + e.getMessage());
        }
    }

    private void giveItemSafely(Player player, ItemStack item) {
        if (item == null || item.getType() == Material.AIR || item.getAmount() <= 0) return;
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private void returnSubmittedBanner(Player player, BannerEditorHolder holder) {
        if (holder.submittedBanner == null) return;
        giveItemSafely(player, holder.submittedBanner.clone());
        holder.submittedBanner = null;
    }

    private void placeBannerInEditor(Player player, Inventory inv, BannerEditorHolder holder, ItemStack source) {
        if (!isBanner(source)) {
            msg(player, "&cEse espacio solo acepta estandartes.");
            return;
        }
        returnSubmittedBanner(player, holder);
        holder.submittedBanner = singleItem(source);
        inv.setItem(bannerEditorInputSlot(), holder.submittedBanner.clone());
    }

    private void removeSubmittedBannerToCursor(Player player, Inventory inv, BannerEditorHolder holder) {
        if (holder.submittedBanner == null) return;
        ItemStack returned = holder.submittedBanner.clone();
        holder.submittedBanner = null;
        showOriginalBanner(inv, holder);
        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || cursor.getType() == Material.AIR) player.setItemOnCursor(returned);
        else giveItemSafely(player, returned);
    }

    private void finishBannerEditor(Player player, BannerEditorHolder holder, boolean reopenSettings) {
        holder.completed = true;
        player.closeInventory();
        if (!reopenSettings) return;
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) return;
            try { openSettingsMenu(player); }
            catch (SQLException e) {
                getLogger().warning("No se pudo volver a Ajustes después de editar banner: " + e.getMessage());
            }
        });
    }

    private void confirmBannerEditor(Player player, BannerEditorHolder holder) {
        try {
            Member member = requireMember(player);
            if (member == null) return;
            if (member.clanId() != holder.clanId) {
                msg(player, "&cYa no perteneces al clan que abrió este editor.");
                returnSubmittedBanner(player, holder);
                finishBannerEditor(player, holder, false);
                return;
            }
            if (!hasRank(player, member, "banner-set")) return;
            if (holder.submittedBanner == null) {
                if (holder.originalBannerData == null || holder.originalBannerData.isBlank()) {
                    msg(player, "&cColoca un estandarte en el espacio central antes de confirmar.");
                    return;
                }
                msg(player, "&7El estandarte del clan no fue modificado.");
                finishBannerEditor(player, holder, true);
                return;
            }

            ItemStack selected = singleItem(holder.submittedBanner);
            setClanBanner(holder.clanId, itemToBase64(selected));
            holder.submittedBanner = null;
            logAction(holder.clanId, player, "ESTANDARTE", "Estandarte oficial actualizado mediante la interfaz");
            broadcastToClan(holder.clanId, "&aEl estandarte oficial del clan fue actualizado por &e" + player.getName() + "&a.");
            finishBannerEditor(player, holder, true);
        } catch (SQLException | IOException e) {
            getLogger().warning("No se pudo confirmar el estandarte del clan: " + e.getMessage());
            msg(player, "&cNo se pudo guardar el estandarte.");
        }
    }

    private void cancelBannerEditor(Player player, BannerEditorHolder holder) {
        returnSubmittedBanner(player, holder);
        msg(player, "&7Cambio de estandarte cancelado.");
        finishBannerEditor(player, holder, true);
    }

    private void handleLogs(Player player, String[] args) throws SQLException {
        Member member = requireMember(player);
        if (member == null) return;
        if (!hasRank(player, member, "logs-view")) return;
        String filter = "all";
        int page = 1;
        if (args.length >= 2) {
            if (args[1].matches("\\d+")) {
                page = Math.max(1, Integer.parseInt(args[1]));
            } else {
                filter = normalizeLogFilter(args[1]);
                if (args.length >= 3) {
                    try { page = Math.max(1, Integer.parseInt(args[2])); } catch (NumberFormatException ignored) {}
                }
            }
        }
        showLogs(player, member.clanId(), page, filter);
    }

    private void showLogs(Player player, int clanId, int page, String filter) throws SQLException {
        int pageSize = 8;
        String normalized = normalizeLogFilter(filter);
        List<ClanLog> logs = getLogs(clanId, normalized, page, pageSize);
        player.sendMessage(color("&8&m-----&r &6Registro de clan &7(" + logFilterDisplay(normalized) + " / " + page + ") &8&m-----"));
        if (logs.isEmpty()) {
            player.sendMessage(color("&7No hay registros para mostrar."));
            return;
        }
        for (ClanLog log : logs) {
            player.sendMessage(color("&8- &7#" + log.id() + " &e" + log.action() + " &7| &f" + log.actorName() + " &8» &7" + log.detail()));
        }
    }

    private void handleTop(Player player, String[] args) throws SQLException {
        String mode = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "fuerza";
        if (!equalsAny(mode, "fuerza", "kills", "banco")) mode = "fuerza";
        List<ClanTopEntry> entries = new ArrayList<>();
        for (Clan clan : listClans()) {
            double value = switch (mode) {
                case "kills" -> getTotalKillsByClan(clan.id());
                case "banco" -> clan.bankBalance();
                default -> calculateStrength(clan);
            };
            entries.add(new ClanTopEntry(clan, value));
        }
        entries.sort(Comparator.comparingDouble(ClanTopEntry::value).reversed());
        player.sendMessage(color("&8&m-----&r &6Top de clanes &7(" + mode + ") &8&m-----"));
        int max = Math.min(10, entries.size());
        if (max == 0) {
            player.sendMessage(color("&7No hay clanes creados todavía."));
            return;
        }
        for (int i = 0; i < max; i++) {
            ClanTopEntry e = entries.get(i);
            player.sendMessage(color("&e#" + (i + 1) + " &8[&b" + e.clan().tag() + "&8] &f" + e.clan().name() + " &7- &6" + formatNumber(e.value())));
        }
    }

    private void handleKillStats(Player player, String[] args) throws SQLException {
        Optional<Clan> clanOpt = args.length >= 2 ? getClanByTag(args[1]) : getPlayerClan(player.getUniqueId());
        if (clanOpt.isEmpty()) {
            msg(player, "&cClan no encontrado.");
            return;
        }
        Clan clan = clanOpt.get();
        player.sendMessage(color("&8&m-----&r &6Bajas de &8[&b" + clan.tag() + "&8] &8&m-----"));
        player.sendMessage(color("&7Kills contra clanes: &a" + getTotalKillsByClan(clan.id())));
        player.sendMessage(color("&7Bajas sufridas: &c" + getTotalDeathsByClan(clan.id())));
        List<ClanTopEntry> suffered = getTopKillersAgainst(clan.id(), 8);
        if (suffered.isEmpty()) {
            player.sendMessage(color("&7Ningún clan ha registrado bajas contra este clan."));
            return;
        }
        player.sendMessage(color("&7Clanes que más bajas le hicieron:"));
        for (ClanTopEntry e : suffered) {
            player.sendMessage(color("&8- &8[&c" + e.clan().tag() + "&8] &f" + e.clan().name() + " &7» &c" + (int) e.value() + " bajas"));
        }
    }


    private void handleEditClan(Player player, String[] args) throws SQLException {
        Member member = requireMember(player);
        if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        if (args.length < 2) {
            msg(player, "&cUso: &e/clan editar <nombre|id> <valor>");
            return;
        }
        String mode = args[1].toLowerCase(Locale.ROOT);
        if (equalsAny(mode, "nombre", "name")) {
            if (!hasRank(player, member, "rename-clan")) return;
            if (args.length < 3) {
                pendingClanNameEdit.add(player.getUniqueId());
                player.closeInventory();
                msg(player, "&7Escribe el nuevo nombre del clan en el chat. &cCancelar &7para cancelar.");
                return;
            }
            String name = join(args, 2).trim();
            int min = getConfig().getInt("creation.name-min", 3), max = getConfig().getInt("creation.name-max", 19);
            if (name.length() < min || name.length() > max) { msg(player, "&cEl nombre debe tener entre &e" + min + " &cy &e" + max + " &ccaracteres."); return; }
            Optional<Clan> existing = getClanByName(name);
            if (existing.isPresent() && existing.get().id() != clan.id()) { msg(player, "&cYa existe un clan con ese nombre."); return; }
            setClanName(clan.id(), name);
            logAction(clan.id(), player, "AJUSTES", "Cambió nombre a " + name);
            broadcastToClan(clan.id(), "&aEl clan ahora se llama &f" + name + "&a.");
            return;
        }
        if (equalsAny(mode, "id", "tag")) {
            if (!hasRank(player, member, "rename-tag")) return;
            if (args.length < 3) {
                pendingClanTagEdit.add(player.getUniqueId());
                player.closeInventory();
                msg(player, "&7Escribe el nuevo ID del clan en el chat. &cCancelar &7para cancelar.");
                return;
            }
            String tag = normalizeTag(args[2]);
            String validation = validateClanIdentity(tag, clan.name());
            if (validation != null) { msg(player, validation); return; }
            Optional<Clan> existing = getClanByTag(tag);
            if (existing.isPresent() && existing.get().id() != clan.id()) { msg(player, "&cYa existe un clan con ese ID."); return; }
            setClanTag(clan.id(), tag);
            logAction(clan.id(), player, "AJUSTES", "Cambió ID a " + tag);
            broadcastToClan(clan.id(), "&aEl clan ahora usa el ID &8[&b" + tag + "&8]&a.");
            return;
        }
        msg(player, "&cUso: &e/clan editar <nombre|id> <valor>");
    }

    private void handleJoinRequests(Player player, String[] args) throws SQLException {
        Member member = requireMember(player);
        if (member == null) return;
        if (args.length == 1 || equalsAny(args[1], "menu", "ver", "lista")) {
            openJoinRequestsMenu(player, 1);
            return;
        }
        if (!hasRank(player, member, "join-requests")) return;
        if (args.length < 3) {
            msg(player, "&cUso: &e/clan solicitudes <aceptar|borrar> <jugador>");
            return;
        }
        Optional<ClanJoinRequest> reqOpt = getJoinRequestByName(member.clanId(), args[2]);
        if (reqOpt.isEmpty()) { msg(player, "&cNo hay solicitud de ese jugador."); return; }
        ClanJoinRequest req = reqOpt.get();
        if (equalsAny(args[1], "aceptar", "accept")) acceptJoinRequest(player, member.clanId(), req);
        else if (equalsAny(args[1], "borrar", "eliminar", "rechazar")) { deleteJoinRequest(member.clanId(), req.uuid()); msg(player, "&cSolicitud eliminada."); }
        else msg(player, "&cUso: &e/clan solicitudes <aceptar|borrar> <jugador>");
    }

    private void handleDisband(Player player, String[] args) throws SQLException {
        Member member = requireMember(player);
        if (member == null) return;
        if (!hasRank(player, member, "disband")) return;
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirmar")) {
            msg(player, "&cPara disolver tu clan usa: &e/clan disolver confirmar");
            return;
        }
        Clan clan = getClan(member.clanId()).orElseThrow();
        logAction(clan.id(), player, "DISOLVER", "Clan disuelto");
        disbandClan(clan.id());
        msg(player, "&cClan &e" + clan.tag() + " &cdisuelto.");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFriendlyFire(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player damager = getDamagingPlayer(event.getDamager());
        if (damager == null || damager.equals(victim)) return;
        try {
            Optional<Member> d = getMember(damager.getUniqueId());
            Optional<Member> v = getMember(victim.getUniqueId());
            if (d.isEmpty() || v.isEmpty()) return;
            if (d.get().clanId() == v.get().clanId() && getConfig().getBoolean("friendly-fire.cancel-members", true)) {
                event.setCancelled(true);
                sendFriendlyFireMessage(damager, "&cNo puedes dañar a miembros de tu clan.");
                return;
            }
            if (getConfig().getBoolean("friendly-fire.cancel-allies", false) && areAllies(d.get().clanId(), v.get().clanId())) {
                event.setCancelled(true);
                sendFriendlyFireMessage(damager, "&cNo puedes dañar a clanes aliados.");
            }
        } catch (SQLException e) {
            getLogger().warning("Error revisando friendly fire: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!getConfig().getBoolean("kills.enabled", true)) return;
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        try {
            Optional<Member> killerMember = getMember(killer.getUniqueId());
            Optional<Member> victimMember = getMember(victim.getUniqueId());
            if (killerMember.isEmpty() || victimMember.isEmpty()) return;
            if (killerMember.get().clanId() == victimMember.get().clanId()) return;
            long cooldownMs = getConfig().getLong("kills.same-victim-cooldown-seconds", 900) * 1000L;
            if (isKillOnCooldown(killer.getUniqueId(), victim.getUniqueId(), cooldownMs)) return;
            setKillCooldown(killer.getUniqueId(), victim.getUniqueId());
            incrementClanKill(killerMember.get().clanId(), victimMember.get().clanId());
            Clan killerClan = getClan(killerMember.get().clanId()).orElseThrow();
            Clan victimClan = getClan(victimMember.get().clanId()).orElseThrow();
            logAction(killerClan.id(), killer, "KILL", "Baja contra " + victimClan.tag());
            logAction(victimClan.id(), victim, "MUERTE", "Baja sufrida por " + killerClan.tag());
        } catch (SQLException e) {
            getLogger().warning("Error registrando kill de clan: " + e.getMessage());
        }
    }


    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getInventory().getHolder() instanceof BannerEditorHolder bannerHolder) {
            handleBannerEditorClick(event, player, bannerHolder);
            return;
        }
        if (event.getInventory().getHolder() instanceof BannerViewHolder bannerHolder) {
            int rawSlot = event.getRawSlot();
            int topSize = event.getView().getTopInventory().getSize();
            boolean clickedTop = rawSlot >= 0 && rawSlot < topSize;

            // La vista funciona como dispensador seguro: nunca se mueve la vista previa,
            // pero cada clic en el estandarte entrega una copia limpia e ilimitada.
            if (clickedTop || event.isShiftClick() || event.getClick() == ClickType.DOUBLE_CLICK) {
                event.setCancelled(true);
            }
            if (clickedTop && event.getSlot() == BANNER_EDITOR_INPUT_SLOT) {
                long cooldownMillis = Math.max(0L, getConfig().getLong("banners.copy-cooldown-seconds", 10L)) * 1000L;
                long now = System.currentTimeMillis();
                long availableAt = bannerCopyCooldowns.getOrDefault(player.getUniqueId(), 0L);
                if (cooldownMillis > 0L && now < availableAt) {
                    long remaining = Math.max(1L, (availableAt - now + 999L) / 1000L);
                    String message = getConfig().getString("banners.copy-cooldown-message", "&cDebes esperar &e{seconds}s &cantes de sacar otro estandarte.");
                    msg(player, message.replace("{seconds}", String.valueOf(remaining)));
                    return;
                }
                giveItemSafely(player, bannerHolder.newCopy());
                if (cooldownMillis > 0L) bannerCopyCooldowns.put(player.getUniqueId(), now + cooldownMillis);
                player.updateInventory();
            }
            return;
        }
        if (event.getInventory().getHolder() instanceof StorageInventoryHolder storageHolder) {
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;
            if (event.getSlot() != STORAGE_NAV_SLOT || storagePagesForSlots(storageHolder.totalSlots()) <= 1) return;
            event.setCancelled(true);
            try {
                saveStoragePage(storageHolder.clanId(), event.getInventory(), storageHolder.page(), storageHolder.totalSlots());
                int pages = storagePagesForSlots(storageHolder.totalSlots());
                int nextPage = event.getClick().isRightClick() ? storageHolder.page() - 1 : storageHolder.page() + 1;
                if (nextPage < 1) nextPage = pages;
                if (nextPage > pages) nextPage = 1;
                openClanStorage(player, nextPage);
            } catch (Exception e) {
                getLogger().warning("Error navegando almacén de clan: " + e.getMessage());
                msg(player, "&cNo se pudo cambiar de página del almacén.");
            }
            return;
        }
        if (!(event.getInventory().getHolder() instanceof ClanMenuHolder holder)) return;
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;
        event.setCancelled(true);
        try {
            handleMenuClick(player, holder, event.getSlot(), event.getClick());
        } catch (SQLException e) {
            getLogger().warning("Error en GUI de clan: " + e.getMessage());
            msg(player, "&cOcurrió un error usando el menú de clan.");
        }
    }

    private void handleBannerEditorClick(InventoryClickEvent event, Player player, BannerEditorHolder holder) {
        Inventory top = event.getView().getTopInventory();
        int rawSlot = event.getRawSlot();
        int topSize = top.getSize();

        if (event.getClick() == ClickType.DOUBLE_CLICK) {
            event.setCancelled(true);
            return;
        }

        if (rawSlot >= 0 && rawSlot < topSize) {
            event.setCancelled(true);
            int slot = event.getSlot();
            if (slot == bannerEditorCancelSlot()) {
                cancelBannerEditor(player, holder);
                return;
            }
            if (slot == bannerEditorConfirmSlot()) {
                confirmBannerEditor(player, holder);
                return;
            }
            if (slot != bannerEditorInputSlot()) return;

            ItemStack cursor = event.getCursor();
            if (cursor == null || cursor.getType() == Material.AIR) {
                removeSubmittedBannerToCursor(player, top, holder);
                return;
            }
            if (!isBanner(cursor)) {
                msg(player, "&cEse espacio solo acepta estandartes.");
                return;
            }

            placeBannerInEditor(player, top, holder, cursor);
            ItemStack remainder = cursor.clone();
            remainder.setAmount(cursor.getAmount() - 1);
            player.setItemOnCursor(remainder.getAmount() <= 0 ? null : remainder);
            player.updateInventory();
            return;
        }

        if (event.isShiftClick()) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (!isBanner(clicked)) {
                if (clicked != null && clicked.getType() != Material.AIR) msg(player, "&cEl editor solo acepta estandartes.");
                return;
            }
            placeBannerInEditor(player, top, holder, clicked);
            ItemStack remainder = clicked.clone();
            remainder.setAmount(clicked.getAmount() - 1);
            event.setCurrentItem(remainder.getAmount() <= 0 ? null : remainder);
            player.updateInventory();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof BannerEditorHolder) && !(holder instanceof BannerViewHolder)) return;
        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (event.getInventory().getHolder() instanceof BannerEditorHolder bannerHolder) {
            if (!bannerHolder.completed) {
                returnSubmittedBanner(player, bannerHolder);
                bannerHolder.completed = true;
            }
            return;
        }
        if (!(event.getInventory().getHolder() instanceof StorageInventoryHolder holder)) return;
        try {
            saveStoragePage(holder.clanId(), event.getInventory(), holder.page(), holder.totalSlots());
            logAction(holder.clanId(), player, "ALMACEN", "Cerró y guardó el almacén");
        } catch (Exception e) {
            getLogger().warning("No se pudo guardar almacén de clan " + holder.clanId() + ": " + e.getMessage());
            msg(player, "&cNo se pudo guardar el almacén del clan. Avisa a un admin.");
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Boolean bankDeposit = pendingBankAmount.remove(player.getUniqueId());
        if (bankDeposit != null) {
            event.setCancelled(true);
            String text = event.getMessage().trim();
            if (text.equalsIgnoreCase("cancelar")) {
                msg(player, "&7Operación de banco cancelada.");
                return;
            }
            Bukkit.getScheduler().runTask(this, () -> {
                try { handleBank(player, new String[]{"banco", bankDeposit ? "depositar" : "retirar", text}); }
                catch (SQLException e) { getLogger().warning("Error procesando banco desde chat: " + e.getMessage()); }
            });
            return;
        }
        if (pendingClanCreateName.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            String text = event.getMessage().trim();
            String storedName = pendingClanCreateName.get(player.getUniqueId());
            if (text.equalsIgnoreCase("cancelar")) {
                pendingClanCreateName.remove(player.getUniqueId());
                msg(player, "&7Creación de clan cancelada.");
                return;
            }

            if (storedName == null || storedName.isBlank()) {
                String name = text.trim();
                int min = getConfig().getInt("creation.name-min", 3), max = getConfig().getInt("creation.name-max", 19);
                if (name.length() < min || name.length() > max) {
                    msg(player, "&cEl nombre debe tener entre &e" + min + " &cy &e" + max + " &ccaracteres. Escribe otro nombre o &4cancelar&c.");
                    return;
                }
                Bukkit.getScheduler().runTask(this, () -> {
                    try {
                        if (getClanByName(name).isPresent()) {
                            msg(player, "&cYa existe un clan con ese nombre. Escribe otro nombre o &4cancelar&c.");
                            return;
                        }
                        pendingClanCreateName.put(player.getUniqueId(), name);
                        msg(player, "&aNombre guardado: &f" + name);
                        msg(player, msgConfig("creation.chat-id-prompt", "&7Ahora escribe el &eID/tag &7del clan. Ejemplo: &bMDV&7. Escribe &ccancelar &7para cancelar."));
                    } catch (SQLException e) {
                        pendingClanCreateName.remove(player.getUniqueId());
                        getLogger().warning("Error validando nombre de clan desde chat: " + e.getMessage());
                        msg(player, "&cNo se pudo validar el nombre del clan.");
                    }
                });
                return;
            }

            String tag = text.trim();
            pendingClanCreateName.remove(player.getUniqueId());
            Bukkit.getScheduler().runTask(this, () -> {
                try { handleCreate(player, new String[]{"crear", tag, storedName}); }
                catch (SQLException e) { getLogger().warning("Error creando clan desde chat: " + e.getMessage()); }
            });
            return;
        }
        if (pendingBoardEdit.remove(player.getUniqueId())) {
            event.setCancelled(true);
            String text = event.getMessage().trim();
            if (text.equalsIgnoreCase("cancelar")) {
                msg(player, "&7Edición de tablero cancelada.");
                return;
            }
            Bukkit.getScheduler().runTask(this, () -> {
                try { handleBoard(player, new String[]{"tablero", "set", text}); }
                catch (SQLException e) { getLogger().warning("Error editando tablero desde chat: " + e.getMessage()); }
            });
            return;
        }
        if (pendingDescriptionEdit.remove(player.getUniqueId())) {
            event.setCancelled(true);
            String text = event.getMessage().trim();
            if (text.equalsIgnoreCase("cancelar")) {
                msg(player, "&7Edición de descripción cancelada.");
                return;
            }
            Bukkit.getScheduler().runTask(this, () -> {
                try { handleDescription(player, new String[]{"descripcion", "set", text}); }
                catch (SQLException e) { getLogger().warning("Error editando descripción desde chat: " + e.getMessage()); }
            });
            return;
        }
        if (pendingClanNameEdit.remove(player.getUniqueId())) {
            event.setCancelled(true);
            String text = event.getMessage().trim();
            if (text.equalsIgnoreCase("cancelar")) { msg(player, "&7Cambio de nombre cancelado."); return; }
            Bukkit.getScheduler().runTask(this, () -> {
                try { handleEditClan(player, new String[]{"editar", "nombre", text}); }
                catch (SQLException e) { getLogger().warning("Error cambiando nombre desde chat: " + e.getMessage()); }
            });
            return;
        }
        if (pendingClanTagEdit.remove(player.getUniqueId())) {
            event.setCancelled(true);
            String text = event.getMessage().trim();
            if (text.equalsIgnoreCase("cancelar")) { msg(player, "&7Cambio de ID cancelado."); return; }
            Bukkit.getScheduler().runTask(this, () -> {
                try { handleEditClan(player, new String[]{"editar", "id", text}); }
                catch (SQLException e) { getLogger().warning("Error cambiando ID desde chat: " + e.getMessage()); }
            });
            return;
        }
        Integer roleToRename = pendingRoleNameEdit.remove(player.getUniqueId());
        if (roleToRename != null) {
            event.setCancelled(true);
            String text = event.getMessage().trim();
            if (text.equalsIgnoreCase("cancelar")) { msg(player, "&7Cambio de rol cancelado."); return; }
            Bukkit.getScheduler().runTask(this, () -> {
                try { handleRoleName(player, new String[]{"rol", String.valueOf(roleToRename), text}); }
                catch (SQLException e) { getLogger().warning("Error cambiando rol desde chat: " + e.getMessage()); }
            });
            return;
        }
        String replyTarget = pendingClanMailReply.remove(player.getUniqueId());
        if (replyTarget != null) {
            event.setCancelled(true);
            String text = event.getMessage().trim();
            if (text.equalsIgnoreCase("cancelar")) { msg(player, "&7Correo de clan cancelado."); return; }
            Bukkit.getScheduler().runTask(this, () -> {
                try { handleClanMail(player, new String[]{"correo", "clan", replyTarget, text}); }
                catch (SQLException e) { getLogger().warning("Error enviando correo desde chat: " + e.getMessage()); }
            });
            return;
        }
        String personalMailTarget = pendingPersonalMail.remove(player.getUniqueId());
        if (personalMailTarget != null) {
            event.setCancelled(true);
            String text = event.getMessage().trim();
            if (text.equalsIgnoreCase("cancelar")) { msg(player, "&7Correo personal cancelado."); return; }
            Bukkit.getScheduler().runTask(this, () -> player.performCommand("correo enviar " + personalMailTarget + " " + text));
            return;
        }
        try {
            Optional<Member> memberOpt = getMember(player.getUniqueId());
            if (memberOpt.isEmpty() || !memberOpt.get().chatToggle()) return;
            event.setCancelled(true);
            String message = event.getMessage();
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    sendClanChat(player, message);
                } catch (SQLException e) {
                    getLogger().warning("Error enviando chat de clan: " + e.getMessage());
                }
            });
        } catch (SQLException e) {
            getLogger().warning("Error leyendo toggle de chat: " + e.getMessage());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!getConfig().getBoolean("base.cancel-on-move", true)) return;
        PendingTeleport pending = pendingTeleports.get(event.getPlayer().getUniqueId());
        if (pending == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
            cancelTeleport(event.getPlayer(), "&cTeletransporte cancelado por moverte.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!getConfig().getBoolean("base.cancel-on-damage", true)) return;
        if (event.getEntity() instanceof Player player && pendingTeleports.containsKey(player.getUniqueId())) {
            cancelTeleport(player, "&cTeletransporte cancelado por recibir daño.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
        if (holder instanceof BannerEditorHolder bannerHolder && !bannerHolder.completed) {
            returnSubmittedBanner(player, bannerHolder);
            bannerHolder.completed = true;
        }
        PendingTeleport pending = pendingTeleports.remove(player.getUniqueId());
        if (pending != null) Bukkit.getScheduler().cancelTask(pending.taskId());
    }

    private void sendClanChat(Player player, String message) throws SQLException {
        Member member = requireMember(player);
        if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        String format = getConfig().getString("chat.format", "&8[&3Clan&8] &b{player}&7: &f{message}")
                .replace("{player}", player.getName())
                .replace("{clan}", clan.name())
                .replace("{id}", clan.tag())
                .replace("{role}", getRoleName(clan.id(), member.role()))
                .replace("{message}", message);
        for (Member m : getMembers(clan.id())) {
            Player online = Bukkit.getPlayer(m.uuid());
            if (online != null) online.sendMessage(color(format));
        }
        String spyPerm = getConfig().getString("chat.spy-permission", "mdvclans.admin");
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission(spyPerm) && getMember(online.getUniqueId()).map(Member::clanId).orElse(-1) != clan.id()) {
                online.sendMessage(color("&8[&cSpy Clan " + clan.tag() + "&8] &7" + player.getName() + ": &f" + message));
            }
        }
        Bukkit.getConsoleSender().sendMessage(color("&8[&3Clan " + clan.tag() + "&8] &7" + player.getName() + ": &f" + message));
    }

    private void cancelTeleport(Player player, String reason) {
        PendingTeleport pending = pendingTeleports.remove(player.getUniqueId());
        if (pending == null) return;
        Bukkit.getScheduler().cancelTask(pending.taskId());
        msg(player, reason);
    }

    private Player getDamagingPlayer(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player p) return p;
        }
        return null;
    }

    private void sendFriendlyFireMessage(Player player, String message) {
        long now = System.currentTimeMillis();
        long cooldown = getConfig().getLong("friendly-fire.message-cooldown-seconds", 3) * 1000L;
        long last = friendlyFireMessageCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now - last >= cooldown) {
            friendlyFireMessageCooldowns.put(player.getUniqueId(), now);
            msg(player, message);
        }
    }

    private boolean isWorldAllowed(World world) {
        List<String> allowed = getConfig().getStringList("worlds.allowed");
        return allowed == null || allowed.isEmpty() || allowed.contains(world.getName());
    }

    private String validateClanIdentity(String tag, String name) {
        int idMin = getConfig().getInt("creation.id-min", 3);
        int idMax = getConfig().getInt("creation.id-max", 5);
        int nameMin = getConfig().getInt("creation.name-min", 3);
        int nameMax = getConfig().getInt("creation.name-max", 19);
        if (tag.length() < idMin || tag.length() > idMax) return "&cEl ID debe tener entre &e" + idMin + " &cy &e" + idMax + " &ccaracteres.";
        if (!idPattern.matcher(tag).matches()) return "&cEl ID solo puede usar letras y números.";
        if (name.length() < nameMin || name.length() > nameMax) return "&cEl nombre debe tener entre &e" + nameMin + " &cy &e" + nameMax + " &ccaracteres.";
        return null;
    }

    private boolean hasRank(Player player, Member member, String key) throws SQLException {
        if (player.hasPermission("mdvclans.admin") || can(member, key)) return true;
        int required = defaultRequiredRank(key);
        msg(player, "&cNo tienes permiso de clan para hacer eso. &7Por defecto requiere rango &e" + required + " &7o permiso custom.");
        return false;
    }

    private boolean can(Member member, String key) throws SQLException {
        return isClanRolePermissionAllowed(member.clanId(), key, member.role());
    }

    private boolean canModifyMember(Member actor, Member target, String key) throws SQLException {
        if (!can(actor, key)) return false;
        if (target.uuid().equals(actor.uuid())) return false;
        return target.role() < actor.role();
    }

    private Member requireMember(Player player) throws SQLException {
        Optional<Member> member = getMember(player.getUniqueId());
        if (member.isEmpty()) {
            msg(player, "&cNo perteneces a ningún clan.");
            return null;
        }
        return member.get();
    }

    private int minRole() { return Math.max(0, getConfig().getInt("roles.min", 0)); }
    private int maxRole() { return Math.min(4, Math.max(minRole(), getConfig().getInt("roles.max", 4))); }

    private String normalizeTag(String raw) {
        return ChatColor.stripColor(raw).replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private String join(String[] args, int start) {
        if (start >= args.length) return "";
        return String.join(" ", Arrays.copyOfRange(args, start, args.length));
    }

    private boolean parseBoolean(String value) {
        return equalsAny(value.toLowerCase(Locale.ROOT), "on", "true", "si", "sí", "abierto", "open", "1", "activar");
    }

    private boolean equalsAny(String value, String... options) {
        for (String option : options) if (value.equalsIgnoreCase(option)) return true;
        return false;
    }

    private void msg(Player player, String message) {
        player.sendMessage(color(msgConfig("prefix", getConfig().getString("prefix", "&8[&6MDVClans&8]&r ")) + message));
    }

    private String color(String text) {
        if (text == null) return "";
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private synchronized int countClans() throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM clans")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private synchronized Optional<Clan> getClan(int id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM clans WHERE id=?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readClan(rs)) : Optional.empty();
            }
        }
    }

    private synchronized Optional<Clan> getClanByTag(String tag) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM clans WHERE tag=? COLLATE NOCASE")) {
            ps.setString(1, normalizeTag(tag));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readClan(rs)) : Optional.empty();
            }
        }
    }

    private synchronized Optional<Clan> getClanByName(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM clans WHERE lower(name)=lower(?)")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readClan(rs)) : Optional.empty();
            }
        }
    }

    private synchronized List<Clan> listClans() throws SQLException {
        List<Clan> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM clans ORDER BY name ASC"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(readClan(rs));
        }
        return list;
    }

    private synchronized Optional<Member> getMember(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM members WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readMember(rs)) : Optional.empty();
            }
        }
    }

    private synchronized Optional<Member> getMemberByName(String name) throws SQLException {
        if (name == null || name.isBlank()) return Optional.empty();
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM members WHERE lower(name)=lower(?) LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readMember(rs)) : Optional.empty();
            }
        }
    }

    private Optional<Member> resolveMemberByNameOrUuid(String value) throws SQLException {
        if (value == null || value.isBlank()) return Optional.empty();
        String clean = value.trim();
        try {
            return getMember(UUID.fromString(clean));
        } catch (IllegalArgumentException ignored) {
            // Not a UUID, continue with player name lookup.
        }
        Player online = Bukkit.getPlayerExact(clean);
        if (online != null) {
            Optional<Member> byUuid = getMember(online.getUniqueId());
            if (byUuid.isPresent()) return byUuid;
        }
        return getMemberByName(clean);
    }

    private synchronized Optional<Member> getMemberByNameInClan(int clanId, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM members WHERE clan_id=? AND lower(name)=lower(?)")) {
            ps.setInt(1, clanId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readMember(rs)) : Optional.empty();
            }
        }
    }

    private synchronized Optional<Clan> getPlayerClan(UUID uuid) throws SQLException {
        Optional<Member> m = getMember(uuid);
        return m.isPresent() ? getClan(m.get().clanId()) : Optional.empty();
    }

    private synchronized int getOwnClanId(UUID uuid) throws SQLException {
        return getMember(uuid).map(Member::clanId).orElse(-1);
    }

    private synchronized List<Member> getMembers(int clanId) throws SQLException {
        List<Member> members = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM members WHERE clan_id=? ORDER BY role DESC, name ASC")) {
            ps.setInt(1, clanId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) members.add(readMember(rs));
            }
        }
        return members;
    }

    private synchronized int countMembers(int clanId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM members WHERE clan_id=?")) {
            ps.setInt(1, clanId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private void addMemberUnsafe(int clanId, UUID uuid, String name, int role, long joinedAt, int chatToggle) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO members(clan_id,uuid,name,role,joined_at,chat_toggle) VALUES(?,?,?,?,?,?)")) {
            ps.setInt(1, clanId);
            ps.setString(2, uuid.toString());
            ps.setString(3, name);
            ps.setInt(4, role);
            ps.setLong(5, joinedAt);
            ps.setInt(6, chatToggle);
            ps.executeUpdate();
        }
    }

    private synchronized void removeMember(UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM members WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    private synchronized void setMemberRole(UUID uuid, int role) throws SQLException {
        if (role >= maxRole()) {
            Optional<Member> target = getMember(uuid);
            if (target.isPresent()) {
                transferLeadershipUnsafe(target.get().clanId(), uuid);
                return;
            }
        }
        try (PreparedStatement ps = connection.prepareStatement("UPDATE members SET role=? WHERE uuid=?")) {
            ps.setInt(1, role);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    private synchronized void transferLeadership(int clanId, UUID newLeaderUuid) throws SQLException {
        if (maxRole() <= minRole()) throw new SQLException("roles.max debe ser mayor que roles.min para transferir liderazgo");
        transferLeadershipUnsafe(clanId, newLeaderUuid);
    }

    private synchronized void transferLeadershipUnsafe(int clanId, UUID newLeaderUuid) throws SQLException {
        int demotedRole = Math.max(minRole(), maxRole() - 1);
        try (PreparedStatement ps = connection.prepareStatement("UPDATE members SET role=? WHERE clan_id=? AND role>=? AND uuid<>?")) {
            ps.setInt(1, demotedRole);
            ps.setInt(2, clanId);
            ps.setInt(3, maxRole());
            ps.setString(4, newLeaderUuid.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("UPDATE members SET role=? WHERE clan_id=? AND uuid=?")) {
            ps.setInt(1, maxRole());
            ps.setInt(2, clanId);
            ps.setString(3, newLeaderUuid.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("UPDATE clans SET owner_uuid=? WHERE id=?")) {
            ps.setString(1, newLeaderUuid.toString());
            ps.setInt(2, clanId);
            ps.executeUpdate();
        }
    }

    private synchronized void setChatToggle(UUID uuid, boolean toggle) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE members SET chat_toggle=? WHERE uuid=?")) {
            ps.setInt(1, toggle ? 1 : 0);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    private synchronized void setClanOpen(int clanId, boolean open) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE clans SET open=? WHERE id=?")) {
            ps.setInt(1, open ? 1 : 0);
            ps.setInt(2, clanId);
            ps.executeUpdate();
        }
    }

    private synchronized void setClanBase(int clanId, int baseNumber, Location loc) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO clan_bases(clan_id,base_number,world,x,y,z,yaw,pitch) VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setInt(1, clanId);
            ps.setInt(2, baseNumber);
            ps.setString(3, loc.getWorld().getName());
            ps.setDouble(4, loc.getX());
            ps.setDouble(5, loc.getY());
            ps.setDouble(6, loc.getZ());
            ps.setFloat(7, loc.getYaw());
            ps.setFloat(8, loc.getPitch());
            ps.executeUpdate();
        }
        if (baseNumber == 1) {
            try (PreparedStatement ps = connection.prepareStatement("UPDATE clans SET base_world=?,base_x=?,base_y=?,base_z=?,base_yaw=?,base_pitch=? WHERE id=?")) {
                ps.setString(1, loc.getWorld().getName());
                ps.setDouble(2, loc.getX());
                ps.setDouble(3, loc.getY());
                ps.setDouble(4, loc.getZ());
                ps.setFloat(5, loc.getYaw());
                ps.setFloat(6, loc.getPitch());
                ps.setInt(7, clanId);
                ps.executeUpdate();
            }
        }
    }

    private synchronized Optional<ClanBase> getClanBase(int clanId, int baseNumber) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM clan_bases WHERE clan_id=? AND base_number=?")) {
            ps.setInt(1, clanId);
            ps.setInt(2, baseNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(new ClanBase(
                        rs.getInt("clan_id"),
                        rs.getInt("base_number"),
                        rs.getString("world"),
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        rs.getDouble("z"),
                        rs.getFloat("yaw"),
                        rs.getFloat("pitch")
                ));
            }
        }
        return Optional.empty();
    }

    private synchronized int countClanBases(int clanId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM clan_bases WHERE clan_id=?")) {
            ps.setInt(1, clanId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private synchronized int clearAllClanBases() throws SQLException {
        int affected;
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM clan_bases")) {
            affected = ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement("UPDATE clans SET base_world=NULL,base_x=0,base_y=0,base_z=0,base_yaw=0,base_pitch=0 WHERE base_world IS NOT NULL AND base_world<>''")) {
            affected += ps.executeUpdate();
        }
        return affected;
    }

    private synchronized boolean isMetaFlagSet(String key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT meta_value FROM mdvclans_meta WHERE meta_key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "true".equalsIgnoreCase(rs.getString("meta_value"));
            }
        }
    }

    private synchronized void setMetaFlag(String key, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO mdvclans_meta(meta_key,meta_value) VALUES(?,?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private synchronized void migrateRolesToV19() throws SQLException {
        if (isMetaFlagSet("roles-migrated-1.9")) return;
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("UPDATE members SET role=CASE " +
                    "WHEN role>=5 THEN 4 " +
                    "WHEN role=4 THEN 3 " +
                    "WHEN role=3 THEN 2 " +
                    "WHEN role=2 THEN 1 " +
                    "ELSE 0 END");
            st.executeUpdate("UPDATE members SET role=4 WHERE uuid IN (" +
                    "SELECT owner_uuid FROM clans c WHERE NOT EXISTS (SELECT 1 FROM members m2 WHERE m2.clan_id=c.id AND m2.role=4))");
            st.executeUpdate("CREATE TEMP TABLE role_names_v19(clan_id INTEGER NOT NULL, role INTEGER NOT NULL, name TEXT NOT NULL, PRIMARY KEY(clan_id, role))");
            st.executeUpdate("INSERT OR REPLACE INTO role_names_v19 SELECT clan_id,0,name FROM role_names WHERE role=0");
            st.executeUpdate("INSERT OR REPLACE INTO role_names_v19 SELECT clan_id,1,name FROM role_names WHERE role=2");
            st.executeUpdate("INSERT OR REPLACE INTO role_names_v19 SELECT clan_id,2,name FROM role_names WHERE role=3");
            st.executeUpdate("INSERT OR REPLACE INTO role_names_v19 SELECT clan_id,3,name FROM role_names WHERE role=4");
            st.executeUpdate("INSERT OR REPLACE INTO role_names_v19 SELECT clan_id,4,name FROM role_names WHERE role>=5");
            st.executeUpdate("DELETE FROM role_names");
            st.executeUpdate("INSERT OR REPLACE INTO role_names(clan_id,role,name) SELECT clan_id,role,name FROM role_names_v19");
            st.executeUpdate("DROP TABLE role_names_v19");
            st.executeUpdate("DELETE FROM clan_role_permissions WHERE role<0 OR role>4");
        }
        setMetaFlag("roles-migrated-1.9", "true");
    }

    private synchronized void migrateBasesToV110() throws SQLException {
        if (isMetaFlagSet("bases-migrated-1.10")) return;
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("INSERT OR IGNORE INTO clan_bases(clan_id,base_number,world,x,y,z,yaw,pitch) " +
                    "SELECT id,1,base_world,base_x,base_y,base_z,base_yaw,base_pitch FROM clans WHERE base_world IS NOT NULL AND base_world<>''");
        }
        setMetaFlag("bases-migrated-1.10", "true");
    }

    private synchronized void ensureRoleNamesForAllClans() throws SQLException {
        List<Integer> clanIds = new ArrayList<>();
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT id FROM clans")) {
            while (rs.next()) clanIds.add(rs.getInt("id"));
        }
        for (int clanId : clanIds) {
            for (int role = minRole(); role <= maxRole(); role++) {
                try (PreparedStatement ps = connection.prepareStatement("INSERT OR IGNORE INTO role_names(clan_id,role,name) VALUES(?,?,?)")) {
                    ps.setInt(1, clanId);
                    ps.setInt(2, role);
                    ps.setString(3, getConfig().getString("roles.defaults." + role, "Rango " + role));
                    ps.executeUpdate();
                }
            }
        }
    }

    private synchronized void ensureSingleLeaderForAllClans() throws SQLException {
        List<Clan> clans = listClans();
        for (Clan clan : clans) {
            List<Member> members = getMembers(clan.id());
            if (members.isEmpty()) continue;
            Member chosen = null;
            for (Member member : members) {
                if (member.uuid().equals(clan.ownerUuid())) {
                    chosen = member;
                    break;
                }
            }
            if (chosen == null) {
                chosen = members.stream()
                        .sorted(Comparator.comparingInt(Member::role).reversed().thenComparingLong(Member::joinedAt))
                        .findFirst()
                        .orElse(members.get(0));
            }
            transferLeadershipUnsafe(clan.id(), chosen.uuid());
        }
    }

    private synchronized void ensureSingleLeaderIndex() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_members_single_leader ON members(clan_id) WHERE role >= 4");
        } catch (SQLException e) {
            getLogger().warning("No se pudo crear índice de líder único. Se reintentará tras reparar liderazgos: " + e.getMessage());
            ensureSingleLeaderForAllClans();
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_members_single_leader ON members(clan_id) WHERE role >= 4");
            }
        }
    }

    private String normalizePermissionKey(String key) {
        if (key == null) return "";
        return key.trim().toLowerCase(Locale.ROOT);
    }

    private int defaultRequiredRank(String key) {
        int required = getConfig().getInt("rank-permissions." + normalizePermissionKey(key), maxRole());
        return Math.max(minRole(), Math.min(maxRole(), required));
    }

    private boolean defaultPermissionAllowed(String key, int role) {
        int normalizedRole = Math.max(minRole(), Math.min(maxRole(), role));
        return normalizedRole >= defaultRequiredRank(key);
    }

    private synchronized boolean isClanRolePermissionAllowed(int clanId, String key, int role) throws SQLException {
        String permissionKey = normalizePermissionKey(key);
        int normalizedRole = Math.max(minRole(), Math.min(maxRole(), role));
        if (normalizedRole >= maxRole()) return true;
        try (PreparedStatement ps = connection.prepareStatement("SELECT allowed FROM clan_role_permissions WHERE clan_id=? AND permission_key=? AND role=?")) {
            ps.setInt(1, clanId);
            ps.setString(2, permissionKey);
            ps.setInt(3, normalizedRole);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("allowed") != 0;
            }
        }
        return defaultPermissionAllowed(permissionKey, normalizedRole);
    }

    private synchronized void setClanRolePermission(int clanId, String key, int role, boolean allowed) throws SQLException {
        String permissionKey = normalizePermissionKey(key);
        int normalizedRole = Math.max(minRole(), Math.min(maxRole(), role));
        if (normalizedRole >= maxRole()) return;
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO clan_role_permissions(clan_id,permission_key,role,allowed) VALUES(?,?,?,?)")) {
            ps.setInt(1, clanId);
            ps.setString(2, permissionKey);
            ps.setInt(3, normalizedRole);
            ps.setInt(4, allowed ? 1 : 0);
            ps.executeUpdate();
        }
    }

    private synchronized void resetClanRolePermissions(int clanId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM clan_role_permissions WHERE clan_id=?")) {
            ps.setInt(1, clanId);
            ps.executeUpdate();
        }
    }

    private synchronized void seedDefaultRoleNames(int clanId) throws SQLException {
        for (int i = minRole(); i <= maxRole(); i++) {
            setRoleName(clanId, i, getConfig().getString("roles.defaults." + i, "Rango " + i));
        }
    }

    private synchronized String getRoleName(int clanId, int role) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT name FROM role_names WHERE clan_id=? AND role=?")) {
            ps.setInt(1, clanId);
            ps.setInt(2, role);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("name");
            }
        }
        return getConfig().getString("roles.defaults." + role, "Rango " + role);
    }

    private synchronized void setRoleName(int clanId, int role, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO role_names(clan_id,role,name) VALUES(?,?,?)")) {
            ps.setInt(1, clanId);
            ps.setInt(2, role);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    private synchronized void removeInvite(int clanId, UUID target) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM invites WHERE clan_id=? AND target_uuid=?")) {
            ps.setInt(1, clanId);
            ps.setString(2, target.toString());
            ps.executeUpdate();
        }
    }

    private synchronized boolean hasInvite(int clanId, UUID target) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM invites WHERE clan_id=? AND target_uuid=? AND expires_at>?")) {
            ps.setInt(1, clanId);
            ps.setString(2, target.toString());
            ps.setLong(3, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private synchronized Optional<Clan> getLatestInvite(UUID target) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT c.* FROM invites i JOIN clans c ON c.id=i.clan_id WHERE i.target_uuid=? AND i.expires_at>? ORDER BY i.expires_at DESC LIMIT 1")) {
            ps.setString(1, target.toString());
            ps.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readClan(rs)) : Optional.empty();
            }
        }
    }

    private synchronized void cleanupExpiredInvites() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM invites WHERE expires_at<?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private synchronized void setRelation(int clanId, int targetId, String relation) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO relations(clan_id,target_clan_id,relation,created_at) VALUES(?,?,?,?)")) {
            ps.setInt(1, clanId);
            ps.setInt(2, targetId);
            ps.setString(3, relation);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
        requestNametagSync(2L);
    }

    private synchronized String getRelation(int clanId, int targetId) throws SQLException {
        if (clanId <= 0 || targetId <= 0 || clanId == targetId) return "NEUTRAL";
        try (PreparedStatement ps = connection.prepareStatement("SELECT relation FROM relations WHERE clan_id=? AND target_clan_id=?")) {
            ps.setInt(1, clanId);
            ps.setInt(2, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("relation") : "NEUTRAL";
            }
        }
    }

    private synchronized String getRelationBetween(int viewerClanId, int targetClanId) throws SQLException {
        if (viewerClanId <= 0 || targetClanId <= 0) return REL_NEUTRAL;
        if (viewerClanId == targetClanId) return "SAME";

        String forward = getRelation(viewerClanId, targetClanId);
        String reverse = getRelation(targetClanId, viewerClanId);

        // Enemigo es simétrico para nametags: si cualquiera de los dos clanes marcó enemigo,
        // ambos jugadores se ven rojos. Esto evita estados raros tras muerte/respawn.
        if (REL_ENEMY.equals(forward) || REL_ENEMY.equals(reverse)) return REL_ENEMY;

        // Aliado sí requiere acuerdo mutuo.
        if (REL_ALLY.equals(forward) && REL_ALLY.equals(reverse)) return REL_ALLY;

        return REL_NEUTRAL;
    }


    private synchronized boolean areAllies(int clanA, int clanB) throws SQLException {
        return REL_ALLY.equals(getRelation(clanA, clanB)) && REL_ALLY.equals(getRelation(clanB, clanA));
    }

    private synchronized void removeRelation(int clanId, int targetId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM relations WHERE clan_id=? AND target_clan_id=?")) {
            ps.setInt(1, clanId);
            ps.setInt(2, targetId);
            ps.executeUpdate();
        }
        requestNametagSync(2L);
    }

    private synchronized void removeRelation(int clanId, int targetId, String relation) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM relations WHERE clan_id=? AND target_clan_id=? AND relation=?")) {
            ps.setInt(1, clanId);
            ps.setInt(2, targetId);
            ps.setString(3, relation);
            ps.executeUpdate();
        }
    }


    private synchronized void setClanBoard(int clanId, String text) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE clans SET board_message=? WHERE id=?")) {
            ps.setString(1, text);
            ps.setInt(2, clanId);
            ps.executeUpdate();
        }
    }

    private synchronized void setClanDescription(int clanId, String text) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE clans SET description=? WHERE id=?")) {
            ps.setString(1, text);
            ps.setInt(2, clanId);
            ps.executeUpdate();
        }
    }

    private synchronized void createClanMail(int fromClanId, int toClanId, UUID senderUuid, String senderName, String message) throws SQLException {
        createClanMail(fromClanId, toClanId, senderUuid, senderName, message, "NORMAL", 0);
    }

    private synchronized void createClanMail(int fromClanId, int toClanId, UUID senderUuid, String senderName, String message, String mailType, int relationClanId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO clan_mails(from_clan_id,to_clan_id,sender_uuid,sender_name,sent_at,message,mail_type,relation_clan_id,deleted) VALUES(?,?,?,?,?,?,?,?,0)")) {
            ps.setInt(1, fromClanId);
            ps.setInt(2, toClanId);
            ps.setString(3, senderUuid == null ? null : senderUuid.toString());
            ps.setString(4, senderName == null ? "Sistema" : senderName);
            ps.setLong(5, System.currentTimeMillis());
            ps.setString(6, message);
            ps.setString(7, mailType == null ? "NORMAL" : mailType);
            ps.setInt(8, relationClanId);
            ps.executeUpdate();
        }
    }

    private synchronized List<ClanMail> getClanMails(int clanId, int page, int pageSize) throws SQLException {
        List<ClanMail> list = new ArrayList<>();
        int offset = Math.max(0, page - 1) * pageSize;
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM clan_mails WHERE to_clan_id=? AND deleted=0 ORDER BY sent_at DESC LIMIT ? OFFSET ?")) {
            ps.setInt(1, clanId);
            ps.setInt(2, pageSize);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(new ClanMail(rs.getInt("id"), rs.getInt("from_clan_id"), rs.getInt("to_clan_id"), rs.getString("sender_name"), rs.getLong("sent_at"), rs.getString("message"), rs.getString("mail_type"), rs.getInt("relation_clan_id")));
            }
        }
        return list;
    }

    private synchronized int countClanMails(int clanId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM clan_mails WHERE to_clan_id=? AND deleted=0")) {
            ps.setInt(1, clanId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    private synchronized boolean deleteClanMail(int clanId, int mailId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE clan_mails SET deleted=1 WHERE id=? AND to_clan_id=?")) {
            ps.setInt(1, mailId);
            ps.setInt(2, clanId);
            return ps.executeUpdate() > 0;
        }
    }

    private synchronized void cleanupAllianceMails(int clanA, int clanB) throws SQLException {
        cleanupRelationMails(clanA, clanB, MAIL_ALLY_REQUEST);
    }

    private synchronized void cleanupNeutralityMails(int clanA, int clanB) throws SQLException {
        cleanupRelationMails(clanA, clanB, MAIL_NEUTRAL_REQUEST);
    }

    private synchronized void cleanupAllRelationRequestMails(int clanA, int clanB) throws SQLException {
        cleanupRelationMails(clanA, clanB, MAIL_ALLY_REQUEST);
        cleanupRelationMails(clanA, clanB, MAIL_NEUTRAL_REQUEST);
    }

    private synchronized void cleanupRelationMails(int clanA, int clanB, String mailType) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE clan_mails SET deleted=1 WHERE mail_type=? AND ((to_clan_id=? AND relation_clan_id=?) OR (to_clan_id=? AND relation_clan_id=?))")) {
            ps.setString(1, mailType);
            ps.setInt(2, clanA);
            ps.setInt(3, clanB);
            ps.setInt(4, clanB);
            ps.setInt(5, clanA);
            ps.executeUpdate();
        }
    }

    private synchronized boolean hasPendingRelationMail(int toClanId, int fromClanId, String mailType) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM clan_mails WHERE to_clan_id=? AND relation_clan_id=? AND mail_type=? AND deleted=0 LIMIT 1")) {
            ps.setInt(1, toClanId);
            ps.setInt(2, fromClanId);
            ps.setString(3, mailType);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private synchronized List<ClanRelationView> getVisibleRelations(int clanId) throws SQLException {
        List<ClanRelationView> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT target_clan_id, relation FROM relations WHERE clan_id=? AND relation<>? ORDER BY relation ASC")) {
            ps.setInt(1, clanId);
            ps.setString(2, REL_NEUTRAL);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String rel = rs.getString("relation");
                    if (!REL_ALLY.equals(rel) && !REL_ENEMY.equals(rel) && !REL_ALLY_REQUEST.equals(rel)) continue;
                    Optional<Clan> c = getClan(rs.getInt("target_clan_id"));
                    c.ifPresent(clan -> list.add(new ClanRelationView(clan, rel)));
                }
            }
        }
        return list;
    }

    private synchronized List<ClanTopEntry> topEntries(String mode) throws SQLException {
        List<ClanTopEntry> entries = new ArrayList<>();
        for (Clan clan : listClans()) {
            double value = switch (mode) {
                case "kills" -> getTotalKillsByClan(clan.id());
                case "banco" -> clan.bankBalance();
                default -> calculateStrength(clan);
            };
            entries.add(new ClanTopEntry(clan, value));
        }
        entries.sort(Comparator.comparingDouble(ClanTopEntry::value).reversed());
        return entries;
    }

    private synchronized void disbandClan(int clanId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM clans WHERE id=?")) {
            ps.setInt(1, clanId);
            ps.executeUpdate();
        }
    }



    private synchronized void addBankBalance(int clanId, double amount) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE clans SET bank_balance = bank_balance + ? WHERE id=?")) {
            ps.setDouble(1, amount);
            ps.setInt(2, clanId);
            ps.executeUpdate();
        }
    }

    private synchronized void setClanTier(int clanId, int tier) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE clans SET tier=? WHERE id=?")) {
            ps.setInt(1, tier);
            ps.setInt(2, clanId);
            ps.executeUpdate();
        }
    }

    private void handleTierUpgrade(Player player) throws SQLException {
        Member member = requireMember(player);
        if (member == null) return;
        if (!hasRank(player, member, "tier-upgrade")) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        int currentTier = Math.max(0, clan.tier());
        int maxTier = maxClanTier();
        if (currentTier >= maxTier) {
            msg(player, "&aTu clan ya alcanzó el tier máximo: &d" + tierName(currentTier) + "&a.");
            return;
        }
        ClanTier next = tierDefinition(currentTier + 1);
        double cost = Math.max(0.0, next.cost());
        if (clan.bankBalance() + 0.0001 < cost) {
            msg(player, "&cFaltan &e" + formatNumber(cost - clan.bankBalance()) + " &cmonedas en el banco del clan para subir a &d" + next.name() + "&c.");
            return;
        }
        addBankBalance(clan.id(), -cost);
        setClanTier(clan.id(), next.tier());
        logAction(clan.id(), player, "TIER", "Mejoró el clan a " + next.name() + " por " + formatNumber(cost));
        broadcastToClan(clan.id(), "&d&l¡El clan subió a " + next.name() + "! &7Costo pagado desde el banco: &e" + formatNumber(cost) + "&7.");
    }

    private ItemStack storageNavItem(int page, int pages) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color("&ePágina " + page + "/" + pages));
            meta.setLore(List.of(
                    color("&7Click izquierdo: siguiente"),
                    color("&7Click derecho: anterior"),
                    color("&8Este slot se reserva para navegación.")
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void putStoragePage(Inventory inv, ItemStack[] allItems, int page, int totalSlots) {
        int start = (page - 1) * STORAGE_PAGE_ITEM_SLOTS;
        int usable = storageUsableSlotsOnPage(inv.getSize(), page, totalSlots);
        for (int slot = 0; slot < usable; slot++) {
            int index = start + slot;
            inv.setItem(slot, index >= 0 && index < allItems.length ? allItems[index] : null);
        }
    }

    private int storageUsableSlotsOnPage(int invSize, int page, int totalSlots) {
        if (totalSlots <= 54) return Math.min(invSize, totalSlots);
        int start = (page - 1) * STORAGE_PAGE_ITEM_SLOTS;
        return Math.max(0, Math.min(STORAGE_PAGE_ITEM_SLOTS, totalSlots - start));
    }

    private synchronized void saveStoragePage(int clanId, Inventory inv, int page, int totalSlots) throws SQLException, IOException {
        Clan clan = getClan(clanId).orElseThrow();
        int mergeSize = Math.max(totalSlots, inventorySerializedSize(clan.storage()));
        ItemStack[] allItems = deserializeInventory(clan.storage(), mergeSize);
        int start = (page - 1) * STORAGE_PAGE_ITEM_SLOTS;
        int usable = storageUsableSlotsOnPage(inv.getSize(), page, totalSlots);
        for (int slot = 0; slot < usable; slot++) {
            int index = start + slot;
            if (index >= 0 && index < allItems.length) allItems[index] = inv.getItem(slot);
        }
        saveClanStorage(clanId, allItems);
    }

    private synchronized int countAllies(int clanId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM relations r1 JOIN relations r2 ON r2.clan_id=r1.target_clan_id AND r2.target_clan_id=r1.clan_id WHERE r1.clan_id=? AND r1.relation=? AND r2.relation=?")) {
            ps.setInt(1, clanId);
            ps.setString(2, REL_ALLY);
            ps.setString(3, REL_ALLY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private boolean canAcceptNewAlly(Clan clan, int otherClanId) throws SQLException {
        if (areAllies(clan.id(), otherClanId)) return true;
        return countAllies(clan.id()) < maxAlliesForClan(clan);
    }

    private synchronized void setClanBanner(int clanId, String data) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE clans SET banner=? WHERE id=?")) {
            ps.setString(1, data);
            ps.setInt(2, clanId);
            ps.executeUpdate();
        }
    }

    private synchronized void saveClanStorage(int clanId, ItemStack[] contents) throws SQLException, IOException {
        String data = inventoryToBase64(contents);
        try (PreparedStatement ps = connection.prepareStatement("UPDATE clans SET storage=? WHERE id=?")) {
            ps.setString(1, data);
            ps.setInt(2, clanId);
            ps.executeUpdate();
        }
    }

    private synchronized void logAction(int clanId, Player actor, String action, String detail) throws SQLException {
        logAction(clanId, actor == null ? null : actor.getUniqueId(), actor == null ? "Sistema" : actor.getName(), action, detail);
    }

    private synchronized void logAction(int clanId, UUID actorUuid, String actorName, String action, String detail) throws SQLException {
        if (!getConfig().getBoolean("logs.enabled", true)) return;
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO clan_logs(clan_id,time,actor_uuid,actor_name,action,detail) VALUES(?,?,?,?,?,?)")) {
            ps.setInt(1, clanId);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, actorUuid == null ? null : actorUuid.toString());
            ps.setString(4, actorName == null ? "Sistema" : actorName);
            ps.setString(5, action);
            ps.setString(6, detail == null ? "" : detail);
            ps.executeUpdate();
        }
        cleanupClanLogs(clanId);
    }

    private synchronized List<ClanLog> getLogs(int clanId, String actionFilter, int page, int pageSize) throws SQLException {
        List<ClanLog> list = new ArrayList<>();
        int offset = Math.max(0, page - 1) * pageSize;
        List<String> actions = logActionsForFilter(actionFilter);
        String sql;
        if (actions.isEmpty()) {
            sql = "SELECT * FROM clan_logs WHERE clan_id=? ORDER BY time DESC LIMIT ? OFFSET ?";
        } else {
            sql = "SELECT * FROM clan_logs WHERE clan_id=? AND action IN (" + placeholders(actions.size()) + ") ORDER BY time DESC LIMIT ? OFFSET ?";
        }
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, clanId);
            int index = 2;
            for (String action : actions) ps.setString(index++, action);
            ps.setInt(index++, pageSize);
            ps.setInt(index, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(new ClanLog(rs.getInt("id"), rs.getLong("time"), rs.getString("actor_name"), rs.getString("action"), rs.getString("detail")));
            }
        }
        return list;
    }


    private synchronized int countLogs(int clanId, String actionFilter) throws SQLException {
        List<String> actions = logActionsForFilter(actionFilter);
        String sql;
        if (actions.isEmpty()) {
            sql = "SELECT COUNT(*) FROM clan_logs WHERE clan_id=?";
        } else {
            sql = "SELECT COUNT(*) FROM clan_logs WHERE clan_id=? AND action IN (" + placeholders(actions.size()) + ")";
        }
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, clanId);
            int index = 2;
            for (String action : actions) ps.setString(index++, action);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private String placeholders(int count) {
        return String.join(",", Collections.nCopies(Math.max(1, count), "?"));
    }

    private synchronized boolean deleteLog(int clanId, int logId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM clan_logs WHERE clan_id=? AND id=?")) {
            ps.setInt(1, clanId);
            ps.setInt(2, logId);
            return ps.executeUpdate() > 0;
        }
    }

    private synchronized void cleanupClanLogs(int clanId) throws SQLException {
        if (!getConfig().getBoolean("logs.cleanup.enabled", true)) return;
        int days = getConfig().getInt("logs.cleanup.auto-delete-days", 30);
        if (days > 0) {
            long cutoff = System.currentTimeMillis() - days * 86_400_000L;
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM clan_logs WHERE clan_id=? AND time<?")) {
                ps.setInt(1, clanId);
                ps.setLong(2, cutoff);
                ps.executeUpdate();
            }
        }
        int max = getConfig().getInt("logs.cleanup.max-per-clan", 250);
        if (max > 0) {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM clan_logs WHERE clan_id=? AND id NOT IN (SELECT id FROM clan_logs WHERE clan_id=? ORDER BY time DESC LIMIT ?)")) {
                ps.setInt(1, clanId);
                ps.setInt(2, clanId);
                ps.setInt(3, max);
                ps.executeUpdate();
            }
        }
    }

    private synchronized void cleanupAllClanLogs() throws SQLException {
        if (!getConfig().getBoolean("logs.cleanup.enabled", true)) return;
        for (Clan clan : listClans()) cleanupClanLogs(clan.id());
    }

    private synchronized boolean isKillOnCooldown(UUID attacker, UUID victim, long cooldownMs) throws SQLException {
        if (cooldownMs <= 0) return false;
        try (PreparedStatement ps = connection.prepareStatement("SELECT last_kill_at FROM kill_cooldowns WHERE attacker_uuid=? AND victim_uuid=?")) {
            ps.setString(1, attacker.toString());
            ps.setString(2, victim.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && System.currentTimeMillis() - rs.getLong("last_kill_at") < cooldownMs;
            }
        }
    }

    private synchronized void setKillCooldown(UUID attacker, UUID victim) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO kill_cooldowns(attacker_uuid,victim_uuid,last_kill_at) VALUES(?,?,?)")) {
            ps.setString(1, attacker.toString());
            ps.setString(2, victim.toString());
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private synchronized void incrementClanKill(int attackerClanId, int victimClanId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO clan_kills(attacker_clan_id,victim_clan_id,kills,last_kill_at) VALUES(?,?,1,?) " +
                "ON CONFLICT(attacker_clan_id,victim_clan_id) DO UPDATE SET kills=kills+1,last_kill_at=excluded.last_kill_at")) {
            ps.setInt(1, attackerClanId);
            ps.setInt(2, victimClanId);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private synchronized int getTotalKillsByClan(int clanId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COALESCE(SUM(kills),0) FROM clan_kills WHERE attacker_clan_id=?")) {
            ps.setInt(1, clanId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    private synchronized int getTotalDeathsByClan(int clanId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COALESCE(SUM(kills),0) FROM clan_kills WHERE victim_clan_id=?")) {
            ps.setInt(1, clanId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    private synchronized List<ClanTopEntry> getTopKillersAgainst(int victimClanId, int limit) throws SQLException {
        List<ClanTopEntry> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT attacker_clan_id,kills FROM clan_kills WHERE victim_clan_id=? ORDER BY kills DESC LIMIT ?")) {
            ps.setInt(1, victimClanId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int kills = rs.getInt("kills");
                    Optional<Clan> c = getClan(rs.getInt("attacker_clan_id"));
                    if (c.isPresent()) list.add(new ClanTopEntry(c.get(), kills));
                }
            }
        }
        return list;
    }

    private String normalizeLogFilter(String filter) {
        if (filter == null || filter.isBlank()) return "all";
        String f = normalizeUiName(filter);
        return switch (f) {
            case "todos", "todo", "all" -> "all";
            case "miembro", "miembros", "members" -> "miembros";
            case "banco", "bank", "economia", "economía" -> "banco";
            case "diplomacia", "relacion", "relaciones", "relation" -> "diplomacia";
            case "correo", "correos", "mail" -> "correo";
            case "combate", "kills", "pvp", "bajas" -> "combate";
            case "base", "bases" -> "base";
            case "almacen", "almacén", "storage" -> "almacen";
            case "admin", "ajustes", "settings" -> "admin";
            default -> f.toUpperCase(Locale.ROOT);
        };
    }

    private String logFilterDisplay(String filter) {
        return switch (normalizeLogFilter(filter)) {
            case "miembros" -> "Miembros";
            case "banco" -> "Banco";
            case "diplomacia" -> "Diplomacia";
            case "correo" -> "Correo";
            case "combate" -> "Combate";
            case "base" -> "Base";
            case "almacen" -> "Almacén";
            case "admin" -> "Admin";
            case "all" -> "Todos";
            default -> filter == null ? "Todos" : filter;
        };
    }

    private List<String> logActionsForFilter(String filter) {
        return switch (normalizeLogFilter(filter)) {
            case "miembros" -> List.of("CREAR", "UNIRSE", "SALIR", "EXPULSAR", "PROMOVER", "DEGRADAR", "SETRANGO", "SOLICITUD");
            case "banco" -> List.of("BANCO");
            case "diplomacia" -> List.of("RELACION", "DIPLOMACIA");
            case "correo" -> List.of("CORREO");
            case "combate" -> List.of("KILL", "MUERTE");
            case "base" -> List.of("SETBASE");
            case "almacen" -> List.of("ALMACEN");
            case "admin" -> List.of("AJUSTES", "ROL", "ESTANDARTE", "TABLERO", "DESCRIPCION", "TIER", "DISOLVER");
            case "all" -> Collections.emptyList();
            default -> List.of(normalizeLogFilter(filter));
        };
    }

    private double calculateStrength(Clan clan) throws SQLException {
        double killWeight = getConfig().getDouble("top.strength.kill-weight", 10.0);
        double bankDivisor = Math.max(1.0, getConfig().getDouble("top.strength.bank-divisor", 10.0));
        double bankCap = getConfig().getDouble("top.strength.bank-cap", 500.0);
        double memberWeight = getConfig().getDouble("top.strength.member-weight", 5.0);
        return getTotalKillsByClan(clan.id()) * killWeight
                + Math.min(clan.bankBalance() / bankDivisor, bankCap)
                + countMembers(clan.id()) * memberWeight;
    }

    private int parseBaseNumber(String[] args, int defaultNumber) {
        if (args == null || args.length < 2) return defaultNumber;
        try { return Math.max(1, Integer.parseInt(args[1])); }
        catch (NumberFormatException ignored) { return defaultNumber; }
    }

    private ClanTier tierDefinition(int tier) {
        int normalized = Math.max(0, Math.min(maxClanTier(), tier));
        String path = "clan-tiers." + normalized;
        String name = getConfig().getString(path + ".name", switch (normalized) {
            case 1 -> "Hermandad";
            case 2 -> "Compañía";
            case 3 -> "Gremio";
            case 4 -> "Reino";
            default -> "Banda";
        });
        double cost = getConfig().getDouble(path + ".cost", switch (normalized) {
            case 1 -> 5000.0;
            case 2 -> 15000.0;
            case 3 -> 50000.0;
            case 4 -> 250000.0;
            default -> 0.0;
        });
        int maxMembers = getConfig().getInt(path + ".max-members", switch (normalized) {
            case 1 -> 12;
            case 2 -> 16;
            case 3 -> 24;
            case 4 -> 55;
            default -> 8;
        });
        int maxBases = getConfig().getInt(path + ".max-bases", normalized >= 2 ? 2 : 1);
        int storageSlots = getConfig().getInt(path + ".storage-slots", switch (normalized) {
            case 1 -> 18;
            case 2 -> 27;
            case 3 -> 54;
            case 4 -> 159;
            default -> 9;
        });
        int storagePages = getConfig().getInt(path + ".storage-pages", storageSlots > 54 ? storagePagesForSlots(storageSlots) : 1);
        int maxAllies = getConfig().getInt(path + ".max-allies", switch (normalized) {
            case 1 -> 3;
            case 2 -> 4;
            case 3 -> 6;
            case 4 -> 8;
            default -> 2;
        });
        return new ClanTier(normalized, name, cost, Math.max(1, maxMembers), Math.max(1, maxBases), Math.max(9, storageSlots), Math.max(1, storagePages), Math.max(0, maxAllies));
    }

    private int maxClanTier() {
        ConfigurationSection sec = getConfig().getConfigurationSection("clan-tiers");
        if (sec == null) return 4;
        int max = 0;
        for (String key : sec.getKeys(false)) {
            try { max = Math.max(max, Integer.parseInt(key)); }
            catch (NumberFormatException ignored) { }
        }
        return Math.max(0, max);
    }

    private String tierName(int tier) {
        return tierDefinition(tier).name();
    }

    private int maxMembersForClan(Clan clan) {
        return tierDefinition(clan == null ? 0 : clan.tier()).maxMembers();
    }

    private int maxBasesForClan(Clan clan) {
        return tierDefinition(clan == null ? 0 : clan.tier()).maxBases();
    }

    private int maxAlliesForClan(Clan clan) {
        return tierDefinition(clan == null ? 0 : clan.tier()).maxAllies();
    }

    private int storageSlotsForClan(Clan clan) {
        ClanTier tier = tierDefinition(clan == null ? 0 : clan.tier());
        int slots = tier.storageSlots();
        if (tier.storagePages() > 1 && slots <= 54) slots = tier.storagePages() * STORAGE_PAGE_ITEM_SLOTS;
        return Math.max(9, slots);
    }

    private int storagePagesForSlots(int slots) {
        if (slots <= 54) return 1;
        return Math.max(1, (int) Math.ceil(slots / (double) STORAGE_PAGE_ITEM_SLOTS));
    }

    private int storageInventorySize(int totalSlots) {
        if (totalSlots > 54) return 54;
        int rows = Math.max(1, (int) Math.ceil(totalSlots / 9.0));
        return Math.max(9, Math.min(54, rows * 9));
    }

    private String storageDisplay(Clan clan) {
        int slots = storageSlotsForClan(clan);
        int pages = storagePagesForSlots(slots);
        if (pages > 1) return pages + " páginas";
        return slots + " slots";
    }

    private List<String> tierUpgradeLore(Clan clan) {
        ClanTier current = tierDefinition(clan.tier());
        if (clan.tier() >= maxClanTier()) {
            return List.of("", "&7Tier actual: &d" + current.name(), "", "&aEste clan ya alcanzó", "&ael tier máximo.");
        }
        ClanTier next = tierDefinition(clan.tier() + 1);
        double missing = Math.max(0.0, next.cost() - clan.bankBalance());
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Tier actual: &d" + current.name());
        lore.add("&7Siguiente tier: &d" + next.name());
        lore.add("");
        lore.add("&7Costo: &e" + formatNumber(next.cost()) + " monedas");
        lore.add("&7Pago: &6Banco del clan");
        lore.add("&7Banco actual: &e" + formatNumber(clan.bankBalance()));
        if (missing > 0.0) lore.add("&cFaltan: &e" + formatNumber(missing));
        lore.add("");
        lore.add("&6&lMejoras al subir:");
        lore.add("&7Miembros: &e" + current.maxMembers() + " &8→ &a" + next.maxMembers());
        lore.add("&7Bases: &e" + current.maxBases() + " &8→ &a" + next.maxBases());
        lore.add("&7Almacén: &e" + storageDisplayForTier(current) + " &8→ &a" + storageDisplayForTier(next));
        lore.add("&7Aliados: &e" + current.maxAllies() + " &8→ &a" + next.maxAllies());
        lore.add("");
        lore.add(missing <= 0.0 ? "&eClick para mejorar." : "&8Necesitas más dinero en el banco.");
        return lore;
    }

    private String storageDisplayForTier(ClanTier tier) {
        int slots = tier.storageSlots();
        int pages = storagePagesForSlots(slots);
        if (tier.storagePages() > 1) pages = tier.storagePages();
        if (pages > 1) return pages + " páginas";
        return slots + " slots";
    }

    private Map<String, String> tierPlaceholders(Clan clan) throws SQLException {
        Map<String, String> ph = new HashMap<>();
        ClanTier current = tierDefinition(clan.tier());
        ClanTier next = tierDefinition(Math.min(maxClanTier(), clan.tier() + 1));
        ph.put("clan_tier", String.valueOf(clan.tier()));
        ph.put("tier", String.valueOf(clan.tier()));
        ph.put("clan_tier_name", current.name());
        ph.put("tier_name", current.name());
        ph.put("clan_next_tier", clan.tier() >= maxClanTier() ? "MAX" : String.valueOf(clan.tier() + 1));
        ph.put("clan_next_tier_name", clan.tier() >= maxClanTier() ? current.name() : next.name());
        ph.put("next_tier_name", clan.tier() >= maxClanTier() ? current.name() : next.name());
        ph.put("clan_tier_cost", clan.tier() >= maxClanTier() ? "0" : formatNumber(next.cost()));
        ph.put("tier_cost", clan.tier() >= maxClanTier() ? "0" : formatNumber(next.cost()));
        ph.put("max_members", String.valueOf(current.maxMembers()));
        ph.put("clan_max_members", String.valueOf(current.maxMembers()));
        ph.put("max_bases", String.valueOf(current.maxBases()));
        ph.put("clan_max_bases", String.valueOf(current.maxBases()));
        ph.put("max_allies", String.valueOf(current.maxAllies()));
        ph.put("clan_max_allies", String.valueOf(current.maxAllies()));
        ph.put("storage_slots", String.valueOf(storageSlotsForClan(clan)));
        ph.put("clan_storage_slots", String.valueOf(storageSlotsForClan(clan)));
        ph.put("storage_pages", String.valueOf(storagePagesForSlots(storageSlotsForClan(clan))));
        ph.put("clan_storage_pages", String.valueOf(storagePagesForSlots(storageSlotsForClan(clan))));
        ph.put("storage_display", storageDisplay(clan));
        ph.put("clan_storage_display", storageDisplay(clan));
        ph.put("bases", String.valueOf(countClanBases(clan.id())));
        ph.put("allies", String.valueOf(countAllies(clan.id())));
        ph.put("next_max_members", String.valueOf(next.maxMembers()));
        ph.put("next_max_bases", String.valueOf(next.maxBases()));
        ph.put("next_max_allies", String.valueOf(next.maxAllies()));
        ph.put("next_storage_slots", String.valueOf(next.storageSlots()));
        ph.put("next_storage_pages", String.valueOf(next.storagePages()));
        ph.put("next_storage_display", storageDisplayForTier(next));
        return ph;
    }


    private int visibleBaseSlots(Clan clan) {
        int configured = nativeMenus == null ? 0 : nativeMenus.getInt("menus.bases.max-visible-bases", 0);
        int max = Math.max(2, maxBasesForClan(clan));
        if (configured > 0) max = Math.max(max, configured);
        return Math.max(1, Math.min(4, max));
    }

    private int baseTeleportSlot(int baseNumber) {
        int[] defaults = {10, 19, 28, 37};
        int index = Math.max(1, baseNumber) - 1;
        int def = index >= 0 && index < defaults.length ? defaults[index] : -1;
        return nativeSlot("menus.bases.teleport.slot-" + baseNumber, def);
    }

    private int baseSetSlot(int baseNumber) {
        int[] defaults = {16, 25, 34, 43};
        int index = Math.max(1, baseNumber) - 1;
        int def = index >= 0 && index < defaults.length ? defaults[index] : -1;
        return nativeSlot("menus.bases.set.slot-" + baseNumber, def);
    }

    private String tierRequiredForBaseName(int baseNumber) {
        return tierName(tierRequiredForBase(baseNumber));
    }

    private int tierRequiredForBase(int baseNumber) {
        int maxTier = maxClanTier();
        for (int tier = 0; tier <= maxTier; tier++) {
            if (tierDefinition(tier).maxBases() >= baseNumber) return tier;
        }
        return maxTier;
    }

    private Map<String, String> basePlaceholders(Clan clan, int baseNumber, Optional<ClanBase> baseOpt) throws SQLException {
        Map<String, String> ph = clanPlaceholders(clan);
        ph.put("base_number", String.valueOf(baseNumber));
        ph.put("base_required_tier", String.valueOf(tierRequiredForBase(baseNumber)));
        ph.put("base_required_tier_name", tierRequiredForBaseName(baseNumber));
        ph.put("base_unlocked", baseNumber <= maxBasesForClan(clan) ? "true" : "false");
        if (baseOpt.isPresent()) {
            ClanBase base = baseOpt.get();
            ph.put("base_status", "&aEstablecida");
            ph.put("base_world", base.world());
            ph.put("base_x", String.valueOf((int) Math.floor(base.x())));
            ph.put("base_y", String.valueOf((int) Math.floor(base.y())));
            ph.put("base_z", String.valueOf((int) Math.floor(base.z())));
        } else {
            ph.put("base_status", "&cNo establecida");
            ph.put("base_world", "-");
            ph.put("base_x", "-");
            ph.put("base_y", "-");
            ph.put("base_z", "-");
        }
        return ph;
    }

    private ItemStack baseTeleportItem(Clan clan, int baseNumber) throws SQLException {
        Optional<ClanBase> baseOpt = getClanBase(clan.id(), baseNumber);
        Map<String, String> ph = basePlaceholders(clan, baseNumber, baseOpt);
        if (baseNumber > maxBasesForClan(clan)) {
            return nativeItem("menus.bases.teleport.locked", Material.BARRIER, "&8&lBase {base_number} bloqueada", List.of(
                    "",
                    "&7Estado: &cBloqueada por tier",
                    "&7Requiere: &d{base_required_tier_name}",
                    "&7Tu clan: &e{clan_tier_name}",
                    "",
                    "&8Sube el tier del clan para desbloquearla."
            ), ph);
        }
        if (baseOpt.isEmpty()) {
            return nativeItem("menus.bases.teleport.missing", Material.GRAY_DYE, "&7&lBase {base_number}", List.of(
                    "",
                    "&7Estado: &cNo establecida",
                    "",
                    "&8Un rango con permiso debe",
                    "&8establecer esta base primero."
            ), ph);
        }
        return nativeItem("menus.bases.teleport.available", Material.ENDER_PEARL, "&b&lIr a Base {base_number}", List.of(
                "",
                "&7Estado: {base_status}",
                "&7Mundo: &f{base_world}",
                "&7Coordenadas: &e{base_x}&7, &e{base_y}&7, &e{base_z}",
                "",
                "&eClick para teletransportarte."
        ), ph);
    }

    private ItemStack baseSetItem(Member member, Clan clan, int baseNumber) throws SQLException {
        Optional<ClanBase> baseOpt = getClanBase(clan.id(), baseNumber);
        Map<String, String> ph = basePlaceholders(clan, baseNumber, baseOpt);
        if (baseNumber > maxBasesForClan(clan)) {
            return nativeItem("menus.bases.set.locked", Material.BARRIER, "&8&lEstablecer Base {base_number}", List.of(
                    "",
                    "&7Estado: &cBloqueada por tier",
                    "&7Requiere: &d{base_required_tier_name}",
                    "&7Tu clan: &e{clan_tier_name}",
                    "",
                    "&8Todavía no puedes fijar esta base."
            ), ph);
        }
        if (!can(member, "setbase")) {
            return nativeItem("menus.bases.set.no-permission", Material.GRAY_DYE, "&7&lEstablecer Base {base_number}", List.of(
                    "",
                    "&cNo tienes permiso para cambiar bases.",
                    "&8Permiso requerido: setbase"
            ), ph);
        }
        return nativeItem("menus.bases.set.available", Material.COMPASS, "&6&lEstablecer Base {base_number}", List.of(
                "",
                "&7Guarda tu ubicación actual",
                "&7como Base {base_number} del clan.",
                "",
                "&7Estado actual: {base_status}",
                "&7Base guardada: &e{base_x}&7, &e{base_y}&7, &e{base_z}",
                "",
                "&eClick para establecer/cambiar."
        ), ph);
    }

    private String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) return String.valueOf((long) Math.rint(value));
        return String.format(Locale.US, "%.2f", value);
    }

    private String inventoryToBase64(ItemStack[] items) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
            dataOutput.writeInt(items.length);
            for (ItemStack item : items) dataOutput.writeObject(item);
        }
        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }

    private int inventorySerializedSize(String data) {
        if (data == null || data.isBlank()) return 0;
        try (BukkitObjectInputStream dataInput = new BukkitObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data)))) {
            return Math.max(0, dataInput.readInt());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private ItemStack[] deserializeInventory(String data, int size) {
        ItemStack[] items = new ItemStack[size];
        if (data == null || data.isBlank()) return items;
        try (BukkitObjectInputStream dataInput = new BukkitObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data)))) {
            int length = dataInput.readInt();
            for (int i = 0; i < length && i < size; i++) items[i] = (ItemStack) dataInput.readObject();
        } catch (Exception e) {
            getLogger().warning("No se pudo leer inventario de clan: " + e.getMessage());
        }
        return items;
    }

    private String itemToBase64(ItemStack item) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream)) {
            dataOutput.writeObject(item);
        }
        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }

    private ItemStack itemFromBase64(String data) throws IOException, ClassNotFoundException {
        try (BukkitObjectInputStream dataInput = new BukkitObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data)))) {
            return (ItemStack) dataInput.readObject();
        }
    }


    private void openMainMenu(Player player) throws SQLException {
        if (!legacyMainMenusEnabled()) {
            openMDVSocialClanRoot(player);
            return;
        }
        Optional<Member> memberOpt = getMember(player.getUniqueId());
        if (memberOpt.isEmpty()) openNoClanMenu(player);
        else openClanManagementMenu(player);
    }

    private void openLegacyMainOrRedirect(Player player, String ui) throws SQLException {
        String normalized = normalizeUiName(ui);
        if (!legacyMainMenusEnabled()) {
            openMDVSocialClanRoot(player);
            return;
        }
        switch (normalized) {
            case "principal", "completo", "panel", "hub" -> openFullClanHub(player);
            case "gestion", "conclan", "con_clan", "clan_con_clan" -> openClanManagementMenu(player);
            case "sinclan", "sin_clan", "clan_sin_clan" -> openNoClanMenu(player);
            case "crear", "creacion", "crear_clan", "nuevo_clan" -> openCreateClanMenu(player);
            default -> openMainMenu(player);
        }
    }

    private boolean legacyMainMenusEnabled() {
        return getConfig().getBoolean("native-menus.legacy-main.enabled", false);
    }

    private void openMDVSocialClanRoot(Player player) {
        player.closeInventory();
        player.performCommand("social " + defaultSocialBackTarget(player));
    }

    private void openFullClanHub(Player player) throws SQLException {
        Member viewer = requireMember(player); if (viewer == null) return;
        Clan clan = getClan(viewer.clanId()).orElseThrow();
        Map<String, String> ph = clanPlaceholders(clan);
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("hub", 1, clan.id(), null, -1), 27, nativeTitle("hub", "&8&lClan &b{id}", ph));
        fill(inv);
        inv.setItem(nativeSlot("menus.hub.items.summary.slot", 4), clanBannerItem(clan, applyPlaceholders(nativeMenus == null ? "&6&l{name}" : nativeMenus.getString("menus.hub.items.summary.name", "&6&l{name}"), ph), lines("menus.hub.items.summary.lore", List.of("&7ID: &b{id}", "&7Miembros: &e{members}", "&7Banco: &e{bank}", "&7Fuerza: &6{strength}"), ph)));
        inv.setItem(nativeSlot("menus.hub.items.members.slot", 10), nativeItem("menus.hub.items.members", Material.PLAYER_HEAD, "&a&lMiembros", List.of("", "&7Cabezas de jugadores, roles", "&7y acciones de cada miembro.", "", "&eClick para abrir."), ph));
        inv.setItem(nativeSlot("menus.hub.items.info.slot", 11), nativeItem("menus.hub.items.info", Material.WRITABLE_BOOK, "&e&lTablero e información", List.of("", "&7Banner, tablero, buzón,", "&7solicitudes y registros.", "", "&eClick para abrir."), ph));
        inv.setItem(nativeSlot("menus.hub.items.relations.slot", 12), nativeItem("menus.hub.items.relations", Material.RED_BANNER, "&c&lRelaciones", List.of("", "&7Aliados, enemigos, bajas", "&7y ranking de clanes.", "", "&eClick para abrir."), ph));
        inv.setItem(nativeSlot("menus.hub.items.storage.slot", 13), nativeItem("menus.hub.items.storage", Material.CHEST, "&6&lAlmacén y banco", List.of("", "&7Acceso al almacén", "&7y banco del clan.", "", "&eClick para abrir."), ph));
        inv.setItem(nativeSlot("menus.hub.items.base.slot", 14), nativeItem("menus.hub.items.base", Material.ENDER_PEARL, "&b&lIr a la base", List.of("", "&7Ejecuta &f/clan base&7.", "", "&eClick para viajar."), ph));
        inv.setItem(nativeSlot("menus.hub.items.clan-list.slot", 15), nativeItem("menus.hub.items.clan-list", Material.BOOK, "&b&lLista de clanes", List.of("", "&7Mira todos los clanes", "&7de MDVCRAFT.", "", "&eClick para abrir."), ph));
        inv.setItem(nativeSlot("menus.hub.items.leave.slot", 16), nativeItem("menus.hub.items.leave", Material.BARRIER, "&c&lAbandonar clan", List.of("", "&7Ejecuta &f/clan salir&7.", "&8Cuidado, mi broc.", "", "&eClick para salir."), ph));
        inv.setItem(nativeSlot("menus.hub.items.settings.slot", 17), can(viewer, "settings") ? nativeItem("menus.hub.items.settings", Material.COMPARATOR, "&6&lAjustes del clan", List.of("", "&7Nombre, ID, rangos, banner,", "&7permisos y disolver.", "", "&eClick para abrir."), ph) : lockedItem("&7Ajustes del clan", "&cRequiere rango alto."));
        setBackItem(inv, "hub", nativeSlot("menus.hub.items.back.slot", 22), "&6&lGestión rápida", List.of("&7Volver al menú de gestión."));
        inv.setItem(nativeSlot("menus.hub.items.close.slot", 26), nativeItem("menus.hub.items.close", Material.BARRIER, "&c&lCerrar", List.of("&7Cierra el menú."), ph));
        openNativeInventory(player, inv);
    }

    private void openNoClanMenu(Player player) throws SQLException {
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("noclan", 1, -1, null, -1), 27, nativeTitle("no-clan", "&8&lClanes", Map.of("player", player.getName())));
        fill(inv);
        inv.setItem(nativeSlot("menus.no-clan.items.list.slot", 11), nativeItem("menus.no-clan.items.list", Material.BOOK, "&b&lLista de clanes", List.of("", "&7Mira los clanes creados", "&7en MDVCRAFT.", "", "&eClick para abrir."), Map.of("player", player.getName())));
        inv.setItem(nativeSlot("menus.no-clan.items.create.slot", 15), nativeItem("menus.no-clan.items.create", Material.EMERALD, "&a&lCrear clan", List.of("", "&7Abre el menú de creación", "&7para escribir nombre e ID.", "", "&eClick para empezar."), Map.of("player", player.getName())));
        inv.setItem(nativeSlot("menus.no-clan.items.close.slot", 22), nativeItem("menus.no-clan.items.close", Material.BARRIER, "&c&lCerrar", List.of("&7Cierra el menú."), Map.of("player", player.getName())));
        openNativeInventory(player, inv);
    }

    private void openCreateClanMenu(Player player) throws SQLException {
        if (getMember(player.getUniqueId()).isPresent()) {
            msg(player, msgConfig("creation.already-in-clan", "&cYa perteneces a un clan."));
            return;
        }
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("createclan", 1, -1, null, -1), 27, nativeTitle("create", "&8&lCrear clan", Map.of("player", player.getName())));
        fill(inv);
        inv.setItem(nativeSlot("menus.create.items.info.slot", 11), nativeItem("menus.create.items.info", Material.WRITABLE_BOOK, "&e&lFormato", List.of("", "&7La creación será guiada por chat:", "&f1) Nombre del clan", "", "&7Ejemplo:", "&f2) ID/tag: &bMDV", "", "&8ID 3-5 caracteres.", "&8Nombre máximo según config."), Map.of("player", player.getName())));
        inv.setItem(nativeSlot("menus.create.items.start.slot", 13), nativeItem("menus.create.items.start", Material.EMERALD, "&a&lEscribir clan", List.of("", "&7Cierra este menú y te pedirá", "&7el nombre y luego el ID por chat.", "", "&eClick para comenzar."), Map.of("player", player.getName())));
        setBackItem(inv, "createclan", nativeSlot("menus.create.items.back.slot", 22), "&6&lVolver", List.of("&7Regresa al menú anterior."));
        inv.setItem(nativeSlot("menus.create.items.close.slot", 26), nativeItem("menus.create.items.close", Material.BARRIER, "&c&lCerrar", List.of("&7Cierra el menú."), Map.of("player", player.getName())));
        openNativeInventory(player, inv);
    }

    private void openClanManagementMenu(Player player) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        Map<String, String> ph = clanPlaceholders(clan);
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("gestion", 1, clan.id(), null, -1), 27, nativeTitle("management", "&8&lGestión del clan", ph));
        fill(inv);
        inv.setItem(nativeSlot("menus.management.items.summary.slot", 4), clanBannerItem(clan, applyPlaceholders(nativeMenus == null ? "&6&l{name}" : nativeMenus.getString("menus.management.items.summary.name", "&6&l{name}"), ph), lines("menus.management.items.summary.lore", List.of("&7ID: &b{id}", "&7Miembros: &e{members}", "&7Banco: &e{bank}", "&7Fuerza: &6{strength}", "", "&eClick para abrir el panel completo."), ph)));
        inv.setItem(nativeSlot("menus.management.items.base.slot", 10), nativeItem("menus.management.items.base", Material.ENDER_PEARL, "&b&lIr a la base", List.of("", "&7Teletranspórtate a la base", "&7definida por el clan.", "", "&eClick para viajar."), ph));
        inv.setItem(nativeSlot("menus.management.items.setbase.slot", 11), can(member, "setbase") ? nativeItem("menus.management.items.setbase", Material.COMPASS, "&6&lFijar base", List.of("", "&7Define la base del clan", "&7en tu ubicación actual.", "", "&8Requiere rango configurado."), ph) : lockedItem("&7Fijar base", "&cNo tienes rango para esta función."));
        inv.setItem(nativeSlot("menus.management.items.bank.slot", 13), nativeItem("menus.management.items.bank", Material.GOLD_NUGGET, "&e&lBanco del clan", List.of("", "&7Balance: &e{bank}", "&7Depositar: &f/clan banco depositar cantidad", "&7Retirar: &f/clan banco retirar cantidad", "", "&eClick para consultar."), ph));
        inv.setItem(nativeSlot("menus.management.items.storage.slot", 14), nativeItem("menus.management.items.storage", Material.CHEST, "&a&lAlmacén del clan", List.of("", "&7Abre el inventario compartido", "&7del clan.", "", "&eClick para abrir."), ph));
        inv.setItem(nativeSlot("menus.management.items.banner.slot", 15), clanBannerItem(clan, applyPlaceholders(nativeMenus == null ? "&f&lEstandarte oficial" : nativeMenus.getString("menus.management.items.banner.name", "&f&lEstandarte oficial"), ph), lines("menus.management.items.banner.lore", List.of("", "&7Ver el banner oficial", "&7del clan.", "", "&eClick para ver."), ph)));
        inv.setItem(nativeSlot("menus.management.items.logs.slot", 16), can(member, "logs-view") ? nativeItem("menus.management.items.logs", Material.WRITABLE_BOOK, "&d&lRegistro del clan", List.of("", "&7Muestra acciones recientes:", "&8banco, miembros, base,", "&8relaciones y bajas.", "", "&eClick para ver."), ph) : lockedItem("&7Registro del clan", "&cNo tienes rango para esta función."));
        setBackItem(inv, "gestion", nativeSlot("menus.management.items.back.slot", 22), "&6&lVolver", List.of("&7Volver al menú social."));
        inv.setItem(nativeSlot("menus.management.items.close.slot", 26), nativeItem("menus.management.items.close", Material.BARRIER, "&c&lCerrar", List.of("&7Cierra el menú."), ph));
        openNativeInventory(player, inv);
    }

    private void openMembersMenu(Player player, int page) throws SQLException {
        Member viewer = requireMember(player); if (viewer == null) return;
        Clan clan = getClan(viewer.clanId()).orElseThrow();
        List<Member> members = getMembers(clan.id());
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("members", page, clan.id(), null, -1), 54, nativeTitle("members", "&8Miembros &b{id}", clanPlaceholders(clan)));
        fill(inv);
        int pageSize = GUI_PAGE_SIZE;
        int start = Math.max(0, page - 1) * pageSize;
        for (int i = start; i < Math.min(start + pageSize, members.size()); i++) {
            Member m = members.get(i);
            inv.setItem(contentSlot(i - start), memberHead(m, clan.id(), player));
        }
        nav(inv, page, members.size(), pageSize, "members", clan.id());
        setBackItem(inv, "members", 49, "&6&lVolver", List.of("&7Regresa al menú principal."));
        openNativeInventory(player, inv);
    }

    private void openClanInfoMenu(Player player) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        Map<String, String> ph = clanPlaceholders(clan);
        ph.put("join_requests", String.valueOf(countJoinRequests(clan.id())));
        ph.put("created", date(clan.createdAt()));
        ph.put("board", String.join(" | ", boardLines(clan.boardMessage())).replace("&", "§"));
        ph.put("board_edit_hint", can(member, "board-edit") ? "&6Shift-click: editar." : "&8Solo rangos altos pueden editar.");
        ph.put("can_logs", can(member, "logs-view") ? "&eClick para ver logs." : "&8No tienes rango para ver logs.");
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("info", 1, clan.id(), null, -1), 27, nativeTitle("info", "&8Info &b{id}", ph));
        fill(inv);
        inv.setItem(nativeSlot("menus.info.items.banner.slot", 10), clanBannerItem(clan,
                applyPlaceholders(configString("menus.info.items.banner.name", "&f&lEstandarte oficial"), ph),
                lines("menus.info.items.banner.lore", List.of("&7Click: ver", "&8Para cambiarlo usa Ajustes del clan.", "&8Sin banner: WHITE_BANNER por defecto"), ph), ph));
        inv.setItem(nativeSlot("menus.info.items.board.slot", 12), nativeItem("menus.info.items.board", Material.WRITABLE_BOOK, "&e&lTablero de información", boardItemLore(clan), ph));
        if (nativeSection("menus.info.items.description") != null) {
            inv.setItem(nativeSlot("menus.info.items.description.slot", 15), nativeItem("menus.info.items.description", Material.MAP, "&6&lDescripción pública", List.of("", "&7Visible en listas e info", "&7pública del clan.", "", "&f{description_line_1}", "&f{description_line_2}", "&f{description_line_3}", "&f{description_line_4}", "&f{description_line_5}", "", "&eClick para ver en chat.", "&8Se edita desde Ajustes."), ph));
        }
        inv.setItem(nativeSlot("menus.info.items.requests.slot", 13), nativeItem("menus.info.items.requests", Material.PLAYER_HEAD, "&a&lSolicitudes pendientes", List.of("", "&7Jugadores que quieren entrar", "&7cuando el clan está restringido.", "", "&7Pendientes: &e{join_requests}", "", "&eClick para abrir."), ph));
        inv.setItem(nativeSlot("menus.info.items.mailbox.slot", 14), nativeItem("menus.info.items.mailbox", Material.CHEST, "&d&lBuzón del clan", List.of("", "&7Mensajes enviados por otros clanes.", "&7Todos pueden leer.", "&7Rangos altos pueden borrar/responder.", "", "&eClick para abrir."), ph));
        inv.setItem(nativeSlot("menus.info.items.logs.slot", 16), can(member, "logs-view") ? nativeItem("menus.info.items.logs", Material.BOOK, "&6&lCreación y registros", List.of("", "&7Creado: &f{created}", "&7Miembros: &e{members}", "&7Banco: &e{bank}", "", "{can_logs}"), ph) : lockedItem("&7Registros del clan", "&cNo tienes rango para ver logs."));
        inv.setItem(nativeSlot("menus.info.items.permissions.slot", 20), nativeItem("menus.info.items.permissions", Material.KNOWLEDGE_BOOK, "&b&lPermisos y rangos", List.of("", "&7Mira qué puede hacer", "&7cada rango del clan.", "", "&eClick para abrir."), ph));
        setBackItem(inv, "info", 22, "&6&lVolver", List.of("&7Regresa al menú principal."));
        openNativeInventory(player, inv);
    }



    private void openRelationsMenu(Player player) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        Map<String, String> ph = clanPlaceholders(clan);
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("relations", 1, member.clanId(), null, -1), 27, nativeTitle("relations", "&8Relaciones", ph));
        fill(inv);
        inv.setItem(nativeSlot("menus.relations.items.list.slot", 11), nativeItem("menus.relations.items.list", Material.BLUE_BANNER, "&9&lRelaciones con clanes", List.of("", "&7Muestra aliados y enemigos.", "&7Los neutrales se omiten.", "", "&eClick para abrir."), ph));
        inv.setItem(nativeSlot("menus.relations.items.kills.slot", 13), nativeItem("menus.relations.items.kills", Material.IRON_SWORD, "&c&lKills y bajas", List.of("", "&7Banners de clanes que han", "&7matado miembros de tu clan.", "", "&eClick para abrir."), ph));
        inv.setItem(nativeSlot("menus.relations.items.ranking.slot", 15), nativeItem("menus.relations.items.ranking", Material.NETHER_STAR, "&6&lRanking", List.of("", "&7Top por fuerza, kills", "&7y banco.", "", "&eClick para abrir."), ph));
        setBackItem(inv, "relations", 22, "&6&lVolver", List.of("&7Regresa al menú principal."));
        openNativeInventory(player, inv);
    }


    private void openStorageHubMenu(Player player) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        Map<String, String> ph = clanPlaceholders(clan);
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("storagehub", 1, clan.id(), null, -1), 27, nativeTitle("storage", "&8Almacén y banco", ph));
        fill(inv);
        inv.setItem(nativeSlot("menus.storage.items.storage.slot", 11), nativeItem("menus.storage.items.storage", Material.CHEST, "&a&lAlmacén del clan", List.of("", "&7Inventario compartido.", "&7Todos pueden usarlo según config.", "", "&eClick para abrir."), ph));
        inv.setItem(nativeSlot("menus.storage.items.bank.slot", 15), nativeItem("menus.storage.items.bank", Material.GOLD_BLOCK, "&e&lBanco del clan", List.of("", "&7Balance: &e{bank}", "", "&7Comandos:", "&f/clan banco depositar <cantidad>", "&f/clan banco retirar <cantidad>", "", "&eClick para ver balance."), ph));
        setBackItem(inv, "storagehub", 22, "&6&lVolver", List.of("&7Regresa al menú principal."));
        openNativeInventory(player, inv);
    }



    private void openBasesMenu(Player player) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        Map<String, String> ph = clanPlaceholders(clan);
        int visibleBases = visibleBaseSlots(clan);
        ph.put("base_visible_count", String.valueOf(visibleBases));
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("bases", 1, clan.id(), null, -1), 54, nativeTitle("bases", "&8Bases del clan &b{id}", ph));
        fill(inv);

        inv.setItem(nativeSlot("menus.bases.items.info.slot", 13), nativeItem("menus.bases.items.info", Material.LODESTONE, "&b&lBases del clan", List.of(
                "",
                "&7Tier actual: &d{clan_tier_name}",
                "&7Bases establecidas: &e{bases}&7/&e{max_bases}",
                "&7Bases visibles: &e{base_visible_count}",
                "",
                "&7Izquierda: &bteletransportarse&7.",
                "&7Derecha: &6establecer/cambiar&7.",
                "",
                "&8Las bases bloqueadas dependen del tier."
        ), ph));

        for (int baseNumber = 1; baseNumber <= visibleBases; baseNumber++) {
            int tpSlot = baseTeleportSlot(baseNumber);
            if (tpSlot >= 0 && tpSlot < inv.getSize()) inv.setItem(tpSlot, baseTeleportItem(clan, baseNumber));
            int setSlot = baseSetSlot(baseNumber);
            if (setSlot >= 0 && setSlot < inv.getSize()) inv.setItem(setSlot, baseSetItem(member, clan, baseNumber));
        }

        setBackItem(inv, "bases", 49, "&6&lVolver", List.of("&7Regresa a gestión del clan."));
        openNativeInventory(player, inv);
    }

    private void openClanListMenu(Player player, int page) throws SQLException {
        List<Clan> clans = listClans();
        Map<String, String> basePh = Map.of("player", player.getName());
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("clanlist", page, -1, null, -1), 54, nativeTitle("clan-list", "&8Clanes de MDVCRAFT", basePh));
        fill(inv);
        int pageSize = GUI_PAGE_SIZE;
        int start = Math.max(0, page - 1) * pageSize;
        Optional<Member> viewerMember = getMember(player.getUniqueId());
        boolean hasClan = viewerMember.isPresent();
        int viewerClanId = viewerMember.map(Member::clanId).orElse(-1);
        for (int i = start; i < Math.min(start + pageSize, clans.size()); i++) {
            Clan c = clans.get(i);
            Map<String, String> ph = clanPlaceholders(c);
            ph.put("entry", c.open() ? "&aAbierta" : "&cInvitación");
            String relationDisplay = hasClan ? relationText(getRelationBetween(viewerClanId, c.id())) : "";
            ph.put("relation", relationDisplay);
            ph.put("relation_raw", hasClan ? getRelationBetween(viewerClanId, c.id()) : "");
            ph.put("relation_line", hasClan ? "&7Relación actual: " + relationDisplay : HIDE_LINE);
            ph.put("click_hint", hasClan ? "&eClick para abrir opciones." : (c.open() ? "&eClick para unirte." : "&eClick para enviar solicitud."));
            ph.put("click_extra", hasClan ? "&8Relaciones, correo e info." : "");
            inv.setItem(contentSlot(i - start), clanBannerItem(c,
                    applyPlaceholders(configString("menus.clan-list.clan-item.name", "&8[&b{id}&8] &f{name}"), ph),
                    lines("menus.clan-list.clan-item.lore", List.of("&7ID: &b{id}", "&7Miembros: &e{members}&7/&e{max_members}", "&7Entrada: {entry}", "&7Fuerza: &6{strength}", "", "{click_hint}", "{click_extra}"), ph), ph));
        }
        nav(inv, page, clans.size(), pageSize, "clanlist", -1);
        setBackItem(inv, "clanlist", 49, "&6&lVolver", List.of("&7Regresa al menú principal."));
        openNativeInventory(player, inv);
    }


    private void openRelationsListMenu(Player player, int page) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan own = getClan(member.clanId()).orElseThrow();
        List<ClanRelationView> relations = getVisibleRelations(member.clanId());
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("relationslist", page, member.clanId(), null, -1), 54, nativeTitle("relations-list", "&8Aliados y enemigos", clanPlaceholders(own)));
        fill(inv);
        int pageSize = GUI_PAGE_SIZE, start = Math.max(0, page - 1) * pageSize;
        for (int i = start; i < Math.min(start + pageSize, relations.size()); i++) {
            ClanRelationView r = relations.get(i);
            String relName = REL_ALLY.equals(r.relation()) ? "&9Aliado" : REL_ENEMY.equals(r.relation()) ? "&cEnemigo" : "&eSolicitud";
            Map<String, String> ph = clanPlaceholders(r.clan());
            ph.put("relation", relName);
            inv.setItem(contentSlot(i - start), clanBannerItem(r.clan(),
                    applyPlaceholders(configString("menus.relations-list.clan-item.name", "{relation} &8[&b{id}&8]"), ph),
                    lines("menus.relations-list.clan-item.lore", List.of("&7Clan: &f{name}", "&7Relación: {relation}", "", "&eClick para ver info."), ph), ph));
        }
        nav(inv, page, relations.size(), pageSize, "relationslist", member.clanId());
        setBackItem(inv, "relationslist", 49, "&6&lVolver", List.of("&7Regresa a Relaciones."));
        openNativeInventory(player, inv);
    }


    private void openKillStatsGui(Player player) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        Map<String, String> ownPh = clanPlaceholders(clan);
        ownPh.put("kills_done", String.valueOf(getTotalKillsByClan(clan.id())));
        ownPh.put("deaths_suffered", String.valueOf(getTotalDeathsByClan(clan.id())));
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("bajas", 1, clan.id(), null, -1), 54, nativeTitle("kills", "&8Kills y bajas", ownPh));
        fill(inv);
        inv.setItem(nativeSlot("menus.kills.items.own.slot", 4), clanBannerItem(clan,
                applyPlaceholders(configString("menus.kills.items.own.name", "&a&lTu clan"), ownPh),
                lines("menus.kills.items.own.lore", List.of("&7Kills hechas: &a{kills_done}", "&7Bajas sufridas: &c{deaths_suffered}", "&7Fuerza: &6{strength}"), ownPh), ownPh));
        List<ClanTopEntry> suffered = getTopKillersAgainst(clan.id(), GUI_PAGE_SIZE);
        for (int i = 0; i < Math.min(GUI_PAGE_SIZE, suffered.size()); i++) {
            ClanTopEntry e = suffered.get(i);
            Map<String, String> ph = clanPlaceholders(e.clan());
            ph.put("kills_against_us", String.valueOf((int) e.value()));
            inv.setItem(contentSlot(i), clanBannerItem(e.clan(),
                    applyPlaceholders(configString("menus.kills.enemy-item.name", "&c{id} &7nos hizo &c{kills_against_us} &7bajas"), ph),
                    lines("menus.kills.enemy-item.lore", List.of("&7Clan: &f{name}", "&7Kills contra nosotros: &c{kills_against_us}"), ph), ph));
        }
        setBackItem(inv, "bajas", 49, "&6&lVolver", List.of("&7Regresa a Relaciones."));
        openNativeInventory(player, inv);
    }


    private void openTopGui(Player player, String mode) throws SQLException {
        if (!equalsAny(mode, "fuerza", "kills", "banco")) mode = "fuerza";
        List<ClanTopEntry> entries = topEntries(mode);
        Map<String, String> menuPh = Map.of("mode", mode);
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("top:" + mode, 1, -1, null, -1), 54, nativeTitle("top", "&8Ranking: {mode}", menuPh));
        fill(inv);
        inv.setItem(nativeSlot("menus.top.items.force.slot", 3), nativeItem("menus.top.items.force", Material.NETHER_STAR, "&6&lTop fuerza", List.of("&eClick para ver."), menuPh));
        inv.setItem(nativeSlot("menus.top.items.kills.slot", 4), nativeItem("menus.top.items.kills", Material.IRON_SWORD, "&c&lTop kills", List.of("&eClick para ver."), menuPh));
        inv.setItem(nativeSlot("menus.top.items.bank.slot", 5), nativeItem("menus.top.items.bank", Material.GOLD_BLOCK, "&e&lTop banco", List.of("&eClick para ver."), menuPh));
        for (int i = 0; i < Math.min(GUI_PAGE_SIZE, entries.size()); i++) {
            ClanTopEntry e = entries.get(i);
            Map<String, String> ph = clanPlaceholders(e.clan());
            ph.put("position", String.valueOf(i + 1));
            ph.put("value", formatNumber(e.value()));
            ph.put("mode", mode);
            inv.setItem(contentSlot(i), clanBannerItem(e.clan(),
                    applyPlaceholders(configString("menus.top.entry.name", "&e#{position} &8[&b{id}&8]"), ph),
                    lines("menus.top.entry.lore", List.of("&7Clan: &f{name}", "&7Valor: &6{value}", "&7Miembros: &e{members}"), ph), ph));
        }
        setBackItem(inv, "top:" + mode, 49, "&6&lVolver", List.of("&7Regresa a Relaciones."));
        openNativeInventory(player, inv);
    }


    private void openMailboxMenu(Player player, int page) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan own = getClan(member.clanId()).orElseThrow();
        List<ClanMail> mails = getClanMails(member.clanId(), page, GUI_PAGE_SIZE);
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("mailbox", page, member.clanId(), null, -1), 54, nativeTitle("mailbox", "&8Buzón del clan", clanPlaceholders(own)));
        fill(inv);
        for (int i = 0; i < mails.size(); i++) {
            ClanMail mail = mails.get(i);
            Optional<Clan> from = getClan(mail.fromClanId());
            Map<String, String> ph = mailPlaceholders(mail, from.orElse(null));
            ItemStack icon = from.map(c -> clanBannerItem(c,
                    applyPlaceholders(configString("menus.mailbox.mail-item.name", "&6&l#&f{mail_id} &8- &d{from_name}"), ph),
                    lines("menus.mailbox.mail-item.lore", List.of("", "&7De: &d{from_name} &8[&6{from_id}&8]", "&7Mensajero: &e{sender}", "&7Fecha: &a{date}", "", "&6&lContenido:", "&f{message_line_1}", "&f{message_line_2}", "&f{message_line_3}", "&f{message_line_4}", "&f{message_line_5}", "", "&eClick para abrir opciones."), ph), ph)
            ).orElse(nativeItem("menus.mailbox.mail-item", Material.PAPER, "&6&l#&f{mail_id}", List.of("&f{message_line_1}", "&f{message_line_2}", "&f{message_line_3}", "&f{message_line_4}", "&f{message_line_5}"), ph));
            inv.setItem(contentSlot(i), icon);
        }
        nav(inv, page, countClanMails(member.clanId()), GUI_PAGE_SIZE, "mailbox", member.clanId());
        setBackItem(inv, "mailbox", 49, "&6&lVolver", List.of("&7Regresa a Información."));
        openNativeInventory(player, inv);
    }


    private void openLogsGui(Player player, int page) throws SQLException {
        openLogsGui(player, page, currentLogFilter.getOrDefault(player.getUniqueId(), "all"));
    }

    private void openLogsGui(Player player, int page, String filter) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        if (!hasRank(player, member, "logs-view")) return;
        String normalized = normalizeLogFilter(filter);
        currentLogFilter.put(player.getUniqueId(), normalized);
        Clan clan = getClan(member.clanId()).orElseThrow();
        List<ClanLog> logs = getLogs(member.clanId(), normalized, page, GUI_PAGE_SIZE);
        Map<String, String> menuPh = clanPlaceholders(clan);
        menuPh.put("filter", logFilterDisplay(normalized));
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("logs", page, member.clanId(), null, -1), 54, nativeTitle("logs", "&8Registros: {filter}", menuPh));
        fill(inv);
        putLogFilterItems(inv, normalized);
        for (int i = 0; i < logs.size(); i++) {
            ClanLog log = logs.get(i);
            Map<String, String> ph = new HashMap<>(menuPh);
            ph.put("log_id", String.valueOf(log.id()));
            ph.put("action", log.action());
            ph.put("category", logFilterDisplay(categoryForLogAction(log.action())));
            ph.put("actor", log.actorName());
            ph.put("date", date(log.time()));
            ph.put("detail", log.detail());
            inv.setItem(contentSlot(i), nativeItem("menus.logs.log-item", Material.PAPER, "&e#{log_id} &6{action}", List.of("&7Categoría: &e{category}", "&7Actor: &f{actor}", "&7Fecha: &f{date}", "", "&7{detail}", "", "&8Click derecho admin: borrar log."), ph));
        }
        nav(inv, page, countLogs(member.clanId(), normalized), GUI_PAGE_SIZE, "logs", member.clanId());
        setBackItem(inv, "logs", 49, "&6&lVolver", List.of("&7Regresa a Información."));
        openNativeInventory(player, inv);
    }

    private void putLogFilterItems(Inventory inv, String activeFilter) {
        String[] keys = {"all", "miembros", "banco", "diplomacia", "correo", "combate", "admin"};
        int[] defaults = {1, 2, 3, 4, 5, 6, 7};
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            Map<String, String> ph = Map.of("filter", logFilterDisplay(key), "active", key.equals(activeFilter) ? "&aActivo" : "&7Click para filtrar");
            String path = "menus.logs.filters." + key;
            Material def = key.equals("all") ? Material.BOOK : key.equals("banco") ? Material.GOLD_NUGGET : key.equals("diplomacia") ? Material.BLUE_BANNER : key.equals("correo") ? Material.WRITABLE_BOOK : key.equals("combate") ? Material.IRON_SWORD : key.equals("admin") ? Material.COMPARATOR : Material.PLAYER_HEAD;
            inv.setItem(nativeSlot(path + ".slot", defaults[i]), nativeItem(path, def, "&e{filter}", List.of("{active}"), ph));
        }
    }



    private String categoryForLogAction(String action) {
        if (action == null) return "all";
        String upper = action.toUpperCase(Locale.ROOT);
        for (String filter : List.of("miembros", "banco", "diplomacia", "correo", "combate", "base", "almacen", "admin")) {
            if (logActionsForFilter(filter).contains(upper)) return filter;
        }
        return "all";
    }

    private void openSettingsMenu(Player player) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        Map<String, String> ph = clanPlaceholders(clan);
        ph.put("entry", clan.open() ? "&aAbierto" : "&cSolo invitación");
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("settings", 1, clan.id(), null, -1), 54, nativeTitle("settings", "&8Ajustes &b{id}", ph));
        fill(inv);
        inv.setItem(nativeSlot("menus.settings.items.rename-name.slot", 10), can(member, "rename-clan") ? nativeItem("menus.settings.items.rename-name", Material.NAME_TAG, "&e&lCambiar nombre", List.of("", "&7Nombre actual: &f{name}", "", "&eClick para escribir el nuevo nombre."), ph) : lockedItem("&7Cambiar nombre", "&cRequiere rango alto."));
        inv.setItem(nativeSlot("menus.settings.items.rename-id.slot", 11), can(member, "rename-tag") ? nativeItem("menus.settings.items.rename-id", Material.OAK_SIGN, "&b&lCambiar ID", List.of("", "&7ID actual: &b{id}", "&7El ID se ve en chat/listas.", "", "&eClick para escribir el nuevo ID."), ph) : lockedItem("&7Cambiar ID", "&cRequiere rango alto."));
        inv.setItem(nativeSlot("menus.settings.items.roles.slot", 12), can(member, "rename-role") ? nativeItem("menus.settings.items.roles", Material.WRITABLE_BOOK, "&d&lNombres de rangos", List.of("", "&7Cambia los nombres visibles", "&7de los rangos 0-4.", "", "&eClick para abrir."), ph) : lockedItem("&7Nombres de rangos", "&cRequiere rango alto."));
        if (nativeSection("menus.settings.items.description") != null) {
            inv.setItem(nativeSlot("menus.settings.items.description.slot", 18), can(member, "description-edit") ? nativeItem("menus.settings.items.description", Material.MAP, "&6&lDescripción pública", List.of("", "&7Descripción actual:", "&f{description}", "", "&eClick para editar.", "&cClick derecho para limpiar."), ph) : lockedItem("&7Descripción pública", "&cRequiere rango alto."));
        }
        inv.setItem(nativeSlot("menus.settings.items.banner.slot", 13), can(member, "banner-set") ? clanBannerItem(clan, applyPlaceholders(configString("menus.settings.items.banner.name", "&f&lCambiar banner"), ph), bannerSettingsLore(ph), ph) : lockedItem("&7Cambiar banner", "&cRequiere rango alto."));
        inv.setItem(nativeSlot("menus.settings.items.permissions.slot", 14), can(member, "permissions-edit") ? nativeItem("menus.settings.items.permissions", Material.KNOWLEDGE_BOOK, "&b&lConfigurar permisos", List.of("", "&7Edita qué acciones puede usar", "&7cada rango del clan.", "", "&eClick para abrir el editor."), ph) : lockedItem("&7Configurar permisos", "&cRequiere permiso de administrar permisos."));
        inv.setItem(nativeSlot("menus.settings.items.open.slot", 15), can(member, "open") ? nativeItem("menus.settings.items.open", Material.OAK_DOOR, "&a&lEntrada del clan", List.of("", "&7Estado actual: {entry}", "", "&eClick para alternar."), ph) : lockedItem("&7Entrada del clan", "&cRequiere rango alto."));
        inv.setItem(nativeSlot("menus.settings.items.disband.slot", 16), can(member, "disband") ? nativeItem("menus.settings.items.disband", Material.TNT, "&4&lDisolver clan", List.of("", "&cAcción peligrosa.", "&7Usa el comando de confirmación.", "", "&eClick para instrucciones."), ph) : lockedItem("&7Disolver clan", "&cSolo el rango máximo."));
        inv.setItem(nativeSlot("menus.settings.items.tier-upgrade.slot", 20), can(member, "tier-upgrade") ? nativeItem("menus.settings.items.tier-upgrade", Material.NETHER_STAR, "&d&lMejorar clan", tierUpgradeLore(clan), ph) : lockedItem("&7Mejorar clan", "&cRequiere permiso para mejorar el clan."));
        if (nativeSection("menus.settings.items.leave") != null) {
            inv.setItem(nativeSlot("menus.settings.items.leave.slot", 24), nativeItem("menus.settings.items.leave", Material.OAK_DOOR, "&c&lSalir del clan", List.of("", "&7Abandona tu clan actual.", "", "&eClick para salir."), ph));
        }
        setBackItem(inv, "settings", 49, "&6&lVolver", List.of("&7Regresa al menú principal."));
        openNativeInventory(player, inv);
    }


    private void openRoleSettingsMenu(Player player) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("rolesettings", 1, clan.id(), null, -1), 27, nativeTitle("role-settings", "&8Rangos &b{id}", clanPlaceholders(clan)));
        fill(inv);
        int[] slots = {10, 11, 12, 13, 14};
        for (int role = minRole(); role <= maxRole() && role < slots.length; role++) {
            Map<String, String> ph = clanPlaceholders(clan);
            ph.put("role_number", String.valueOf(role));
            ph.put("role_name", getRoleName(clan.id(), role));
            inv.setItem(nativeSlot("menus.role-settings.role-item.slot-" + role, slots[role]), nativeItem("menus.role-settings.role-item", Material.PAPER, "&eRango {role_number} &8- &b{role_name}", List.of("", "&7Este es el nombre visible", "&7del rango {role_number}.", "", "&eClick para renombrar."), ph));
        }
        setBackItem(inv, "rolesettings", 22, "&6&lVolver", List.of("&7Regresa a ajustes."));
        openNativeInventory(player, inv);
    }


    private void openPermissionsMenu(Player player, int page) throws SQLException {
        openPermissionTableMenu(player, page, false);
    }

    private void openPermissionsEditMenu(Player player, int page) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        if (!hasRank(player, member, "permissions-edit")) return;
        openPermissionTableMenu(player, page, true);
    }

    private void openPermissionTableMenu(Player player, int page, boolean editable) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        List<String> keys = permissionKeys();
        String menuHolder = editable ? "permissionsedit" : "permissions";
        String path = permissionMenuPath(editable);
        int rowsPerPage = Math.max(1, Math.min(5, nativeMenus == null ? 4 : nativeMenus.getInt(path + ".table.rows-per-page", 4)));
        int[] headerSlots = nativeIntArray(path + ".table.role-header-slots", new int[]{3,4,5,6,7});
        int[] rowStarts = nativeIntArray(path + ".table.permission-row-start-slots", new int[]{9,18,27,36});
        rowsPerPage = Math.min(rowsPerPage, Math.max(1, rowStarts.length));
        int maxPage = Math.max(1, (int) Math.ceil(keys.size() / (double) rowsPerPage));
        page = Math.max(1, Math.min(page, maxPage));
        int start = (page - 1) * rowsPerPage;
        Map<String, String> basePh = clanPlaceholders(clan);
        basePh.put("page", String.valueOf(page));
        basePh.put("max_page", String.valueOf(maxPage));
        basePh.put("mode", editable ? "Edición" : "Vista");
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder(menuHolder, page, clan.id(), null, -1), 54, nativeTitle(menuConfigKey(menuHolder), editable ? "&8Editar permisos &7({page}/{max_page})" : "&8Permisos &7({page}/{max_page})", basePh));
        fill(inv);
        for (int role = minRole(); role <= maxRole() && role - minRole() < headerSlots.length; role++) {
            Map<String, String> ph = new HashMap<>(basePh);
            ph.put("role_number", String.valueOf(role));
            ph.put("role_name", getRoleName(clan.id(), role));
            ph.put("leader_note", role >= maxRole() ? "&aEl líder siempre tiene todos los permisos." : "&7Columna de permisos para este rango.");
            inv.setItem(headerSlots[role - minRole()], nativeItem(path + ".role-header", Material.GRAY_BANNER, "&6&lRango {role_number}", List.of("&f{role_name}", "", "{leader_note}"), ph));
        }

        for (int row = 0; row < rowsPerPage && start + row < keys.size() && row < rowStarts.length; row++) {
            String key = keys.get(start + row);
            int req = defaultRequiredRank(key);
            Map<String, String> ph = new HashMap<>(basePh);
            ph.put("permission", key);
            ph.put("permission_key", key);
            ph.put("permission_name", permissionName(key));
            ph.put("permission_index", String.valueOf(start + row + 1));
            ph.put("required_rank", String.valueOf(req));
            ph.put("required_role", getRoleName(clan.id(), req));
            ph.put("permission_description", String.join("|", permissionDescription(key)));

            int rowStart = rowStarts[row];
            inv.setItem(rowStart, nativeItem(path + ".permission-item", permissionMaterial(key, req), "&e#{permission_index} {permission_name}", permissionTableLore(key, editable), ph));

            for (int role = minRole(); role <= maxRole() && role - minRole() < headerSlots.length; role++) {
                boolean allowed = isClanRolePermissionAllowed(clan.id(), key, role);
                Map<String, String> statusPh = new HashMap<>(ph);
                statusPh.put("role_number", String.valueOf(role));
                statusPh.put("role_name", getRoleName(clan.id(), role));
                statusPh.put("permission_status", allowed ? "&aPermitido" : "&cBloqueado");
                statusPh.put("permission_status_plain", allowed ? "Permitido" : "Bloqueado");
                statusPh.put("edit_hint", editable ? (role >= maxRole() ? "&8El líder no se puede bloquear." : "&eClick para alternar este permiso.") : "&8Solo lectura.");
                int slot = rowStart + 3 + (role - minRole());
                inv.setItem(slot, nativeItem(path + (allowed ? ".allowed-item" : ".denied-item"), allowed ? Material.LIME_WOOL : Material.RED_WOOL, allowed ? "&a✔ Permitido" : "&c✖ Bloqueado", List.of("&7Rango: &6{role_number} &8- &f{role_name}", "&7Permiso: {permission_name}", "", "{permission_status}", "{edit_hint}"), statusPh));
            }
        }

        if (editable) {
            inv.setItem(nativeSlot("menus.permissions-edit.items.reset.slot", 50), nativeItem("menus.permissions-edit.items.reset", Material.REDSTONE_BLOCK, "&c&lResetear permisos", List.of("", "&7Borra la configuración custom", "&7de este clan y vuelve a", "&7los permisos por defecto.", "", "&eClick para resetear."), basePh));
        }
        nav(inv, page, keys.size(), rowsPerPage, menuHolder, clan.id());
        setBackItem(inv, menuHolder, 49, "&6&lVolver", List.of(editable ? "&7Regresa a ajustes." : "&7Regresa al tablero de información."));
        openNativeInventory(player, inv);
    }

    private void openJoinRequestsMenu(Player player, int page) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        cleanupInvalidJoinRequests(clan.id());
        List<ClanJoinRequest> requests = getJoinRequests(clan.id(), page, GUI_PAGE_SIZE);
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("joinrequests", page, clan.id(), null, -1), 54, nativeTitle("join-requests", "&8Solicitudes &b{id}", clanPlaceholders(clan)));
        fill(inv);
        boolean canManage = can(member, "join-requests");
        for (int i = 0; i < requests.size(); i++) {
            ClanJoinRequest req = requests.get(i);
            Player online = Bukkit.getPlayer(req.uuid());
            Map<String, String> ph = new HashMap<>();
            ph.put("player", req.name());
            ph.put("status", online != null ? "&aConectado" : "&cDesconectado");
            ph.put("requested", date(req.requestedAt()));
            PlayerProfileSnapshot profile = resolvePlayerProfile(req.uuid(), req.name());
            ph.put("level", profile.level());
            ph.put("race", profile.race());
            ph.putAll(resolveMDVSocialTitlePlaceholders(req.uuid()));
            ph.put("manage_hint", canManage ? "&aClick izquierdo: aceptar|&cClick derecho: borrar" : "&8No tienes rango para gestionar.");

            OfflinePlayer requestPlayer = Bukkit.getOfflinePlayer(req.uuid());
            ItemStack head = nativeItem("menus.join-requests.request-head", Material.PLAYER_HEAD, "&e{player}", List.of("&7Estado: {status}", "&7Título: &r{title_colored}", "&7Solicitud: &f{requested}", "&7Nivel: &e{level}", "&7Raza: &d{race}", "", "{manage_hint}"), ph, requestPlayer);
            if (head.getItemMeta() instanceof SkullMeta meta) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(req.uuid()));
                head.setItemMeta(meta);
            }
            inv.setItem(contentSlot(i), head);
        }
        nav(inv, page, countJoinRequests(clan.id()), GUI_PAGE_SIZE, "joinrequests", clan.id());
        setBackItem(inv, "joinrequests", 49, "&6&lVolver", List.of("&7Regresa a Información."));
        openNativeInventory(player, inv);
    }


    private void openMemberActionMenu(Player player, UUID targetUuid) throws SQLException {
        Member actor = requireMember(player); if (actor == null) return;
        Optional<Member> targetOpt = getMember(targetUuid);
        if (targetOpt.isEmpty() || targetOpt.get().clanId() != actor.clanId()) { msg(player, "&cEse jugador ya no está en tu clan."); return; }
        Member target = targetOpt.get();
        Map<String, String> ph = memberPlaceholders(target, actor.clanId(), player);
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("memberaction", 1, actor.clanId(), target.uuid(), -1), 27, nativeTitle("member-action", "&8Miembro &b{player}", ph));
        fill(inv);
        if (nativeConfiguredItemEnabled("menus.member-action.items.member")) {
            inv.setItem(nativeSlot("menus.member-action.items.member.slot", 4), memberHead(target, actor.clanId(), player));
        }
        if (nativeConfiguredItemEnabled("menus.member-action.items.mail")) {
            inv.setItem(nativeSlot("menus.member-action.items.mail.slot", 10), nativeItem("menus.member-action.items.mail", Material.MAP, "&d&lCorreo personal", List.of("", "&7Abre/usa MDVSocial para", "&7mandarle una carta personal.", "", "&eClick para instrucciones."), ph));
        }
        if (nativeConfiguredItemEnabled("menus.member-action.items.promote")) {
            inv.setItem(nativeSlot("menus.member-action.items.promote.slot", 11), canModifyMember(actor, target, "promote") ? nativeItem("menus.member-action.items.promote", Material.LIME_DYE, "&a&lPromover", List.of("", "&7Sube un rango al miembro.", "", "&eClick para promover."), ph) : lockedItem("&7Promover", "&cNo tienes rango para esta función."));
        }
        if (nativeConfiguredItemEnabled("menus.member-action.items.demote")) {
            inv.setItem(nativeSlot("menus.member-action.items.demote.slot", 12), canModifyMember(actor, target, "demote") ? nativeItem("menus.member-action.items.demote", Material.YELLOW_DYE, "&e&lDegradar", List.of("", "&7Baja un rango al miembro.", "", "&eClick para degradar."), ph) : lockedItem("&7Degradar", "&cNo tienes rango para esta función."));
        }
        if (nativeConfiguredItemEnabled("menus.member-action.items.kick")) {
            inv.setItem(nativeSlot("menus.member-action.items.kick.slot", 14), canModifyMember(actor, target, "kick") ? nativeItem("menus.member-action.items.kick", Material.RED_DYE, "&c&lExpulsar", List.of("", "&7Expulsa al miembro del clan.", "&cAcción delicada.", "", "&eClick para expulsar."), ph) : lockedItem("&7Expulsar", "&cNo tienes rango para esta función."));
        }
        // 1.10.2+: el botón de perfil queda totalmente opcional.
        // Para mostrarlo, debe existir en YAML y tener enabled: true.
        if (nativeOptionalItemEnabled("menus.member-action.items.profile", false)) {
            inv.setItem(nativeSlot("menus.member-action.items.profile.slot", 16), nativeItem("menus.member-action.items.profile", Material.BOOK, "&b&lVer perfil", List.of("", "&7Información básica del jugador.", "&7Más perfil se puede conectar", "&7con MDVSocial/MMOCore luego."), ph));
        }
        setBackItem(inv, "memberaction", 22, "&6&lVolver", List.of("&7Regresa a miembros."));
        openNativeInventory(player, inv);
    }


    private void openClanActionMenu(Player player, int targetClanId) throws SQLException {
        Optional<Clan> targetOpt = getClan(targetClanId);
        if (targetOpt.isEmpty()) { msg(player, "&cClan no encontrado."); return; }
        Clan target = targetOpt.get();
        Optional<Member> own = getMember(player.getUniqueId());
        Map<String, String> ph = clanPlaceholders(target);
        ph.put("entry", target.open() ? "&aAbierta" : "&cInvitación");
        String relationRaw = own.map(member -> {
            try { return getRelationBetween(member.clanId(), target.id()); }
            catch (SQLException e) { return REL_NEUTRAL; }
        }).orElse("NO_CLAN");
        String relationDisplay = "NO_CLAN".equals(relationRaw) ? "&8Sin clan" : relationText(relationRaw);
        ph.put("relation", relationDisplay);
        ph.put("relation_raw", relationRaw);
        ph.put("relation_line", "&7Relación actual: " + relationDisplay);
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("clanaction", 1, target.id(), null, -1), 27, nativeTitle("clan-action", "&8Clan &b{id}", ph));
        fill(inv);
        if (nativeConfiguredItemEnabled("menus.clan-action.items.summary")) {
            inv.setItem(nativeSlot("menus.clan-action.items.summary.slot", 4), clanBannerItem(target,
                    applyPlaceholders(configString("menus.clan-action.items.summary.name", "&8[&b{id}&8] &f{name}"), ph),
                    lines("menus.clan-action.items.summary.lore", List.of("&7Miembros: &e{members}", "&7Entrada: {entry}", "&7Fuerza: &6{strength}"), ph), ph));
        }
        if (own.isEmpty()) {
            if (target.open() && nativeConfiguredItemEnabled("menus.clan-action.items.join")) {
                inv.setItem(nativeSlot("menus.clan-action.items.join.slot", 13),
                        nativeItem("menus.clan-action.items.join", Material.LIME_DYE, "&a&lUnirse", List.of("", "&7Este clan está abierto.", "", "&eClick para unirte."), ph));
            } else if (!target.open() && nativeConfiguredItemEnabled("menus.clan-action.items.request")) {
                inv.setItem(nativeSlot("menus.clan-action.items.request.slot", 13),
                        nativeItem("menus.clan-action.items.request", Material.PAPER, "&e&lEnviar solicitud", List.of("", "&7Este clan es por invitación.", "&7Enviarás una solicitud de ingreso.", "", "&eClick para solicitar."), ph));
            }
        } else {
            Member member = own.get();
            boolean canRel = can(member, "relation");
            boolean canMail = can(member, "mail-send");
            if (nativeConfiguredItemEnabled("menus.clan-action.items.info")) {
                inv.setItem(nativeSlot("menus.clan-action.items.info.slot", 10), nativeItem("menus.clan-action.items.info", Material.BOOK, "&b&lVer info", List.of("", "&eClick para ver información."), ph));
            }
            if (nativeConfiguredItemEnabled("menus.clan-action.items.ally")) {
                inv.setItem(nativeSlot("menus.clan-action.items.ally.slot", 11), canRel ? nativeItem("menus.clan-action.items.ally", Material.BLUE_DYE, "&9&lProponer alianza", List.of("", "&7Envía o acepta una solicitud", "&7de alianza diplomática.", "&8Si son enemigos, requiere aceptación.", "", "&eClick para establecer."), ph) : lockedItem("&7Proponer alianza", "&cRequiere rango alto."));
            }
            if (nativeConfiguredItemEnabled("menus.clan-action.items.enemy")) {
                inv.setItem(nativeSlot("menus.clan-action.items.enemy.slot", 12), canRel ? nativeItem("menus.clan-action.items.enemy", Material.RED_DYE, "&c&lDeclarar enemigo", List.of("", "&7Marca este clan como enemigo.", "", "&eClick para establecer."), ph) : lockedItem("&7Declarar enemigo", "&cRequiere rango alto."));
            }
            if (nativeConfiguredItemEnabled("menus.clan-action.items.neutral")) {
                inv.setItem(nativeSlot("menus.clan-action.items.neutral.slot", 14), canRel ? nativeItem("menus.clan-action.items.neutral", Material.GRAY_DYE, "&7&lVolver neutral", List.of("", "&7Quita alianza o solicita paz", "&7si ambos clanes son enemigos.", "&8La paz enemiga requiere aceptación.", "", "&eClick para establecer."), ph) : lockedItem("&7Volver neutral", "&cRequiere rango alto."));
            }
            if (nativeConfiguredItemEnabled("menus.clan-action.items.mail")) {
                inv.setItem(nativeSlot("menus.clan-action.items.mail.slot", 15), canMail ? nativeItem("menus.clan-action.items.mail", Material.WRITABLE_BOOK, "&d&lCorreo de clan", List.of("", "&7Escribe un correo formal", "&7al buzón de este clan.", "", "&eClick para escribir."), ph) : lockedItem("&7Correo de clan", "&cRequiere rango alto."));
            }
        }
        setBackItem(inv, "clanaction", 22, "&6&lVolver", List.of("&7Regresa a la lista."));
        openNativeInventory(player, inv);
    }


    private Map<String, String> mailPlaceholders(ClanMail mail, Clan from) {
        Map<String, String> ph = new HashMap<>();
        ph.put("mail_id", String.valueOf(mail.id()));
        ph.put("sender", mail.senderName());
        ph.put("date", date(mail.sentAt()));
        ph.put("message", mail.message());
        ph.put("mail_type", mail.mailType() == null ? "NORMAL" : mail.mailType());
        ph.put("from_id", from == null ? "?" : from.tag());
        ph.put("from_name", from == null ? "Clan desconocido" : from.name());
        ph.put("relation_clan_id", String.valueOf(mail.relationClanId()));
        int mailLineLength = Math.max(20, getConfig().getInt("clan-mail.line-length", 45));
        int mailLinesLimit = Math.max(1, getConfig().getInt("clan-mail.lines", 10));
        List<String> messageLines = autoWrapText(mail.message(), mailLinesLimit, mailLineLength);
        ph.put("message_lines", String.join("|", messageLines));
        for (int i = 1; i <= 10; i++) ph.put("message_line_" + i, i <= messageLines.size() ? messageLines.get(i - 1) : "");
        return ph;
    }

    private boolean isAllianceRequest(ClanMail mail) {
        return mail != null && MAIL_ALLY_REQUEST.equalsIgnoreCase(mail.mailType()) && mail.relationClanId() > 0;
    }

    private boolean isNeutralityRequest(ClanMail mail) {
        return mail != null && MAIL_NEUTRAL_REQUEST.equalsIgnoreCase(mail.mailType()) && mail.relationClanId() > 0;
    }

    private void openMailActionMenu(Player player, int mailId) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Optional<ClanMail> mailOpt = getClanMail(member.clanId(), mailId);
        if (mailOpt.isEmpty()) { msg(player, "&cCorreo no encontrado."); return; }
        ClanMail mail = mailOpt.get();
        Optional<Clan> fromOpt = getClan(mail.fromClanId());
        Map<String, String> ph = mailPlaceholders(mail, fromOpt.orElse(null));
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("mailaction", 1, member.clanId(), null, mail.id()), 27, nativeTitle("mail-action", "&8Correo #{mail_id}", ph));
        fill(inv);
        if (nativeConfiguredItemEnabled("menus.mail-action.items.message")) {
            inv.setItem(nativeSlot("menus.mail-action.items.message.slot", 4), fromOpt.map(c -> clanBannerItem(c,
                    applyPlaceholders(configString("menus.mail-action.items.message.name", "&6&l#&f{mail_id} &8- &d{from_name}"), ph),
                    lines("menus.mail-action.items.message.lore", List.of("", "&7De: &d{from_name} &8[&6{from_id}&8]", "&7Mensajero: &e{sender}", "&7Fecha: &a{date}", "", "&6&lContenido:", "&f{message_line_1}", "&f{message_line_2}", "&f{message_line_3}", "&f{message_line_4}", "&f{message_line_5}", "&f{message_line_6}", "&f{message_line_7}", "&f{message_line_8}", "&f{message_line_9}", "&f{message_line_10}"), ph), ph))
                    .orElse(nativeItem("menus.mail-action.items.message", Material.PAPER, "&6&l#&f{mail_id}", List.of("&f{message_line_1}", "&f{message_line_2}", "&f{message_line_3}", "&f{message_line_4}", "&f{message_line_5}"), ph)));
        }
        if (isAllianceRequest(mail)) {
            if (nativeConfiguredItemEnabled("menus.mail-action.items.accept-ally")) {
                inv.setItem(nativeSlot("menus.mail-action.items.accept-ally.slot", 10), nativeItem("menus.mail-action.items.accept-ally", Material.LIME_DYE, "&a&lAceptar alianza", List.of("", "&7Acepta la alianza con", "&d{from_name} &8[&6{from_id}&8]&7.", "", "&eClick para aceptar."), ph));
            }
            if (nativeConfiguredItemEnabled("menus.mail-action.items.reject-ally")) {
                inv.setItem(nativeSlot("menus.mail-action.items.reject-ally.slot", 12), nativeItem("menus.mail-action.items.reject-ally", Material.RED_DYE, "&c&lRechazar alianza", List.of("", "&7Rechaza esta solicitud", "&7de alianza.", "", "&eClick para rechazar."), ph));
            }
        }
        if (isNeutralityRequest(mail)) {
            if (nativeConfiguredItemEnabled("menus.mail-action.items.accept-neutral")) {
                inv.setItem(nativeSlot("menus.mail-action.items.accept-neutral.slot", 10), nativeItem("menus.mail-action.items.accept-neutral", Material.LIME_DYE, "&a&lAceptar paz", List.of("", "&7Acepta volver a neutralidad", "&7con &d{from_name} &8[&6{from_id}&8]&7.", "", "&eClick para aceptar."), ph));
            }
            if (nativeConfiguredItemEnabled("menus.mail-action.items.reject-neutral")) {
                inv.setItem(nativeSlot("menus.mail-action.items.reject-neutral.slot", 12), nativeItem("menus.mail-action.items.reject-neutral", Material.RED_DYE, "&c&lRechazar paz", List.of("", "&7Rechaza esta solicitud", "&7de neutralidad.", "", "&eClick para rechazar."), ph));
            }
        }
        if (nativeConfiguredItemEnabled("menus.mail-action.items.reply")) {
            inv.setItem(nativeSlot("menus.mail-action.items.reply.slot", 14), can(member, "mail-send") && fromOpt.isPresent() ? nativeItem("menus.mail-action.items.reply", Material.WRITABLE_BOOK, "&d&lResponder", List.of("", "&7Escribe un correo al clan", "&d{from_name} &8[&6{from_id}&8]&7.", "", "&eClick para responder."), ph) : lockedItem("&7Responder", "&cRequiere rango alto."));
        }
        if (nativeConfiguredItemEnabled("menus.mail-action.items.delete")) {
            inv.setItem(nativeSlot("menus.mail-action.items.delete.slot", 16), can(member, "mail-delete") ? nativeItem("menus.mail-action.items.delete", Material.RED_DYE, "&c&lEliminar", List.of("", "&7Borra este correo del buzón.", "", "&eClick para eliminar."), ph) : lockedItem("&7Eliminar", "&cRequiere rango alto."));
        }
        setBackItem(inv, "mailaction", 22, "&6&lVolver", List.of("&7Regresa al buzón."));
        openNativeInventory(player, inv);
    }

    private boolean handleConfiguredBackClick(Player player, ClanMenuHolder holder, int slot) throws SQLException {
        int backSlot = configuredBackSlot(holder.menu(), defaultBackSlot(holder.menu()));
        if (backSlot < 0 || slot != backSlot) return false;
        executeBackAction(player, holder.menu());
        return true;
    }

    private void executeBackAction(Player player, String holderMenu) throws SQLException {
        String key = menuConfigKey(holderMenu);
        ConfigurationSection sec = backSection(key);
        String action = sec == null ? "NATIVE" : sec.getString("action", "NATIVE");
        action = action == null ? "NATIVE" : action.trim().toUpperCase(Locale.ROOT);
        String target = backTargetForPlayer(player, sec, "target");
        String command = sec == null ? "" : sec.getString("command", "");

        if (action.equals("CLOSE") || action.equals("CERRAR")) {
            player.closeInventory();
            return;
        }
        if (action.equals("COMMAND") || action.equals("COMANDO")) {
            player.closeInventory();
            if (command != null && !command.isBlank()) player.performCommand(stripSlash(command));
            return;
        }
        if (action.equals("MDVSOCIAL") || action.equals("SOCIAL") || action.equals("OPEN_MENU")) {
            player.closeInventory();
            if (target != null && !target.isBlank()) player.performCommand("social " + target);
            else if (command != null && !command.isBlank()) player.performCommand(stripSlash(command));
            else player.performCommand("social main");
            return;
        }
        if (action.equals("PREVIOUS") || action.equals("ANTERIOR")) {
            UUID uuid = player.getUniqueId();
            String previous = previousNativeMenu.remove(uuid);
            if (previous != null && !previous.isBlank() && !previous.equals(holderMenu)) {
                suppressHistoryOnce.add(uuid);
                openDynamicClanUi(player, uiNameFromHolder(previous), 1);
            } else {
                executeBackFallback(player, holderMenu, sec);
            }
            return;
        }

        if (target == null || target.isBlank()) target = defaultBackTarget(holderMenu);
        openDynamicClanUi(player, target, 1);
    }

    private void executeBackFallback(Player player, String holderMenu, ConfigurationSection sec) throws SQLException {
        String fallbackAction = sec == null ? "" : sec.getString("fallback-action", sec.getString("fallback", ""));
        fallbackAction = fallbackAction == null ? "" : fallbackAction.trim().toUpperCase(Locale.ROOT);
        String fallbackTarget = backTargetForPlayer(player, sec, "fallback-target");
        String fallbackCommand = sec == null ? "" : sec.getString("fallback-command", sec.getString("command", ""));

        if (fallbackAction.equals("MDVSOCIAL") || fallbackAction.equals("SOCIAL") || fallbackAction.equals("OPEN_MENU")) {
            player.closeInventory();
            if (fallbackTarget == null || fallbackTarget.isBlank()) fallbackTarget = defaultSocialBackTarget(player);
            player.performCommand("social " + fallbackTarget);
            return;
        }
        if (fallbackAction.equals("COMMAND") || fallbackAction.equals("COMANDO")) {
            player.closeInventory();
            if (fallbackCommand != null && !fallbackCommand.isBlank()) player.performCommand(stripSlash(fallbackCommand));
            return;
        }
        if (fallbackAction.equals("CLOSE") || fallbackAction.equals("CERRAR")) {
            player.closeInventory();
            return;
        }

        if ("clanlist".equals(holderMenu)) {
            player.closeInventory();
            player.performCommand("social " + defaultSocialBackTarget(player));
            return;
        }

        openDynamicClanUi(player, defaultBackTarget(holderMenu), 1);
    }

    private String backTargetForPlayer(Player player, ConfigurationSection sec, String baseKey) {
        if (sec == null) return "";
        boolean hasClan = false;
        try { hasClan = getMember(player.getUniqueId()).isPresent(); } catch (SQLException ignored) { }
        String specific = hasClan
                ? sec.getString(baseKey + "-with-clan", sec.getString(baseKey + "-con-clan", ""))
                : sec.getString(baseKey + "-without-clan", sec.getString(baseKey + "-sin-clan", ""));
        if (specific != null && !specific.isBlank()) return specific;
        return sec.getString(baseKey, sec.getString("target", ""));
    }

    private String defaultSocialBackTarget(Player player) {
        String withClan = getConfig().getString("native-menus.legacy-main.with-clan-target", "clan_con_clan");
        String withoutClan = getConfig().getString("native-menus.legacy-main.without-clan-target", "clan_sin_clan");
        try {
            return getMember(player.getUniqueId()).isPresent() ? withClan : withoutClan;
        } catch (SQLException ignored) {
            return withoutClan;
        }
    }

    private String stripSlash(String command) {
        if (command == null) return "";
        String out = command.trim();
        return out.startsWith("/") ? out.substring(1) : out;
    }

    private void setBackItem(Inventory inv, String holderMenu, int defaultSlot, String defName, List<String> defLore) {
        int slot = configuredBackSlot(holderMenu, defaultSlot);
        if (slot < 0 || slot >= inv.getSize()) return;
        inv.setItem(slot, backItem(holderMenu, defName, defLore));
    }

    private ItemStack backItem(String holderMenu, String defName, List<String> defLore) {
        String key = menuConfigKey(holderMenu);
        String path = backSection(key) != null ? "menus." + key + ".back" : "global.back";
        return nativeItem(path, Material.ARROW, defName, defLore, Map.of());
    }

    private ConfigurationSection backSection(String menuKey) {
        if (nativeMenus == null) return null;
        ConfigurationSection direct = nativeMenus.getConfigurationSection("menus." + menuKey + ".back");
        if (direct != null) return direct;
        return nativeMenus.getConfigurationSection("menus." + menuKey + ".navigation.back");
    }

    private int configuredBackSlot(String holderMenu, int defaultSlot) {
        String key = menuConfigKey(holderMenu);
        ConfigurationSection sec = backSection(key);
        return sec == null ? defaultSlot : sec.getInt("slot", defaultSlot);
    }

    private int defaultBackSlot(String holderMenu) {
        return switch (holderMenu) {
            case "noclan" -> -1;
            case "createclan", "hub", "gestion", "info", "relations", "storagehub", "memberaction", "clanaction", "mailaction", "rolesettings" -> 22;
            default -> 49;
        };
    }

    private String menuConfigKey(String holderMenu) {
        if (holderMenu == null) return "auto";
        if (holderMenu.startsWith("top:")) return "top";
        return switch (holderMenu) {
            case "noclan" -> "no-clan";
            case "createclan" -> "create";
            case "gestion" -> "management";
            case "storagehub" -> "storage";
            case "bases" -> "bases";
            case "clanlist" -> "clan-list";
            case "relationslist" -> "relations-list";
            case "mailbox" -> "mailbox";
            case "memberaction" -> "member-action";
            case "clanaction" -> "clan-action";
            case "mailaction" -> "mail-action";
            case "rolesettings" -> "role-settings";
            case "permissionsedit" -> "permissions-edit";
            case "joinrequests" -> "join-requests";
            default -> holderMenu;
        };
    }

    private String uiNameFromHolder(String holderMenu) {
        if (holderMenu == null) return "auto";
        if (holderMenu.startsWith("top:")) return holderMenu.replace("top:", "top_");
        return switch (holderMenu) {
            case "noclan" -> "sinclan";
            case "createclan" -> "crear";
            case "hub" -> "hub";
            case "gestion" -> "gestion";
            case "members" -> "miembros";
            case "info" -> "info";
            case "relations" -> "relaciones";
            case "storagehub" -> "almacen";
            case "bases" -> "bases";
            case "clanlist" -> "lista";
            case "relationslist" -> "relaciones_lista";
            case "mailbox" -> "correo";
            case "bajas" -> "bajas";
            case "logs" -> "logs";
            case "settings" -> "ajustes";
            case "rolesettings" -> "rangos";
            case "permissions" -> "permisos";
            case "permissionsedit" -> "permisos_editar";
            case "joinrequests" -> "solicitudes";
            case "memberaction" -> "miembros";
            case "clanaction" -> "lista";
            case "mailaction" -> "correo";
            default -> holderMenu;
        };
    }

    private String defaultBackTarget(String holderMenu) {
        if (holderMenu == null) return "auto";
        if (holderMenu.startsWith("top:")) return "relaciones";
        return switch (holderMenu) {
            case "createclan" -> "sinclan";
            case "hub" -> "auto";
            case "members", "info", "relations", "storagehub", "bases", "settings", "clanlist" -> "auto";
            case "relationslist", "bajas" -> "relaciones";
            case "mailbox", "logs", "joinrequests" -> "info";
            case "rolesettings", "permissionsedit" -> "ajustes";
            case "permissions" -> "info";
            case "memberaction" -> "miembros";
            case "clanaction" -> "lista";
            case "mailaction" -> "correo";
            default -> "auto";
        };
    }

    private void handleMenuClick(Player player, ClanMenuHolder holder, int slot, ClickType click) throws SQLException {
        String menu = holder.menu();
        if (handleConfiguredBackClick(player, holder, slot)) return;
        if (slot == 45 && holder.page() > 1) { openPaged(player, menu, holder.page() - 1); return; }
        if (slot == 53) { openPaged(player, menu, holder.page() + 1); return; }
        switch (menu) {
            case "main", "noclan", "hub", "gestion" -> handleMainClick(player, menu, slot);
            case "createclan" -> handleCreateClanMenuClick(player, slot);
            case "members" -> handleMembersClick(player, holder.page(), slot, click);
            case "info" -> handleInfoClick(player, slot, click);
            case "settings" -> handleSettingsClick(player, slot, click);
            case "rolesettings" -> handleRoleSettingsClick(player, slot);
            case "permissions" -> { }
            case "permissionsedit" -> handlePermissionsEditClick(player, holder.page(), slot, click);
            case "joinrequests" -> handleJoinRequestsClick(player, holder.page(), slot, click);
            case "memberaction" -> handleMemberActionClick(player, holder.targetUuid(), slot);
            case "clanaction" -> handleClanActionClick(player, holder.clanId(), slot);
            case "mailaction" -> handleMailActionClick(player, holder.mailId(), slot);
            case "relations" -> handleRelationsMenuClick(player, slot);
            case "storagehub" -> handleStorageHubClick(player, slot, click);
            case "bases" -> handleBasesClick(player, slot);
            case "clanlist" -> handleClanListClick(player, holder.page(), slot, click);
            case "relationslist" -> handleRelationsListClick(player, holder.page(), slot);
            case "mailbox" -> handleMailboxClick(player, holder.page(), slot, click);
            case "bajas" -> handleKillStatsClick(player, slot);
            case "logs" -> handleLogsClick(player, holder.page(), slot, click);
            default -> { if (menu.startsWith("top:")) handleTopGuiClick(player, menu, slot); }
        }
    }


    private void handleRelationsMenuClick(Player player, int slot) throws SQLException {
        if (slot == nativeSlot("menus.relations.items.list.slot", 11)) openRelationsListMenu(player, 1);
        else if (slot == nativeSlot("menus.relations.items.kills.slot", 13)) openKillStatsGui(player);
        else if (slot == nativeSlot("menus.relations.items.ranking.slot", 15)) openTopGui(player, "fuerza");
    }

    private void handleStorageHubClick(Player player, int slot, ClickType click) throws SQLException {
        if (slot == nativeSlot("menus.storage.items.storage.slot", 11)) { player.closeInventory(); handleStorage(player); }
        else if (slot == nativeSlot("menus.storage.items.bank.slot", 15)) {
            Member member = requireMember(player); if (member == null) return;
            boolean withdraw = click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT;
            if (withdraw) {
                if (!hasRank(player, member, "bank-withdraw")) return;
                pendingBankAmount.put(player.getUniqueId(), false);
                player.closeInventory();
                msg(player, "&7Escribe la cantidad que deseas &cretirar &7del banco del clan. Escribe &ccancelar &7para cancelar.");
            } else {
                if (!hasRank(player, member, "bank-deposit")) return;
                pendingBankAmount.put(player.getUniqueId(), true);
                player.closeInventory();
                msg(player, "&7Escribe la cantidad que deseas &adepositar &7en el banco del clan. Escribe &ccancelar &7para cancelar.");
            }
        }
    }


    private void handleBasesClick(Player player, int slot) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        int visibleBases = visibleBaseSlots(clan);
        for (int baseNumber = 1; baseNumber <= visibleBases; baseNumber++) {
            if (slot == baseTeleportSlot(baseNumber)) {
                if (baseNumber > maxBasesForClan(clan)) {
                    msg(player, "&cLa base &e#" + baseNumber + " &cestá bloqueada. Requiere tier &d" + tierRequiredForBaseName(baseNumber) + "&c.");
                    return;
                }
                if (getClanBase(clan.id(), baseNumber).isEmpty()) {
                    msg(player, "&cLa base &e#" + baseNumber + " &cno está establecida.");
                    return;
                }
                player.closeInventory();
                handleBase(player, new String[]{"base", String.valueOf(baseNumber)});
                return;
            }
            if (slot == baseSetSlot(baseNumber)) {
                if (baseNumber > maxBasesForClan(clan)) {
                    msg(player, "&cLa base &e#" + baseNumber + " &cestá bloqueada. Requiere tier &d" + tierRequiredForBaseName(baseNumber) + "&c.");
                    return;
                }
                if (!hasRank(player, member, "setbase")) return;
                player.closeInventory();
                handleSetBase(player, new String[]{"setbase", String.valueOf(baseNumber)});
                return;
            }
        }
    }

    private void handleCreateClanMenuClick(Player player, int slot) throws SQLException {
        if (slot == nativeSlot("menus.create.items.start.slot", 13)) {
            if (getMember(player.getUniqueId()).isPresent()) {
                msg(player, msgConfig("creation.already-in-clan", "&cYa perteneces a un clan."));
                player.closeInventory();
                return;
            }
            player.closeInventory();
            startClanCreateNamePrompt(player);
        } else if (slot == nativeSlot("menus.create.items.back.slot", 22)) openNoClanMenu(player);
        else if (slot == nativeSlot("menus.create.items.close.slot", 26)) player.closeInventory();
    }

    private void handleMainClick(Player player, String menu, int slot) throws SQLException {
        boolean hasClan = getMember(player.getUniqueId()).isPresent();
        if (!hasClan || menu.equals("noclan")) {
            if (slot == nativeSlot("menus.no-clan.items.list.slot", 11)) openClanListMenu(player, 1);
            else if (slot == nativeSlot("menus.no-clan.items.create.slot", 15)) openCreateClanMenu(player);
            else if (slot == nativeSlot("menus.no-clan.items.close.slot", 22)) player.closeInventory();
            return;
        }
        if (menu.equals("gestion")) {
            if (slot == nativeSlot("menus.management.items.summary.slot", 4)) openFullClanHub(player);
            else if (slot == nativeSlot("menus.management.items.base.slot", 10)) openBasesMenu(player);
            else if (slot == nativeSlot("menus.management.items.setbase.slot", 11)) openBasesMenu(player);
            else if (slot == nativeSlot("menus.management.items.bank.slot", 13)) { player.closeInventory(); player.performCommand("clan banco"); }
            else if (slot == nativeSlot("menus.management.items.storage.slot", 14)) { player.closeInventory(); handleStorage(player); }
            else if (slot == nativeSlot("menus.management.items.banner.slot", 15)) { player.closeInventory(); player.performCommand("clan estandarte ver"); }
            else if (slot == nativeSlot("menus.management.items.logs.slot", 16)) openLogsGui(player, 1);
            else if (slot == nativeSlot("menus.management.items.close.slot", 26)) player.closeInventory();
            return;
        }
        if (slot == nativeSlot("menus.hub.items.members.slot", 10)) openMembersMenu(player, 1);
        else if (slot == nativeSlot("menus.hub.items.info.slot", 11)) openClanInfoMenu(player);
        else if (slot == nativeSlot("menus.hub.items.relations.slot", 12)) openRelationsMenu(player);
        else if (slot == nativeSlot("menus.hub.items.storage.slot", 13)) openStorageHubMenu(player);
        else if (slot == nativeSlot("menus.hub.items.base.slot", 14)) openBasesMenu(player);
        else if (slot == nativeSlot("menus.hub.items.clan-list.slot", 15)) openClanListMenu(player, 1);
        else if (slot == nativeSlot("menus.hub.items.leave.slot", 16)) {
            runAfterGuiClick(player, () -> {
                player.closeInventory();
                try {
                    if (handleLeave(player)) {
                        Bukkit.getScheduler().runTaskLater(this, () -> player.performCommand("social"), 1L);
                    }
                } catch (SQLException e) {
                    getLogger().warning("No se pudo procesar la salida del clan de " + player.getName() + ": " + e.getMessage());
                    msg(player, "&cNo se pudo salir del clan. Inténtalo nuevamente.");
                }
            });
        }
        else if (slot == nativeSlot("menus.hub.items.settings.slot", 17)) openSettingsMenu(player);
        else if (slot == nativeSlot("menus.hub.items.close.slot", 26)) player.closeInventory();
    }



    private void handleMembersClick(Player player, int page, int slot, ClickType click) throws SQLException {
        Member actor = requireMember(player); if (actor == null) return;
        List<Member> members = getMembers(actor.clanId());
        int index = pageIndexFromSlot(page, slot);
        if (index < 0 || index >= members.size()) return;
        Member target = members.get(index);
        openMemberActionMenu(player, target.uuid());
    }


    private void runAfterGuiClick(Player player, Runnable runnable) {
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                runnable.run();
            } finally {
                try { player.updateInventory(); } catch (Throwable ignored) { }
            }
        });
    }

    private void beginDescriptionEdit(Player player) {
        pendingDescriptionEdit.add(player.getUniqueId());
        runAfterGuiClick(player, () -> {
            player.closeInventory();
            msg(player, "&7Escribe la descripción pública del clan. Se dividirá automáticamente en líneas. Escribe &ccancelar &7para cancelar.");
        });
    }

    private void handleInfoClick(Player player, int slot, ClickType click) throws SQLException {
        if (slot == nativeSlot("menus.info.items.banner.slot", 10)) { player.closeInventory(); player.performCommand("clan estandarte ver"); }
        else if (slot == nativeSlot("menus.info.items.board.slot", 12)) {
            if (click.isShiftClick()) {
                Member member = requireMember(player);
                if (member == null) return;
                if (!hasRank(player, member, "board-edit")) return;
                pendingBoardEdit.add(player.getUniqueId());
                player.closeInventory();
                msg(player, "&7Escribe el nuevo tablero. Usa &e| &7para separar líneas. &cCancelar &7para cancelar.");
            } else player.performCommand("clan tablero ver");
        }
        else if (nativeSection("menus.info.items.description") != null && slot == nativeSlot("menus.info.items.description.slot", 15)) {
            runAfterGuiClick(player, () -> {
                player.closeInventory();
                player.performCommand("clan descripcion ver");
            });
        }
        else if (slot == nativeSlot("menus.info.items.requests.slot", 13)) openJoinRequestsMenu(player, 1);
        else if (slot == nativeSlot("menus.info.items.mailbox.slot", 14)) openMailboxMenu(player, 1);
        else if (slot == nativeSlot("menus.info.items.logs.slot", 16)) openLogsGui(player, 1);
        else if (slot == nativeSlot("menus.info.items.permissions.slot", 20)) openPermissionsMenu(player, 1);
    }



    private void handleClanListClick(Player player, int page, int slot, ClickType click) throws SQLException {
        List<Clan> clans = listClans();
        int index = pageIndexFromSlot(page, slot);
        if (index < 0 || index >= clans.size()) return;
        Clan target = clans.get(index);
        Optional<Member> own = getMember(player.getUniqueId());
        if (own.isEmpty()) {
            player.closeInventory();
            if (target.open()) player.performCommand("clan unirse " + target.tag());
            else player.performCommand("clan unirse " + target.tag());
            return;
        }
        openClanActionMenu(player, target.id());
    }


    private void handleRelationsListClick(Player player, int page, int slot) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        List<ClanRelationView> relations = getVisibleRelations(member.clanId());
        int index = pageIndexFromSlot(page, slot);
        if (index < 0 || index >= relations.size()) return;
        openClanActionMenu(player, relations.get(index).clan().id());
    }

    private void handleMailboxClick(Player player, int page, int slot, ClickType click) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        List<ClanMail> mails = getClanMails(member.clanId(), page, GUI_PAGE_SIZE);
        int index = pageIndexFromSlot(page, slot);
        if (index < 0 || index >= mails.size()) return;
        openMailActionMenu(player, mails.get(index).id());
    }



    private List<String> bannerSettingsLore(Map<String, String> ph) {
        List<String> modern = List.of(
                "",
                "&7Abre el editor de estandarte",
                "&7oficial del clan.",
                "",
                "&eClick para abrir."
        );
        List<String> configured = lines("menus.settings.items.banner.lore", modern, ph);
        String joined = configured.stream().map(ChatColor::stripColor).collect(Collectors.joining(" ")).toLowerCase(Locale.ROOT);
        if (joined.contains("banner en tu mano") || joined.contains("guardar banner en mano") || joined.contains("click derecho: quitar")) {
            return modern.stream().map(line -> applyPlaceholders(line, ph)).collect(Collectors.toList());
        }
        return configured;
    }

    private void handleSettingsClick(Player player, int slot, ClickType click) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        if (slot == nativeSlot("menus.settings.items.rename-name.slot", 10)) {
            if (!hasRank(player, member, "rename-clan")) return;
            pendingClanNameEdit.add(player.getUniqueId()); player.closeInventory();
            msg(player, "&7Escribe el nuevo &enombre &7del clan. Escribe &ccancelar &7para cancelar.");
        } else if (slot == nativeSlot("menus.settings.items.rename-id.slot", 11)) {
            if (!hasRank(player, member, "rename-tag")) return;
            pendingClanTagEdit.add(player.getUniqueId()); player.closeInventory();
            msg(player, "&7Escribe el nuevo &eID &7del clan. Escribe &ccancelar &7para cancelar.");
        } else if (slot == nativeSlot("menus.settings.items.roles.slot", 12)) openRoleSettingsMenu(player);
        else if (nativeSection("menus.settings.items.description") != null && slot == nativeSlot("menus.settings.items.description.slot", 18)) {
            if (!hasRank(player, member, "description-edit")) return;
            if (click == ClickType.RIGHT) {
                runAfterGuiClick(player, () -> {
                    player.closeInventory();
                    player.performCommand("clan descripcion limpiar");
                });
            } else {
                beginDescriptionEdit(player);
            }
        }
        else if (slot == nativeSlot("menus.settings.items.banner.slot", 13)) openBannerEditor(player);
        else if (slot == nativeSlot("menus.settings.items.permissions.slot", 14)) openPermissionsEditMenu(player, 1);
        else if (slot == nativeSlot("menus.settings.items.open.slot", 15)) { player.closeInventory(); Clan clan = getPlayerClan(player.getUniqueId()).orElseThrow(); player.performCommand("clan abierto " + (clan.open() ? "off" : "on")); }
        else if (slot == nativeSlot("menus.settings.items.disband.slot", 16)) { player.closeInventory(); msg(player, "&cPara disolver usa: &e/clan disolver confirmar"); }
        else if (slot == nativeSlot("menus.settings.items.tier-upgrade.slot", 20)) { handleTierUpgrade(player); openSettingsMenu(player); }
        else if (nativeSection("menus.settings.items.leave") != null && slot == nativeSlot("menus.settings.items.leave.slot", 24)) {
            runAfterGuiClick(player, () -> {
                player.closeInventory();
                try {
                    if (handleLeave(player)) {
                        Bukkit.getScheduler().runTaskLater(this, () -> player.performCommand("social"), 1L);
                    }
                } catch (SQLException e) {
                    getLogger().warning("No se pudo procesar la salida del clan de " + player.getName() + ": " + e.getMessage());
                    msg(player, "&cNo se pudo salir del clan. Inténtalo nuevamente.");
                }
            });
        }
    }


    private void handleRoleSettingsClick(Player player, int slot) throws SQLException {
        int[] defaults = {10, 11, 12, 13, 14};
        for (int role = minRole(); role <= maxRole() && role < defaults.length; role++) {
            if (slot == nativeSlot("menus.role-settings.role-item.slot-" + role, defaults[role])) {
                Member member = requireMember(player); if (member == null) return;
                if (!hasRank(player, member, "rename-role")) return;
                pendingRoleNameEdit.put(player.getUniqueId(), role);
                player.closeInventory();
                msg(player, "&7Escribe el nuevo nombre para el rango &e" + role + "&7. Escribe &ccancelar &7para cancelar.");
                return;
            }
        }
    }


    private void handlePermissionsEditClick(Player player, int page, int slot, ClickType click) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        if (!hasRank(player, member, "permissions-edit")) return;
        if (slot == nativeSlot("menus.permissions-edit.items.reset.slot", 50)) {
            resetClanRolePermissions(member.clanId());
            logAction(member.clanId(), player, "PERMISOS", "Reseteó los permisos custom del clan");
            msg(player, "&aPermisos del clan reseteados a los valores por defecto.");
            openPermissionsEditMenu(player, page);
            return;
        }
        List<String> keys = permissionKeys();
        String path = permissionMenuPath(true);
        int rowsPerPage = Math.max(1, Math.min(5, nativeMenus == null ? 4 : nativeMenus.getInt(path + ".table.rows-per-page", 4)));
        int[] headerSlots = nativeIntArray(path + ".table.role-header-slots", new int[]{3,4,5,6,7});
        int[] rowStarts = nativeIntArray(path + ".table.permission-row-start-slots", new int[]{9,18,27,36});
        rowsPerPage = Math.min(rowsPerPage, Math.max(1, rowStarts.length));
        int start = Math.max(0, page - 1) * rowsPerPage;
        for (int row = 0; row < rowsPerPage && start + row < keys.size() && row < rowStarts.length; row++) {
            int relative = slot - (rowStarts[row] + 3);
            if (relative < 0 || relative >= headerSlots.length) continue;
            int role = minRole() + relative;
            if (role < minRole() || role > maxRole()) continue;
            String key = keys.get(start + row);
            if (role >= maxRole()) {
                msg(player, "&cEl líder siempre tiene todos los permisos y no se puede bloquear.");
                return;
            }
            if (!player.hasPermission("mdvclans.admin") && member.role() < maxRole() && role >= member.role()) {
                msg(player, "&cSolo puedes modificar permisos de rangos menores al tuyo.");
                return;
            }
            boolean current = isClanRolePermissionAllowed(member.clanId(), key, role);
            setClanRolePermission(member.clanId(), key, role, !current);
            logAction(member.clanId(), player, "PERMISOS", (current ? "Bloqueó" : "Permitió") + " " + key + " para rango " + role);
            openPermissionsEditMenu(player, page);
            return;
        }
    }

    private void handleJoinRequestsClick(Player player, int page, int slot, ClickType click) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        List<ClanJoinRequest> requests = getJoinRequests(member.clanId(), page, GUI_PAGE_SIZE);
        int index = pageIndexFromSlot(page, slot);
        if (index < 0 || index >= requests.size()) return;
        ClanJoinRequest request = requests.get(index);
        if (!hasRank(player, member, "join-requests")) return;
        if (click == ClickType.RIGHT) {
            deleteJoinRequest(member.clanId(), request.uuid());
            logAction(member.clanId(), player, "SOLICITUD", "Rechazó solicitud de " + request.name());
            msg(player, "&cSolicitud borrada.");
        } else {
            acceptJoinRequest(player, member.clanId(), request);
        }
        openJoinRequestsMenu(player, page);
    }

    private void handleMemberActionClick(Player player, UUID targetUuid, int slot) throws SQLException {
        Member actor = requireMember(player); if (actor == null || targetUuid == null) return;
        Optional<Member> targetOpt = getMember(targetUuid);
        if (targetOpt.isEmpty() || targetOpt.get().clanId() != actor.clanId()) { msg(player, "&cEse miembro ya no está en tu clan."); return; }
        Member target = targetOpt.get();
        if (nativeConfiguredItemClicked("menus.member-action.items.mail", slot, 10)) {
            player.closeInventory();
            pendingPersonalMail.put(player.getUniqueId(), target.name());
            msg(player, "&7Escribe el correo personal para &e" + target.name() + "&7. Escribe &ccancelar &7para cancelar.");
        }
        else if (nativeConfiguredItemClicked("menus.member-action.items.promote", slot, 11)) { player.closeInventory(); player.performCommand("clan promover " + target.name()); }
        else if (nativeConfiguredItemClicked("menus.member-action.items.demote", slot, 12)) { player.closeInventory(); player.performCommand("clan degradar " + target.name()); }
        else if (nativeConfiguredItemClicked("menus.member-action.items.kick", slot, 14)) { player.closeInventory(); player.performCommand("clan expulsar " + target.name()); }
    }


    private void handleClanActionClick(Player player, int targetClanId, int slot) throws SQLException {
        Optional<Clan> targetOpt = getClan(targetClanId);
        if (targetOpt.isEmpty()) { msg(player, "&cClan no encontrado."); return; }
        Clan target = targetOpt.get();
        Optional<Member> own = getMember(player.getUniqueId());
        if (own.isEmpty()) {
            if ((target.open() && nativeConfiguredItemClicked("menus.clan-action.items.join", slot, 13))
                    || (!target.open() && nativeConfiguredItemClicked("menus.clan-action.items.request", slot, 13))) {
                player.closeInventory();
                player.performCommand("clan unirse " + target.tag());
            }
            return;
        }
        if (nativeConfiguredItemClicked("menus.clan-action.items.info", slot, 10)) { player.closeInventory(); player.performCommand("clan info " + target.tag()); }
        else if (nativeConfiguredItemClicked("menus.clan-action.items.ally", slot, 11)) { player.closeInventory(); player.performCommand("clan relacion " + target.tag() + " aliado"); }
        else if (nativeConfiguredItemClicked("menus.clan-action.items.enemy", slot, 12)) { player.closeInventory(); player.performCommand("clan relacion " + target.tag() + " enemigo"); }
        else if (nativeConfiguredItemClicked("menus.clan-action.items.neutral", slot, 14)) { player.closeInventory(); player.performCommand("clan relacion " + target.tag() + " neutral"); }
        else if (nativeConfiguredItemClicked("menus.clan-action.items.mail", slot, 15)) { pendingClanMailReply.put(player.getUniqueId(), target.tag()); player.closeInventory(); msg(player, "&7Escribe el correo para el clan &e" + target.tag() + "&7. Escribe &ccancelar &7para cancelar."); }
    }


    private void handleMailActionClick(Player player, int mailId, int slot) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Optional<ClanMail> mailOpt = getClanMail(member.clanId(), mailId);
        if (mailOpt.isEmpty()) { msg(player, "&cCorreo no encontrado."); return; }
        ClanMail mail = mailOpt.get();
        if (nativeConfiguredItemClicked("menus.mail-action.items.accept-ally", slot, 10) && isAllianceRequest(mail)) {
            if (!hasRank(player, member, "relation")) return;
            Optional<Clan> requester = getClan(mail.relationClanId());
            Optional<Clan> ownClan = getClan(member.clanId());
            if (requester.isEmpty() || ownClan.isEmpty()) { msg(player, "&cNo se pudo encontrar el clan solicitante."); return; }
            acceptAlliance(ownClan.get(), requester.get(), player);
            deleteClanMail(member.clanId(), mail.id());
            openMailboxMenu(player, 1);
            return;
        }
        if (nativeConfiguredItemClicked("menus.mail-action.items.reject-ally", slot, 12) && isAllianceRequest(mail)) {
            if (!hasRank(player, member, "relation")) return;
            Optional<Clan> requester = getClan(mail.relationClanId());
            Optional<Clan> ownClan = getClan(member.clanId());
            if (requester.isEmpty() || ownClan.isEmpty()) { msg(player, "&cNo se pudo encontrar el clan solicitante."); return; }
            rejectAllianceRequest(ownClan.get(), requester.get(), player);
            deleteClanMail(member.clanId(), mail.id());
            openMailboxMenu(player, 1);
            return;
        }
        if (nativeConfiguredItemClicked("menus.mail-action.items.accept-neutral", slot, 10) && isNeutralityRequest(mail)) {
            if (!hasRank(player, member, "relation")) return;
            Optional<Clan> requester = getClan(mail.relationClanId());
            Optional<Clan> ownClan = getClan(member.clanId());
            if (requester.isEmpty() || ownClan.isEmpty()) { msg(player, "&cNo se pudo encontrar el clan solicitante."); return; }
            acceptNeutrality(ownClan.get(), requester.get(), player);
            deleteClanMail(member.clanId(), mail.id());
            openMailboxMenu(player, 1);
            return;
        }
        if (nativeConfiguredItemClicked("menus.mail-action.items.reject-neutral", slot, 12) && isNeutralityRequest(mail)) {
            if (!hasRank(player, member, "relation")) return;
            Optional<Clan> requester = getClan(mail.relationClanId());
            Optional<Clan> ownClan = getClan(member.clanId());
            if (requester.isEmpty() || ownClan.isEmpty()) { msg(player, "&cNo se pudo encontrar el clan solicitante."); return; }
            rejectNeutralityRequest(ownClan.get(), requester.get(), player);
            deleteClanMail(member.clanId(), mail.id());
            openMailboxMenu(player, 1);
            return;
        }
        if (nativeConfiguredItemClicked("menus.mail-action.items.reply", slot, 14)) {
            if (!hasRank(player, member, "mail-send")) return;
            Optional<Clan> from = getClan(mail.fromClanId());
            if (from.isEmpty()) return;
            pendingClanMailReply.put(player.getUniqueId(), from.get().tag());
            player.closeInventory();
            msg(player, "&7Escribe la respuesta para &e" + from.get().tag() + "&7. Escribe &ccancelar &7para cancelar.");
        } else if (nativeConfiguredItemClicked("menus.mail-action.items.delete", slot, 16)) {
            if (!hasRank(player, member, "mail-delete")) return;
            deleteClanMail(member.clanId(), mail.id());
            msg(player, "&aCorreo eliminado.");
            openMailboxMenu(player, 1);
        }
    }


    private void handleLogsClick(Player player, int page, int slot, ClickType click) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        String[] keys = {"all", "miembros", "banco", "diplomacia", "correo", "combate", "admin"};
        for (String key : keys) {
            int def = switch (key) { case "all" -> 1; case "miembros" -> 2; case "banco" -> 3; case "diplomacia" -> 4; case "correo" -> 5; case "combate" -> 6; default -> 7; };
            if (slot == nativeSlot("menus.logs.filters." + key + ".slot", def)) { openLogsGui(player, 1, key); return; }
        }
        int index = pageIndexFromSlot(page, slot);
        if (index < 0) return;
        String filter = currentLogFilter.getOrDefault(player.getUniqueId(), "all");
        List<ClanLog> logs = getLogs(member.clanId(), filter, page, GUI_PAGE_SIZE);
        if (index >= logs.size()) return;
        ClanLog log = logs.get(index);
        if (click == ClickType.RIGHT) {
            if (!player.hasPermission("mdvclans.admin")) { msg(player, "&cSolo un admin puede borrar logs desde la GUI."); return; }
            if (deleteLog(member.clanId(), log.id())) msg(player, "&aLog #" + log.id() + " eliminado.");
            openLogsGui(player, page, filter);
        }
    }

    private void handleKillStatsClick(Player player, int slot) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        List<ClanTopEntry> suffered = getTopKillersAgainst(member.clanId(), GUI_PAGE_SIZE);
        int index = pageIndexFromSlot(1, slot);
        if (index < 0 || index >= suffered.size()) return;
        openClanActionMenu(player, suffered.get(index).clan().id());
    }

    private void handleTopGuiClick(Player player, String menu, int slot) throws SQLException {
        if (slot == nativeSlot("menus.top.items.force.slot", 3)) { openTopGui(player, "fuerza"); return; }
        if (slot == nativeSlot("menus.top.items.kills.slot", 4)) { openTopGui(player, "kills"); return; }
        if (slot == nativeSlot("menus.top.items.bank.slot", 5)) { openTopGui(player, "banco"); return; }

        String mode = menu != null && menu.startsWith("top:") ? menu.substring("top:".length()) : "fuerza";
        if (!equalsAny(mode, "fuerza", "kills", "banco")) mode = "fuerza";
        List<ClanTopEntry> entries = topEntries(mode);
        int index = pageIndexFromSlot(1, slot);
        if (index < 0 || index >= entries.size()) return;
        openClanActionMenu(player, entries.get(index).clan().id());
    }


    private void openPaged(Player player, String menu, int page) throws SQLException {
        if (menu.equals("members")) openMembersMenu(player, page);
        else if (menu.equals("clanlist")) openClanListMenu(player, page);
        else if (menu.equals("relationslist")) openRelationsListMenu(player, page);
        else if (menu.equals("mailbox")) openMailboxMenu(player, page);
        else if (menu.equals("joinrequests")) openJoinRequestsMenu(player, page);
        else if (menu.equals("permissions")) openPermissionsMenu(player, page);
        else if (menu.equals("permissionsedit")) openPermissionsEditMenu(player, page);
        else if (menu.equals("bases")) openBasesMenu(player);
        else openMainMenu(player);
    }

    private void openNativeInventory(Player player, Inventory inv) {
        if (inv != null && inv.getHolder() instanceof ClanMenuHolder holder) {
            String opened = holder.menu();
            UUID uuid = player.getUniqueId();
            if (suppressHistoryOnce.remove(uuid)) {
                currentNativeMenu.put(uuid, opened);
            } else {
                String current = currentNativeMenu.get(uuid);
                if (current != null && !current.equals(opened)) previousNativeMenu.put(uuid, current);
                currentNativeMenu.put(uuid, opened);
            }
        }
        player.openInventory(inv);
    }

    private ItemStack memberHead(Member member, int clanId, Player viewer) throws SQLException {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(member.uuid()));
        Map<String, String> ph = memberPlaceholders(member, clanId, viewer);
        OfflinePlayer off = Bukkit.getOfflinePlayer(member.uuid());
        String name = nativeMenus == null ? "&e{player} &8(&b{role_name}&8)" : nativeMenus.getString("menus.members.member-head.name", "&e{player} &8(&b{role_name}&8)");
        meta.setDisplayName(color(applyPlaceholdersAndPapi(name, ph, off)));
        List<String> configured = lines("menus.members.member-head.lore", List.of(
                "&7Rango: &b{role_number} &8- &f{role_name}",
                "&7Estado: {status}",
                "&7Última vez: &f{last_seen}",
                "&7Ingreso: &f{joined}",
                "&7Título: &r{title_colored}",
                "&7Nivel: &e{level}",
                "&7Raza: &d{race}",
                "",
                "&eClick para abrir opciones."), ph, off);
        meta.setLore(configured.stream().map(this::color).collect(Collectors.toList()));
        item.setItemMeta(meta);
        return item;
    }

    private String permissionName(String key) {
        String configured = configString("permission-labels." + key + ".name", "");
        if (configured != null && !configured.isBlank()) return configured;
        return switch (key) {
            case "invite" -> "&aInvitar miembros";
            case "kick" -> "&cExpulsar miembros";
            case "promote" -> "&aPromover miembros";
            case "demote" -> "&eDegradar miembros";
            case "set-rank" -> "&dAsignar rango";
            case "rename-role" -> "&dRenombrar rangos";
            case "setbase" -> "&bFijar base";
            case "relation" -> "&9Gestionar relaciones";
            case "open" -> "&aAbrir/cerrar clan";
            case "bank-deposit" -> "&eDepositar banco";
            case "bank-withdraw" -> "&6Retirar banco";
            case "storage-open" -> "&aAbrir almacén";
            case "banner-set" -> "&fCambiar estandarte";
            case "logs-view" -> "&6Ver registros";
            case "board-edit" -> "&eEditar tablero";
            case "description-edit" -> "&6Editar descripción";
            case "mail-send" -> "&dEnviar correos de clan";
            case "mail-delete" -> "&cBorrar correos de clan";
            case "join-requests" -> "&aGestionar solicitudes";
            case "rename-clan" -> "&eCambiar nombre del clan";
            case "rename-tag" -> "&bCambiar ID del clan";
            case "settings" -> "&6Abrir ajustes";
            case "permissions-edit" -> "&bAdministrar permisos";
            case "tier-upgrade" -> "&dMejorar clan";
            case "disband" -> "&4Disolver clan";
            default -> "&e" + key;
        };
    }

    private List<String> permissionDescription(String key) {
        List<String> configured = nativeMenus == null ? Collections.emptyList() : nativeMenus.getStringList("permission-labels." + key + ".description");
        if (configured != null && !configured.isEmpty()) return configured;
        return switch (key) {
            case "invite" -> List.of("&7Permite invitar jugadores", "&7a entrar al clan.");
            case "kick" -> List.of("&7Permite expulsar miembros", "&7de menor jerarquía.");
            case "promote" -> List.of("&7Permite subir el rango", "&7de otros miembros.");
            case "demote" -> List.of("&7Permite bajar el rango", "&7de otros miembros.");
            case "set-rank" -> List.of("&7Permite asignar un rango", "&7numérico exacto.");
            case "rename-role" -> List.of("&7Permite cambiar el nombre", "&7visible de los rangos.");
            case "setbase" -> List.of("&7Permite definir la base", "&7de teletransporte del clan.");
            case "relation" -> List.of("&7Permite declarar aliados,", "&7enemigos o neutralidad.");
            case "open" -> List.of("&7Permite cambiar si el clan", "&7es abierto o por solicitud.");
            case "bank-deposit" -> List.of("&7Permite depositar monedas", "&7en el banco del clan.");
            case "bank-withdraw" -> List.of("&7Permite retirar monedas", "&7del banco del clan.");
            case "storage-open" -> List.of("&7Permite abrir el almacén", "&7compartido del clan.");
            case "banner-set" -> List.of("&7Permite guardar o quitar", "&7el estandarte oficial.");
            case "logs-view" -> List.of("&7Permite ver registros", "&7internos del clan.");
            case "board-edit" -> List.of("&7Permite editar el tablero", "&7de información del clan.");
            case "description-edit" -> List.of("&7Permite editar la descripción", "&7pública del clan.");
            case "mail-send" -> List.of("&7Permite enviar y responder", "&7correos entre clanes.");
            case "mail-delete" -> List.of("&7Permite eliminar correos", "&7del buzón del clan.");
            case "join-requests" -> List.of("&7Permite aceptar o rechazar", "&7solicitudes de ingreso.");
            case "rename-clan" -> List.of("&7Permite cambiar el nombre", "&7formal del clan.");
            case "rename-tag" -> List.of("&7Permite cambiar el ID/tag", "&7visible del clan.");
            case "settings" -> List.of("&7Permite acceder al panel", "&7de ajustes del clan.");
            case "permissions-edit" -> List.of("&7Permite abrir el editor", "&7de permisos por rol.");
            case "tier-upgrade" -> List.of("&7Permite gastar dinero del banco", "&7para subir el tier del clan.");
            case "disband" -> List.of("&7Permite disolver el clan.", "&cAcción peligrosa.");
            default -> List.of("&7Permiso interno del clan.");
        };
    }

    private Material permissionMaterial(String key, int requiredRank) {
        Material configured = materialFrom(nativeMenus == null ? null : nativeMenus.getString("permission-labels." + key + ".material"), null);
        if (configured != null) return configured;
        return switch (Math.max(minRole(), Math.min(maxRole(), requiredRank))) {
            case 0 -> Material.LIME_WOOL;
            case 1 -> Material.GREEN_WOOL;
            case 2 -> Material.LIGHT_BLUE_WOOL;
            case 3 -> Material.YELLOW_WOOL;
            case 4 -> Material.ORANGE_WOOL;
            default -> Material.RED_WOOL;
        };
    }

    private List<String> permissionKeys() {
        return List.of("invite", "kick", "promote", "demote", "set-rank", "rename-role", "setbase", "relation", "open", "bank-deposit", "bank-withdraw", "storage-open", "banner-set", "logs-view", "board-edit", "description-edit", "mail-send", "mail-delete", "join-requests", "rename-clan", "rename-tag", "settings", "permissions-edit", "tier-upgrade", "disband");
    }

    private String permissionMenuPath(boolean editable) {
        if (editable && nativeSection("menus.permissions-edit") != null) return "menus.permissions-edit";
        return "menus.permissions";
    }

    private List<String> permissionTableLore(String key, boolean editable) {
        String path = permissionMenuPath(editable) + ".permission-item.lore";
        List<String> configured = nativeMenus == null ? Collections.emptyList() : nativeMenus.getStringList(path);
        if (configured != null && !configured.isEmpty()) return configured;
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Descripción:");
        lore.addAll(permissionDescription(key));
        lore.add("");
        lore.add("&7Rango requerido por defecto: &6{required_rank}");
        lore.add("&7Nombre del rango: &f{required_role}");
        lore.add("");
        lore.add(editable ? "&8Click en las lanas para alternar." : "&8Las lanas muestran el estado actual.");
        return lore;
    }

    private int[] nativeIntArray(String path, int[] defaults) {
        if (nativeMenus == null) return defaults;
        List<Integer> values = nativeMenus.getIntegerList(path);
        if (values == null || values.isEmpty()) return defaults;
        int[] out = new int[values.size()];
        for (int i = 0; i < values.size(); i++) out[i] = values.get(i);
        return out;
    }

    private List<String> expandLore(List<String> lore, Map<String, String> placeholders) {
        List<String> out = new ArrayList<>();
        if (lore == null) return out;
        for (String rawLine : lore) {
            String line = rawLine == null ? "" : rawLine;
            if (line.contains("{permission_description}")) {
                String replacement = placeholders == null ? "" : placeholders.getOrDefault("permission_description", "");
                if (replacement == null || replacement.isBlank()) {
                    out.add(applyPlaceholders(line, placeholders));
                } else {
                    String prefix = line.replace("{permission_description}", "");
                    for (String part : replacement.split("\\|", -1)) {
                        if (!part.isBlank()) out.add(applyPlaceholders(prefix + part, placeholders));
                    }
                }
            } else if (line.contains("{board_lines}") || line.contains("{description_lines}") || line.contains("{message_lines}")) {
                String token = line.contains("{description_lines}") ? "description_lines" : line.contains("{message_lines}") ? "message_lines" : "board_lines";
                String replacement = placeholders == null ? "" : placeholders.getOrDefault(token, "");
                if (replacement == null || replacement.isBlank()) {
                    out.add(applyPlaceholders(line, placeholders));
                } else {
                    String prefix = line.replace("{" + token + "}", "");
                    for (String part : replacement.split("\\|", -1)) {
                        if (!part.isBlank()) out.add(applyPlaceholders(prefix + part, placeholders));
                    }
                }
            } else {
                out.add(applyPlaceholders(line, placeholders));
            }
        }
        return out;
    }

    private List<String> papiPlaceholderList(String path, List<String> defaults) {
        List<String> configured = getConfig().getStringList(path);
        return configured == null || configured.isEmpty() ? defaults : configured;
    }

    private String firstPapi(OfflinePlayer player, List<String> placeholders) {
        if (player == null || Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return "";
        for (String placeholder : placeholders) {
            String value = safePapi(player, placeholder);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private Map<String, String> memberPlaceholders(Member member, int clanId, Player viewer) throws SQLException {
        Map<String, String> ph = new HashMap<>();
        OfflinePlayer off = Bukkit.getOfflinePlayer(member.uuid());
        Player online = Bukkit.getPlayer(member.uuid());
        PlayerProfileSnapshot profile = resolvePlayerProfile(member.uuid(), member.name());
        ph.put("player", member.name());
        ph.put("role_number", String.valueOf(member.role()));
        ph.put("role_name", getRoleName(clanId, member.role()));
        ph.put("status", online != null ? "&aConectado" : "&cDesconectado");
        ph.put("last_seen", online != null ? "Ahora" : date(off.getLastSeen()));
        ph.put("joined", date(member.joinedAt()));
        ph.put("level", profile.level());
        ph.put("race", profile.race());
        ph.putAll(resolveMDVSocialTitlePlaceholders(member.uuid()));
        return ph;
    }

    private Map<String, String> resolveMDVSocialTitlePlaceholders(UUID uuid) {
        Map<String, String> ph = new HashMap<>();
        String unknown = getConfig().getString("integrations.mdvsocial.unknown-title-text", "Sin título");
        String title = mdvSocialTargetPlaceholder(uuid, "title", unknown);
        String titleColored = mdvSocialTargetPlaceholder(uuid, "title_colored", title);
        String titlePrefix = mdvSocialTargetPlaceholder(uuid, "title_prefix", "");
        String titlePrefixPlain = mdvSocialTargetPlaceholder(uuid, "title_prefix_plain", ChatColor.stripColor(color(titlePrefix)));
        String titleId = mdvSocialTargetPlaceholder(uuid, "title_id", "");
        String activeTitle = mdvSocialTargetPlaceholder(uuid, "active_title", titleId);

        ph.put("title", title);
        ph.put("title_plain", ChatColor.stripColor(color(title)));
        ph.put("title_colored", titleColored);
        ph.put("title_prefix", titlePrefix);
        ph.put("title_prefix_plain", titlePrefixPlain);
        ph.put("title_id", titleId);
        ph.put("active_title", activeTitle);
        return ph;
    }

    private String mdvSocialTargetPlaceholder(UUID uuid, String key, String fallback) {
        if (uuid == null || Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return fallback == null ? "" : fallback;
        String placeholder = "%mdvsocial_" + key + "_of_uuid_" + uuid + "%";
        String value = safePapi(Bukkit.getOfflinePlayer(uuid), placeholder);
        return value.isBlank() ? (fallback == null ? "" : fallback) : value;
    }

    private String safePapi(OfflinePlayer player, String placeholder) {
        try {
            String out = PlaceholderAPI.setPlaceholders(player, placeholder);
            if (invalidProfileValue(out, placeholder)) return "";
            return out;
        } catch (Throwable ignored) { return ""; }
    }

    private boolean invalidProfileValue(String out, String placeholder) {
        if (out == null || out.equalsIgnoreCase(placeholder) || out.contains("%")) return true;
        String clean = ChatColor.stripColor(color(out)).trim();
        if (clean.isBlank()) return true;
        String lower = clean.toLowerCase(Locale.ROOT);
        return lower.equals("nomatch") || lower.equals("no match") || lower.equals("none") || lower.equals("null") || lower.equals("n/a") || lower.equals("na") || lower.equals("-") || lower.equals("sin raza") || lower.equals("sin clase") || lower.equals("no class");
    }

    private PlayerProfileSnapshot resolvePlayerProfile(UUID uuid, String fallbackName) {
        String level = "";
        String race = "";
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        Player online = Bukkit.getPlayer(uuid);

        if (online != null) {
            level = firstPapi(online, papiPlaceholderList("integrations.mmocore.level-placeholders", List.of("%mmocore_level%")));
            race = firstPapi(online, papiPlaceholderList("integrations.mmocore.race-placeholders", List.of("%mmocore_race%", "%mmocore_class%", "%mmocore_class_name%", "%mmocore_player_class%", "%mmocore_profession%")));
        }

        PlayerProfileSnapshot mmocore = resolveMMOCoreProfile(offline);
        if (level.isBlank()) level = mmocore.level();
        if (race.isBlank()) race = mmocore.race();

        if (level.isBlank() || race.isBlank()) {
            PlayerProfileSnapshot cached = getCachedPlayerProfile(uuid).orElse(PlayerProfileSnapshot.empty());
            if (level.isBlank()) level = cached.level();
            if (race.isBlank()) race = cached.race();
        }

        if (!level.isBlank() || !race.isBlank()) {
            savePlayerProfileCache(uuid, online != null ? online.getName() : (offline.getName() != null ? offline.getName() : fallbackName), level, race);
        }

        if (level.isBlank()) level = getConfig().getString("profile-cache.unknown-level-text", "Sin datos");
        if (race.isBlank()) race = getConfig().getString("profile-cache.unknown-race-text", "Sin raza");
        return new PlayerProfileSnapshot(level, race);
    }

    private PlayerProfileSnapshot resolveMMOCoreProfile(OfflinePlayer player) {
        if (player == null || !getConfig().getBoolean("integrations.mmocore.use-direct-api", true)) return PlayerProfileSnapshot.empty();
        if (Bukkit.getPluginManager().getPlugin("MMOCore") == null) return PlayerProfileSnapshot.empty();
        try {
            Class<?> dataClass = Class.forName("net.Indyuce.mmocore.api.player.PlayerData");
            Object data;
            try {
                data = dataClass.getMethod("get", OfflinePlayer.class).invoke(null, player);
            } catch (Throwable ignored) {
                data = dataClass.getMethod("get", UUID.class).invoke(null, player.getUniqueId());
            }
            if (data == null) return PlayerProfileSnapshot.empty();

            String level = "";
            try {
                Object rawLevel = dataClass.getMethod("getLevel").invoke(data);
                if (rawLevel != null) level = String.valueOf(rawLevel);
            } catch (Throwable ignored) {}

            String race = "";
            try {
                Object profess = dataClass.getMethod("getProfess").invoke(data);
                if (profess != null) {
                    try { race = String.valueOf(profess.getClass().getMethod("getName").invoke(profess)); }
                    catch (Throwable ignoredName) {
                        try { race = String.valueOf(profess.getClass().getMethod("getId").invoke(profess)); }
                        catch (Throwable ignoredId) {}
                    }
                }
            } catch (Throwable ignored) {}

            if (invalidProfileValue(level, "")) level = "";
            if (invalidProfileValue(race, "")) race = "";
            return new PlayerProfileSnapshot(level, race);
        } catch (Throwable ignored) {
            return PlayerProfileSnapshot.empty();
        }
    }

    private void scheduleProfileCacheUpdate(Player player, long delayTicks) {
        if (player == null || !getConfig().getBoolean("profile-cache.enabled", true)) return;
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player current = Bukkit.getPlayer(uuid);
            if (current != null) updatePlayerProfileCache(current);
        }, Math.max(1L, delayTicks));
    }

    private void updatePlayerProfileCache(Player player) {
        if (player == null || !getConfig().getBoolean("profile-cache.enabled", true)) return;
        String level = firstPapi(player, papiPlaceholderList("integrations.mmocore.level-placeholders", List.of("%mmocore_level%")));
        String race = firstPapi(player, papiPlaceholderList("integrations.mmocore.race-placeholders", List.of("%mmocore_race%", "%mmocore_class%", "%mmocore_class_name%", "%mmocore_player_class%", "%mmocore_profession%")));
        PlayerProfileSnapshot mmocore = resolveMMOCoreProfile(player);
        if (level.isBlank()) level = mmocore.level();
        if (race.isBlank()) race = mmocore.race();
        if (!level.isBlank() || !race.isBlank()) savePlayerProfileCache(player.getUniqueId(), player.getName(), level, race);
    }

    private Optional<PlayerProfileSnapshot> getCachedPlayerProfile(UUID uuid) {
        if (uuid == null || connection == null || !getConfig().getBoolean("profile-cache.enabled", true)) return Optional.empty();
        try (PreparedStatement ps = connection.prepareStatement("SELECT level,race FROM player_profiles WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String level = rs.getString("level");
                    String race = rs.getString("race");
                    return Optional.of(new PlayerProfileSnapshot(level == null ? "" : level, race == null ? "" : race));
                }
            }
        } catch (SQLException ignored) {}
        return Optional.empty();
    }

    private void savePlayerProfileCache(UUID uuid, String name, String level, String race) {
        if (uuid == null || connection == null || !getConfig().getBoolean("profile-cache.enabled", true)) return;
        String cleanLevel = invalidProfileValue(level, "") ? "" : level;
        String cleanRace = invalidProfileValue(race, "") ? "" : race;
        if (cleanLevel.isBlank() && cleanRace.isBlank()) return;
        Optional<PlayerProfileSnapshot> old = getCachedPlayerProfile(uuid);
        if (cleanLevel.isBlank() && old.isPresent()) cleanLevel = old.get().level();
        if (cleanRace.isBlank() && old.isPresent()) cleanRace = old.get().race();
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO player_profiles(uuid,name,level,race,updated_at) VALUES(?,?,?,?,?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name == null ? "" : name);
            ps.setString(3, cleanLevel);
            ps.setString(4, cleanRace);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    private List<String> boardItemLore(Clan clan) {
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Mensaje actual:");
        for (String line : boardLines(clan.boardMessage())) lore.add("&f" + line);
        lore.add("");
        lore.add("&eClick: ver en chat");
        lore.add("{board_edit_hint}");
        return lore;
    }

    private List<String> splitLines(String text, int limit) {
        if (text == null || text.isBlank()) return Collections.emptyList();
        return Arrays.stream(text.split("\\|", -1)).map(String::trim).filter(x -> !x.isBlank()).limit(Math.max(1, limit)).collect(Collectors.toList());
    }

    private String normalizeAutoText(String text) {
        if (text == null) return "";
        return text.replace('§', '&').replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
    }

    private List<String> autoWrapText(String text, int maxLines, int charsPerLine) {
        String clean = normalizeAutoText(text);
        if (clean.isBlank()) return Collections.emptyList();
        int linesLimit = Math.max(1, maxLines);
        int lineLimit = Math.max(10, charsPerLine);
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : clean.split(" ")) {
            if (word.isBlank()) continue;
            while (word.length() > lineLimit) {
                if (current.length() > 0) {
                    out.add(current.toString().trim());
                    current.setLength(0);
                    if (out.size() >= linesLimit) return out;
                }
                out.add(word.substring(0, lineLimit));
                word = word.substring(lineLimit);
                if (out.size() >= linesLimit) return out;
            }
            if (current.length() == 0) {
                current.append(word);
            } else if (current.length() + 1 + word.length() <= lineLimit) {
                current.append(' ').append(word);
            } else {
                out.add(current.toString().trim());
                if (out.size() >= linesLimit) return out;
                current.setLength(0);
                current.append(word);
            }
        }
        if (current.length() > 0 && out.size() < linesLimit) out.add(current.toString().trim());
        return out;
    }

    private String clanDescription(Clan clan) {
        if (clan == null || clan.description() == null || clan.description().isBlank()) return getConfig().getString("clan-description.default-text", "Este clan aún no tiene descripción.");
        return String.join(" | ", descriptionLines(clan.description()));
    }

    private List<String> descriptionLines(String text) {
        String source = (text == null || text.isBlank()) ? getConfig().getString("clan-description.default-text", "Este clan aún no tiene descripción.") : text;
        int lineCount = Math.max(1, getConfig().getInt("clan-description.lines", 5));
        int max = Math.max(lineCount, getConfig().getInt("clan-description.max-length", 180));
        int charsPerLine = Math.max(10, (int) Math.ceil(max / (double) lineCount));
        List<String> wrapped = autoWrapText(source, lineCount, charsPerLine);
        return wrapped.isEmpty() ? List.of(getConfig().getString("clan-description.default-text", "Este clan aún no tiene descripción.")) : wrapped;
    }

    private String descriptionLine(String text, int index) {
        List<String> lines = descriptionLines(text);
        if (index < 0 || index >= lines.size()) return "";
        return lines.get(index);
    }

    private List<String> boardLines(String text) {
        if (text == null || text.isBlank()) return List.of("&8Sin información todavía.");
        return splitLines(text, 10);
    }

    private String boardLine(String text, int index) {
        List<String> lines = boardLines(text);
        if (index < 0 || index >= lines.size()) return "";
        return lines.get(index);
    }

    private int pageIndexFromSlot(int page, int slot) {
        for (int i = 0; i < PAGE_CONTENT_SLOTS.length; i++) {
            if (PAGE_CONTENT_SLOTS[i] == slot) return (Math.max(1, page) - 1) * GUI_PAGE_SIZE + i;
        }
        return -1;
    }

    private int contentSlot(int indexOnPage) {
        if (indexOnPage < 0 || indexOnPage >= PAGE_CONTENT_SLOTS.length) return -1;
        return PAGE_CONTENT_SLOTS[indexOnPage];
    }

    private void fill(Inventory inv) {
        ItemStack filler = nativeItem("global.filler", Material.BLACK_STAINED_GLASS_PANE, " ", List.of(), Map.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
    }

    private void nav(Inventory inv, int page, int total, int pageSize, String menu, int clanId) {
        if (page > 1) inv.setItem(45, nativeItem("global.previous-page", Material.ARROW, "&ePágina anterior", List.of("&7Click para volver."), Map.of("page", String.valueOf(page))));
        if (page * pageSize < total) inv.setItem(53, nativeItem("global.next-page", Material.SPECTRAL_ARROW, "&ePágina siguiente", List.of("&7Click para avanzar."), Map.of("page", String.valueOf(page))));
    }

    private ItemStack clanBannerItem(Clan clan, String name, List<String> lore) {
        return clanBannerItem(clan, name, lore, Map.of());
    }

    private ItemStack clanBannerItem(Clan clan, String name, List<String> lore, Map<String, String> placeholders) {
        ItemStack base = new ItemStack(Material.WHITE_BANNER);
        if (clan != null && clan.hasBanner()) {
            try { base = itemFromBase64(clan.banner()); }
            catch (Exception ignored) { base = new ItemStack(Material.WHITE_BANNER); }
        }
        ItemStack item = base.clone();
        item.setAmount(1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(color(applyPlaceholders(name, placeholders)));
        meta.setLore(lore.stream().map(line -> color(applyPlaceholders(line, placeholders))).collect(Collectors.toList()));
        hideBannerTooltip(meta);
        item.setItemMeta(meta);
        return item;
    }

    private void hideBannerTooltip(ItemMeta meta) {
        if (meta == null || !getConfig().getBoolean("banners.hide-patterns", true)) return;
        try {
            meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        } catch (Throwable ignored) {
            // Paper 1.21+ lo soporta. Si alguna build antigua no lo tiene, no rompemos el menú.
        }
    }

    private String configString(String path, String def) {
        return nativeMenus == null ? def : nativeMenus.getString(path, def);
    }

    private ItemStack lockedItem(String name, String reason) {
        return nativeItem("global.locked", Material.GRAY_DYE, name, List.of("", reason, "&8No puedes usar esta función."), Map.of("reason", reason));
    }

    private List<String> playerProfileLore(Member member, int clanId, Player viewer, List<String> extra) throws SQLException {
        List<String> lore = new ArrayList<>();
        OfflinePlayer off = Bukkit.getOfflinePlayer(member.uuid());
        Player online = Bukkit.getPlayer(member.uuid());
        lore.add(color("&7Rango: &b" + member.role() + " &8- &f" + getRoleName(clanId, member.role())));
        lore.add(color("&7Estado: " + (online != null ? "&aConectado" : "&cDesconectado")));
        lore.add(color("&7Última vez: &f" + (online != null ? "Ahora" : date(off.getLastSeen()))));
        lore.add(color("&7Ingreso: &f" + date(member.joinedAt())));
        PlayerProfileSnapshot profile = resolvePlayerProfile(member.uuid(), member.name());
        lore.add(color("&7Nivel: &e" + profile.level()));
        lore.add(color("&7Raza: &d" + profile.race()));
        if (extra != null) for (String line : extra) lore.add(color(line));
        return lore;
    }

    private String nativeTitle(String menuKey, String def, Map<String, String> placeholders) {
        String raw = nativeMenus == null ? def : nativeMenus.getString("menus." + menuKey + ".title", def);
        return color(applyPlaceholders(raw, placeholders));
    }

    private int nativeSlot(String path, int def) {
        return nativeMenus == null ? def : nativeMenus.getInt(path, def);
    }

    private List<String> lines(String path, List<String> def, Map<String, String> placeholders) {
        return lines(path, def, placeholders, null);
    }

    private List<String> lines(String path, List<String> def, Map<String, String> placeholders, OfflinePlayer papiPlayer) {
        List<String> configured = nativeMenus == null ? Collections.emptyList() : nativeMenus.getStringList(path);
        List<String> source = configured == null || configured.isEmpty() ? def : configured;
        return source.stream()
                .map(line -> applyPlaceholdersAndPapi(line, placeholders, papiPlayer))
                .filter(line -> !HIDE_LINE.equals(ChatColor.stripColor(line)))
                .collect(Collectors.toList());
    }

    private ItemStack nativeItem(String path, Material defMaterial, String defName, List<String> defLore, Map<String, String> placeholders) {
        return nativeItem(path, defMaterial, defName, defLore, placeholders, null);
    }

    private ItemStack nativeItem(String path, Material defMaterial, String defName, List<String> defLore, Map<String, String> placeholders, OfflinePlayer papiPlayer) {
        ConfigurationSection sec = nativeSection(path);
        Material material = materialFrom(sec == null ? null : sec.getString("material"), defMaterial);
        String name = sec == null ? defName : sec.getString("name", defName);
        List<String> lore = sec == null ? defLore : sec.getStringList("lore");
        if (lore == null || lore.isEmpty()) lore = defLore;

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (material == Material.PLAYER_HEAD && meta instanceof SkullMeta skull) {
            String texture = applyPlaceholdersAndPapi(readTexture(sec), placeholders, papiPlayer);
            if (texture != null && !texture.isBlank()) {
                applySkullTexture(skull, texture);
            } else if (sec != null) {
                String owner = sec.getString("head-owner", sec.getString("owner", ""));
                owner = applyPlaceholdersAndPapi(owner == null ? "" : owner, placeholders, papiPlayer);
                if (owner != null && !owner.isBlank()) skull.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
            }
            meta = skull;
        }

        meta.setDisplayName(color(applyPlaceholdersAndPapi(name, placeholders, papiPlayer)));
        if (lore != null) {
            List<String> expandedLore = expandLore(lore, placeholders);
            if (papiPlayer != null && Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                expandedLore = expandedLore.stream().map(line -> {
                    try { return PlaceholderAPI.setPlaceholders(papiPlayer, line); }
                    catch (Throwable ignored) { return line; }
                }).collect(Collectors.toList());
            }
            meta.setLore(expandedLore.stream().map(this::color).collect(Collectors.toList()));
        }
        if (material.name().endsWith("_BANNER")) hideBannerTooltip(meta);
        item.setItemMeta(meta);
        return item;
    }

    private ConfigurationSection nativeSection(String path) {
        return nativeMenus == null ? null : nativeMenus.getConfigurationSection(path);
    }

    private boolean nativeOptionalItemEnabled(String path, boolean defaultIfMissing) {
        ConfigurationSection sec = nativeSection(path);
        if (sec == null) return defaultIfMissing;
        return sec.getBoolean("enabled", defaultIfMissing);
    }

    // 1.10.3: items de menús nativos configurables.
    // Si el bloque no existe, no se dibuja ni responde al click.
    // Si existe, se muestra por defecto; agrega enabled: false para ocultarlo sin borrar el bloque.
    private boolean nativeConfiguredItemEnabled(String path) {
        ConfigurationSection sec = nativeSection(path);
        if (sec == null) return false;
        return sec.getBoolean("enabled", true);
    }

    private boolean nativeConfiguredItemClicked(String path, int clickedSlot, int defaultSlot) {
        return nativeConfiguredItemEnabled(path) && clickedSlot == nativeSlot(path + ".slot", defaultSlot);
    }

    private String readTexture(ConfigurationSection sec) {
        if (sec == null) return "";
        String texture = sec.getString("custom-head-texture", "");
        if (texture == null || texture.isBlank()) texture = sec.getString("texture", "");
        if (texture == null || texture.isBlank()) texture = sec.getString("head-texture", "");
        if (texture == null || texture.isBlank()) texture = sec.getString("skull-texture", "");
        if (texture == null || texture.isBlank()) texture = sec.getString("texture-base64", "");
        return texture == null ? "" : texture.trim();
    }

    private String extractTextureUrl(String textureValue) {
        if (textureValue == null) return "";
        String value = textureValue.trim();
        if (value.isBlank()) return "";
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        try {
            String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            int urlKey = decoded.indexOf("\"url\"");
            if (urlKey < 0) return "";
            int colon = decoded.indexOf(':', urlKey);
            if (colon < 0) return "";
            int firstQuote = decoded.indexOf('\"', colon);
            if (firstQuote < 0) return "";
            int secondQuote = decoded.indexOf('\"', firstQuote + 1);
            if (secondQuote < 0) return "";
            return decoded.substring(firstQuote + 1, secondQuote).replace("\\/", "/");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void applySkullTexture(SkullMeta skull, String textureValue) {
        if (skull == null || textureValue == null || textureValue.isBlank()) return;
        String textureUrl = extractTextureUrl(textureValue.trim());
        if (textureUrl == null || textureUrl.isBlank()) {
            getLogger().warning("No se pudo aplicar textura custom de cabeza: textura inválida o Base64 sin URL.");
            return;
        }
        try {
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID(), "MDVClans");
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(new URL(textureUrl));
            profile.setTextures(textures);
            skull.setOwnerProfile(profile);
        } catch (Throwable ex) {
            getLogger().warning("No se pudo aplicar textura custom de cabeza: " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
        }
    }

    private Material materialFrom(String raw, Material def) {
        if (raw == null || raw.isBlank()) return def;
        Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        return material == null ? def : material;
    }

    private String applyPlaceholders(String text, Map<String, String> placeholders) {
        if (text == null || placeholders == null || placeholders.isEmpty()) return text;
        String out = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            out = out.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return out;
    }

    // 1.10.4: permite usar PlaceholderAPI después de reemplazar placeholders internos.
    // Ejemplo en lista de miembros: %mdvsocial_title_colored_of_{player}%
    private String applyPlaceholdersAndPapi(String text, Map<String, String> placeholders, OfflinePlayer papiPlayer) {
        String out = applyPlaceholders(text, placeholders);
        if (out == null || papiPlayer == null || !out.contains("%")) return out;
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return out;
        try {
            return PlaceholderAPI.setPlaceholders(papiPlayer, out);
        } catch (Throwable ignored) {
            return out;
        }
    }

    private Map<String, String> clanPlaceholders(Clan clan) throws SQLException {
        Map<String, String> map = new HashMap<>();
        if (clan != null) {
            map.put("clan_id", clan.tag());
            map.put("id", clan.tag());
            map.put("clan_name", clan.name());
            map.put("name", clan.name());
            map.put("members", String.valueOf(countMembers(clan.id())));
            map.put("max_members", String.valueOf(maxMembersForClan(clan)));
            map.putAll(tierPlaceholders(clan));
            map.put("bank", formatNumber(clan.bankBalance()));
            map.put("strength", formatNumber(calculateStrength(clan)));
            map.put("open", clan.open() ? "Abierto" : "Invitación");
            map.put("created", date(clan.createdAt()));
            map.put("description", clanDescription(clan));
            map.put("description_plain", ChatColor.stripColor(color(clanDescription(clan))));
            List<String> descriptionLines = descriptionLines(clan.description());
            map.put("description_lines", String.join("|", descriptionLines));
            for (int i = 1; i <= 5; i++) map.put("description_line_" + i, descriptionLine(clan.description(), i - 1));
            List<String> boardLines = boardLines(clan.boardMessage());
            map.put("board", String.join(" | ", boardLines));
            map.put("board_lines", String.join("|", boardLines));
            for (int i = 1; i <= 10; i++) map.put("board_line_" + i, boardLine(clan.boardMessage(), i - 1));
        }
        return map;
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(name));
        if (lore != null) meta.setLore(lore.stream().map(this::color).collect(Collectors.toList()));
        item.setItemMeta(meta);
        return item;
    }

    private String date(long ms) {
        if (ms <= 0) return "Nunca";
        return new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date(ms));
    }

    private void saveNativeMenuResources() {
        String[] resources = {
                "NativeMenus/00-global.yml",
                "NativeMenus/10-main.yml",
                "NativeMenus/20-info.yml",
                "NativeMenus/30-members.yml",
                "NativeMenus/40-relations.yml",
                "NativeMenus/50-settings.yml",
                "NativeMenus/60-actions.yml",
                "NativeMenus/70-permission-labels.yml",
                "NativeMenus/80-bases.yml",
                "NativeMenus/90-storage.yml"
        };
        for (String resource : resources) saveResourceIfMissing(resource);
    }

    private void saveResourceIfMissing(String resource) {
        File file = new File(getDataFolder(), resource);
        if (!file.exists()) saveResource(resource, false);
    }

    private void startNametagTask() {
        if (!getConfig().getBoolean("nametags.enabled", true)) return;
        long interval = Math.max(20L, getConfig().getLong("nametags.update-interval-ticks", 100L));
        nametagTaskId = Bukkit.getScheduler().runTaskTimer(this, this::syncNametags, 40L, interval).getTaskId();
    }

    private Scoreboard getViewerNametagScoreboard(Player viewer) {
        Scoreboard current = viewer.getScoreboard();
        if (Bukkit.getScoreboardManager() == null) return current;

        boolean forcePersonal = getConfig().getBoolean("nametags.force-personal-scoreboard", false);
        boolean forceIfMain = getConfig().getBoolean("nametags.force-personal-scoreboard-if-main", true);
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();

        if (forcePersonal || (forceIfMain && (current == null || current == main))) {
            Scoreboard personal = personalNametagBoards.computeIfAbsent(viewer.getUniqueId(), ignored -> Bukkit.getScoreboardManager().getNewScoreboard());
            if (current != personal) viewer.setScoreboard(personal);
            return personal;
        }
        return current;
    }

    private void syncNametags() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        Map<UUID, Member> memberSnapshot = new HashMap<>();
        Map<Integer, Clan> clanSnapshot = new HashMap<>();
        Map<String, String> relationSnapshot = new HashMap<>();
        Map<UUID, String> levelSnapshot = new HashMap<>();

        for (Player player : players) {
            levelSnapshot.put(player.getUniqueId(), resolveNametagLevel(player));
            try {
                Optional<Member> memberOpt = getMember(player.getUniqueId());
                if (memberOpt.isPresent()) {
                    Member member = memberOpt.get();
                    memberSnapshot.put(player.getUniqueId(), member);
                    if (!clanSnapshot.containsKey(member.clanId())) {
                        getClan(member.clanId()).ifPresent(clan -> clanSnapshot.put(clan.id(), clan));
                    }
                }
            } catch (SQLException e) {
                getLogger().warning("Error preparando caché de nametags: " + e.getMessage());
            }
        }

        for (Player viewer : players) {
            try {
                Scoreboard board = getViewerNametagScoreboard(viewer);
                for (Player target : players) {
                    updateNametagFor(viewer, target, board, memberSnapshot, clanSnapshot, relationSnapshot, levelSnapshot);
                }
            } catch (Exception e) {
                getLogger().warning("Error actualizando nametags: " + e.getMessage());
            }
        }
    }



    private void requestNametagSync(long delayTicks) {
        if (!getConfig().getBoolean("nametags.enabled", true)) return;
        Bukkit.getScheduler().runTaskLater(this, this::syncNametags, Math.max(1L, delayTicks));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoinNametag(PlayerJoinEvent event) {
        requestNametagSync(10L);
        requestNametagSync(40L);
        scheduleProfileCacheUpdate(event.getPlayer(), 20L);
        scheduleProfileCacheUpdate(event.getPlayer(), Math.max(40L, getConfig().getLong("profile-cache.update-on-join-delay-ticks", 80L)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuitNametag(PlayerQuitEvent event) {
        personalNametagBoards.remove(event.getPlayer().getUniqueId());
        nametagAppliedTeams.remove(event.getPlayer().getUniqueId());
        nametagAppliedTeams.values().forEach(map -> map.remove(event.getPlayer().getName()));
        bannerCopyCooldowns.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawnNametag(PlayerRespawnEvent event) {
        requestNametagSync(5L);
        requestNametagSync(30L);
    }

    private void updateNametagFor(Player viewer, Player target, Scoreboard board, Map<UUID, Member> memberSnapshot, Map<Integer, Clan> clanSnapshot, Map<String, String> relationSnapshot, Map<UUID, String> levelSnapshot) throws SQLException {
        String entry = target.getName();
        Member targetMember = memberSnapshot.get(target.getUniqueId());
        if (targetMember == null) {
            removeAppliedNametagTeam(viewer, board, entry);
            return;
        }
        Clan targetClan = clanSnapshot.get(targetMember.clanId());
        if (targetClan == null) {
            removeAppliedNametagTeam(viewer, board, entry);
            return;
        }

        Member viewerMember = memberSnapshot.get(viewer.getUniqueId());
        int viewerClanId = viewerMember == null ? -1 : viewerMember.clanId();
        String relationKey = viewerClanId + ":" + targetClan.id();
        String relation = relationSnapshot.get(relationKey);
        if (relation == null) {
            relation = getRelationBetween(viewerClanId, targetClan.id());
            relationSnapshot.put(relationKey, relation);
        }
        String formatKey = switch (relation) {
            case "SAME" -> "same";
            case REL_ALLY -> "ally";
            case REL_ENEMY -> "enemy";
            default -> "neutral";
        };

        String compactUuid = target.getUniqueId().toString().replace("-", "");
        String teamName = "mdvc_" + formatKey.substring(0, Math.min(2, formatKey.length())) + "_" + compactUuid.substring(0, 8);
        if (teamName.length() > 16) teamName = teamName.substring(0, 16);
        String targetLevel = levelSnapshot.getOrDefault(target.getUniqueId(), "");

        Map<String, String> viewerApplied = nametagAppliedTeams.computeIfAbsent(viewer.getUniqueId(), ignored -> new ConcurrentHashMap<>());
        String previousTeam = viewerApplied.get(entry);
        if (teamName.equals(previousTeam)) {
            Team existing = board.getTeam(teamName);
            if (existing != null && existing.hasEntry(entry)) {
                ensureNametagTeam(existing, formatKey, targetClan, targetLevel);
                return;
            }
        }

        if (previousTeam != null && !previousTeam.equals(teamName)) {
            Team old = board.getTeam(previousTeam);
            if (old != null && old.hasEntry(entry)) old.removeEntry(entry);
        } else if (previousTeam == null) {
            removeFromMDVTeams(board, entry);
        }

        Team team = board.getTeam(teamName);
        if (team == null) team = board.registerNewTeam(teamName);
        ensureNametagTeam(team, formatKey, targetClan, targetLevel);
        if (!team.hasEntry(entry)) team.addEntry(entry);
        viewerApplied.put(entry, teamName);
    }

    private void ensureNametagTeam(Team team, String formatKey, Clan targetClan, String targetLevel) {
        String prefix = color(getConfig().getString("nametags.formats." + formatKey, "&7[{id}] ")
                .replace("{id}", targetClan.tag())
                .replace("{name}", targetClan.name()));
        String suffix = "";
        if (getConfig().getBoolean("nametags.level-suffix.enabled", true)) {
            String level = targetLevel == null ? "" : targetLevel.trim();
            if (!level.isBlank()) {
                suffix = color(getConfig().getString("nametags.level-suffix.format", " &a[&2{level}&a]")
                        .replace("{level}", level)
                        .replace("%level%", level)
                        .replace("%nivel%", level));
            }
        }
        if (!prefix.equals(team.getPrefix())) team.setPrefix(prefix);
        if (!suffix.equals(team.getSuffix())) team.setSuffix(suffix);
        try { team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS); } catch (Throwable ignored) { }
    }

    private String resolveNametagLevel(Player player) {
        if (player == null || !getConfig().getBoolean("nametags.level-suffix.enabled", true)) return "";
        String level = firstPapi(player, papiPlaceholderList("integrations.mmocore.level-placeholders", List.of("%mmocore_level%")));
        if (level.isBlank()) level = resolveMMOCoreProfile(player).level();
        if (level == null) return "";
        String clean = ChatColor.stripColor(color(level)).trim();
        if (clean.isBlank() || clean.equalsIgnoreCase("Sin datos")) return "";
        return clean;
    }

    private void removeAppliedNametagTeam(Player viewer, Scoreboard board, String entry) {
        Map<String, String> viewerApplied = nametagAppliedTeams.get(viewer.getUniqueId());
        String previousTeam = viewerApplied == null ? null : viewerApplied.remove(entry);
        if (previousTeam != null) {
            Team old = board.getTeam(previousTeam);
            if (old != null && old.hasEntry(entry)) old.removeEntry(entry);
        }
    }


    private void removeFromMDVTeams(Scoreboard board, String entry) {
        for (Team team : board.getTeams()) {
            if (team.getName().startsWith("mdvc") && team.hasEntry(entry)) {
                team.removeEntry(entry);
            }
        }
    }


    private synchronized void setClanName(int clanId, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE clans SET name=? WHERE id=?")) {
            ps.setString(1, name);
            ps.setInt(2, clanId);
            ps.executeUpdate();
        }
    }

    private synchronized void setClanTag(int clanId, String tag) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE clans SET tag=? WHERE id=?")) {
            ps.setString(1, tag);
            ps.setInt(2, clanId);
            ps.executeUpdate();
        }
    }

    private synchronized void createJoinRequest(int clanId, UUID target, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO join_requests(clan_id,target_uuid,target_name,requested_at) VALUES(?,?,?,?)")) {
            ps.setInt(1, clanId);
            ps.setString(2, target.toString());
            ps.setString(3, name);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private synchronized List<ClanJoinRequest> getJoinRequests(int clanId, int page, int pageSize) throws SQLException {
        cleanupInvalidJoinRequests(clanId);
        List<ClanJoinRequest> list = new ArrayList<>();
        int offset = Math.max(0, page - 1) * pageSize;
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM join_requests WHERE clan_id=? ORDER BY requested_at DESC LIMIT ? OFFSET ?")) {
            ps.setInt(1, clanId);
            ps.setInt(2, pageSize);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(new ClanJoinRequest(UUID.fromString(rs.getString("target_uuid")), rs.getString("target_name"), rs.getLong("requested_at")));
            }
        }
        return list;
    }

    private synchronized int countJoinRequests(int clanId) throws SQLException {
        cleanupInvalidJoinRequests(clanId);
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM join_requests WHERE clan_id=?")) {
            ps.setInt(1, clanId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    private synchronized Optional<ClanJoinRequest> getJoinRequestByName(int clanId, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM join_requests WHERE clan_id=? AND lower(target_name)=lower(?)")) {
            ps.setInt(1, clanId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(new ClanJoinRequest(UUID.fromString(rs.getString("target_uuid")), rs.getString("target_name"), rs.getLong("requested_at")));
            }
        }
        return Optional.empty();
    }

    private synchronized void deleteJoinRequest(int clanId, UUID target) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM join_requests WHERE clan_id=? AND target_uuid=?")) {
            ps.setInt(1, clanId);
            ps.setString(2, target.toString());
            ps.executeUpdate();
        }
    }

    private synchronized void deleteJoinRequestsForPlayerUnsafe(UUID target) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM join_requests WHERE target_uuid=?")) {
            ps.setString(1, target.toString());
            ps.executeUpdate();
        }
    }

    private synchronized void cleanupInvalidJoinRequests(int clanId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM join_requests WHERE clan_id=? AND target_uuid IN (SELECT uuid FROM members)")) {
            ps.setInt(1, clanId);
            ps.executeUpdate();
        }
    }

    private void acceptJoinRequest(Player actor, int clanId, ClanJoinRequest request) throws SQLException {
        if (getMember(request.uuid()).isPresent()) {
            deleteJoinRequest(clanId, request.uuid());
            msg(actor, "&cEse jugador ya pertenece a otro clan. Solicitud borrada.");
            return;
        }
        Clan clan = getClan(clanId).orElseThrow();
        if (countMembers(clanId) >= maxMembersForClan(clan)) {
            msg(actor, "&cTu clan ya alcanzó el límite de miembros.");
            return;
        }
        synchronized (this) {
            addMemberUnsafe(clanId, request.uuid(), request.name(), minRole(), System.currentTimeMillis(), 0);
            deleteJoinRequestsForPlayerUnsafe(request.uuid());
        }
        logAction(clanId, actor, "SOLICITUD", "Aceptó a " + request.name());
        broadcastToClan(clanId, "&e" + request.name() + " &ase unió al clan por solicitud aceptada.");
        Player online = Bukkit.getPlayer(request.uuid());
        if (online != null) msg(online, "&aTu solicitud fue aceptada. Ahora perteneces al clan.");
    }

    private synchronized Optional<ClanMail> getClanMail(int clanId, int mailId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM clan_mails WHERE to_clan_id=? AND id=? AND deleted=0")) {
            ps.setInt(1, clanId);
            ps.setInt(2, mailId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(new ClanMail(rs.getInt("id"), rs.getInt("from_clan_id"), rs.getInt("to_clan_id"), rs.getString("sender_name"), rs.getLong("sent_at"), rs.getString("message"), rs.getString("mail_type"), rs.getInt("relation_clan_id")));
            }
        }
        return Optional.empty();
    }

    private Clan readClan(ResultSet rs) throws SQLException {
        return new Clan(
                rs.getInt("id"),
                rs.getString("tag"),
                rs.getString("name"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getInt("open") == 1,
                rs.getLong("created_at"),
                rs.getInt("tier"),
                rs.getDouble("bank_balance"),
                rs.getString("banner"),
                rs.getString("storage"),
                rs.getString("board_message"),
                rs.getString("description"),
                rs.getString("base_world"),
                rs.getDouble("base_x"),
                rs.getDouble("base_y"),
                rs.getDouble("base_z"),
                rs.getFloat("base_yaw"),
                rs.getFloat("base_pitch")
        );
    }

    private Member readMember(ResultSet rs) throws SQLException {
        return new Member(
                rs.getInt("clan_id"),
                UUID.fromString(rs.getString("uuid")),
                rs.getString("name"),
                rs.getInt("role"),
                rs.getLong("joined_at"),
                rs.getInt("chat_toggle") == 1
        );
    }

    private void broadcastToClan(int clanId, String message) throws SQLException {
        for (Member m : getMembers(clanId)) {
            Player online = Bukkit.getPlayer(m.uuid());
            if (online != null) msg(online, message);
        }
    }

    private void notifyClan(int clanId, String message) throws SQLException {
        broadcastToClan(clanId, message);
    }

    private String relationText(String relation) {
        return switch (relation) {
            case "SAME" -> "&aTu clan";
            case REL_ALLY -> "&9Aliado";
            case REL_ENEMY -> "&cEnemigo";
            case REL_ALLY_REQUEST -> "&eSolicitud de alianza";
            default -> "&7Neutral";
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("mdvclans")) {
            if (args.length == 1) return filter(List.of("reload", "wipebases", "limpiarbases"), args[0]);
            if (args.length == 2 && equalsAny(args[0], "wipebases", "limpiarbases")) return filter(List.of("confirmar"), args[1]);
            return Collections.emptyList();
        }
        if (!(sender instanceof Player)) return Collections.emptyList();
        if (args.length == 1) {
            return filter(List.of("ayuda", "crear", "info", "lista", "invitar", "aceptar", "rechazar", "unirse", "abierto", "salir", "expulsar", "promover", "degradar", "setrango", "lider", "rol", "chat", "c", "setbase", "base", "relacion", "banco", "depositar", "retirar", "almacen", "mejorar", "tier", "upgrade", "estandarte", "logs", "top", "bajas", "tablero", "correo", "editar", "solicitudes", "menu", "abrir", "ui", "interfaz", "disolver"), args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            if (args.length == 2) {
                if (equalsAny(sub, "info", "unirse", "relacion")) return filter(listClanTags(), args[1]);
                if (equalsAny(sub, "invitar")) return null;
                if (equalsAny(sub, "aceptar", "rechazar", "declinar")) return filter(listInviteTags(((Player) sender).getUniqueId()), args[1]);
                if (equalsAny(sub, "abierto")) return filter(List.of("on", "off"), args[1]);
                if (equalsAny(sub, "expulsar", "promover", "degradar", "setrango", "lider", "líder", "leader", "transferirlider", "transferir-lider", "transferleader")) return filter(memberNamesOfSenderClan((Player) sender), args[1]);
                if (equalsAny(sub, "rol")) return filter(List.of("0", "1", "2", "3", "4"), args[1]);
                if (equalsAny(sub, "banco")) return filter(List.of("depositar", "retirar", "log"), args[1]);
                if (equalsAny(sub, "estandarte", "banner")) return filter(List.of("set", "ver", "quitar"), args[1]);
                if (equalsAny(sub, "top")) return filter(List.of("fuerza", "kills", "banco"), args[1]);
                if (equalsAny(sub, "logs", "registro", "registros")) return filter(List.of("all", "miembros", "banco", "diplomacia", "correo", "combate", "admin", "base", "almacen"), args[1]);
                if (equalsAny(sub, "descripcion", "descripción", "desc")) return filter(List.of("ver", "set", "limpiar"), args[1]);
                if (equalsAny(sub, "editar")) return filter(List.of("nombre", "id"), args[1]);
                if (equalsAny(sub, "solicitudes")) return filter(List.of("ver", "aceptar", "borrar"), args[1]);
                if (equalsAny(sub, "menu", "abrir", "ui", "interfaz")) return filter(List.of("auto", "gestion", "sinclan", "miembros", "info", "relaciones", "relaciones_lista", "almacen", "lista", "lista_sinclan", "crear", "creacion", "correo", "top", "top_kills", "top_banco", "bajas", "logs", "ajustes", "rangos", "permisos", "permisos_editar", "solicitudes", "principal"), args[1]);
                if (equalsAny(sub, "disolver")) return filter(List.of("confirmar"), args[1]);
            }
            if (args.length == 3 && equalsAny(sub, "relacion")) return filter(List.of("neutral", "aliado", "enemigo"), args[2]);
            if (args.length == 3 && equalsAny(sub, "setrango")) return filter(List.of("0", "1", "2", "3"), args[2]);
        } catch (SQLException ignored) {
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> input, String prefix) {
        if (input == null) return null;
        String lower = prefix.toLowerCase(Locale.ROOT);
        return input.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower)).collect(Collectors.toList());
    }

    private List<String> listClanTags() throws SQLException {
        return listClans().stream().map(Clan::tag).collect(Collectors.toList());
    }

    private List<String> listInviteTags(UUID target) throws SQLException {
        List<String> tags = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT c.tag FROM invites i JOIN clans c ON c.id=i.clan_id WHERE i.target_uuid=? AND i.expires_at>?")) {
            ps.setString(1, target.toString());
            ps.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tags.add(rs.getString("tag"));
            }
        }
        return tags;
    }

    public boolean isPlayerInClan(UUID playerUuid) {
        if (playerUuid == null) return false;
        try {
            return getMember(playerUuid).isPresent();
        } catch (SQLException ignored) {
            return false;
        }
    }

    /**
     * Devuelve el banner serializado del clan del jugador.
     * - null: el jugador no tiene clan.
     * - "": el jugador tiene clan pero no tiene banner configurado.
     * - texto base64: banner serializado del clan.
     */
    public String getPlayerClanBannerData(UUID playerUuid) {
        if (playerUuid == null) return null;
        try {
            Optional<Member> member = getMember(playerUuid);
            if (member.isEmpty()) return null;
            Optional<Clan> clan = getClan(member.get().clanId());
            if (clan.isEmpty()) return null;
            String banner = clan.get().banner();
            return banner == null ? "" : banner;
        } catch (SQLException ignored) {
            return null;
        }
    }

    private List<String> memberNamesOfSenderClan(Player player) throws SQLException {
        Optional<Member> member = getMember(player.getUniqueId());
        if (member.isEmpty()) return Collections.emptyList();
        return getMembers(member.get().clanId()).stream().map(Member::name).collect(Collectors.toList());
    }

    public record Clan(int id, String tag, String name, UUID ownerUuid, boolean open, long createdAt,
                       int tier, double bankBalance, String banner, String storage, String boardMessage, String description,
                       String baseWorld, double baseX, double baseY, double baseZ, float baseYaw, float basePitch) {
        boolean hasBase() { return baseWorld != null && !baseWorld.isBlank(); }
        boolean hasBanner() { return banner != null && !banner.isBlank(); }
        boolean hasStorage() { return storage != null && !storage.isBlank(); }
    }

    public record ClanLog(int id, long time, String actorName, String action, String detail) {}

    public record ClanTopEntry(Clan clan, double value) {}

    public record ClanMail(int id, int fromClanId, int toClanId, String senderName, long sentAt, String message, String mailType, int relationClanId) {}

    public record ClanRelationView(Clan clan, String relation) {}

    public record ClanTier(int tier, String name, double cost, int maxMembers, int maxBases, int storageSlots, int storagePages, int maxAllies) {}

    public record ClanBase(int clanId, int baseNumber, String world, double x, double y, double z, float yaw, float pitch) {}

    public record PlayerProfileSnapshot(String level, String race) {
        public static PlayerProfileSnapshot empty() { return new PlayerProfileSnapshot("", ""); }
    }

    public record ClanJoinRequest(UUID uuid, String name, long requestedAt) {}

    private record ClanMenuHolder(String menu, int page, int clanId, UUID targetUuid, int mailId) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private record StorageInventoryHolder(int clanId, int page, int totalSlots) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private static final class BannerEditorHolder implements InventoryHolder {
        private final int clanId;
        private final String originalBannerData;
        private ItemStack submittedBanner;
        private boolean completed;

        private BannerEditorHolder(int clanId, String originalBannerData) {
            this.clanId = clanId;
            this.originalBannerData = originalBannerData;
        }

        @Override public Inventory getInventory() { return null; }
    }

    private static final class BannerViewHolder implements InventoryHolder {
        private final int clanId;
        private final ItemStack bannerTemplate;

        private BannerViewHolder(int clanId, ItemStack bannerTemplate) {
            this.clanId = clanId;
            this.bannerTemplate = bannerTemplate.clone();
        }

        private ItemStack newCopy() {
            ItemStack copy = bannerTemplate.clone();
            copy.setAmount(1);
            return copy;
        }

        @Override public Inventory getInventory() { return null; }
    }

    public record Member(int clanId, UUID uuid, String name, int role, long joinedAt, boolean chatToggle) {}

    private record PendingTeleport(int taskId, Location start) {}

    private final class MDVClansExpansion extends PlaceholderExpansion {
        @Override
        public String getIdentifier() {
            return "mdvclans";
        }

        @Override
        public String getAuthor() {
            return "MDVCRAFT";
        }

        @Override
        public String getVersion() {
            return MDVClansPlugin.this.getDescription().getVersion();
        }

        @Override
        public boolean persist() {
            return true;
        }

        private String targetClanPlaceholder(String params) throws SQLException {
            if (params == null || params.isBlank()) return null;
            String lower = params.toLowerCase(Locale.ROOT);

            String target;
            if (lower.startsWith("clan_line_of_")) {
                target = params.substring("clan_line_of_".length());
                return targetClanLine(target);
            }
            if (lower.startsWith("clan_name_of_")) {
                target = params.substring("clan_name_of_".length());
                return targetClanValue(target, "name");
            }
            if (lower.startsWith("clan_id_of_")) {
                target = params.substring("clan_id_of_".length());
                return targetClanValue(target, "id");
            }
            if (lower.startsWith("clan_tag_of_")) {
                target = params.substring("clan_tag_of_".length());
                return targetClanValue(target, "tag");
            }
            if (lower.startsWith("clan_lpc_tag_of_")) {
                target = params.substring("clan_lpc_tag_of_".length());
                return targetClanValue(target, "lpc_tag");
            }
            if (lower.startsWith("clan_tier_name_of_")) {
                target = params.substring("clan_tier_name_of_".length());
                return targetClanValue(target, "tier_name");
            }
            if (lower.startsWith("clan_tier_of_")) {
                target = params.substring("clan_tier_of_".length());
                return targetClanValue(target, "tier");
            }
            if (lower.startsWith("clan_role_of_")) {
                target = params.substring("clan_role_of_".length());
                return targetClanValue(target, "role");
            }
            if (lower.startsWith("clan_role_number_of_")) {
                target = params.substring("clan_role_number_of_".length());
                return targetClanValue(target, "role_number");
            }
            if (lower.startsWith("clan_members_of_")) {
                target = params.substring("clan_members_of_".length());
                return targetClanValue(target, "members");
            }
            if (lower.startsWith("clan_max_members_of_")) {
                target = params.substring("clan_max_members_of_".length());
                return targetClanValue(target, "max_members");
            }
            if (lower.startsWith("clan_is_in_clan_of_")) {
                target = params.substring("clan_is_in_clan_of_".length());
                return targetClanIsInClan(target);
            }

            // Aliases shorter for convenience.
            if (lower.startsWith("name_of_")) {
                target = params.substring("name_of_".length());
                return targetClanValue(target, "name");
            }
            if (lower.startsWith("id_of_")) {
                target = params.substring("id_of_".length());
                return targetClanValue(target, "id");
            }
            if (lower.startsWith("tier_name_of_")) {
                target = params.substring("tier_name_of_".length());
                return targetClanValue(target, "tier_name");
            }
            if (lower.startsWith("is_in_clan_of_")) {
                target = params.substring("is_in_clan_of_".length());
                return targetClanIsInClan(target);
            }

            return null;
        }

        private String targetClanIsInClan(String target) throws SQLException {
            Optional<Member> member = resolveMemberByNameOrUuid(target);
            if (member.isEmpty()) return "false";
            return getClan(member.get().clanId()).isPresent() ? "true" : "false";
        }

        private String targetNoClanText() {
            return color(getConfig().getString("placeholders.other-no-clan", "&8Sin clan"));
        }

        private Optional<Clan> targetClan(String target) throws SQLException {
            Optional<Member> member = resolveMemberByNameOrUuid(target);
            if (member.isEmpty()) return Optional.empty();
            return getClan(member.get().clanId());
        }

        private String targetClanLine(String target) throws SQLException {
            Optional<Member> memberOpt = resolveMemberByNameOrUuid(target);
            if (memberOpt.isEmpty()) return targetNoClanText();
            Optional<Clan> clanOpt = getClan(memberOpt.get().clanId());
            if (clanOpt.isEmpty()) return targetNoClanText();
            Clan clan = clanOpt.get();
            String format = getConfig().getString("placeholders.clan-line-format", "&5{tier_name} &8- &f{name} &8[&6{id}&8]");
            return color(format
                    .replace("{id}", clan.tag())
                    .replace("{tag}", clan.tag())
                    .replace("{name}", clan.name())
                    .replace("{tier}", String.valueOf(clan.tier()))
                    .replace("{tier_name}", tierName(clan.tier()))
                    .replace("{role}", getRoleName(clan.id(), memberOpt.get().role()))
                    .replace("{role_number}", String.valueOf(memberOpt.get().role()))
                    .replace("{members}", String.valueOf(countMembers(clan.id())))
                    .replace("{max_members}", String.valueOf(maxMembersForClan(clan))));
        }

        private String targetClanValue(String target, String key) throws SQLException {
            Optional<Member> memberOpt = resolveMemberByNameOrUuid(target);
            if (memberOpt.isEmpty()) return targetNoClanText();
            Optional<Clan> clanOpt = getClan(memberOpt.get().clanId());
            if (clanOpt.isEmpty()) return targetNoClanText();
            Member member = memberOpt.get();
            Clan clan = clanOpt.get();
            return switch (key) {
                case "id" -> clan.tag();
                case "name" -> clan.name();
                case "tag" -> color(getConfig().getString("placeholders.tag-format", "&8[&b{id}&8]&r").replace("{id}", clan.tag()).replace("{name}", clan.name()));
                case "lpc_tag" -> color(getConfig().getString("placeholders.lpc-tag-format", "&8[&b{id}&8]&r ").replace("{id}", clan.tag()).replace("{name}", clan.name()));
                case "tier" -> String.valueOf(clan.tier());
                case "tier_name" -> tierName(clan.tier());
                case "role" -> getRoleName(clan.id(), member.role());
                case "role_number" -> String.valueOf(member.role());
                case "members" -> String.valueOf(countMembers(clan.id()));
                case "max_members" -> String.valueOf(maxMembersForClan(clan));
                default -> targetNoClanText();
            };
        }

        @Override
        public String onPlaceholderRequest(Player player, String params) {
            try {
                String targetValue = targetClanPlaceholder(params);
                if (targetValue != null) return targetValue;
                if (player == null) return "";
                Optional<Member> memberOpt = getMember(player.getUniqueId());
                String noClan = getConfig().getString("placeholders.no-clan", "");
                if (memberOpt.isEmpty()) {
                    return switch (params.toLowerCase(Locale.ROOT)) {
                        // Placeholder dedicado al chat: nunca muestra textos como "Sin clan".
                        case "chat_prefix" -> "";
                        case "id", "name", "tag", "lpc_tag", "role", "role_number", "members", "member_count", "clan_members", "bank", "kills", "deaths", "strength", "tier", "tier_name", "max_members", "max_bases", "max_allies", "storage_slots", "storage_pages", "storage_display", "allies", "bases", "description", "description_plain", "description_line_1", "description_line_2", "description_line_3", "description_line_4", "description_line_5", "board", "board_plain", "board_line_1", "board_line_2", "board_line_3", "board_line_4", "board_line_5", "board_line_6", "board_line_7", "board_line_8", "board_line_9", "board_line_10", "is_in_clan" -> params.equalsIgnoreCase("is_in_clan") ? "false" : noClan;
                        default -> noClan;
                    };
                }
                Member member = memberOpt.get();
                Optional<Clan> clanOpt = getClan(member.clanId());
                if (clanOpt.isEmpty()) return params.equalsIgnoreCase("chat_prefix") ? "" : noClan;
                Clan clan = clanOpt.get();
                return switch (params.toLowerCase(Locale.ROOT)) {
                    case "id" -> clan.tag();
                    case "name" -> clan.name();
                    case "tag" -> color(getConfig().getString("placeholders.tag-format", "&8[&b{id}&8]&r").replace("{id}", clan.tag()).replace("{name}", clan.name()));
                    case "lpc_tag" -> color(getConfig().getString("placeholders.lpc-tag-format", "&8[&b{id}&8]&r ").replace("{id}", clan.tag()).replace("{name}", clan.name()));
                    case "chat_prefix" -> color(getConfig().getString("placeholders.chat-prefix-format", "&8[&6{id}&8]&r ").replace("{id}", clan.tag()).replace("{tag}", clan.tag()).replace("{name}", clan.name()));
                    case "role" -> getRoleName(clan.id(), member.role());
                    case "role_number" -> String.valueOf(member.role());
                    case "members", "member_count", "clan_members" -> String.valueOf(countMembers(clan.id()));
                    case "tier" -> String.valueOf(clan.tier());
                    case "tier_name" -> tierName(clan.tier());
                    case "max_members" -> String.valueOf(maxMembersForClan(clan));
                    case "max_bases" -> String.valueOf(maxBasesForClan(clan));
                    case "max_allies" -> String.valueOf(maxAlliesForClan(clan));
                    case "storage_slots" -> String.valueOf(storageSlotsForClan(clan));
                    case "storage_pages" -> String.valueOf(storagePagesForSlots(storageSlotsForClan(clan)));
                    case "storage_display" -> storageDisplay(clan);
                    case "allies" -> String.valueOf(countAllies(clan.id()));
                    case "bases" -> String.valueOf(countClanBases(clan.id()));
                    case "bank" -> formatNumber(clan.bankBalance());
                    case "kills" -> String.valueOf(getTotalKillsByClan(clan.id()));
                    case "deaths" -> String.valueOf(getTotalDeathsByClan(clan.id()));
                    case "strength" -> formatNumber(calculateStrength(clan));
                    case "description" -> color(clanDescription(clan));
                    case "description_plain" -> ChatColor.stripColor(color(clanDescription(clan)));
                    case "description_line_1" -> color(descriptionLine(clan.description(), 0));
                    case "description_line_2" -> color(descriptionLine(clan.description(), 1));
                    case "description_line_3" -> color(descriptionLine(clan.description(), 2));
                    case "description_line_4" -> color(descriptionLine(clan.description(), 3));
                    case "description_line_5" -> color(descriptionLine(clan.description(), 4));
                    case "board" -> color(String.join(" &8| &f", boardLines(clan.boardMessage())));
                    case "board_plain" -> ChatColor.stripColor(color(String.join(" | ", boardLines(clan.boardMessage()))));
                    case "board_line_1" -> color(boardLine(clan.boardMessage(), 0));
                    case "board_line_2" -> color(boardLine(clan.boardMessage(), 1));
                    case "board_line_3" -> color(boardLine(clan.boardMessage(), 2));
                    case "board_line_4" -> color(boardLine(clan.boardMessage(), 3));
                    case "board_line_5" -> color(boardLine(clan.boardMessage(), 4));
                    case "board_line_6" -> color(boardLine(clan.boardMessage(), 5));
                    case "board_line_7" -> color(boardLine(clan.boardMessage(), 6));
                    case "board_line_8" -> color(boardLine(clan.boardMessage(), 7));
                    case "board_line_9" -> color(boardLine(clan.boardMessage(), 8));
                    case "board_line_10" -> color(boardLine(clan.boardMessage(), 9));
                    case "is_in_clan" -> "true";
                    case "open" -> clan.open() ? "true" : "false";
                    default -> noClan;
                };
            } catch (SQLException e) {
                return "";
            }
        }
    }
}
