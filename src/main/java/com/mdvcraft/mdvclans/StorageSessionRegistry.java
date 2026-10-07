package com.mdvcraft.mdvclans;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Tracks one editable storage session per clan, including administrative access.
 * The caller must save the current inventory before replacing or releasing it.
 */
final class StorageSessionRegistry<I> {
    record Session<I>(int clanId, UUID owner, I inventory) {
        Session {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(inventory, "inventory");
        }
    }

    private final Map<Integer, Session<I>> sessions = new HashMap<>();

    synchronized boolean isAvailable(int clanId, UUID owner) {
        Objects.requireNonNull(owner, "owner");
        Session<I> current = sessions.get(clanId);
        return current == null || current.owner().equals(owner);
    }

    /** Claims an unused clan, or replaces a saved page belonging to the same owner. */
    synchronized boolean claim(int clanId, UUID owner, I inventory) {
        Session<I> candidate = new Session<>(clanId, owner, inventory);
        if (!isAvailable(clanId, owner)) return false;
        sessions.put(clanId, candidate);
        return true;
    }

    synchronized boolean owns(int clanId, UUID owner, I inventory) {
        Session<I> current = sessions.get(clanId);
        return current != null && current.owner().equals(owner) && current.inventory() == inventory;
    }

    /** An old page's close event cannot release a replacement page's session. */
    synchronized boolean release(int clanId, UUID owner, I inventory) {
        if (!owns(clanId, owner, inventory)) return false;
        sessions.remove(clanId);
        return true;
    }

    synchronized Session<I> get(int clanId) {
        return sessions.get(clanId);
    }

    synchronized List<Session<I>> snapshot() {
        return List.copyOf(sessions.values());
    }

    synchronized void clear() {
        sessions.clear();
    }
}
