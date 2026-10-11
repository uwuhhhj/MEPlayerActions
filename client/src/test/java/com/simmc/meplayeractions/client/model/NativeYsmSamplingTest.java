package com.simmc.meplayeractions.client.model;

import com.google.gson.*;
import com.simmc.meplayeractions.client.model.nativeysm.RawYsmModel;
import com.simmc.meplayeractions.expression.Molang;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NativeYsmSamplingTest
{
    @Test
    void thisUsesPreviousControllerAxesAndRestoresContextAfterSampling() throws Exception
    {
        RawYsmModel raw = NativeYsmFileTest.model();
        RawYsmModel.RawAnimationFile file = new RawYsmModel.RawAnimationFile();
        RawYsmModel.RawAnimation clip = new RawYsmModel.RawAnimation();
        clip.name = "idle";
        clip.length = 1;
        clip.loopMode = 1;
        RawYsmModel.RawBoneAnimation track = new RawYsmModel.RawBoneAnimation();
        track.boneName = "ysmGlowEyes";
        for (List<RawYsmModel.RawKeyframe> keys : List.of(track.position, track.rotation, track.scale))
        {
            RawYsmModel.RawKeyframe key = new RawYsmModel.RawKeyframe();
            key.postData = new Object[]
            { "this", "this", "this" };
            keys.add(key);
        }
        clip.boneAnimations.add(track);
        file.animations.put("idle", clip);
        raw.mainEntity.animationFiles.put("main", file);
        BbModel model = BbModel.parse(NativeYsmFile.importModel(raw, null).raw());
        BbModel.Pose previous = model.emptyPose();
        // Reader index 0 is its identity root; the fixture's one real outliner bone is index 1.
        previous.channels[1][0] = new Vector3f(5, 6, 7);
        previous.channels[1][1] = new Vector3f(30, 60, 90);
        previous.channels[1][2] = new Vector3f(2, 3, 4);
        Molang.Context context = new Molang.Context();
        context.enableNativeYsm();
        context.currentValue(99);
        context.physicsScope("outer");
        BbModel.Pose sampled = model.evaluateClip("idle", 3, "LOOP", context, previous).pose();
        assertEquals(new Vector3f(5, 6, 7), sampled.channels[1][0]);
        assertEquals(new Vector3f(2, 3, 4), sampled.channels[1][2]);
        assertEquals(-Math.PI / 6, sampled.channels[1][1].x, 1e-6);
        assertEquals(-Math.PI / 3, sampled.channels[1][1].y, 1e-6);
        assertEquals(Math.PI / 2, sampled.channels[1][1].z, 1e-6);
        assertEquals(99, context.currentValue());
        assertEquals("outer", context.physicsScope());
        assertEquals(new Vector3f(30, 60, 90), previous.channels[1][1],
                "The source snapshot is immutable during sampling");
    }

    @Test
    void nativeSoundKeyframeUsesMatureDefaultChannelAndTwoArguments() throws Exception
    {
        RawYsmModel raw = NativeYsmFileTest.model();
        RawYsmModel.RawAnimationFile file = new RawYsmModel.RawAnimationFile();
        RawYsmModel.RawAnimation clip = new RawYsmModel.RawAnimation();
        clip.name = "idle";
        clip.length = 1;
        RawYsmModel.RawSoundEffect sound = new RawYsmModel.RawSoundEffect();
        sound.timestamp = .1f;
        sound.effectName = "test";
        clip.soundEffects.add(sound);
        file.animations.put("idle", clip);
        raw.mainEntity.animationFiles.put("main", file);
        BbModel model = BbModel.parse(NativeYsmFile.importModel(raw, null).raw());
        List<List<Object>> calls = new ArrayList<>();
        Molang.Context context = new Molang.Context();
        context.enableNativeYsm();
        context.functions((name, args) ->
        {
            if (name.equals("ysm.play_sound"))
            {
                calls.add(List.copyOf(args));
            }
            return null;
        });
        model.clipEvents("idle", "ONCE", -1, 10, context);
        assertEquals(1, calls.size());
        assertEquals(2, calls.get(0).size());
        assertEquals(0d, ((Number) calls.get(0).get(0)).doubleValue());
        assertEquals("test", calls.get(0).get(1));
    }

    @Test
    void nativeInventoryBeyondLegacyBbLimitRemainsBounded() throws Exception
    {
        RawYsmModel raw = NativeYsmFileTest.model();
        RawYsmModel.RawAnimationFile file = new RawYsmModel.RawAnimationFile();
        for (int i = 0; i < 129; i++)
        {
            RawYsmModel.RawAnimation clip = new RawYsmModel.RawAnimation();
            clip.name = "extra" + i;
            clip.length = 1;
            file.animations.put(clip.name, clip);
        }
        raw.mainEntity.animationFiles.put("main", file);
        BbModel model = BbModel.parse(NativeYsmFile.importModel(raw, null).raw());
        assertEquals(129, model.animations().size());
        assertTrue(model.animationCatalog().contains("extra128"));
    }
}
