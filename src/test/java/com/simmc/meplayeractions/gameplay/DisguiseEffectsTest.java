package com.simmc.meplayeractions.gameplay;

import com.simmc.meplayeractions.action.DisguiseOptions.Effect;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DisguiseEffectsTest
{
    private static DisguiseEffects.State state(String id, int amplifier, int ticks)
    {
        return new DisguiseEffects.State(id, amplifier, ticks, true, true, true, null);
    }

    private static final class Potions implements DisguiseEffects.Port
    {
        final Map<String, DisguiseEffects.State> current = new HashMap<>();
        final DisguiseEffects effects = new DisguiseEffects(this);
        String rejectAdd, rejectRemove;
        boolean rejectRestore;
        int writes;

        @Override
        public DisguiseEffects.State current(String id)
        {
            return this.current.get(id);
        }

        @Override
        public boolean add(DisguiseEffects.State state)
        {
            if (state.id().equals(this.rejectAdd) || (this.rejectRestore && state.ambient()))
            {
                return false;
            }

            this.current.put(state.id(), state);
            this.writes++;
            this.effects.changed(state.id(), false);
            return true;
        }

        @Override
        public void remove(String id)
        {
            if (id.equals(this.rejectRemove))
            {
                return;
            }

            this.current.remove(id);
            this.writes++;
            this.effects.changed(id, false);
        }
    }

    @Test
    void sessionEffectUsesBoundedLeaseAndRestoresAgedOriginal()
    {
        DisguiseEffectsTest.Potions p = new Potions();
        p.current.put("slowness", state("slowness", 2, 1000));
        p.effects.apply(List.of(new Effect("slowness", 1, 0)), 0);
        assertEquals(0, p.current("slowness").amplifier());
        assertEquals(200, p.current("slowness").duration());
        assertEquals(List.of("slowness"), p.effects.activeIds());
        p.effects.clear(100, true);
        assertEquals(state("slowness", 2, 900), p.current("slowness"));
        int writes = p.writes;
        p.effects.clear(100, true);
        assertEquals(writes, p.writes);
    }

    @Test
    void renewalKeepsOwnershipAndFiniteEffectsExpireIndependently()
    {
        DisguiseEffectsTest.Potions p = new Potions();
        p.current.put("speed", state("speed", 0, 1000));
        p.effects.apply(List.of(new Effect("slowness", 1, 0), new Effect("speed", 2, 3)), 0);
        p.effects.tick(60);
        assertEquals(state("speed", 0, 940), p.current("speed"));
        assertEquals(List.of("slowness"), p.effects.activeIds());
        int writes = p.writes;
        p.effects.tick(100);
        assertTrue(p.writes > writes);
        assertFalse(p.effects.empty());
        p.effects.clear(120, true);
        assertNull(p.current("slowness"));
    }

    @Test
    void externalIdenticalEffectIsNotRemovedOrOverwrittenOnUndisguise()
    {
        DisguiseEffectsTest.Potions p = new Potions();
        p.effects.apply(List.of(new Effect("slowness", 1, 0)), 0);
        DisguiseEffects.State external = p.current("slowness");
        p.effects.changed("slowness", false); // Another plugin can write an identical effect.
        p.effects.tick(100);
        p.effects.clear(101, true);
        assertEquals(external, p.current("slowness"));
        assertTrue(p.effects.empty());
    }

    @Test
    void milkEndsOwnershipAndDoesNotResurrectTheOriginalPotion()
    {
        DisguiseEffectsTest.Potions p = new Potions();
        p.current.put("slowness", state("slowness", 2, 1000));
        p.effects.apply(List.of(new Effect("slowness", 1, 0)), 0);
        p.remove("slowness");
        p.effects.tick(100);
        p.effects.clear(101, true);
        assertNull(p.current("slowness"));
    }

    @Test
    void rejectedSecondEffectRollsBackFirstAndPreservesOriginals()
    {
        DisguiseEffectsTest.Potions p = new Potions();
        p.current.put("slowness", state("slowness", 2, 1000));
        p.rejectAdd = "speed";
        assertThrows(IllegalStateException.class,
                () -> p.effects.apply(List.of(new Effect("slowness", 1, 0), new Effect("speed", 2, 0)), 0));
        assertEquals(state("slowness", 2, 1000), p.current("slowness"));
        assertNull(p.current("speed"));
        assertTrue(p.effects.empty());
    }

    @Test
    void cancelledRemovalLeavesTheOriginalAndAllowsCleanupToBeRetried()
    {
        DisguiseEffectsTest.Potions p = new Potions();
        p.current.put("slowness", state("slowness", 2, 1000));
        p.rejectRemove = "slowness";
        assertThrows(IllegalStateException.class, () -> p.effects.apply(List.of(new Effect("slowness", 1, 0)), 0));
        assertEquals(state("slowness", 2, 1000), p.current("slowness"));
        assertTrue(p.effects.empty());
        p.rejectRemove = null;
        p.effects.apply(List.of(new Effect("slowness", 1, 0)), 0);
        p.rejectRemove = "slowness";
        assertThrows(IllegalStateException.class, () -> p.effects.clear(100, true));
        assertFalse(p.effects.empty());
        p.rejectRemove = null;
        p.effects.clear(101, true);
        assertEquals(state("slowness", 2, 899), p.current("slowness"));
    }

    @Test
    void naturalExpiryRestoresOriginalButDeathDoesNot()
    {
        DisguiseEffectsTest.Potions p = new Potions();
        p.current.put("slowness", state("slowness", 2, 1000));
        p.effects.apply(List.of(new Effect("slowness", 1, 1)), 0);
        p.current.remove("slowness");
        p.effects.changed("slowness", true);
        p.effects.tick(20);
        assertEquals(state("slowness", 2, 980), p.current("slowness"));
        p.effects.apply(List.of(new Effect("slowness", 1, 0)), 20);
        p.effects.clear(21, false);
        assertNull(p.current("slowness"));
    }

    @Test
    void elapsedOriginalAndHiddenEffectsNeverBecomeInfiniteByAccident()
    {
        assertNull(state("speed", 0, 20).remaining(21));
        assertEquals(-1, state("speed", 0, -1).remaining(500).duration());
        DisguiseEffects.State chain = new DisguiseEffects.State("speed", 2, 20, false, false, true,
                state("speed", 0, 100));
        assertEquals(state("speed", 0, 79), chain.remaining(21));
        DisguiseEffectsTest.Potions p = new Potions();
        p.current.put("speed", state("speed", 0, 20));
        p.effects.apply(List.of(new Effect("speed", 2, 0)), 0);
        p.effects.clear(21, true);
        assertNull(p.current("speed"));
    }

    @Test
    void rejectedRestorationRetainsCleanupAndRetriesWithElapsedTime()
    {
        DisguiseEffectsTest.Potions p = new Potions();
        p.current.put("slowness", state("slowness", 2, 1000));
        p.effects.apply(List.of(new Effect("slowness", 1, 0)), 0);
        p.rejectRestore = true;
        assertThrows(IllegalStateException.class, () -> p.effects.clear(100, true));
        assertFalse(p.effects.empty());
        assertNull(p.current("slowness"));
        p.rejectRestore = false;
        p.effects.tick(101);
        assertTrue(p.effects.empty());
        assertEquals(state("slowness", 2, 899), p.current("slowness"));
    }

    @Test
    void changedEffectWithoutAnEventIsStillPreservedAtCleanup()
    {
        DisguiseEffectsTest.Potions p = new Potions();
        p.effects.apply(List.of(new Effect("slowness", 1, 0)), 0);
        DisguiseEffects.State external = state("slowness", 3, 1000);
        p.current.put("slowness", external);
        p.effects.clear(10, true);
        assertEquals(external, p.current("slowness"));
        assertTrue(p.effects.empty());
    }
}
