package com.simmc.meplayeractions.client.model;

import java.util.List;
import java.util.Optional;

/** Fixed private client choices; IDs and resource roots never name server-distributed packs. */
public final class BuiltinYsmModels {
    public static final String DEFAULT_ID = "openysm_default";
    public static final String TAISHO_MAID_ID = "wine_fox_01_taisho_maid";
    public static final String NEW_YEAR_ID = "wine_fox_02_new_year";
    public static final String ASTRONAUT_ID = "wine_fox_03_astronaut";
    private static final String ROOT = "/assets/meplayeractions/builtin/";
    private static final List<String> LANGUAGES = List.of("lang/en_us.json", "lang/zh_cn.json");
    private static final List<Model> MODELS = List.of(
            new Model(DEFAULT_ID, "默认模型 · OpenYSM", ROOT + "openysm_default/", LANGUAGES, List.of()),
            new Model(TAISHO_MAID_ID, "酒狐 · 大正女仆", ROOT + "wine_fox/01_taisho_maid/", LANGUAGES,
                    List.of("sounds/car_idle.ogg", "sounds/car_run.ogg", "sounds/get_down.ogg",
                            "sounds/otoko_wa_tsurai_yo.ogg", "sounds/zufolo_impazzito.ogg")),
            new Model(NEW_YEAR_ID, "酒狐 · 新春", ROOT + "wine_fox/02_new_year/", LANGUAGES, List.of()),
            new Model(ASTRONAUT_ID, "酒狐 · 宇航员", ROOT + "wine_fox/03_astronaut/", LANGUAGES, List.of()));
    private static final List<String> IDS = MODELS.stream().map(Model::id).toList();

    private BuiltinYsmModels() { }

    public record Model(String id, String label, String resourceRoot, List<String> languages, List<String> sounds) {
        public Model { languages = List.copyOf(languages); sounds = List.copyOf(sounds); }
    }

    public static List<Model> models() { return MODELS; }
    /** Exact accepted IDs, including the existing default first for saved-choice compatibility. */
    public static List<String> ids() { return IDS; }
    public static boolean contains(String id) { return id != null && IDS.contains(id); }
    public static Optional<Model> find(String id) { return MODELS.stream().filter(model -> model.id().equals(id)).findFirst(); }
}
