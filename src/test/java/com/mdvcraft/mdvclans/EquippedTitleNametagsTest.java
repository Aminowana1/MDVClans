package com.mdvcraft.mdvclans;

import io.papermc.paper.scoreboard.numbers.FixedFormat;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EquippedTitleNametagsTest {
    private static final String CONFIG = "nametags.equipped-title-line.";
    private static final String OBJECTIVE_NAME = "mdvc_title";
    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final Server server = mock(Server.class);
    private final Player target = mock(Player.class);
    private final Player viewer = mock(Player.class);
    private final Logger logger = mock(Logger.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final BukkitTask scheduledTask = mock(BukkitTask.class);
    private final YamlConfiguration config = new YamlConfiguration();
    private final Map<UUID, String> equipment = new HashMap<>();
    private final UUID targetId = UUID.randomUUID();
    private final UUID viewerId = UUID.randomUUID();
    private final Function<UUID, String> lookup = mock(Function.class);
    private BoardFixture fixture;
    private EquippedTitleNametags manager;

    @BeforeAll
    static void initializeCriteriaWithoutAServer() {
        // Paper's Criteria constants ask Bukkit for criteria during initialization.
        // Scope the static mock to that initialization; do not install a global server.
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getScoreboardCriteria(anyString()))
                    .thenAnswer(invocation -> new TestCriteria(invocation.getArgument(0)));
            assertNotNull(Criteria.DUMMY);
            assertEquals("dummy", Criteria.DUMMY.getName());
        }
    }

    @BeforeEach
    void setup() {
        fixture = new BoardFixture();
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(logger);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
                .thenReturn(scheduledTask);
        doReturn(List.of(target, viewer)).when(server).getOnlinePlayers();
        when(target.getUniqueId()).thenReturn(targetId);
        when(viewer.getUniqueId()).thenReturn(viewerId);
        when(target.getName()).thenReturn("Orco");
        when(viewer.getName()).thenReturn("Maga");
        when(target.getScoreboard()).thenReturn(fixture.board);
        when(viewer.getScoreboard()).thenReturn(fixture.board);
        when(lookup.apply(any(UUID.class))).thenAnswer(invocation -> equipment.get(invocation.getArgument(0)));
        equipment.put(targetId, "&6Campeón");
        manager = new EquippedTitleNametags(plugin, lookup);
        manager.reload();
    }

    @AfterEach
    void cleanup() {
        if (manager != null) manager.close();
    }

    @Test
    void sharedNativeBoardKeepsDistinctFixedTitlesAndReadsEachTargetOnce() {
        equipment.put(viewerId, "&dHechicera");
        manager.tick();
        Objective objective = fixture.currentTitleObjective();
        verify(fixture.board, times(1)).registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY,
                Component.empty(), RenderType.INTEGER);
        verify(objective).setDisplaySlot(DisplaySlot.BELOW_NAME);
        verify(objective).numberFormat(NumberFormat.blank());
        assertEquals("Campeón", renderedText(fixture.score(objective, "Orco")));
        assertEquals("Hechicera", renderedText(fixture.score(objective, "Maga")));
        verify(lookup, times(1)).apply(targetId);
        verify(lookup, times(1)).apply(viewerId);
        verify(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(20L));
    }

    @Test
    void equipmentChangesAndClearsOnlyTheTitleScore() {
        equipment.put(viewerId, "&dHechicera");
        manager.tick();
        Objective objective = fixture.currentTitleObjective();
        Score targetScore = fixture.score(objective, "Orco");
        equipment.put(targetId, "§bExplorador");
        manager.tick();
        assertEquals("Explorador", renderedText(targetScore));
        equipment.put(targetId, "");
        manager.tick();
        verify(targetScore).resetScore();
        verify(fixture.score(objective, "Maga"), never()).resetScore();
        verify(objective, never()).unregister();
        verify(fixture.board, never()).resetScores(anyString());
    }

    @Test
    void noEquipmentCreatesNoObjectiveAndLastTitleRemovalCleansOwnedObjective() {
        equipment.put(targetId, "&6  ");
        config.set(CONFIG + "format", "&7Título: {title}");
        manager.reload();
        manager.tick();
        equipment.put(targetId, null);
        manager.tick();
        assertTrue(fixture.registered.isEmpty());
        equipment.put(targetId, "&aGuardián");
        manager.tick();
        Objective objective = fixture.currentTitleObjective();
        equipment.put(targetId, "");
        manager.tick();
        verify(objective).unregister();
        assertNull(fixture.currentTitleObjective());
    }

    @Test
    void preservesForeignBelowNameAndWarnsOnceWithoutStealingIt() {
        Objective health = fixture.installForeign("health", DisplaySlot.BELOW_NAME);
        manager.tick();
        manager.tick();
        verifyNoInteractions(health);
        assertTrue(fixture.registered.isEmpty());
        verify(logger, times(1)).warning(contains("otro plugin ocupa"));
        assertSame(health, fixture.slots.get(DisplaySlot.BELOW_NAME));
        fixture.removeForeign("health");
        manager.tick();
        assertNotNull(fixture.currentTitleObjective(), "Freeing the slot should enable native titles on next refresh.");
    }

    @Test
    void preservesForeignReservedNameEvenWhenItIsInTheSidebar() {
        Objective foreign = fixture.installForeign(OBJECTIVE_NAME, DisplaySlot.SIDEBAR);
        manager.tick();
        manager.close();
        verifyNoInteractions(foreign);
        assertTrue(fixture.registered.isEmpty());
        assertSame(foreign, fixture.named.get(OBJECTIVE_NAME));
        assertSame(foreign, fixture.slots.get(DisplaySlot.SIDEBAR));
    }

    @Test
    void scoreboardChangesCleanUnusedBoardAndPopulateTheNewCurrentBoard() {
        manager.tick();
        Objective oldObjective = fixture.currentTitleObjective();
        BoardFixture next = new BoardFixture();
        when(target.getScoreboard()).thenReturn(next.board);
        when(viewer.getScoreboard()).thenReturn(next.board);
        manager.tick();
        verify(oldObjective).unregister();
        assertNull(fixture.currentTitleObjective());
        assertEquals("Campeón", renderedText(next.score(next.currentTitleObjective(), "Orco")));
        verify(target, never()).setScoreboard(any());
        verify(viewer, never()).setScoreboard(any());
    }

    @Test
    void quitReloadAndDisableCleanOnlyOwnedScoresAndObjectives() {
        equipment.put(viewerId, "&dHechicera");
        manager.tick();
        Objective first = fixture.currentTitleObjective();
        manager.onQuit(new PlayerQuitEvent(target, ""));
        verify(fixture.score(first, "Orco")).resetScore();
        verify(fixture.score(first, "Maga"), never()).resetScore();
        manager.reload();
        verify(first).unregister();
        verify(scheduledTask).cancel();
        manager.tick();
        Objective second = fixture.currentTitleObjective();
        config.set(CONFIG + "enabled", false);
        manager.reload();
        verify(second).unregister();
        manager.tick();
        manager.close();
        verify(second, times(1)).unregister();
        assertEquals(2, fixture.registered.size());
    }

    @Test
    void foreignReplacementWithTheSameNameIsNeverAdoptedOrUnregistered() {
        manager.tick();
        Objective original = fixture.currentTitleObjective();
        Objective replacement = fixture.installForeign(OBJECTIVE_NAME, DisplaySlot.BELOW_NAME);
        manager.tick();
        manager.close();
        verify(original, never()).unregister();
        verifyNoInteractions(replacement);
        assertSame(replacement, fixture.currentTitleObjective());
    }

    @Test
    void ownedObjectiveRepurposedForSidebarIsLeftUntouched() {
        manager.tick();
        Objective objective = fixture.currentTitleObjective();
        fixture.move(objective, DisplaySlot.SIDEBAR);
        clearInvocations(objective);
        manager.tick();
        manager.close();
        verify(objective, never()).unregister();
        verify(objective, never()).setDisplaySlot(any());
        assertSame(objective, fixture.slots.get(DisplaySlot.SIDEBAR));
    }

    @Test
    void fixedTitleRestoresAfterAnotherPluginResetsEntryScores() {
        manager.tick();
        Score score = fixture.score(fixture.currentTitleObjective(), "Orco");
        score.resetScore();
        manager.tick();
        verify(score, times(2)).numberFormat(any(NumberFormat.class));
        assertEquals("Campeón", renderedText(score));
    }

    @Test
    void legacyAbovePositionUsesNativeBelowAndWarnsOnce() {
        config.set(CONFIG + "position", "above");
        manager.reload();
        manager.tick();
        verify(fixture.currentTitleObjective()).setDisplaySlot(DisplaySlot.BELOW_NAME);
        manager.reload();
        verify(logger, times(1)).warning(contains("solo admite position: below"));
    }

    @Test
    void nativeUpdatesDoNotTouchTeamsNamesEntitiesOrOtherDisplaySlots() {
        Objective sidebar = fixture.installForeign("sidebar", DisplaySlot.SIDEBAR);
        Objective tab = fixture.installForeign("tab", DisplaySlot.PLAYER_LIST);
        manager.tick();
        manager.close();
        verifyNoInteractions(sidebar, tab);
        verify(fixture.board, never()).getObjective(DisplaySlot.SIDEBAR);
        verify(fixture.board, never()).getObjective(DisplaySlot.PLAYER_LIST);
        verify(fixture.board, never()).clearSlot(any());
        verify(fixture.board, never()).getTeams();
        verify(fixture.board, never()).getTeam(anyString());
        verify(fixture.board, never()).registerNewTeam(anyString());
        verify(fixture.board, never()).resetScores(anyString());
        for (Player player : List.of(target, viewer)) {
            verify(player, never()).setScoreboard(any());
            verify(player, never()).setDisplayName(anyString());
            verify(player, never()).setPlayerListName(anyString());
            verify(player, never()).getWorld();
            verify(player, never()).getLocation();
            verify(player, never()).showEntity(any(), any());
            verify(player, never()).hideEntity(any(), any());
            verify(player, never()).addPassenger(any());
        }
    }

    private static String renderedText(Score score) {
        ArgumentCaptor<NumberFormat> formats = ArgumentCaptor.forClass(NumberFormat.class);
        verify(score, atLeastOnce()).numberFormat(formats.capture());
        FixedFormat fixed = assertInstanceOf(FixedFormat.class, formats.getValue());
        return PlainTextComponentSerializer.plainText().serialize(fixed.component());
    }

    private record TestCriteria(String name) implements Criteria {
        @Override
        public String getName() { return name; }

        @Override
        public boolean isReadOnly() { return false; }

        @Override
        public RenderType getDefaultRenderType() { return RenderType.INTEGER; }
    }

    private static final class BoardFixture {
        private final Scoreboard board = mock(Scoreboard.class);
        private final Map<String, Objective> named = new HashMap<>();
        private final Map<DisplaySlot, Objective> slots = new EnumMap<>(DisplaySlot.class);
        private final Map<Objective, Map<String, Score>> scores = new HashMap<>();
        private final Map<Objective, AtomicReference<DisplaySlot>> objectiveSlots = new HashMap<>();
        private final List<Objective> registered = new ArrayList<>();

        private BoardFixture() {
            when(board.getObjective(anyString())).thenAnswer(invocation -> named.get(invocation.getArgument(0)));
            when(board.getObjective(any(DisplaySlot.class))).thenAnswer(invocation -> slots.get(invocation.getArgument(0)));
            when(board.registerNewObjective(eq(OBJECTIVE_NAME), eq(Criteria.DUMMY),
                    any(Component.class), eq(RenderType.INTEGER))).thenAnswer(invocation -> {
                Objective objective = mock(Objective.class);
                AtomicReference<DisplaySlot> displaySlot = new AtomicReference<>();
                objectiveSlots.put(objective, displaySlot);
                named.put(OBJECTIVE_NAME, objective);
                registered.add(objective);
                when(objective.getDisplaySlot()).thenAnswer(ignored -> displaySlot.get());
                when(objective.getScore(anyString())).thenAnswer(scoreInvocation ->
                        score(objective, scoreInvocation.getArgument(0)));
                doAnswer(setSlot -> {
                    move(objective, setSlot.getArgument(0));
                    return null;
                }).when(objective).setDisplaySlot(any(DisplaySlot.class));
                doAnswer(ignored -> {
                    named.remove(OBJECTIVE_NAME, objective);
                    slots.values().removeIf(current -> current == objective);
                    return null;
                }).when(objective).unregister();
                return objective;
            });
        }

        private Objective currentTitleObjective() {
            return named.get(OBJECTIVE_NAME);
        }

        private Score score(Objective objective, String name) {
            return scores.computeIfAbsent(objective, ignored -> new HashMap<>()).computeIfAbsent(name, ignored -> {
                Score score = mock(Score.class);
                AtomicBoolean set = new AtomicBoolean();
                when(score.isScoreSet()).thenAnswer(call -> set.get());
                doAnswer(call -> {
                    set.set(true);
                    return null;
                }).when(score).numberFormat(any(NumberFormat.class));
                doAnswer(call -> {
                    set.set(false);
                    return null;
                }).when(score).resetScore();
                return score;
            });
        }

        private Objective installForeign(String name, DisplaySlot slot) {
            Objective foreign = mock(Objective.class);
            Objective replaced = named.put(name, foreign);
            if (replaced != null) slots.values().removeIf(objective -> objective == replaced);
            slots.put(slot, foreign);
            return foreign;
        }

        private void removeForeign(String name) {
            Objective foreign = named.remove(name);
            slots.values().removeIf(objective -> objective == foreign);
        }

        private void move(Objective objective, DisplaySlot slot) {
            slots.values().removeIf(current -> current == objective);
            if (slot != null) slots.put(slot, objective);
            objectiveSlots.get(objective).set(slot);
        }
    }
}
