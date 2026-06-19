package com.mdvcraft.mdvclans;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
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
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
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

    private Connection connection;
    private Economy economy;
    private Pattern idPattern;
    private FileConfiguration messages;
    private File messagesFile;

    private static final int[] PAGE_CONTENT_SLOTS = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34,37,38,39,40,41,42,43};
    private static final int GUI_PAGE_SIZE = PAGE_CONTENT_SLOTS.length;

    private final Map<UUID, PendingTeleport> pendingTeleports = new ConcurrentHashMap<>();
    private final Map<UUID, Long> baseCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> friendlyFireMessageCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> openStorageViewers = new ConcurrentHashMap<>();
    private final Set<UUID> pendingClanCreate = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingBoardEdit = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingClanNameEdit = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingClanTagEdit = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> pendingRoleNameEdit = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingClanMailReply = new ConcurrentHashMap<>();
    private int nametagTaskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("messages.yml");
        loadMessages();
        saveMenuTemplates();
        reloadLocalSettings();
        setupEconomy();

        try {
            openDatabase();
            createTables();
            cleanupExpiredInvites();
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

        getLogger().info("MDVClans 1.6.2 habilitado.");
    }

    @Override
    public void onDisable() {
        for (PendingTeleport pending : pendingTeleports.values()) {
            Bukkit.getScheduler().cancelTask(pending.taskId());
        }
        pendingTeleports.clear();
        if (nametagTaskId != -1) {
            Bukkit.getScheduler().cancelTask(nametagTaskId);
            nametagTaskId = -1;
        }
        closeDatabase();
    }

    private void loadMessages() {
        messagesFile = new File(getDataFolder(), "messages.yml");
        messages = YamlConfiguration.loadConfiguration(messagesFile);
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
                    "bank_balance REAL NOT NULL DEFAULT 0," +
                    "banner TEXT," +
                    "storage TEXT," +
                    "board_message TEXT," +
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
                    "deleted INTEGER NOT NULL DEFAULT 0," +
                    "FOREIGN KEY(from_clan_id) REFERENCES clans(id) ON DELETE CASCADE," +
                    "FOREIGN KEY(to_clan_id) REFERENCES clans(id) ON DELETE CASCADE" +
                    ")");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_members_clan ON members(clan_id)");
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
        addColumnIfMissing("clans", "bank_balance", "REAL NOT NULL DEFAULT 0");
        addColumnIfMissing("clans", "banner", "TEXT");
        addColumnIfMissing("clans", "storage", "TEXT");
        addColumnIfMissing("clans", "board_message", "TEXT");
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
            switch (sub) {
                case "crear", "create" -> handleCreate(player, args);
                case "menu", "gui", "menú" -> handleClanMenuCommand(player, args);
                case "info" -> handleInfo(player, args);
                case "lista", "list" -> handleList(player, args);
                case "invitar", "invite" -> handleInvite(player, args);
                case "aceptar", "accept" -> handleAccept(player, args);
                case "unirse", "join" -> handleJoin(player, args);
                case "abierto", "open" -> handleOpen(player, args);
                case "salir", "leave" -> handleLeave(player);
                case "expulsar", "kick" -> handleKick(player, args);
                case "promover", "promote" -> handlePromote(player, args);
                case "degradar", "demote" -> handleDemote(player, args);
                case "setrango", "setrank" -> handleSetRank(player, args);
                case "rol", "rango", "rolnombre" -> handleRoleName(player, args);
                case "chat" -> handleClanChatCommand(player, args, 1);
                case "c" -> handleClanChatCommand(player, args, 1);
                case "setbase" -> handleSetBase(player);
                case "base", "home" -> handleBase(player);
                case "relacion", "relation" -> handleRelation(player, args);
                case "banco" -> handleBank(player, args);
                case "depositar" -> handleBankShortcut(player, args, true);
                case "retirar" -> handleBankShortcut(player, args, false);
                case "almacen", "almacén" -> handleStorage(player);
                case "estandarte", "banner" -> handleBanner(player, args);
                case "logs", "registro", "registros" -> handleLogs(player, args);
                case "tablero" -> handleBoard(player, args);
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

    private boolean handleAdminCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mdvclans.admin")) {
            sender.sendMessage(color("&cNo tienes permiso."));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadMessages();
            reloadLocalSettings();
            if (nametagTaskId != -1) Bukkit.getScheduler().cancelTask(nametagTaskId);
            nametagTaskId = -1;
            startNametagTask();
            sender.sendMessage(color("&aMDVClans recargado."));
            return true;
        }
        sender.sendMessage(color("&6MDVClans admin: &e/mdvclans reload"));
        return true;
    }

    private void sendHelp(Player player) {
        msg(player, "&6MDVClans &7comandos:");
        player.sendMessage(color("&e/clan crear <ID> <nombre> &7- Crea un clan."));
        player.sendMessage(color("&e/clan info [ID] &7- Muestra información."));
        player.sendMessage(color("&e/clan lista &7- Lista los clanes."));
        player.sendMessage(color("&e/clan invitar <jugador> &7- Invita a alguien."));
        player.sendMessage(color("&e/clan aceptar [ID] &7- Acepta invitación."));
        player.sendMessage(color("&e/clan unirse <ID> &7- Entra a un clan abierto."));
        player.sendMessage(color("&e/clan abierto <on/off> &7- Cambia entrada libre."));
        player.sendMessage(color("&e/clan chat [mensaje] &7- Alterna o habla por clan."));
        player.sendMessage(color("&e/clan setbase &7/ &e/clan base &7- Base del clan."));
        player.sendMessage(color("&e/clan relacion <ID> <neutral|aliado|enemigo>"));
        player.sendMessage(color("&e/clan rol <0-5> <nombre> &7- Nombra un rango."));
        player.sendMessage(color("&e/clan editar <nombre|id> <valor> &7- Ajustes del clan."));
        player.sendMessage(color("&e/clan solicitudes &7- Solicitudes pendientes."));
        player.sendMessage(color("&e/clan banco &7- Banco del clan."));
        player.sendMessage(color("&e/clan almacen &7- Almacén compartido."));
        player.sendMessage(color("&e/clan estandarte set/ver &7- Banner oficial."));
        player.sendMessage(color("&e/clan top [fuerza|kills|banco] &7- Ranking."));
        player.sendMessage(color("&e/clan bajas &7- Estadísticas de kills."));
        player.sendMessage(color("&e/clan logs &7- Registro básico."));
        player.sendMessage(color("&e/clan menu &7- Abre la interfaz principal."));
        player.sendMessage(color("&e/clan tablero set <texto> &7- Edita el tablero."));
        player.sendMessage(color("&e/clan correo clan <ID> <mensaje> &7- Envía correo a otro clan."));
    }

    private void handleCreate(Player player, String[] args) throws SQLException {
        if (!getConfig().getBoolean("creation.enabled", true)) {
            msg(player, "&cLa creación de clanes está desactivada.");
            return;
        }
        if (args.length < 3) {
            msg(player, "&cUso: &e/clan crear <ID> <nombre del clan>");
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
        player.sendMessage(color("&6Clan: &8[&b" + clan.tag() + "&8] &f" + clan.name()));
        player.sendMessage(color("&7Miembros: &e" + members.size() + "&7/&e" + getConfig().getInt("limits.max-members", 20)));
        player.sendMessage(color("&7Entrada: " + (clan.open() ? "&aAbierta" : "&cCon invitación")));
        player.sendMessage(color("&7Base: " + (clan.hasBase() ? "&aDefinida" : "&cNo definida")));
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
            player.sendMessage(color("&8[&b" + c.tag() + "&8] &f" + c.name() + " &7- &e" + count + " &7miembros &8| &6Fuerza &e" + formatNumber(calculateStrength(c))));
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
        if (countMembers(clan.id()) >= getConfig().getInt("limits.max-members", 20)) {
            msg(player, "&cTu clan ya alcanzó el límite de miembros.");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg(player, "&cEse jugador no está conectado.");
            return;
        }
        if (getMember(target.getUniqueId()).isPresent()) {
            msg(player, "&cEse jugador ya pertenece a un clan.");
            return;
        }
        long expires = System.currentTimeMillis() + getConfig().getLong("invites.expire-seconds", 120) * 1000L;
        synchronized (this) {
            try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO invites(clan_id,target_uuid,inviter_uuid,expires_at) VALUES(?,?,?,?)")) {
                ps.setInt(1, clan.id());
                ps.setString(2, target.getUniqueId().toString());
                ps.setString(3, player.getUniqueId().toString());
                ps.setLong(4, expires);
                ps.executeUpdate();
            }
        }
        msg(player, "&aInvitaste a &e" + target.getName() + " &aal clan.");
        msg(target, "&aRecibiste una invitación del clan &8[&b" + clan.tag() + "&8] &f" + clan.name() + "&a.");
        target.sendMessage(color("&7Usa &e/clan aceptar " + clan.tag() + " &7para entrar."));
    }

    private void handleAccept(Player player, String[] args) throws SQLException {
        if (getMember(player.getUniqueId()).isPresent()) {
            msg(player, "&cYa perteneces a un clan.");
            return;
        }
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
        joinClan(player, clan, "&aAceptaste la invitación y entraste al clan &8[&b" + clan.tag() + "&8]&a.");
        removeInvite(clan.id(), player.getUniqueId());
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

    private void joinClan(Player player, Clan clan, String successMessage) throws SQLException {
        if (countMembers(clan.id()) >= getConfig().getInt("limits.max-members", 20)) {
            msg(player, "&cEse clan ya alcanzó el límite de miembros.");
            return;
        }
        synchronized (this) {
            addMemberUnsafe(clan.id(), player.getUniqueId(), player.getName(), minRole(), System.currentTimeMillis(), 0);
            deleteJoinRequestsForPlayerUnsafe(player.getUniqueId());
        }
        msg(player, successMessage);
        broadcastToClan(clan.id(), "&e" + player.getName() + " &ase unió al clan.");
        logAction(clan.id(), player, "UNIRSE", player.getName() + " se unió al clan.");
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

    private void handleLeave(Player player) throws SQLException {
        Member member = requireMember(player);
        if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        if (member.role() >= maxRole()) {
            if (countMembers(clan.id()) > 1) {
                msg(player, "&cEres líder. Pasa el liderazgo o disuelve el clan antes de salir.");
                return;
            }
            disbandClan(clan.id());
            msg(player, "&cDisolviste tu clan al salir.");
            return;
        }
        logAction(clan.id(), player, "SALIR", player.getName() + " salió del clan.");
        removeMember(player.getUniqueId());
        msg(player, "&aSaliste del clan.");
        broadcastToClan(clan.id(), "&e" + player.getName() + " &7salió del clan.");
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
            msg(player, "&cUso: &e/clan setrango <jugador> <0-5>");
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
        Member target = targetOpt.get();
        if (!target.uuid().equals(player.getUniqueId()) && target.role() >= actor.role()) {
            msg(player, "&cNo puedes modificar a alguien de rango igual o mayor.");
            return;
        }
        setMemberRole(target.uuid(), role);
        logAction(actor.clanId(), player, "SETRANGO", target.name() + " a rango " + role);
        broadcastToClan(actor.clanId(), "&e" + target.name() + " &7ahora es &b" + getRoleName(actor.clanId(), role) + "&7.");
    }

    private void handleRoleName(Player player, String[] args) throws SQLException {
        if (args.length < 3) {
            msg(player, "&cUso: &e/clan rol <0-5> <nombre>");
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

    private void handleSetBase(Player player) throws SQLException {
        Member member = requireMember(player);
        if (member == null) return;
        if (!hasRank(player, member, "setbase")) return;
        if (!isWorldAllowed(player.getWorld())) {
            msg(player, "&cNo puedes fijar base de clan en este mundo.");
            return;
        }
        setClanBase(member.clanId(), player.getLocation());
        logAction(member.clanId(), player, "SETBASE", "Base actualizada en " + player.getWorld().getName());
        broadcastToClan(member.clanId(), "&aLa base del clan fue actualizada por &e" + player.getName() + "&a.");
    }

    private void handleBase(Player player) throws SQLException {
        Member member = requireMember(player);
        if (member == null) return;
        Optional<Clan> clanOpt = getClan(member.clanId());
        if (clanOpt.isEmpty() || !clanOpt.get().hasBase()) {
            msg(player, "&cTu clan no tiene base definida.");
            return;
        }
        Clan clan = clanOpt.get();
        World world = Bukkit.getWorld(clan.baseWorld());
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
        Location destination = new Location(world, clan.baseX(), clan.baseY(), clan.baseZ(), clan.baseYaw(), clan.basePitch());
        if (delay <= 0) {
            player.teleport(destination);
            baseCooldowns.put(player.getUniqueId(), now);
            msg(player, "&aTeletransportado a la base del clan.");
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
            msg(player, "&aTeletransportado a la base del clan.");
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
        if (equalsAny(mode, "neutral", "neutro")) {
            removeRelation(own.id(), target.id());
            removeRelation(target.id(), own.id());
            logAction(own.id(), player, "RELACION", "Neutral con " + target.tag());
            msg(player, "&7Relación con &e" + target.tag() + " &7establecida como neutral.");
            notifyClan(target.id(), "&7El clan &e" + own.tag() + " &7estableció relación neutral con ustedes.");
            return;
        }
        if (equalsAny(mode, "enemigo", "enemy")) {
            setRelation(own.id(), target.id(), REL_ENEMY);
            removeRelation(target.id(), own.id(), REL_ALLY);
            removeRelation(target.id(), own.id(), REL_ALLY_REQUEST);
            logAction(own.id(), player, "RELACION", "Enemigo: " + target.tag());
            broadcastToClan(own.id(), "&cTu clan declaró enemigo a &e" + target.tag() + "&c.");
            notifyClan(target.id(), "&cEl clan &e" + own.tag() + " &clos declaró enemigos.");
            return;
        }
        if (equalsAny(mode, "aliado", "ally")) {
            boolean needsAccept = getConfig().getBoolean("relations.ally-requires-accept", true);
            if (!needsAccept || getRelation(target.id(), own.id()).equals(REL_ALLY_REQUEST)) {
                setRelation(own.id(), target.id(), REL_ALLY);
                setRelation(target.id(), own.id(), REL_ALLY);
                logAction(own.id(), player, "RELACION", "Alianza aceptada con " + target.tag());
                broadcastToClan(own.id(), "&9Ahora son aliados del clan &e" + target.tag() + "&9.");
                notifyClan(target.id(), "&9Ahora son aliados del clan &e" + own.tag() + "&9.");
            } else {
                setRelation(own.id(), target.id(), REL_ALLY_REQUEST);
                logAction(own.id(), player, "RELACION", "Solicitud de alianza a " + target.tag());
                msg(player, "&aSolicitud de alianza enviada a &e" + target.tag() + "&a.");
                notifyClan(target.id(), "&9El clan &e" + own.tag() + " &9quiere una alianza. Usa &e/clan relacion " + own.tag() + " aliado &9para aceptar.");
            }
            return;
        }
        msg(player, "&cRelación inválida: usa neutral, aliado o enemigo.");
    }




    private void handleClanMenuCommand(Player player, String[] args) throws SQLException {
        if (args.length <= 1) {
            openMainMenu(player);
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "principal", "completo", "panel" -> openFullClanHub(player);
            case "gestion", "gestión" -> openClanManagementMenu(player);
            case "miembros" -> openMembersMenu(player, 1);
            case "info" -> openClanInfoMenu(player);
            case "relaciones" -> openRelationsMenu(player);
            case "almacen", "almacén" -> openStorageHubMenu(player);
            case "lista", "clanes" -> openClanListMenu(player, 1);
            case "correo", "buzon", "buzón" -> openMailboxMenu(player, 1);
            case "top", "ranking" -> openTopGui(player, "fuerza");
            case "bajas" -> openKillStatsGui(player);
            case "logs" -> openLogsGui(player, 1);
            case "ajustes" -> openSettingsMenu(player);
            case "solicitudes" -> openJoinRequestsMenu(player, 1);
            default -> openMainMenu(player);
        }
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
        if (!getConfig().getBoolean("storage-chest.enabled", true)) {
            msg(player, "&cEl almacén de clan está desactivado.");
            return;
        }
        Member member = requireMember(player);
        if (member == null) return;
        if (!hasRank(player, member, "storage-open")) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        int size = Math.max(9, Math.min(54, getConfig().getInt("storage-chest.size", 27)));
        size = ((size + 8) / 9) * 9;
        String title = color(getConfig().getString("storage-chest.title", "&8Almacén de clan {id}").replace("{id}", clan.tag()).replace("{name}", clan.name()));
        Inventory inv = Bukkit.createInventory(player, size, title);
        ItemStack[] items = deserializeInventory(clan.storage(), size);
        inv.setContents(items);
        openStorageViewers.put(player.getUniqueId(), clan.id());
        player.openInventory(inv);
        logAction(clan.id(), player, "ALMACEN", "Abrió el almacén");
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
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item == null || item.getType() == Material.AIR || !item.getType().name().endsWith("BANNER")) {
                msg(player, "&cDebes tener un banner/estandarte en la mano.");
                return;
            }
            ItemStack copy = item.clone();
            copy.setAmount(1);
            try {
                setClanBanner(member.clanId(), itemToBase64(copy));
                logAction(member.clanId(), player, "ESTANDARTE", "Estandarte oficial actualizado");
                broadcastToClan(member.clanId(), "&aEl estandarte oficial del clan fue actualizado por &e" + player.getName() + "&a.");
            } catch (IOException e) {
                msg(player, "&cNo se pudo guardar el estandarte.");
            }
            return;
        }
        if (equalsAny(action, "ver", "view")) {
            if (!clan.hasBanner()) {
                msg(player, "&cTu clan no tiene estandarte oficial.");
                return;
            }
            try {
                Inventory inv = Bukkit.createInventory(player, 9, color("&8Estandarte " + clan.tag()));
                inv.setItem(4, itemFromBase64(clan.banner()));
                player.openInventory(inv);
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

    private void handleLogs(Player player, String[] args) throws SQLException {
        Member member = requireMember(player);
        if (member == null) return;
        if (!hasRank(player, member, "logs-view")) return;
        int page = 1;
        if (args.length >= 2) {
            try { page = Math.max(1, Integer.parseInt(args[1])); } catch (NumberFormatException ignored) {}
        }
        showLogs(player, member.clanId(), page, null);
    }

    private void showLogs(Player player, int clanId, int page, String actionFilter) throws SQLException {
        int pageSize = 8;
        List<ClanLog> logs = getLogs(clanId, actionFilter, page, pageSize);
        player.sendMessage(color("&8&m-----&r &6Registro de clan &7(" + page + ") &8&m-----"));
        if (logs.isEmpty()) {
            player.sendMessage(color("&7No hay registros para mostrar."));
            return;
        }
        for (ClanLog log : logs) {
            player.sendMessage(color("&8- &e" + log.action() + " &7| &f" + log.actorName() + " &8» &7" + log.detail()));
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

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Integer clanId = openStorageViewers.remove(player.getUniqueId());
        if (clanId == null) return;
        try {
            saveClanStorage(clanId, event.getInventory().getContents());
            logAction(clanId, player, "ALMACEN", "Cerró y guardó el almacén");
        } catch (Exception e) {
            getLogger().warning("No se pudo guardar almacén de clan " + clanId + ": " + e.getMessage());
            msg(player, "&cNo se pudo guardar el almacén del clan. Avisa a un admin.");
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (pendingClanCreate.remove(player.getUniqueId())) {
            event.setCancelled(true);
            String text = event.getMessage().trim();
            if (text.equalsIgnoreCase("cancelar")) {
                msg(player, "&7Creación de clan cancelada.");
                return;
            }
            String[] split = text.split(" ", 2);
            if (split.length < 2) {
                msg(player, "&cFormato inválido. Escribe: &eID Nombre del clan");
                return;
            }
            Bukkit.getScheduler().runTask(this, () -> {
                try { handleCreate(player, new String[]{"crear", split[0], split[1]}); }
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
        PendingTeleport pending = pendingTeleports.remove(event.getPlayer().getUniqueId());
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

    private boolean hasRank(Player player, Member member, String key) {
        int required = getConfig().getInt("rank-permissions." + key, maxRole());
        if (member.role() >= required || player.hasPermission("mdvclans.admin")) return true;
        msg(player, "&cNecesitas rango &e" + required + " &co superior para hacer eso.");
        return false;
    }

    private boolean can(Member member, String key) {
        int required = getConfig().getInt("rank-permissions." + key, maxRole());
        return member.role() >= required;
    }

    private boolean canModifyMember(Member actor, Member target, String key) {
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

    private int minRole() { return getConfig().getInt("roles.min", 0); }
    private int maxRole() { return getConfig().getInt("roles.max", 5); }

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
        try (PreparedStatement ps = connection.prepareStatement("UPDATE members SET role=? WHERE uuid=?")) {
            ps.setInt(1, role);
            ps.setString(2, uuid.toString());
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

    private synchronized void setClanBase(int clanId, Location loc) throws SQLException {
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
        if (viewerClanId <= 0 || targetClanId <= 0) return "NEUTRAL";
        if (viewerClanId == targetClanId) return "SAME";
        String rel = getRelation(viewerClanId, targetClanId);
        if (REL_ALLY.equals(rel) && REL_ALLY.equals(getRelation(targetClanId, viewerClanId))) return REL_ALLY;
        return rel;
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

    private synchronized void createClanMail(int fromClanId, int toClanId, UUID senderUuid, String senderName, String message) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO clan_mails(from_clan_id,to_clan_id,sender_uuid,sender_name,sent_at,message,deleted) VALUES(?,?,?,?,?,?,0)")) {
            ps.setInt(1, fromClanId);
            ps.setInt(2, toClanId);
            ps.setString(3, senderUuid == null ? null : senderUuid.toString());
            ps.setString(4, senderName == null ? "Sistema" : senderName);
            ps.setLong(5, System.currentTimeMillis());
            ps.setString(6, message);
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
                while (rs.next()) list.add(new ClanMail(rs.getInt("id"), rs.getInt("from_clan_id"), rs.getInt("to_clan_id"), rs.getString("sender_name"), rs.getLong("sent_at"), rs.getString("message")));
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
    }

    private synchronized List<ClanLog> getLogs(int clanId, String actionFilter, int page, int pageSize) throws SQLException {
        List<ClanLog> list = new ArrayList<>();
        int offset = Math.max(0, page - 1) * pageSize;
        String sql = actionFilter == null ?
                "SELECT * FROM clan_logs WHERE clan_id=? ORDER BY time DESC LIMIT ? OFFSET ?" :
                "SELECT * FROM clan_logs WHERE clan_id=? AND action=? ORDER BY time DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, clanId);
            if (actionFilter == null) {
                ps.setInt(2, pageSize);
                ps.setInt(3, offset);
            } else {
                ps.setString(2, actionFilter);
                ps.setInt(3, pageSize);
                ps.setInt(4, offset);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(new ClanLog(rs.getLong("time"), rs.getString("actor_name"), rs.getString("action"), rs.getString("detail")));
            }
        }
        return list;
    }


    private synchronized int countLogs(int clanId, String actionFilter) throws SQLException {
        String sql = actionFilter == null ?
                "SELECT COUNT(*) FROM clan_logs WHERE clan_id=?" :
                "SELECT COUNT(*) FROM clan_logs WHERE clan_id=? AND action=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, clanId);
            if (actionFilter != null) ps.setString(2, actionFilter);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
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

    private double calculateStrength(Clan clan) throws SQLException {
        double killWeight = getConfig().getDouble("top.strength.kill-weight", 10.0);
        double bankDivisor = Math.max(1.0, getConfig().getDouble("top.strength.bank-divisor", 10.0));
        double bankCap = getConfig().getDouble("top.strength.bank-cap", 500.0);
        double memberWeight = getConfig().getDouble("top.strength.member-weight", 5.0);
        return getTotalKillsByClan(clan.id()) * killWeight
                + Math.min(clan.bankBalance() / bankDivisor, bankCap)
                + countMembers(clan.id()) * memberWeight;
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
        Optional<Member> memberOpt = getMember(player.getUniqueId());
        if (memberOpt.isEmpty()) openNoClanMenu(player);
        else openClanManagementMenu(player);
    }

    private void openFullClanHub(Player player) throws SQLException {
        Member viewer = requireMember(player); if (viewer == null) return;
        Clan clan = getClan(viewer.clanId()).orElseThrow();
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("hub", 1, clan.id(), null, -1), 27, color("&8&lClan &b" + clan.tag()));
        fill(inv);
        inv.setItem(4, clanBannerItem(clan, "&6&l" + clan.name(), List.of("&7ID: &b" + clan.tag(), "&7Miembros: &e" + countMembers(clan.id()), "&7Banco: &e" + formatNumber(clan.bankBalance()), "&7Fuerza: &6" + formatNumber(calculateStrength(clan)))));
        inv.setItem(10, item(Material.PLAYER_HEAD, "&a&lMiembros", List.of("", "&7Cabezas de jugadores, roles", "&7y acciones de cada miembro.", "", "&eClick para abrir.")));
        inv.setItem(11, item(Material.WRITABLE_BOOK, "&e&lTablero e información", List.of("", "&7Banner, tablero, buzón,", "&7solicitudes y registros.", "", "&eClick para abrir.")));
        inv.setItem(12, item(Material.RED_BANNER, "&c&lRelaciones", List.of("", "&7Aliados, enemigos, bajas", "&7y ranking de clanes.", "", "&eClick para abrir.")));
        inv.setItem(13, item(Material.CHEST, "&6&lAlmacén y banco", List.of("", "&7Acceso al almacén", "&7y banco del clan.", "", "&eClick para abrir.")));
        inv.setItem(14, item(Material.ENDER_PEARL, "&b&lIr a la base", List.of("", "&7Ejecuta &f/clan base&7.", "", "&eClick para viajar.")));
        inv.setItem(15, item(Material.BOOK, "&b&lLista de clanes", List.of("", "&7Mira todos los clanes", "&7de MDVCRAFT.", "", "&eClick para abrir.")));
        inv.setItem(16, item(Material.BARRIER, "&c&lAbandonar clan", List.of("", "&7Ejecuta &f/clan salir&7.", "&8Cuidado, mi broc.", "", "&eClick para salir.")));
        inv.setItem(17, can(viewer, "settings") ? item(Material.COMPARATOR, "&6&lAjustes del clan", List.of("", "&7Nombre, ID, rangos, banner,", "&7permisos y disolver.", "", "&eClick para abrir.")) : lockedItem("&7Ajustes del clan", "&cRequiere rango alto."));
        inv.setItem(22, item(Material.ARROW, "&6&lGestión rápida", List.of("&7Volver al menú de gestión.")));
        inv.setItem(26, item(Material.BARRIER, "&c&lCerrar", List.of("&7Cierra el menú.")));
        player.openInventory(inv);
    }

    private void openNoClanMenu(Player player) throws SQLException {
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("noclan", 1, -1, null, -1), 27, color("&8&lClanes"));
        fill(inv);
        inv.setItem(11, item(Material.BOOK, "&b&lLista de clanes", List.of("", "&7Mira los clanes creados", "&7en MDVCRAFT.", "", "&eClick para abrir.")));
        inv.setItem(15, item(Material.EMERALD, "&a&lCrear clan", List.of("", "&7Te pedirá en el chat:", "&fID Nombre del clan", "", "&8Ejemplo: MDV Medieval Craft", "", "&eClick para empezar.")));
        inv.setItem(22, item(Material.BARRIER, "&c&lCerrar", List.of("&7Cierra el menú.")));
        player.openInventory(inv);
    }

    private void openClanManagementMenu(Player player) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("gestion", 1, clan.id(), null, -1), 27, color("&8&lGestión del clan"));
        fill(inv);
        inv.setItem(4, clanBannerItem(clan, "&6&l" + clan.name(), List.of("&7ID: &b" + clan.tag(), "&7Miembros: &e" + countMembers(clan.id()), "&7Banco: &e" + formatNumber(clan.bankBalance()), "&7Fuerza: &6" + formatNumber(calculateStrength(clan)), "", "&eClick para abrir el panel completo.")));
        inv.setItem(10, item(Material.ENDER_PEARL, "&b&lIr a la base", List.of("", "&7Teletranspórtate a la base", "&7definida por el clan.", "", "&eClick para viajar.")));
        inv.setItem(11, can(member, "setbase") ? item(Material.COMPASS, "&6&lFijar base", List.of("", "&7Define la base del clan", "&7en tu ubicación actual.", "", "&8Requiere rango configurado.")) : lockedItem("&7Fijar base", "&cNo tienes rango para esta función."));
        inv.setItem(13, item(Material.GOLD_NUGGET, "&e&lBanco del clan", List.of("", "&7Balance: &e" + formatNumber(clan.bankBalance()), "&7Depositar: &f/clan banco depositar cantidad", "&7Retirar: &f/clan banco retirar cantidad", "", "&eClick para consultar.")));
        inv.setItem(14, item(Material.CHEST, "&a&lAlmacén del clan", List.of("", "&7Abre el inventario compartido", "&7del clan.", "", "&eClick para abrir.")));
        inv.setItem(15, clanBannerItem(clan, "&f&lEstandarte oficial", List.of("", "&7Ver el banner oficial", "&7del clan.", "", "&eClick para ver.")));
        inv.setItem(16, can(member, "logs-view") ? item(Material.WRITABLE_BOOK, "&d&lRegistro del clan", List.of("", "&7Muestra acciones recientes:", "&8banco, miembros, base,", "&8relaciones y bajas.", "", "&eClick para ver.")) : lockedItem("&7Registro del clan", "&cNo tienes rango para esta función."));
        inv.setItem(22, item(Material.ARROW, "&6&lVolver", List.of("&7Volver al menú social.")));
        inv.setItem(26, item(Material.BARRIER, "&c&lCerrar", List.of("&7Cierra el menú.")));
        player.openInventory(inv);
    }

    private void openMembersMenu(Player player, int page) throws SQLException {
        Member viewer = requireMember(player); if (viewer == null) return;
        Clan clan = getClan(viewer.clanId()).orElseThrow();
        List<Member> members = getMembers(clan.id());
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("members", page, clan.id(), null, -1), 54, color("&8Miembros &b" + clan.tag()));
        fill(inv);
        int pageSize = GUI_PAGE_SIZE;
        int start = Math.max(0, page - 1) * pageSize;
        for (int i = start; i < Math.min(start + pageSize, members.size()); i++) {
            Member m = members.get(i);
            inv.setItem(contentSlot(i - start), memberHead(m, clan.id(), player));
        }
        nav(inv, page, members.size(), pageSize, "members", clan.id());
        inv.setItem(49, item(Material.ARROW, "&6&lVolver", List.of("&7Regresa al menú principal.")));
        player.openInventory(inv);
    }

    private void openClanInfoMenu(Player player) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("info", 1, clan.id(), null, -1), 27, color("&8Info &b" + clan.tag()));
        fill(inv);
        inv.setItem(10, clanBannerItem(clan, "&f&lEstandarte oficial", List.of("&7Click: ver", "&8Para cambiarlo usa Ajustes del clan.", "&8Sin banner: WHITE_BANNER por defecto")));
        inv.setItem(12, item(Material.WRITABLE_BOOK, "&e&lTablero de información", boardItemLore(clan)));
        inv.setItem(13, item(Material.PLAYER_HEAD, "&a&lSolicitudes pendientes", List.of("", "&7Jugadores que quieren entrar", "&7cuando el clan está restringido.", "", "&7Pendientes: &e" + countJoinRequests(clan.id()), "", "&eClick para abrir.")));
        inv.setItem(14, item(Material.CHEST, "&d&lBuzón del clan", List.of("", "&7Mensajes enviados por otros clanes.", "&7Todos pueden leer.", "&7Rangos altos pueden borrar/responder.", "", "&eClick para abrir.")));
        inv.setItem(16, item(Material.BOOK, "&6&lCreación y registros", List.of("", "&7Creado: &f" + date(clan.createdAt()), "&7Miembros: &e" + countMembers(clan.id()), "&7Banco: &e" + formatNumber(clan.bankBalance()), "", can(member, "logs-view") ? "&eClick para ver logs." : "&8No tienes rango para ver logs.")));
        inv.setItem(20, item(Material.KNOWLEDGE_BOOK, "&b&lPermisos y rangos", List.of("", "&7Mira qué puede hacer", "&7cada rango del clan.", "", "&eClick para abrir.")));
        inv.setItem(22, item(Material.ARROW, "&6&lVolver", List.of("&7Regresa al menú principal.")));
        player.openInventory(inv);
    }


    private void openRelationsMenu(Player player) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("relations", 1, member.clanId(), null, -1), 27, color("&8Relaciones"));
        fill(inv);
        inv.setItem(11, item(Material.BLUE_BANNER, "&9&lRelaciones con clanes", List.of("", "&7Muestra aliados y enemigos.", "&7Los neutrales se omiten.", "", "&eClick para abrir.")));
        inv.setItem(13, item(Material.IRON_SWORD, "&c&lKills y bajas", List.of("", "&7Banners de clanes que han", "&7matado miembros de tu clan.", "", "&eClick para abrir.")));
        inv.setItem(15, item(Material.NETHER_STAR, "&6&lRanking", List.of("", "&7Top por fuerza, kills", "&7y banco.", "", "&eClick para abrir.")));
        inv.setItem(22, item(Material.ARROW, "&6&lVolver", List.of("&7Regresa al menú principal.")));
        player.openInventory(inv);
    }

    private void openStorageHubMenu(Player player) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("storagehub", 1, clan.id(), null, -1), 27, color("&8Almacén y banco"));
        fill(inv);
        inv.setItem(11, item(Material.CHEST, "&a&lAlmacén del clan", List.of("", "&7Inventario compartido.", "&7Todos pueden usarlo según config.", "", "&eClick para abrir.")));
        inv.setItem(15, item(Material.GOLD_BLOCK, "&e&lBanco del clan", List.of("", "&7Balance: &e" + formatNumber(clan.bankBalance()), "", "&7Comandos:", "&f/clan banco depositar <cantidad>", "&f/clan banco retirar <cantidad>", "", "&eClick para ver balance.")));
        inv.setItem(22, item(Material.ARROW, "&6&lVolver", List.of("&7Regresa al menú principal.")));
        player.openInventory(inv);
    }

    private void openClanListMenu(Player player, int page) throws SQLException {
        List<Clan> clans = listClans();
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("clanlist", page, -1, null, -1), 54, color("&8Clanes de MDVCRAFT"));
        fill(inv);
        int pageSize = GUI_PAGE_SIZE;
        int start = Math.max(0, page - 1) * pageSize;
        boolean hasClan = getMember(player.getUniqueId()).isPresent();
        for (int i = start; i < Math.min(start + pageSize, clans.size()); i++) {
            Clan c = clans.get(i);
            List<String> lore = new ArrayList<>();
            lore.add("&7ID: &b" + c.tag());
            lore.add("&7Miembros: &e" + countMembers(c.id()) + "&7/&e" + getConfig().getInt("limits.max-members", 20));
            lore.add("&7Entrada: " + (c.open() ? "&aAbierta" : "&cInvitación"));
            lore.add("&7Fuerza: &6" + formatNumber(calculateStrength(c)));
            lore.add("");
            if (hasClan) {
                lore.add("&eClick para abrir opciones.");
                lore.add("&8Relaciones, correo e info.");
            } else {
                lore.add(c.open() ? "&eClick para unirte." : "&eClick para enviar solicitud.");
            }
            inv.setItem(contentSlot(i - start), clanBannerItem(c, "&8[&b" + c.tag() + "&8] &f" + c.name(), lore));
        }
        nav(inv, page, clans.size(), pageSize, "clanlist", -1);
        inv.setItem(49, item(Material.ARROW, "&6&lVolver", List.of("&7Regresa al menú principal.")));
        player.openInventory(inv);
    }

    private void openRelationsListMenu(Player player, int page) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        List<ClanRelationView> relations = getVisibleRelations(member.clanId());
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("relationslist", page, member.clanId(), null, -1), 54, color("&8Aliados y enemigos"));
        fill(inv);
        int pageSize = GUI_PAGE_SIZE, start = Math.max(0, page - 1) * pageSize;
        for (int i = start; i < Math.min(start + pageSize, relations.size()); i++) {
            ClanRelationView r = relations.get(i);
            String relName = REL_ALLY.equals(r.relation()) ? "&9Aliado" : REL_ENEMY.equals(r.relation()) ? "&cEnemigo" : "&eSolicitud";
            inv.setItem(contentSlot(i - start), clanBannerItem(r.clan(), relName + " &8[&b" + r.clan().tag() + "&8]", List.of("&7Clan: &f" + r.clan().name(), "&7Relación: " + relName, "", "&eClick para ver info.")));
        }
        nav(inv, page, relations.size(), pageSize, "relationslist", member.clanId());
        inv.setItem(49, item(Material.ARROW, "&6&lVolver", List.of("&7Regresa a Relaciones.")));
        player.openInventory(inv);
    }

    private void openKillStatsGui(Player player) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("bajas", 1, clan.id(), null, -1), 54, color("&8Kills y bajas"));
        fill(inv);
        inv.setItem(4, clanBannerItem(clan, "&a&lTu clan", List.of("&7Kills hechas: &a" + getTotalKillsByClan(clan.id()), "&7Bajas sufridas: &c" + getTotalDeathsByClan(clan.id()), "&7Fuerza: &6" + formatNumber(calculateStrength(clan)))));
        List<ClanTopEntry> suffered = getTopKillersAgainst(clan.id(), GUI_PAGE_SIZE);
        for (int i = 0; i < Math.min(GUI_PAGE_SIZE, suffered.size()); i++) {
            ClanTopEntry e = suffered.get(i);
            inv.setItem(contentSlot(i), clanBannerItem(e.clan(), "&c" + e.clan().tag() + " &7nos hizo &c" + (int)e.value() + " &7bajas", List.of("&7Clan: &f" + e.clan().name(), "&7Kills contra nosotros: &c" + (int)e.value())));
        }
        inv.setItem(49, item(Material.ARROW, "&6&lVolver", List.of("&7Regresa a Relaciones.")));
        player.openInventory(inv);
    }

    private void openTopGui(Player player, String mode) throws SQLException {
        if (!equalsAny(mode, "fuerza", "kills", "banco")) mode = "fuerza";
        List<ClanTopEntry> entries = topEntries(mode);
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("top:" + mode, 1, -1, null, -1), 54, color("&8Ranking: " + mode));
        fill(inv);
        inv.setItem(3, item(Material.NETHER_STAR, "&6&lTop fuerza", List.of("&eClick para ver.")));
        inv.setItem(4, item(Material.IRON_SWORD, "&c&lTop kills", List.of("&eClick para ver.")));
        inv.setItem(5, item(Material.GOLD_BLOCK, "&e&lTop banco", List.of("&eClick para ver.")));
        for (int i = 0; i < Math.min(GUI_PAGE_SIZE, entries.size()); i++) {
            ClanTopEntry e = entries.get(i);
            inv.setItem(contentSlot(i), clanBannerItem(e.clan(), "&e#" + (i + 1) + " &8[&b" + e.clan().tag() + "&8]", List.of("&7Clan: &f" + e.clan().name(), "&7Valor: &6" + formatNumber(e.value()), "&7Miembros: &e" + countMembers(e.clan().id()))));
        }
        inv.setItem(49, item(Material.ARROW, "&6&lVolver", List.of("&7Regresa a Relaciones.")));
        player.openInventory(inv);
    }

    private void openMailboxMenu(Player player, int page) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        List<ClanMail> mails = getClanMails(member.clanId(), page, GUI_PAGE_SIZE);
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("mailbox", page, member.clanId(), null, -1), 54, color("&8Buzón del clan"));
        fill(inv);
        for (int i = 0; i < mails.size(); i++) {
            ClanMail mail = mails.get(i);
            Optional<Clan> from = getClan(mail.fromClanId());
            ItemStack icon = from.map(c -> clanBannerItem(c, "&dCorreo #" + mail.id() + " &7de &b" + c.tag(), List.of("&7Enviado por: &f" + mail.senderName(), "&7Fecha: &f" + date(mail.sentAt()), "", "&f" + mail.message(), "", "&eClick: abrir opciones"))).orElse(item(Material.PAPER, "&dCorreo #" + mail.id(), List.of("&f" + mail.message())));
            inv.setItem(contentSlot(i), icon);
        }
        nav(inv, page, countClanMails(member.clanId()), GUI_PAGE_SIZE, "mailbox", member.clanId());
        inv.setItem(49, item(Material.ARROW, "&6&lVolver", List.of("&7Regresa a Información.")));
        player.openInventory(inv);
    }

    private void openLogsGui(Player player, int page) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        if (!hasRank(player, member, "logs-view")) return;
        List<ClanLog> logs = getLogs(member.clanId(), null, page, GUI_PAGE_SIZE);
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("logs", page, member.clanId(), null, -1), 54, color("&8Registros del clan"));
        fill(inv);
        for (int i = 0; i < logs.size(); i++) {
            ClanLog log = logs.get(i);
            inv.setItem(contentSlot(i), item(Material.PAPER, "&e" + log.action(), List.of("&7Actor: &f" + log.actorName(), "&7Fecha: &f" + date(log.time()), "", "&7" + log.detail())));
        }
        nav(inv, page, countLogs(member.clanId(), null), GUI_PAGE_SIZE, "logs", member.clanId());
        inv.setItem(49, item(Material.ARROW, "&6&lVolver", List.of("&7Regresa a Información.")));
        player.openInventory(inv);
    }


    private void openSettingsMenu(Player player) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("settings", 1, clan.id(), null, -1), 54, color("&8Ajustes &b" + clan.tag()));
        fill(inv);
        inv.setItem(10, can(member, "rename-clan") ? item(Material.NAME_TAG, "&e&lCambiar nombre", List.of("", "&7Nombre actual: &f" + clan.name(), "", "&eClick para escribir el nuevo nombre.")) : lockedItem("&7Cambiar nombre", "&cRequiere rango alto."));
        inv.setItem(11, can(member, "rename-tag") ? item(Material.OAK_SIGN, "&b&lCambiar ID", List.of("", "&7ID actual: &b" + clan.tag(), "&7El ID se ve en chat/listas.", "", "&eClick para escribir el nuevo ID.")) : lockedItem("&7Cambiar ID", "&cRequiere rango alto."));
        inv.setItem(12, can(member, "rename-role") ? item(Material.WRITABLE_BOOK, "&d&lNombres de rangos", List.of("", "&7Cambia los nombres visibles", "&7de los rangos 0-5.", "", "&eClick para abrir.")) : lockedItem("&7Nombres de rangos", "&cRequiere rango alto."));
        inv.setItem(13, can(member, "banner-set") ? clanBannerItem(clan, "&f&lCambiar banner", List.of("", "&7Usa el banner en tu mano.", "", "&eClick: guardar banner en mano", "&cClick derecho: quitar banner")) : lockedItem("&7Cambiar banner", "&cRequiere rango alto."));
        inv.setItem(14, item(Material.KNOWLEDGE_BOOK, "&b&lVer permisos", List.of("", "&7Muestra qué rango necesita", "&7cada acción del clan.", "", "&eClick para abrir.")));
        inv.setItem(15, can(member, "open") ? item(Material.OAK_DOOR, "&a&lEntrada del clan", List.of("", "&7Estado actual: " + (clan.open() ? "&aAbierto" : "&cSolo invitación"), "", "&eClick para alternar.")) : lockedItem("&7Entrada del clan", "&cRequiere rango alto."));
        inv.setItem(16, can(member, "disband") ? item(Material.TNT, "&4&lDisolver clan", List.of("", "&cAcción peligrosa.", "&7Usa el comando de confirmación.", "", "&eClick para instrucciones.")) : lockedItem("&7Disolver clan", "&cSolo el rango máximo."));
        inv.setItem(49, item(Material.ARROW, "&6&lVolver", List.of("&7Regresa al menú principal.")));
        player.openInventory(inv);
    }

    private void openRoleSettingsMenu(Player player) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("rolesettings", 1, clan.id(), null, -1), 27, color("&8Rangos &b" + clan.tag()));
        fill(inv);
        int[] slots = {10, 11, 12, 13, 14, 15};
        for (int role = minRole(); role <= maxRole() && role < slots.length; role++) {
            inv.setItem(slots[role], item(Material.PAPER, "&eRango " + role + " &8- &b" + getRoleName(clan.id(), role), List.of("", "&7Este es el nombre visible", "&7del rango " + role + ".", "", "&eClick para renombrar.")));
        }
        inv.setItem(22, item(Material.ARROW, "&6&lVolver", List.of("&7Regresa a ajustes.")));
        player.openInventory(inv);
    }

    private void openPermissionsMenu(Player player) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("permissions", 1, clan.id(), null, -1), 54, color("&8Permisos del clan"));
        fill(inv);
        List<String> keys = List.of("invite", "kick", "promote", "demote", "set-rank", "rename-role", "setbase", "relation", "open", "bank-deposit", "bank-withdraw", "storage-open", "banner-set", "logs-view", "board-edit", "mail-send", "mail-delete", "join-requests", "rename-clan", "rename-tag", "settings", "disband");
        int slotIndex = 0;
        for (String key : keys) {
            int req = getConfig().getInt("rank-permissions." + key, maxRole());
            inv.setItem(contentSlot(slotIndex++), item(Material.PAPER, "&e" + key, List.of("", "&7Rango requerido: &b" + req, "&7Nombre: &f" + getRoleName(clan.id(), Math.max(minRole(), Math.min(maxRole(), req))))));
        }
        inv.setItem(49, item(Material.ARROW, "&6&lVolver", List.of("&7Regresa a ajustes.")));
        player.openInventory(inv);
    }

    private void openJoinRequestsMenu(Player player, int page) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Clan clan = getClan(member.clanId()).orElseThrow();
        cleanupInvalidJoinRequests(clan.id());
        List<ClanJoinRequest> requests = getJoinRequests(clan.id(), page, GUI_PAGE_SIZE);
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("joinrequests", page, clan.id(), null, -1), 54, color("&8Solicitudes &b" + clan.tag()));
        fill(inv);
        boolean canManage = can(member, "join-requests");
        for (int i = 0; i < requests.size(); i++) {
            ClanJoinRequest req = requests.get(i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(req.uuid()));
            meta.setDisplayName(color("&e" + req.name()));
            List<String> lore = new ArrayList<>();
            Player online = Bukkit.getPlayer(req.uuid());
            lore.add(color("&7Estado: " + (online != null ? "&aConectado" : "&cDesconectado")));
            lore.add(color("&7Solicitud: &f" + date(req.requestedAt())));
            if (online != null && Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                String level = safePapi(online, "%mmocore_level%");
                String race = safePapi(online, "%mmocore_race%");
                if (!level.isBlank()) lore.add(color("&7Nivel: &e" + level));
                if (!race.isBlank()) lore.add(color("&7Raza: &d" + race));
            } else lore.add(color("&7Nivel/Raza: &8solo si está online"));
            lore.add("");
            if (canManage) {
                lore.add(color("&aClick izquierdo: aceptar"));
                lore.add(color("&cClick derecho: borrar"));
            } else lore.add(color("&8No tienes rango para gestionar."));
            meta.setLore(lore); head.setItemMeta(meta);
            inv.setItem(contentSlot(i), head);
        }
        nav(inv, page, countJoinRequests(clan.id()), GUI_PAGE_SIZE, "joinrequests", clan.id());
        inv.setItem(49, item(Material.ARROW, "&6&lVolver", List.of("&7Regresa a Información.")));
        player.openInventory(inv);
    }

    private void openMemberActionMenu(Player player, UUID targetUuid) throws SQLException {
        Member actor = requireMember(player); if (actor == null) return;
        Optional<Member> targetOpt = getMember(targetUuid);
        if (targetOpt.isEmpty() || targetOpt.get().clanId() != actor.clanId()) { msg(player, "&cEse jugador ya no está en tu clan."); return; }
        Member target = targetOpt.get();
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("memberaction", 1, actor.clanId(), target.uuid(), -1), 27, color("&8Miembro &b" + target.name()));
        fill(inv);
        inv.setItem(4, memberHead(target, actor.clanId(), player));
        inv.setItem(10, item(Material.MAP, "&d&lCorreo personal", List.of("", "&7Abre/usa MDVSocial para", "&7mandarle una carta personal.", "", "&eClick para instrucciones.")));
        inv.setItem(11, canModifyMember(actor, target, "promote") ? item(Material.LIME_DYE, "&a&lPromover", List.of("", "&7Sube un rango al miembro.", "", "&eClick para promover.")) : lockedItem("&7Promover", "&cNo tienes rango para esta función."));
        inv.setItem(12, canModifyMember(actor, target, "demote") ? item(Material.YELLOW_DYE, "&e&lDegradar", List.of("", "&7Baja un rango al miembro.", "", "&eClick para degradar.")) : lockedItem("&7Degradar", "&cNo tienes rango para esta función."));
        inv.setItem(14, canModifyMember(actor, target, "kick") ? item(Material.RED_DYE, "&c&lExpulsar", List.of("", "&7Expulsa al miembro del clan.", "&cAcción delicada.", "", "&eClick para expulsar.")) : lockedItem("&7Expulsar", "&cNo tienes rango para esta función."));
        inv.setItem(16, item(Material.BOOK, "&b&lVer perfil", List.of("", "&7Información básica del jugador.", "&7Más perfil se puede conectar", "&7con MDVSocial/MMOCore luego.")));
        inv.setItem(22, item(Material.ARROW, "&6&lVolver", List.of("&7Regresa a miembros.")));
        player.openInventory(inv);
    }

    private void openClanActionMenu(Player player, int targetClanId) throws SQLException {
        Optional<Clan> targetOpt = getClan(targetClanId);
        if (targetOpt.isEmpty()) { msg(player, "&cClan no encontrado."); return; }
        Clan target = targetOpt.get();
        Optional<Member> own = getMember(player.getUniqueId());
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("clanaction", 1, target.id(), null, -1), 27, color("&8Clan &b" + target.tag()));
        fill(inv);
        inv.setItem(4, clanBannerItem(target, "&8[&b" + target.tag() + "&8] &f" + target.name(), List.of("&7Miembros: &e" + countMembers(target.id()), "&7Entrada: " + (target.open() ? "&aAbierta" : "&cInvitación"), "&7Fuerza: &6" + formatNumber(calculateStrength(target)))));
        if (own.isEmpty()) {
            inv.setItem(13, target.open() ? item(Material.LIME_DYE, "&a&lUnirse", List.of("", "&7Este clan está abierto.", "", "&eClick para unirte.")) : item(Material.PAPER, "&e&lEnviar solicitud", List.of("", "&7Este clan es por invitación.", "&7Enviarás una solicitud de ingreso.", "", "&eClick para solicitar.")));
        } else {
            Member member = own.get();
            boolean canRel = can(member, "relation");
            boolean canMail = can(member, "mail-send");
            inv.setItem(10, item(Material.BOOK, "&b&lVer info", List.of("", "&eClick para ver información.")));
            inv.setItem(11, canRel ? item(Material.BLUE_DYE, "&9&lProponer alianza", List.of("", "&7Envía/acepta relación aliada.", "", "&eClick para establecer.")) : lockedItem("&7Proponer alianza", "&cRequiere rango alto."));
            inv.setItem(12, canRel ? item(Material.RED_DYE, "&c&lDeclarar enemigo", List.of("", "&7Marca este clan como enemigo.", "", "&eClick para establecer.")) : lockedItem("&7Declarar enemigo", "&cRequiere rango alto."));
            inv.setItem(14, canRel ? item(Material.GRAY_DYE, "&7&lVolver neutral", List.of("", "&7Quita alianza/enemistad.", "", "&eClick para establecer.")) : lockedItem("&7Volver neutral", "&cRequiere rango alto."));
            inv.setItem(15, canMail ? item(Material.WRITABLE_BOOK, "&d&lCorreo de clan", List.of("", "&7Escribe un correo formal", "&7al buzón de este clan.", "", "&eClick para escribir.")) : lockedItem("&7Correo de clan", "&cRequiere rango alto."));
        }
        inv.setItem(22, item(Material.ARROW, "&6&lVolver", List.of("&7Regresa a la lista.")));
        player.openInventory(inv);
    }

    private void openMailActionMenu(Player player, int mailId) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Optional<ClanMail> mailOpt = getClanMail(member.clanId(), mailId);
        if (mailOpt.isEmpty()) { msg(player, "&cCorreo no encontrado."); return; }
        ClanMail mail = mailOpt.get();
        Clan from = getClan(mail.fromClanId()).orElse(null);
        Inventory inv = Bukkit.createInventory(new ClanMenuHolder("mailaction", 1, member.clanId(), null, mail.id()), 27, color("&8Correo #" + mail.id()));
        fill(inv);
        inv.setItem(4, from != null ? clanBannerItem(from, "&dCorreo de &b" + from.tag(), List.of("&7Enviado por: &f" + mail.senderName(), "&7Fecha: &f" + date(mail.sentAt()), "", "&f" + mail.message())) : item(Material.PAPER, "&dCorreo #" + mail.id(), List.of("&f" + mail.message())));
        inv.setItem(11, can(member, "mail-send") && from != null ? item(Material.WRITABLE_BOOK, "&a&lResponder", List.of("", "&7Responder al clan que", "&7envió este correo.", "", "&eClick para escribir.")) : lockedItem("&7Responder", "&cRequiere rango alto."));
        inv.setItem(15, can(member, "mail-delete") ? item(Material.RED_DYE, "&c&lEliminar", List.of("", "&7Borra este correo del buzón.", "", "&eClick para eliminar.")) : lockedItem("&7Eliminar", "&cRequiere rango alto."));
        inv.setItem(22, item(Material.ARROW, "&6&lVolver", List.of("&7Regresa al buzón.")));
        player.openInventory(inv);
    }

    private void handleMenuClick(Player player, ClanMenuHolder holder, int slot, ClickType click) throws SQLException {
        String menu = holder.menu();
        if (slot == 49 && !menu.equals("main")) { openMainMenu(player); return; }
        if (slot == 45 && holder.page() > 1) { openPaged(player, menu, holder.page() - 1); return; }
        if (slot == 53) { openPaged(player, menu, holder.page() + 1); return; }
        switch (menu) {
            case "main", "noclan", "hub", "gestion" -> handleMainClick(player, menu, slot);
            case "members" -> handleMembersClick(player, holder.page(), slot, click);
            case "info" -> handleInfoClick(player, slot, click);
            case "settings" -> handleSettingsClick(player, slot, click);
            case "rolesettings" -> handleRoleSettingsClick(player, slot);
            case "permissions" -> { if (slot == 49) openSettingsMenu(player); }
            case "joinrequests" -> handleJoinRequestsClick(player, holder.page(), slot, click);
            case "memberaction" -> handleMemberActionClick(player, holder.targetUuid(), slot);
            case "clanaction" -> handleClanActionClick(player, holder.clanId(), slot);
            case "mailaction" -> handleMailActionClick(player, holder.mailId(), slot);
            case "relations" -> { if (slot == 11) openRelationsListMenu(player,1); else if (slot == 13) openKillStatsGui(player); else if (slot == 15) openTopGui(player,"fuerza"); else if (slot == 22) openMainMenu(player); }
            case "storagehub" -> { if (slot == 11) { player.closeInventory(); handleStorage(player); } else if (slot == 15) { player.closeInventory(); handleBank(player, new String[]{"banco"}); } else if (slot == 22) openMainMenu(player); }
            case "clanlist" -> handleClanListClick(player, holder.page(), slot, click);
            case "relationslist" -> handleRelationsListClick(player, holder.page(), slot);
            case "mailbox" -> handleMailboxClick(player, holder.page(), slot, click);
            case "bajas" -> { if (slot == 49) openRelationsMenu(player); }
            case "logs" -> { if (slot == 49) openClanInfoMenu(player); }
            default -> { if (menu.startsWith("top:")) handleTopGuiClick(player, slot); }
        }
    }

    private void handleMainClick(Player player, String menu, int slot) throws SQLException {
        boolean hasClan = getMember(player.getUniqueId()).isPresent();
        if (!hasClan || menu.equals("noclan")) {
            if (slot == 11) openClanListMenu(player, 1);
            else if (slot == 15) { pendingClanCreate.add(player.getUniqueId()); player.closeInventory(); msg(player, "&7Escribe en el chat: &eID Nombre del clan&7. Ejemplo: &fMDV Medieval Craft&7. Escribe &ccancelar &7para cancelar."); }
            else if (slot == 22 || slot == 26) player.closeInventory();
            return;
        }
        if (menu.equals("gestion")) {
            if (slot == 4) openFullClanHub(player);
            else if (slot == 10) { player.closeInventory(); player.performCommand("clan base"); }
            else if (slot == 11) { player.closeInventory(); player.performCommand("clan setbase"); }
            else if (slot == 13) { player.closeInventory(); player.performCommand("clan banco"); }
            else if (slot == 14) { player.closeInventory(); handleStorage(player); }
            else if (slot == 15) { player.closeInventory(); player.performCommand("clan estandarte ver"); }
            else if (slot == 16) openLogsGui(player, 1);
            else if (slot == 22 || slot == 26) player.closeInventory();
            return;
        }
        if (slot == 10) openMembersMenu(player, 1);
        else if (slot == 11) openClanInfoMenu(player);
        else if (slot == 12) openRelationsMenu(player);
        else if (slot == 13) openStorageHubMenu(player);
        else if (slot == 14) { player.closeInventory(); player.performCommand("clan base"); }
        else if (slot == 15) openClanListMenu(player, 1);
        else if (slot == 16) { player.closeInventory(); player.performCommand("clan salir"); }
        else if (slot == 17) openSettingsMenu(player);
        else if (slot == 22) openClanManagementMenu(player);
        else if (slot == 26) player.closeInventory();
    }


    private void handleMembersClick(Player player, int page, int slot, ClickType click) throws SQLException {
        Member actor = requireMember(player); if (actor == null) return;
        List<Member> members = getMembers(actor.clanId());
        int index = pageIndexFromSlot(page, slot);
        if (index < 0 || index >= members.size()) return;
        Member target = members.get(index);
        openMemberActionMenu(player, target.uuid());
    }


    private void handleInfoClick(Player player, int slot, ClickType click) throws SQLException {
        if (slot == 10) { player.closeInventory(); player.performCommand("clan estandarte ver"); }
        else if (slot == 12) { if (click.isShiftClick()) { pendingBoardEdit.add(player.getUniqueId()); player.closeInventory(); msg(player, "&7Escribe el nuevo tablero. Usa &e| &7para separar líneas. &cCancelar &7para cancelar."); } else player.performCommand("clan tablero ver"); }
        else if (slot == 13) openJoinRequestsMenu(player, 1);
        else if (slot == 14) openMailboxMenu(player, 1);
        else if (slot == 16) openLogsGui(player, 1);
        else if (slot == 20) openPermissionsMenu(player);
        else if (slot == 22) openMainMenu(player);
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
        player.closeInventory();
        player.performCommand("clan info " + relations.get(index).clan().tag());
    }

    private void handleMailboxClick(Player player, int page, int slot, ClickType click) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        List<ClanMail> mails = getClanMails(member.clanId(), page, GUI_PAGE_SIZE);
        int index = pageIndexFromSlot(page, slot);
        if (index < 0 || index >= mails.size()) return;
        openMailActionMenu(player, mails.get(index).id());
    }



    private void handleSettingsClick(Player player, int slot, ClickType click) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        if (slot == 10) {
            if (!hasRank(player, member, "rename-clan")) return;
            pendingClanNameEdit.add(player.getUniqueId()); player.closeInventory();
            msg(player, "&7Escribe el nuevo &enombre &7del clan. Escribe &ccancelar &7para cancelar.");
        } else if (slot == 11) {
            if (!hasRank(player, member, "rename-tag")) return;
            pendingClanTagEdit.add(player.getUniqueId()); player.closeInventory();
            msg(player, "&7Escribe el nuevo &eID &7del clan. Escribe &ccancelar &7para cancelar.");
        } else if (slot == 12) openRoleSettingsMenu(player);
        else if (slot == 13) { player.closeInventory(); player.performCommand(click == ClickType.RIGHT ? "clan estandarte quitar" : "clan estandarte set"); }
        else if (slot == 14) openPermissionsMenu(player);
        else if (slot == 15) { player.closeInventory(); Clan clan = getPlayerClan(player.getUniqueId()).orElseThrow(); player.performCommand("clan abierto " + (clan.open() ? "off" : "on")); }
        else if (slot == 16) { player.closeInventory(); msg(player, "&cPara disolver usa: &e/clan disolver confirmar"); }
        else if (slot == 49) openMainMenu(player);
    }

    private void handleRoleSettingsClick(Player player, int slot) throws SQLException {
        int[] slots = {10, 11, 12, 13, 14, 15};
        for (int role = minRole(); role <= maxRole() && role < slots.length; role++) {
            if (slot == slots[role]) {
                Member member = requireMember(player); if (member == null) return;
                if (!hasRank(player, member, "rename-role")) return;
                pendingRoleNameEdit.put(player.getUniqueId(), role);
                player.closeInventory();
                msg(player, "&7Escribe el nuevo nombre para el rango &e" + role + "&7. Escribe &ccancelar &7para cancelar.");
                return;
            }
        }
        if (slot == 22) openSettingsMenu(player);
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
        if (slot == 10) { player.closeInventory(); msg(player, "&7Correo personal: usa el buzón de MDVSocial para escribir a &e" + target.name() + "&7."); }
        else if (slot == 11) { player.closeInventory(); player.performCommand("clan promover " + target.name()); }
        else if (slot == 12) { player.closeInventory(); player.performCommand("clan degradar " + target.name()); }
        else if (slot == 14) { player.closeInventory(); player.performCommand("clan expulsar " + target.name()); }
        else if (slot == 22) openMembersMenu(player, 1);
    }

    private void handleClanActionClick(Player player, int targetClanId, int slot) throws SQLException {
        Optional<Clan> targetOpt = getClan(targetClanId);
        if (targetOpt.isEmpty()) { msg(player, "&cClan no encontrado."); return; }
        Clan target = targetOpt.get();
        Optional<Member> own = getMember(player.getUniqueId());
        if (own.isEmpty()) {
            if (slot == 13) { player.closeInventory(); player.performCommand("clan unirse " + target.tag()); }
            else if (slot == 22) openClanListMenu(player, 1);
            return;
        }
        if (slot == 10) { player.closeInventory(); player.performCommand("clan info " + target.tag()); }
        else if (slot == 11) { player.closeInventory(); player.performCommand("clan relacion " + target.tag() + " aliado"); }
        else if (slot == 12) { player.closeInventory(); player.performCommand("clan relacion " + target.tag() + " enemigo"); }
        else if (slot == 14) { player.closeInventory(); player.performCommand("clan relacion " + target.tag() + " neutral"); }
        else if (slot == 15) { pendingClanMailReply.put(player.getUniqueId(), target.tag()); player.closeInventory(); msg(player, "&7Escribe el correo para el clan &e" + target.tag() + "&7. Escribe &ccancelar &7para cancelar."); }
        else if (slot == 22) openClanListMenu(player, 1);
    }

    private void handleMailActionClick(Player player, int mailId, int slot) throws SQLException {
        Member member = requireMember(player); if (member == null) return;
        Optional<ClanMail> mailOpt = getClanMail(member.clanId(), mailId);
        if (mailOpt.isEmpty()) { msg(player, "&cCorreo no encontrado."); return; }
        ClanMail mail = mailOpt.get();
        if (slot == 11) {
            if (!hasRank(player, member, "mail-send")) return;
            Optional<Clan> from = getClan(mail.fromClanId());
            if (from.isEmpty()) return;
            pendingClanMailReply.put(player.getUniqueId(), from.get().tag());
            player.closeInventory();
            msg(player, "&7Escribe la respuesta para &e" + from.get().tag() + "&7. Escribe &ccancelar &7para cancelar.");
        } else if (slot == 15) {
            if (!hasRank(player, member, "mail-delete")) return;
            deleteClanMail(member.clanId(), mail.id());
            msg(player, "&aCorreo eliminado.");
            openMailboxMenu(player, 1);
        } else if (slot == 22) openMailboxMenu(player, 1);
    }

    private void handleTopGuiClick(Player player, int slot) throws SQLException {
        if (slot == 3) openTopGui(player, "fuerza");
        else if (slot == 4) openTopGui(player, "kills");
        else if (slot == 5) openTopGui(player, "banco");
        else if (slot == 49) openRelationsMenu(player);
    }

    private void openPaged(Player player, String menu, int page) throws SQLException {
        if (menu.equals("members")) openMembersMenu(player, page);
        else if (menu.equals("clanlist")) openClanListMenu(player, page);
        else if (menu.equals("relationslist")) openRelationsListMenu(player, page);
        else if (menu.equals("mailbox")) openMailboxMenu(player, page);
        else if (menu.equals("joinrequests")) openJoinRequestsMenu(player, page);
        else openMainMenu(player);
    }

    private ItemStack memberHead(Member member, int clanId, Player viewer) throws SQLException {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(member.uuid()));
        meta.setDisplayName(color("&e" + member.name() + " &8(&b" + getRoleName(clanId, member.role()) + "&8)"));
        meta.setLore(playerProfileLore(member, clanId, viewer, List.of("", "&eClick para abrir opciones.")));
        item.setItemMeta(meta);
        return item;
    }


    private String safePapi(Player player, String placeholder) {
        try {
            String out = PlaceholderAPI.setPlaceholders(player, placeholder);
            if (out == null || out.equalsIgnoreCase(placeholder) || out.contains("%")) return "";
            return out;
        } catch (Throwable ignored) { return ""; }
    }

    private List<String> boardItemLore(Clan clan) {
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Mensaje actual:");
        for (String line : boardLines(clan.boardMessage())) lore.add("&f" + line);
        lore.add("");
        lore.add("&eClick: ver en chat");
        lore.add("&6Shift-click: editar");
        return lore;
    }

    private List<String> boardLines(String text) {
        if (text == null || text.isBlank()) return List.of("&8Sin información todavía.");
        return Arrays.stream(text.split("\\|", -1)).map(String::trim).filter(x -> !x.isBlank()).limit(8).collect(Collectors.toList());
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
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
    }

    private void nav(Inventory inv, int page, int total, int pageSize, String menu, int clanId) {
        if (page > 1) inv.setItem(45, item(Material.ARROW, "&ePágina anterior", List.of("&7Click para volver.")));
        if (page * pageSize < total) inv.setItem(53, item(Material.ARROW, "&ePágina siguiente", List.of("&7Click para avanzar.")));
    }

    private ItemStack clanBannerItem(Clan clan, String name, List<String> lore) {
        ItemStack base = new ItemStack(Material.WHITE_BANNER);
        if (clan != null && clan.hasBanner()) {
            try { base = itemFromBase64(clan.banner()); }
            catch (Exception ignored) { base = new ItemStack(Material.WHITE_BANNER); }
        }
        ItemStack item = base.clone();
        item.setAmount(1);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(name));
        meta.setLore(lore.stream().map(this::color).collect(Collectors.toList()));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack lockedItem(String name, String reason) {
        return item(Material.GRAY_DYE, name, List.of("", reason, "&8No puedes usar esta función."));
    }

    private List<String> playerProfileLore(Member member, int clanId, Player viewer, List<String> extra) throws SQLException {
        List<String> lore = new ArrayList<>();
        OfflinePlayer off = Bukkit.getOfflinePlayer(member.uuid());
        Player online = Bukkit.getPlayer(member.uuid());
        lore.add(color("&7Rango: &b" + member.role() + " &8- &f" + getRoleName(clanId, member.role())));
        lore.add(color("&7Estado: " + (online != null ? "&aConectado" : "&cDesconectado")));
        lore.add(color("&7Última vez: &f" + (online != null ? "Ahora" : date(off.getLastSeen()))));
        lore.add(color("&7Ingreso: &f" + date(member.joinedAt())));
        if (online != null && Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            String level = safePapi(online, "%mmocore_level%");
            String race = safePapi(online, "%mmocore_race%");
            if (!level.isBlank()) lore.add(color("&7Nivel: &e" + level));
            if (!race.isBlank()) lore.add(color("&7Raza: &d" + race));
        } else {
            lore.add(color("&7Nivel/Raza: &8solo si está online"));
        }
        if (extra != null) for (String line : extra) lore.add(color(line));
        return lore;
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

    private void saveMenuTemplates() {
        saveResourceIfMissing("Menus/clan.yml");
        saveResourceIfMissing("Menus/clan_gestion.yml");
        saveResourceIfMissing("Menus/clan_rankings.yml");
        saveResourceIfMissing("Menus/clan_miembros.yml");
        saveResourceIfMissing("Menus/clan_info.yml");
        saveResourceIfMissing("Menus/clan_relaciones.yml");
        saveResourceIfMissing("Menus/clan_lista.yml");
        saveResourceIfMissing("Menus/clan_ajustes.yml");
        saveResourceIfMissing("Menus/clan_solicitudes.yml");
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

    private void syncNametags() {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (Player viewer : players) {
            try {
                Scoreboard board = viewer.getScoreboard();
                for (Player target : players) {
                    updateNametagFor(viewer, target, board);
                }
            } catch (Exception e) {
                getLogger().warning("Error actualizando nametags: " + e.getMessage());
            }
        }
    }

    private void updateNametagFor(Player viewer, Player target, Scoreboard board) throws SQLException {
        String entry = target.getName();
        removeFromMDVTeams(board, entry);
        Optional<Member> targetMember = getMember(target.getUniqueId());
        if (targetMember.isEmpty()) return;
        Optional<Clan> targetClanOpt = getClan(targetMember.get().clanId());
        if (targetClanOpt.isEmpty()) return;
        Clan targetClan = targetClanOpt.get();
        int viewerClanId = getOwnClanId(viewer.getUniqueId());
        String relation = getRelationBetween(viewerClanId, targetClan.id());
        String formatKey = switch (relation) {
            case "SAME" -> "same";
            case REL_ALLY -> "ally";
            case REL_ENEMY -> "enemy";
            default -> "neutral";
        };
        String prefix = color(getConfig().getString("nametags.formats." + formatKey, "&7[{id}] ").replace("{id}", targetClan.tag()).replace("{name}", targetClan.name()));
        String teamName = "mdvc" + Integer.toHexString((formatKey + targetClan.tag()).hashCode()).replace("-", "n");
        if (teamName.length() > 16) teamName = teamName.substring(0, 16);
        Team team = board.getTeam(teamName);
        if (team == null) team = board.registerNewTeam(teamName);
        team.setPrefix(prefix);
        team.setSuffix("");
        team.addEntry(entry);
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
        if (countMembers(clanId) >= getConfig().getInt("limits.max-members", 20)) {
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
                if (rs.next()) return Optional.of(new ClanMail(rs.getInt("id"), rs.getInt("from_clan_id"), rs.getInt("to_clan_id"), rs.getString("sender_name"), rs.getLong("sent_at"), rs.getString("message")));
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
                rs.getDouble("bank_balance"),
                rs.getString("banner"),
                rs.getString("storage"),
                rs.getString("board_message"),
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
            return args.length == 1 ? filter(List.of("reload"), args[0]) : Collections.emptyList();
        }
        if (!(sender instanceof Player)) return Collections.emptyList();
        if (args.length == 1) {
            return filter(List.of("ayuda", "crear", "info", "lista", "invitar", "aceptar", "unirse", "abierto", "salir", "expulsar", "promover", "degradar", "setrango", "rol", "chat", "c", "setbase", "base", "relacion", "banco", "depositar", "retirar", "almacen", "estandarte", "logs", "top", "bajas", "tablero", "correo", "editar", "solicitudes", "menu", "disolver"), args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            if (args.length == 2) {
                if (equalsAny(sub, "info", "unirse", "relacion")) return filter(listClanTags(), args[1]);
                if (equalsAny(sub, "invitar")) return null;
                if (equalsAny(sub, "aceptar")) return filter(listInviteTags(((Player) sender).getUniqueId()), args[1]);
                if (equalsAny(sub, "abierto")) return filter(List.of("on", "off"), args[1]);
                if (equalsAny(sub, "expulsar", "promover", "degradar", "setrango")) return filter(memberNamesOfSenderClan((Player) sender), args[1]);
                if (equalsAny(sub, "rol")) return filter(List.of("0", "1", "2", "3", "4", "5"), args[1]);
                if (equalsAny(sub, "banco")) return filter(List.of("depositar", "retirar", "log"), args[1]);
                if (equalsAny(sub, "estandarte", "banner")) return filter(List.of("set", "ver", "quitar"), args[1]);
                if (equalsAny(sub, "top")) return filter(List.of("fuerza", "kills", "banco"), args[1]);
                if (equalsAny(sub, "editar")) return filter(List.of("nombre", "id"), args[1]);
                if (equalsAny(sub, "solicitudes")) return filter(List.of("ver", "aceptar", "borrar"), args[1]);
                if (equalsAny(sub, "disolver")) return filter(List.of("confirmar"), args[1]);
            }
            if (args.length == 3 && equalsAny(sub, "relacion")) return filter(List.of("neutral", "aliado", "enemigo"), args[2]);
            if (args.length == 3 && equalsAny(sub, "setrango")) return filter(List.of("0", "1", "2", "3", "4", "5"), args[2]);
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

    private List<String> memberNamesOfSenderClan(Player player) throws SQLException {
        Optional<Member> member = getMember(player.getUniqueId());
        if (member.isEmpty()) return Collections.emptyList();
        return getMembers(member.get().clanId()).stream().map(Member::name).collect(Collectors.toList());
    }

    public record Clan(int id, String tag, String name, UUID ownerUuid, boolean open, long createdAt,
                       double bankBalance, String banner, String storage, String boardMessage,
                       String baseWorld, double baseX, double baseY, double baseZ, float baseYaw, float basePitch) {
        boolean hasBase() { return baseWorld != null && !baseWorld.isBlank(); }
        boolean hasBanner() { return banner != null && !banner.isBlank(); }
        boolean hasStorage() { return storage != null && !storage.isBlank(); }
    }

    public record ClanLog(long time, String actorName, String action, String detail) {}

    public record ClanTopEntry(Clan clan, double value) {}

    public record ClanMail(int id, int fromClanId, int toClanId, String senderName, long sentAt, String message) {}

    public record ClanRelationView(Clan clan, String relation) {}

    public record ClanJoinRequest(UUID uuid, String name, long requestedAt) {}

    private record ClanMenuHolder(String menu, int page, int clanId, UUID targetUuid, int mailId) implements InventoryHolder {
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

        @Override
        public String onPlaceholderRequest(Player player, String params) {
            if (player == null) return "";
            try {
                Optional<Member> memberOpt = getMember(player.getUniqueId());
                String noClan = getConfig().getString("placeholders.no-clan", "");
                if (memberOpt.isEmpty()) {
                    return switch (params.toLowerCase(Locale.ROOT)) {
                        case "id", "name", "tag", "lpc_tag", "role", "role_number", "member_count", "bank", "kills", "deaths", "strength", "is_in_clan" -> params.equalsIgnoreCase("is_in_clan") ? "false" : noClan;
                        default -> noClan;
                    };
                }
                Member member = memberOpt.get();
                Optional<Clan> clanOpt = getClan(member.clanId());
                if (clanOpt.isEmpty()) return noClan;
                Clan clan = clanOpt.get();
                return switch (params.toLowerCase(Locale.ROOT)) {
                    case "id" -> clan.tag();
                    case "name" -> clan.name();
                    case "tag" -> color(getConfig().getString("placeholders.tag-format", "&8[&b{id}&8]&r").replace("{id}", clan.tag()).replace("{name}", clan.name()));
                    case "lpc_tag" -> color(getConfig().getString("placeholders.lpc-tag-format", "&8[&b{id}&8]&r ").replace("{id}", clan.tag()).replace("{name}", clan.name()));
                    case "role" -> getRoleName(clan.id(), member.role());
                    case "role_number" -> String.valueOf(member.role());
                    case "member_count" -> String.valueOf(countMembers(clan.id()));
                    case "bank" -> formatNumber(clan.bankBalance());
                    case "kills" -> String.valueOf(getTotalKillsByClan(clan.id()));
                    case "deaths" -> String.valueOf(getTotalDeathsByClan(clan.id()));
                    case "strength" -> formatNumber(calculateStrength(clan));
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
