package com.simmc.meplayeractions.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.simmc.meplayeractions.client.network.ServerModelCatalogSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ServerDisguisePreferencesTest {
    @TempDir Path directory;
    private static ServerDisguisePreferences example() {
        return ServerDisguisePreferences.fromFields("0.8","","true","8","10","2","slowness:1");
    }
    @Test void unsetFieldsInheritServerDefaultsAndAnExplicitExampleUsesOnlyExistingArguments() {
        assertEquals("meplayeractions disguise ysm_01_jk",ServerDisguisePreferences.defaults().command("ysm_01_jk"));
        assertEquals("meplayeractions disguise ysm_02_jk scale=0.8 show-self=true view-distance=8 max-viewers=10 delay=2 effect=slowness:1",
                example().command("ysm_02_jk"));
        assertEquals(example(),ServerDisguisePreferences.fromJson(example().toJson()));
        assertEquals(List.of(),ServerDisguisePreferences.fromFields("","","","","","","none").arguments());
    }
    @Test void commandAssemblyRejectsAdditionalCommandsAndEffectsOutsideTheServerContract() {
        for(String id:List.of("ysm_01_jk scale=8","ysm_01_jk\nstop","../model","ysm_01_jk;op"))
            assertThrows(IllegalArgumentException.class,()->example().command(id));
        for(String effect:List.of("slowness:1 show-self=false","slowness:0","slowness:257","slowness:1:0","speed:1","slowness:1:86401"))
            assertThrows(IllegalArgumentException.class,()->ServerDisguisePreferences.fromFields("","","","","","",effect));
        assertThrows(IllegalArgumentException.class,()->ServerDisguisePreferences.fromFields("NaN","","","","","",""));
        assertThrows(IllegalArgumentException.class,()->ServerDisguisePreferences.fromFields("","yes","","","","",""));
        assertThrows(IllegalArgumentException.class,()->ServerDisguisePreferences.fromFields("","","","","1.5","",""));
    }
    @Test void profilesPersistByServerModelWithoutEnablingPrivateAppearanceOrReplacingOtherChoices() {
        Path path=directory.resolve("options.json");ClientOptions options=new ClientOptions(path);
        options.toggleFavorite("openysm_default");
        assertTrue(options.updateServerDisguisePreferences("ysm_02_jk",example()));
        ClientOptions loaded=new ClientOptions(path);
        assertEquals(example(),loaded.serverDisguisePreferences("ysm_02_jk"));
        assertTrue(loaded.serverDisguisePreferences("ysm_01_jk").isDefault());
        assertFalse(loaded.localAppearance().enabled());assertFalse(loaded.privateSyncEnabled);
        assertTrue(loaded.isFavorite("openysm_default"));
        assertTrue(loaded.resetServerDisguisePreferences("ysm_02_jk"));
        assertTrue(new ClientOptions(path).serverDisguisePreferences("ysm_02_jk").isDefault());
    }
    @Test void oneMalformedProfileDoesNotDiscardValidProfilesOrPrivateOptions() throws Exception {
        JsonObject json=new JsonObject();json.addProperty("enabled",false);JsonObject profiles=new JsonObject();
        profiles.add("valid",example().toJson());JsonObject malformed=new JsonObject();malformed.addProperty("showSelf","false");
        profiles.add("bad",malformed);profiles.add("unsafe id",example().toJson());json.add("serverDisguiseProfiles",profiles);
        Path path=directory.resolve("options.json");Files.writeString(path,json.toString());
        ClientOptions options=new ClientOptions(path);assertFalse(options.enabled);
        assertEquals(example(),options.serverDisguisePreferences("valid"));
        assertTrue(options.serverDisguisePreferences("bad").isDefault());
        assertTrue(options.serverDisguisePreferences("unsafe id").isDefault());
    }
    @Test void capacityAndIoFailuresLeaveThePreviousDraftAndUnrelatedOptionsIntact() throws Exception {
        ClientOptions options=new ClientOptions(directory.resolve("options.json"));
        for(int index=0;index<ClientOptions.MAX_SERVER_DISGUISE_PROFILES;index++)assertTrue(options.updateServerDisguisePreferences("model_"+index,example()));
        assertFalse(options.updateServerDisguisePreferences("overflow",example()));
        assertTrue(options.updateServerDisguisePreferences("model_0",ServerDisguisePreferences.defaults()));
        assertTrue(options.updateServerDisguisePreferences("overflow",example()));
        Path blocked=directory.resolve("blocked");Files.writeString(blocked,"keep");
        ClientOptions failure=new ClientOptions(blocked.resolve("options.json"));failure.showSelf=false;
        assertFalse(failure.updateServerDisguisePreferences("ysm_01_jk",example()));
        assertTrue(failure.serverDisguisePreferences("ysm_01_jk").isDefault());assertFalse(failure.showSelf);
        assertEquals("keep",Files.readString(blocked));
    }
    @Test void savedParametersStillRequireCurrentCatalogueAuthorityAndRespectTheSharedCommandCooldown() {
        ServerModelCatalogSnapshot catalogue=new ServerModelCatalogSnapshot();JsonObject chunk=new JsonObject();
        chunk.addProperty("revision",1);chunk.addProperty("index",0);chunk.addProperty("count",1);
        chunk.addProperty("canDisguise",true);chunk.addProperty("truncated",false);JsonArray models=new JsonArray();
        JsonObject entry=new JsonObject();entry.addProperty("id","ysm_02_jk");entry.addProperty("label","model");models.add(entry);chunk.add("models",models);
        assertTrue(catalogue.accept(chunk));
        assertTrue(catalogue.command("ysm_02_jk",false,1,example()).isEmpty());
        assertTrue(catalogue.command("unknown",true,1,example()).isEmpty());
        assertEquals(example().command("ysm_02_jk"),catalogue.command("ysm_02_jk",true,1,example()).orElseThrow());
        assertTrue(catalogue.command("ysm_02_jk",true,2,ServerDisguisePreferences.defaults()).isEmpty());
        assertTrue(catalogue.command("ysm_02_jk",true,ServerModelCatalogSnapshot.COMMAND_INTERVAL+1,ServerDisguisePreferences.defaults()).isPresent());
        catalogue.reset();assertTrue(catalogue.command("ysm_02_jk",true,Long.MAX_VALUE,example()).isEmpty());
    }
    @Test void serverSuspensionSurvivesAClientRestartWithoutErasingTheSavedPrivateModel() {
        Path path=directory.resolve("paused.json");ClientOptions options=new ClientOptions(path);
        var saved=new LocalAppearanceSettings(true,"openysm_default",1.2f,0,0,0);
        options.setLocalAppearance(saved);assertTrue(options.updatePrivateAppearancePaused(true));
        ClientOptions restarted=new ClientOptions(path);assertTrue(restarted.privateAppearancePaused);
        assertEquals(saved,restarted.localAppearance());
        assertTrue(restarted.updatePrivateAppearancePaused(false));assertFalse(new ClientOptions(path).privateAppearancePaused);
    }
}
