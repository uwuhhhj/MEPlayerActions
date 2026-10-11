package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.model.BbModel;
import com.simmc.meplayeractions.client.model.BuiltinYsmModels;
import com.simmc.meplayeractions.client.model.YsmFolderModel;
import com.simmc.meplayeractions.client.model.YsmModelProfile;

import com.google.gson.JsonObject;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeGuiPreviewCameraTest
{
    private static final PreviewMesh.Settings SETTINGS = new PreviewMesh.Settings(true, false, "", "");

    @Test
    void nativeItemParentExactlyMatchesEveryProjectedBodyVertex()
    {
        JsonObject properties = new JsonObject();
        properties.addProperty("height_scale", .6f);
        properties.addProperty("width_scale", .8f);
        NativeGuiPreviewCamera.Camera camera = NativeGuiPreviewCamera.owner(1.8f, 1, 37, -21)
                .withModelScale(properties);
        for (Vector3f point : List.of(new Vector3f(0, 0, 0), new Vector3f(.4f, 1.7f, -.2f),
                new Vector3f(-.3f, .9f, .5f)))
        {
            NativeGuiPreviewCamera.Position projected = camera.project(point.x, point.y, point.z, 10, 20, 125, 171);
            Vector3f attached = camera.projectedBodyMatrix().transformPosition(new Vector3f(point));
            assertEquals(projected.x() - (10 + 125 * .5f), attached.x, .0001);
            assertEquals(projected.y() - (20 + 171 * .5f), attached.y, .0001);
            assertEquals(projected.depth() * camera.pixelsPerBlock(), attached.z, .0001);
        }
    }

    @Test
    void nativeConvertedProfilesUseFixedCameraWhileStandaloneBbModelsKeepTheirBoundsFit() throws Exception
    {
        for (String id : List.of(YsmFolderModel.DEFAULT_ID, BuiltinYsmModels.TAISHO_MAID_ID,
                BuiltinYsmModels.NEW_YEAR_ID))
        {
            YsmFolderModel.Imported imported = YsmFolderModel.bundledWithProfile(id, null);
            assertTrue(NativeGuiPreviewCamera.appliesTo(imported.profile()), id);
            assertTrue(BbModel.parse(imported.raw()).ysmControllers(), "The profile belongs to converted YSM geometry");
        }
        assertFalse(NativeGuiPreviewCamera.appliesTo(YsmModelProfile.empty()));
        assertFalse(NativeGuiPreviewCamera.appliesTo(null));
        // Ordinary off-origin BB geometry retains its existing fit/centering behavior.
        List<PreviewMesh.Quad> fitted = PreviewMesh.project(face(10, 30, 1, 2, 8, 0), 20, 40, 240, 300, 0, 0);
        List<PreviewMesh.Point> points = fitted.getFirst().points();
        assertEquals(107, points.get(1).x(), .001);
        assertEquals(322, points.get(1).y(), .001);
        assertEquals(300 * .88, points.get(1).y() - points.get(0).y(), .001);
    }

    @Test
    void ownerCameraUsesNormalizedNativeHeightAndTheInventorySourceAnchor()
    {
        NativeGuiPreviewCamera.Camera camera = NativeGuiPreviewCamera.owner(3.6f, 2, 0, 0);
        assertEquals(70, camera.displaySize());
        assertEquals(70, camera.pixelsPerBlock());
        assertEquals(.9625, camera.translationY(), .00001);
        assertFalse(camera.rotationDisabled());
        NativeGuiPreviewCamera.Position foot = camera.project(0, 0, 0, 5, 29, 125, 171);
        NativeGuiPreviewCamera.Position head = camera.project(0, 1.8f, 0, 5, 29, 125, 171);
        assertEquals(67.5, foot.x(), .00001);
        assertEquals(181.175, foot.y(), .0001);
        assertEquals(126, foot.y() - head.y(), .0001);
        NativeGuiPreviewCamera.Position largerViewport = camera.project(0, 1.8f, 0, 5, 29, 250, 342);
        assertEquals(125 * .5, largerViewport.x() - head.x(), .0001);
        assertEquals(171 * .5, largerViewport.y() - head.y(), .0001,
                "A larger viewport moves only its center; it cannot invent a new zoom");
    }

    @Test
    void dummyCardUsesTheSourceScaleOffsetAndAuthoredFixedViewFlag()
    {
        NativeGuiPreviewCamera.Camera fixed = NativeGuiPreviewCamera.card(1.8f, 1, true);
        assertEquals(30, fixed.displaySize());
        assertEquals(30, fixed.pixelsPerBlock());
        assertEquals(1.8f / 2 + 5.5f / 30, fixed.translationY());
        assertTrue(fixed.rotationDisabled());
        assertEquals(0, fixed.yaw());
        assertEquals(0, fixed.pitch());
        NativeGuiPreviewCamera.Position foot = fixed.project(0, 0, 0, 0, 0, 52, 76);
        assertEquals(26, foot.x());
        assertEquals(70.2, foot.y(), .0001);
        NativeGuiPreviewCamera.Camera scaledEntity = NativeGuiPreviewCamera.card(3.6f, 2, true);
        assertEquals(15, scaledEntity.pixelsPerBlock());
        assertEquals(70.35, scaledEntity.project(0, 0, 0, 0, 0, 52, 76).y(), .0001);
        NativeGuiPreviewCamera.Camera rotated = NativeGuiPreviewCamera.card(1.8f, 1, false);
        assertFalse(rotated.rotationDisabled());
        assertEquals(-20, rotated.yaw());
        assertEquals(-10, rotated.pitch());
        assertEquals(.9, rotated.translationY(), .00001);
        assertNotEquals(fixed.project(1, 1, 1, 0, 0, 52, 76), rotated.project(1, 1, 1, 0, 0, 52, 76));
    }

    @Test
    void aLargeAuthoredStageCannotShrinkOrRecenterThePreviewedActor() throws Exception
    {
        YsmFolderModel.Imported imported = YsmFolderModel.bundledWithProfile(BuiltinYsmModels.TAISHO_MAID_ID, null);
        BbModel model = BbModel.parse(imported.raw());
        List<BbModel.Vertex> authoredStage = model.sample(0,
                List.of(new BbModel.Layer("manual", "gui", 0, 1, "LOOP", 0, 0)));
        assertTrue(authoredStage.stream().anyMatch(vertex -> Math.abs(vertex.y()) > 4),
                "The real WineFox stage extends beyond a native standing player");
        List<BbModel.Vertex> actor = face(0, 0, -20, .6f, 1.8f, 99);
        ArrayList<BbModel.Vertex> combined = new ArrayList<>(actor);
        combined.addAll(authoredStage);
        NativeGuiPreviewCamera.Camera camera = NativeGuiPreviewCamera.card(1.8f, 1, true);
        PreviewMesh.Quad alone = PreviewMesh.project(actor, 0, 0, 52, 76, SETTINGS, camera).getFirst();
        PreviewMesh.Quad withStage = PreviewMesh.project(combined, 0, 0, 52, 76, SETTINGS, camera).stream()
                .filter(quad -> quad.texture() == 99).findFirst().orElseThrow();
        assertEquals(alone, withStage, "Real authored stage geometry cannot change the actor's camera or anchor");
        assertEquals(54, alone.points().get(1).y() - alone.points().get(0).y(), .0001);
        assertEquals(authoredStage.size() / 4 + 1, PreviewMesh.project(combined, 0, 0, 52, 76, SETTINGS, camera).size(),
                "Off-screen author geometry is preserved for viewport clipping, not deleted to improve framing");
    }

    @Test
    void cardDoesNotInheritOwnerPoseOrScale()
    {
        NativeGuiPreviewCamera.Camera standing = NativeGuiPreviewCamera.select(true, 1.8f, 1, 0, 0, true);
        NativeGuiPreviewCamera.Camera swimmingScaledOwner = NativeGuiPreviewCamera.select(true, .6f, 2, 90, -35, true);
        assertEquals(standing, swimmingScaledOwner,
                "A GUI DummyPlayer remains standing with default native attributes during owner swimming/scaling");
        assertEquals(1.8f, swimmingScaledOwner.nativeHeight());
        assertEquals(1, swimmingScaledOwner.entityScale());
        assertEquals(NativeGuiPreviewCamera.owner(.6f, 2, 90, -35),
                NativeGuiPreviewCamera.select(false, .6f, 2, 90, -35, true),
                "The inventory owner camera must retain the actual owner's native height and scale");
    }

    @Test
    void nativeRotationPreservesUvDepthOrderingAndRejectsInvalidEntityDimensions()
    {
        List<BbModel.Vertex> faces = new ArrayList<>(face(0, 0, -2, 1, 1, 3));
        faces.addAll(face(0, 0, 2, 1, 1, 7));
        List<PreviewMesh.Quad> front = PreviewMesh.project(faces, 0, 0, 125, 171, SETTINGS,
                NativeGuiPreviewCamera.owner(1.8f, 1, 0, 0));
        List<PreviewMesh.Quad> rear = PreviewMesh.project(faces, 0, 0, 125, 171, SETTINGS,
                NativeGuiPreviewCamera.owner(1.8f, 1, 180, 0));
        assertEquals(List.of(7, 3), front.stream().map(PreviewMesh.Quad::texture).toList());
        assertEquals(List.of(3, 7), rear.stream().map(PreviewMesh.Quad::texture).toList());
        for (int corner = 0; corner < 4; corner++)
        {
            assertEquals(faces.get(corner).u(), rear.getFirst().points().get(corner).u());
            assertEquals(faces.get(corner).v(), rear.getFirst().points().get(corner).v());
        }
        assertTrue(PreviewMesh
                .project(faces.subList(0, 3), 0, 0, 125, 171, SETTINGS, NativeGuiPreviewCamera.owner(1.8f, 1, 0, 0))
                .isEmpty());
        assertThrows(IllegalArgumentException.class, () -> NativeGuiPreviewCamera.owner(Float.NaN, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> NativeGuiPreviewCamera.owner(1.8f, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> NativeGuiPreviewCamera.card(1.8f, Float.POSITIVE_INFINITY, false));
    }

    @Test
    void nativeAuthorScaleDefaultsToPointSevenWithoutChangingCameraAnchors() throws Exception
    {
        NativeGuiPreviewCamera.Camera nativeCamera = NativeGuiPreviewCamera.owner(1.8f, 1, 0, 0);
        NativeGuiPreviewCamera.Camera defaults = nativeCamera.withModelScale(new JsonObject());
        JsonObject explicit = new JsonObject();
        explicit.addProperty("height_scale", .7f);
        explicit.addProperty("width_scale", .7f);
        assertEquals(defaults, nativeCamera.withModelScale(explicit));
        assertEquals(.7f, defaults.modelScale().x());
        assertEquals(.7f, defaults.modelScale().y());
        assertEquals(.7f, defaults.modelScale().z());
        assertEquals(nativeCamera.displaySize(), defaults.displaySize());
        assertEquals(nativeCamera.pixelsPerBlock(), defaults.pixelsPerBlock());
        assertEquals(nativeCamera.nativeHeight(), defaults.nativeHeight());
        assertEquals(nativeCamera.entityScale(), defaults.entityScale());
        assertEquals(nativeCamera.translationY(), defaults.translationY());
        assertEquals(nativeCamera.project(0, 0, 0, 0, 0, 145, 137), defaults.project(0, 0, 0, 0, 0, 145, 137),
                "Native foot anchor does not shrink with model geometry");
        // The default author's hair reaches 43.3 model pixels, rather than the player's 1.8-block native bounds.
        assertTrue(nativeCamera.project(0, 43.3f / 16, 0, 0, 0, 145, 137).y() < 0);
        assertEquals(2.56875, defaults.project(0, 43.3f / 16, 0, 0, 0, 145, 137).y(), .0001,
                "The original .7 author scale restores the head inside the existing small viewport");
        for (String id : List.of(YsmFolderModel.DEFAULT_ID, BuiltinYsmModels.TAISHO_MAID_ID,
                BuiltinYsmModels.NEW_YEAR_ID, BuiltinYsmModels.ASTRONAUT_ID))
        {
            YsmModelProfile profile = YsmFolderModel.bundledWithProfile(id, null).profile();
            assertEquals(defaults.modelScale(), nativeCamera.withModelScale(profile.properties()).modelScale(), id);
        }
    }

    @Test
    void nativeAuthorScaleUsesUpstreamNonUniformAxesForOwnerAndCard()
    {
        JsonObject properties = new JsonObject();
        properties.addProperty("height_scale", 2);
        properties.addProperty("width_scale", 3);
        for (NativeGuiPreviewCamera.Camera unscaled : List.of(NativeGuiPreviewCamera.owner(1.8f, 1, 0, 0),
                NativeGuiPreviewCamera.card(1.8f, 1, true)))
        {
            NativeGuiPreviewCamera.Camera camera = unscaled.withModelScale(properties);
            NativeGuiPreviewCamera.Position origin = camera.project(0, 0, 0, 10, 20, 125, 171);
            NativeGuiPreviewCamera.Position corner = camera.project(1, 1, 1, 10, 20, 125, 171);
            assertEquals(2 * camera.pixelsPerBlock(), origin.x() - corner.x(), .0001,
                    "IGeoRenderer uses height_scale for X");
            assertEquals(3 * camera.pixelsPerBlock(), origin.y() - corner.y(), .0001,
                    "IGeoRenderer uses width_scale for Y");
            assertEquals(2, corner.depth() - origin.depth(), .0001, "IGeoRenderer uses height_scale for Z");
            NativeGuiPreviewCamera.Position normal = camera.rotateNormal(1, 1, 1);
            assertEquals(.5, normal.x(), .0001);
            assertEquals(1.0 / 3, normal.y(), .0001);
            assertEquals(.5, normal.depth(), .0001, "Lighting uses the scale's inverse transpose");
            List<PreviewMesh.Quad> mesh = PreviewMesh.project(face(0, 0, 1, 1, 1, 3), 10, 20, 125, 171, SETTINGS,
                    camera);
            List<PreviewMesh.Quad> decorated = PreviewMesh.compose(mesh, 10, 20, 125, 171, true, true);
            assertEquals(125, decorated.getFirst().points().get(3).x() - decorated.getFirst().points().get(0).x());
            assertEquals(171, decorated.getLast().points().get(1).y() - decorated.getLast().points().get(0).y(),
                    "Author geometry scale must not shrink BG/FG images");
        }
    }

    @Test
    void nativeAuthorScalePrecedesTheGeckoOffsetAndPreservesPerVertexDepth()
    {
        JsonObject properties = new JsonObject();
        properties.addProperty("height_scale", .5);
        properties.addProperty("width_scale", 2);
        NativeGuiPreviewCamera.Camera nativeCamera = NativeGuiPreviewCamera.owner(1.8f, 1, 25, -30);
        NativeGuiPreviewCamera.Camera camera = nativeCamera.withModelScale(properties);
        NativeGuiPreviewCamera.Position expected = nativeCamera.rotate(.5f, 2 + .01f, 1);
        NativeGuiPreviewCamera.Position projected = camera.project(1, 1, 2, 10, 20, 125, 171);
        assertEquals(10 + 62.5 - 70 * expected.x(), projected.x(), .0001);
        assertEquals(20 + 85.5 + 70 * (camera.translationY() - expected.y()), projected.y(), .0001);
        assertEquals(expected.depth(), projected.depth(), .0001, "TranslateY .01 remains outside the author S matrix");
        assertEquals(nativeCamera.project(0, 0, 0, 10, 20, 125, 171), camera.project(0, 0, 0, 10, 20, 125, 171));
        List<BbModel.Vertex> vertices = face(0, 0, 2, 1, 1, 7);
        PreviewMesh.Quad quad = PreviewMesh.project(vertices, 10, 20, 125, 171, SETTINGS, camera).getFirst();
        for (int i = 0; i < 4; i++)
        {
            BbModel.Vertex vertex = vertices.get(i);
            PreviewMesh.Point point = quad.points().get(i);
            NativeGuiPreviewCamera.Position actual = camera.project(vertex.x(), vertex.y(), vertex.z(), 10, 20, 125,
                    171);
            assertEquals(actual.depth(), point.depth(), .0001);
            assertEquals(vertex.u(), point.u());
            assertEquals(vertex.v(), point.v());
        }
        assertNotEquals(quad.points().get(0).depth(), quad.points().get(1).depth(),
                "Slanted polygons retain each corner depth for actual depth testing, rather than only a face average");
        assertEquals(quad.points().stream().mapToDouble(PreviewMesh.Point::depth).average().orElseThrow(), quad.depth(),
                .0001);
        assertEquals(0, new PreviewMesh.Point(1, 2, 3, 4).depth(),
                "The plain BB four-argument point constructor stays compatible");
    }

    private static List<BbModel.Vertex> face(float x, float y, float z, float width, float height, int texture)
    {
        return List.of(new BbModel.Vertex(x + width, y + height, z, 0, 0, 0, 0, -1, texture),
                new BbModel.Vertex(x + width, y, z, 0, 1, 0, 0, -1, texture),
                new BbModel.Vertex(x, y, z, 1, 1, 0, 0, -1, texture),
                new BbModel.Vertex(x, y + height, z, 1, 0, 0, 0, -1, texture));
    }
}
