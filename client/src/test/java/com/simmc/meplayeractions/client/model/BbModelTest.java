package com.simmc.meplayeractions.client.model;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BbModelTest {
    private static final float EPS = 1e-5f;

    @Test void nativeHandSlotsRemainVisibleAboveTheLegacyPostureAndBelowManualActions() {
        JsonObject json=fixture();
        addAnimation(json,"idle",1,constantFrame("position",16,0,0));
        addAnimation(json,"hand",1,constantFrame("position",32,0,0));
        addAnimation(json,"manual",1,constantFrame("position",64,0,0));
        BbModel model=parse(json);
        for(String slot:List.of("player.hold_mainhand","player.hold_offhand","player.swing","player.use")) {
            var layers=List.of(layer(slot,"hand","HOLD",0,0),layer("posture","idle","HOLD",0,0));
            assertEquals(4,model.sample(0,layers).getFirst().x(),EPS,slot);
            var player=new AnimationPlayer(model);
            assertEquals(4,player.sample(0,layers).getFirst().x(),EPS,slot);
            assertEquals(5,model.sample(0,List.of(layers.get(0),layers.get(1),layer("manual","manual","HOLD",0,0))).getFirst().x(),EPS);
        }
    }

    @Test void shippedModelsParseAndEveryAnimationProducesFiniteUnitNormals() throws Exception {
        for (String name : List.of("ysm_01_jk", "ysm_02_jk")) {
            Path file = Path.of("../examples/models/" + name + ".bbmodel");
            BbModel model = BbModel.parse(Files.readAllBytes(file));
            assertEquals(name.equals("ysm_01_jk") ? 232 : 335, model.cubeCount());
            assertEquals(1, model.textures().size());
            assertTrue(model.animations().containsAll(Set.of("crawl_idle", "crawl_walk", "player_jump", "bed_sleep", "idle", "sit", "sleep")));
            for (String animation : model.animations()) {
                List<BbModel.Vertex> vertices = model.sample(3.25, List.of(layer("posture", animation, "LOOP", 0, 0)));
                assertFalse(vertices.isEmpty(), animation);
                assertEquals(0, vertices.size() % 4);
                for (BbModel.Vertex vertex : vertices) {
                    assertTrue(Float.isFinite(vertex.x()) && Float.isFinite(vertex.y()) && Float.isFinite(vertex.z()));
                    assertEquals(1, Math.sqrt(vertex.nx() * vertex.nx() + vertex.ny() * vertex.ny() + vertex.nz() * vertex.nz()), 1e-4);
                    assertEquals(0, vertex.texture());
                }
            }
            List<BbModel.Vertex> upright = model.sample(5, List.of(layer("posture", "idle", "LOOP", 0, 0)));
            List<BbModel.Vertex> crawl = model.sample(5, List.of(layer("posture", "crawl_idle", "LOOP", 0, 0)));
            assertTrue(height(crawl) < height(upright) * .75, "The authored crawl root must already make the model horizontal");
        }
    }

    @Test void cubeFacesHaveCorrectWindingAndUvs() {
        JsonObject json = fixture();
        JsonObject faces = json.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces");
        for (String side : List.of("east", "south", "west", "up", "down"))
            faces.add(side, faces.get("north").deepCopy());
        List<BbModel.Vertex> vertices = parse(json).sample(0, List.of());
        assertEquals(24, vertices.size());
        for (int f = 0; f < vertices.size(); f += 4) {
            BbModel.Vertex a = vertices.get(f), b = vertices.get(f + 1), c = vertices.get(f + 2);
            Vector3f geometric = new Vector3f(b.x() - a.x(), b.y() - a.y(), b.z() - a.z())
                    .cross(new Vector3f(c.x() - a.x(), c.y() - a.y(), c.z() - a.z())).normalize();
            assertEquals(a.nx(), geometric.x, EPS); assertEquals(a.ny(), geometric.y, EPS); assertEquals(a.nz(), geometric.z, EPS);
        }
        assertEquals(1, vertices.getFirst().x(), EPS);
        assertEquals(1, vertices.getFirst().y(), EPS);
        assertEquals(0, vertices.getFirst().u(), EPS);
        assertEquals(0, vertices.getFirst().v(), EPS);
        assertEquals(1, vertices.get(2).u(), EPS);
        assertEquals(1, vertices.get(2).v(), EPS);
    }

    @Test void textureUvResolutionIsIndependentOfPngPixelDimensionsAndRotationWorks() {
        JsonObject json = fixture();
        json.getAsJsonArray("textures").get(0).getAsJsonObject().addProperty("uv_width", 32);
        json.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces")
                .getAsJsonObject("north").addProperty("rotation", 90);
        List<BbModel.Vertex> vertices = parse(json).sample(0, List.of());
        assertEquals(0, vertices.get(0).u(), EPS);
        assertEquals(1, vertices.get(0).v(), EPS);
        assertEquals(.5, vertices.get(1).u(), EPS);
        assertEquals(1, vertices.get(1).v(), EPS);
    }

    @Test void cubeRotationUsesItsOwnOriginAndInflate() {
        JsonObject json = fixture();
        JsonObject cube = json.getAsJsonArray("elements").get(0).getAsJsonObject();
        cube.add("origin", vector(8, 8, 8)); cube.add("rotation", vector(0, 0, 90));
        cube.addProperty("inflate", 1);
        BbModel.Vertex vertex = parse(json).sample(0, List.of()).getFirst();
        assertEquals(-1f / 16, vertex.x(), EPS);
        assertEquals(17f / 16, vertex.y(), EPS);
        assertEquals(-1f / 16, vertex.z(), EPS);
    }

    @Test void nestedAbsolutePivotsAreConvertedRelativeToParent() {
        JsonObject json = fixture();
        JsonObject root = json.getAsJsonArray("outliner").get(0).getAsJsonObject();
        root.add("origin", vector(8, 0, 0)); root.add("rotation", vector(0, 0, 90));
        JsonObject child = new JsonObject(); child.addProperty("uuid", "child"); child.addProperty("name", "child");
        child.add("origin", vector(16, 0, 0)); child.add("children", stringArray("cube"));
        JsonArray children = new JsonArray(); children.add(child); root.add("children", children);
        JsonObject cube = json.getAsJsonArray("elements").get(0).getAsJsonObject();
        cube.add("from", vector(16, 0, 0)); cube.add("to", vector(32, 16, 16));
        BbModel.Vertex vertex = parse(json).sample(0, List.of()).getFirst();
        assertEquals(-.5, vertex.x(), EPS); assertEquals(1.5, vertex.y(), EPS); assertEquals(0, vertex.z(), EPS);
    }

    @Test void linearStepAndCatmullRomKeyframesEvaluateBetweenTicks() {
        JsonObject json = fixture();
        addAnimation(json, "linear", 1, frames("position", "linear", new double[]{0, 1}, new double[]{0, 16}));
        addAnimation(json, "step", 1, frames("position", "step", new double[]{0, 1}, new double[]{0, 16}));
        addAnimation(json, "spline", 3, frames("position", "catmullrom", new double[]{0, 1, 2, 3}, new double[]{0, 16, 0, 0}));
        BbModel model = parse(json);
        assertEquals(1.5, firstX(model, 10, layer("posture", "linear", "HOLD", 0, 0)), EPS);
        assertEquals(1, firstX(model, 10, layer("posture", "step", "HOLD", 0, 0)), EPS);
        assertEquals(2, firstX(model, 20, layer("posture", "step", "HOLD", 0, 0)), EPS);
        assertEquals(1.5625, firstX(model, 30, layer("posture", "spline", "HOLD", 0, 0)), EPS);
    }

    @Test void twoPointKeyframesPreserveIncomingAndOutgoingDiscontinuities() {
        JsonObject json = fixture();
        JsonArray frames = frames("position", "linear", new double[]{0, 1}, new double[]{0, 32});
        frames.get(0).getAsJsonObject().getAsJsonArray("data_points").add(point(16, 0, 0));
        frames.get(1).getAsJsonObject().getAsJsonArray("data_points").add(point(48, 0, 0));
        addAnimation(json, "split", 1, frames);
        BbModel model = parse(json);
        assertEquals(1, firstX(model, 0, layer("posture", "split", "HOLD", 0, 0)), EPS);
        assertEquals(2.5, firstX(model, 10, layer("posture", "split", "HOLD", 0, 0)), EPS);
        assertEquals(3, firstX(model, 20, layer("posture", "split", "HOLD", 0, 0)), EPS);
        assertEquals(4, firstX(model, 21, layer("posture", "split", "HOLD", 0, 0)), EPS);
    }

    @Test void loopOnceHoldAndEntryExitTransitionsUseServerClock() {
        JsonObject json = fixture();
        addAnimation(json, "move", 1, frames("position", "linear", new double[]{0, 1}, new double[]{0, 16}));
        BbModel model = parse(json);
        assertEquals(1.25, firstX(model, 25, layer("posture", "move", "LOOP", 0, 0)), EPS);
        assertEquals(2, firstX(model, 25, layer("posture", "move", "HOLD", 0, 0)), EPS);
        assertEquals(1.5, firstX(model, 25, layer("posture", "move", "ONCE", 0, 10)), EPS);
        assertEquals(1, firstX(model, 31, layer("posture", "move", "ONCE", 0, 10)), EPS);
        assertEquals(1, firstX(model, -1, layer("posture", "move", "HOLD", 0, 0)), EPS);
        assertEquals(1.25, firstX(model, 10, layer("posture", "move", "HOLD", 20, 0)), EPS);
    }

    @Test void layersOverrideOnlyOwnedChannelsAndPriorityDoesNotDependOnPacketOrder() {
        JsonObject json = fixture();
        addAnimation(json, "move", 1, frames("position", "linear", new double[]{0}, new double[]{8}));
        JsonArray rotation = frames("rotation", "linear", new double[]{0}, new double[]{0});
        rotation.get(0).getAsJsonObject().add("data_points", pointArray(point(0, 0, 90)));
        addAnimation(json, "turn", 1, rotation);
        BbModel model = parse(json);
        List<BbModel.Layer> layers = List.of(layer("manual", "turn", "HOLD", 0, 0), layer("posture", "move", "HOLD", 0, 0));
        BbModel.Vertex vertex = model.sample(0, layers).getFirst();
        assertEquals(-.5, vertex.x(), EPS); assertEquals(1, vertex.y(), EPS);
    }

    @Test void playbackSpeedMatchesTheServerSupportedRange() {
        JsonObject json = fixture();
        addAnimation(json, "move", 1, frames("position", "linear", new double[]{0, 1}, new double[]{0, 16}));
        BbModel model = parse(json);
        assertEquals(1.5, firstX(model, .5, new BbModel.Layer("posture", "move", 0, 20, "HOLD", 0, 0)), EPS);
        assertEquals(1.5, firstX(model, 1000, new BbModel.Layer("posture", "move", 0, .01, "HOLD", 0, 0)), EPS);
        assertThrows(IllegalArgumentException.class, () -> new BbModel.Layer("posture", "move", 0, 20.001, "HOLD", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new BbModel.Layer("posture", "move", 0, .009, "HOLD", 0, 0));
    }

    @Test void legacy410AnimationAxesMatchBlockbenchMigrationAndModelEngine() {
        JsonObject legacy = fixture(); legacy.getAsJsonObject("meta").addProperty("format_version", "4.10");
        addAnimation(legacy, "move", 1, constantFrame("position", 16, 32, 48));
        addAnimation(legacy, "turn_x", 1, constantFrame("rotation", 90, 0, 0));
        addAnimation(legacy, "turn_y", 1, constantFrame("rotation", 0, 90, 0));
        addAnimation(legacy, "turn_z", 1, constantFrame("rotation", 0, 0, 90));
        BbModel model = parse(legacy);
        BbModel.Vertex moved = model.sample(0, List.of(layer("posture", "move", "HOLD", 0, 0))).getFirst();
        assertEquals(0, moved.x(), EPS); assertEquals(3, moved.y(), EPS); assertEquals(3, moved.z(), EPS);
        BbModel.Vertex rx = model.sample(0, List.of(layer("posture", "turn_x", "HOLD", 0, 0))).getFirst();
        assertEquals(1, rx.x(), EPS); assertEquals(0, rx.y(), EPS); assertEquals(-1, rx.z(), EPS);
        BbModel.Vertex ry = model.sample(0, List.of(layer("posture", "turn_y", "HOLD", 0, 0))).getFirst();
        assertEquals(0, ry.x(), EPS); assertEquals(1, ry.y(), EPS); assertEquals(1, ry.z(), EPS);
        BbModel.Vertex rz = model.sample(0, List.of(layer("posture", "turn_z", "HOLD", 0, 0))).getFirst();
        assertEquals(-1, rz.x(), EPS); assertEquals(1, rz.y(), EPS); assertEquals(0, rz.z(), EPS);

        JsonObject modern = legacy.deepCopy(); modern.getAsJsonObject("meta").addProperty("format_version", "5.0");
        BbModel.Vertex modernMove = parse(modern).sample(0, List.of(layer("posture", "move", "HOLD", 0, 0))).getFirst();
        assertEquals(2, modernMove.x(), EPS); assertEquals(3, modernMove.y(), EPS); assertEquals(3, modernMove.z(), EPS);
        // Rest rotations are editor geometry, even in a legacy file.
        legacy.getAsJsonArray("outliner").get(0).getAsJsonObject().add("rotation", vector(90,0,0));
        assertEquals(1, parse(legacy).sample(0, List.of()).getFirst().z(), EPS);
        modern.getAsJsonObject("meta").addProperty("format_version", "5.1");
        assertThrows(IllegalArgumentException.class, () -> parse(modern));
        legacy.getAsJsonObject("meta").addProperty("format_version", "3.1");
        assertThrows(IllegalArgumentException.class, () -> parse(legacy));
        JsonObject missing = fixture(); missing.remove("meta");
        assertThrows(IllegalArgumentException.class, () -> parse(missing));
    }

    private static List<BbModel.Vertex> bodySample(BbModel model, String clip, double tick, String... names) {
        BbModel.Pose pose = model.emptyPose();
        com.simmc.meplayeractions.expression.Molang.Context context = new com.simmc.meplayeractions.expression.Molang.Context();
        context.frame(Map.of("ysm.food_level",20d,"query.life_time",tick/20));
        model.initializePhysics(context);
        for (BbModel.Layer layer : BbModel.ordered(model.withParallelLayers(List.of(layer("posture",clip,"LOOP",0,0))))) {
            var evaluated=model.evaluate(tick,layer,true,context);
            BbModel.overlay(pose,evaluated.pose(),evaluated.weight(),false);
        }
        return model.vertices(pose,names.length==0?null:Set.of(names));
    }
    private static double zCenter(List<BbModel.Vertex> vertices) {
        assertFalse(vertices.isEmpty());return vertices.stream().mapToDouble(BbModel.Vertex::z).average().orElseThrow();
    }
    @Test void shippedCrawlHeadIsAheadOfFeetAndTailStaysAboveGround() throws Exception {
        for (String name : List.of("ysm_01_jk", "ysm_02_jk")) {
            BbModel model=BbModel.parse(Files.readAllBytes(Path.of("../examples/models/"+name+".bbmodel")));
            for(String animation:List.of("climbing","climb"))for(double tick:new double[]{0,5,10,15,20}) {
                var body=bodySample(model,animation,tick,"jk","jk2","Head","LeftLeg","RightLeg");
                var tail=bodySample(model,animation,tick,"Tail1","Tail2","Tail3");
                var head=bodySample(model,animation,tick,"Head");
                var feet=bodySample(model,animation,tick,"LeftFoot","RightFoot");
                double minBody=body.stream().mapToDouble(BbModel.Vertex::y).min().orElseThrow();
                double minTail=tail.stream().mapToDouble(BbModel.Vertex::y).min().orElseThrow();
                assertTrue(minBody>-.16,name+" "+animation+" body contact: "+minBody);
                assertTrue(minTail>0,name+" "+animation+" tail must extend upward: "+minTail);
                assertTrue(zCenter(head)<zCenter(feet)-.5,name+" "+animation+" head must lead feet");
            }
        }
    }
    @Test void shippedBedPoseIsSupineAndTouchesMattressInEveryBedDirection() throws Exception {
        for(String name:List.of("ysm_01_jk","ysm_02_jk")) {
            BbModel model=BbModel.parse(Files.readAllBytes(Path.of("../examples/models/"+name+".bbmodel")));
            var bed=bodySample(model,"bed_sleep",30);
            var back=bodySample(model,"bed_sleep",30,"BackClothe");
            var head=bodySample(model,"bed_sleep",30,"Head");
            var feet=bodySample(model,"bed_sleep",30,"LeftFoot","RightFoot");
            double contact=back.stream().mapToDouble(BbModel.Vertex::y).min().orElseThrow();
            assertTrue(contact>=-.005&&contact<.04,name+" back clothing must contact mattress: "+contact);
            assertTrue(head.stream().anyMatch(v->v.ny()>.99),"Face points upward");
            double delta=zCenter(head)-zCenter(feet);
            assertTrue(delta<-1,name+" head leads toward BB -Z");
            // Original model height is intentionally retained; limbs may extend past a vanilla bed.
            double standingHeight=model.sample(0,List.of()).stream().mapToDouble(BbModel.Vertex::y).max().orElseThrow();
            double span=bed.stream().mapToDouble(BbModel.Vertex::z).max().orElseThrow()-bed.stream().mapToDouble(BbModel.Vertex::z).min().orElseThrow();
            assertTrue(span>standingHeight*.75&&span<standingHeight*1.4,name+" supine span retains original dimensions: "+span);
            for(double[] direction:new double[][]{{0,0,1},{90,-1,0},{180,0,-1},{-90,1,0}}) {
                double radians=Math.toRadians(180-direction[0]);
                assertTrue(Math.sin(radians)*delta*direction[1]+Math.cos(radians)*delta*direction[2]>1);
                var torso=bodySample(model,"bed_sleep",30,"Head","LeftLeg","RightLeg","BackClothe");
                assertTrue(torso.stream().mapToDouble(BbModel.Vertex::y).min().orElseThrow()>-.07,name+" body must stay above mattress");
            }
        }
    }

    @Test void animationPlayerMaintainsPlaybackAcrossRepeatedSnapshotsAndCrossfadesRemoval() {
        JsonObject json = fixture();
        addAnimation(json, "move", 1, frames("position", "linear", new double[]{0, 1}, new double[]{0, 16}));
        addAnimation(json, "next", 1, frames("position", "linear", new double[]{0}, new double[]{32}));
        AnimationPlayer player = new AnimationPlayer(parse(json));
        BbModel.Layer first = layer("posture", "move", "HOLD", 0, 4);
        player.sample(0, List.of(first));
        assertEquals(1.5, player.sample(10, List.of(first)).getFirst().x(), EPS);
        assertEquals(1.75, player.sample(15, List.of(first)).getFirst().x(), EPS);
        BbModel.Layer next = new BbModel.Layer("posture", "next", 20, 1, "HOLD", 4, 4);
        assertEquals(2, player.sample(20, List.of(next)).getFirst().x(), EPS);
        assertEquals(2.5, player.sample(22, List.of(next)).getFirst().x(), EPS);
        assertEquals(3, player.sample(24, List.of()).getFirst().x(), EPS);
        assertEquals(2, player.sample(26, List.of()).getFirst().x(), EPS);
        assertEquals(1, player.sample(28, List.of()).getFirst().x(), EPS);
    }

    @Test void interactionAddsPunchDeltasAndKeepsCrawlOrSeatedPostureDuringFadeOut() {
        for (double angle : new double[]{90, 45}) {
            JsonObject json = headFixture();
            JsonObject leg = new JsonObject(); leg.addProperty("uuid", "leg"); leg.addProperty("name", "leg");
            leg.add("origin", vector(0,0,0)); leg.add("children", stringArray("cube"));
            json.getAsJsonArray("outliner").get(0).getAsJsonObject().getAsJsonArray("children").set(0, leg);
            JsonArray rootPose = constantFrame("rotation", angle, 0, 0);
            rootPose.addAll(constantFrame("position", 0, 16, 0));
            addMultiBoneAnimation(json, "posture", Map.of("bone", rootPose,
                    "head", constantFrame("rotation", 30, 0, 0), "leg", constantFrame("rotation", 15, 0, 0)));
            JsonArray rootPunch = constantFrame("rotation", 0, 0, 0);
            rootPunch.addAll(constantFrame("position", 0, 4, 0));
            addMultiBoneAnimation(json, "punch", Map.of("bone", rootPunch, "head", constantFrame("rotation", 10, 0, 0)));
            BbModel model = parse(json);
            BbModel.Layer posture = layer("posture", "posture", "HOLD", 0, 0);
            BbModel.Layer punch = layer("interaction", "punch", "HOLD", 4, 4);
            List<BbModel.Vertex> full = model.sample(4, List.of(punch, posture));
            assertEquals(1.25 + Math.cos(Math.toRadians(angle + 15)), full.getFirst().y(), EPS);
            assertEquals(Math.sin(Math.toRadians(angle + 15)), full.getFirst().z(), EPS);
            assertEquals(Math.sin(Math.toRadians(angle + 40)), full.get(4).ny(), EPS);
            assertEquals(-Math.cos(Math.toRadians(angle + 40)), full.get(4).nz(), EPS);
            AnimationPlayer player = new AnimationPlayer(model);
            player.sample(0, List.of(posture, punch));
            assertEquals(1.125 + Math.cos(Math.toRadians(angle + 15)), player.sample(2, List.of(posture, punch)).getFirst().y(), EPS);
            assertEquals(full, player.sample(4, List.of(posture, punch)));
            player.sample(5, List.of(posture));
            List<BbModel.Vertex> fading = player.sample(7, List.of(posture));
            assertEquals(1.125 + Math.cos(Math.toRadians(angle + 15)), fading.getFirst().y(), EPS);
            assertEquals(Math.sin(Math.toRadians(angle + 15)), fading.getFirst().z(), EPS);
            assertEquals(Math.sin(Math.toRadians(angle + 35)), fading.get(4).ny(), EPS);
            assertEquals(model.sample(9, List.of(posture)), player.sample(9, List.of(posture)));
        }
    }

    @Test void interactionScaleMultipliesPostureAndManualPositionStillOverrides() {
        JsonObject json = fixture();
        JsonArray posture = constantFrame("scale", 2, 3, 4); posture.addAll(constantFrame("position", 8, 0, 0));
        JsonArray interaction = constantFrame("scale", .5, 2, 1); interaction.addAll(constantFrame("position", 4, 0, 0));
        addAnimation(json, "base", 1, posture); addAnimation(json, "delta", 1, interaction);
        addAnimation(json, "absolute", 1, constantFrame("position", 32, 0, 0));
        BbModel model = parse(json);
        BbModel.Layer base = layer("posture", "base", "HOLD", 0, 0);
        for (String name : List.of("interaction", "arms", "swing", "use")) {
            BbModel.Layer delta = layer(name, "delta", "HOLD", 4, 4);
            List<BbModel.Layer> layers = List.of(base, delta);
            BbModel.Vertex halfway = model.sample(2, layers).getFirst();
            assertEquals(2.125, halfway.x(), EPS); assertEquals(4.5, halfway.y(), EPS);
            AnimationPlayer player = new AnimationPlayer(model); player.sample(0, layers);
            assertEquals(halfway, player.sample(2, layers).getFirst());
            BbModel.Vertex full = model.sample(4, layers).getFirst();
            assertEquals(1.75, full.x(), EPS); assertEquals(6, full.y(), EPS);
            List<BbModel.Layer> manual = new ArrayList<>(layers); manual.add(layer("manual", "absolute", "HOLD", 0, 0));
            assertEquals(3, model.sample(4, manual).getFirst().x(), EPS);
        }
    }

    @Test void negativeScaleKeepsWindingAndNormalsConsistentAndZeroScaleHidesGeometry() {
        JsonObject json = fixture();
        JsonArray scale = frames("scale", "linear", new double[]{0}, new double[]{0});
        scale.get(0).getAsJsonObject().add("data_points", pointArray(point(-1, 1, 1)));
        addAnimation(json, "mirror", 1, scale);
        addAnimation(json, "hidden", 1, frames("scale", "linear", new double[]{0}, new double[]{0}));
        BbModel model = parse(json);
        List<BbModel.Vertex> vertices = model.sample(1, List.of(layer("manual", "mirror", "HOLD", 0, 0)));
        BbModel.Vertex a = vertices.get(0), b = vertices.get(1), c = vertices.get(2);
        Vector3f normal = new Vector3f(b.x()-a.x(), b.y()-a.y(), b.z()-a.z())
                .cross(new Vector3f(c.x()-a.x(), c.y()-a.y(), c.z()-a.z())).normalize();
        assertEquals(a.nz(), normal.z, EPS);
        assertTrue(model.sample(1, List.of(layer("manual", "hidden", "HOLD", 0, 0))).isEmpty());
    }

    @Test void rejectsUnsupportedMeshesScriptsExternalImagesUnknownBonesAndInvalidFloats() {
        JsonObject mesh = fixture(); mesh.getAsJsonArray("elements").get(0).getAsJsonObject().addProperty("type", "mesh");
        assertThrows(IllegalArgumentException.class, () -> parse(mesh));
        JsonObject external = fixture(); external.getAsJsonArray("textures").get(0).getAsJsonObject().addProperty("source", "../../secret.png");
        assertThrows(IllegalArgumentException.class, () -> parse(external));
        JsonObject script = fixture(); addAnimation(script, "bad", 1, frames("position", "linear", new double[]{0}, new double[]{0}));
        script.getAsJsonArray("animations").get(0).getAsJsonObject().addProperty("anim_time_update", "query.anim_time");
        assertThrows(IllegalArgumentException.class, () -> parse(script));
        JsonObject expression = fixture(); addAnimation(expression, "bad", 1, frames("position", "linear", new double[]{0}, new double[]{0}));
        expression.getAsJsonArray("animations").get(0).getAsJsonObject().getAsJsonObject("animators").getAsJsonObject("bone")
                .getAsJsonArray("keyframes").get(0).getAsJsonObject().getAsJsonArray("data_points").get(0).getAsJsonObject().addProperty("x", "Math.sin(query.anim_time)");
        BbModel dynamic = parse(expression);
        assertEquals(1 + Math.sin(Math.toRadians(.5)) / 16,
                dynamic.sample(10, List.of(layer("posture", "bad", "HOLD", 0, 0))).getFirst().x(), EPS);
        JsonObject nan = fixture(); nan.getAsJsonArray("elements").get(0).getAsJsonObject().add("from", JsonParser.parseString("[\"NaN\",0,0]"));
        assertThrows(IllegalArgumentException.class, () -> parse(nan));
        JsonObject unknown = fixture(); unknown.getAsJsonArray("outliner").get(0).getAsJsonObject().add("children", stringArray("missing"));
        assertThrows(IllegalArgumentException.class, () -> parse(unknown));
        assertThrows(IllegalArgumentException.class, () -> BbModel.parse(("[".repeat(150)+"]".repeat(150)).getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> BbModel.parse(new byte[]{(byte)0xc3,0x28}));
        assertThrows(IllegalArgumentException.class, () -> BbModel.parse(new byte[8*1024*1024+1]));
    }

    @Test void textureBytesCannotBeMutatedAfterValidation() {
        BbModel model = parse(fixture());
        byte[] first = model.textures().getFirst().png(); first[0] = 0;
        assertEquals((byte)137, model.textures().getFirst().png()[0]);
        assertThrows(UnsupportedOperationException.class, () -> model.textures().clear());
    }

    @Test void playerLookOnlyRotatesHeadAndWrapsYawWithCorrectNoseDirection() {
        BbModel model = parse(headFixture());
        List<BbModel.Vertex> neutral = model.sample(0, List.of());
        List<BbModel.Vertex> yaw = model.sample(0, List.of(), 20, 0);
        assertEquals(neutral.subList(0, 4), yaw.subList(0, 4), "Body cube must remain unchanged");
        BbModel.Vertex head = yaw.get(4);
        assertEquals(Math.sin(Math.toRadians(20)), head.nx(), EPS);
        assertEquals(-Math.cos(Math.toRadians(20)), head.nz(), EPS);
        assertEquals(model.sample(0, List.of(), 90, 0), model.sample(0, List.of(), 450, 0));
        assertEquals(model.sample(0, List.of(), 90, 0), model.sample(0, List.of(), -270, 0));
        assertEquals(-.5, model.sample(0, List.of(), 0, 30).get(4).ny(), EPS);
        assertEquals(-1, model.sample(0, List.of(), 0, 120).get(4).ny(), EPS);
        assertThrows(IllegalArgumentException.class, () -> model.sample(0, List.of(), Float.NaN, 0));
    }

    @Test void crawlHeadCounterrotationRemainsIntactBeforeLookDelta() {
        JsonObject json = headFixture();
        JsonArray rootFrames = frames("rotation", "linear", new double[]{0}, new double[]{90});
        addAnimation(json, "crawl", 1, rootFrames);
        JsonArray headFrames = frames("rotation", "linear", new double[]{0}, new double[]{-90});
        JsonObject animator = new JsonObject(); animator.addProperty("type", "bone"); animator.add("keyframes", headFrames);
        json.getAsJsonArray("animations").get(0).getAsJsonObject().getAsJsonObject("animators").add("head", animator);
        BbModel model = parse(json);
        BbModel.Layer layer = layer("posture", "crawl", "HOLD", 0, 0);
        BbModel.Vertex head = model.sample(0, List.of(layer), 20, 30).get(4);
        assertEquals(Math.sin(Math.toRadians(20)) * Math.cos(Math.toRadians(30)), head.nx(), EPS);
        assertEquals(-.5, head.ny(), EPS);
        assertEquals(-Math.cos(Math.toRadians(20)) * Math.cos(Math.toRadians(30)), head.nz(), EPS);
        // The body's root still uses the author's crawl angle, while the head
        // compensation and look delta affect only its own cube.
        assertEquals(model.sample(0, List.of(layer)).subList(0, 4), model.sample(0, List.of(layer), 20, 30).subList(0, 4));
    }

    @Test void explicitlyAuthoredManualHeadRotationFadesOutAdditionalLook() {
        JsonObject json = headFixture();
        addAnimation(json, "head_lock", 1, new JsonArray());
        JsonObject animator = new JsonObject(); animator.addProperty("type", "bone");
        animator.add("keyframes", frames("rotation", "linear", new double[]{0}, new double[]{0}));
        JsonObject animation = json.getAsJsonArray("animations").get(0).getAsJsonObject();
        animation.getAsJsonObject("animators").add("head", animator);
        BbModel model = parse(json);
        AnimationPlayer player = new AnimationPlayer(model);
        BbModel.Layer manual = layer("manual", "head_lock", "HOLD", 4, 4);
        assertEquals(Math.sin(Math.toRadians(20)), player.sample(0, List.of(manual), 20, 0).get(4).nx(), EPS);
        assertEquals(Math.sin(Math.toRadians(10)), player.sample(2, List.of(manual), 20, 0).get(4).nx(), EPS);
        assertEquals(0, player.sample(4, List.of(manual), 20, 0).get(4).nx(), EPS);
        player.sample(5, List.of(), 20, 0);
        assertEquals(Math.sin(Math.toRadians(10)), player.sample(7, List.of(), 20, 0).get(4).nx(), EPS);
    }

    @Test void prefixedHeadDescendantInheritsTrackingWithoutApplyingItTwice() {
        JsonObject json = headFixture();
        JsonObject head = json.getAsJsonArray("outliner").get(0).getAsJsonObject().getAsJsonArray("children").get(1).getAsJsonObject();
        JsonObject child = new JsonObject(); child.addProperty("uuid", "eye"); child.addProperty("name", "h_Eyes");
        child.add("origin", vector(0,16,0)); child.add("children", stringArray("headcube"));
        JsonArray children = new JsonArray(); children.add(child); head.add("children", children);
        BbModel.Vertex vertex = parse(json).sample(0, List.of(), 20, 0).get(4);
        assertEquals(Math.sin(Math.toRadians(20)), vertex.nx(), EPS);
    }

    @Test void invalidPngChecksumAndIncompletePngAreRejectedBeforeReady() {
        JsonObject json = fixture();
        JsonObject texture = json.getAsJsonArray("textures").get(0).getAsJsonObject();
        byte[] png = Base64.getDecoder().decode(texture.get("source").getAsString().substring(22));
        byte[] corrupt = png.clone(); corrupt[20] ^= 1;
        texture.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(corrupt));
        assertThrows(IllegalArgumentException.class, () -> parse(json));
        texture.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(Arrays.copyOf(png, png.length - 12)));
        assertThrows(IllegalArgumentException.class, () -> parse(json));
        corrupt = png.clone(); corrupt[41] ^= 1; // First IDAT payload; IHDR is intact.
        texture.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(corrupt));
        assertThrows(IllegalArgumentException.class, () -> parse(json));
    }

    @Test void textureCountAndCombinedDecodedPixelsAreBounded() throws Exception {
        JsonObject json = fixture(); JsonArray textures = json.getAsJsonArray("textures");
        JsonObject tiny = textures.get(0).getAsJsonObject().deepCopy();
        while (textures.size() < 16) textures.add(tiny.deepCopy());
        assertEquals(16, parse(json).textures().size());
        textures.add(tiny.deepCopy());
        assertThrows(IllegalArgumentException.class, () -> parse(json));

        // This image compresses to a few KiB but requires 16M decoded pixels.
        // A second tiny image must be rejected before its decoder allocates.
        BufferedImage image = new BufferedImage(4096, 4096, BufferedImage.TYPE_BYTE_GRAY);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
        JsonObject large = new JsonObject();
        large.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray()));
        textures = new JsonArray(); textures.add(large); json.add("textures", textures);
        assertEquals(1, parse(json).textures().size());
        textures.add(tiny);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> parse(json));
        assertTrue(failure.getCause().getMessage().contains("dimensions exceed limits"));
    }

    private static JsonObject headFixture() {
        JsonObject model = fixture();
        JsonObject headCube = model.getAsJsonArray("elements").get(0).deepCopy().getAsJsonObject();
        headCube.addProperty("uuid", "headcube"); headCube.add("from", vector(0,16,-16)); headCube.add("to", vector(16,32,0));
        model.getAsJsonArray("elements").add(headCube);
        JsonObject head = new JsonObject(); head.addProperty("uuid", "head"); head.addProperty("name", "hi_Head");
        head.add("origin", vector(0,16,0));head.add("children",stringArray("headcube"));
        model.getAsJsonArray("outliner").get(0).getAsJsonObject().getAsJsonArray("children").add(head);
        return model;
    }

    private static float height(List<BbModel.Vertex> vertices) {
        double min = vertices.stream().mapToDouble(BbModel.Vertex::y).min().orElseThrow();
        double max = vertices.stream().mapToDouble(BbModel.Vertex::y).max().orElseThrow();
        return (float) (max - min);
    }
    private static float firstX(BbModel model, double tick, BbModel.Layer layer) { return model.sample(tick, List.of(layer)).getFirst().x(); }
    private static BbModel.Layer layer(String name, String animation, String loop, int in, int out) {
        return new BbModel.Layer(name, animation, 0, 1, loop, in, out);
    }
    private static BbModel parse(JsonObject json) { return BbModel.parse(json.toString().getBytes(StandardCharsets.UTF_8)); }
    private static JsonArray vector(double... values) { JsonArray array = new JsonArray(); for(double value:values) array.add(value); return array; }
    private static JsonArray stringArray(String... values) { JsonArray array = new JsonArray(); for(String value:values) array.add(value); return array; }
    private static JsonObject point(double x, double y, double z) { JsonObject point = new JsonObject(); point.addProperty("x",x);point.addProperty("y",y);point.addProperty("z",z);return point; }
    private static JsonArray pointArray(JsonObject point) { JsonArray array=new JsonArray();array.add(point);return array; }
    private static JsonArray frames(String channel, String interpolation, double[] times, double[] xs) {
        JsonArray frames = new JsonArray();
        for(int i=0;i<times.length;i++) { JsonObject frame=new JsonObject();frame.addProperty("channel",channel);frame.addProperty("time",times[i]);
            frame.addProperty("interpolation",interpolation);frame.add("data_points",pointArray(point(xs[i],0,0)));frames.add(frame); }
        return frames;
    }
    private static JsonArray constantFrame(String channel, double x, double y, double z) {
        JsonArray frames = frames(channel, "linear", new double[]{0}, new double[]{x});
        frames.get(0).getAsJsonObject().add("data_points", pointArray(point(x,y,z))); return frames;
    }
    private static void addMultiBoneAnimation(JsonObject model, String name, Map<String, JsonArray> bones) {
        JsonObject animators = new JsonObject();
        bones.forEach((id, frames) -> { JsonObject animator = new JsonObject(); animator.addProperty("type", "bone");
            animator.add("keyframes", frames); animators.add(id, animator); });
        JsonObject animation = new JsonObject(); animation.addProperty("name", name); animation.addProperty("length", 1);
        animation.add("animators", animators); model.getAsJsonArray("animations").add(animation);
    }
    private static void addAnimation(JsonObject model, String name, double length, JsonArray frames) {
        JsonObject animator = new JsonObject(); animator.addProperty("type", "bone"); animator.add("keyframes", frames);
        JsonObject animators = new JsonObject(); animators.add("bone", animator);
        JsonObject animation = new JsonObject();animation.addProperty("name",name);animation.addProperty("length",length);animation.add("animators",animators);
        model.getAsJsonArray("animations").add(animation);
    }
    private static JsonObject fixture() {
        JsonObject model = JsonParser.parseString("""
                {"meta":{"format_version":"5.0"},"resolution":{"width":16,"height":16},"textures":[],"animations":[],
                 "elements":[{"uuid":"cube","type":"cube","from":[0,0,0],"to":[16,16,16],"origin":[0,0,0],
                              "faces":{"north":{"uv":[0,0,16,16],"texture":0}}}],
                 "outliner":[{"uuid":"bone","name":"bone","origin":[0,0,0],"children":["cube"]}]}
                """).getAsJsonObject();
        try {
            BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, 0xffffffff);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
            JsonObject texture = new JsonObject();texture.addProperty("source","data:image/png;base64,"+Base64.getEncoder().encodeToString(bytes.toByteArray()));
            model.getAsJsonArray("textures").add(texture);
        } catch(Exception e) { throw new AssertionError(e); }
        return model;
    }
}
