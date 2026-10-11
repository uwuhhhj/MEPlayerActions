package com.simmc.meplayeractions.gameplay;

import com.simmc.meplayeractions.action.DisguiseOptions.Effect;
import java.util.*;

/** Main-thread effect leases; external changes end ownership, including other plugins' writes. */
public final class DisguiseEffects
{
    public record State(String id, int amplifier, int duration, boolean ambient, boolean particles, boolean icon,
            State hidden)
    {
        public State remaining(long elapsed)
        {
            State next = this.hidden == null ? null : this.hidden.remaining(elapsed);
            long left = this.duration < 0 ? -1 : this.duration - elapsed;
            return this.duration >= 0 && left <= 0 ? next
                    : new State(this.id, this.amplifier, (int) left, this.ambient, this.particles, this.icon, next);
        }

        private boolean sameAppearance(State other)
        {
            return other != null && this.id.equals(other.id) && this.amplifier == other.amplifier
                    && this.ambient == other.ambient && this.particles == other.particles && this.icon == other.icon;
        }
    }

    public interface Port
    {
        State current(String id);

        boolean add(State state);

        void remove(String id);
    }

    private static final int LEASE = 200, RENEW = 100;

    private static final class Owned
    {
        final State previous;
        final long captured, expires;
        final Effect requested;
        State written;
        long nextRenew;
        boolean removedPrevious;

        Owned(State previous, Effect requested, long tick)
        {
            this.previous = previous;
            this.requested = requested;
            this.captured = tick;
            this.expires = requested.seconds() == 0 ? Long.MAX_VALUE : tick + requested.seconds() * 20L;
        }
    }

    private final Port port;
    private final Map<String, Owned> owned = new LinkedHashMap<>();
    private boolean writing;

    public DisguiseEffects(Port port)
    {
        this.port = port;
    }

    public boolean empty()
    {
        return this.owned.isEmpty();
    }

    public List<String> activeIds()
    {
        return List.copyOf(this.owned.keySet());
    }

    public void apply(List<Effect> effects, long tick)
    {
        if (!this.owned.isEmpty())
        {
            throw new IllegalStateException("旧伪装药水尚未清理");
        }

        try
        {
            for (Effect effect : effects)
            {
                Owned item = new Owned(this.port.current(effect.id()), effect, tick);
                this.owned.put(effect.id(), item);
                this.writing = true;
                try
                {
                    if (item.previous != null)
                    {
                        this.port.remove(effect.id());
                        if (this.port.current(effect.id()) != null)
                        {
                            throw rejected(effect.id());
                        }

                        item.removedPrevious = true;
                    }
                    write(item, tick);
                }
                finally
                {
                    this.writing = false;
                }
            }
        }
        catch (RuntimeException failure)
        {
            try
            {
                clear(tick, true);
            }
            catch (RuntimeException cleanup)
            {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void write(Owned item, long tick)
    {
        int duration = (int) Math.min(LEASE, item.expires - tick);
        State desired = new State(item.requested.id(), item.requested.level() - 1, duration, false, false, true, null);
        if (!this.port.add(desired))
        {
            throw rejected(desired.id());
        }

        State current = this.port.current(desired.id());
        if (!desired.sameAppearance(current))
        {
            throw rejected(desired.id());
        }

        item.written = current;
        item.nextRenew = tick + Math.min(RENEW, duration);
    }

    public void tick(long tick)
    {
        for (String id : List.copyOf(this.owned.keySet()))
        {
            Owned item = this.owned.get(id);
            if (item.written == null || tick >= item.expires || this.port.current(id) == null)
            {
                release(id, item, tick, true);
                continue;
            }
            if (!item.written.sameAppearance(this.port.current(id)))
            {
                this.owned.remove(id);
                continue;
            }
            if (tick >= item.nextRenew)
            {
                this.writing = true;
                try
                {
                    write(item, tick);
                }
                finally
                {
                    this.writing = false;
                }
            }
        }
    }

    /** Expiration is handled next tick so a pre-existing effect can resume with elapsed time deducted. */
    public void changed(String id, boolean expiration)
    {
        if (!this.writing && !expiration)
        {
            this.owned.remove(id);
        }

    }

    public void clear(long tick, boolean restorePrevious)
    {
        RuntimeException failure = null;
        for (String id : List.copyOf(this.owned.keySet()))
        {
            try
            {
                release(id, this.owned.get(id), tick, restorePrevious);
            }
            catch (RuntimeException e)
            {
                if (failure == null)
                {
                    failure = e;
                }
                else
                {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null)
        {
            throw failure;
        }

    }

    private void release(String id, Owned item, long tick, boolean restorePrevious)
    {
        State current = this.port.current(id);
        // External events usually revoke ownership first; the comparison is a fallback.
        if (current != null && (item.written == null || !item.written.sameAppearance(current)))
        {
            this.owned.remove(id);
            return;
        }
        this.writing = true;
        try
        {
            if (current != null)
            {
                this.port.remove(id);
                if (this.port.current(id) != null)
                {
                    throw rejected(id);
                }

                item.written = null;
            }
            State previous = restorePrevious && item.previous != null && (item.removedPrevious || current != null)
                    ? item.previous.remaining(Math.max(0, tick - item.captured))
                    : null;
            if (previous != null && !this.port.add(previous))
            {
                throw new IllegalStateException("无法恢复原有药水：" + id);
            }

            this.owned.remove(id);
        }
        finally
        {
            this.writing = false;
        }
    }

    private static IllegalStateException rejected(String id)
    {
        return new IllegalStateException("伪装药水 " + id + " 应用/清理被取消；请检查其他插件的药水限制");
    }
}
