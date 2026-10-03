# Third-party notices

## OpenYSM default player model

The independent client includes the `builtin/default` player model from
[IzumiiKonata/OpenYSM-Updated](https://github.com/IzumiiKonata/OpenYSM-Updated),
revision `0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85`.

Included original assets: `ysm.json`, the player and arm geometry, vanilla
projectile/vehicle geometry and textures, main/extra/arm/first-person-arm
animations, boat/arrow animations, both player skins, GUI background/foreground
textures, and `en_us`/`zh_cn` model strings. They are kept in
`assets/meplayeractions/builtin/openysm_default/`. No wine-fox model is bundled.

The original `ysm.json` identifies this default model's license as **CC0**
(`metadata.license.type: "CC 0"`). Original credits are preserved in that file:
哥斯拉 (model), 端木一动不动 (animation), 甜粽子 (animation), 星屑海螺 (animation),
蓝玫瑰 (texture/model), and 艾雷克亚 (additional spell animation).
The CC0 public-domain dedication is documented at
<https://creativecommons.org/publicdomain/zero/1.0/>.

The client independently imports the cube geometry, vanilla animation families,
selected PNG, configuration metadata and local components. Bedrock coordinates
are mapped once; authored expressions, null defaults, timeline scripts, loops,
controller definitions and interpolation remain available to the independent
per-instance runtime. Third-party mod animation files named by the original
manifest remain metadata for later integration and are not included. This asset
notice describes imported resources; it does not claim that every OpenYSM
feature is implemented or that unverified render paths have passed validation.

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
