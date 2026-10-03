package com.simmc.meplayeractions.client.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class YsmRenderAttachmentTest {
    private static void assertPoint(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 1e-6);
        assertEquals(expected.y, actual.y, 1e-6);
        assertEquals(expected.z, actual.z, 1e-6);
    }
    @Test void animatedHandRotationMovesTheItemAndItsCorrectionTogether() {
        Matrix4f locator = new Matrix4f().translation(1, 2, 3).rotateY((float) (Math.PI / 2));
        Matrix4f source = new Matrix4f(locator);
        Matrix4f item = YsmItemRenderer.itemTransform(locator);
        assertPoint(new Vector3f(.9f, 1.9375f, 3), item.transformPosition(new Vector3f()));
        assertPoint(new Vector3f(1, 2, 3), locator.transformPosition(new Vector3f()));
        assertEquals(source, locator, "Attaching an item must not mutate the model's sampled bone matrix");
        assertPoint(new Vector3f(.9f, 2.9375f, 3), item.transformPosition(new Vector3f(0, 0, 1)));
    }
    @Test void parentScaleAndMirroringRemainInTheItemAttachment() {
        Matrix4f locator = new Matrix4f().translation(0, 4, 0).scale(-2, 2, 2);
        Matrix4f item = YsmItemRenderer.itemTransform(locator);
        assertPoint(new Vector3f(0, 3.875f, -.2f), item.transformPosition(new Vector3f()));
        assertEquals(-8, item.determinant3x3(), 1e-5);
        assertTrue(YsmItemRenderer.usable(item));
        assertFalse(YsmItemRenderer.usable(new Matrix4f().scale(0, 1, 1)), "A hidden hand cannot draw an item");
        assertFalse(YsmItemRenderer.usable(new Matrix4f().m00(Float.NaN)));
    }
    @Test void armGeometryIsAnchoredAtTheAuthoredShoulderAndKeepsItsAnimationOffset() {
        Vector3f shoulder = new Vector3f(.071875f, 1.8609375f, 0);
        Matrix4f right = YsmComponentRenderer.armBasis(false, shoulder);
        assertPoint(new Vector3f(-5 / 16f, 2 / 16f, 0), right.transformPosition(new Vector3f(shoulder)));
        assertPoint(new Vector3f(-5 / 16f - .1f, 2 / 16f + 1, .2f),
                right.transformPosition(new Vector3f(shoulder).add(.1f, -1, .2f)));
        assertPoint(new Vector3f(5 / 16f, 2 / 16f, 0), YsmComponentRenderer.armBasis(true, shoulder).transformPosition(new Vector3f(shoulder)));
    }
    @Test void registryMatchesUseExactIdsAndActualTagMembership() {
        assertTrue(YsmComponentRenderer.matches(List.of("minecraft:arrow"), "minecraft:arrow", tag -> false));
        assertFalse(YsmComponentRenderer.matches(List.of("minecraft:arrow"), "other:arrow", tag -> false));
        assertTrue(YsmComponentRenderer.matches(List.of("#minecraft:boats"), "minecraft:oak_boat", Set.of("minecraft:boats")::contains));
        assertFalse(YsmComponentRenderer.matches(List.of("#minecraft:boats"), "minecraft:oak_boat", tag -> false));
        assertFalse(YsmComponentRenderer.matches(List.of(), "minecraft:arrow", tag -> true));
    }
    @Test void privateArmScaleChangesSizeAroundTheShoulderWithoutMovingItsCameraAnchor() {
        Vector3f shoulder = new Vector3f(.071875f, 1.8609375f, 0);
        Matrix4f scaled = YsmComponentRenderer.armBasis(false, shoulder, .65f);
        assertPoint(new Vector3f(-5 / 16f, 2 / 16f, 0), scaled.transformPosition(new Vector3f(shoulder)));
        assertPoint(new Vector3f(-5 / 16f, 2 / 16f + .65f, 0), scaled.transformPosition(new Vector3f(shoulder).add(0, -1, 0)));
    }
    @Test void legacyBoatRulesNeverReplaceChestOrUnrelatedEntitiesByNameSuffix() {
        assertTrue(YsmComponentRenderer.legacyBoatMatch(List.of("#minecraft:boat"), true, false));
        assertFalse(YsmComponentRenderer.legacyBoatMatch(List.of("#minecraft:boat"), true, true));
        assertTrue(YsmComponentRenderer.legacyBoatMatch(List.of("minecraft:chest_boat"), true, true));
        assertFalse(YsmComponentRenderer.legacyBoatMatch(List.of("minecraft:chest_boat"), true, false));
        assertFalse(YsmComponentRenderer.legacyBoatMatch(List.of("other:chest_boat", "#other:boat"), true, true));
        assertFalse(YsmComponentRenderer.legacyBoatMatch(List.of("#minecraft:boat"), false, false));
    }
    @Test void vehiclesAndProjectilesUseTheirNativeEnvironmentPredicates() {
        assertEquals("fly", YsmComponentRenderer.environment(false, false, true, false, false));
        assertEquals("ground", YsmComponentRenderer.environment(false, false, true, true, false));
        assertEquals("water", YsmComponentRenderer.environment(false, true, true, true, false));
        assertEquals("air", YsmComponentRenderer.environment(true, false, false, true, false), "Arrow onGround is not its embedded state");
        assertEquals("ground", YsmComponentRenderer.environment(true, false, false, false, true));
        assertEquals("fire", YsmComponentRenderer.environment(true, false, true, false, true));
        assertEquals("water", YsmComponentRenderer.environment(true, true, true, false, true));
    }
    @Test void authoredProjectileAndVehicleFrontsFollowTheActualEntityOrientation() {
        assertPoint(new Vector3f(0, 0, 1), YsmComponentRenderer.componentBasis(true, 0, 0).transformDirection(new Vector3f(1, 0, 0)));
        assertPoint(new Vector3f(1, 0, 0), YsmComponentRenderer.componentBasis(true, 90, 0).transformDirection(new Vector3f(1, 0, 0)));
        assertPoint(new Vector3f(0, .5f, (float) Math.sqrt(.75)), YsmComponentRenderer.componentBasis(true, 0, 30).transformDirection(new Vector3f(1, 0, 0)));
        assertPoint(new Vector3f(0, 0, 1), YsmComponentRenderer.componentBasis(false, 0, 0).transformDirection(new Vector3f(0, 0, -1)));
    }
}
