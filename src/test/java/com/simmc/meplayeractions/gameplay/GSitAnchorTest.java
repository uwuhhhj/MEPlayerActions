package com.simmc.meplayeractions.gameplay;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class GSitAnchorTest {
    @Test void surfaceRestoresThePublicSeatOffsetForFullSlabsAndCustomHeights() {
        UUID worldId = UUID.randomUUID();
        World world = (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{World.class},
                (proxy, method, args) -> method.getName().equals("getUID") ? worldId : null);
        for (double surface : new double[]{-60, -60.5, -59.5, -59.75}) {
            Location seat = new Location(world, 1.25, surface + .05, 2.75, 90, 18);
            GSitAnchor anchor = GSitAnchor.of(seat, -.05);
            assertEquals(surface, anchor.y(), 1e-9);
            assertEquals(worldId, anchor.world());
            assertEquals(1.25, anchor.x()); assertEquals(2.75, anchor.z());
            assertEquals(90, anchor.bodyYaw());
            assertEquals(surface + .05, seat.getY(), "Sampling must not mutate GSit's Location");
        }
    }
    @Test void unavailableWorldCannotCreateAVisualFrame() {
        assertNull(GSitAnchor.of(null, -.05));
        assertNull(GSitAnchor.of(new Location(null, 0, 0, 0), -.05));
    }
}
