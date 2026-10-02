package com.simmc.meplayeractions.gameplay;

import com.simmc.meplayeractions.action.DisguiseOptions.Effect;
import java.util.*;

/** Main-thread effect leases; external changes end ownership, including other plugins' writes. */
public final class DisguiseEffects {
    public record State(String id, int amplifier, int duration, boolean ambient, boolean particles,
                        boolean icon, State hidden) {
        public State remaining(long elapsed) {
            State next = hidden == null ? null : hidden.remaining(elapsed);
            long left = duration < 0 ? -1 : duration - elapsed;
            return duration >= 0 && left <= 0 ? next : new State(id, amplifier, (int) left, ambient, particles, icon, next);
        }
        private boolean sameAppearance(State other) {
            return other != null && id.equals(other.id) && amplifier == other.amplifier
                    && ambient == other.ambient && particles == other.particles && icon == other.icon;
        }
    }
    public interface Port {
        State current(String id);
        boolean add(State state);
        void remove(String id);
    }
    private static final int LEASE = 200, RENEW = 100;
    private static final class Owned {
        final State previous;
        final long captured, expires;
        final Effect requested;
        State written;
        long nextRenew;
        boolean removedPrevious;
        Owned(State previous, Effect requested, long tick) {
            this.previous = previous; this.requested = requested; captured = tick;
            expires = requested.seconds() == 0 ? Long.MAX_VALUE : tick + requested.seconds() * 20L;
        }
    }
    private final Port port;
    private final Map<String, Owned> owned = new LinkedHashMap<>();
    private boolean writing;
    public DisguiseEffects(Port port) { this.port = port; }
    public boolean empty() { return owned.isEmpty(); }
    public List<String> activeIds() { return List.copyOf(owned.keySet()); }

    public void apply(List<Effect> effects, long tick) {
        if (!owned.isEmpty()) throw new IllegalStateException("旧伪装药水尚未清理");
        try {
            for (Effect effect : effects) {
                Owned item = new Owned(port.current(effect.id()), effect, tick);
                owned.put(effect.id(), item);
                writing = true;
                try {
                    if (item.previous != null) {
                        port.remove(effect.id());
                        if (port.current(effect.id()) != null) throw rejected(effect.id());
                        item.removedPrevious = true;
                    }
                    write(item, tick);
                } finally { writing = false; }
            }
        } catch (RuntimeException failure) {
            try { clear(tick, true); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private void write(Owned item, long tick) {
        int duration = (int) Math.min(LEASE, item.expires - tick);
        State desired = new State(item.requested.id(), item.requested.level() - 1, duration, false, false, true, null);
        if (!port.add(desired)) throw rejected(desired.id());
        State current = port.current(desired.id());
        if (!desired.sameAppearance(current)) throw rejected(desired.id());
        item.written = current; item.nextRenew = tick + Math.min(RENEW, duration);
    }
    public void tick(long tick) {
        for (String id : List.copyOf(owned.keySet())) {
            Owned item = owned.get(id);
            if (item.written == null || tick >= item.expires || port.current(id) == null) {
                release(id, item, tick, true); continue;
            }
            if (!item.written.sameAppearance(port.current(id))) { owned.remove(id); continue; }
            if (tick >= item.nextRenew) {
                writing = true;
                try { write(item, tick); }
                finally { writing = false; }
            }
        }
    }
    /** Expiration is handled next tick so a pre-existing effect can resume with elapsed time deducted. */
    public void changed(String id, boolean expiration) {
        if (!writing && !expiration) owned.remove(id);
    }
    public void clear(long tick, boolean restorePrevious) {
        RuntimeException failure = null;
        for (String id : List.copyOf(owned.keySet())) {
            try { release(id, owned.get(id), tick, restorePrevious); }
            catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        if (failure != null) throw failure;
    }
    private void release(String id, Owned item, long tick, boolean restorePrevious) {
        State current = port.current(id);
        // External events usually revoke ownership first; the comparison is a fallback.
        if (current != null && (item.written == null || !item.written.sameAppearance(current))) {
            owned.remove(id); return;
        }
        writing = true;
        try {
            if (current != null) {
                port.remove(id);
                if (port.current(id) != null) throw rejected(id);
                item.written = null;
            }
            State previous = restorePrevious && item.previous != null && (item.removedPrevious || current != null)
                    ? item.previous.remaining(Math.max(0, tick - item.captured)) : null;
            if (previous != null && !port.add(previous)) throw new IllegalStateException("无法恢复原有药水：" + id);
            owned.remove(id);
        } finally { writing = false; }
    }
    private static IllegalStateException rejected(String id) {
        return new IllegalStateException("伪装药水 " + id + " 应用/清理被取消；请检查其他插件的药水限制");
    }
}
