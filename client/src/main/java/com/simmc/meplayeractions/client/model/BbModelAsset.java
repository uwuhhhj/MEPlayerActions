package com.simmc.meplayeractions.client.model;

import com.simmc.meplayeractions.client.network.AssetTransfer;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/** Source-format interpretation is independent of network authorization, cache identity and action ownership. */
public final class BbModelAsset {
    private BbModelAsset() { }

    public record Decoded(BbModel model, String previewAnimation, YsmModelProfile profile) {
        public Decoded {
            Objects.requireNonNull(model);
            Objects.requireNonNull(previewAnimation);
            Objects.requireNonNull(profile);
        }
    }

    /** Self-contained server/cache/pack input; its original bytes remain the caller's wire and disk identity. */
    public static Decoded read(byte[] raw) throws IOException {
        if (raw == null || raw.length == 0 || raw.length > AssetTransfer.MAX_RAW)
            throw new IOException("BBModel 资产大小不能超过 8 MiB");
        var imported = NativeBbModel.read(raw, Map.of("model.bbmodel", raw), null);
        return new Decoded(BbModel.parseLocal(imported.raw()), imported.previewAnimation(), imported.profile());
    }
}
