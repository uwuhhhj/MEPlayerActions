# Third-party notices

## OpenYSM default player model

The independent client includes the `builtin/default` player model from
[IzumiiKonata/OpenYSM-Updated](https://github.com/IzumiiKonata/OpenYSM-Updated),
revision `0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85`.

Included original assets: `ysm.json`, `models/main.json`, `models/arm.json`,
vanilla projectile/vehicle geometry (`arrow`, `trident`, `fishing_bobber`,
`firework_rocket`, `boat`, `chest_boat`, `minecart`) and matching textures,
`animations/main.animation.json`, `extra.animation.json`, `arm.animation.json`,
`fp.arm.animation.json`, `arrow.animation.json`, and `boat.animation.json`,
`textures/default.png`, `textures/blue.png`, GUI background/foreground PNGs,
and `lang/en_us.json` / `lang/zh_cn.json`. They are kept in
`assets/meplayeractions/builtin/openysm_default/`. The imported primary animation
library contains 115 clips; arm, first-person-arm, projectile and vehicle
components total nine. Component animation clips are counted separately.
Third-party mod animation files referenced by this default model's original
manifest are not included or integrated. The separately licensed Wine Fox
models below are additional choices, not replacements for this CC0 default.

The original `ysm.json` identifies this default model's license as **CC0**
(`metadata.license.type: "CC 0"`). Original credits are preserved in that file:
哥斯拉 (model), 端木一动不动 (animation), 甜粽子 (animation), 星屑海螺 (animation),
蓝玫瑰 (texture/model), and 艾雷克亚 (additional spell animation).
The CC0 public-domain dedication is documented at
<https://creativecommons.org/publicdomain/zero/1.0/>.

The client independently imports the cube geometry, vanilla animation families,
selected base PNG, author configuration metadata, language strings and local
components. Bedrock coordinates are mapped once; authored typed expressions,
null defaults, timeline scripts, loops, controller definitions, interpolation
and per-instance physics remain available to the independent runtime. Local
skins and authored form variables are saved by model ID and are private to the
user's client. Asset inclusion does not certify every OpenYSM feature or any
render/effect path that has not completed validation. Encrypted `.ysm`, the
OpenYSM network/cache protocol, mesh skinning, PBR materials and third-party mod
integrations are outside the current implementation.

The model assets' **CC0** declaration is separate from the reference software's
**MIT** license. The unmodified software license is bundled as
[LICENSE.OpenYSM.txt](client/src/main/resources/assets/meplayeractions/builtin/openysm_default/LICENSE.OpenYSM.txt)
and the corresponding resource notice is
[NOTICE.md](client/src/main/resources/assets/meplayeractions/builtin/openysm_default/NOTICE.md).

## OpenYSM Wine Fox model assets (CC BY-NC-SA 4.0)

MEPlayerActions 0.4.3 includes the first three Wine Fox model folders from
[IzumiiKonata/OpenYSM-Updated](https://github.com/IzumiiKonata/OpenYSM-Updated)
at revision `0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85`. These are source assets already
collected by that reference project, not newly authored MPA models. All 77
original files (4,973,579 bytes) are copied without changing their contents,
including the `ysm.json` authors, license declarations and prior modification
credits, geometry, animation/controller JSON, textures, avatars and supplied
sounds. Runtime import and Minecraft API adaptation are separate from those
unchanged assets. Inclusion is not a claim that every animation family,
material or external-mod integration is supported or has passed game testing.

| Client model ID | Original folder / title | Preserved author credits and roles |
| --- | --- | --- |
| `wine_fox_01_taisho_maid` | [01_taisho_maid/ysm.json](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/resources/assets/yes_steve_model/builtin/wine_fox/01_taisho_maid/ysm.json), Wine Fox（酒狐） | 完美冻结（模型原作）；星屑海螺（动画原作）；哥斯拉（映素团队）（模型修改）；白帆小喵（新版材质）；蓝玫瑰（UI 材质/贴图）；墨染逝羽（面具模型）；Maks怜悯（载具模型）；浅陌菌（模型修改）；祸御神（动画修改）；羊毛（音效/动画修改） |
| `wine_fox_02_new_year` | [02_new_year/ysm.json](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/resources/assets/yes_steve_model/builtin/wine_fox/02_new_year/ysm.json), New Year Wine Fox（新春酒狐） | 就叫纸板（模型）；星屑海螺（动画） |
| `wine_fox_03_astronaut` | [03_astronaut/ysm.json](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/resources/assets/yes_steve_model/builtin/wine_fox/03_astronaut/ysm.json), 宇航员酒狐 | 完全冻结（模型）；星屑海螺（动画）；哥斯拉（映素团队）（模型修改）；祸御神（GUI 动画） |

The converted ordinary-folder representation preserves the source's authored
explicit duration, even where later keys or events exist, and its infinite
duration semantics when `animation_length` is absent. It keeps separate,
author-ordered timeline programs. MPA applies bounded input validation: for
converted internal-format `65535` YSM, each timeline event permits at most 64
programs and 32 KiB of aggregate UTF-8 script text; finite durations and
key/event times are bounded to 10,000 seconds. Ordinary BBModel's 32-program /
3,600-second limits remain in place, as do the 8 MiB input and other resource,
geometry and execution budgets. These importer adaptations do not modify the
77 original Wine Fox resource files or imply new runtime acceptance results.
Converted internal-format `65535` cubes explicitly marked `ysm_signed_cube`
retain signed endpoints, original faces, UVs and winding, following
[YSMFolderDeserializer's signed bounds and corners](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/resource/YSMFolderDeserializer.java#L536-L645);
ordinary BBModel inverted-bound rejection and finite size/resource budgets
remain unchanged.

The original folders are retained at
`assets/meplayeractions/builtin/wine_fox/01_taisho_maid/`,
`assets/meplayeractions/builtin/wine_fox/02_new_year/` and
`assets/meplayeractions/builtin/wine_fox/03_astronaut/` in the client JAR.
Each original manifest declares `metadata.license.type: "CC BY-NC-SA 4.0"`.
The `properties.free` field is a model property and does not replace that
license. The full license is included as
[docs/licenses/CC-BY-NC-SA-4.0.txt](docs/licenses/CC-BY-NC-SA-4.0.txt) and in
the client JAR at
`assets/meplayeractions/builtin/wine_fox/LICENSE.CC-BY-NC-SA-4.0.txt`.
The canonical license and legal code are available from
[Creative Commons](https://creativecommons.org/licenses/by-nc-sa/4.0/)
and [its full legal code](https://creativecommons.org/licenses/by-nc-sa/4.0/legalcode.en).

These model assets may be used and redistributed under **CC BY-NC-SA 4.0**:
retain the supplied author and license credits, give source and license links,
identify modifications, and use them for noncommercial purposes. Distributing
adaptations requires the same license elements or a compatible license, and
must not add restrictions that prevent recipients exercising licensed rights.
The license supplies the warranty disclaimer; it does not grant endorsement.
This scope applies to the Wine Fox assets above. It does not relicense MPA's
software, the MIT GUI source/assets, the separately declared CC0 default model,
or the server's `ysm_01_jk` / `ysm_02_jk` assets.

No saved private model selection is replaced: `openysm_default` remains the
factory selection, and these three models are additional choices in CLIENT.
They do not add server models to the client or bypass server authorization.

## OpenYSM vanilla hand animation adaptations (MIT)

The native hand animation path adapts the following MIT-licensed portions of
[IzumiiKonata/OpenYSM-Updated](https://github.com/IzumiiKonata/OpenYSM-Updated)
at revision `0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85`. The port retains authored loop
selection and the source's prior-stack comparison and duplicate-request rules;
MPA supplies the Minecraft 1.21.11 entity/stack state adapter and
body/first-person integration. This is a scoped source adaptation, not a copy of the complete
OpenYSM runtime, external-mod compatibility layer or network protocol.

| Reference source | MPA adaptation |
| --- | --- |
| [AnimationFormatValidator.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/animation/AnimationFormatValidator.java) | `client/AnimationFormatValidator`: internal-format / primary-assembly validation |
| [IAnimationPredicate.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/animation/IAnimationPredicate.java), `playAnimationWithValid` | `VanillaYsmAnimations`: authored loop for accepted formats, predicate loop fallback for old non-primary animations |
| [MainHandHoldPredicate.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/animation/predicate/MainHandHoldPredicate.java), [OffHandHoldPredicate.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/animation/predicate/OffHandHoldPredicate.java) | Main/off-hand hold selection and prior damaged-stack comparison in `VanillaYsmAnimations.HandPlayback` |
| [AnimationControllerInstance.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/core/controller/AnimationControllerInstance.java), `setAnimation` | Retaining the clock for the same animation/loop request in shared `HandPlayback` |

[YSMFolderDeserializer.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/resource/YSMFolderDeserializer.java) is also
consulted for the ordinary-folder internal format value `65535`; the manifest's
`spec:2` is not an internal animation format version. As in the source
[LivingEntityFrameState](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/entity/LivingEntityFrameState.java), MPA's native adapter
tracks the actual prior `ItemStack` reference and uses Minecraft's
`ItemStack.areEqual` for full-stack comparisons including count/components.
The prior stack's damage check reads its current state, so the first durability
change follows the same source rule. This is not an immutable stack copy.
Tracking is bounded per hand and cleared on world/instance reset. Other native
entity observations and per-instance component state come from MPA's
Minecraft adapter.

[OpenYSMDev/ModernYSM](https://github.com/OpenYSMDev/ModernYSM), the discontinued
`1.20.1-forge` branch at revision
[`a515d44686af77155a311b2a592327ca5d45a658`](https://github.com/OpenYSMDev/ModernYSM/tree/a515d44686af77155a311b2a592327ca5d45a658),
is a secondary source cross-check; it is not the port's primary revision or a
runtime dependency. Its matching
[AnimationFormatValidator](https://github.com/OpenYSMDev/ModernYSM/blob/a515d44686af77155a311b2a592327ca5d45a658/common/src/main/java/com/elfmcys/yesstevemodel/client/animation/AnimationFormatValidator.java)
and [IAnimationPredicate](https://github.com/OpenYSMDev/ModernYSM/blob/a515d44686af77155a311b2a592327ca5d45a658/common/src/main/java/com/elfmcys/yesstevemodel/client/animation/IAnimationPredicate.java)
are reference links only.

The complete MIT notice reproduced below and the bundled
`LICENSE.OpenYSM.txt` are retained. This software attribution does not change
the CC0 default model or the separate CC BY-NC-SA 4.0 Wine Fox asset licenses.

## OpenYSM legacy arrow identity reference (MIT)

The ordinary-folder importer maps a legacy `files.arrow` entry only to
`minecraft:arrow`, following the original model-type `3` identity in
[YSMBinaryDeserializer](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/resource/YSMBinaryDeserializer.java)
and the exact entity-type lookup in
[GeckoProjectileEntity](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/entity/GeckoProjectileEntity.java).
These MIT source references use the same pinned revision
`0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85`. Modern `projectiles` definitions
have priority, and no `spectral_arrow` alias is invented. This is a bounded
folder compatibility mapping; it does not import or support the reference's
complete binary YSM deserializer. The original Wine Fox resource bytes and
asset licenses remain unchanged.

## OpenYSM GUI source adaptations and assets (MIT)

MEPlayerActions 0.4.3 adapts portions of the gallery and classic animation
roulette from [IzumiiKonata/OpenYSM-Updated](https://github.com/IzumiiKonata/OpenYSM-Updated)
at revision `0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85`, under the repository's
[MIT software license](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/LICENSE.txt).
The port targets Minecraft 1.21.11 GUI APIs and MPA's own model/runtime bindings;
it does not port OpenYSM's distribution protocol or certify complete feature parity.

Adapted source portions:

| Reference source | MPA adaptation |
| --- | --- |
| [PlayerModelScreen.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/PlayerModelScreen.java) | Direct gallery layout and CLIENT / SERVER source presentation in `PlayerModelScreen` / `LocalAppearanceScreen` |
| [ModelButton.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/button/ModelButton.java), [IconButton.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/button/IconButton.java), [PackIconButton.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/button/PackIconButton.java) | Gallery cards, source-group cards and GUI icon rendering |
| [AnimationRouletteScreen.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/AnimationRouletteScreen.java), [RadialSliceRenderState.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/RadialSliceRenderState.java) | Classic polygon slices, paths, pages and inline author forms in `AnimationWheelScreen` / `RadialSliceRenderState` |
| [FlatColorButton.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/button/FlatColorButton.java), [ConfigCheckBox.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/button/ConfigCheckBox.java), [AnimationSlider.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/button/AnimationSlider.java) | MPA flat/check/range widgets and author-script callbacks |

The GUI preview camera and scene split also reference
[PlayerModelScreen.renderModelPreview](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/PlayerModelScreen.java#L549),
[ModelButton](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/button/ModelButton.java#L181),
[ModelPreviewRenderer.submitLivingEntityPreview](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/renderer/ModelPreviewRenderer.java#L365)
and [PlayerPreviewEntity](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/entity/PlayerPreviewEntity.java)
at the same MIT revision. MPA's `NativeGuiPreviewCamera`, `PreviewScene` and
`ModelPreview` adapt the actual-player inventory framing (size 70 / offset
0.0625) separately from the standing dummy-card framing (size 30 / disabled
rotation offset 5.5). The gallery OWNER scene displays the currently used appearance, while card
selection remains a draft until applied. OWNER samples native pose and typed
queries in third-person inventory mode; CARD submits only the author's preview
clip to `player.cap`. The source card is 90 pixels high, with its preview
submitted at nominal height 76 and cropped to height 70. Author background/foreground decorations and fixed-card
rotation apply to CARD. Each scene has independent animation/controller/physics
state while sharing an asset atlas; GUI sampling does not emit world effects.
Ordinary BBModel retains its earlier fit behavior. This does not port the entire upstream GUI renderer or GUI-held-item
geometry, and does not alter the 77 original Wine Fox resources or their
separate asset license.

The shared author INITIAL transform also follows
[IGeoRenderer.renderEarly](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/geo/IGeoRenderer.java#L40),
[YSMFolderDeserializer property defaults](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/resource/YSMFolderDeserializer.java#L191)
and [GeoReplacedEntityRenderer's body offset](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/geo/GeoReplacedEntityRenderer.java#L155)
at the same MIT revision. MPA's `model.YsmRenderScale` maps source
`height_scale` to X/Z and `width_scale` to Y, defaulting both to 0.7. The body
matrix appends `T(0,0.01,0)` before `S`, so a vertex is scaled before receiving
the offset; native player and user transforms stay outside this block. The
private YSM player BODY and GUI share this source transform, with held items
and equipment inheriting the body parent. Server/plain BBModel keeps identity
author scaling. First-person arms, vehicles and projectiles retain their
separate renderer paths and do not receive this BODY transform.

MPA's `ui.NativeGuiRenderBackend` is its Minecraft 1.21.11 / Fabric API adapter
for `SpecialGuiElementRegistry` and `SpecialGuiElementRenderer`. It submits
YSM previews to the native offscreen color/depth path (RGBA8 / DEPTH32), using
the official `position_tex_color` shader, per-corner depth, LEQUAL depth testing
and depth writes. Each submitted preview's attachment/render evidence is filled
by actual renderer execution; preparing a mesh is not a GPU execution result.
Ordinary BBModel retains its earlier 2D branch. Central 0.4.3 validation has
confirmed the author INITIAL transform and actual RGBA8 / DEPTH32, LEQUAL and
depth-write execution. Visual review confirmed the default model's eye whites
and complete head, plus the three Wine Fox model views. This does not guarantee
arbitrary intersecting translucent-face ordering: translucent fragments still
blend and write depth in submission order. The original 77 Wine Fox resource
bytes, source attribution and CC BY-NC-SA 4.0 declarations remain unchanged.

The unmodified GUI PNG assets `roulette.png`, `icon.png`, `settings.png` and
`default_pack_icon.png` are copied from
`common/src/main/resources/assets/yes_steve_model/texture/` at revision
`0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85` into
`client/src/main/resources/assets/meplayeractions/textures/gui/` for this MIT GUI
source port. `roulette.png` has SHA-256
`f9750bb56eef72d11b8e342952e9518ab077916461cf3f79c37738cae108b4a3`.
These GUI assets follow the repository's **MIT** notice; they are distinct from
the `builtin/default` model assets' separate **CC0** declaration. No server
example or wine-fox model is covered by this GUI attribution.

The complete, unmodified MIT notice below and bundled `LICENSE.OpenYSM.txt`
are retained for these adaptations and assets.

The reference repository's software license is reproduced in the bundled
`LICENSE.OpenYSM.txt`:

The MIT License (MIT)

Copyright (c) 2026 OpenYSM

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
