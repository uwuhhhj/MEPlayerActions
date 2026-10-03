package com.simmc.meplayeractions.client.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simmc.meplayeractions.client.network.AssetTransfer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class YsmFunctionsTest {
    @TempDir Path temporary;

    @Test void sourceFilenameEventsAndCallableFunctionsActuallyReachTheCorrectPlayerFamily() throws Exception {
        Path folder = fixture(); Path functions = Files.createDirectories(folder.resolve("functions"));
        String square = "return args[0]*args[0];";
        String main = "v.called=fn.square(3);return ctrl.state_bypass;";
        String fp = "v.fp_called=1;return ctrl.state_bypass;";
        Files.writeString(functions.resolve("square.molang"), square);
        Files.writeString(functions.resolve("entry@PLAYER_CTRL_MAIN.molang"), main);
        Files.writeString(functions.resolve("@fp.arm_ctrl_main.molang"), fp);
        var imported = YsmFolderModel.readWithProfile(folder, null); var profile = imported.profile();
        assertEquals(List.of("entry", "square"), profile.functions().keySet().stream().toList());
        assertEquals(square, profile.functions().get("square"));
        assertEquals(main, profile.events().getAsJsonArray("player_ctrl_main").get(0).getAsString());
        assertEquals(fp, profile.events().getAsJsonArray("fp.arm_ctrl_main").get(0).getAsString());
        JsonObject raw = JsonParser.parseString(new String(imported.raw(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(square, raw.getAsJsonObject("ysm_functions").get("square").getAsString());
        AnimationPlayer player = new AnimationPlayer(BbModel.parse(imported.raw()));
        player.sample(0, List.of(new BbModel.Layer("movement", "idle", 0, 1, "LOOP", 0, 0)));
        assertEquals(9d, player.expressionVariables().get("variable.called"));
        assertFalse(player.expressionVariables().containsKey("variable.fp_called"));
        var arm = profile.components().stream().filter(component -> component.kind().equals("fp_arm")).findFirst().orElseThrow();
        assertEquals("fp.arm", arm.model().controllerFamily());
        AnimationPlayer firstPerson = new AnimationPlayer(arm.model()); firstPerson.sample(0, List.of());
        assertEquals(1d, firstPerson.expressionVariables().get("variable.fp_called"));
        assertFalse(firstPerson.expressionVariables().containsKey("variable.called"));
        assertThrows(UnsupportedOperationException.class, () -> profile.functions().put("outside", "return 0;"));
        profile.events().remove("player_ctrl_main"); assertTrue(profile.events().has("player_ctrl_main"));
        Files.writeString(functions.resolve("square.molang"), "return args[0]+1;");
        assertNotEquals(AssetTransfer.hash(imported.raw()), AssetTransfer.hash(YsmFolderModel.read(folder)),
                "Function edits belong to the new private converted model's hash");
    }

    @Test void multipleEventFilesUseStablePathOrderAndPreserveLegacyPropertiesEvents() throws Exception {
        Path folder = fixture(); Path functions = Files.createDirectories(folder.resolve("functions/sub"));
        Files.writeString(functions.resolve("b@player_ctrl_main.molang"), "v.order=2;return ctrl.state_stop;");
        Files.writeString(folder.resolve("functions/a@player_ctrl_main.molang"), "v.order=1;return ctrl.state_bypass;");
        JsonObject manifest = JsonParser.parseString(Files.readString(folder.resolve("ysm.json"))).getAsJsonObject();
        manifest.getAsJsonObject("properties").add("events", JsonParser.parseString("{\"player_ctrl_parallel_5\":\"v.legacy=7;\"}"));
        Files.writeString(folder.resolve("ysm.json"), manifest.toString());
        var profile = YsmFolderModel.readWithProfile(folder, null).profile();
        assertEquals(List.of("player_ctrl_parallel_5", "player_ctrl_main"), profile.events().keySet().stream().toList());
        assertEquals("v.legacy=7;", profile.events().getAsJsonArray("player_ctrl_parallel_5").get(0).getAsString());
        assertEquals("v.order=1;return ctrl.state_bypass;", profile.events().getAsJsonArray("player_ctrl_main").get(0).getAsString());
        assertEquals("v.order=2;return ctrl.state_stop;", profile.events().getAsJsonArray("player_ctrl_main").get(1).getAsString());
    }

    @Test void authoredEventFilenameAndCommentsDoNotNeedToBeACallableIdentifier() throws Exception {
        Path folder = fixture(); Path functions = Files.createDirectories(folder.resolve("functions"));
        String name = "印度格挡（格挡连招控制器）";
        String script = "// 作者函数说明\nv.event_called=8;/* bounded note */return ctrl.state_bypass;";
        Files.writeString(functions.resolve(name + "@player_ctrl_main.molang"), script);
        var imported = YsmFolderModel.readWithProfile(folder, null);
        assertEquals(script, imported.profile().functions().get(name));
        assertEquals(script, imported.profile().events().getAsJsonArray("player_ctrl_main").get(0).getAsString());
        AnimationPlayer player = new AnimationPlayer(BbModel.parse(imported.raw()));
        player.sample(0, List.of(new BbModel.Layer("movement", "idle", 0, 1, "LOOP", 0, 0)));
        assertEquals(8d, player.expressionVariables().get("variable.event_called"),
                "A legitimate source event filename may contain punctuation even though fn calls use identifiers");
    }

    @Test void duplicateStemsAndCaseFoldCallableCollisionsCannotDependOnDirectoryEnumeration() throws Exception {
        Path folder = fixture(); Path nested = Files.createDirectories(folder.resolve("functions/sub"));
        Files.writeString(nested.resolve("same.molang"), "return 1;");
        Path other = folder.resolve("functions/same.molang"); Files.writeString(other, "return 2;");
        assertThrows(IOException.class, () -> YsmFolderModel.read(folder));
        Files.delete(other); Files.delete(nested.resolve("same.molang"));
        Files.writeString(folder.resolve("functions/Foo@player_ctrl_main.molang"), "return 1;");
        Files.writeString(nested.resolve("foo@fp.arm_ctrl_main.molang"), "return 2;");
        assertThrows(IOException.class, () -> YsmFolderModel.read(folder), "The parser's case folding must not silently select one callable");
    }

    @Test void malformedUtf8OversizedScriptsAndDeepExpressionsAreRejectedBeforeExecution() throws Exception {
        Path folder = fixture(); Path functions = Files.createDirectories(folder.resolve("functions"));
        Path file = functions.resolve("probe.molang");
        Files.write(file, new byte[]{(byte) 0xc3, 0x28}); assertThrows(IOException.class, () -> YsmFolderModel.read(folder));
        Files.writeString(file, "//" + "x".repeat(32_767)); assertThrows(IOException.class, () -> YsmFolderModel.read(folder));
        Files.writeString(file, "return " + "(".repeat(100) + "1" + ")".repeat(100) + ";");
        assertThrows(IOException.class, () -> YsmFolderModel.read(folder));
        Files.writeString(file, "\ufeffreturn 1;");
        assertEquals("return 1;", YsmFolderModel.readWithProfile(folder, null).profile().functions().get("probe"));
        Files.write(file, new byte[0]);
        assertEquals("", YsmFolderModel.readWithProfile(folder, null).profile().functions().get("probe"),
                "An authored empty function is a bounded no-op, while required model resources remain nonempty");
    }

    @Test void functionAndPerEventCountsRemainBounded() throws Exception {
        Path folder = fixture(); Path functions = Files.createDirectories(folder.resolve("functions"));
        for (int i = 0; i < 64; i++) Files.writeString(functions.resolve("f" + i + ".molang"), "return 1;");
        assertEquals(64, YsmFolderModel.readWithProfile(folder, null).profile().functions().size());
        Path overflow = functions.resolve("f64.molang"); Files.writeString(overflow, "return 1;");
        assertThrows(IOException.class, () -> YsmFolderModel.read(folder)); Files.delete(overflow);
        for (int i = 0; i < 64; i++) Files.delete(functions.resolve("f" + i + ".molang"));
        for (int i = 0; i < 33; i++) Files.writeString(functions.resolve("f" + i + "@player_ctrl_main.molang"), "return ctrl.state_continue;");
        assertThrows(IOException.class, () -> YsmFolderModel.read(folder));
    }

    @Test void functionDirectoryDepthAndCombinedInputBytesStillHaveTheExistingLimits() throws Exception {
        Path folder = fixture(); Path nested = folder.resolve("functions");
        for (int i = 0; i < 10; i++) nested = Files.createDirectories(nested.resolve("d"));
        Files.writeString(nested.resolve("probe.molang"), "return 1;");
        assertThrows(IOException.class, () -> YsmFolderModel.read(folder));
        Path other = YsmFolderFixtures.copyDefault(temporary.resolve("combined"));
        long fixtureBytes;
        try (var files = Files.walk(other)) {
            fixtureBytes = files.filter(Files::isRegularFile).mapToLong(file -> {
                try { return Files.size(file); } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
            }).sum();
        }
        Path sounds = Files.createDirectories(other.resolve("sounds"));
        Files.write(sounds.resolve("large.ogg"), new byte[Math.toIntExact(AssetTransfer.MAX_RAW - fixtureBytes - 16_384)]);
        assertTrue(YsmFolderModel.readWithProfile(other, null).profile().soundResource("large").isPresent(),
                "The complete fixture and audio fit the original input budget before adding a function");
        Path functions = Files.createDirectories(other.resolve("functions"));
        String script = "return 1;" + " ".repeat(32_759);
        Files.writeString(functions.resolve("f.molang"), script);
        IOException overflow = assertThrows(IOException.class, () -> YsmFolderModel.read(other));
        assertTrue(overflow.getMessage().contains("元数据和贴图总大小不能超过 8 MiB"),
                "A single valid 32 KiB function exceeds only the shared input budget, not the script or output limits");
    }

    @Test void linkedFunctionsNeverExecuteOrReadAnExternalTargetAndCleanupPreservesIt() throws Exception {
        Path folder = fixture(), external = Files.createDirectories(temporary.resolve("external-functions"));
        Path link = folder.resolve("functions"); Path target = external.resolve("probe.molang");
        Files.writeString(target, "v.outside=1;");
        try {
            directoryLink(link, external); assertThrows(IOException.class, () -> YsmFolderModel.read(folder));
            assertEquals("v.outside=1;", Files.readString(target));
        } finally {
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) Files.delete(link);
        }
        assertTrue(Files.isRegularFile(target));
    }

    @Test void bundledModelWithNoFunctionsRetainsItsExistingHashAndHasEmptyCallableMetadata() throws Exception {
        var builtin = YsmFolderModel.bundledDefaultWithProfile(null);
        assertTrue(builtin.profile().functions().isEmpty()); assertTrue(builtin.profile().events().isEmpty());
        JsonObject raw = JsonParser.parseString(new String(builtin.raw(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse(raw.has("ysm_functions")); assertFalse(raw.has("ysm_events"));
        assertArrayEquals(builtin.raw(), YsmFolderModel.read(fixture()));
    }

    private Path fixture() throws IOException { return YsmFolderFixtures.copyDefault(temporary.resolve("model")); }
    private static void directoryLink(Path link, Path target) throws Exception {
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
}
