package com.simmc.meplayeractions.client.model;

import com.google.gson.*;
import com.simmc.meplayeractions.client.network.AssetTransfer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class YsmModelProfileTest {
    @TempDir Path temporary;

    @Test void defaultProfileRetainsAuthorActionsFormsLanguagesSkinsAndVanillaComponents() throws Exception {
        var imported = YsmFolderModel.bundledDefaultWithProfile(null); var profile = imported.profile();
        assertTrue(profile.isYsm()); assertEquals("CC 0", profile.metadata().getAsJsonObject("license").get("type").getAsString());
        assertEquals(List.of("extra0", "extra1", "extra2", "extra3", "extra4", "extra5", "extra6", "extra7"),
                profile.extraAnimations().keySet().stream().toList());
        assertEquals("#extra_config", profile.extraAnimations().get("extra0"));
        assertEquals("v.roaming.red_bow_headdress", profile.extraAnimationButtons().get(0).getAsJsonObject()
                .getAsJsonArray("config_forms").get(0).getAsJsonObject().get("value").getAsString());
        assertEquals("gui", profile.previewAnimation()); assertEquals("default", profile.defaultTexture());
        assertEquals(List.of("default", "blue"), profile.textures().stream().map(YsmModelProfile.TextureChoice::id).toList());
        assertEquals("默认模型", profile.localized("zh_cn", "metadata.name", "missing"));
        assertNotEquals("missing", profile.localized("de_de", "metadata.name", "missing"));
        assertEquals("fallback", profile.localized("zh_cn", "absent", "fallback"));
        assertEquals(9, profile.components().size());
        assertEquals(List.of("arm", "fp_arm", "projectile", "projectile", "projectile", "projectile", "vehicle", "vehicle", "vehicle"),
                profile.components().stream().map(YsmModelProfile.Component::kind).toList());
        assertTrue(profile.components().get(0).model().animations().contains("use_mainhand:bow"));
        assertTrue(profile.components().get(1).model().animations().containsAll(List.of("parallel0", "use_offhand:shield")));
        assertEquals(List.of("#minecraft:boat"), profile.components().get(6).matches());
        assertTrue(profile.components().get(6).model().animations().containsAll(List.of("water", "forward")));
        assertTrue(profile.resourcePng("textures/gui/foreground.png").isPresent());
        assertTrue(profile.resourcePng("../outside.png").isEmpty());
        assertTrue(profile.animationFiles().containsKey("tac"), "Deferred mod family paths remain metadata");
        assertFalse(BbModel.parse(imported.raw()).animations().contains("pre_parallel_import_parallel1"),
                "The native YSM pipeline must not execute duplicated synthetic aliases");
    }

    @Test void skinObjectMaterialsAndPublicViewsAreImmutableAndSelectedPngChangesOnlyTheSkin() throws Exception {
        Path folder = fixture(); JsonObject manifest = read(folder.resolve("ysm.json"));
        JsonArray textures = manifest.getAsJsonObject("files").getAsJsonObject("player").getAsJsonArray("texture");
        JsonObject material = new JsonObject(); material.addProperty("uv", "textures/blue.png");
        material.addProperty("emission", "textures/default.png"); textures.set(1, material); write(folder.resolve("ysm.json"), manifest);
        var original = YsmFolderModel.readWithProfile(folder, "default");
        var blue = YsmFolderModel.readWithProfile(folder, "blue");
        assertNotEquals(AssetTransfer.hash(original.raw()), AssetTransfer.hash(blue.raw()));
        assertEquals("textures/default.png", blue.profile().textures().get(1).material().get("emission").getAsString());
        byte[] png = blue.profile().textures().get(1).png(); byte first = png[0]; png[0]++;
        assertEquals(first, blue.profile().textures().get(1).png()[0]);
        byte[] resource = blue.profile().resourcePng("textures/blue.png").orElseThrow(); resource[0]++;
        assertEquals(first, blue.profile().resourcePng("textures/blue.png").orElseThrow()[0]);
        blue.profile().properties().remove("extra_animation"); assertEquals(8, blue.profile().extraAnimations().size());
        blue.profile().extraAnimationButtons().remove(0); assertEquals(1, blue.profile().extraAnimationButtons().size());
        assertThrows(UnsupportedOperationException.class, () -> blue.profile().extraAnimations().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> blue.profile().components().get(2).matches().add("minecraft:zombie"));
        var component = blue.profile().components().getFirst(); byte[] raw = component.raw(); raw[0]++;
        assertEquals(component.hash(), AssetTransfer.hash(component.raw()));
        assertEquals(BbModel.parse(original.raw()).sample(10, List.of()), BbModel.parse(blue.raw()).sample(10, List.of()));
    }

    @Test void orderedOrdinaryVariableFormsAndClassificationRemainAuthorMetadata() throws Exception {
        Path folder = fixture(); JsonObject manifest = read(folder.resolve("ysm.json"));
        JsonObject properties = manifest.getAsJsonObject("properties");
        properties.add("extra_animation_buttons", JsonParser.parseString("""
                [{"id":"face","name":"Face","config_forms":[
                 {"type":"range","value":"v.player_size","min":0.2,"max":3,"step":0.05},
                 {"type":"radio","value":"v.player_face","labels":{"Neutral":"v.player_face=0;v.player_smile=0;","Smile":"v.player_face=1;v.player_smile=1;"}}]}]
                """));
        properties.add("extra_animation_classify", JsonParser.parseString("[{\"name\":\"Face\",\"extra_animation\":{\"extra2\":\"Two\",\"extra1\":\"One\"}}]"));
        write(folder.resolve("ysm.json"), manifest); var profile = YsmFolderModel.readWithProfile(folder, null).profile();
        JsonArray forms = profile.extraAnimationButtons().get(0).getAsJsonObject().getAsJsonArray("config_forms");
        assertEquals("v.player_size", forms.get(0).getAsJsonObject().get("value").getAsString());
        JsonObject labels = forms.get(1).getAsJsonObject().getAsJsonObject("labels");
        assertEquals(List.of("Neutral", "Smile"), labels.keySet().stream().toList());
        assertEquals("v.player_face=1;v.player_smile=1;", labels.get("Smile").getAsString());
        assertEquals(List.of("extra2", "extra1"), profile.extraAnimationClassify().get(0).getAsJsonObject()
                .getAsJsonObject("extra_animation").keySet().stream().toList());
    }

    @Test void sourceControllersReachBothPrivatePlayerAndFirstPersonRawWithoutLosingScripts() throws Exception {
        Path folder = fixture(); JsonObject definitions = JsonParser.parseString("""
                {"controller.player.test":{"initial_state":"idle","states":{
                  "idle":{"animations":["idle"],"transitions":[{"walk":"q.ground_speed>0"}]},
                  "walk":{"animations":[{"walk":"q.ground_speed>0"}],"on_entry":["v.marker=1;"],"on_exit":["v.marker=0;"]}}},
                 "controller.fp.arm.test":{"initial_state":"idle","states":{"idle":{"animations":["parallel0"]}}}}
                """).getAsJsonObject();
        JsonObject controller = new JsonObject(); controller.add("animation_controllers", definitions);
        Files.createDirectories(folder.resolve("controller")); write(folder.resolve("controller/vanilla.json"), controller);
        JsonObject manifest = read(folder.resolve("ysm.json"));
        manifest.getAsJsonObject("files").getAsJsonObject("player").add("animation_controllers", JsonParser.parseString("[\"controller/vanilla.json\"]"));
        write(folder.resolve("ysm.json"), manifest); var imported = YsmFolderModel.readWithProfile(folder, null);
        assertEquals(definitions, imported.profile().animationControllers());
        JsonObject playerRaw = JsonParser.parseString(new String(imported.raw(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(definitions, playerRaw.getAsJsonObject("ysm_animation_controllers"));
        assertEquals("player", playerRaw.get("ysm_controller_family").getAsString());
        var fp = imported.profile().components().stream().filter(component -> component.kind().equals("fp_arm")).findFirst().orElseThrow();
        JsonObject fpRaw = JsonParser.parseString(new String(fp.raw(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(definitions, fpRaw.getAsJsonObject("ysm_animation_controllers"));
        assertEquals("fp.arm", fpRaw.get("ysm_controller_family").getAsString());
        imported.profile().animationControllers().remove("controller.player.test");
        assertEquals(2, imported.profile().animationControllers().size());
    }

    @Test void sourceLoopWeightsAndChronologicalTimelineScriptsArePreservedIncludingQuotedPunctuation() throws Exception {
        Path folder = fixture(); Path path = folder.resolve("animations/extra.animation.json"); JsonObject source = read(path);
        source.getAsJsonObject("animations").add("timeline_probe", JsonParser.parseString("""
                {"loop":"hold_on_last_frame","animation_length":1,"blend_weight":"v.weight??1",
                 "bones":{"Root":{"position":{"0.1":{"pre":[1,2,3]}}}},
                 "timeline":{"0.2":"v.result=v.result+3;","0.0":["v.result=1;","v.message='minecraft:test?yes:no';"]}}
                """));
        source.getAsJsonObject("animations").add("once_probe", new JsonObject());
        source.getAsJsonObject("animations").add("explicit_zero", JsonParser.parseString("{\"animation_length\":0}"));
        write(path, source); var imported = YsmFolderModel.readWithProfile(folder, null);
        JsonArray clips = JsonParser.parseString(new String(imported.raw(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("animations");
        JsonObject probe = findClip(clips, "timeline_probe");
        assertEquals("HOLD", probe.get("loop").getAsString()); assertEquals("v.weight??1", probe.get("blend_weight").getAsString());
        assertEquals("ONCE", findClip(clips, "once_probe").get("loop").getAsString());
        assertTrue(findClip(clips, "once_probe").get("ysm_infinite").getAsBoolean(),
                "Source constant clips with no length remain active until their slot stops them");
        assertFalse(findClip(clips, "explicit_zero").has("ysm_infinite"));
        assertEquals("LOOP", findClip(clips, "idle").get("loop").getAsString());
        JsonArray timeline = probe.getAsJsonObject("animators").getAsJsonObject("ysm_import_timeline").getAsJsonArray("keyframes");
        assertEquals(0, timeline.get(0).getAsJsonObject().get("time").getAsDouble());
        assertEquals(.2, timeline.get(1).getAsJsonObject().get("time").getAsDouble());
        assertEquals("v.message='minecraft:test?yes:no';", timeline.get(0).getAsJsonObject().getAsJsonArray("data_points")
                .get(1).getAsJsonObject().get("script").getAsString());
    }

    @Test void soundParticleAndControllerEventsRetainActualSourcePayloads() throws Exception {
        Path folder = fixture(); JsonObject manifest = read(folder.resolve("ysm.json"));
        JsonObject events = JsonParser.parseString("{\"player_ctrl_main\":\"v.event_seen=1;\"}").getAsJsonObject();
        manifest.getAsJsonObject("properties").add("events", events); write(folder.resolve("ysm.json"), manifest);
        Path path = folder.resolve("animations/extra.animation.json"); JsonObject source = read(path);
        source.getAsJsonObject("animations").add("effect_probe", JsonParser.parseString("""
                {"animation_length":1,"sound_effects":{"0.2":{"effect":"song","volume":0.5}},
                 "particle_effects":{"0.3":{"effect":"minecraft:smoke","locator":"RightHandLocator","pre_effect_script":"v.fx=1;"}}}
                """));
        write(path, source); var imported = YsmFolderModel.readWithProfile(folder, null);
        JsonObject raw = JsonParser.parseString(new String(imported.raw(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(events.get("player_ctrl_main").getAsString(),
                raw.getAsJsonObject("ysm_events").getAsJsonArray("player_ctrl_main").get(0).getAsString());
        JsonObject effects = findClip(raw.getAsJsonArray("animations"), "effect_probe").getAsJsonObject("animators");
        JsonObject sound = effects.getAsJsonObject("ysm_import_sound_effects").getAsJsonArray("keyframes").get(0).getAsJsonObject();
        assertEquals("sound", sound.get("channel").getAsString());
        assertEquals("song", sound.getAsJsonArray("data_points").get(0).getAsJsonObject().get("effect").getAsString());
        assertEquals(.5, sound.getAsJsonArray("data_points").get(0).getAsJsonObject().get("volume").getAsDouble());
        JsonObject particle = effects.getAsJsonObject("ysm_import_particle_effects").getAsJsonArray("keyframes").get(0).getAsJsonObject();
        assertEquals("particle", particle.get("channel").getAsString());
        assertEquals("v.fx=1;", particle.getAsJsonArray("data_points").get(0).getAsJsonObject().get("pre_effect_script").getAsString());
    }

    @Test void localSoundPathIsBoundedAndOnlyProvidesDefensiveAuthorStemLookups() throws Exception {
        Path folder = fixture(); Files.createDirectories(folder.resolve("audio/nested"));
        byte[] sound = "OggS bounded fixture payload".getBytes(StandardCharsets.UTF_8);
        Files.write(folder.resolve("audio/nested/song.ogg"), sound);
        Files.writeString(folder.resolve("audio/ignored.txt"), "not an Ogg resource");
        JsonObject manifest = read(folder.resolve("ysm.json"));
        manifest.getAsJsonObject("files").addProperty("sound_path", "audio"); write(folder.resolve("ysm.json"), manifest);
        var profile = YsmFolderModel.readWithProfile(folder, null).profile();
        assertEquals(List.of("song"), profile.soundResources().keySet().stream().toList());
        assertArrayEquals(sound, profile.soundResource("song").orElseThrow());
        byte[] first = profile.soundResource("song").orElseThrow(); first[0]++;
        profile.soundResources().get("song")[0]++;
        assertArrayEquals(sound, profile.soundResource("song").orElseThrow());
        assertTrue(profile.soundResource("minecraft:song").isEmpty());
        assertTrue(profile.soundResource("../song").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> profile.soundResources().put("outside", sound));
        manifest.getAsJsonObject("files").addProperty("sound_path", "../audio"); write(folder.resolve("ysm.json"), manifest);
        assertThrows(IOException.class, () -> YsmFolderModel.read(folder));
    }

    @Test void duplicateSoundStemsCountsAndBytesCannotEscapeTheCombinedImportBudget() throws Exception {
        Path folder = fixture(); Path sounds = Files.createDirectories(folder.resolve("sounds/sub"));
        Files.write(sounds.resolve("song.ogg"), new byte[]{1}); Files.write(folder.resolve("sounds/song.ogg"), new byte[]{2});
        assertThrows(IOException.class, () -> YsmFolderModel.read(folder), "Duplicate source sound stems are ambiguous");
        Files.delete(sounds.resolve("song.ogg")); Files.delete(folder.resolve("sounds/song.ogg"));
        for (int i = 0; i < 33; i++) Files.write(sounds.resolve("sound" + i + ".ogg"), new byte[]{1});
        assertThrows(IOException.class, () -> YsmFolderModel.read(folder));
        for (int i = 0; i < 33; i++) Files.delete(sounds.resolve("sound" + i + ".ogg"));
        Files.write(sounds.resolve("large.ogg"), new byte[AssetTransfer.MAX_RAW]);
        assertThrows(IOException.class, () -> YsmFolderModel.read(folder), "Audio shares the model's original eight MiB total");
    }

    @Test void componentObjectMatchAndTextureMetadataUseOnlyValidatedLocalAssets() throws Exception {
        Path folder = fixture(); JsonObject manifest = read(folder.resolve("ysm.json"));
        manifest.getAsJsonObject("files").add("projectiles", JsonParser.parseString("""
                {"minecraft:arrow":{"model":"models/arrow.json","animation":"animations/arrow.animation.json",
                  "texture":{"uv":"textures/arrow.png"}}}
                """));
        write(folder.resolve("ysm.json"), manifest); var profile = YsmFolderModel.readWithProfile(folder, null).profile();
        var arrow = profile.components().stream().filter(component -> component.kind().equals("projectile")).findFirst().orElseThrow();
        assertEquals("minecraft:arrow", arrow.id()); assertEquals(List.of("minecraft:arrow"), arrow.matches());
        assertEquals("textures/arrow.png", arrow.textures().getFirst().material().get("uv").getAsString());
        assertTrue(arrow.model().animations().contains("air"));
        manifest.getAsJsonObject("files").getAsJsonObject("projectiles").getAsJsonObject("minecraft:arrow").addProperty("model", "../outside.json");
        write(folder.resolve("ysm.json"), manifest); assertThrows(IOException.class, () -> YsmFolderModel.read(folder));
    }

    @Test void controllerPathsCountsAndDuplicateNamesCannotBypassLocalImportLimits() throws Exception {
        Path folder = fixture(); JsonObject manifest = read(folder.resolve("ysm.json"));
        JsonObject player = manifest.getAsJsonObject("files").getAsJsonObject("player");
        player.add("animation_controllers", JsonParser.parseString("[\"../outside.json\"]"));
        write(folder.resolve("ysm.json"), manifest); assertThrows(IOException.class, () -> YsmFolderModel.read(folder));
        JsonObject definitions = new JsonObject();
        for (int i = 0; i < 65; i++) definitions.add("controller.player." + i, JsonParser.parseString("{\"states\":{\"default\":{}}}"));
        JsonObject file = new JsonObject(); file.add("animation_controllers", definitions);
        Files.createDirectories(folder.resolve("controller")); write(folder.resolve("controller/large.json"), file);
        player.add("animation_controllers", JsonParser.parseString("[\"controller/large.json\"]"));
        write(folder.resolve("ysm.json"), manifest); assertThrows(IOException.class, () -> YsmFolderModel.read(folder));
        definitions.remove("controller.player.64"); write(folder.resolve("controller/large.json"), file);
        player.add("animation_controllers", JsonParser.parseString("[\"controller/large.json\",\"controller/large.json\"]"));
        write(folder.resolve("ysm.json"), manifest); assertThrows(IOException.class, () -> YsmFolderModel.read(folder));
    }

    @Test void everyReferencedSkinIsValidatedAndCombinedInputStillHasTheEightMiBBudget() throws Exception {
        Path folder = fixture(); Files.writeString(folder.resolve("textures/blue.png"), "bad unused skin");
        assertThrows(IOException.class, () -> YsmFolderModel.read(folder));
        YsmFolderFixtures.copyDefault(folder);
        String language = "{\"message\":\"" + "x".repeat(4 * 1024 * 1024) + "\"}";
        Files.writeString(folder.resolve("lang/en_us.json"), language); Files.writeString(folder.resolve("lang/zh_cn.json"), language);
        assertThrows(IOException.class, () -> YsmFolderModel.read(folder));
    }

    @Test void perModelClipLimitAndFormLimitRemainBoundedAcrossExpandedFamilies() throws Exception {
        Path folder = fixture(); JsonObject extra = read(folder.resolve("animations/extra.animation.json"));
        for (int i = 0; i < 14; i++) extra.getAsJsonObject("animations").add("overflow" + i, new JsonObject());
        write(folder.resolve("animations/extra.animation.json"), extra); assertThrows(IOException.class, () -> YsmFolderModel.read(folder));
        YsmFolderFixtures.copyDefault(folder); JsonObject manifest = read(folder.resolve("ysm.json"));
        JsonArray forms = new JsonArray(); for (int i = 0; i < 129; i++) forms.add(JsonParser.parseString("{\"type\":\"checkbox\",\"value\":\"v.a\"}"));
        manifest.getAsJsonObject("properties").getAsJsonArray("extra_animation_buttons").get(0).getAsJsonObject().add("config_forms", forms);
        write(folder.resolve("ysm.json"), manifest); assertThrows(IOException.class, () -> YsmFolderModel.read(folder));
    }

    @Test void allSkinPngPixelsShareTheExistingSixteenMillionPixelBudget() throws Exception {
        Path folder = fixture();
        java.awt.image.BufferedImage oversizedTotal = new java.awt.image.BufferedImage(4096, 4096,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        assertTrue(javax.imageio.ImageIO.write(oversizedTotal, "png", folder.resolve("textures/blue.png").toFile()));
        oversizedTotal.flush();
        assertThrows(IOException.class, () -> YsmFolderModel.read(folder),
                "An unselected skin still consumes the profile's bounded validated pixel budget");
    }

    @Test void linkedModelAncestorCannotReadOutsideTheOrdinaryFolderAndCleanupPreservesItsTarget() throws Exception {
        Path folder = fixture(), link = folder.resolve("models"), target = folder.resolve("original-models");
        Files.move(link, target);
        try {
            createDirectoryLink(link, target);
            assertThrows(IOException.class, () -> YsmFolderModel.read(folder));
            assertTrue(Files.isRegularFile(target.resolve("main.json")));
        } finally {
            // Remove the directory entry itself; never recurse into a symlink/junction target.
            if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.delete(link);
            Files.move(target, link);
        }
        assertTrue(Files.isRegularFile(link.resolve("main.json")));
    }

    @Test void linkedSoundDirectoryCannotReadExternalResourcesAndCleanupPreservesItsTarget() throws Exception {
        Path folder = fixture(), target = Files.createDirectories(temporary.resolve("external-audio"));
        Path link = folder.resolve("sounds"); Files.write(target.resolve("song.ogg"), new byte[]{1, 2, 3});
        try {
            createDirectoryLink(link, target); assertThrows(IOException.class, () -> YsmFolderModel.read(folder));
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(target.resolve("song.ogg")));
        } finally {
            if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.delete(link);
        }
        assertTrue(Files.isRegularFile(target.resolve("song.ogg")));
    }

    private static void createDirectoryLink(Path link, Path target) throws Exception {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException failure) {
            if (!System.getProperty("os.name").startsWith("Windows")) throw failure;
            String command = "New-Item -ItemType Junction -Path '" + link.toString().replace("'", "''")
                    + "' -Target '" + target.toString().replace("'", "''") + "' -ErrorAction Stop | Out-Null";
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
                    .redirectErrorStream(true).start();
            boolean completed = process.waitFor(10, TimeUnit.SECONDS);
            if (!completed) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
            assertTrue(completed, "Junction creation must finish");
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private Path fixture() throws IOException { return YsmFolderFixtures.copyDefault(temporary.resolve("model")); }
    private static JsonObject read(Path file) throws IOException { return JsonParser.parseString(Files.readString(file)).getAsJsonObject(); }
    private static void write(Path file, JsonObject json) throws IOException { Files.writeString(file, json.toString()); }
    private static JsonObject findClip(JsonArray clips, String name) {
        for (JsonElement clip : clips) if (clip.getAsJsonObject().get("name").getAsString().equals(name)) return clip.getAsJsonObject();
        throw new AssertionError("Missing converted clip " + name);
    }
}
