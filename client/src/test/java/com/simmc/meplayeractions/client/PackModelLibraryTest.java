package com.simmc.meplayeractions.client;

import com.google.gson.*;
import com.simmc.meplayeractions.client.network.AssetTransfer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackModelLibraryTest {
    private record Fixture(Map<String, byte[]> resources, String hash, byte[] source) { }
    private Fixture fixture() throws Exception {
        byte[] raw;
        try (var stream = getClass().getResourceAsStream("/ysm_02_jk.bbmodel")) { raw = stream.readAllBytes(); }
        String text = new String(raw, StandardCharsets.UTF_8), hash = AssetTransfer.hash(raw);
        var root = JsonParser.parseString(text).getAsJsonObject();
        var resources = new HashMap<String, byte[]>(); int index = 0;
        for (var element : root.getAsJsonArray("textures")) {
            String source = element.getAsJsonObject().get("source").getAsString();
            String id = "modelengine:textures/test_" + index++ + ".png";
            resources.put(id, Base64.getDecoder().decode(source.substring("data:image/png;base64,".length())));
            text = text.replace("\"" + source + "\"", "\"" + id + "\"");
        }
        resources.put("meplayeractions:models/ysm_02_jk.bbmodel", text.getBytes(StandardCharsets.UTF_8));
        resources.put(PackModelLibrary.INDEX, ("{\"version\":1,\"models\":[{\"modelId\":\"ysm_02_jk\",\"hashSha256\":\""
                + hash + "\",\"modelResource\":\"meplayeractions:models/ysm_02_jk.bbmodel\"}]}").getBytes(StandardCharsets.UTF_8));
        return new Fixture(resources, hash, raw);
    }
    private PackModelLibrary.Resources reader(Fixture f) {
        return (id, max) -> {
            byte[] bytes = f.resources.get(id);
            if (bytes == null || bytes.length > max) throw new IOException("Missing/oversize resource");
            return bytes;
        };
    }
    @Test void sharedPackTextureRestoresOriginalHashAndModel() throws Exception {
        Fixture f = fixture(); var loaded = PackModelLibrary.load(reader(f), "ysm_02_jk", f.hash);
        assertEquals(f.hash, loaded.hash()); assertTrue(loaded.model().cubeCount() > 100);
        assertTrue(loaded.model().animations().contains("wave"));
        assertFalse(new String(f.resources.get("meplayeractions:models/ysm_02_jk.bbmodel"), StandardCharsets.UTF_8).contains("data:image/png"));
    }
    @Test void corruptSharedTextureCannotAcquireRendering() throws Exception {
        Fixture f = fixture(); f.resources.get("modelengine:textures/test_0.png")[20] ^= 1;
        assertThrows(IOException.class, () -> PackModelLibrary.load(reader(f), "ysm_02_jk", f.hash));
    }
    @Test void missingOrWrongPackVersionDoesNotLoadAnotherModel() throws Exception {
        Fixture f = fixture();
        assertThrows(IOException.class, () -> PackModelLibrary.load(reader(f), "ysm_01_jk", f.hash));
        assertThrows(IOException.class, () -> PackModelLibrary.load(reader(f), "ysm_02_jk", "0".repeat(64)));
        f.resources.remove("modelengine:textures/test_0.png");
        assertThrows(IOException.class, () -> PackModelLibrary.load(reader(f), "ysm_02_jk", f.hash));
    }
    @Test void resourceReferencesCannotChooseUrlsOrEscapeNamespaces() {
        for (String bad : List.of("https://server/texture.png", "file:/private.png", "test:../escape.png", "test:/absolute.png", "test:a//b.png"))
            assertThrows(IOException.class, () -> PackModelLibrary.resource(bad), bad);
        assertDoesNotThrow(() -> PackModelLibrary.resource("other_engine:textures/players/base.png"));
    }
    @Test void duplicateCatalogIdentitiesAreRejected() throws Exception {
        Fixture f = fixture(); var index = JsonParser.parseString(new String(f.resources.get(PackModelLibrary.INDEX), StandardCharsets.UTF_8)).getAsJsonObject();
        index.getAsJsonArray("models").add(index.getAsJsonArray("models").get(0).deepCopy());
        f.resources.put(PackModelLibrary.INDEX, index.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> PackModelLibrary.load(reader(f), "ysm_02_jk", f.hash));
    }
}
