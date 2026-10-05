package com.simmc.meplayeractions.client.effects;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class YsmModelEffectsTest {
    @Test void nativeSoundFunctionsUseIdThenNameAndMatureRangesWithoutChangingBbmodelShorthand() {
        assertThrows(IllegalArgumentException.class, () -> YsmModelEffects.SoundRequest.parseNative(List.of("minecraft:entity.cat.ambient")));
        var nativeSound = YsmModelEffects.SoundRequest.parseNative(List.of("authorVoice", "voice", 7d, 1001d, .0001d));
        assertEquals("s:authorVoice", nativeSound.key());
        assertTrue(nativeSound.replace()); assertTrue(nativeSound.global()); assertTrue(nativeSound.loop());
        assertEquals(1000f, nativeSound.volume()); assertEquals(.001f, nativeSound.pitch());
        assertEquals("", YsmModelEffects.nativeSoundKey(-.5));
        assertThrows(IllegalArgumentException.class, () -> YsmModelEffects.nativeSoundKey(-1d));
        assertEquals("", YsmModelEffects.SoundRequest.parse(List.of("minecraft:entity.cat.ambient")).key());
    }
    @Test void localSoundIdsBelongToTheAuthorControllerAndGlobalUsesTheEntityFallbackManager() {
        assertNotEquals(YsmModelEffects.soundScope(true, false, "player.main"),
                YsmModelEffects.soundScope(true, false, "player.extra"));
        assertEquals(YsmModelEffects.soundScope(true, true, "player.main"),
                YsmModelEffects.soundScope(true, true, "player.extra"));
        assertEquals(YsmModelEffects.soundScope(true, true, "player.main"),
                YsmModelEffects.soundScope(true, false, ""));
        assertNotEquals(YsmModelEffects.soundScope(false, false, ""), YsmModelEffects.soundScope(false, true, ""));
    }
    @Test void soundParametersKeepAuthorFlagsAndKeyframeShorthand() {
        var sound = YsmModelEffects.SoundRequest.parse(List.of("voice", "music", 7d, 1000d, .001d));
        assertEquals("s:voice", sound.key()); assertEquals("music", sound.name());
        assertTrue(sound.replace()); assertTrue(sound.global()); assertTrue(sound.loop());
        assertEquals(4, sound.volume()); assertEquals(.01, sound.pitch(), .00001);
        var keyframe = YsmModelEffects.SoundRequest.parse(List.of("minecraft:entity.experience_orb.pickup"));
        assertEquals("", keyframe.key()); assertFalse(keyframe.loop()); assertEquals(1, keyframe.pitch());
        assertEquals("", YsmModelEffects.soundKey(0d)); assertEquals("n:4", YsmModelEffects.soundKey(4d));
        assertEquals("s:4", YsmModelEffects.soundKey("4"));
    }
    @Test void malformedSoundsCannotUsePathsNonfiniteNumbersOrUnsupportedFlags() {
        for (List<Object> args : List.<List<Object>>of(List.of(1d, "../secret"), List.of(1d, "sub/secret"), List.of(1d, "music", 8d),
                List.of(-1d, "music"), List.of(1d, "music", 0d, Double.NaN)))
            assertThrows(IllegalArgumentException.class, () -> YsmModelEffects.SoundRequest.parse(args));
    }
    @Test void particleSignatureMatchesOffsetDeltaSpeedCountLifetimeWithBoundedDefaults() {
        var defaults = YsmModelEffects.ParticleSpec.parse(List.of("minecraft:flame"));
        assertEquals(0, defaults.count()); assertEquals(20, defaults.lifetime()); assertEquals(0, defaults.speed());
        var explicit = YsmModelEffects.ParticleSpec.parse(List.of("minecraft:flame", 1d, 2d, 3d, 4d, 5d, 6d, .5d, 7d, 40d));
        assertEquals(1, explicit.offsetX()); assertEquals(3, explicit.offsetZ()); assertEquals(6, explicit.deltaZ());
        assertEquals(.5, explicit.speed()); assertEquals(7, explicit.count()); assertEquals(40, explicit.lifetime());
        var limited = YsmModelEffects.ParticleSpec.parse(List.of("minecraft:flame", 1000d, 0d, 0d, 0d, 0d, 0d, 100d, 1000d, 1000d));
        assertEquals(32, limited.offsetX()); assertEquals(4, limited.speed()); assertEquals(64, limited.count()); assertEquals(200, limited.lifetime());
        assertThrows(IllegalArgumentException.class, () -> YsmModelEffects.ParticleSpec.parse(List.of("minecraft:flame", Double.POSITIVE_INFINITY)));
    }
    @Test void absoluteOffsetsStillUseOwnerOriginButSkipYawRotation() {
        double[] relative = YsmModelEffects.rotate(0, 2, 1, 90, false);
        assertArrayEquals(new double[]{-1, 2, 0}, relative, .00001);
        assertArrayEquals(new double[]{0, 2, 1}, YsmModelEffects.rotate(0, 2, 1, 90, true), .00001);
    }
    @Test void soundAttemptBudgetDoesNotResetOnReleaseOrBindingReplacement() {
        var budget = new YsmModelEffects.Budget();
        for (int i = 0; i < 8; i++) { assertTrue(budget.acquire(true, 0)); budget.release(); }
        assertFalse(budget.acquire(true, 999_999_999)); assertEquals(0, budget.live());
        assertTrue(budget.acquire(true, 1_000_000_000)); budget.release();
        assertThrows(IllegalStateException.class, budget::release);
    }
    @Test void aggregateLiveCapAndParticleRateRemainSeparate() {
        var budget = new YsmModelEffects.Budget();
        for (int i = 0; i < 64; i++) assertTrue(budget.acquire(false, 0));
        assertFalse(budget.acquire(false, 0)); assertEquals(64, budget.live());
        for (int i = 0; i < 32; i++) budget.release();
        assertFalse(budget.acquire(false, 0));
        for (int i = 0; i < 32; i++) assertTrue(budget.acquire(false, 1_000_000_000));
        assertFalse(budget.acquire(true, 1_000_000_000)); assertEquals(64, budget.live());
    }
}
