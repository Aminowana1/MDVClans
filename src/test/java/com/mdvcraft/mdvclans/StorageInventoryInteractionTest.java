package com.mdvcraft.mdvclans;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Exercises the actual storage listeners without a running Minecraft server. */
class StorageInventoryInteractionTest {
    private final UUID owner = UUID.randomUUID();
    private final StorageSessionRegistry<Inventory> sessions = new StorageSessionRegistry<>();
    private MDVClansPlugin plugin;
    private Player player;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(MDVClansPlugin.class, CALLS_REAL_METHODS);
        player = mock(Player.class);
        connection = mock(Connection.class);
        when(player.getUniqueId()).thenReturn(owner);
        when(player.hasPermission("mdvclans.admin")).thenReturn(true);
        YamlConfiguration config = new YamlConfiguration();
        config.set("logs.enabled", false);
        doReturn(config).when(plugin).getConfig();
        doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();
        inject("storageSessions", sessions);
        inject("connection", connection);
    }

    @Test
    void administratorCanWithdrawAndDepositInContentSlots() throws Exception {
        Inventory inventory = inventory(1, 106, true);
        assertTrue(sessions.claim(12, owner, inventory));
        InventoryClickEvent withdraw = click(inventory, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        InventoryClickEvent deposit = click(inventory, 1, ClickType.RIGHT, InventoryAction.PLACE_ONE);
        InventoryClickEvent hotbarSwap = click(inventory, 2, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP);

        plugin.onInventoryClick(withdraw);
        plugin.onInventoryClick(deposit);
        plugin.onInventoryClick(hotbarSwap);

        verify(withdraw, never()).setCancelled(true);
        verify(deposit, never()).setCancelled(true);
        verify(hotbarSwap, never()).setCancelled(true);
    }

    @Test
    void shiftTransfersRemainAvailableForMembersAndAdministrators() throws Exception {
        for (boolean admin : new boolean[]{false, true}) {
            Inventory inventory = inventory(1, 106, admin);
            assertTrue(sessions.claim(12, owner, inventory));
            // The top inventory has 53 editable slots and one full navigation stack.
            InventoryClickEvent bottomShift = click(inventory, 54, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
            InventoryClickEvent topShift = click(inventory, 0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);

            plugin.onInventoryClick(bottomShift);
            plugin.onInventoryClick(topShift);

            verify(bottomShift, never()).setCancelled(true);
            verify(topShift, never()).setCancelled(true);
        }
    }

    @Test
    void navigationCannotBePickedUpSwappedOrDropped() throws Exception {
        Inventory inventory = inventory(1, 106, true);
        assertTrue(sessions.claim(12, owner, inventory));
        for (ClickType type : new ClickType[]{ClickType.NUMBER_KEY, ClickType.SHIFT_LEFT, ClickType.DROP, ClickType.MIDDLE}) {
            InventoryClickEvent event = click(inventory, 53, type, InventoryAction.PICKUP_ALL);
            plugin.onInventoryClick(event);
            verify(event).setCancelled(true);
        }
        verifyNoInteractions(connection);
    }

    @Test
    void unusedRoundedSlotsRejectDeposits() throws Exception {
        Inventory inventory = inventory(1, 10, false);
        assertTrue(sessions.claim(12, owner, inventory));
        InventoryClickEvent allowed = click(inventory, 9, ClickType.LEFT, InventoryAction.PLACE_ALL);
        InventoryClickEvent blocked = click(inventory, 10, ClickType.LEFT, InventoryAction.PLACE_ALL);

        plugin.onInventoryClick(allowed);
        plugin.onInventoryClick(blocked);

        verify(allowed, never()).setCancelled(true);
        verify(blocked).setCancelled(true);
    }

    @Test
    void doubleClickInBottomInventoryCannotCollectProtectedItems() throws Exception {
        Inventory inventory = inventory(1, 106, false);
        assertTrue(sessions.claim(12, owner, inventory));
        InventoryClickEvent event = click(inventory, 54, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR);

        plugin.onInventoryClick(event);

        verify(event).setCancelled(true);
    }

    @Test
    void fullSinglePageAllowsNormalDoubleClickCollection() throws Exception {
        Inventory inventory = inventory(1, 54, false);
        assertTrue(sessions.claim(12, owner, inventory));
        InventoryClickEvent event = click(inventory, 54, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR);

        plugin.onInventoryClick(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void dragAllowsContentButRejectsNavigationAndUnusedSlots() throws Exception {
        Inventory paginated = inventory(1, 106, true);
        assertTrue(sessions.claim(12, owner, paginated));
        InventoryDragEvent allowed = drag(paginated, Set.of(0, 52, 54));
        InventoryDragEvent navigation = drag(paginated, Set.of(0, 53, 54));
        plugin.onInventoryDrag(allowed);
        plugin.onInventoryDrag(navigation);
        verify(allowed, never()).setCancelled(true);
        verify(navigation).setCancelled(true);

        Inventory rounded = inventory(1, 10, false);
        assertTrue(sessions.claim(12, owner, rounded));
        InventoryDragEvent unused = drag(rounded, Set.of(9, 10));
        plugin.onInventoryDrag(unused);
        verify(unused).setCancelled(true);
    }

    @Test
    void staleInventoryCannotMoveItemsOrReleaseCurrentPageOnClose() throws Exception {
        Inventory previous = inventory(1, 106, false);
        Inventory current = inventory(2, 106, false);
        assertTrue(sessions.claim(12, owner, previous));
        assertTrue(sessions.claim(12, owner, current));
        InventoryClickEvent click = click(previous, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        InventoryDragEvent drag = drag(previous, Set.of(0));

        plugin.onInventoryClick(click);
        plugin.onInventoryDrag(drag);
        plugin.onInventoryClose(close(previous));

        verify(click).setCancelled(true);
        verify(drag).setCancelled(true);
        assertTrue(sessions.owns(12, owner, current));
        verifyNoInteractions(connection);
    }

    @Test
    void administratorLosingPermissionCannotKeepEditing() throws Exception {
        Inventory inventory = inventory(1, 106, true);
        assertTrue(sessions.claim(12, owner, inventory));
        when(player.hasPermission("mdvclans.admin")).thenReturn(false);
        InventoryClickEvent click = click(inventory, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        InventoryDragEvent drag = drag(inventory, Set.of(0));

        plugin.onInventoryClick(click);
        plugin.onInventoryDrag(drag);

        verify(click).setCancelled(true);
        verify(drag).setCancelled(true);
    }

    @Test
    void closingAdministrativeStoragePersistsContentAndReleasesSessionOnce() throws Exception {
        Inventory inventory = inventory(2, 106, true);
        assertTrue(sessions.claim(12, owner, inventory));
        PreparedStatement update = databaseWithClan();

        plugin.onInventoryClose(close(inventory));
        plugin.onInventoryClose(close(inventory));

        ArgumentCaptor<String> data = ArgumentCaptor.forClass(String.class);
        verify(update).setString(eq(1), data.capture());
        verify(update).setInt(2, 12);
        verify(update, times(1)).executeUpdate();
        assertNull(sessions.get(12));
        try (BukkitObjectInputStream input = new BukkitObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data.getValue())))) {
            assertEquals(106, input.readInt());
            for (int slot = 0; slot < 106; slot++) assertNull(input.readObject());
        }
        verify(inventory, never()).getItem(53);
        verify(inventory).getItem(0);
        verify(inventory).getItem(52);
    }

    @Test
    void failedCloseSaveRetainsInventoryAndExcludesOtherEditors() throws Exception {
        Inventory inventory = inventory(1, 9, true);
        assertTrue(sessions.claim(12, owner, inventory));
        PreparedStatement update = databaseWithClan();
        when(update.executeUpdate()).thenThrow(new SQLException("Simulated write failure"));

        plugin.onInventoryClose(close(inventory));

        assertTrue(sessions.owns(12, owner, inventory));
        assertFalse(sessions.claim(12, UUID.randomUUID(), mock(Inventory.class)));
        verify(player).sendMessage(anyString());
    }

    private PreparedStatement databaseWithClan() throws Exception {
        PreparedStatement select = mock(PreparedStatement.class);
        PreparedStatement update = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement("SELECT * FROM clans WHERE id=?")).thenReturn(select);
        when(connection.prepareStatement("UPDATE clans SET storage=? WHERE id=?")).thenReturn(update);
        when(select.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getInt("id")).thenReturn(12);
        when(rows.getString("owner_uuid")).thenReturn(owner.toString());
        when(update.executeUpdate()).thenReturn(1);
        return update;
    }

    private Inventory inventory(int page, int slots, boolean admin) throws Exception {
        Class<?> holderClass = Class.forName(MDVClansPlugin.class.getName() + "$StorageInventoryHolder");
        Constructor<?> constructor = holderClass.getDeclaredConstructor(int.class, int.class, int.class, UUID.class, boolean.class);
        constructor.setAccessible(true);
        InventoryHolder holder = (InventoryHolder) constructor.newInstance(12, page, slots, owner, admin);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenReturn(holder);
        when(inventory.getSize()).thenReturn(slots > 54 ? 54 : ((slots + 8) / 9) * 9);
        return inventory;
    }

    private InventoryClickEvent click(Inventory inventory, int rawSlot, ClickType click, InventoryAction action) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView view = mock(InventoryView.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getView()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(inventory);
        when(event.getRawSlot()).thenReturn(rawSlot);
        when(event.getClick()).thenReturn(click);
        when(event.getAction()).thenReturn(action);
        return event;
    }

    private InventoryDragEvent drag(Inventory inventory, Set<Integer> slots) {
        InventoryDragEvent event = mock(InventoryDragEvent.class);
        InventoryView view = mock(InventoryView.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getView()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(inventory);
        when(event.getRawSlots()).thenReturn(slots);
        return event;
    }

    private InventoryCloseEvent close(Inventory inventory) {
        InventoryCloseEvent event = mock(InventoryCloseEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        return event;
    }

    private void inject(String name, Object value) throws Exception {
        Field field = MDVClansPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }
}
