package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.client.model.BbModel.Layer;
import java.util.*;

/** Rebase authoritative manual/interaction starts once, without restarting on repeated packets. */
public final class LocalLayerClock {
    private final Map<String, Layer> received = new HashMap<>(), rebased = new HashMap<>();
    public List<Layer> accept(long serverTick, long localTick, List<Layer> layers) {
        Set<String> present = new HashSet<>();
        List<Layer> result = new ArrayList<>();
        for (Layer layer : layers) {
            present.add(layer.layer());
            if (!layer.equals(received.get(layer.layer()))) {
                received.put(layer.layer(), layer);
                rebased.put(layer.layer(), new Layer(layer.layer(), layer.animation(),
                        Math.max(0, localTick - Math.max(0, serverTick - layer.startedAtTick())),
                        layer.speed(), layer.loop(), layer.inTicks(), layer.outTicks()));
            }
            result.add(rebased.get(layer.layer()));
        }
        received.keySet().retainAll(present); rebased.keySet().retainAll(present);
        return List.copyOf(result);
    }
}
