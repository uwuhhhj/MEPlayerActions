package com.simmc.meplayeractions.client.model;

import com.simmc.meplayeractions.client.LocalAppearanceSettings;
import com.simmc.meplayeractions.client.LocalModelLibrary;
import com.simmc.meplayeractions.client.network.AssetTransfer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BuiltinYsmModelsTest {
    @TempDir Path temporary;
    private static final String WINE_ROOT = "/assets/meplayeractions/builtin/wine_fox/";

    @Test void catalogAddsOnlyTheThreeOfficialModelsAndKeepsExistingDefaults() throws Exception {
        assertEquals(List.of("openysm_default", "wine_fox_01_taisho_maid", "wine_fox_02_new_year",
                "wine_fox_03_astronaut"), BuiltinYsmModels.ids());
        var library = new LocalModelLibrary(temporary.resolve("models"));
        assertEquals(BuiltinYsmModels.ids(), library.models().stream().map(LocalModelLibrary.Entry::id).toList());
        for (var model : BuiltinYsmModels.models()) {
            assertTrue(LocalAppearanceSettings.isValidModelId(model.id()));
            assertFalse(model.label().isBlank());
        }
        assertEquals(new LocalAppearanceSettings(false, "openysm_default", 1, 0, 0, 0), LocalAppearanceSettings.defaults());
        assertEquals(AssetTransfer.hash(YsmFolderModel.bundledDefault()), library.load("openysm_default").hash());
        for (String id : List.of("ysm_01_jk", "ysm_02_jk", "wine_fox_04_student", "wine_fox/01_taisho_maid", "../01_taisho_maid"))
            assertThrows(IOException.class, () -> YsmFolderModel.bundledWithProfile(id, null), id);
    }

    @Test void taishoMaidDecodesRealGeometryAnimationsComponentsSkinsAndSounds() throws Exception {
        var loaded = decode(BuiltinYsmModels.TAISHO_MAID_ID, "Wine Fox（酒狐）", 490, 57, 36, 8, 10);
        assertEquals(List.of("skin", "skin_white"), loaded.profile().textures().stream().map(YsmModelProfile.TextureChoice::id).toList());
        assertEquals(List.of("car_idle", "car_run", "get_down", "otoko_wa_tsurai_yo", "zufolo_impazzito"),
                loaded.profile().soundResources().keySet().stream().toList());
        assertEquals("fp.arm", loaded.profile().components().stream().filter(component -> component.kind().equals("fp_arm"))
                .findFirst().orElseThrow().model().controllerFamily());
        var white = new LocalModelLibrary(temporary.resolve("models")).load(BuiltinYsmModels.TAISHO_MAID_ID, "skin_white");
        assertEquals("skin_white", white.profile().selectedTexture());
        assertNotEquals(loaded.hash(), white.hash()); assertEquals(loaded.model().cubeCount(), white.model().cubeCount());
        assertEquals("Get Down", loaded.profile().extraAnimations().get("extra3"));
    }

    @Test void newYearDecodesItsOwnAuthoredPlayerAndLegacyArrow() throws Exception {
        var loaded = decode(BuiltinYsmModels.NEW_YEAR_ID, "New Year Wine Fox（新春酒狐）", 342, 48, 14, 3, 2);
        assertEquals("是的", loaded.profile().extraAnimations().get("extra3"));
        assertTrue(loaded.profile().soundResources().isEmpty());
        var arrow = arrow(loaded);
        assertFalse(arrow.model().animations().isEmpty(), "The manifest explicitly references this original arrow animation");
    }

    @Test void astronautDecodesArmAssemblyAndKeepsBlankAuthorActionLabels() throws Exception {
        var loaded = decode(BuiltinYsmModels.ASTRONAUT_ID, "宇航员酒狐", 552, 108, 20, 3, 4);
        assertTrue(loaded.model().animations().containsAll(List.of("hold_mainhand:sword", "hold_offhand:shield", "swing:sword")));
        assertTrue(loaded.profile().extraAnimations().values().stream().allMatch(String::isEmpty));
        assertFalse(loaded.profile().properties().get("free").getAsBoolean());
        assertTrue(arrow(loaded).model().animations().isEmpty(), "Unreferenced arrow animation files must not be invented as manifest references");
    }

    private LocalModelLibrary.Loaded decode(String id, String name, int cubes, int clips, int armCubes,
                                            int componentCount, int authors) throws Exception {
        var loaded = new LocalModelLibrary(temporary.resolve("models")).load(id);
        assertEquals(cubes, loaded.model().cubeCount(), id); assertEquals(clips, loaded.model().animations().size(), id);
        assertTrue(loaded.profile().isYsm()); assertEquals("gui", loaded.previewAnimation());
        assertEquals(360, loaded.model().animationLengthTicks("gui"));
        assertEquals(65535, loaded.model().animationFormatVersion());
        for (String clip : loaded.model().animations()) assertTrue(loaded.model().animationFromPrimaryAssembly(clip), id + "/" + clip);
        assertEquals(name, loaded.profile().metadata().get("name").getAsString());
        assertEquals("CC BY-NC-SA 4.0", loaded.profile().metadata().getAsJsonObject("license").get("type").getAsString());
        assertEquals(authors, loaded.profile().metadata().getAsJsonArray("authors").size());
        assertFalse(loaded.profile().languages().getAsJsonObject("en_us").isEmpty());
        assertFalse(loaded.profile().languages().getAsJsonObject("zh_cn").isEmpty());
        assertEquals(8, loaded.profile().extraAnimations().size(), "These original manifests declare eight slots; labels can be blank");
        assertEquals(componentCount, loaded.profile().components().size(), id);
        assertEquals(armCubes, loaded.profile().components().stream().filter(component -> component.kind().equals("arm"))
                .findFirst().orElseThrow().model().cubeCount());
        assertEquals(53, arrow(loaded).model().cubeCount());
        for (var component : loaded.profile().components()) assertEquals(65535, component.model().animationFormatVersion());
        var vertices = new AnimationPlayer(loaded.model()).sample(6, List.of(new BbModel.Layer("gui", "gui", 0, 1, "LOOP", 0, 0)), 0, 0, Map.of());
        assertFalse(vertices.isEmpty(), id);
        assertTrue(vertices.stream().allMatch(vertex -> Float.isFinite(vertex.x()) && Float.isFinite(vertex.y()) && Float.isFinite(vertex.z())), id);
        return loaded;
    }

    private static YsmModelProfile.Component arrow(LocalModelLibrary.Loaded loaded) {
        var arrow = loaded.profile().components().stream().filter(component -> component.id().equals("minecraft:arrow")).findFirst().orElseThrow();
        assertEquals("projectile", arrow.kind()); assertEquals(List.of("minecraft:arrow"), arrow.matches());
        return arrow;
    }

    @Test void allSeventySevenOriginalResourceFilesRemainByteExactAtThePinnedRevision() throws Exception {
        Map<String, Integer> counts = new LinkedHashMap<>(); long bytes = 0;
        for (String line : UPSTREAM_FILES.strip().split("\n")) {
            String[] fields = line.split("\t"); assertEquals(4, fields.length);
            var model = BuiltinYsmModels.find(fields[0]).orElseThrow();
            try (var resource = BuiltinYsmModels.class.getResourceAsStream(model.resourceRoot() + fields[1])) {
                assertNotNull(resource, line); byte[] raw = resource.readAllBytes();
                assertEquals(Integer.parseInt(fields[3]), raw.length, line);
                assertEquals(fields[2], HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)), line);
                counts.merge(fields[0], 1, Integer::sum); bytes += raw.length;
            }
        }
        assertEquals(Map.of(BuiltinYsmModels.TAISHO_MAID_ID, 43, BuiltinYsmModels.NEW_YEAR_ID, 15, BuiltinYsmModels.ASTRONAUT_ID, 19), counts);
        assertEquals(4_973_579, bytes);
        try (var license = BuiltinYsmModels.class.getResourceAsStream(WINE_ROOT + "LICENSE.CC-BY-NC-SA-4.0.txt")) {
            assertNotNull(license); byte[] raw = license.readAllBytes(); assertEquals(20_852, raw.length);
            assertEquals("329d5952cc9edbeb8310d68327f4684f7fd0ccc76f27aedc2fa6563e27ae34a4",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
        }
    }

    // SHA-256 of original resource bytes at OpenYSM-Updated 0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85.
    private static final String UPSTREAM_FILES = """
            wine_fox_01_taisho_maid	animations/arrow.animation.json	7920d5f25a5f639cecfe5e980ea6bd35fa43f1747c0f4afd418ab66b8fcb3313	1238
            wine_fox_01_taisho_maid	animations/carryon.animation.json	5371aee783c07a9d441d3b9a88666c349e692c2461bd07037ecf274423ca376e	3798
            wine_fox_01_taisho_maid	animations/extra.animation.json	9933f5fe80d55f81b43fa5d87a144c91793ef538684d634303a29c611fea9ff9	504442
            wine_fox_01_taisho_maid	animations/fp.arm.animation.json	17b10e3b6c163069d61c4e26cf778b2d9d5f9fa3b90265866dd09426252e13a2	328
            wine_fox_01_taisho_maid	animations/main.animation.json	2fdb908b1b77845008dc8e8f882e71827af4f496d46972bf7418ed9a04c4362b	393471
            wine_fox_01_taisho_maid	animations/tac.animation.json	f9c6f7d7d4afc5422975f36994f6d8a3eb26dade1a6d8dab646f312a0cca3173	193417
            wine_fox_01_taisho_maid	animations/tlm.animation.json	f8d09b38d362cde2f018e28930c586ecce57a55e248015a874018c88ce685b10	321430
            wine_fox_01_taisho_maid	animations/vehicle/horse.animation.json	d20a3e7d99c4c255ce4fb2a56aa8f8595cbc6b22b21c93838ef869d8e375db96	1700
            wine_fox_01_taisho_maid	animations/vehicle/mule.animation.json	469312a4e3befccf2bb960bd7507bf0e7205b5100a4a5c1b1d147f226c96ad1d	1648
            wine_fox_01_taisho_maid	avatar/bfxm.jpg	41fb944b9d083ff69e1edc2ba69cf44d444f5645a4e1114026f1c9ad3ef6025f	5466
            wine_fox_01_taisho_maid	avatar/gsl.png	dbfee9f254d512bad28db7f7e23d631d4aa450cbe42a31d1e2ed3a756f16f4a1	17212
            wine_fox_01_taisho_maid	avatar/hl.png	80f61d49f385faf974b8b3e579b6b307b9046992f1d3ae32647e8676102d5fa3	5216
            wine_fox_01_taisho_maid	avatar/hys.png	6dda47283fff88a4ed4ec07509de852b473cc97170e560b71f10339c2ab14ecf	5773
            wine_fox_01_taisho_maid	avatar/lmg.png	0d05a6be0b1ffb32f6497265f7411b7741f18ae89a82e82a4982e39ba4183fa4	2984
            wine_fox_01_taisho_maid	avatar/maks.jpg	836e8432f93fd41b5e6d19eb97a89027faf78e1ffa3758b489592f7f4a86ec9c	4744
            wine_fox_01_taisho_maid	avatar/mrsy.png	1d5f4b48bda1349a1f5f7ceb5de981dfc4590f6a766574d3db5323290e4010eb	5984
            wine_fox_01_taisho_maid	avatar/nico.png	c4dc33bb154086be5c4ea25c3638257de58713a7ba5e198b05235e4252074f09	2366
            wine_fox_01_taisho_maid	avatar/wmdj.jpg	ffabb00e0bb3328749e1d5ac542a8d2f36ce8dba291469976068e36b6fb9b8c7	5154
            wine_fox_01_taisho_maid	avatar/wool.png	98eb2c163ca5c7beac09a80d892df1fd414ff102dd8f85c428d99ff15f85ea9d	6683
            wine_fox_01_taisho_maid	controller/wine_fox.animation_controllers.json	6cfffe227e58f254d5001cfc473ef32cafa9cabfd8c6be30ece71add58f5692d	1601
            wine_fox_01_taisho_maid	lang/en_us.json	417edfd996633b7a406e172cc5bda1ef21c4fd5b92587ca5ee61b3bf706f1933	2287
            wine_fox_01_taisho_maid	lang/zh_cn.json	27842b53991ef32ba06a4f987f6d0027662e7fa59531761a66aba5ce6866080f	2202
            wine_fox_01_taisho_maid	models/arm.json	50014b87b2cd05acda0e3edf426fe4d90decff9274ec25e57de87639cf0a5214	8008
            wine_fox_01_taisho_maid	models/arrow.json	13fd9a38830571238aa6a45338b188d9626229dbc1a73f81aefd04af53b36d5a	29883
            wine_fox_01_taisho_maid	models/boat.json	2c1632097fb60194852c3b128ce5405d3318f343c8e23a884c15a7568674f480	17425
            wine_fox_01_taisho_maid	models/foxcar.json	1d5c7bddee0fc876834d9e46d9e0e5e8106458d46cbf11e6adb6ac28bff36b1c	289697
            wine_fox_01_taisho_maid	models/main.json	a1525e7a88b4d77c7b0dc35116cf4fba6a3f32a288691d7f983b9ecf999f08ee	100384
            wine_fox_01_taisho_maid	models/minecart.json	03e79239cab5bf591eb17bc59dfa3120853a7f889bf4a6a56838dfe56006e140	73027
            wine_fox_01_taisho_maid	models/trident.json	b87fecf17e6dd1069b6d0cb7c1822f3d9c7166dfb296d9bd0ff29763396bdebd	4702
            wine_fox_01_taisho_maid	sounds/car_idle.ogg	906d2c14001168b1387c87623b0335c43dc2d49309f382f084f822966c32aa1a	76211
            wine_fox_01_taisho_maid	sounds/car_run.ogg	70ec38e95609d72c24ad5a7ca3a592b6da805e9952f03c86dc4902abe3fc8e23	166833
            wine_fox_01_taisho_maid	sounds/get_down.ogg	63dbf11be085953b3232710552f049df9465c1a15327cd083ecaa0f726cba86c	289223
            wine_fox_01_taisho_maid	sounds/otoko_wa_tsurai_yo.ogg	53dc18199056bc254a8e664483b4adbcf3317bee5d1182435323c6cc4960b191	298472
            wine_fox_01_taisho_maid	sounds/zufolo_impazzito.ogg	ee80cfe3f9ad70f2bd6b3b735d65c5c356c1119c67c8da4d812c838058ca6990	172286
            wine_fox_01_taisho_maid	textures/arrow.png	0d4435fe0620372d92279e07af771b1e96cdd5d6c3e1628a538336195d47eaaf	1212
            wine_fox_01_taisho_maid	textures/boat.png	447c0e2d1ca0f29099294b68fc1d61c2b39a51372692cd9126219486d095cf51	4300
            wine_fox_01_taisho_maid	textures/foxcar.png	f38fbe1bf62f74e2e94792f1707049d2b69e71a2ec6210302f75ed2606ad1f3a	32129
            wine_fox_01_taisho_maid	textures/foxcar_s.png	691cd3da973e556026365c384a76649534251a11ef5093f681d1040fd50d3810	11514
            wine_fox_01_taisho_maid	textures/minecart.png	71f80b4165c2e055362c500cb387061ae3afaf5a90aa74de012c98917afdb515	32463
            wine_fox_01_taisho_maid	textures/skin.png	77010882e37f727dd0cd18249485e94d702aedda206ef725fe3d7c657e72df8f	10475
            wine_fox_01_taisho_maid	textures/skin_white.png	9d6e8d421adcf4c9eb75a120e9ffc7ee55c8528ee1dd02a9f4e6f2339e72b430	9949
            wine_fox_01_taisho_maid	textures/trident.png	eac28c315c1e5c69b01af28b8bf505a24670e658f926ba9e258d7d5147678e82	486
            wine_fox_01_taisho_maid	ysm.json	13ad14adbf8ec96a32982beebd5d788f8109d7ad88dbca2f7fd38b35610f3cf4	5487
            wine_fox_02_new_year	animations/arrow.animation.json	bc6cff221fc2c751de765c6ffdbd24115bca062b5d19fa4f8f716fdbf6c27389	1122
            wine_fox_02_new_year	animations/carryon.animation.json	5371aee783c07a9d441d3b9a88666c349e692c2461bd07037ecf274423ca376e	3798
            wine_fox_02_new_year	animations/extra.animation.json	8fb05745cf26fded7ea3a2ab372fb179cb9c1fb5952899e9b958235181abcbc5	136038
            wine_fox_02_new_year	animations/main.animation.json	888fca8166766b5108c3c592b8441f0943c028412175861a431d294e9a8b7867	309790
            wine_fox_02_new_year	animations/tac.animation.json	f9c6f7d7d4afc5422975f36994f6d8a3eb26dade1a6d8dab646f312a0cca3173	193417
            wine_fox_02_new_year	avatar/hl.png	80f61d49f385faf974b8b3e579b6b307b9046992f1d3ae32647e8676102d5fa3	5216
            wine_fox_02_new_year	avatar/zb.avif	3454087a917e39176ad6c2aa64ea2db80a397b786d6dd91244dee6d34439715c	9947
            wine_fox_02_new_year	lang/en_us.json	497fde8cbbcb0ec6cf396a62d84839d60c6c64c7045f4c3c79040bb4b34772ea	778
            wine_fox_02_new_year	lang/zh_cn.json	b7087e6d9a884b9748b8f7fea92f285f4d53230a341a4aa59e66e1cec414b77e	783
            wine_fox_02_new_year	models/arm.json	b334e8ccd94fb630a48b732e8255b239e8c2102a4798aae8f4fe2415a1b3cec3	7998
            wine_fox_02_new_year	models/arrow.json	13fd9a38830571238aa6a45338b188d9626229dbc1a73f81aefd04af53b36d5a	29883
            wine_fox_02_new_year	models/main.json	38520531321177b20897237f3f64d8d0fb435a0c28a7dc14179b965e3e1fd077	191789
            wine_fox_02_new_year	textures/arrow.png	0d4435fe0620372d92279e07af771b1e96cdd5d6c3e1628a538336195d47eaaf	1212
            wine_fox_02_new_year	textures/skin.png	37f10b36cf7b4e9bf946ee59dc2aefae674da3b99e25446772085d3e73f66127	12661
            wine_fox_02_new_year	ysm.json	b7e61aea31fdd98f210fdb795989481fdb981db72776aa1904bf6a9b9e6c0b7d	1876
            wine_fox_03_astronaut	animations/arm.animation.json	a96091b4c5d62368f47ceacace1694ecf53ff4f9893cef371b4a70c057bdbbc4	188471
            wine_fox_03_astronaut	animations/arrow.animation.json	bc6cff221fc2c751de765c6ffdbd24115bca062b5d19fa4f8f716fdbf6c27389	1122
            wine_fox_03_astronaut	animations/carryon.animation.json	5371aee783c07a9d441d3b9a88666c349e692c2461bd07037ecf274423ca376e	3798
            wine_fox_03_astronaut	animations/extra.animation.json	b18894cbd4d70e31806a62d8bdb762926fd45951e68eed0119d4d529fd11dc38	69061
            wine_fox_03_astronaut	animations/main.animation.json	8ca4aa4c3b7b011a19ba0f6dba981ff12c872e5da81bafbf4276b43877f2e489	300897
            wine_fox_03_astronaut	animations/tac.animation.json	63ee720953376de7948caed9c303c6ca9ba2a054c6afb796e4cdce1fd4ba6859	183653
            wine_fox_03_astronaut	avatars/af.avif	6359c8dbb9f0f8c0d2e7723be2289cfced5e2ae39e08180badea913afcdb3614	4802
            wine_fox_03_astronaut	avatars/gsl.png	dbfee9f254d512bad28db7f7e23d631d4aa450cbe42a31d1e2ed3a756f16f4a1	17212
            wine_fox_03_astronaut	avatars/hl.png	03d609e8d022ca022796b1cf2683efc1a9cf22e111a78b47ee3424caffad8536	7056
            wine_fox_03_astronaut	avatars/hys.png	734a8f179cb3c19ee402c1720526e3a1e9c8c6fd93207ccc9c19a0f036a3342a	7385
            wine_fox_03_astronaut	controller/controller.json	3fe15544afa2febef510328e3dc61a682e1d2c9a1138aea259bd3f73855fc83f	7053
            wine_fox_03_astronaut	lang/en_us.json	78b69c2fa78f22e4df4101e2eed5776a0d58622c1efae46ace919ddf3b2d0dfc	952
            wine_fox_03_astronaut	lang/zh_cn.json	bbd7f9e4652992519157d2dfad712f409fe88134acdb3a7ee15b384ef505eaf2	938
            wine_fox_03_astronaut	models/arm.json	ceccd6e3fc442a8bea7ab30391a647636683b36f7435cb8b23360c438eeb955a	4321
            wine_fox_03_astronaut	models/arrow.json	13fd9a38830571238aa6a45338b188d9626229dbc1a73f81aefd04af53b36d5a	29883
            wine_fox_03_astronaut	models/main.json	33ce85097261700d03b727136484dc53fc0935a4a0b30c13163dd0d3848e253e	102628
            wine_fox_03_astronaut	textures/arrow.png	0d4435fe0620372d92279e07af771b1e96cdd5d6c3e1628a538336195d47eaaf	1212
            wine_fox_03_astronaut	textures/skin.png	028d5e613e9cc625f5737cee6383cb00bff21ae6edeb143fe331d8765766afab	11765
            wine_fox_03_astronaut	ysm.json	e9c279611e0dff1fad886a9e8f85651e9b5d2f54a42dccf400b407b8dbc721be	1752
            """;
}
