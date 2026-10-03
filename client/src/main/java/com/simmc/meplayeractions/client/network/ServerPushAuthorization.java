package com.simmc.meplayeractions.client.network;

import java.util.*;

/** Server-issued download grants; observing a hash never authorizes unsolicited bytes. */
public final class ServerPushAuthorization {
    public static final int MAX_OFFERS = 4;
    public static final long MAX_LIFETIME = 60_000_000_000L, IDLE_TIMEOUT = 15_000_000_000L;
    public record Identity(UUID owner, String instance, String modelId, String hash) {
        public Identity {
            Objects.requireNonNull(owner);
            if (instance == null || !UUID.fromString(instance).toString().equals(instance)
                    || modelId == null || !modelId.matches("[a-z0-9_-]{1,64}")
                    || hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Offer identity");
        }
    }
    public record Offer(UUID offerId, Identity identity) {
        public Offer { Objects.requireNonNull(offerId); Objects.requireNonNull(identity); }
    }
    public enum Phase { CACHE_VALIDATION, MISSING, RECEIVING, DECODING }
    private static final class Grant {
        final Offer offer; final long created;
        long progress; Phase phase = Phase.CACHE_VALIDATION; AssetTransfer transfer;
        Grant(Offer offer, long now) { this.offer = offer; created = progress = now; }
    }
    private final Map<UUID, Grant> grants = new LinkedHashMap<>();
    private final Map<UUID, Offer> retired = new LinkedHashMap<>();

    public static boolean acceptsHandshake(String mode, Collection<String> capabilities) {
        return "server-push".equals(mode) && capabilities != null
                && capabilities.contains("local_render") && capabilities.contains("server_push_models");
    }

    /** Exact binding identity is required at issuance; duplicate grants cannot reset time or phase. */
    public boolean offer(Offer offer, Collection<Identity> currentBindings, long now) {
        if (!currentBindings.contains(offer.identity())) throw new IllegalArgumentException("Offer has no current binding");
        Offer completed = retired.get(offer.offerId());
        if (completed != null) {
            if (!completed.equals(offer)) throw new IllegalArgumentException("Reused offer ID");
            return false;
        }
        Grant previous = grants.get(offer.offerId());
        if (previous != null) {
            if (!previous.offer.equals(offer)) throw new IllegalArgumentException("Conflicting offer ID");
            return false;
        }
        if (grants.size() >= MAX_OFFERS) throw new IllegalArgumentException("Too many active offers");
        grants.put(offer.offerId(), new Grant(offer, now));
        return true;
    }

    public Optional<Offer> find(UUID id) { return Optional.ofNullable(grants.get(id)).map(grant -> grant.offer); }
    public boolean current(Offer offer, Collection<Identity> currentBindings, long now) {
        Grant grant = grants.get(offer.offerId());
        return grant != null && grant.offer.equals(offer) && valid(grant, currentBindings, now);
    }
    private static boolean valid(Grant grant, Collection<Identity> bindings, long now) {
        return now - grant.created < MAX_LIFETIME && now - grant.progress < IDLE_TIMEOUT
                && bindings.stream().anyMatch(binding -> binding.hash().equals(grant.offer.identity().hash()));
    }
    private Grant require(UUID id, String hash, Collection<Identity> bindings, long now) {
        Grant grant = grants.get(id);
        if (grant == null || !grant.offer.identity().hash().equals(hash) || !valid(grant, bindings, now))
            throw new IllegalArgumentException("Unissued, stale or mismatched asset offer");
        return grant;
    }
    public void missing(Offer offer, Collection<Identity> bindings, long now) {
        Grant grant = require(offer.offerId(), offer.identity().hash(), bindings, now);
        if (grant.phase != Phase.CACHE_VALIDATION) throw new IllegalArgumentException("Unexpected cache feedback");
        grant.phase = Phase.MISSING; grant.progress = now;
    }
    public void begin(UUID id, String hash, String modelId, int rawBytes, int compressedBytes, int chunks,
                      Collection<Identity> bindings, long now) {
        Grant grant = require(id, hash, bindings, now);
        if (grant.phase != Phase.MISSING || !grant.offer.identity().modelId().equals(modelId))
            throw new IllegalArgumentException("Transfer does not match missing offer");
        AssetTransfer transfer = new AssetTransfer(modelId, hash, rawBytes, compressedBytes, chunks);
        grant.transfer = transfer; grant.phase = Phase.RECEIVING; grant.progress = now;
    }
    public void chunk(UUID id, String hash, int index, String data, Collection<Identity> bindings, long now) {
        Grant grant = require(id, hash, bindings, now);
        if (grant.phase != Phase.RECEIVING) throw new IllegalArgumentException("Chunk outside offered transfer");
        grant.transfer.put(index, data); grant.progress = now;
    }
    public AssetTransfer end(UUID id, String hash, Collection<Identity> bindings, long now) {
        Grant grant = require(id, hash, bindings, now);
        if (grant.phase != Phase.RECEIVING) throw new IllegalArgumentException("End outside offered transfer");
        AssetTransfer transfer = grant.transfer; grant.transfer = null;
        grant.phase = Phase.DECODING; grant.progress = now;
        return transfer;
    }
    public boolean remove(Offer offer) {
        Grant grant = grants.get(offer.offerId());
        if (grant == null || !grant.offer.equals(offer)) return false;
        grants.remove(offer.offerId()); retire(offer); return true;
    }
    public List<Offer> prune(Collection<Identity> currentBindings, long now) {
        List<Offer> removed = new ArrayList<>();
        grants.values().removeIf(grant -> {
            if (valid(grant, currentBindings, now)) return false;
            removed.add(grant.offer); return true;
        });
        removed.forEach(this::retire);
        return List.copyOf(removed);
    }
    private void retire(Offer offer) {
        retired.put(offer.offerId(), offer);
        if (retired.size() > 64) retired.remove(retired.keySet().iterator().next());
    }
    public void clear() { grants.clear(); retired.clear(); }
    public int receiving() { return (int) grants.values().stream().filter(grant -> grant.phase == Phase.RECEIVING).count(); }
    public List<Map<String, Object>> diagnostics() {
        return grants.values().stream().map(grant -> {
            Identity identity = grant.offer.identity();
            return Map.<String, Object>of("owner", identity.owner().toString(), "instance", identity.instance(),
                    "modelId", identity.modelId(), "hash", identity.hash(), "offerId", grant.offer.offerId().toString(),
                    "phase", grant.phase.name());
        }).toList();
    }
}
