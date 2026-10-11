/*
 * Derived from Sparkle-Morpher b1230a431900a286d2cca198072df7fb43c490b4.
 * Copyright (c) 2026 OpenYSM. MIT license: /assets/meplayeractions/licenses/sparkle-morpher-MIT.txt.
 * Only the self-contained public model-file format is imported; server/cache key paths are excluded.
 */
package com.simmc.meplayeractions.client.model.nativeysm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class YSMBinaryDeserializer implements AutoCloseable
{

    private final YSMByteBuf reader;
    private final int format;
    private final RawYsmModel model;

    public YSMBinaryDeserializer(byte[] decompressedData)
    {
        this.reader = new YSMByteBuf(decompressedData);
        this.format = (int) this.reader.readDword();
        this.model = new RawYsmModel();
        this.model.formatVersion = this.format;
    }

    public YSMBinaryDeserializer(byte[] decompressedData, int format)
    {
        this.reader = new YSMByteBuf(decompressedData);
        this.format = format;
        this.model = new RawYsmModel();
        this.model.formatVersion = this.format;
    }

    private RawYsmModel deserializeInternal(boolean closeOnExit)
    {

        if (this.format < 4)
        {
            deserializeLegacyV1();
        }
        else if (this.format <= 15)
        {
            deserializeLegacyV15();
        }
        else
        {
            deserializeModern();
        }

        this.model.projectiles.entrySet().removeIf(entry ->
        { // 老版本格式可能有动画没模型没纹理
            RawYsmModel.RawSubEntity sub = entry.getValue();
            return sub.model == null || sub.textures.isEmpty();
        });

        int offset = this.reader.getOffset(); // 关闭前获取偏移量
        if (closeOnExit)
        {
            this.reader.close();
        }

        return this.model;
    }

    public RawYsmModel deserialize()
    {
        return deserializeInternal(true);
    }

    public RawYsmModel deserializeKeepOpen()
    {
        return deserializeInternal(false);
    }

    public void parseYSMFooter(RawYsmModel footer)
    {
        try
        {
            if (this.format < 9)
            { // 《9没有
                return;
            }
            if (this.format > 26)
            { // >26 这里有个版本号
                this.model.footer.version = this.reader.readVarInt();
            }
            else
            {
                this.model.footer.version = this.format;
            }

            this.model.footer.unkInt1 = this.reader.readVarInt(); // always 1

            this.model.footer.rand = this.reader.readString(); // 随机字符串

            this.model.footer.time = this.reader.readVarLong(); // Unix 时间戳 如 1775738769

            this.model.footer.extra = this.reader.readString(); // 导出时的额外字符串

            if (this.format >= 24)
            { // TODO: 这个是什么数据？
                this.model.footer.unkInt2 = this.reader.readVarInt(); // always 0，暂时没看到过其他的情况，似乎是字符串
            }

        }
        catch (Throwable t)
        {

            throw new IllegalArgumentException("Invalid YSM footer", t);
        }
    }

    private void deserializeLegacyV1()
    {
        int unknownNeedSkipBytes = this.reader.readVarInt();
        this.reader.skipBytes(unknownNeedSkipBytes);

        List<RawYsmModel.RawGeometry> tempModels = new ArrayList<>();
        int modelCount = this.reader.readCount(34);
        for (int i = 0; i < modelCount; ++i)
        {
            int modelId = this.reader.readVarInt(); // 棄用或僅作為內部ID
            int unknownMustBeOneFlag = this.reader.readVarInt();
            if (unknownMustBeOneFlag != 1)
            {
                throw new RuntimeException("Expected 1");
            }

            RawYsmModel.RawGeometry rawGeometry = parseModels();
            rawGeometry.modelType = modelId;
            tempModels.add(rawGeometry);
        }
        assignMainModels(tempModels);

        Map<Integer, RawYsmModel.RawAnimationFile> tempAnims = new HashMap<>();
        int animationBlobCount = this.reader.readCount(32);
        for (int i = 0; i < animationBlobCount; ++i)
        {
            int animationId = this.reader.readVarInt();
            int unknownPadding = this.reader.readVarInt();
            if (unknownPadding != 1)
            {
                throw new RuntimeException("Expected 1");
            }

            RawYsmModel.RawAnimationFile rawAnimationFile = parseAnimations();

            String animKey = animationFamily(animationId);
            if (animationId == 5)
            {
                RawYsmModel.RawSubEntity arrowEntity = this.model.projectiles.computeIfAbsent("minecraft:arrow", k ->
                {
                    RawYsmModel.RawSubEntity newSub = new RawYsmModel.RawSubEntity();
                    newSub.identifier = k;
                    return newSub;
                });
                arrowEntity.animationFiles.put(animKey, rawAnimationFile);
            }
            else
            {
                this.model.mainEntity.animationFiles.put(animKey, rawAnimationFile);
            }

            rawAnimationFile.animType = animationId;
            tempAnims.put(animationId, rawAnimationFile);
        }

        List<RawYsmModel.RawTexture> tempTextures = new ArrayList<>();
        int customTextureCount = this.reader.readCount(16);
        for (int i = 0; i < customTextureCount; ++i)
        {
            RawYsmModel.RawTexture tex = new RawYsmModel.RawTexture();
            tex.name = this.reader.readString();
            if (this.format < 4)
            {
                int unknownFormatFlag = this.reader.readVarInt();
                if (unknownFormatFlag != 0x01)
                {
                    throw new RuntimeException("Expected 0x01");
                }

            }
            tex.data = this.reader.readByteArray();
            tex.width = this.reader.readVarInt();
            tex.height = this.reader.readVarInt();
            tex.imageFormat = -1; // RGBA

            if ("arrow.png".equals(tex.name))

            {
                this.model.projectiles.get("minecraft:arrow").textures.put(tex.name, tex);
            }

            else

            {
                this.model.mainEntity.textures.put(tex.name, tex);
            }

            tempTextures.add(tex);
        }

        // Tables回填Hash
        int modelTableSize = this.reader.readCount(200_000);
        for (int i = 0; i < modelTableSize; ++i)
        {
            int modelId = this.reader.readVarInt();
            String modelHash = this.reader.readString();
            for (RawYsmModel.RawGeometry tempModel : tempModels)
            {
                if (tempModel.modelType == modelId)

                {
                    tempModel.sha256 = modelHash;
                }

            }
        }

        int animationTableSize = this.reader.readCount(200_000);
        for (int i = 0; i < animationTableSize; ++i)
        {
            int animationId = this.reader.readVarInt();
            tempAnims.get(animationId).fileHash = this.reader.readString();
        }

        int textureTableSize = this.reader.readCount(200_000);
        for (int i = 0; i < textureTableSize; ++i)
        {
            String textureName = this.reader.readString();
            String textureHash = this.reader.readString();
            RawYsmModel.RawTexture tex = this.model.mainEntity.textures.get(textureName);
            if (tex != null)
            {
                tex.hash = textureHash;
            }

        }

        String unkString = this.reader.readString();
        this.model.properties.sha256 = unkString;
    }

    private void deserializeLegacyV15()
    {
        int unknownNeedSkipBytes = this.reader.readVarInt();
        this.reader.skipBytes(unknownNeedSkipBytes);

        List<RawYsmModel.RawGeometry> tempModels = new ArrayList<>();
        int modelCount = this.reader.readCount(34);
        for (int i = 0; i < modelCount; ++i)
        {
            int modelId = this.reader.readVarInt();
            int unknownPadding = this.reader.readVarInt();
            if (unknownPadding != 1)
            {
                throw new RuntimeException("Expected 1");
            }

            RawYsmModel.RawGeometry rawGeometry = parseModels();
            rawGeometry.modelType = modelId;
            tempModels.add(rawGeometry);
        }

        assignMainModels(tempModels); //TODO

        Map<Integer, RawYsmModel.RawAnimationFile> tempAnims = new HashMap<>();
        int animationBlobCount = this.reader.readCount(32);
        for (int i = 0; i < animationBlobCount; ++i)
        {
            int animationId = this.reader.readVarInt();
            int unknownPadding = this.reader.readVarInt();
            if (unknownPadding != 1)
            {
                throw new RuntimeException("Expected 1");
            }

            RawYsmModel.RawAnimationFile rawAnimationFile = parseAnimations();

            String animKey = animationFamily(animationId);
            if (animationId == 5)
            {
                RawYsmModel.RawSubEntity arrowEntity = this.model.projectiles.computeIfAbsent("minecraft:arrow", k ->
                {
                    RawYsmModel.RawSubEntity newSub = new RawYsmModel.RawSubEntity();
                    newSub.identifier = k;
                    return newSub;
                });
                arrowEntity.animationFiles.put(animKey, rawAnimationFile);
            }
            else
            {
                this.model.mainEntity.animationFiles.put(animKey, rawAnimationFile);
            }

            rawAnimationFile.animType = animationId;
            tempAnims.put(animationId, rawAnimationFile);
        }

        if (this.format > 9)
        {
            parseAnimationControllers(this.model.mainEntity.animationControllerFiles, false);
            int animationControllerTableSize = this.reader.readCount(200_000);
            for (int i = 0; i < animationControllerTableSize; ++i)
            {
                String controllerName = this.reader.readString();
                String controllerHash = this.reader.readString();
                for (RawYsmModel.RawAnimationControllerFile file : this.model.mainEntity.animationControllerFiles)
                {
                    if (controllerName.equals(file.name))
                    {
                        file.hash = controllerHash;
                        break;
                    }
                }
            }
        }

        int customTextureCount = this.reader.readCount(16);
        for (int i = 0; i < customTextureCount; ++i)
        {
            RawYsmModel.RawTexture tex = new RawYsmModel.RawTexture();
            tex.name = this.reader.readString();
            tex.data = this.reader.readByteArray();
            tex.width = this.reader.readVarInt();
            tex.height = this.reader.readVarInt();
            tex.imageFormat = -1; // RGBA

            int subTextureSize = this.reader.readCount(2);
            for (int j = 0; j < subTextureSize; ++j)
            {
                RawYsmModel.RawTexture.SubTexture sub = new RawYsmModel.RawTexture.SubTexture();
                sub.specularType = this.reader.readVarInt();
                sub.data = this.reader.readByteArray();
                sub.width = this.reader.readVarInt();
                sub.height = this.reader.readVarInt();
                sub.imageFormat = -1; // RGBA
                tex.subTextures.add(sub);
            }

            // 特殊處理一下
            if ("/ARROW\\".equals(tex.name))

            {
                this.model.projectiles.get("minecraft:arrow").textures.put(tex.name, tex);
            }

            else

            {
                this.model.mainEntity.textures.put(tex.name, tex);
            }

        }

        if (this.format > 9)
        {
            parseSoundFiles();
            int soundTableCount = this.reader.readCount(200_000);
            for (int i = 0; i < soundTableCount; ++i)
            {
                String soundName = this.reader.readString();
                String soundHash = this.reader.readString();
                RawYsmModel.RawDataFile sf = this.model.soundFiles.get(soundName);
                if (sf != null)
                {
                    sf.hash = soundHash;
                }

            }
        }

        List<RawYsmModel.RawImage> tempAvatars = new ArrayList<>();
        int extraTextureCount = this.reader.readCount(16);
        for (int i = 0; i < extraTextureCount; ++i)
        {
            RawYsmModel.RawImage avatar = new RawYsmModel.RawImage();
            avatar.name = this.reader.readString();
            avatar.data = this.reader.readByteArray();
            avatar.width = this.reader.readVarInt();
            avatar.height = this.reader.readVarInt();
            avatar.format = -1; // 兼容旧版默认 RGBA
            tempAvatars.add(avatar);
        }

        // Tables回填Hash
        int modelTableSize = this.reader.readCount(200_000);
        for (int i = 0; i < modelTableSize; ++i)
        {
            int modelId = this.reader.readVarInt();
            String modelHash = this.reader.readString();
            for (RawYsmModel.RawGeometry tempModel : tempModels)
            {
                if (tempModel.modelType == modelId)

                {
                    tempModel.sha256 = modelHash;
                }

            }
        }

        int animationTableSize = this.reader.readCount(200_000);
        for (int i = 0; i < animationTableSize; ++i)
        {
            int animationId = this.reader.readVarInt();
            String animationHash = this.reader.readString();
            //            if (tempAnims.get(animationId) == null) {
            //                "".hashCode();
            //
            //            }
            tempAnims.get(animationId).fileHash = animationHash;
        }

        int textureTableSize = this.reader.readCount(200_000);
        for (int i = 0; i < textureTableSize; ++i)
        {
            String textureName = this.reader.readString();
            String textureHash = this.reader.readString();
            RawYsmModel.RawTexture tex = this.model.mainEntity.textures.get(textureName);
            if (tex != null)
            {
                tex.hash = textureHash;
            }
            int subTextureSize = this.reader.readCount(2);
            for (int j = 0; j < subTextureSize; ++j)
            {
                int specularType = this.reader.readVarInt();
                String specialImageHash = this.reader.readString();
                // 修復舊版丟棄子紋理Hash
                if (tex != null)
                {
                    for (RawYsmModel.RawTexture.SubTexture sub : tex.subTextures)
                    {
                        if (sub.specularType == specularType)
                        {
                            sub.hash = specialImageHash;
                            break;
                        }
                    }
                }
            }
        }
        for (RawYsmModel.RawSubEntity value : this.model.projectiles.values())
        {
            for (RawYsmModel.RawTexture rawTexture : value.textures.values())
            {
                this.model.mainEntity.textures.remove(rawTexture.name);
            }
        }

        parseYSMJson();

        for (int i = 0; i < tempAvatars.size(); i++)
        {
            RawYsmModel.RawImage avatar = tempAvatars.get(i);
            if (i < this.model.metadata.authors.size())
            {
                this.model.metadata.authors.get(i).avatar = avatar.name;
                this.model.metadata.authors.get(i).avatarImage = avatar;
            }
            else
            {
                this.model.metadata.extraAvatars.add(avatar);
            }
        }
    }

    private void deserializeModern()
    {
        parseSoundFiles();
        parseFunctionFiles();
        parseLanguageFiles();

        if (this.format < 26)
        {
            int subEntityTotalCount = this.reader.readCount(200_000);
            for (int i = 0; i < subEntityTotalCount; ++i)
            {
                parseSubEntity(this.model.vehicles, "SubEntity", i);
            }
            int footerFlag = this.reader.readVarInt(); // always 00
        }
        else
        {
            int vehiclesTotalCount = this.reader.readCount(200_000);
            for (int i = 0; i < vehiclesTotalCount; ++i)
            {
                parseSubEntity(this.model.vehicles, "Vehicle", i);
            }

            int projectilesTotalCount = this.reader.readCount(200_000);
            for (int i = 0; i < projectilesTotalCount; ++i)
            {
                parseSubEntity(this.model.projectiles, "Projectile", i);
            }

        }

        int unknownEntityFlag = this.reader.readVarInt();
        if (unknownEntityFlag != 1)
        {
            throw new RuntimeException("Expected 1 after SubEntities");
        }

        int animationCount = this.reader.readCount(1024);
        for (int i = 0; i < animationCount; ++i)
        {
            int type = this.reader.readVarInt();
            String hash = this.reader.readString();

            RawYsmModel.RawAnimationFile animRef = parseAnimations();
            this.model.mainEntity.animationFiles.put(animationFamily(type), animRef);
            animRef.animType = type;
            animRef.fileHash = hash;
        }

        parseAnimationControllers(this.model.mainEntity.animationControllerFiles, true);

        parseTextureFiles(this.model.mainEntity.textures);

        int modelTotalCount = this.reader.readCount(34);
        List<RawYsmModel.RawGeometry> tempMainModels = new ArrayList<>();
        for (int i = 0; i < modelTotalCount; ++i)
        {
            int modelType = this.reader.readVarInt();
            String hash = this.reader.readString();

            RawYsmModel.RawGeometry geoRef = parseModels();
            geoRef.sha256 = hash;
            geoRef.modelType = modelType;
            tempMainModels.add(geoRef);

        }
        assignMainModels(tempMainModels);

        parseYSMJson();
    }

    private void parseSubEntity(Map<String, RawYsmModel.RawSubEntity> targetMap, String categoryName, int index)
    {
        RawYsmModel.RawSubEntity subEntity = new RawYsmModel.RawSubEntity();
        String subModuleName = "";
        if (this.format <= 26)
        {
            subModuleName = this.reader.readString();
            subEntity.identifier = subModuleName;
        }
        else
        {
            subEntity.identifier = categoryName + "_" + index; // >=26沒有Header Name
        }
        int animationCount = this.reader.readCount(1024);
        for (int i = 0; i < animationCount; ++i)
        {
            String hash = this.reader.readString();
            RawYsmModel.RawAnimationFile rawAnimationFile = parseAnimations();
            subEntity.animationFiles.put(categoryName, rawAnimationFile);
            rawAnimationFile.fileHash = hash;
        }

        parseAnimationControllers(subEntity.animationControllerFiles, false);

        RawYsmModel.RawTexture baseTex = new RawYsmModel.RawTexture();
        SpecialImageResult imgRes = parseSpecialImage();
        baseTex.hash = imgRes.hash;
        baseTex.data = imgRes.data;
        baseTex.width = this.reader.readVarInt();
        baseTex.height = this.reader.readVarInt();
        baseTex.imageFormat = this.reader.readVarInt();
        baseTex.unknownFlag = this.reader.readVarInt();
        baseTex.name = "base_texture_" + index;
        subEntity.textures.put(baseTex.name, baseTex);

        // Sub Textures
        int subTextureSize = this.reader.readCount(2);
        for (int i = 0; i < subTextureSize; ++i)
        {
            RawYsmModel.RawTexture.SubTexture subTex = new RawYsmModel.RawTexture.SubTexture();
            subTex.specularType = this.reader.readVarInt();
            SpecialImageResult specRes = parseSpecialImage();
            subTex.hash = specRes.hash;
            subTex.data = specRes.data;
            subTex.width = this.reader.readVarInt();
            subTex.height = this.reader.readVarInt();
            subTex.imageFormat = this.reader.readVarInt();
            subTex.unknownFlag = this.reader.readVarInt();
            baseTex.subTextures.add(subTex);
        }

        String modelHash = this.reader.readString();
        subEntity.model = parseModels();
        subEntity.model.sha256 = modelHash;

        if (this.format > 26)
        {
            int identifierCount = this.reader.readCount(32);
            if (identifierCount <= 0 || identifierCount > 128)
            {
                throw new RuntimeException("Invalid SubEntity identifier count: " + identifierCount);
            }

            List<String> identifiers = new ArrayList<>();
            for (int i = 0; i < identifierCount; i++)
            {
                identifiers.add(this.reader.readString());
            }

            subEntity.identifier = identifiers.get(0);
            subEntity.matchIds = identifiers.toArray(new String[0]);
            targetMap.put(subEntity.identifier, subEntity);
        }
        else
        {
            targetMap.put(subEntity.identifier, subEntity);
        }
    }

    private RawYsmModel.RawGeometry parseModels()
    {
        RawYsmModel.RawGeometry geo = new RawYsmModel.RawGeometry();

        int boneCount = this.reader.readCount(2047);
        for (int i = 0; i < boneCount; i++)
        {
            RawYsmModel.RawBone bone = new RawYsmModel.RawBone();
            bone.parentName = this.reader.readString();
            int cubeCount = this.reader.readCount(4096);

            for (int j = 0; j < cubeCount; j++)
            {
                RawYsmModel.RawCube cube = new RawYsmModel.RawCube();
                int faceCount = this.reader.readCount(6);
                for (int k = 0; k < faceCount; k++)
                {
                    RawYsmModel.RawFace face = new RawYsmModel.RawFace();
                    face.normal = readVector3D(); // normal
                    for (int v = 0; v < 4; v++)
                    {
                        face.positions[v] = readVector3D(); // position
                        face.u[v] = this.reader.readFloat(); // u
                        face.v[v] = this.reader.readFloat(); // v
                    }
                    cube.faces.add(face);
                }
                cube.unkInt1 = this.reader.readVarInt();
                cube.unkInt2 = this.reader.readVarInt();
                cube.unkInt3 = this.reader.readVarInt();
                bone.cubes.add(cube);
            }

            bone.name = this.reader.readString();
            bone.unkPad1 = this.reader.readVarInt();
            bone.unkPad2 = this.reader.readVarInt();
            bone.unkPad3 = this.reader.readVarInt();
            bone.unkPad4 = this.reader.readVarInt();
            bone.unkPad5 = this.reader.readVarInt();

            bone.pivot = readVector3D();
            bone.rotation = readVector3D();
            geo.bones.add(bone);
        }

        geo.identifier = this.reader.readString();
        geo.textureHeight = this.reader.readFloat();
        geo.textureWidth = this.reader.readFloat();
        geo.visibleBoundsHeight = this.reader.readFloat();
        geo.visibleBoundsWidth = this.reader.readFloat();

        int visibleBoundsOffsetSize = this.reader.readCount(3);
        geo.visibleBoundsOffset = new float[visibleBoundsOffsetSize];
        for (int i = 0; i < visibleBoundsOffsetSize; i++)
        {
            geo.visibleBoundsOffset[i] = this.reader.readFloat();
        }

        geo.unkFloat1 = this.reader.readFloat();
        geo.unkFloat2 = this.reader.readFloat();

        int hasInfoJsonFlag = this.reader.readVarInt();
        if (hasInfoJsonFlag > 0)
        {
            parseLegacyYSMInfo();
        }

        geo.footerPad1 = this.reader.readVarInt();
        geo.footerPad2 = this.reader.readVarInt();
        geo.footerPad3 = this.reader.readVarInt();

        return geo;
    }

    private void parseYSMJson()
    {
        this.model.properties.sha256 = this.reader.readString();
        int isNewVersionYsm = this.reader.readVarInt();

        if (isNewVersionYsm != 0)
        {
            if (this.format <= 15)
            {
                this.reader.readVarInt(); // unknown
            }

            this.model.metadata.name = this.reader.readString();
            this.model.metadata.tips = this.reader.readString();
            this.model.metadata.licenseType = this.reader.readString();
            this.model.metadata.licenseDescription = this.reader.readString();

            int authorsCount = this.reader.readCount(32);
            for (int i = 0; i < authorsCount; i++)
            {
                RawYsmModel.RawMetadata.Author author = new RawYsmModel.RawMetadata.Author();
                author.name = this.reader.readString();
                author.role = this.reader.readString();
                int contactsCount = this.reader.readCount(200_000);
                for (int j = 0; j < contactsCount; j++)
                {
                    author.contacts.put(this.reader.readString(), this.reader.readString());
                }
                author.comment = this.reader.readString();
                this.model.metadata.authors.add(author);
            }

            int linksCount = this.reader.readCount(200_000);
            for (int i = 0; i < linksCount; i++)
            {
                this.model.metadata.links.put(this.reader.readString(), this.reader.readString());
            }
        }

        if (isNewVersionYsm == 0 && this.format <= 15)
        {
            return;
        }

        this.model.properties.widthScale = this.reader.readFloat();
        this.model.properties.heightScale = this.reader.readFloat();

        int extraAnimationsCount = this.reader.readCount(200_000);
        for (int i = 0; i < extraAnimationsCount; i++)
        {
            try
            {
                this.model.properties.extraAnimations.put(this.reader.readString(), this.reader.readString());
            }
            catch (Throwable ex)
            {
                throw new RuntimeException("Error reading extra animations at index " + i, ex);
            }
        }

        if (this.format > 9)
        {
            int extraAnimationButtonsCount = this.reader.readCount(32);
            for (int i = 0; i < extraAnimationButtonsCount; i++)
            {
                RawYsmModel.ExtraAnimationButton btn = new RawYsmModel.ExtraAnimationButton();
                btn.id = this.reader.readString();
                btn.name = this.reader.readString();
                this.reader.readVarInt(); // buttonPadding

                int configurationFormsCount = this.reader.readCount(128);
                for (int j = 0; j < configurationFormsCount; j++)
                {
                    RawYsmModel.ConfigForm form = new RawYsmModel.ConfigForm();
                    form.type = this.reader.readString();
                    form.title = this.reader.readString();
                    form.description = this.reader.readString();
                    form.defaultValue = this.reader.readString();
                    form.step = this.reader.readFloat();
                    form.min = this.reader.readFloat();
                    form.max = this.reader.readFloat();
                    int labelsSize = this.reader.readCount(200_000);
                    for (int l = 0; l < labelsSize; l++)
                    {
                        form.labels.put(this.reader.readString(), this.reader.readString());
                    }
                    btn.forms.add(form);
                }
                this.model.properties.extraAnimationButtons.add(btn);
            }

            int extraAnimationClassifyCount = this.reader.readCount(32);
            for (int i = 0; i < extraAnimationClassifyCount; i++)
            {
                RawYsmModel.ExtraAnimationClassify classify = new RawYsmModel.ExtraAnimationClassify();
                classify.id = this.reader.readString();
                int classificationExtrasCount = this.reader.readCount(200_000);
                for (int j = 0; j < classificationExtrasCount; j++)
                {
                    classify.extras.put(this.reader.readString(), this.reader.readString());
                }
                this.model.properties.extraAnimationClassifies.add(classify);
            }
        }

        this.model.properties.defaultTexture = this.reader.readString();
        this.model.properties.previewAnimation = this.reader.readString();
        this.model.properties.isFree = this.reader.readVarInt() != 0;

        if (this.format > 4)
        {
            this.model.properties.renderLayersFirst = this.reader.readVarInt() != 0;
        }

        if (this.format >= 15)
        {
            this.model.properties.allCutout = this.reader.readVarInt() != 0;
            this.model.properties.disablePreviewRotation = this.reader.readVarInt() != 0;
        }

        if (this.format > 15)
        {
            this.model.properties.guiNoLighting = this.reader.readVarInt() != 0;
            if (this.format >= 32)
            {
                this.model.properties.mergeMultilineExpr = this.reader.readVarInt() != 0;
            }

            this.model.properties.guiForeground = this.reader.readString();
            this.model.properties.guiBackground = this.reader.readString();

            int avatarsCount = this.reader.readCount(32);
            for (int i = 0; i < avatarsCount; i++)
            {
                RawYsmModel.RawImage avatar = new RawYsmModel.RawImage();
                avatar.name = this.reader.readString();
                avatar.data = this.reader.readByteArray();
                avatar.width = this.reader.readVarInt();
                avatar.height = this.reader.readVarInt();
                avatar.format = this.reader.readVarInt();
                avatar.unknownFlag = this.reader.readVarInt();
                if (i < this.model.metadata.authors.size())
                {
                    this.model.metadata.authors.get(i).avatar = avatar.name;
                    this.model.metadata.authors.get(i).avatarImage = avatar;
                }
                else
                {
                    this.model.metadata.extraAvatars.add(avatar);
                }
            }
        }

        if (this.format <= 15)
        {
            return;
        }

        int backgroundImagesCount = this.reader.readCount(32);
        for (int i = 0; i < backgroundImagesCount; i++)
        {
            RawYsmModel.RawImage bg = new RawYsmModel.RawImage();
            bg.name = this.reader.readString();
            bg.data = this.reader.readByteArray();
            bg.width = this.reader.readVarInt();
            bg.height = this.reader.readVarInt();
            bg.format = this.reader.readVarInt();
            bg.unknownFlag = this.reader.readVarInt();
            this.model.properties.backgroundImages.add(bg);
        }
    }

    private void parseLegacyYSMInfo()
    {
        this.model.metadata.name = this.reader.readString();
        this.model.metadata.tips = this.reader.readString();
        int extraAnimationsCount = this.reader.readCount(200_000);
        for (int i = 0; i < extraAnimationsCount; i++)
        {
            this.model.properties.extraAnimations.put("extra" + i, this.reader.readString());
        }
        int authorsCount = this.reader.readCount(32);
        for (int i = 0; i < authorsCount; i++)
        {
            RawYsmModel.RawMetadata.Author author = new RawYsmModel.RawMetadata.Author();
            author.name = this.reader.readString();
            this.model.metadata.authors.add(author);
        }
        this.model.metadata.licenseType = this.reader.readString();
        this.model.properties.isFree = this.reader.readVarInt() != 0;
    }

    private RawYsmModel.RawAnimationFile parseAnimations()
    {
        RawYsmModel.RawAnimationFile animFile = new RawYsmModel.RawAnimationFile();

        int animationCount = this.reader.readCount(1024);
        for (int animIndex = 0; animIndex < animationCount; ++animIndex)
        {
            RawYsmModel.RawAnimation anim = new RawYsmModel.RawAnimation();
            anim.name = this.reader.readString();
            anim.length = this.reader.readAnimationLength() / 20.0f;
            anim.loopMode = this.reader.readVarInt();

            if (this.format > 9)
            {
                anim.unkInt1 = this.reader.readVarInt();
                anim.unkInt2 = this.reader.readVarInt();
                int blendWeightMolangCount = this.reader.readCount(200_000);
                for (int i = 0; i < blendWeightMolangCount; i++)
                {
                    // 1 = float
                    // 2 = string
                    byte datatype = this.reader.readByte();

                    if (datatype == 0x01)
                    {
                        anim.blendWeight = this.reader.readFloat();
                    }
                    else if (datatype == 0x02)
                    {
                        anim.blendWeight = this.reader.readString();
                    }
                }
                anim.unkInt4 = this.reader.readVarInt();
            }

            int boneCount = this.reader.readCount(2047);
            for (int i = 0; i < boneCount; ++i)
            {
                RawYsmModel.RawBoneAnimation ba = new RawYsmModel.RawBoneAnimation();
                ba.boneName = this.reader.readString();
                parseChannel(ba.rotation);
                parseChannel(ba.position);
                parseChannel(ba.scale);
                anim.boneAnimations.add(ba);
            }

            // Timeline
            int timelineEventGroupsCount = this.reader.readCount(200_000);
            for (int i = 0; i < timelineEventGroupsCount; ++i)
            {
                RawYsmModel.RawTimelineEvent event = new RawYsmModel.RawTimelineEvent();
                int timelineEventsCount = this.reader.readCount(200_000);
                for (int j = 0; j < timelineEventsCount; ++j)
                {
                    event.events.add(this.reader.readString());
                }
                event.timestamp = this.reader.readFloat() / 20.0f;
                anim.timelineEvents.add(event);
            }

            // Effects
            if (this.format > 9)
            {
                int soundEffectsCount = this.reader.readCount(32);
                for (int i = 0; i < soundEffectsCount; i++)
                {
                    RawYsmModel.RawSoundEffect sfx = new RawYsmModel.RawSoundEffect();
                    sfx.effectName = this.reader.readString();
                    sfx.timestamp = this.reader.readFloat() / 20.0f;
                    anim.soundEffects.add(sfx);
                }
            }
            animFile.animations.put(anim.name, anim);
        }

        return animFile;
    }

    private void parseChannel(List<RawYsmModel.RawKeyframe> channel)
    {
        int keyframeCount = this.reader.readCount(200_000);
        if (keyframeCount == 0)
        {
            return;
        }

        for (int i = 0; i < keyframeCount; i++)
        {
            RawYsmModel.RawKeyframe kf = new RawYsmModel.RawKeyframe();
            kf.timestamp = this.reader.readFloat() / 20.0f;
            kf.interpolationMode = this.reader.readVarInt();

            Object[] firstData = new Object[3];
            for (int j = 0; j < 3; j++)
            {
                byte datatype = this.reader.readByte();
                if (datatype == 0x01)
                {
                    firstData[j] = this.reader.readFloat();
                }
                else if (datatype == 0x02)
                {
                    firstData[j] = this.reader.readString();
                }
            }

            kf.hasPreData = this.reader.readVarInt() > 0;
            if (kf.hasPreData)
            {
                for (int j = 0; j < 3; j++)
                {
                    byte datatype = this.reader.readByte();
                    if (datatype == 0x01)
                    {
                        kf.postData[j] = this.reader.readFloat();
                    }
                    else if (datatype == 0x02)
                    {
                        kf.postData[j] = this.reader.readString();
                    }
                }

                kf.preData = firstData;
                kf.hasPreData = true;
            }
            else
            {

                kf.postData = firstData;
                kf.hasPreData = false;
            }
            channel.add(kf);
        }
    }

    private void parseAnimationControllers(List<RawYsmModel.RawAnimationControllerFile> targetList, boolean readName)
    {
        int controllerCount = this.reader.readCount(64);
        for (int i = 0; i < controllerCount; i++)
        {
            RawYsmModel.RawAnimationControllerFile file = new RawYsmModel.RawAnimationControllerFile();

            if (this.format <= 15)
            {
                file.legacyUnknownInt = this.reader.readVarInt();
                file.name = "legacy_controller_" + i;
            }
            else
            {
                if (readName)
                {
                    file.name = this.reader.readString();
                }

                file.hash = this.reader.readString();
            }

            parseAnimationControllerBody(file.controllers);
            targetList.add(file);
        }
    }

    private void parseAnimationControllerBody(Map<String, RawYsmModel.RawAnimationController> targetMap)
    {
        int animationCount = this.reader.readCount(1024);
        for (int animIndex = 0; animIndex < animationCount; ++animIndex)
        {
            RawYsmModel.RawAnimationController entry = new RawYsmModel.RawAnimationController();
            entry.animationName = this.reader.readString();
            entry.initialState = this.reader.readString();

            int statesCount = this.reader.readCount(64);
            for (int s = 0; s < statesCount; s++)
            {
                RawYsmModel.RawControllerState state = new RawYsmModel.RawControllerState();
                state.name = this.reader.readString();

                // animations
                int animationsSize = this.reader.readCount(32);
                for (int j = 0; j < animationsSize; j++)
                {
                    state.animations.put(this.reader.readString(), this.reader.readString());
                }
                // transitions
                int transitionsSize = this.reader.readCount(32);
                for (int j = 0; j < transitionsSize; j++)
                {
                    state.transitions.put(this.reader.readString(), this.reader.readString());
                }
                // on_entry
                int onEntryCount = this.reader.readCount(64);
                for (int j = 0; j < onEntryCount; j++)
                {
                    state.onEntry.add(this.reader.readString());
                }
                // on_exit
                int onExitCount = this.reader.readCount(64);
                for (int j = 0; j < onExitCount; j++)
                {
                    state.onExit.add(this.reader.readString());
                }
                // blend_transition
                if (this.reader.readVarInt() != 0)
                {
                    state.blendTransitionValue = this.reader.readFloat();
                }
                else
                {
                    int blendTransitionsCount = this.reader.readCount(200_000);
                    for (int j = 0; j < blendTransitionsCount; j++)
                    {
                        state.blendTransitions.put(this.reader.readFloat(), this.reader.readFloat());
                    }
                }
                state.blendViaShortestPath = this.reader.readVarInt() != 0;
                // sound_effects (format > 26)
                if (this.format > 26)
                {
                    int soundEffectsCount = this.reader.readCount(32);
                    for (int j = 0; j < soundEffectsCount; j++)
                    {
                        state.soundEffects.add(this.reader.readString());
                    }
                }
                entry.states.add(state);
            }
            targetMap.put(entry.animationName, entry);
        }
    }

    private void parseSoundFiles()
    {
        int soundCount = this.reader.readCount(32);
        for (int i = 0; i < soundCount; i++)
        {
            String soundName = this.reader.readString();
            String hash = "";
            if (this.format > 15)
            {
                hash = this.reader.readString(); // soundHash
            }
            byte[] data = this.reader.readByteArray();
            this.model.soundFiles.put(soundName, new RawYsmModel.RawDataFile(hash, data));
        }
    }

    private void parseFunctionFiles()
    {
        int functionCount = this.reader.readCount(64);
        for (int i = 0; i < functionCount; i++)
        {
            String functionName = this.reader.readString();
            String hash = this.reader.readString();
            byte[] data = this.reader.readByteArray();
            this.model.functionFiles.put(functionName, new RawYsmModel.RawDataFile(hash, data));
        }
    }

    private void parseLanguageFiles()
    {
        int languageCount = this.reader.readCount(16);
        for (int i = 0; i < languageCount; i++)
        {
            String languageName = this.reader.readString();
            String hash = this.reader.readString();
            int nodesCount = this.reader.readCount(2048);
            Map<String, String> langMap = new java.util.LinkedHashMap<>();
            for (int j = 0; j < nodesCount; j++)
            {
                langMap.put(this.reader.readString(), this.reader.readString());
            }
            this.model.languageFiles.put(languageName, new RawYsmModel.RawLanguageFile(hash, langMap));
        }
    }

    private void parseTextureFiles(Map<String, RawYsmModel.RawTexture> targetMap)
    {
        int textureCount = this.reader.readCount(16);
        for (int i = 0; i < textureCount; i++)
        {
            RawYsmModel.RawTexture tex = new RawYsmModel.RawTexture();
            tex.name = this.reader.readString();
            tex.hash = this.reader.readString();
            tex.data = this.reader.readByteArray();
            tex.width = this.reader.readVarInt();
            tex.height = this.reader.readVarInt();
            tex.imageFormat = this.reader.readVarInt();
            tex.unknownFlag = this.reader.readVarInt();

            int subTextureSize = this.reader.readCount(2);
            for (int j = 0; j < subTextureSize; j++)
            {
                RawYsmModel.RawTexture.SubTexture subTex = new RawYsmModel.RawTexture.SubTexture();
                subTex.specularType = this.reader.readVarInt();
                SpecialImageResult specRes = parseSpecialImage();
                subTex.hash = specRes.hash;
                subTex.data = specRes.data;
                subTex.width = this.reader.readVarInt();
                subTex.height = this.reader.readVarInt();
                subTex.imageFormat = this.reader.readVarInt();
                subTex.unknownFlag = this.reader.readVarInt();
                tex.subTextures.add(subTex);
            }
            targetMap.put(tex.name, tex);
        }
    }

    private SpecialImageResult parseSpecialImage()
    {
        String imageHash = this.reader.readString();
        byte[] imageData = this.reader.readByteArray();
        return new SpecialImageResult(imageHash, imageData);
    }

    private float[] readVector3D()
    {
        return new float[]
        { this.reader.readFloat(), this.reader.readFloat(), this.reader.readFloat() };
    }

    private void assignMainModels(List<RawYsmModel.RawGeometry> tempMainModels)
    {
        for (RawYsmModel.RawGeometry tempMainModel : tempMainModels)
        {
            switch (tempMainModel.modelType)
            {
            case 1:
                this.model.mainEntity.mainModel = tempMainModel;
                break;
            case 2:
                this.model.mainEntity.armModel = tempMainModel;
                break;
            case 3:
                RawYsmModel.RawSubEntity subEntity = new RawYsmModel.RawSubEntity();
                subEntity.model = tempMainModel;
                subEntity.identifier = "minecraft:arrow";
                this.model.projectiles.put(subEntity.identifier, subEntity);
                break;
            default:
                throw new RuntimeException("Unknown model type: " + tempMainModel.modelType);
            }
        }
    }

    private static String animationFamily(int type)
    {
        return switch (type)
        {
        case 1 -> "main";
        case 2 -> "arm";
        case 3 -> "extra";
        case 4 -> "tac";
        case 5 -> "arrow";
        case 6 -> "carryon";
        case 7 -> "parcool";
        case 8 -> "swem";
        case 9 -> "slashblade";
        case 10 -> "tlm";
        case 11 -> "fp_arm";
        case 12 -> "immersive_melodies";
        case 13 -> "irons_spell_books";
        default -> "unk_" + type;
        };
    }

    @Override
    public void close() throws Exception
    {
        if (this.reader != null)
        {
            this.reader.close();
        }
    }

    private static class SpecialImageResult
    {
        public String hash;
        public byte[] data;

        public SpecialImageResult(String hash, byte[] data)
        {
            this.hash = hash;
            this.data = data;
        }
    }

    public YSMByteBuf getReader()
    {
        return this.reader;
    }
}
