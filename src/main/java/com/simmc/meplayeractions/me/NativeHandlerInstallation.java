package com.simmc.meplayeractions.me;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Main-thread owner requests, network-thread installation, and a main-thread readiness decision. */
final class NativeHandlerInstallation {
    enum State { PENDING, READY, FAILED, CLOSED }
    private record Request(Object lease,BooleanSupplier valid) { }
    private final Executor network,main;
    private final BooleanSupplier channelLive,current,install;
    private final Runnable remove;
    private final Map<Object,Request> requests=new HashMap<>();
    private volatile State state=State.PENDING;
    private volatile boolean cancelled;
    private boolean started;
    NativeHandlerInstallation(Executor network,Executor main,BooleanSupplier channelLive,BooleanSupplier current,
                              BooleanSupplier install,Runnable remove) {
        this.network=network;this.main=main;this.channelLive=channelLive;this.current=current;this.install=install;this.remove=remove;
    }
    boolean request(Object owner,Object lease,BooleanSupplier valid) {
        if(state==State.CLOSED||!test(valid)||!test(channelLive)||!test(current))return false;
        requests.put(owner,new Request(lease,valid));
        if(!started) {
            started=true;
            try {network.execute(this::installOnNetwork);}
            catch(RuntimeException unavailable){state=State.FAILED;}
        }
        return state==State.READY;
    }
    boolean pending(Object owner,Object lease) {
        Request request=requests.get(owner);
        return state==State.PENDING&&request!=null&&request.lease==lease&&test(request.valid)
                &&test(channelLive)&&test(current);
    }
    void withdraw(Object owner) {requests.remove(owner);if(requests.isEmpty())close();}
    boolean empty() {return requests.isEmpty();}
    State state() {return state;}
    private void installOnNetwork() {
        if(cancelled)return;
        boolean success;
        try {success=test(channelLive)&&install.getAsBoolean();}
        catch(RuntimeException|LinkageError unavailable){success=false;}
        if(!success||cancelled)remove.run();
        if(cancelled)return;
        final boolean installed=success;
        try {main.execute(()->completeOnMain(installed));}
        catch(RuntimeException stopped){cancelled=true;state=State.CLOSED;remove.run();}
    }
    private void completeOnMain(boolean success) {
        if(cancelled)return;
        requests.values().removeIf(request->!test(request.valid));
        if(requests.isEmpty()||!test(channelLive)||!test(current)){close();return;}
        state=success?State.READY:State.FAILED;
    }
    void close() {
        if(cancelled)return;
        cancelled=true;state=State.CLOSED;requests.clear();
        try {network.execute(remove);}
        catch(RuntimeException stopped){/* A stopped event loop cannot publish a late successful installation. */}
    }
    private static boolean test(BooleanSupplier predicate){try{return predicate.getAsBoolean();}catch(RuntimeException|LinkageError unavailable){return false;}}
}
