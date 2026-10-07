package com.mdvcraft.mdvclans;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StorageSessionRegistryTest {
    private final StorageSessionRegistry<Object> registry = new StorageSessionRegistry<>();
    private final UUID member = UUID.randomUUID();
    private final UUID administrator = UUID.randomUUID();

    @Test
    void memberAndAdministratorCannotEditTheSameClanTogether() {
        Object memberInventory = new Object();
        assertTrue(registry.claim(12, member, memberInventory));
        assertFalse(registry.isAvailable(12, administrator));
        assertFalse(registry.claim(12, administrator, new Object()));
        assertTrue(registry.owns(12, member, memberInventory));

        assertTrue(registry.release(12, member, memberInventory));
        assertTrue(registry.claim(12, administrator, new Object()));
        assertFalse(registry.claim(12, member, new Object()));
    }

    @Test
    void closingPreviousPageDoesNotUnlockReplacementPage() {
        Object previousPage = new Object();
        Object nextPage = new Object();
        assertTrue(registry.claim(12, member, previousPage));
        assertTrue(registry.claim(12, member, nextPage));

        assertFalse(registry.release(12, member, previousPage));
        assertFalse(registry.owns(12, member, previousPage));
        assertTrue(registry.owns(12, member, nextPage));
        assertFalse(registry.claim(12, administrator, new Object()));
    }

    @Test
    void ownerCanReopenAndReleaseOnlyTheirCurrentInventory() {
        Object firstInventory = new Object();
        Object reopenedInventory = new Object();
        assertTrue(registry.claim(12, member, firstInventory));
        assertTrue(registry.isAvailable(12, member));
        assertTrue(registry.claim(12, member, reopenedInventory));
        assertFalse(registry.release(12, administrator, reopenedInventory));
        assertTrue(registry.release(12, member, reopenedInventory));
        assertFalse(registry.release(12, member, reopenedInventory));
        assertNull(registry.get(12));
        assertTrue(registry.isAvailable(12, administrator));
    }

    @Test
    void inventoryIdentityMattersEvenWhenObjectsAreEqual() {
        Object inventory = new String("inventory");
        Object equivalentInventory = new String("inventory");
        assertEquals(inventory, equivalentInventory);
        assertTrue(registry.claim(12, member, inventory));
        assertFalse(registry.owns(12, member, equivalentInventory));
        assertFalse(registry.release(12, member, equivalentInventory));
        assertTrue(registry.owns(12, member, inventory));
    }

    @Test
    void failedSaveRetainsOriginalInventoryForRetry() {
        Object inventory = new Object();
        assertTrue(registry.claim(12, member, inventory));

        assertThrows(IOException.class, () -> {
            saveThatFails();
            registry.release(12, member, inventory);
        });

        assertSame(inventory, registry.get(12).inventory());
        assertFalse(registry.claim(12, administrator, new Object()));
        assertEquals(List.of(registry.get(12)), registry.snapshot());
        // A later successful persistence attempt can release the original session.
        assertTrue(registry.release(12, member, inventory));
        assertTrue(registry.claim(12, administrator, new Object()));
    }

    @Test
    void differentClansRemainIndependentlyAccessible() {
        Object firstClanInventory = new Object();
        Object secondClanInventory = new Object();
        assertTrue(registry.claim(12, member, firstClanInventory));
        assertTrue(registry.claim(34, administrator, secondClanInventory));
        assertEquals(2, registry.snapshot().size());
        assertTrue(registry.release(12, member, firstClanInventory));
        assertTrue(registry.owns(34, administrator, secondClanInventory));
    }

    @Test
    void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return registry.claim(12, member, new Object());
            });
            Future<Boolean> second = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return registry.claim(12, administrator, new Object());
            });
            start.countDown();
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(1, registry.snapshot().size());
        }
    }

    @Test
    void snapshotIsStableWhenRegistryChanges() {
        assertTrue(registry.claim(12, member, new Object()));
        List<StorageSessionRegistry.Session<Object>> snapshot = registry.snapshot();
        registry.clear();
        assertEquals(1, snapshot.size());
        assertTrue(registry.snapshot().isEmpty());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    @Test
    void missingOwnerOrInventoryCannotCreateSession() {
        assertThrows(NullPointerException.class, () -> registry.claim(12, null, new Object()));
        assertThrows(NullPointerException.class, () -> registry.claim(12, member, null));
        assertTrue(registry.snapshot().isEmpty());
    }

    private static void saveThatFails() throws IOException {
        throw new IOException("Simulated persistence failure");
    }
}
