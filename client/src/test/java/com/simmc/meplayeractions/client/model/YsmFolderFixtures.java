package com.simmc.meplayeractions.client.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Copies the complete bundled vanilla fixture, without any third-party mod animation families. */
public final class YsmFolderFixtures {
    private YsmFolderFixtures() { }
    public static Path copyDefault(Path folder) throws IOException {
        for (String asset : List.of("ysm.json", "models/main.json", "models/arm.json", "models/arrow.json",
                "models/boat.json", "models/chest_boat.json", "models/firework_rocket.json", "models/fishing_bobber.json",
                "models/minecart.json", "models/trident.json", "animations/main.animation.json", "animations/extra.animation.json",
                "animations/arm.animation.json", "animations/fp.arm.animation.json", "animations/arrow.animation.json",
                "animations/boat.animation.json", "textures/default.png", "textures/blue.png", "textures/arrow.png",
                "textures/boat.png", "textures/firework_rocket.png", "textures/fishing_bobber.png", "textures/minecart.png",
                "textures/trident.png", "textures/gui/background.png", "textures/gui/foreground.png", "lang/en_us.json", "lang/zh_cn.json")) {
            Path target = folder.resolve(asset); Files.createDirectories(target.getParent());
            try (var input = YsmFolderModel.class.getResourceAsStream("/assets/meplayeractions/builtin/openysm_default/" + asset)) {
                if (input == null) throw new IOException("Missing vanilla fixture: " + asset);
                Files.write(target, input.readAllBytes());
            }
        }
        return folder;
    }
}
