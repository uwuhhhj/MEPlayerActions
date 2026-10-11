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
        public Key {
            Objects.requireNonNull(owner);
            Objects.requireNonNull(entity);
            Objects.requireNonNull(processor);
        }

        public boolean body() {
            return this.processor.equals("body");
        }
    }

    private final int maximum;
    private final Consumer<T> close;
    private final Map<Key, T> states = new LinkedHashMap<>();

    public ModelEffectInstances(int maximum, Consumer<T> close) {
        if (maximum < 1) {
            throw new IllegalArgumentException("Effect instance budget");
        }

        this.maximum = maximum;
        this.close = Objects.requireNonNull(close);
    }

    /** A new binding/entity replaces only this processor; another processor never evicts its sibling. */
    public T acquire(Key key, Predicate<T> sameInstance, Supplier<T> create) {
        T state = this.states.get(key);
        if (state != null && !sameInstance.test(state)) {
            this.states.remove(key);
            this.close.accept(state);
            state = null;
        }
        if (state == null) {
            if (this.states.size() >= this.maximum) {
                return null;
            }

            state = Objects.requireNonNull(create.get());
            this.states.put(key, state);
        }
        return state;
    }

    public Map<Key, T> snapshot() {
        return Map.copyOf(this.states);
    }

    public void forEach(BiConsumer<Key, T> action) {
        this.states.forEach(action);
    }

    public void retain(BiPredicate<Key, T> valid) {
        for (Key key : snapshot().keySet()) {
            if (!valid.test(key, this.states.get(key))) {
                T state = this.states.remove(key);
                this.close.accept(state);
            }
        }

    }

    public void closeOwner(UUID owner) {
        retain((key, state) -> !key.owner().equals(owner));
    }

    public void close() {
        for (T state : this.states.values()) {
            this.close.accept(state);
        }

        this.states.clear();
    }
}
