# Third-party notices

This document identifies the third-party code and resources used by
MEPlayerActions, their pinned sources, host adaptations and licenses.
Current functionality is described in [YSM compatibility](docs/YSM_COMPATIBILITY.md).

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
skins and authored form variables are saved by model ID. Private appearance is
local by default; it can be shared with authorized mod viewers only after
explicit client opt-in and server negotiation/permissions on MPA's separate
`meplayeractions:private` protocol 1. Server disguises and private appearances
are mutually exclusive. Asset inclusion does not certify every OpenYSM
feature or any render/effect path that has not completed validation. Public,
self-contained native `.ysm` versions 1–32, ZIP and spec 2 folders are supported
within the documented geometry/resource budgets. The OpenYSM network/cache
protocol, mesh skinning, PBR materials and third-party mod/cloud integrations
remain outside this scope. See [YSM compatibility](docs/YSM_COMPATIBILITY.md).

The model assets' **CC0** declaration is separate from the reference software's
**MIT** license. The unmodified software license is bundled as
[LICENSE.OpenYSM.txt](client/src/main/resources/assets/meplayeractions/builtin/openysm_default/LICENSE.OpenYSM.txt)
and the corresponding resource notice is
[NOTICE.md](client/src/main/resources/assets/meplayeractions/builtin/openysm_default/NOTICE.md).

## OpenYSM original Alex and Steve model assets (CC0)

MEPlayerActions also includes the original `misc/1_alex` and
`misc/2_steve` folders from OpenYSM-Updated revision
`0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85`. Each original `ysm.json` declares
`metadata.license.type: "CC 0"` and preserves the credits 哥斯拉 (model),
端木一动不动 (animation), and 甜粽子 (animation), including supplied contact and
avatar information.

| Client model ID | Original manifest | Unchanged resources in the client JAR |
| --- | --- | --- |
| `openysm_alex` | [misc/1_alex/ysm.json](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/resources/assets/yes_steve_model/builtin/misc/1_alex/ysm.json) | `assets/meplayeractions/builtin/misc/1_alex/`: 11 original files, 338,197 bytes |
| `openysm_steve` | [misc/2_steve/ysm.json](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/resources/assets/yes_steve_model/builtin/misc/2_steve/ysm.json) | `assets/meplayeractions/builtin/misc/2_steve/`: 11 original files, 341,194 bytes |

The original manifest, main/arm geometry, animation files, selected base
texture, language files and author avatars are copied without changing their
bytes. Runtime adaptation is separate from these resources; included external
mod animation files do not establish external-mod support. These additional
CC0 choices do not replace `openysm_default` or any saved selection and do not
change the separate Wine Fox licenses. The dedication is
[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/).

## Sparkle-Morpher source adaptations (MIT)

The native format, author configuration and vanilla runtime adapt
selected portions of
[sdf123098/Sparkle-Morpher](https://github.com/sdf123098/Sparkle-Morpher/tree/b1230a431900a286d2cca198072df7fb43c490b4)
at revision `b1230a431900a286d2cca198072df7fb43c490b4`. The pinned repository's
software license is MIT, copyright 2026 OpenYSM. Its original
[LICENSE.txt](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/LICENSE.txt)
is retained without modification as
[sparkle-morpher-MIT.txt](client/src/main/resources/assets/meplayeractions/licenses/sparkle-morpher-MIT.txt).

| Reference source at the pinned revision | MPA adaptation |
| --- | --- |
| [YSMBinaryDeserializer](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/resource/YSMBinaryDeserializer.java), [YSMFolderDeserializer](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/resource/YSMFolderDeserializer.java), [YsmGeometryParsing](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/resource/YsmGeometryParsing.java) | Public self-contained native format loading and bounded geometry/folder interpretation; MPA validates local paths, sizes and resource budgets |
| [MolangOption](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/core/gui/molang/MolangOption.java), [ModelSettingsScreen](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/core/gui/ModelSettingsScreen.java), [RangedSliderWidget](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/client/gui/button/RangedSliderWidget.java) | Author checkbox/range/radio forms, scripts, types/scopes and step semantics; MPA supplies its own explicit save, atomic profile update and Minecraft 1.21.11 UI adapter |
| [YSMBinding](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/client/animation/molang/YSMBinding.java), [MovementQuery](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/geckolib3/util/MovementQuery.java), [ControllerActionResolver](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/client/animation/ControllerActionResolver.java) | Mature vanilla entity observations, movement/controller selection and authored expression semantics in MPA's bounded runtime |
| [RoamingStruct](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/client/animation/molang/struct/RoamingStruct.java), [LocalModelSettingsStore](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/common/src/main/java/com/micaftic/morpher/util/LocalModelSettingsStore.java) | Numeric roaming author state with 64-variable / 32-character-name bounds and dirty-change persistence; MPA reuses per-model profile variables and its separately authorized private protocol, while instance physics remains local |

These are scoped adaptations. They do not import the reference cloud service,
optional other-mod compatibility or its network protocol, and do not relicense
the separately licensed model assets. Implemented capabilities and limitations
are recorded in [YSM compatibility](docs/YSM_COMPATIBILITY.md).

## Native BBModel and YSM runtime adaptations (MIT)

The native BBModel importer retains the complete applicable source chain from
Sparkle-Morpher revision `b1230a431900a286d2cca198072df7fb43c490b4` in
[model/nativebbmodel](client/src/main/java/com/simmc/meplayeractions/client/model/nativebbmodel/).
It includes `BBAnimation`, `BBAnimationController`, `BBCollection`,
`BBDisplaySettings`, `BBElement`, `BBGroup`, `BBModelFile`, `BBModelParser`,
`BBOutlinerNode`, `BbRotationCompat`, `BBTexture`, `BBToRawConverter`,
`GsonTypeByField`, `ImportedActionPresetInstaller`, `ImportedHumanoidNormalizer`,
`LocatorInference` and `ZipModelSniffer`, plus the source `Interpolations` and
`MathHelper` utilities. Source packages and the existing `RawYsmModel` type are
mapped to MPA; `BbImportHost` supplies logging and the source GeometryBaker's
bone-name normalization. The source parser, numerical curve baking, meshes,
controller/event conversion, humanoid normalization, locator inference and
fallback action installation are retained. `NativeBbModel` is the host adapter
for strict JSON/resource budgets, declared local PNG companions and the existing
native runtime/profile conversion. Local and authorized private receivers use
this same chain; the ordinary/server blueprint parser remains separate.

The post-conversion local assembly also ports the source
`BuiltinBbmodelActionPreset`, `ModelAssemblyFactory` semantic remapping,
`SemanticSkeleton`, `YsmAnimationParsing`, `YsmJsonSupport` and
`ImportedVanillaPoseController` routines. The three pinned preset animation JSON
files and their original `README.md` / `CREDITS.md` are retained under
[assets/meplayeractions/builtin/bbmodel/animations](client/src/main/resources/assets/meplayeractions/builtin/bbmodel/animations/).
These preset assets have separate source attribution: the upstream credits
identify adapted Mojang/Microsoft Bedrock animations and the CC BY idle animation
by splatty. They are not relicensed as MIT by the software migration; the
unchanged source credits and attribution links remain with the files.

The fixed OpenYSM-Updated revision
`0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85` and Sparkle revision above also supply
the following runtime routines, adapted to MPA's model storage and Minecraft
1.21.11 deferred render interfaces:

| Fixed source routines | MPA host/runtime adaptation |
| --- | --- |
| `AnimationProcessor`, `BoneAnimationQueue`, `AnimationControllerInstance`, `BoneBlendState`, `MathUtil`, `AnimationControllerRuntime`, `SoundKeyFrameExecutor` | Per-channel ownership/reset, resolved beginning snapshots, native predicate blend rules, initial-rotation quaternion interpolation and scoped sound lifecycle in `AnimationPlayer` / `YsmAnimationController` |
| `AnimationMapper.parse(list, mergeMultilineExpr)`, folder/binary property mapping | Source literal-newline script joining and author property propagation in `NativeYsmScriptArrays`, `NativeYsmFile`, `YsmFolderModel` and `YsmModelProfile` |
| `QueryBinding`, `YSMBinding`, `Position`, `PositionDelta`, `RotationToCamera`, `HandRenderFunction`, `Armor` | Model-instance lifetime, qualified entity names, nullable native axes and distinct hand/armor unavailable results in `VanillaYsmQueries` |
| `GeoReplacedEntityRenderer`, `CustomPlayerItemInHandLayer`, `CustomPlayerArmorLayer`, `CustomPlayerElytraLayer`, `RenderUtils` | Author mesh/attachment order, native visibility/outline and exact attachment transforms in `ModelRenderer`, `YsmItemRenderer`, `YsmEquipmentRenderer`; native vanilla label/fire/shadow submission retained through geometry-only suppression |

Native held items retain Sparkle's verified main-arm and same-side extra-locator
corrections, and OpenYSM's visible-chain interpretation. Native head equipment
uses the modern OpenYSM `Equippable HEAD` exclusion. These source-specific choices
are recorded explicitly, including the omission of pumpkin-like equippable head
items from that generic native layer. Existing MPA armor/cape layer toggles are a
host extension, not an upstream feature. Fixed upstream CPU
geometry rendering does not consume `cube.cullable`; retained `all_cutout`
metadata does not introduce a GPU pipeline or reinterpret transparency.

Original MIT notices remain bundled in `LICENSE.OpenYSM.txt` and
`sparkle-morpher-MIT.txt`; new source files identify their pinned origin. Model
asset licenses remain independent. Resource authorization, upload/observer
budgets and MPA's private protocol are host adaptations. Per user decision,
food/experience/full effect/input fields absent from vanilla observer updates
are not additionally synchronized. This migration does not import the cloud
service, upstream network protocol or optional other-mod integrations.

## Bundled native format and WebP dependencies

The client JAR embeds the unchanged Maven artifacts
`com.github.luben:zstd-jni:1.5.7-6` and `org.glavo:webp:0.2.0` to decode supported
native model compression and WebP textures. Required AVIF textures are rejected;
optional AVIF author avatars use a placeholder without rejecting an otherwise
valid model. No AVIF decoder is bundled. These library notices are separate
from the model-asset licenses.

| Included component | Source / license | Retained license or notice |
| --- | --- | --- |
| Sparkle-Morpher software adaptation | [Pinned MIT license](https://github.com/sdf123098/Sparkle-Morpher/blob/b1230a431900a286d2cca198072df7fb43c490b4/LICENSE.txt) | [sparkle-morpher-MIT.txt](client/src/main/resources/assets/meplayeractions/licenses/sparkle-morpher-MIT.txt) |
| JWebP 0.2.0, copyright 2026 Glavo | Apache 2.0 as published in the [fixed Maven sources artifact](https://repo.maven.apache.org/maven2/org/glavo/webp/0.2.0/webp-0.2.0-sources.jar); attribution applies to this release | [jwebp-0.2.0-Apache-2.0.txt](client/src/main/resources/assets/meplayeractions/licenses/jwebp-0.2.0-Apache-2.0.txt), [jwebp-0.2.0-NOTICE.txt](client/src/main/resources/assets/meplayeractions/licenses/jwebp-0.2.0-NOTICE.txt) |
| Zstd-JNI 1.5.7-6, Luben Karavelov | [Original BSD 2-Clause LICENSE](https://raw.githubusercontent.com/luben/zstd-jni/v1.5.7-6/LICENSE) | [zstd-jni-1.5.7-6-BSD-2-Clause.txt](client/src/main/resources/assets/meplayeractions/licenses/zstd-jni-1.5.7-6-BSD-2-Clause.txt) |
| Bundled Zstandard native code, Facebook, Inc. | [Original native BSD 3-Clause LICENSE](https://raw.githubusercontent.com/luben/zstd-jni/v1.5.7-6/src/main/native/LICENSE); MPA uses this BSD option | [zstd-1.5.7-6-BSD-3-Clause.txt](client/src/main/resources/assets/meplayeractions/licenses/zstd-1.5.7-6-BSD-3-Clause.txt) |
| xxHash 0.8.2 upstream, Yann Collet | [Official v0.8.2 BSD 2-Clause LICENSE](https://raw.githubusercontent.com/Cyan4973/xxHash/v0.8.2/LICENSE) | [xxhash-0.8.2-BSD-2-Clause.txt](client/src/main/resources/assets/meplayeractions/licenses/xxhash-0.8.2-BSD-2-Clause.txt) |
| Zstandard's adapted xxHash 0.8.2, Yann Collet / Meta Platforms, Inc. | [Fixed-tag original xxhash.h notice](https://raw.githubusercontent.com/luben/zstd-jni/v1.5.7-6/src/main/native/common/xxhash.h), which points to the native BSD/GPLv2 options; MPA uses the BSD option | [xxhash-zstd-jni-1.5.7-6-NOTICE.txt](client/src/main/resources/assets/meplayeractions/licenses/xxhash-zstd-jni-1.5.7-6-NOTICE.txt), with the complete native BSD 3-Clause license above |

The Zstd-JNI, Zstandard and upstream xxHash license files retain the original
downloaded bytes at these fixed tags. The adapted xxHash notice retains its
complete original leading comment block. JWebP's bundled notice identifies the
fixed Maven release and its source. All listed files are available under
`assets/meplayeractions/licenses/` in the client JAR; install/source packages
also retain the notices and licenses.

## OpenYSM Wine Fox model assets (CC BY-NC-SA 4.0)

MEPlayerActions includes the first three Wine Fox model folders from
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
author-ordered timeline programs. MPA permits at most 256 ordered programs per native YSM timeline
event, including converted `65535` input, while retaining the 32 KiB aggregate
UTF-8 text budget; it does not truncate or combine authored programs. Finite
durations and key/event times remain bounded to 10,000 seconds. Ordinary
BBModel's 32-program / 3,600-second limits and 8 MiB standalone input bound
remain in place. Local YSM input, expanded source resources and converted
main/component output use separate 64 MiB stage budgets; private network
archives and their expanded resources remain bounded to 8 MiB or the lower
negotiated limit. Resource counts, geometry, image pixels and execution budgets
continue to apply. These importer adaptations do not modify the 77 original
Wine Fox resource files. Current limits are listed in
[YSM compatibility](docs/YSM_COMPATIBILITY.md).
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
folder compatibility mapping; public self-contained binary YSM decoding uses
the separate source adaptations described above. The original Wine Fox resource bytes and
asset licenses remain unchanged.

## OpenYSM first-person hand renderer adaptations (MIT)

The first-person renderer follows
[HandItemRenderer.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/renderer/HandItemRenderer.java#L39)
and the native `AvatarRenderer.renderRightHand` / `renderLeftHand` hooks in
[ItemInHandRendererMixin.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/fabric/src/main/java/com/elfmcys/yesstevemodel/fabric/mixin/client/ItemInHandRendererMixin.java#L21)
at the same MIT revision `0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85`.
MPA's `YsmComponentRenderer` keeps full authored coordinates and uses
`T(left +0.25 / right -0.25, 1.8, 0) S(-1, -1, 1)` without shoulder
normalization or additional BODY/user scaling. `BbModel.authoredArmBones`
adapts the source masks from
[YSMClientMapper.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/resource/YSMClientMapper.java#L436)
and [NativeModelRenderer.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/geckolib3/geo/NativeModelRenderer.java#L139):
LeftArm = 1, RightArm = 2, Background = 3, with descendants inheriting their
parent mask and mask 3 retained for either hand.

MPA's `PlayerArmRendererMixin` replaces only native hand entries reached by
empty-hand/map rendering. The additional ordinary-held-item arm mixin is
removed; vanilla item/equip/swing/use transformations remain responsible for
ordinary held blocks and weapons, without promising an additional authored arm
in those paths. This is a scoped adaptation, not a complete upstream renderer
or external-mod integration. The complete MIT notice and bundled
`LICENSE.OpenYSM.txt` are retained; model asset licenses remain separate.

## OpenYSM GUI source adaptations and assets (MIT)

The client adapts portions of the gallery and classic animation
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
| [BooleanOptionRow.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/rip/ysm/gui/components/BooleanOptionRow.java), [ConfigCheckBoxForge.java](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/button/ConfigCheckBoxForge.java) | Actual-option selection feedback for independent player / equipment / disguise visibility footer controls; MPA supplies Chinese state labels, immediate save/refresh, a separate rendering toggle in client settings and its own forced server-disguise policy |

The GUI preview camera and scene split also reference
[PlayerModelScreen.renderModelPreview](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/PlayerModelScreen.java#L549),
[ModelButton](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/button/ModelButton.java#L181),
[ModelPreviewRenderer.submitLivingEntityPreview](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/renderer/ModelPreviewRenderer.java#L365)
and [PlayerPreviewEntity](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/entity/PlayerPreviewEntity.java)
at the same MIT revision. MPA's `NativeGuiPreviewCamera`, `PreviewScene` and
`ModelPreview` adapt the actual-player inventory framing (size 70 / offset
0.0625) separately from the standing dummy-card framing (size 30 / disabled
rotation offset 5.5). Gallery selections remain drafts until applied.
OWNER samples native pose and typed queries in third-person inventory mode;
CARD submits only the author's preview clip to `player.cap`.
The source-sized card is fixed at 52 by 90 pixels,
with 55/93-pixel slot strides and 45-pixel title wrapping in at most two centered
lines. Its preview is submitted at nominal height 76 and only model geometry is
cropped to height 70; author background/foreground decorations cover the complete
90-pixel card. Fixed-card rotation applies to CARD. Each scene has independent animation/controller/physics
state while sharing an asset atlas; GUI sampling does not emit world effects.
The gallery and settings drag rules also reference
[PlayerModelScreen.mouseDragged](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/PlayerModelScreen.java#L427)
and [PlayerTextureScreen.mouseDragged / adjustPitch](https://github.com/IzumiiKonata/OpenYSM-Updated/blob/0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85/common/src/main/java/com/elfmcys/yesstevemodel/client/gui/PlayerTextureScreen.java#L282):
`pitch -= dy`, `yaw += 1.5 * dx`, with the source's -90 to 90 degree pitch clamp.
Ordinary BBModel uses its bounds-fit camera. This does not port the entire upstream GUI renderer or GUI-held-item
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
YSM, server and ordinary BBModel previews to the native offscreen color/depth path (RGBA8 / DEPTH32), using
the official `position_tex_color` shader, per-corner depth, LEQUAL depth testing
and depth writes. Each submitted preview's attachment/render evidence is filled
by actual renderer execution; preparing a mesh is not a GPU execution result.
Server and ordinary BBModel previews use this same depth path while
retaining identity author scaling and their existing bounds-fit camera.
Intersecting translucent-face ordering remains constrained by submission order:
translucent fragments blend and write depth in that order. The original 77 Wine Fox resource
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
