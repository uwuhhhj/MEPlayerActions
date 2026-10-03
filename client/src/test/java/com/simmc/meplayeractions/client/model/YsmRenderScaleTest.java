package com.simmc.meplayeractions.client.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class YsmRenderScaleTest {
    @Test void absentSourcePropertiesUsePointSevenLikeTheOfficialPlayerModels() throws Exception {
        var defaults = YsmRenderScale.fromProperties(new JsonObject());
        assertEquals(new YsmRenderScale(.7f, .7f, .7f), defaults);
        assertEquals(defaults, YsmRenderScale.fromProperties(null));
        assertEquals(defaults, YsmRenderScale.fromProperties(properties(.7, .7)));
        for (String id : List.of(YsmFolderModel.DEFAULT_ID, BuiltinYsmModels.TAISHO_MAID_ID,
                BuiltinYsmModels.NEW_YEAR_ID, BuiltinYsmModels.ASTRONAUT_ID))
            assertEquals(defaults, YsmRenderScale.forProfile(YsmFolderModel.bundledWithProfile(id, null).profile()), id);
    }

    @Test void nonUniformSourceHeightScaleIsXZAndWidthScaleIsY() {
        var scale = YsmRenderScale.fromProperties(properties(.5, 2));
        assertEquals(new YsmRenderScale(.5f, 2, .5f), scale);
        JsonObject missingWidth = new JsonObject(); missingWidth.addProperty("height_scale", 3);
        assertEquals(new YsmRenderScale(3, .7f, 3), YsmRenderScale.fromProperties(missingWidth));
        JsonObject missingHeight = new JsonObject(); missingHeight.addProperty("width_scale", .25);
        assertEquals(new YsmRenderScale(.7f, .25f, .7f), YsmRenderScale.fromProperties(missingHeight));
    }

    @Test void onlyNativeYsmProfilesReceiveAuthorScaleAndTheInitialPlayerBodyOffset() {
        var nativeProfile = profile(true, properties(.5, 2));
        var ordinaryProfile = profile(false, properties(.5, 2));
        assertEquals(new YsmRenderScale(.5f, 2, .5f), YsmRenderScale.forProfile(nativeProfile));
        assertSame(YsmRenderScale.IDENTITY, YsmRenderScale.forProfile(ordinaryProfile));
        assertSame(YsmRenderScale.IDENTITY, YsmRenderScale.forProfile(YsmModelProfile.empty()));
        assertSame(YsmRenderScale.IDENTITY, YsmRenderScale.forProfile(null));
        var point = new Vector3f(1, 2, 3);
        assertEquals(point, YsmRenderScale.forProfile(ordinaryProfile)
                .applyInitialPlayerBody(new Matrix4f(), ordinaryProfile.isYsm()).transformPosition(new Vector3f(point)));
        // Even a caller retaining a previous non-identity record must gate the INITIAL operation by profile type.
        assertEquals(point, YsmRenderScale.forProfile(nativeProfile)
                .applyInitialPlayerBody(new Matrix4f(), false).transformPosition(new Vector3f(point)));
        var nativePoint = YsmRenderScale.forProfile(nativeProfile)
                .applyInitialPlayerBody(new Matrix4f(), true).transformPosition(new Vector3f(point));
        assertEquals(.5, nativePoint.x, .000001);
        assertEquals(4.01, nativePoint.y, .000001, ".01 translation is outside author scale, rather than becoming .02");
        assertEquals(1.5, nativePoint.z, .000001);
    }

    @Test void finiteSourceFloatsPreserveZeroNegativeAndNumericStringsButRejectInvalidValues() {
        assertEquals(new YsmRenderScale(0, -2, 0), YsmRenderScale.fromProperties(properties(0, -2)));
        assertEquals(new YsmRenderScale(-.5f, 2, -.5f), YsmRenderScale.fromProperties(
                JsonParser.parseString("{\"height_scale\":\"-0.5\",\"width_scale\":\"2\"}").getAsJsonObject()));
        for (String source : List.of("null", "{}", "[1,2]", "true", "\"NaN\"", "\"Infinity\"", "1e100")) {
            JsonObject height = new JsonObject(); height.add("height_scale", JsonParser.parseString(source));
            JsonObject width = new JsonObject(); width.add("width_scale", JsonParser.parseString(source));
            assertThrows(IllegalArgumentException.class, () -> YsmRenderScale.fromProperties(height), source);
            assertThrows(IllegalArgumentException.class, () -> YsmRenderScale.fromProperties(width), source);
        }
        assertThrows(IllegalArgumentException.class, () -> new YsmRenderScale(Float.NaN, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new YsmRenderScale(1, Float.NEGATIVE_INFINITY, 1));
        assertThrows(IllegalArgumentException.class, () -> new YsmRenderScale(1, 1, Float.POSITIVE_INFINITY));
    }

    @Test void initialBodyTransformKeepsUserScaleRotationAndAttachedHandInTheSameParentMatrix() {
        var scale = YsmRenderScale.fromProperties(properties(.5, 2));
        var parent = new Matrix4f().translation(10, 20, 30).rotateY((float) Math.PI / 2).scale(.65f);
        Matrix4f body = scale.applyInitialPlayerBody(new Matrix4f(parent), true);
        Vector3f bodyPoint = body.transformPosition(new Vector3f(1, 2, 3));
        assertEquals(10.975, bodyPoint.x, .000002);
        assertEquals(22.6065, bodyPoint.y, .000002);
        assertEquals(29.675, bodyPoint.z, .000002);
        Vector3f attachedHand = new Matrix4f(body).translate(1, 2, 3).transformPosition(new Vector3f());
        assertEquals(bodyPoint.x, attachedHand.x, .000002);
        assertEquals(bodyPoint.y, attachedHand.y, .000002);
        assertEquals(bodyPoint.z, attachedHand.z, .000002, "The hand locator's origin inherits the complete INITIAL body parent");
        Vector3f nativeFoot = body.transformPosition(new Vector3f());
        assertEquals(20.0065, nativeFoot.y, .000002, "User scale also scales the source's pre-author .01 offset");
        Vector3f ordinaryFoot = scale.applyInitialPlayerBody(new Matrix4f(parent), false).transformPosition(new Vector3f());
        assertEquals(20, ordinaryFoot.y, .000002, "Server BB geometry receives neither author scale nor source offset");
        Vector3f recovered = new Matrix4f(body).invert().transformPosition(new Vector3f(bodyPoint));
        assertEquals(1, recovered.x, .00002);
        assertEquals(2, recovered.y, .00002);
        assertEquals(3, recovered.z, .00002,
                "Uniform user scale and non-uniform author scale must not be baked into animation clocks or bone poses");
    }

    private static JsonObject properties(double height, double width) {
        JsonObject result = new JsonObject();
        result.addProperty("height_scale", height); result.addProperty("width_scale", width);
        return result;
    }

    private static YsmModelProfile profile(boolean nativeYsm, JsonObject properties) {
        JsonObject manifest = new JsonObject();
        if (nativeYsm) manifest.addProperty("spec", 2);
        manifest.add("properties", properties);
        return new YsmModelProfile(manifest, new JsonObject(), new JsonObject(), Map.of(), List.of(), List.of(), "", "");
    }
}
