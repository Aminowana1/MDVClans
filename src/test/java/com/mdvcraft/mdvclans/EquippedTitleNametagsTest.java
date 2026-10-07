package com.mdvcraft.mdvclans;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EquippedTitleNametagsTest {
    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final Server server = mock(Server.class);
    private final World world = mock(World.class);
    private final Player target = mock(Player.class);
    private final Player viewer = mock(Player.class);
    private final BukkitTask scheduledTask = mock(BukkitTask.class);
    private final YamlConfiguration config = new YamlConfiguration();
    private final AtomicReference<String> equipped = new AtomicReference<>("&6Campeón");
    private final AtomicReference<Location> targetLocation = new AtomicReference<>();
    private final List<TextDisplay> spawned = new ArrayList<>();
    private final List<Location> spawnLocations = new ArrayList<>();
    private final UUID targetId = UUID.randomUUID();
    private EquippedTitleNametags manager;

    @BeforeEach
    void setup() {
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getConfig()).thenReturn(config);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
                .thenReturn(scheduledTask);
        doReturn(List.of(target, viewer)).when(server).getOnlinePlayers();
        config.set("nametags.equipped-title-line.update-interval-ticks", 1);

        targetLocation.set(new Location(world, 0.0, 64.0, 0.0));
        when(target.getUniqueId()).thenReturn(targetId);
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(target.getWorld()).thenReturn(world);
        when(viewer.getWorld()).thenReturn(world);
        when(target.getLocation()).thenAnswer(invocation -> targetLocation.get().clone());
        when(viewer.getLocation()).thenAnswer(invocation -> new Location(world, 2.0, 64.0, 0.0));
        when(target.getHeight()).thenReturn(1.8);
        when(target.isOnline()).thenReturn(true);
        when(viewer.isOnline()).thenReturn(true);
        when(target.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(viewer.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(viewer.canSee(target)).thenReturn(true);

        doAnswer(invocation -> {
            Location location = invocation.getArgument(0);
            TextDisplay display = mock(TextDisplay.class);
            when(display.isValid()).thenReturn(true);
            when(display.getWorld()).thenReturn(world);
            AtomicReference<Location> current = new AtomicReference<>(location.clone());
            when(display.getLocation()).thenAnswer(ignored -> current.get().clone());
            when(display.teleport(any(Location.class))).thenAnswer(teleport -> {
                current.set(((Location) teleport.getArgument(0)).clone());
                return true;
            });
            Consumer<TextDisplay> initializer = invocation.getArgument(2);
            initializer.accept(display);
            spawned.add(display);
            spawnLocations.add(location.clone());
            return display;
        }).when(world).spawn(any(Location.class), eq(TextDisplay.class), any(Consumer.class));
        manager = new EquippedTitleNametags(plugin, uuid -> uuid.equals(targetId) ? equipped.get() : "");
        manager.reload();
    }

    @AfterEach
    void cleanup() {
        manager.close();
    }

    @Test
    void titleTracksEquipmentAndNeverTouchesNameOrScoreboard() {
        manager.tick();
        TextDisplay display = spawned.getFirst();
        verify(display).setPersistent(false);
        verify(display).setVisibleByDefault(false);
        verify(viewer).showEntity(plugin, display);
        verify(target, never()).showEntity(eq(plugin), any());
        assertEquals("Campeón", renderedText(display));

        equipped.set("&dExplorador");
        manager.tick();
        assertEquals(1, spawned.size(), "Equipment changes should update the existing line.");
        assertEquals("Explorador", renderedText(display));
        equipped.set("");
        manager.tick();
        verify(display).remove();

        verify(target, never()).getScoreboard();
        verify(viewer, never()).getScoreboard();
        verify(target, never()).setScoreboard(any());
        verify(viewer, never()).setScoreboard(any());
        verify(target, never()).setDisplayName(any());
        verify(target, never()).setPlayerListName(any());
    }

    @Test
    void positionCanMoveAboveOrBelowExistingName() {
        manager.tick();
        double nameHeight = 64.0 + 1.8 + 0.5;
        assertEquals(nameHeight + 0.32, spawnLocations.getFirst().getY(), 0.00001);

        config.set("nametags.equipped-title-line.position", "below");
        config.set("nametags.equipped-title-line.line-gap", 0.4);
        manager.reload();
        manager.tick();
        assertEquals(nameHeight - 0.4, spawnLocations.getLast().getY(), 0.00001);
        verify(spawned.getFirst()).remove();
    }

    @Test
    void movementFollowsWithoutAttachingPassengersAndTeleportsRemoveOldLine() {
        manager.tick();
        TextDisplay display = spawned.getFirst();
        targetLocation.set(new Location(world, 3.0, 65.0, 0.0));
        manager.tick();
        ArgumentCaptor<Location> destination = ArgumentCaptor.forClass(Location.class);
        verify(display).teleport(destination.capture());
        assertEquals(3.0, destination.getValue().getX());
        assertEquals(65.0 + 1.8 + 0.82, destination.getValue().getY(), 0.00001);
        verify(target, never()).addPassenger(any());

        manager.onTeleport(new PlayerTeleportEvent(target, target.getLocation(), new Location(world, 200.0, 64.0, 0.0)));
        verify(display).remove();
        targetLocation.set(new Location(world, 200.0, 64.0, 0.0));
        manager.tick();
        assertEquals(2, spawned.size());
        assertEquals(200.0, spawnLocations.getLast().getX());
    }

    @Test
    void hidingPlayerOrSneakingHidesTheirTitle() {
        manager.tick();
        TextDisplay display = spawned.getFirst();
        when(viewer.canSee(target)).thenReturn(false);
        for (int tick = 0; tick < 5; tick++) manager.tick();
        verify(viewer).hideEntity(plugin, display);

        when(viewer.canSee(target)).thenReturn(true);
        for (int tick = 0; tick < 5; tick++) manager.tick();
        verify(viewer, times(2)).showEntity(plugin, display);
        when(target.isSneaking()).thenReturn(true);
        manager.tick();
        verify(display).remove();
        when(target.isSneaking()).thenReturn(false);
        when(target.isInvisible()).thenReturn(true);
        manager.tick();
        assertEquals(1, spawned.size(), "Invisible players must not expose a floating title.");
    }

    @Test
    void quitReloadAndDisableCleanUpEntitiesAndTasks() {
        manager.tick();
        TextDisplay first = spawned.getFirst();
        manager.onQuit(new PlayerQuitEvent(target, ""));
        verify(first).remove();

        manager.tick();
        TextDisplay second = spawned.getLast();
        config.set("nametags.equipped-title-line.enabled", false);
        manager.reload();
        verify(second).remove();
        verify(scheduledTask).cancel();
        manager.close();
        verify(second, times(1)).remove();
    }

    @Test
    void emptyOrFormattingOnlyEquipmentNeverCreatesAPlaceholderLine() {
        equipped.set("&6   ");
        config.set("nametags.equipped-title-line.format", "&7Título: {title}");
        manager.reload();
        manager.tick();
        verify(world, never()).spawn(any(Location.class), eq(TextDisplay.class), any(Consumer.class));
        equipped.set(null);
        manager.tick();
        assertTrue(spawned.isEmpty());
    }

    private static String renderedText(TextDisplay display) {
        ArgumentCaptor<Component> text = ArgumentCaptor.forClass(Component.class);
        verify(display, atLeastOnce()).text(text.capture());
        return PlainTextComponentSerializer.plainText().serialize(text.getValue());
    }
}
