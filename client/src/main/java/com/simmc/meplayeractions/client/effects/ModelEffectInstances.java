package com.simmc.meplayeractions.client.effects;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** OpenYSM AnimationProcessor owns an audio manager per processor, independently of shared model bytes. */
public final class ModelEffectInstances<T> {
    public record Key(UUID owner, UUID entity, String processor) {
        public Key { Objects.requireNonNull(owner); Objects.requireNonNull(entity); Objects.requireNonNull(processor); }
        public boolean body() { return processor.equals("body"); }
    }
    private final int maximum;
    private final Consumer<T> close;
    private final Map<Key, T> states = new LinkedHashMap<>();

    public ModelEffectInstances(int maximum, Consumer<T> close) {
        if (maximum < 1) throw new IllegalArgumentException("Effect instance budget");
        this.maximum = maximum; this.close = Objects.requireNonNull(close);
    }
    /** A new binding/entity replaces only this processor; another processor never evicts its sibling. */
    public T acquire(Key key, Predicate<T> sameInstance, Supplier<T> create) {
        T state = states.get(key);
        if (state != null && !sameInstance.test(state)) { states.remove(key); close.accept(state); state = null; }
        if (state == null) {
            if (states.size() >= maximum) return null;
            state = Objects.requireNonNull(create.get()); states.put(key, state);
        }
        return state;
    }
    public Map<Key, T> snapshot() { return Map.copyOf(states); }
    public void forEach(BiConsumer<Key, T> action) { states.forEach(action); }
    public void retain(BiPredicate<Key, T> valid) {
        for (Key key : snapshot().keySet()) if (!valid.test(key, states.get(key))) {
            T state = states.remove(key); close.accept(state);
        }
    }
    public void closeOwner(UUID owner) { retain((key, state) -> !key.owner().equals(owner)); }
    public void close() {
        for (T state : states.values()) close.accept(state);
        states.clear();
    }
}
