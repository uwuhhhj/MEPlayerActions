package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.protection.ResourceSettings;
import java.util.*;
import java.util.function.Predicate;

/** Main-thread server-issued authorizations; feedback cannot choose a model or replay a transfer. */
final class PushAssetOffers {
    static final int MAX_PENDING = 2, MAX_RECORDS = 64;
    static final int STATUS_TICKS = 100, IDLE_TICKS = 300, ABSOLUTE_TICKS = 1200, RENDER_TICKS = 200;
    record Offer(UUID id, UUID owner, UUID instance, String modelId, String hash) { }
    record Cancelled(Offer offer, String reason) { }
    enum Feedback { INVALID, CACHED, MISSING, REJECTED }
    private enum Phase { OFFERED, MISSING, TRANSFERRING, DELIVERED, CACHED, READY }
    private static final class Entry {
        final Offer offer; final long created; Phase phase = Phase.OFFERED; long at;
        Entry(Offer offer, long tick) { this.offer = offer; created = at = tick; }
    }
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private int maxPending = MAX_PENDING, statusTicks = STATUS_TICKS, queueTicks = IDLE_TICKS,
            idleTicks = IDLE_TICKS, totalTicks = ABSOLUTE_TICKS, renderTicks = RENDER_TICKS;

    void configure(ResourceSettings.Network policy) {
        maxPending = policy.transfersPerPlayer(); statusTicks = Math.min(STATUS_TICKS, policy.transferQueueTicks());
        queueTicks = policy.transferQueueTicks(); idleTicks = policy.transferIdleTicks();
        totalTicks = policy.transferTotalTicks(); renderTicks = policy.readyTicks();
    }

    int pending() { return (int) entries.values().stream().filter(e -> e.phase != Phase.READY).count(); }
    boolean canIssue(String hash) { return !entries.containsKey(hash) && entries.size() < MAX_RECORDS && pending() < maxPending; }
    Offer issue(UUID owner, UUID instance, String modelId, String hash, long tick) {
        if (!canIssue(hash)) return null;
        Offer offer = new Offer(UUID.randomUUID(), owner, instance, modelId, hash);
        entries.put(hash, new Entry(offer, tick)); return offer;
    }
    Feedback feedback(UUID id, String hash, String status, long tick) {
        Entry entry = entries.get(hash);
        if (entry == null || !entry.offer.id().equals(id) || entry.phase != Phase.OFFERED
                || distance(tick, entry.at) >= statusTicks) return Feedback.INVALID;
        Feedback result = switch (status) {
            case "cached" -> Feedback.CACHED;
            case "missing" -> Feedback.MISSING;
            case "rejected" -> Feedback.REJECTED;
            default -> Feedback.INVALID;
        };
        if (result == Feedback.INVALID) return result;
        if (result == Feedback.REJECTED) entries.remove(hash);
        else { entry.phase = result == Feedback.CACHED ? Phase.CACHED : Phase.MISSING; entry.at = tick; }
        return result;
    }
    List<Offer> missing() { return entries.values().stream().filter(e -> e.phase == Phase.MISSING).map(e -> e.offer).toList(); }
    boolean start(Offer offer) {
        Entry entry = current(offer);
        if (entry == null || entry.phase != Phase.MISSING) return false;
        entry.phase = Phase.TRANSFERRING; return true;
    }
    void delivered(Offer offer, long tick) {
        Entry entry = current(offer);
        if (entry != null && entry.phase == Phase.TRANSFERRING) { entry.phase = Phase.DELIVERED; entry.at = tick; }
    }
    void progress(Offer offer, long tick) {
        Entry entry = current(offer);
        if (entry != null && entry.phase == Phase.TRANSFERRING) entry.at = tick;
    }
    void ready(String hash) { Entry entry = entries.get(hash); if (entry != null) entry.phase = Phase.READY; }
    boolean transferring(Offer offer) { Entry entry = current(offer); return entry != null && entry.phase == Phase.TRANSFERRING; }
    private Entry current(Offer offer) {
        Entry entry = entries.get(offer.hash()); return entry != null && entry.offer.equals(offer) ? entry : null;
    }
    Offer find(String hash) { Entry entry = entries.get(hash); return entry == null ? null : entry.offer; }
    Offer remove(String hash) { Entry entry = entries.remove(hash); return entry == null ? null : entry.offer; }
    List<Cancelled> expire(long tick, Predicate<Offer> authorized) {
        List<Cancelled> cancelled = new ArrayList<>();
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next(); String reason = null;
            if (!authorized.test(entry.offer)) reason = "asset_not_authorized";
            else if (entry.phase != Phase.READY && distance(tick, entry.created) >= totalTicks) reason = "asset_offer_expired";
            else if (entry.phase == Phase.OFFERED && distance(tick, entry.at) >= statusTicks) reason = "asset_offer_expired";
            else if (entry.phase == Phase.MISSING && distance(tick, entry.at) >= queueTicks) reason = "asset_queue_timeout";
            else if (entry.phase == Phase.TRANSFERRING && distance(tick, entry.at) >= idleTicks) reason = "asset_transfer_timeout";
            else if ((entry.phase == Phase.DELIVERED || entry.phase == Phase.CACHED)
                    && distance(tick, entry.at) >= renderTicks) reason = "asset_render_timeout";
            if (reason != null) { iterator.remove(); cancelled.add(new Cancelled(entry.offer, reason)); }
        }
        return List.copyOf(cancelled);
    }
    List<Offer> clear() { List<Offer> result = entries.values().stream().map(e -> e.offer).toList(); entries.clear(); return result; }
    private static long distance(long now, long before) { return (now - before) & 0xffff_ffffL; }
}
