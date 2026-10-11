package com.simmc.meplayeractions.client;

import java.util.*;
import java.util.function.*;

/** Bounded, serialized-once control messages. Budget deferral never executes failure cleanup. */
final class DeferredClientPackets
{
    enum Result
    {
        SENT, DEFERRED, FAILED
    }

    static final int CAPACITY = 128, RETENTION_TICKS = 100;
    private final LinkedHashMap<String, Packet> pending = new LinkedHashMap<>();
    private final IntPredicate reserve;
    private final IntConsumer release;

    DeferredClientPackets()
    {
        this(ignored -> true, ignored ->
        {
        });
    }

    DeferredClientPackets(IntPredicate reserve, IntConsumer release)
    {
        this.reserve = Objects.requireNonNull(reserve);
        this.release = Objects.requireNonNull(release);
    }

    boolean add(String key, byte[] payload, long tick, BooleanSupplier valid, Runnable sent, Runnable failed)
    {
        if (!this.pending.containsKey(key) && this.pending.size() >= CAPACITY)
        {
            failed.run();
            return false;
        }
        Packet previous = this.pending.remove(key);
        if (previous != null)
        {
            this.release.accept(previous.payload.length);
        }

        if (!this.reserve.test(payload.length))
        {
            failed.run();
            return false;
        }
        this.pending.put(key, new Packet(payload, tick, valid, sent, failed));
        return true;
    }

    void drain(Function<byte[], Result> sender, long tick, int maxAttempts)
    {
        int attempts = 0;
        for (String key : List.copyOf(this.pending.keySet()))
        {
            if (attempts++ >= maxAttempts)
            {
                break;
            }

            Packet packet = this.pending.get(key);
            if (packet == null)
            {
                continue;
            }

            if (!packet.valid.getAsBoolean() || ClientSyncCadence.distance(tick, packet.tick) >= RETENTION_TICKS)
            {
                this.pending.remove(key);
                this.release.accept(packet.payload.length);
                packet.failed.run();
                continue;
            }
            Result result = sender.apply(packet.payload);
            if (result == Result.DEFERRED)
            {
                return;
            }

            this.pending.remove(key);
            this.release.accept(packet.payload.length);
            if (result == Result.SENT)
            {
                packet.sent.run();
            }
            else
            {
                packet.failed.run();
            }

        }
    }

    int size()
    {
        return this.pending.size();
    }

    void cancel(String key)
    {
        Packet previous = this.pending.remove(key);
        if (previous != null)
        {
            this.release.accept(previous.payload.length);
        }
    }

    void clear()
    {
        List<DeferredClientPackets.Packet> values = List.copyOf(this.pending.values());
        this.pending.clear();
        values.forEach(packet ->
        {
            this.release.accept(packet.payload.length);
            packet.failed.run();
        });
    }

    private record Packet(byte[] payload, long tick, BooleanSupplier valid, Runnable sent, Runnable failed)
    {
    }
}
