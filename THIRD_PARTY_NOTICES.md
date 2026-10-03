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
No wine-fox model is bundled. Third-party mod animation files referenced by the
original manifest are not included or integrated.

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
