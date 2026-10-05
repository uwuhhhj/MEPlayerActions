package com.simmc.meplayeractions.client.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.Map;

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
    @Test void extraAuthorLocatorsAreIndependentAndHiddenLocatorsCannotAttachItems() {
        var bones = Map.of("RightHandLocator", new Matrix4f(), "RightHand", new Matrix4f(),
                "RightHandLocator2", new Matrix4f().translation(1, 2, 3),
                "RightHandLocator3", new Matrix4f().scale(0), "RightHandLocator8", new Matrix4f(),
                "RightHandLocator9", new Matrix4f(), "LeftHandLocator2", new Matrix4f());
        assertEquals(List.of("RightHandLocator", "RightHandLocator2", "RightHandLocator8"),
                YsmItemRenderer.attachmentBones(bones, "Right"));
        assertEquals(List.of("LeftHandLocator2"), YsmItemRenderer.attachmentBones(bones, "Left"));
        assertTrue(YsmItemRenderer.attachmentBones(Map.of("RightHand", new Matrix4f()), "Right", true).isEmpty(),
                "A declared but author-hidden locator must not fall back to its visible parent hand");
    }
    @Test void nativeHandEntryPreservesTheFullAuthoredCoordinatesLikeOpenYsm() {
        Vector3f shoulder = new Vector3f(.071875f, 1.8609375f, 0);
        Matrix4f right = YsmComponentRenderer.armBasis(false);
        assertPoint(new Vector3f(-.25f - shoulder.x, 1.8f - shoulder.y, 0), right.transformPosition(new Vector3f(shoulder)));
        assertPoint(new Vector3f(-.25f - shoulder.x - .1f, 1.8f - shoulder.y + 1, .2f),
                right.transformPosition(new Vector3f(shoulder).add(.1f, -1, .2f)));
        assertPoint(new Vector3f(.25f - shoulder.x, 1.8f - shoulder.y, 0), YsmComponentRenderer.armBasis(true).transformPosition(new Vector3f(shoulder)));
    }
    @Test void registryMatchesUseExactIdsAndActualTagMembership() {
        assertTrue(YsmComponentRenderer.matches(List.of("minecraft:arrow"), "minecraft:arrow", tag -> false));
        assertFalse(YsmComponentRenderer.matches(List.of("minecraft:arrow"), "other:arrow", tag -> false));
        assertTrue(YsmComponentRenderer.matches(List.of("#minecraft:boats"), "minecraft:oak_boat", Set.of("minecraft:boats")::contains));
        assertFalse(YsmComponentRenderer.matches(List.of("#minecraft:boats"), "minecraft:oak_boat", tag -> false));
        assertFalse(YsmComponentRenderer.matches(List.of(), "minecraft:arrow", tag -> true));
    }
    @Test void leftAndRightNativeHandEntriesUseOnlyTheUpstreamTranslationAndAxisReflection() {
        Matrix4f right = YsmComponentRenderer.armBasis(false);
        Matrix4f left = YsmComponentRenderer.armBasis(true);
        assertPoint(new Vector3f(-.25f, 1.8f, 0), right.transformPosition(new Vector3f()));
        assertPoint(new Vector3f(.25f, 1.8f, 0), left.transformPosition(new Vector3f()));
        assertPoint(new Vector3f(-1, -1, 1), right.transformDirection(new Vector3f(1, 1, 1)));
        assertPoint(new Vector3f(-1, -1, 1), left.transformDirection(new Vector3f(1, 1, 1)));
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
    @Test void passengerLocatorMovesTheRiderInVehicleSpaceWithoutOverwritingItsBodyYaw() {
        Matrix4f locator = new Matrix4f().translation(1, 2, 3);
        Matrix4f source = new Matrix4f(locator);
        Matrix4f north = YsmComponentRenderer.passengerTransform(0, locator, -1);
        Matrix4f east = YsmComponentRenderer.passengerTransform(90, locator, -1);
        assertPoint(new Vector3f(-1, 1, -3), north.transformPosition(new Vector3f()));
        assertPoint(new Vector3f(3, 1, -1), east.transformPosition(new Vector3f()));
        assertPoint(new Vector3f(1, 0, 0), east.transformDirection(new Vector3f(1, 0, 0)));
        assertEquals(source, locator);
        Matrix4f authorRotated = new Matrix4f().translation(1, 2, 3).rotateZ((float) (Math.PI / 2));
        Matrix4f rotated = YsmComponentRenderer.passengerTransform(180, authorRotated, -.5);
        assertPoint(new Vector3f(1.5f, 2, 3), rotated.transformPosition(new Vector3f()));
        assertPoint(new Vector3f(0, 1, 0), rotated.transformDirection(new Vector3f(1, 0, 0)));
    }
}
