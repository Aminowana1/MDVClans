package com.mdvcraft.mdvclans;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    private Connection connection;
    private Economy economy;
    private Pattern idPattern;

    private final Map<UUID, PendingTeleport> pendingTeleports = new ConcurrentHashMap<>();
    private final Map<UUID, Long> baseCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> friendlyFireMessageCooldowns = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
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

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new MDVClansExpansion().register();
            getLogger().info("PlaceholderAPI detectado: placeholders registrados.");
        }

        getLogger().info("MDVClans 1.0.0 habilitado.");
    }

    @Override
    public void onDisable() {
        for (PendingTeleport pending : pendingTeleports.values()) {
            Bukkit.getScheduler().cancelTask(pending.taskId());
        }
        pendingTeleports.clear();
        closeDatabase();
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
        File file = new File(getDataFolder(), getConfig().getString("storage.sqlite-file", "clans.db"));
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
            st.executeUpdate("CREATE TABLE IF NOT EXISTS relations (" +
                    "clan_id INTEGER NOT NULL," +
                    "target_clan_id INTEGER NOT NULL," +
                    "relation TEXT NOT NULL," +
                    "created_at INTEGER NOT NULL," +
                    "PRIMARY KEY(clan_id, target_clan_id)," +
                    "FOREIGN KEY(clan_id) REFERENCES clans(id) ON DELETE CASCADE," +
                    "FOREIGN KEY(target_clan_id) REFERENCES clans(id) ON DELETE CASCADE" +
                    ")");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_members_clan ON members(clan_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_invites_target ON invites(target_uuid)");
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
            reloadLocalSettings();
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
            player.sendMessage(color("&8[&b" + c.tag() + "&8] &f" + c.name() + " &7- &e" + count + " &7miembros"));
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
            msg(player, "&cEse clan solo acepta miembros por invitación.");
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
        }
        msg(player, successMessage);
        broadcastToClan(clan.id(), "&e" + player.getName() + " &ase unió al clan.");
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
            msg(player, "&7Relación con &e" + target.tag() + " &7establecida como neutral.");
            notifyClan(target.id(), "&7El clan &e" + own.tag() + " &7estableció relación neutral con ustedes.");
            return;
        }
        if (equalsAny(mode, "enemigo", "enemy")) {
            setRelation(own.id(), target.id(), REL_ENEMY);
            removeRelation(target.id(), own.id(), REL_ALLY);
            removeRelation(target.id(), own.id(), REL_ALLY_REQUEST);
            broadcastToClan(own.id(), "&cTu clan declaró enemigo a &e" + target.tag() + "&c.");
            notifyClan(target.id(), "&cEl clan &e" + own.tag() + " &clos declaró enemigos.");
            return;
        }
        if (equalsAny(mode, "aliado", "ally")) {
            boolean needsAccept = getConfig().getBoolean("relations.ally-requires-accept", true);
            if (!needsAccept || getRelation(target.id(), own.id()).equals(REL_ALLY_REQUEST)) {
                setRelation(own.id(), target.id(), REL_ALLY);
                setRelation(target.id(), own.id(), REL_ALLY);
                broadcastToClan(own.id(), "&9Ahora son aliados del clan &e" + target.tag() + "&9.");
                notifyClan(target.id(), "&9Ahora son aliados del clan &e" + own.tag() + "&9.");
            } else {
                setRelation(own.id(), target.id(), REL_ALLY_REQUEST);
                msg(player, "&aSolicitud de alianza enviada a &e" + target.tag() + "&a.");
                notifyClan(target.id(), "&9El clan &e" + own.tag() + " &9quiere una alianza. Usa &e/clan relacion " + own.tag() + " aliado &9para aceptar.");
            }
            return;
        }
        msg(player, "&cRelación inválida: usa neutral, aliado o enemigo.");
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

    @EventHandler(priority = EventPriority.NORMAL)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
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
        player.sendMessage(color(getConfig().getString("prefix", "&8[&6MDVClans&8]&r ") + message));
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

    private synchronized void disbandClan(int clanId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM clans WHERE id=?")) {
            ps.setInt(1, clanId);
            ps.executeUpdate();
        }
    }

    private Clan readClan(ResultSet rs) throws SQLException {
        return new Clan(
                rs.getInt("id"),
                rs.getString("tag"),
                rs.getString("name"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getInt("open") == 1,
                rs.getLong("created_at"),
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
            return filter(List.of("ayuda", "crear", "info", "lista", "invitar", "aceptar", "unirse", "abierto", "salir", "expulsar", "promover", "degradar", "setrango", "rol", "chat", "c", "setbase", "base", "relacion", "disolver"), args[0]);
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
                       String baseWorld, double baseX, double baseY, double baseZ, float baseYaw, float basePitch) {
        boolean hasBase() { return baseWorld != null && !baseWorld.isBlank(); }
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
                        case "id", "name", "tag", "lpc_tag", "role", "role_number", "member_count", "is_in_clan" -> params.equalsIgnoreCase("is_in_clan") ? "false" : noClan;
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
