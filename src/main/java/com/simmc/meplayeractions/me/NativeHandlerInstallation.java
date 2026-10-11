package com.simmc.meplayeractions.me;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Main-thread owner requests, network-thread installation, and a main-thread readiness decision. */
final class NativeHandlerInstallation
{
    enum State
    {
        PENDING, READY, FAILED, CLOSED
    }

    private record Request(Object lease, BooleanSupplier valid)
    {
    }

    private final Executor network, main;
    private final BooleanSupplier channelLive, current, install;
    private final Runnable remove;
    private final Map<Object, Request> requests = new HashMap<>();
    private volatile State state = State.PENDING;
    private volatile boolean cancelled;
    private boolean started;

    NativeHandlerInstallation(Executor network, Executor main, BooleanSupplier channelLive, BooleanSupplier current,
            BooleanSupplier install, Runnable remove)
    {
        this.network = network;
        this.main = main;
        this.channelLive = channelLive;
        this.current = current;
        this.install = install;
        this.remove = remove;
    }

    boolean request(Object owner, Object lease, BooleanSupplier valid)
    {
        if (this.state == State.CLOSED || !test(valid) || !test(this.channelLive) || !test(this.current))
        {
            return false;
        }

        this.requests.put(owner, new Request(lease, valid));
        if (!this.started)
        {
            this.started = true;
            try
            {
                this.network.execute(this::installOnNetwork);
            }
            catch (RuntimeException unavailable)
            {
                this.state = State.FAILED;
            }
        }
        return this.state == State.READY;
    }

    boolean pending(Object owner, Object lease)
    {
        Request request = this.requests.get(owner);
        return this.state == State.PENDING && request != null && request.lease == lease && test(request.valid)
                && test(this.channelLive) && test(this.current);
    }

    void withdraw(Object owner)
    {
        this.requests.remove(owner);
        if (this.requests.isEmpty())
        {
            close();
        }
    }

    boolean empty()
    {
        return this.requests.isEmpty();
    }

    State state()
    {
        return this.state;
    }

    private void installOnNetwork()
    {
        if (this.cancelled)
        {
            return;
        }

        boolean success;
        try
        {
            success = test(this.channelLive) && this.install.getAsBoolean();
        }
        catch (RuntimeException | LinkageError unavailable)
        {
            success = false;
        }
        if (!success || this.cancelled)
        {
            this.remove.run();
        }

        if (this.cancelled)
        {
            return;
        }

        final boolean installed = success;
        try
        {
            this.main.execute(() -> completeOnMain(installed));
        }
        catch (RuntimeException stopped)
        {
            this.cancelled = true;
            this.state = State.CLOSED;
            this.remove.run();
        }
    }

    private void completeOnMain(boolean success)
    {
        if (this.cancelled)
        {
            return;
        }

        this.requests.values().removeIf(request -> !test(request.valid));
        if (this.requests.isEmpty() || !test(this.channelLive) || !test(this.current))
        {
            close();
            return;
        }
        this.state = success ? State.READY : State.FAILED;
    }

    void close()
    {
        if (this.cancelled)
        {
            return;
        }

        this.cancelled = true;
        this.state = State.CLOSED;
        this.requests.clear();
        try
        {
            this.network.execute(this.remove);
        }
        catch (RuntimeException stopped)
        {
            /* A stopped event loop cannot publish a late successful installation. */}
    }

    private static boolean test(BooleanSupplier predicate)
    {
        try
        {
            return predicate.getAsBoolean();
        }
        catch (RuntimeException | LinkageError unavailable)
        {
            return false;
        }
    }
}
