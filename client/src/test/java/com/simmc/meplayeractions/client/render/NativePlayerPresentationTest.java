package com.simmc.meplayeractions.client.render;

import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.EntityPose;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NativePlayerPresentationTest {
    private static final Unsafe FIXTURE_ALLOCATOR = fixtureAllocator();

    private static Unsafe fixtureAllocator() {
        try {
            var field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot allocate native render-state fixtures", error);
        }
    }

    private static PlayerEntityRenderState nativeState() {
        try {
            // These tests exercise real vanilla state fields, not Minecraft's item registry.
            // The normal constructor initializes ItemStack.EMPTY, which needs Fabric's remap
            // access transformations unavailable on the plain JUnit classpath. No production
            // object is allocated this way; every field consumed here is explicitly set below.
            PlayerEntityRenderState state = (PlayerEntityRenderState) FIXTURE_ALLOCATOR.allocateInstance(PlayerEntityRenderState.class);
            state.pose = EntityPose.STANDING;
            return state;
        } catch (InstantiationException error) {
            throw new AssertionError("Cannot allocate native render-state fixture", error);
        }
    }

    @AfterEach void releaseFrame() { NativePlayerPresentation.beginFrame(List.of()); }

    @Test void sharedBodyUsesTheActualNativeFrameIncludingRendererOnlyOffsets() {
        UUID owner = UUID.randomUUID();
        PlayerEntityRenderState state = nativeState();
        state.x = 25.125;
        state.y = 64.375;
        state.z = -9.5;
        state.positionOffset = new Vec3d(0, 0, .25);
        state.bodyYaw = 145;
        state.relativeHeadYaw = -20;
        state.pitch = -35;
        state.pose = EntityPose.STANDING;
        // PlayerRenderer adds the crouching correction beyond state.positionOffset.
        Vec3d rendererOffset = new Vec3d(0, -.125, .25);
        var snapshot = NativePlayerPresentation.store(owner, state, .625f, rendererOffset, new Matrix4f(), true);
        assertEquals(25.125, snapshot.x());
        assertEquals(64.25, snapshot.y());
        assertEquals(-9.25, snapshot.z());
        assertEquals(new Vec3d(25.125, 64.375, -9.5), snapshot.entityPosition());
        assertEquals(.625f, snapshot.tickDelta());
        assertEquals(145, snapshot.bodyYaw());
        assertEquals(125, snapshot.headYaw());
        assertEquals(-35, snapshot.headPitch());
        assertTrue(NativePlayerPresentation.captured(owner));
        assertSame(snapshot, NativePlayerPresentation.get(owner));
        assertEquals(64.375, state.y, "Replacing a model must not rewrite vanilla's player position");
        assertEquals(new Vec3d(0, 0, .25), state.positionOffset);
    }

    @Test void laterVanillaStateAndMatrixChangesCannotMoveAnAlreadyExtractedModel() {
        UUID owner = UUID.randomUUID();
        PlayerEntityRenderState state = nativeState();
        state.y = 72;
        state.bodyYaw = 90;
        state.pose = EntityPose.STANDING;
        Matrix4f parent = new Matrix4f().translation(0, .25f, 0);
        var snapshot = NativePlayerPresentation.store(owner, state, .375f, Vec3d.ZERO, parent, true);
        state.y = 98;
        state.bodyYaw = -90;
        parent.translation(0, 20, 0);
        snapshot.parent().translation(0, -20, 0);
        assertEquals(72, snapshot.y());
        assertEquals(90, snapshot.bodyYaw());
        assertEquals(.375f, snapshot.tickDelta());
        assertEquals(.25f, snapshot.parent().transformPosition(new Vector3f()).y);
    }

    @Test void upsideDownBodyDoesNotReverseAuthorHeadQueriesTwice() {
        PlayerEntityRenderState state = nativeState();
        state.bodyYaw = 45;
        state.relativeHeadYaw = -20;
        state.pitch = 60;
        state.flipUpsideDown = true;
        var snapshot = NativePlayerPresentation.store(UUID.randomUUID(), state, .5f, Vec3d.ZERO, new Matrix4f(), true);
        assertEquals(65, snapshot.headYaw());
        assertEquals(-60, snapshot.headPitch());
        assertEquals(-20, state.relativeHeadYaw);
        assertEquals(60, state.pitch);
        assertTrue(state.flipUpsideDown);
    }

    @Test void newFrameNeverReusesACulledOrUntrackedRemoteBody() {
        UUID remote = UUID.randomUUID();
        PlayerEntityRenderState state = nativeState();
        state.y = 80;
        NativePlayerPresentation.store(remote, state, .5f, Vec3d.ZERO, new Matrix4f(), true);
        NativePlayerPresentation.beginFrame(List.of());
        assertNull(NativePlayerPresentation.get(remote));
        assertFalse(NativePlayerPresentation.captured(remote));
        UUID self = UUID.randomUUID();
        NativePlayerPresentation.store(self, state, .75f, Vec3d.ZERO, new Matrix4f(), false);
        assertNotNull(NativePlayerPresentation.get(self));
        assertFalse(NativePlayerPresentation.captured(self), "A first-person fallback is not a visible native world body");
    }

    @Test void vehicleAndProjectileFramesUseNativeOriginsOffsetsFrozenDeltaAndVisibility() {
        UUID entity = UUID.randomUUID();
        EntityRenderState state = new EntityRenderState();
        state.x = 18.25;
        state.y = 67.75;
        state.z = -4.5;
        state.age = 123.625f;
        state.light = 0xA000A0;
        state.invisible = true;
        state.outlineColor = 0xFF123456;
        Vec3d rendererOffset = new Vec3d(.125, -.25, .5);
        var frame = NativePlayerPresentation.storeEntity(entity, state, .625f, rendererOffset);
        assertEquals(new Vec3d(18.25, 67.75, -4.5), frame.entityPosition());
        assertEquals(new Vec3d(18.375, 67.5, -4), frame.presentationPosition());
        assertEquals(.625f, frame.tickDelta());
        assertEquals(123.625f, frame.age());
        assertEquals(0xA000A0, frame.light());
        assertTrue(frame.invisible());
        assertEquals(0xFF123456, frame.outlineColor());
        assertSame(state, frame.state(), "Accessories read the exact native queue snapshot without extracting it again");
        assertSame(frame, NativePlayerPresentation.entityFrame(entity));
        state.x = 999;
        state.age = 999;
        state.light = 0;
        state.invisible = false;
        state.outlineColor = 0;
        assertEquals(18.375, frame.x());
        assertEquals(18.25, frame.nativeX());
        assertEquals(123.625f, frame.age());
        assertEquals(0xA000A0, frame.light());
        assertTrue(frame.invisible());
        assertEquals(0xFF123456, frame.outlineColor());
    }

    @Test void worldFrameCameraAndAllEntitySnapshotsAreClearedTogether() {
        Vec3d camera = new Vec3d(30.5, 80.125, -40.75);
        NativePlayerPresentation.beginFrame(List.of(), camera);
        UUID entity = UUID.randomUUID();
        NativePlayerPresentation.storeEntity(entity, new EntityRenderState(), 1, Vec3d.ZERO);
        assertEquals(camera, NativePlayerPresentation.cameraPosition());
        assertNotNull(NativePlayerPresentation.entityFrame(entity));
        NativePlayerPresentation.beginFrame(List.of());
        assertNull(NativePlayerPresentation.entityFrame(entity));
        assertEquals(Vec3d.ZERO, NativePlayerPresentation.cameraPosition());
    }

    @Test void sleepingAndUpsideDownParentStateIsCopiedWithoutDisablingVanillaAnimations() {
        PlayerEntityRenderState actual = nativeState();
        actual.age = 123.75f;
        actual.height = 1.8f;
        actual.standingEyeHeight = 1.62f;
        actual.bodyYaw = 35;
        actual.relativeHeadYaw = 12;
        actual.pitch = -28;
        actual.pose = EntityPose.SLEEPING;
        actual.sleepingDirection = Direction.EAST;
        actual.shaking = true;
        actual.flipUpsideDown = true;
        actual.deathTime = 4;
        actual.usingRiptide = true;
        PlayerEntityRenderState modelParent = nativeState();
        modelParent.deathTime = 99;
        modelParent.usingRiptide = true;
        YsmBodyTransform.copyForModel(actual, modelParent);
        assertNotSame(actual, modelParent);
        assertEquals(123.75f, modelParent.age);
        assertEquals(1.8f, modelParent.height);
        assertEquals(1.62f, modelParent.standingEyeHeight);
        assertEquals(35, modelParent.bodyYaw);
        assertEquals(12, modelParent.relativeHeadYaw);
        assertEquals(-28, modelParent.pitch);
        assertEquals(EntityPose.SLEEPING, modelParent.pose);
        assertEquals(Direction.EAST, modelParent.sleepingDirection);
        assertTrue(modelParent.shaking);
        assertTrue(modelParent.flipUpsideDown);
        assertEquals(0, modelParent.deathTime);
        assertFalse(modelParent.usingRiptide);
        assertEquals(4, actual.deathTime);
        assertTrue(actual.usingRiptide);
        modelParent.standingEyeHeight = 0;
        assertEquals(1.62f, actual.standingEyeHeight);
    }
}
