"""Validate the latest Maven build and package only this project's deliverables."""
from __future__ import annotations

import hashlib
import argparse
import json
import math
import posixpath
from pathlib import Path
import re
import shutil
import struct
import xml.etree.ElementTree as ET
import zipfile
import zlib
from urllib.parse import quote

PROJECT = Path(__file__).resolve().parents[1]
WORKSPACE = PROJECT.parents[1]
DIST = WORKSPACE / "dist"
NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
ZIP_TIME = (2026, 10, 3, 0, 0, 0)
MODEL_IDS = ("ysm_01_jk", "ysm_02_jk")
BASELINE_VERSION = "0.4.2"
BASELINE_SERVER_SHA256 = "7e737aab6668c9a097260ec6a3bdc12b95c42ef4049c351012b450abcc992352"
BASELINE_VALIDATION_SHA256 = "1b3aa253a54f34bb22765bc076edb62c9936c699c4cfb0f8c58488c9e4caeb8b"
REFERENCE_GUI_REVISION = "0306e1fa3bbeaaf6fa8c1af89d87bb7a1c077b85"
REFERENCE_ROULETTE_SHA256 = "f9750bb56eef72d11b8e342952e9518ab077916461cf3f79c37738cae108b4a3"
WINE_FOX_MANIFEST_SHA256 = "e83719c9209559e0e55f41717d8a93f98b1bc14c5b404fda399fdb5c883b3a9a"
WINE_FOX_LICENSE_SHA256 = "329d5952cc9edbeb8310d68327f4684f7fd0ccc76f27aedc2fa6563e27ae34a4"
TARGETED_CLIENT_METHODS = {
    "com.simmc.meplayeractions.client.PlayerInteractionPolicyTest": (
        "independentClientAppearanceDoesNotNeedAWorldOrServerBridge",
        "newServerDisguiseDefaultsToServerEvenBeforeAssetsOrHandshakeAreReady",
        "anExplicitClientChoiceKeepsServerOwnershipAndEquipmentRules",
        "manualChoiceSurvivesSameInstanceSnapshotsReloadsAndTemporaryBindingLoss",
        "switchingBackStopsThePrivateScopeAndTheNextInstanceAgainDefaultsToServer",
        "explicitClientSelectionCannotBypassTheExistingFallbackVisibilityGuard",
        "onlyAnAuthoritativeRemovalResumesClientModeAfterFailedServerTakeover",
        "worldResetDoesNotCarryAnOverrideIntoTheNextServerDisguise",
    ),
    "com.simmc.meplayeractions.client.WheelPreferencesTest": (
        "oldConstructorAndDefaultsKeepTheWheelClosedAfterChoosingAnAction",
        "switchingSourcesRetainsSeparatePagesAndTheKeepOpenChoice",
        "aSmallerCatalogClampsOnlyTheDisplayedPageAndRetainsTheSavedMemory",
        "invalidPageMemoryAndNullSourcesAreRejectedBeforeSaving",
    ),
    "com.simmc.meplayeractions.client.ClientOptionsTest": (
        "legacyOptionsGainWheelDefaultsWithoutChangingPrivateModelChoices",
        "wheelSourcesPagesAndKeepOpenRoundTripIndependentlyOfActionLock",
        "invalidAndFutureWheelFieldsFallBackIndividuallyWithoutErasingValidSiblings",
        "malformedWheelSectionDoesNotPreventRestoringPrivateAppearanceAndProfiles",
        "ioFailureRollsBackWheelMemoryAndLeavesTheBlockingDirectoryUntouched",
    ),
    "com.simmc.meplayeractions.client.ui.WheelSelectionTest": (
        "savedPageIsClampedToChangedActualActionsIncludingAnEmptyServerList",
        "compactWheelStaysClearOfTitleSettingsAndOnlyReservesPagingWhenNeeded",
        "classicPolygonPositionsFollowTheUpstreamPositiveXAxisOrder",
        "classicPartialPageDrawsAndAcceptsOnlyItsActualEntries",
        "classicGapsAndInnerConfigurationRegionCannotDispatchNeighborActions",
        "classicSlicesContainFourStraightEdgesWithTheAuthoredGapAndVertexOrder",
        "classicWheelAndAuthorPanelFitTogetherInCompactAndWideViewports",
        "authorPanelScrollStopsAtBothEndsAndClampsAfterAGroupChange",
    ),
    "com.simmc.meplayeractions.client.model.BuiltinYsmModelsTest": (
        "catalogAddsOnlyTheThreeOfficialModelsAndKeepsExistingDefaults",
        "taishoMaidDecodesRealGeometryAnimationsComponentsSkinsAndSounds",
        "newYearDecodesItsOwnAuthoredPlayerAndLegacyArrow",
        "astronautDecodesArmAssemblyAndKeepsBlankAuthorActionLabels",
        "allSeventySevenOriginalResourceFilesRemainByteExactAtThePinnedRevision",
    ),
    "com.simmc.meplayeractions.client.LocalAppearanceSettingsTest": (
        "acceptsBundledAndSingleLocalModelFiles",
        "localModelSelectionCannotEscapeItsModelDirectory",
    ),
    "com.simmc.meplayeractions.client.LocalModelLibraryTest": (
        "modelPickerIncludesBundledAndSafeFilesAndLoadsTheSelectedFile",
        "pickerSupportsSafeYsmFoldersAndTheirRealPlayerAnimations",
    ),
    "com.simmc.meplayeractions.client.OpenYsmHoldMigrationTest": (
        "modernAndPrimaryAnimationsKeepTheirAuthoredLoopWhileOldSecondaryKeepsPredicateFallback",
        "rawFolderUsesInternalVersion65535AndActualDefaultSwordRetainsItsAuthoredHold",
        "realBundledSwordKeepsAuthoredFinalPosePastMultipleLengthsWithoutRestarting",
        "sameItemRequestsAndPauseKeepTheStartButAnActualWeaponSwitchRestartsExactlyOnce",
        "damagedTrackedWeaponsIgnoreComponentChangesButDifferentItemTypesStillRestart",
        "intactComponentAndCountChangesRestartEvenWhenTheirDiagnosticHashesCollide",
        "firstDurabilityChangeReadsThePreviouslyTrackedNativeStackDamageWithoutRestarting",
        "inPlaceTrackedStackChangesDoNotRestartButReplacementStacksWithDifferentComponentsDo",
        "authoredOnceDoesNotRestartAfterFinishingAndChangedRequestedLoopStartsANewRequest",
        "chargedAndFishingSpecialPredicatesDoNotResetForItemComponentsAndBothHandsUseTheSharedClock",
        "metadataInventoriesRejectUnknownOriginsMissingLoopsAndInvalidVersions",
    ),
    "com.simmc.meplayeractions.client.model.YsmAnimationMetadataBoundsTest": (
        "nativeTimelineRunsSixtyFourIndependentProgramsInOrderAndRejectsTheSixtyFifth",
        "nativeExplicitDurationIsFiniteAndIndependentOfLaterAuthoredKeysAndEvents",
        "timelineAggregateUtf8BudgetAndExistingRawByteBudgetRemainBounded",
        "ordinaryBbModelAndSoundParticleLimitsDoNotInheritNativeTimelineAllowance",
        "flaggedNativeFoxcarSignedCubeKeepsAllThreeFacesTheirCornerOrderUvsAndNormals",
        "signedCubeAllowanceRequiresNativeFormatAndExplicitFlagAndKeepsFiniteBounds",
    ),
    "com.simmc.meplayeractions.client.model.YsmFolderModelTest": (
        "officialTimelineArraysAndExplicitDurationsRemainUnexpanded",
        "sixtyFourTimelineProgramsKeepOrderAndIndependentReturns",
        "folderRejectsTimelineAndDurationBeyondObservedBoundedCompatibility",
        "officialFoxcarKeepsItsOriginalSignedCubeAndEveryAuthoredFace",
    ),
    "com.simmc.meplayeractions.client.ui.NativeGuiPreviewCameraTest": (
        "nativeConvertedProfilesUseFixedCameraWhileStandaloneBbModelsKeepTheirBoundsFit",
        "ownerCameraUsesNormalizedNativeHeightAndTheInventorySourceAnchor",
        "dummyCardUsesTheSourceScaleOffsetAndAuthoredFixedViewFlag",
        "aLargeAuthoredStageCannotShrinkOrRecenterThePreviewedActor",
        "nativeRotationPreservesUvDepthOrderingAndRejectsInvalidEntityDimensions",
        "cardDoesNotInheritOwnerPoseOrScale",
        "nativeAuthorScaleDefaultsToPointSevenWithoutChangingCameraAnchors",
        "nativeAuthorScaleUsesUpstreamNonUniformAxesForOwnerAndCard",
        "nativeAuthorScalePrecedesTheGeckoOffsetAndPreservesPerVertexDepth",
    ),
    "com.simmc.meplayeractions.client.ui.PreviewSceneTest": (
        "asynchronouslyLoadedCardStartsAtZeroWithOnlyItsAuthoredCap",
        "repeatedCardDrawsReuseTheirClockWithoutRepeatingTheEntranceOrBlink",
        "originalWhiteEyeLayersOpenCloseAndReopenOnTheSourceBlinkClock",
        "guiParameterEditReusesTheClockAndAnObsoleteCardPreviewStaysStopped",
        "authoredPreviewConditionTransformsRealVerticesWithoutChangingTheWorldBranch",
        "ownerUsesActualVanillaHandsAndMovementWithoutTheCardGui",
        "cardKeepsMainStoppedAndExplicitCapWithAuthoredLoopAndFalseExtraFlag",
        "ownerAndCardClocksAuthorVariablesAndResetsStayIndependent",
        "fourBuiltinsUseOnlyTheirDeclaredCardPreviewWithoutSyntheticHoverOrFocus",
    ),
    "com.simmc.meplayeractions.client.model.YsmRenderScaleTest": (
        "absentSourcePropertiesUsePointSevenLikeTheOfficialPlayerModels",
        "nonUniformSourceHeightScaleIsXZAndWidthScaleIsY",
        "onlyNativeYsmProfilesReceiveAuthorScaleAndTheInitialPlayerBodyOffset",
        "finiteSourceFloatsPreserveZeroNegativeAndNumericStringsButRejectInvalidValues",
        "initialBodyTransformKeepsUserScaleRotationAndAttachedHandInTheSameParentMatrix",
    ),
}
INTERACTION_STAGES = (
    "local-appearance-server-base", "local-appearance-own", "local-appearance-action", "local-appearance-off",
    "menu", "server-instance-auto", "server-equipment-armor", "server-equipment-elytra",
    "other-equipment-armor", "other-equipment-elytra", "private-equipment-suspended",
    "undisguise", "private-restored-ui", "private-equipment-restored",
)
OBSERVER_INTERACTION_STAGES = tuple(stage for stage in INTERACTION_STAGES
                                   if stage not in {"menu", "private-restored-ui", "private-equipment-restored"})


def observer_interaction_required_checks() -> set[str]:
    required = {"privateAppearanceBServerModelBaseline", "undisguiseTargetMeGeometryRemoved",
                "undisguiseUnmoddedNativeOwnerAndEquipmentRestored", "unmoddedNativeEquipmentStagesAllObserved",
                "allStagesCaptured", "observedArtifactHashesCaptured", "noMpaClientInstalled", "observerArtifactHashCaptured"}
    required.update(f"local-appearance-{stage}BServerModelAndTransformUnchanged" for stage in ("own", "action", "off"))
    required.update(stage + "UnmoddedObserverNoNativeEquipmentLeak" for stage in
                    ("server-equipment-armor", "server-equipment-elytra", "private-equipment-suspended"))
    required.update(stage + "UnmoddedOwnerActuallyEquipped" for stage in ("other-equipment-armor", "other-equipment-elytra"))
    return required


def standalone_interaction_required_checks() -> set[str]:
    return {
        "standaloneInteractionJOnlyBinding", "standaloneInteractionJOpensWheel", "standaloneInteractionReferenceWheel",
        "standaloneInteractionSingleSettings", "standaloneInteractionSettingsOpensModelScreen", "standaloneInteractionSources",
        "standaloneInteractionReferenceModelScreen", "standaloneInteractionClientGallery", "standaloneInteractionClientModelSettings",
        "standaloneInteractionAdvancedPreferences", "standaloneInteractionPreferencePersistence", "standaloneInteractionInlineAuthorForms",
        "standaloneInteractionLocalActionNoServerRequest", "standaloneInteractionWheelMemory", "standaloneInteractionNoServerAuthority",
        "standaloneInteractionControlsInBounds", "standaloneInteractionCleanup",
        "standaloneWineFoxTaishoMaidGuiAndWorld", "standaloneWineFoxNewYearGuiAndWorld", "standaloneWineFoxAstronautGuiAndWorld",
    }


def validate_interaction_frames(game: dict, observer: dict) -> None:
    assert game.get("requiredStages") == list(INTERACTION_STAGES), "Focused A stage contract changed"
    stages = [row.get("stage") for row in game.get("interactionDelivery", {}).get("observations", [])]
    assert all(stages.count(stage) == 1 for stage in INTERACTION_STAGES), "Missing or duplicate focused A frame"
    screenshots = set(game.get("screenshots", []))
    callbacks = game.get("screenshotCallbacks", [])
    for number, stage in enumerate(INTERACTION_STAGES, 1):
        filename = f"screenshots/{number:02d}-{stage}.png"
        assert filename in screenshots, f"Missing focused A stage screenshot: {stage}"
        assert any(row.get("file") == filename and row.get("fileWritten") is True
                   and game["startedAtMillis"] <= row.get("completedAtMillis", 0) <= game["completedAtMillis"]
                   for row in callbacks), f"Missing current screenshot completion: {stage}"
    assert set(observer.get("requiredStages", [])) == set(OBSERVER_INTERACTION_STAGES), "Focused B stage contract changed"
    assert set(OBSERVER_INTERACTION_STAGES) <= set(observer.get("captured", [])), "Missing focused B captured stage"
    assert set(OBSERVER_INTERACTION_STAGES) <= {row.get("stage") for row in observer.get("frames", [])}, "Missing focused B frame"
    assert {f"screenshots/observer-{stage}.png" for stage in OBSERVER_INTERACTION_STAGES} <= set(observer.get("screenshots", [])), \
        "Missing focused B stage screenshots"
    assert observer_interaction_required_checks() <= {row["name"] for row in observer["checks"]}, "Incomplete focused B independent checks"


def interaction_required_checks() -> set[str]:
    required = set(("savedPrivateAppearanceVisibleBeforeServerDisguise", "serverDisguiseSuppressesPrivateAppearance",
                    "JOpensServerWheel", "serverSettingsHidePrivateModelControls", "manualPrivateOverrideCurrentMeshAndTransform",
                    "sameServerInstanceReopensClientWheel", "manualPrivateWheelActionNoServerRequest",
                    "manualPrivateActionRemoteBindingUnchanged", "manualServerSelectionRestoresOriginalBinding",
                    "playerModelJBindingOnly", "serverWheelHasOnlyActionsAndSettings", "wheelReopenSameScopePage",
                    "playerModelServerWheelActualAction", "sameInstanceAllowsExplicitPrivateOverride",
                    "newServerInstanceResetsWheelSource", "undisguiseRestoresSavedPrivateAppearance",
                    "undisguiseRestoresClientWheel", "undisguiseRestoresPrivateSettingsUi", "undisguisePushOwnerReleased",
                    "playerModelHomeUsesReferenceGallery", "wheelReferencePolygonsActuallyRendered",
                    "wheelAuthorFormSameScreenActualCallback", "wheelAuthorFormRestoredNoServerRequest",
                    "defaultHeldItemAuthoredLoopMetadata", "defaultHeldItemHoldsWithoutReplay", "defaultHeldItemChangeRestartsOnce"))
    for stage in ("local-appearance-own", "local-appearance-off", "private-equipment-suspended"):
        required.update(stage + suffix for suffix in ("RemoteBindingUnchanged", "NoServerRequest", "SavedPrivateProfilePreserved"))
    for stage in ("server-equipment-armor", "server-equipment-elytra", "other-equipment-armor", "other-equipment-elytra",
                  "private-equipment-suspended", "private-equipment-restored"):
        required.update(stage + suffix for suffix in ("NativeEquipmentSource", "EquipmentRenderPolicy", "SwordShieldStillSubmitted"))
    return required


def checked_delivery(report: dict, key: str, minimum_checks: set[str]) -> dict:
    assert report.get("validationProfile") == "interactions", "Focused acceptance profile was not explicitly selected"
    delivery = report.get(key, {})
    assert delivery.get("scope") == "interactions" and delivery.get("passed") is True, f"Failed focused delivery: {key}"
    required = delivery.get("requiredChecks")
    assert isinstance(required, list) and required and all(isinstance(name, str) and name for name in required)
    assert len(set(required)) == len(required) and minimum_checks <= set(required), f"Incomplete focused contract: {key}"
    checks = delivery.get("checks")
    assert isinstance(checks, list) and checks and all(row.get("passed") is True for row in checks), f"Failed focused checks: {key}"
    names = [row["name"] for row in checks]
    assert set(required) <= set(names), f"Missing required focused checks: {key}"
    assert isinstance(delivery.get("observations"), list) and delivery["observations"], f"Missing actual focused observations: {key}"
    return delivery


def validate_held_item_delivery(delivery: dict, client_jar: Path) -> None:
    playback = delivery.get("heldItemPlayback", {})
    assert playback.get("sourcePassed") is True and playback.get("continuous") is True and playback.get("step", 0) >= 4, \
        "Missing completed authored HOLD playback observation"
    source = playback.get("source", {})
    assert source.get("readSucceeded") is True and source.get("modelId") == "openysm_default" \
        and source.get("manifestSpec") == 2 and source.get("convertedFormatVersion") == 65_535
    root = "assets/meplayeractions/builtin/openysm_default/"
    with zipfile.ZipFile(client_jar) as archive:
        manifest_bytes = archive.read(root + "ysm.json")
        manifest = json.loads(manifest_bytes)
        assert source.get("manifestSha256") == hashlib.sha256(manifest_bytes).hexdigest()
        assert source.get("declaredPlayerFiles") == manifest["files"]["player"]
        for family in ("main", "extra", "arm"):
            declared = manifest["files"]["player"]["animation"][family]
            data = archive.read(root + declared)
            actual = source["primarySources"][family]
            assert actual["declaredPath"] == declared and actual["bytes"] == len(data) \
                and actual["sha256"] == hashlib.sha256(data).hexdigest(), "Held-item proof used a different primary animation source"
        arm = json.loads(archive.read(root + manifest["files"]["player"]["animation"]["arm"]))["animations"]
    clips = source.get("clips", [])
    assert len(clips) == 2 and {clip["hand"] for clip in clips} == {"mainhand", "offhand"}
    for clip in clips:
        name = "hold_" + clip["hand"] + ":sword"
        assert clip["animation"] == name and clip["rawClip"] == arm[name]
        assert clip["rawLoop"] == arm[name]["loop"] == "hold_on_last_frame" and clip["convertedLoop"] == "HOLD" \
            and clip["fromPrimaryAssembly"] is True
        assert clip["rawLengthSeconds"] == arm[name]["animation_length"] \
            and abs(clip["convertedLengthTicks"] - clip["rawLengthSeconds"] * 20) < 1e-7
    required_window = max(clip["convertedLengthTicks"] for clip in clips) * 2
    assert required_window > 0 and playback.get("requiredWindowTicks") == required_window
    assert playback.get("emptyTransition", {}).get("actualMainEmpty") is True \
        and playback["emptyTransition"].get("actualOffEmpty") is True, "No actual empty-hand transition was rendered"
    for hand in ("mainhand", "offhand"):
        initial = playback["initialSwordStartedAt"][hand]
        restarted = playback["restartedSwordStartedAt"][hand]
        assert restarted > initial, "Weapon switch did not produce a new real layer start"
        counts = playback["actualObservedStartCounters"][hand]
        assert counts["observedIronSwordStarts"] == counts["observedDiamondSwordStarts"] == 1 \
            and counts["ironStartedAtTicks"] == [initial] and counts["diamondStartedAtTicks"] == [restarted], "Held swords replayed during a fixed observation window"
    frames = playback.get("frames", [])
    assert frames and all(frame.get("valid") is True for frame in frames)
    for step in (1, 3):
        window = [frame for frame in frames if frame.get("step") == step]
        assert len(window) >= 20 and max(frame["windowAgeTicks"] for frame in window) >= required_window, \
            "Held-item proof omitted its fixed multi-length observation window"


def source_render_scale(properties: dict) -> dict[str, float]:
    """Preserve the original height->XZ/width->Y finite-float property semantics."""
    def source_float(key: str) -> float:
        value = properties.get(key, .7)
        assert type(value) in (int, float, str), f"Invalid authored {key}"
        converted = struct.unpack(">f", struct.pack(">f", float(value)))[0]
        assert math.isfinite(converted), f"Non-finite authored {key}"
        return converted
    height, width = source_float("height_scale"), source_float("width_scale")
    return {"x": height, "y": width, "z": height}


def read_png_pixels(data: bytes) -> tuple[int, int, int, bytes]:
    """Bounded stdlib decoding for the RGB/RGBA PNGs produced by Minecraft."""
    assert data.startswith(b"\x89PNG\r\n\x1a\n") and len(data) <= 64 * 1024 * 1024
    offset, compressed, dimensions, ended = 8, bytearray(), None, False
    while offset + 12 <= len(data):
        size = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        assert offset + size + 12 <= len(data), "Truncated PNG chunk"
        payload = data[offset + 8:offset + 8 + size]
        assert zlib.crc32(kind + payload) & 0xffffffff == struct.unpack_from(">I", data, offset + size + 8)[0]
        if kind == b"IHDR":
            assert dimensions is None and size == 13
            width, height, bits, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", payload)
            assert 0 < width <= 4096 and 0 < height <= 4096 and bits == 8 and color in {2, 6} \
                and compression == filtering == interlace == 0, "Unsupported Minecraft PNG format"
            dimensions = (width, height, 3 if color == 2 else 4)
        elif kind == b"IDAT":
            assert dimensions is not None
            compressed.extend(payload)
        elif kind == b"IEND":
            assert size == 0 and offset + 12 == len(data)
            ended = True
            break
        offset += size + 12
    assert ended and dimensions is not None and compressed, "Incomplete PNG"
    width, height, channels = dimensions
    stride = width * channels
    maximum = height * (stride + 1)
    assert maximum <= 64 * 1024 * 1024
    decoder = zlib.decompressobj()
    filtered = decoder.decompress(compressed, maximum + 1)
    assert len(filtered) == maximum and decoder.eof and not decoder.unused_data and not decoder.unconsumed_tail
    pixels = bytearray(height * stride)
    for row in range(height):
        start = row * (stride + 1)
        mode = filtered[start]
        assert 0 <= mode <= 4, "Invalid PNG filter"
        current = bytearray(filtered[start + 1:start + stride + 1])
        previous = pixels[(row - 1) * stride:row * stride] if row else bytes(stride)
        for index in range(stride):
            left = current[index - channels] if index >= channels else 0
            above = previous[index]
            upper_left = previous[index - channels] if index >= channels else 0
            if mode == 1: predictor = left
            elif mode == 2: predictor = above
            elif mode == 3: predictor = (left + above) // 2
            elif mode == 4:
                guess = left + above - upper_left
                distances = (abs(guess - left), abs(guess - above), abs(guess - upper_left))
                predictor = (left, above, upper_left)[distances.index(min(distances))]
            else: predictor = 0
            current[index] = (current[index] + predictor) & 255
        pixels[row * stride:(row + 1) * stride] = current
    return width, height, channels, bytes(pixels)


def polygon_area(points: list[list[float]]) -> float:
    assert len(points) == 4 and all(len(point) == 2 and all(type(axis) in (int, float) and math.isfinite(axis)
                                                        for axis in point) for point in points)
    return abs(sum(points[index][0] * points[(index + 1) % 4][1]
                   - points[(index + 1) % 4][0] * points[index][1] for index in range(4))) * .5


def inside_polygon(points: list[list[float]], x: float, y: float) -> bool:
    sign = 0
    for index, point in enumerate(points):
        following = points[(index + 1) % 4]
        cross = (following[0] - point[0]) * (y - point[1]) - (following[1] - point[1]) * (x - point[0])
        if abs(cross) > 1e-7:
            direction = 1 if cross > 0 else -1
            if sign and direction != sign: return False
            sign = direction
    return sign != 0


def validate_default_projected_visibility(source: dict, proof: dict, archive: zipfile.ZipFile,
                                          authored: dict, root: str) -> None:
    visual = source["defaultVisual"]
    geometry_path = root + authored["files"]["player"]["model"]["main"]
    geometry_bytes = archive.read(geometry_path)
    geometry = json.loads(geometry_bytes)["minecraft:geometry"][0]
    bones = {bone["name"]: bone for bone in geometry["bones"]}
    expected_regions = {}
    for name in ("Head", "RightEyelid", "LeftEyelid"):
        face = bones[name]["cubes"][0]["uv"]["north"]
        expected_regions[name] = [*face["uv"], face["uv"][0] + face["uv_size"][0], face["uv"][1] + face["uv_size"][1]]
    expected_head_regions = []
    for face in bones["Head"]["cubes"][0]["uv"].values():
        u, v = face["uv"]
        du, dv = face["uv_size"]
        expected_head_regions.append([min(u, u + du), min(v, v + dv), max(u, u + du), max(v, v + dv)])
    texture_path = root + authored["files"]["player"]["texture"][0]
    texture_bytes = archive.read(texture_path)
    width, height, channels, texture_pixels = read_png_pixels(texture_bytes)
    assert visual["geometry"] == "/" + geometry_path and visual["geometrySha256"] == hashlib.sha256(geometry_bytes).hexdigest() \
        and visual["texture"] == "/" + texture_path and visual["textureSha256"] == hashlib.sha256(texture_bytes).hexdigest()
    assert visual["frontUvPixels"] == expected_regions and visual["textureWidth"] == width == geometry["description"]["texture_width"] \
        and visual["textureHeight"] == height == geometry["description"]["texture_height"]
    assert visual["headUvPixels"] == expected_head_regions and len(expected_head_regions) == 6
    uv = expected_regions["RightEyelid"]
    source_colors = []
    for y in range(int(uv[1]), int(uv[3])):
        for x in range(int(uv[0]), int(uv[2])):
            start = (y * width + x) * channels
            r, g, b = texture_pixels[start:start + 3]
            alpha = texture_pixels[start + 3] if channels == 4 else 255
            color = (alpha << 24) | (r << 16) | (g << 8) | b
            source_colors.append(color if color < 2**31 else color - 2**32)
    assert visual["whiteSourcePixels"] == source_colors and source_colors \
        and all(color >> 24 & 255 == 255 and min(color >> 16 & 255, color >> 8 & 255, color & 255) >= 200
                and max(color >> 16 & 255, color >> 8 & 255, color & 255) - min(color >> 16 & 255, color >> 8 & 255, color & 255) <= 24
                for color in source_colors), "Original default eye pixels must remain opaque neutral white"
    clips = json.loads(archive.read(root + authored["files"]["player"]["animation"]["main"]))["animations"]
    blink = clips["pre_parallel1"]
    opens = [float(time) * 20 for name in ("RightEyelid", "LeftEyelid")
             for time, frame in blink["bones"][name]["scale"].items() if frame["post"][1] == 1]
    cycle, safe_start = blink["animation_length"] * 20, math.ceil(max(opens)) + 2
    assert visual["blinkAnimation"] == "pre_parallel1" and visual["blinkCycleTicks"] == cycle \
        and visual["blinkSafeOpenStartTicks"] == safe_start and visual["blinkSafeEndMarginTicks"] == 3
    projected = proof["defaultProjectedVisibility"]
    assert projected.get("passed") is True and projected.get("source") == visual
    contexts = projected["contexts"]
    assert len(contexts) == 2 and {context["context"] for context in contexts} == {"OWNER", "CARD"}
    for context in contexts:
        draw = proof["owner" if context["context"] == "OWNER" else "card"]
        age = draw["sampleTick"]
        assert context.get("passed") is True and context.get("sourceBlinkSafeOpen") is True \
            and context.get("headFullyInsideClip") is True and context["sampleTick"] == age \
            and abs(context["blinkPhaseTicks"] - age % cycle) < 1e-7 and safe_start <= age % cycle < cycle - 3
        clip = context["clip"]
        assert clip == draw["clip"] and clip["width"] > 0 and clip["height"] > 0
        assert len(context["eyeFaces"]) == 2 and len(context["headFaces"]) == 6 \
            and len({json.dumps(face["points"]) for face in context["eyeFaces"]}) == 2
        head_bounds = []
        head_front_visible = False
        for faces, regions, area_limit, actual in ((context["eyeFaces"], [expected_regions["RightEyelid"]], .25, draw["faces"]),
                                                 (context["headFaces"], expected_head_regions, None, draw["headFaces"])):
            for face in faces:
                area = polygon_area(face["points"])
                assert face in actual and (area_limit is None or area > area_limit) \
                    and all(clip["x"] <= point[0] <= clip["x"] + clip["width"]
                            and clip["y"] <= point[1] <= clip["y"] + clip["height"] for point in face["points"]), \
                    "Default head or white eye is clipped by the actual native GUI viewport"
                depths = face["depths"]
                assert len(depths) == 4 and all(type(depth) in (int, float) and math.isfinite(depth) for depth in depths)
                uvs, atlas_uvs, atlas = face["sourceUv"], face["atlasUv"], face["atlas"]
                assert len(uvs) == len(atlas_uvs) == 4 and len(atlas) == 6 and all(value > 0 for value in atlas[2:])
                bounds = [min(uv[0] for uv in uvs) * width, min(uv[1] for uv in uvs) * height,
                          max(uv[0] for uv in uvs) * width, max(uv[1] for uv in uvs) * height]
                assert any(all(abs(a - b) < .001 for a, b in zip(bounds, region)) for region in regions)
                if area_limit is None:
                    head_bounds.append(bounds)
                    head_front_visible |= area > 1 and all(abs(a - b) < .001 for a, b in zip(bounds, expected_regions["Head"]))
                assert all(abs(mapped[0] * atlas[4] - atlas[0] - uv[0] * atlas[2]) < .001
                           and abs(mapped[1] * atlas[5] - atlas[1] - uv[1] * atlas[3]) < .001
                           for uv, mapped in zip(uvs, atlas_uvs)), "Actual native GUI UVs differ from the source eye/head face"
        assert head_front_visible and all(all(abs(a - b) < .001 for a, b in zip(actual, expected))
                                          for actual, expected in zip(sorted(head_bounds), sorted(expected_head_regions))), \
            "Default head proof omits an authored side or its visible front face"


def validate_default_png_visibility(pixels_proof: dict, context_proof: dict, report_path: Path, report: dict) -> None:
    assert pixels_proof.get("passed") is True
    path = Path(pixels_proof["screenshot"]).resolve()
    assert path.is_relative_to(report_path.parent.resolve()) and path.relative_to(report_path.parent.resolve()).as_posix() in report["screenshots"]
    data = path.read_bytes()
    assert hashlib.sha256(data).hexdigest() == pixels_proof["sha256"], "Default eye readback belongs to another screenshot"
    width, height, channels, image = read_png_pixels(data)
    assert (width, height) == (pixels_proof["width"], pixels_proof["height"]) \
        == (pixels_proof["framebufferWidth"], pixels_proof["framebufferHeight"])
    scale = pixels_proof["windowScaleFactor"]
    assert type(scale) is int and scale > 0 \
        and type(pixels_proof["scaledWidth"]) is int and type(pixels_proof["scaledHeight"]) is int \
        and math.ceil(width / scale) == pixels_proof["scaledWidth"] \
        and math.ceil(height / scale) == pixels_proof["scaledHeight"], \
        "Default eye readback uses different window/framebuffer coordinates"
    source_colors = context_proof["defaultProjectedVisibility"]["source"]["whiteSourcePixels"]
    minimum_source = min(min(color >> 16 & 255, color >> 8 & 255, color & 255) for color in source_colors)
    threshold = math.floor(minimum_source * .45)
    assert pixels_proof["minimumWhiteChannel"] == threshold and pixels_proof["maximumWhiteChannelSpread"] == 24
    contexts = pixels_proof["contexts"]
    assert len(contexts) == 2 and {context["context"] for context in contexts} == {"OWNER", "CARD"}
    sx = sy = scale
    for context in contexts:
        projected = next(row for row in context_proof["defaultProjectedVisibility"]["contexts"] if row["context"] == context["context"])
        assert context.get("passed") is True and len(context["eyes"]) == 2
        for eye, face in zip(context["eyes"], projected["eyeFaces"]):
            points = face["points"]
            assert eye["points"] == points
            left = max(0, math.floor(min(point[0] for point in points) * sx))
            right = min(width, math.ceil(max(point[0] for point in points) * sx))
            top = max(0, math.floor(min(point[1] for point in points) * sy))
            bottom = min(height, math.ceil(max(point[1] for point in points) * sy))
            tested, visible, samples = 0, 0, []
            for y in range(top, bottom):
                for x in range(left, right):
                    if not inside_polygon(points, (x + .5) / sx, (y + .5) / sy): continue
                    tested += 1
                    start = (y * width + x) * channels
                    r, g, b = image[start:start + 3]
                    if min(r, g, b) >= threshold and max(r, g, b) - min(r, g, b) <= 24:
                        visible += 1
                        alpha = image[start + 3] if channels == 4 else 255
                        color = (alpha << 24) | (r << 16) | (g << 8) | b
                        if len(samples) < 16: samples.append([x, y, color if color < 2**31 else color - 2**32])
            assert tested == eye["testedPixels"] and visible == eye["visibleNeutralEyePixels"] and visible > 0 \
                and samples == eye["pixelSamples"], "Default white-eye readback does not match the actual framebuffer PNG"


def validate_native_preview_gpu(draw: dict) -> None:
    """Preparation labels alone cannot prove an offscreen render/flush occurred."""
    backend = draw.get("nativeBackend", {})
    assert backend.get("backend") == "minecraft-special-gui-offscreen" \
        and backend.get("pipeline") == "meplayeractions:pipeline/gui_model_native_depth" \
        and backend.get("shader") == backend.get("fragmentShader") == "minecraft:core/position_tex_color" \
        and backend.get("depthTest") == "LEQUAL_DEPTH_TEST" and backend.get("depthWrite") is True \
        and backend.get("perVertexDepth") is True, "Native GUI did not use its actual depth-writing shader pipeline"
    execution = draw.get("nativeExecution", {})
    renders, decorations = execution.get("renderCount"), draw.get("decorationQuads")
    assert type(renders) is int and renders > 0 and type(decorations) is int \
        and 0 <= decorations < draw["quads"] \
        and execution.get("emittedVertices") == (draw["quads"] - decorations) * 4 * renders \
        and execution.get("colorAttachment") is True and execution.get("depthAttachment") is True \
        and execution.get("colorFormat") == "RGBA8" and execution.get("depthFormat") == "DEPTH32" \
        and execution.get("targetWidth", 0) > 0 and execution.get("targetHeight", 0) > 0, \
        "Native GUI proof lacks actual mesh emission to live RGBA8/DEPTH32 attachments"
    depths = draw.get("modelVertexDepthRange")
    assert isinstance(depths, list) and len(depths) == 2 \
        and all(type(value) in (int, float) and math.isfinite(value) for value in depths) \
        and depths[0] < depths[1], "Native GUI did not retain the submitted model's per-vertex depth range"


def validate_native_gui_preview(source: dict, proof: dict, archive: zipfile.ZipFile,
                                model_id: str, root: str, actual_draws: list[dict]) -> None:
    """Compare actual owner/card submissions with the authored primary clip and native camera."""
    manifest_bytes = archive.read(root + "ysm.json")
    authored = json.loads(manifest_bytes)
    properties = authored["properties"]
    name = properties["preview_animation"]
    animation_path = root + authored["files"]["player"]["animation"]["main"]
    animation_bytes = archive.read(animation_path)
    clip = json.loads(animation_bytes)["animations"][name]
    raw_loop = clip.get("loop", False)
    loop = "LOOP" if raw_loop is True else "HOLD" if raw_loop == "hold_on_last_frame" else "ONCE"
    length = clip.get("animation_length", 0)
    animation_sha = hashlib.sha256(animation_bytes).hexdigest()
    expected_scale = source_render_scale(properties)
    assert source.get("modelId") == model_id and source.get("manifest") == "/" + root + "ysm.json" \
        and source.get("manifestSha256") == hashlib.sha256(manifest_bytes).hexdigest()
    assert source.get("properties") == properties and source.get("animationResource") == "/" + animation_path \
        and source.get("animationSha256") == animation_sha and source.get("animation") == name \
        and source.get("loop") == loop and source.get("length") == length and source.get("clipPresent") is True, \
        "GUI preview proof differs from its authored primary animation"
    locked = properties.get("disable_preview_rotation", False)
    background, foreground = properties.get("gui_background", ""), properties.get("gui_foreground", "")
    assert source.get("disableCardRotation") == locked and source.get("background") == background \
        and source.get("foreground") == foreground
    shape = source["shape"]
    assert shape.get("heightScalePropertyPresent") == ("height_scale" in properties) \
        and shape.get("widthScalePropertyPresent") == ("width_scale" in properties) \
        and abs(shape["heightScale"] - expected_scale["x"]) < 1e-5 and abs(shape["widthScale"] - expected_scale["y"]) < 1e-5 \
        and len(shape["modelScale"]) == 3 and all(abs(actual - expected) < 1e-5 for actual, expected in zip(shape["modelScale"], expected_scale.values())) \
        and abs(shape["initialYOffset"] - .01) < 1e-5 and proof.get("sourceShape") == shape
    assert all(proof.get(flag) is True for flag in ("passed", "ownerValid", "cardValid", "independentContexts"))
    assert proof.get("sourcePreviewAnimation") == name and proof.get("sourcePreviewAnimationSha256") == animation_sha \
        and proof.get("sourcePreviewAnimationLoop") == loop and proof.get("sourcePreviewAnimationLength") == length
    owner, card = proof.get("owner", {}), proof.get("card", {})
    assert owner in actual_draws and card in actual_draws and owner != card \
        and owner.get("key") == card.get("key"), "GUI context proof is not from both actual current submissions"

    def finite(value: object) -> bool:
        return type(value) in (int, float) and math.isfinite(value)

    def near(draw: dict, key: str, expected: float) -> bool:
        return finite(draw.get(key)) and abs(draw[key] - expected) < 1e-5

    for draw in (owner, card):
        assert draw.get("nativeCamera") is True and draw.get("startCount") == 1 \
            and finite(draw.get("sampleTick")) and draw["sampleTick"] >= 12 \
            and finite(draw.get("startedAtTick")) and .999 <= draw.get("entryProgress", -1) <= 1 \
            and finite(draw.get("vertices")) and draw["vertices"] > 0 \
            and finite(draw.get("quads")) and draw["quads"] > 0 and draw["vertices"] == draw["quads"] * 4
        validate_native_preview_gpu(draw)
        assert all(finite(draw.get("modelScale", {}).get(axis))
                   and abs(draw["modelScale"][axis] - expected) < 1e-5 for axis, expected in expected_scale.items()), \
            "GUI model scale differs from the authored INITIAL height/width properties"
        requests = draw.get("layerRequests")
        assert isinstance(requests, list) and requests and draw.get("controllerSlots") == [request["layer"] for request in requests] \
            and draw.get("layers") == [request["animation"] for request in requests] \
            and all(finite(request.get("startedAtTick")) and request.get("loop") in {"LOOP", "ONCE", "HOLD"} for request in requests), \
            "GUI preview lacks real layer/animation/loop submissions"
    height, scale = proof.get("actualOwnerNativeHeight"), proof.get("actualOwnerNativeScale")
    assert finite(height) and finite(scale) and height > 0 and scale > 0
    assert owner.get("context") == "OWNER" and owner.get("cameraSource") == "openysm-owner-inventory" \
        and near(owner, "displaySize", 70) and near(owner, "pixelsPerBlock", 70) \
        and near(owner, "nativeEntityHeight", height) and near(owner, "nativeEntityScale", scale) \
        and near(owner, "anchorY", height / scale * .5 + .0625) and owner.get("rotationDisabled") is False \
        and finite(owner.get("yaw")) and finite(owner.get("pitch")) \
        and owner.get("background") == owner.get("foreground") == "" and near(owner, "decorationQuads", 0) \
        and "player.cap" not in owner["controllerSlots"] and name not in owner["layers"], \
        "Owner GUI preview is using the dummy animation or the old bounds-fit stage"
    assert card.get("context") == "CARD" and card.get("cameraSource") == "openysm-dummy-card" \
        and near(card, "displaySize", 30) and near(card, "pixelsPerBlock", 30) \
        and near(card, "nativeEntityHeight", 1.8) and near(card, "nativeEntityScale", 1) \
        and near(card, "anchorY", .9 + (5.5 / 30 if locked else 0)) \
        and card.get("rotationDisabled") == locked and near(card, "yaw", 0 if locked else -20) \
        and near(card, "pitch", 0 if locked else -10) and card.get("background") == background \
        and card.get("foreground") == foreground and near(card, "decorationQuads", bool(background) + bool(foreground)) \
        and card["controllerSlots"] == ["player.cap"] and card["layers"] == [name] \
        and card["layerRequests"][0]["loop"] == loop, "Dummy card does not use the source cap, fixed camera and decorations"
    if model_id == "openysm_default":
        validate_default_projected_visibility(source, proof, archive, authored, root)


def validate_wine_fox_delivery(report: dict, client_jar: Path) -> None:
    """Require actual card/preview and fresh world draws for each pinned builtin."""
    manifest_path = PROJECT / "client/builtin-wine-fox-manifest.json"
    assert digest(manifest_path) == WINE_FOX_MANIFEST_SHA256
    models = read_json(manifest_path)["models"]
    delivery = report["interactionDelivery"]
    rows = delivery.get("wineFoxModels")
    assert isinstance(rows, list) and len(rows) == 3 and [row.get("modelId") for row in rows] == \
        [model["id"] for model in models], "Missing or duplicate actual Wine Fox GUI/world observations"
    jar_sha = digest(client_jar)
    loaded_origin = Path(report["artifactProof"]["clientSource"]).resolve()
    assert loaded_origin.is_file() and loaded_origin.suffix == ".jar" and digest(loaded_origin) == jar_sha

    def positive(value: object) -> bool:
        return type(value) in (int, float) and math.isfinite(value) and value > 0

    def origin_from_jar(origin: dict, class_name: str) -> None:
        assert origin.get("class") == class_name and origin.get("fromJar") is True \
            and origin.get("sha256") == jar_sha and Path(origin["path"]).resolve() == loaded_origin, \
            "Wine Fox rendering or registry came from a different loaded JAR"

    with zipfile.ZipFile(client_jar) as archive:
        for model, row in zip(models, rows):
            model_id = model["id"]
            root = "/" + model["resource_root"]
            source = row.get("source", {})
            raw = archive.read(model["resource_root"] + "ysm.json")
            authored = json.loads(raw)
            assert source.get("resourceRoot") == root and source.get("registryModelId") == model_id \
                and source.get("registryLabel") == model["label"] and source.get("manifest") == root + "ysm.json"
            assert source.get("manifestSha256") == hashlib.sha256(raw).hexdigest() \
                and source.get("spec") == authored["spec"] and source.get("declaredFiles") == authored["files"], \
                "Wine Fox observation used different authored files or manifest"
            geometry_path = authored["files"]["player"]["model"]["main"]
            geometry = archive.read(model["resource_root"] + geometry_path)
            names = list(dict.fromkeys(bone["name"] for bone in json.loads(geometry)["minecraft:geometry"][0]["bones"]))
            assert names and source.get("primaryGeometry") == root + geometry_path \
                and source.get("primaryGeometrySha256") == hashlib.sha256(geometry).hexdigest() \
                and source.get("primaryBoneNames") == names, "Wine Fox primary geometry source differs"
            assert row.get("sourceClassesFromTestedJar") is True and source.get("expectedClientJarSha256") == jar_sha
            origin_from_jar(source["registryClassOrigin"], "com.simmc.meplayeractions.client.model.BuiltinYsmModels")
            origin_from_jar(source["modelRendererClassOrigin"], "com.simmc.meplayeractions.client.render.ModelRenderer")
            asset_hash = row.get("assetHash", "")
            assert isinstance(asset_hash, str) and re.fullmatch(r"[a-f0-9]{64}", asset_hash)
            preview, world = row.get("preview", {}), row.get("world", {})
            assert preview.get("modelId") == model_id and preview.get("hash") == world.get("hash") == asset_hash
            assert preview.get("draftCallback") is True and preview.get("useCallback") is True \
                and positive(preview.get("vertices")) and positive(preview.get("emittedVertices")) \
                and preview["emittedVertices"] > preview["baselineEmittedVertices"], "No real Wine Fox preview emission/use callback"
            card, left = preview.get("selectedCard", {}), preview.get("leftPreview", {})
            assert card.get("modelId") == left.get("modelId") == model_id \
                and all(card.get(flag) is True for flag in ("loaded", "drawn", "selected")) \
                and left.get("drawn") is True and left.get("key") == model_id + ":" + asset_hash
            draws = preview.get("actualDraws", [])
            assert len(draws) >= 2 and all(draw.get("key") == left["key"] and positive(draw.get("vertices"))
                and positive(draw.get("quads")) and draw["vertices"] == draw["quads"] * 4
                and .999 <= draw.get("entryProgress", -1) <= 1
                and all(positive(draw.get(axis)) for axis in ("width", "height")) for draw in draws), \
                "Wine Fox main preview and thumbnail were not actually submitted"
            viewport = lambda draw: tuple(draw[axis] for axis in ("x", "y", "width", "height"))
            assert viewport(left) in {viewport(draw) for draw in draws} \
                and any(card["x"] <= draw["x"] and card["y"] <= draw["y"]
                        and draw["x"] + draw["width"] <= card["x"] + card["width"]
                        and draw["y"] + draw["height"] <= card["y"] + card["height"]
                        and viewport(draw) != viewport(left) for draw in draws), "Wine Fox thumbnail draw is missing"
            validate_native_gui_preview(source["guiPreview"], preview["nativeContextProof"], archive,
                                        model_id, model["resource_root"], draws)
            assert tuple(preview["nativeContextProof"]["owner"][axis] for axis in ("x", "y", "width", "height")) == viewport(left), \
                "Wine Fox left preview does not use its actual owner context"
            draft = preview.get("draftPreview", {})
            assert preview.get("ownerCapturedAfterActualUse") is True and draft.get("modelId") == model_id \
                and draft.get("hash") == asset_hash and draft.get("profileUnchanged") is True \
                and draft.get("profileBeforeUse") == preview.get("profileBeforeUse") \
                and positive(draft.get("emittedVertices")) and draft["emittedVertices"] > draft["baselineEmittedVertices"], \
                "Wine Fox must first be a non-applied card draft, then an actual used owner model"
            draft_card = draft.get("selectedCard", {})
            assert draft_card.get("modelId") == model_id and all(draft_card.get(flag) is True for flag in ("loaded", "drawn", "selected"))
            if draft["profileBeforeUse"].get("enabled") is True:
                assert draft.get("leftPreviewBeforeUse", {}).get("modelId") == draft["profileBeforeUse"]["modelId"], \
                    "Selecting a draft changed the actual owner's left preview before use"
            draft_draws = draft.get("actualDraws", [])
            assert draft_draws and all(draw.get("key") == left["key"] and draw.get("context") == "CARD"
                and draw.get("nativeCamera") is True and draw.get("cameraSource") == "openysm-dummy-card"
                and draw.get("controllerSlots") == ["player.cap"] and draw.get("layers") == [source["guiPreview"]["animation"]]
                and len(draw.get("layerRequests", [])) == 1
                and draw["layerRequests"][0].get("layer") == "player.cap"
                and draw["layerRequests"][0].get("animation") == source["guiPreview"]["animation"]
                and draw["layerRequests"][0].get("loop") == source["guiPreview"]["loop"]
                and positive(draw.get("vertices")) and positive(draw.get("quads"))
                and draw["vertices"] == draw["quads"] * 4 and draw.get("sampleTick", -1) >= 12
                and .999 <= draw.get("entryProgress", -1) <= 1 and draw.get("startCount") == 1 for draw in draft_draws), \
                "Wine Fox draft did not use actual independently submitted authored cap geometry"
            for draw in draft_draws:
                validate_native_preview_gpu(draw)
                assert all(type(draw.get("modelScale", {}).get(axis)) in (int, float)
                           and math.isfinite(draw["modelScale"][axis]) and abs(draw["modelScale"][axis] - expected) < 1e-5
                           for axis, expected in source_render_scale(authored["properties"]).items())
            assert re.fullmatch(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}", world.get("owner", "")) \
                and world.get("instance", "").startswith("local-self:") and world.get("primarySourceBonesPresent") is True
            assert positive(world.get("vertices")) and world["extractedFrame"] > world["baselineFrame"] \
                and world["drawnBatches"] > world["baselineDrawnBatches"], "No fresh Wine Fox world extraction or GPU draw"
            origin_from_jar(world["modelRendererClassOrigin"], "com.simmc.meplayeractions.client.render.ModelRenderer")
            frames = world.get("frames", [])
            assert len(frames) >= 3 and frames[-1]["extractedFrame"] == world["extractedFrame"]
            previous = world["baselineFrame"]
            for frame in frames:
                assert frame.get("owner") == world["owner"] and frame.get("instance") == world["instance"] \
                    and frame.get("hash") == asset_hash and positive(frame.get("vertices")) \
                    and frame["extractedFrame"] > previous and frame["drawnBatches"] > world["baselineDrawnBatches"]
                body = frame.get("body", {})
                assert body.get("owner") == world["owner"] and body.get("hash") == asset_hash \
                    and body.get("motionSource") == "local-self" and body.get("vertices") == frame["vertices"] \
                    and all(type(body.get(axis)) in (int, float) and math.isfinite(body[axis]) for axis in
                            ("minX", "maxX", "minY", "maxY", "minZ", "maxZ")) \
                    and body["maxY"] > body["minY"], "Wine Fox world proof lacks actual owner geometry"
                assert body.get("instance") == world["instance"] and body.get("nativeYsm") is True \
                    and all(type(body.get("modelScale" + axis.upper())) in (int, float)
                            and math.isfinite(body["modelScale" + axis.upper()])
                            and abs(body["modelScale" + axis.upper()] - expected) < 1e-5
                            for axis, expected in source_render_scale(authored["properties"]).items()) \
                    and type(body.get("initialYOffset")) in (int, float) and abs(body["initialYOffset"] - .01) < 1e-5, \
                    "Wine Fox world body differs from the GUI's authored INITIAL scale/offset"
                previous = frame["extractedFrame"]
            expected_screenshots = [f"screenshots/01-interaction-winefox-{model_id}-{view}.png" for view in ("home", "game")]
            assert row.get("screenshots") == expected_screenshots and set(expected_screenshots) <= set(report["screenshots"])
            for view in ("home", "game"):
                observations = [observation for observation in delivery["observations"]
                                if observation.get("phase") == f"winefox-{model_id}-{view}"]
                assert len(observations) == 1 and observations[0].get("stage") == "interaction-ui" \
                    and observations[0].get("diagnostics", {}).get("modelId") == model_id \
                    and observations[0].get("profile", {}).get("modelId") == model_id \
                    and observations[0].get("profile", {}).get("enabled") is True
            game_observation = next(observation for observation in delivery["observations"] if observation.get("phase") == f"winefox-{model_id}-game")
            home_observation = next(observation for observation in delivery["observations"] if observation.get("phase") == f"winefox-{model_id}-home")
            assert game_observation["stageTick"] < home_observation["stageTick"] \
                and game_observation["requestPacketsSent"] == home_observation["requestPacketsSent"], \
                "Wine Fox GUI owner was not captured after the real world model or sent a server request"
            assert all(type(frame["body"].get("bindingScale")) in (int, float)
                       and math.isfinite(frame["body"]["bindingScale"])
                       and abs(frame["body"]["bindingScale"] - game_observation["profile"]["scale"]) < 1e-5
                       for frame in frames), "Wine Fox INITIAL scale lost the actual user's separate binding scale"


def validate_interaction_baseline(jar: Path, version: str) -> tuple[dict, list[str], dict]:
    """Reuse the anchored full run only after byte-for-byte server and input comparison."""
    assert version == "0.4.3", "The interactions baseline is scoped to the 0.4.3 interaction-only release"
    base = f"MEPlayerActions-{BASELINE_VERSION}"
    validation_path = DIST / f"{base}-validation.json"
    assert digest(validation_path) == BASELINE_VALIDATION_SHA256, "Baseline validation changed"
    validation = read_json(validation_path)
    assert validation["version"] == BASELINE_VERSION and validation["test_totals"] == {
        "tests": 261, "failures": 0, "errors": 0, "skipped": 0}, "Invalid full server baseline"
    assert validation["two_client_in_game_verified"] is True and validation["standalone_client_verified"] is True
    artifacts = {}
    for filename in (f"{base}.jar", f"{base}-source.zip", f"{base}-tests.zip"):
        path = DIST / filename
        expected = validation["artifacts"][filename]
        assert path.is_file() and path.stat().st_size == expected["bytes"] and digest(path) == expected["sha256"], \
            f"Historical baseline artifact changed: {filename}"
        artifacts[filename] = expected
    baseline_jar = DIST / f"{base}.jar"
    assert digest(baseline_jar) == BASELINE_SERVER_SHA256, "Server baseline does not match the authorized release"
    before_version = f"<version>{BASELINE_VERSION}</version>".encode()
    after_version = f"<version>{version}</version>".encode()
    inputs = []
    with zipfile.ZipFile(DIST / f"{base}-source.zip") as source:
        assert source.testzip() is None
        roots = ("src/main/java", "src/test", "src/main/resources", "examples/models", "examples/blueprints", "tools")
        for root_name in roots:
            def included(relative: str) -> bool:
                if root_name == "src/main/java": return relative.endswith(".java")
                if root_name == "tools": return relative != "tools/package_release.py" and not relative.endswith((".pyc", ".log")) and "__pycache__" not in relative.split("/")
                return True
            current = {path.relative_to(PROJECT).as_posix(): path for path in (PROJECT / root_name).rglob("*")
                       if path.is_file() and included(path.relative_to(PROJECT).as_posix())}
            historical = {entry.removeprefix("MEPlayerActions/") for entry in source.namelist()
                          if entry.startswith(f"MEPlayerActions/{root_name}/") and not entry.endswith("/")
                          and included(entry.removeprefix("MEPlayerActions/"))}
            assert set(current) == historical, f"Baseline source file set changed: {root_name}"
            for relative, path in sorted(current.items()):
                assert path.read_bytes() == source.read(f"MEPlayerActions/{relative}"), f"Baseline input changed: {relative}"
                inputs.append({"path": relative, "sha256": digest(path)})
        old_pom = source.read("MEPlayerActions/pom.xml")
        assert old_pom.replace(before_version, after_version, 1) == (PROJECT / "pom.xml").read_bytes(), \
            "Server POM changed beyond its project version"
    metadata_changes = []
    with zipfile.ZipFile(baseline_jar) as before, zipfile.ZipFile(jar) as after:
        assert before.testzip() is None and after.testzip() is None
        assert len(before.namelist()) == len(set(before.namelist())) and len(after.namelist()) == len(set(after.namelist()))
        assert set(before.namelist()) == set(after.namelist()), "Server JAR entry set changed"
        for entry in before.namelist():
            old, new = before.read(entry), after.read(entry)
            if entry == "plugin.yml":
                expected = old.replace(f"version: '{BASELINE_VERSION}'".encode(), f"version: '{version}'".encode(), 1)
            elif entry == "META-INF/maven/com.simmc/MEPlayerActions/pom.xml":
                expected = old.replace(before_version, after_version, 1)
            elif entry == "META-INF/maven/com.simmc/MEPlayerActions/pom.properties":
                expected = old.replace(f"version={BASELINE_VERSION}".encode(), f"version={version}".encode(), 1)
            elif entry == "META-INF/MANIFEST.MF":
                # The authorized rebuild uses JDK 21 instead of the baseline's JDK 26.
                # Class-file version and every compiled byte remain separately checked.
                expected = old.replace(b"Build-Jdk-Spec: 26\r\n", b"Build-Jdk-Spec: 21\r\n", 1)
            else:
                expected = old
            assert new == expected, f"Server JAR behavioral or unrelated metadata change: {entry}"
            if new != old: metadata_changes.append(entry)
    with zipfile.ZipFile(DIST / f"{base}-tests.zip") as archive:
        assert archive.testzip() is None
        matrix = json.loads(archive.read("server/matrix-proof.json"))
        assert matrix["serverJarSha256"].lower() == BASELINE_SERVER_SHA256 and matrix["serverTests"] == 261 and matrix["passed"] is True
    proof = {"mode": "reused-baseline", "testsRerun": False, "baselineVersion": BASELINE_VERSION,
             "baselineValidationSha256": BASELINE_VALIDATION_SHA256, "baselineArtifacts": artifacts,
             "baselineServerSha256": BASELINE_SERVER_SHA256, "currentServerSha256": digest(jar),
             "serverEntriesByteIdenticalExceptAllowedMetadata": True, "allowedMetadataChanges": metadata_changes,
             "manifestBuildJdkSpecChange": "26 to 21; all other manifest bytes identical",
             "serverAndToolInputsByteIdentical": inputs, "serverTests": validation["test_totals"],
             "pythonToolsTests": "Reused prior full packaging evidence; unchanged tool and test sources; not rerun"}
    return validation["test_totals"], validation["test_suites"], proof


def validate_targeted_client_reports(client: Path, inputs: list[Path]) -> tuple[dict, dict]:
    reports = sorted((client / "build/test-results/test").glob("TEST-*.xml"))
    assert reports, "Fresh targeted client reports required"
    required = {f"{suite}.{method}" for suite, methods in TARGETED_CLIENT_METHODS.items() for method in methods}
    sources = [client / "src/test/java" / (suite.replace(".", "/") + ".java") for suite in TARGETED_CLIENT_METHODS]
    freshest = max(path.stat().st_mtime_ns for path in [*inputs, *sources])
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    actual = []
    report_facts = []
    for report in reports:
        assert report.stat().st_mtime_ns >= freshest, f"Stale targeted client report: {report.name}"
        suite = ET.parse(report).getroot()
        for key in totals: totals[key] += int(suite.attrib.get(key, "0"))
        for case in suite.findall("testcase"):
            assert not any(case.find(tag) is not None for tag in ("failure", "error", "skipped")), "Targeted client test did not pass"
            actual.append(case.attrib.get("classname", suite.attrib["name"]) + "." + case.attrib["name"].removesuffix("()"))
        report_facts.append({"path": report.relative_to(PROJECT).as_posix(), "sha256": digest(report)})
    assert totals == {"tests": len(required), "failures": 0, "errors": 0, "skipped": 0}, \
        f"Expected only the {len(required)} targeted methods: {totals}"
    assert len(actual) == len(set(actual)) == len(required) and set(actual) == required, "Targeted client method filters differ"
    return totals, {"mode": "fresh-targeted", "methodFilters": sorted(required), "reports": report_facts, "totals": totals}


def validate_gui_assets(client: Path, archive: zipfile.ZipFile, focused: bool) -> dict | None:
    resources = client / "src/main/resources"
    directory = resources / "assets/meplayeractions/textures/gui"
    if not directory.exists():
        assert not focused, "Reference GUI textures are required for the interactions release"
        return None
    assets = {path.relative_to(resources).as_posix(): path for path in directory.rglob("*") if path.is_file()}
    prefix = "assets/meplayeractions/textures/gui/"
    actual = {entry for entry in archive.namelist() if entry.startswith(prefix) and not entry.endswith("/")}
    assert actual == set(assets), "Client JAR GUI asset set differs from the current source"
    facts = {}
    for entry, path in sorted(assets.items()):
        data = path.read_bytes()
        assert archive.read(entry) == data, f"Missing or stale GUI texture/license resource: {entry}"
        if path.suffix == ".png": assert data.startswith(b"\x89PNG\r\n\x1a\n"), f"Invalid GUI PNG: {entry}"
        facts[entry] = {"sha256": digest(path), "bytes": len(data)}
    roulette = prefix + "roulette.png"
    assert roulette in facts and facts[roulette]["sha256"] == REFERENCE_ROULETTE_SHA256, "Reference checkbox/slider atlas differs"
    license_entry = "assets/meplayeractions/builtin/openysm_default/LICENSE.OpenYSM.txt"
    license_bytes = (resources / license_entry).read_bytes()
    assert archive.read(license_entry) == license_bytes and b"The MIT License (MIT)" in license_bytes \
        and b"Copyright (c) 2026 OpenYSM" in license_bytes, "Original GUI software license is not bundled"
    notices = (PROJECT / "THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8")
    chapters = [chapter for chapter in re.split(r"(?m)^## ", notices) if "roulette.png" in chapter]
    assert len(chapters) == 1 and "MIT" in chapters[0] and REFERENCE_GUI_REVISION in chapters[0] \
        and "https://github.com/IzumiiKonata/OpenYSM-Updated" in chapters[0], "GUI assets need their own MIT source/revision notice"
    assert all(path.name in chapters[0] for path in assets.values() if path.suffix == ".png"), "GUI notice omits a copied texture"
    return {"assets": facts, "sourceRevision": REFERENCE_GUI_REVISION, "license": "MIT",
            "bundledLicensePath": license_entry, "bundledLicenseSha256": hashlib.sha256(license_bytes).hexdigest(),
            "distributionNotice": "THIRD_PARTY_NOTICES.md", "distributionNoticeSha256": digest(PROJECT / "THIRD_PARTY_NOTICES.md"),
            "licenseScope": "Reference GUI assets and ported software; separate from the builtin model's CC0 assets"}


def validate_wine_fox_assets(client: Path, archive: zipfile.ZipFile, focused: bool) -> dict | None:
    manifest_path = client / "builtin-wine-fox-manifest.json"
    if not manifest_path.exists():
        assert not focused, "The three official builtin models need their pinned source manifest"
        return None
    assert digest(manifest_path) == WINE_FOX_MANIFEST_SHA256, "Pinned Wine Fox source manifest changed"
    manifest = read_json(manifest_path)
    upstream = "https://github.com/IzumiiKonata/OpenYSM-Updated"
    assert manifest["schema"] == 1 and manifest["upstream"] == upstream and manifest["revision"] == REFERENCE_GUI_REVISION
    assert manifest["original_file_count"] == 77 and manifest["original_bytes"] == 4_973_579
    expected_models = {"wine_fox_01_taisho_maid": ("01_taisho_maid", 43, 3_123_310),
                       "wine_fox_02_new_year": ("02_new_year", 15, 906_308),
                       "wine_fox_03_astronaut": ("03_astronaut", 19, 943_961)}
    assert len(manifest["models"]) == 3 and {model["id"] for model in manifest["models"]} == set(expected_models)
    resources = client / "src/main/resources"
    entries = set()
    total_bytes = 0
    for model in manifest["models"]:
        folder, count, size = expected_models[model["id"]]
        root = f"assets/meplayeractions/builtin/wine_fox/{folder}/"
        assert model["resource_root"] == root and model["upstream_path"] == \
            f"common/src/main/resources/assets/yes_steve_model/builtin/wine_fox/{folder}"
        assert model["file_count"] == len(model["files"]) == count and model["bytes"] == size
        model_bytes = 0
        for fact in model["files"]:
            relative = fact["path"]
            assert isinstance(relative, str) and relative and not relative.startswith("/") and "\\" not in relative \
                and ":" not in relative and all(part not in {"", ".", ".."} for part in relative.split("/"))
            entry = root + relative
            assert entry not in entries, "Duplicate pinned Wine Fox file"
            source = resources / entry
            assert source.resolve().is_relative_to((resources / root).resolve()) and source.is_file()
            data = source.read_bytes()
            assert len(data) == fact["bytes"] and hashlib.sha256(data).hexdigest() == fact["sha256"], f"Wine Fox source changed: {entry}"
            assert archive.read(entry) == data, f"Wine Fox JAR resource differs: {entry}"
            entries.add(entry); model_bytes += len(data)
        assert model_bytes == size
        raw = read_json(resources / root / "ysm.json")
        assert raw["metadata"]["license"]["type"] == "CC BY-NC-SA 4.0", "Original Wine Fox license metadata changed"
        total_bytes += model_bytes
    assert len(entries) == 77 and total_bytes == 4_973_579
    shared = manifest["shared_license"]
    license_entry = "assets/meplayeractions/builtin/wine_fox/LICENSE.CC-BY-NC-SA-4.0.txt"
    assert shared == {"path": license_entry, "sha256": WINE_FOX_LICENSE_SHA256, "bytes": 20_852}
    license_path = PROJECT / "docs/licenses/CC-BY-NC-SA-4.0.txt"
    license_bytes = license_path.read_bytes()
    assert len(license_bytes) == 20_852 and digest(license_path) == WINE_FOX_LICENSE_SHA256
    assert (resources / license_entry).read_bytes() == archive.read(license_entry) == license_bytes, "Complete Wine Fox license differs or is absent"
    expected = entries | {license_entry}
    prefix = "assets/meplayeractions/builtin/wine_fox/"
    assert {entry for entry in archive.namelist() if entry.startswith(prefix) and not entry.endswith("/")} == expected
    assert {path.relative_to(resources).as_posix() for path in (resources / prefix).rglob("*") if path.is_file()} == expected
    notices = (PROJECT / "THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8")
    chapter = next((text for text in re.split(r"(?m)^## ", notices)
                    if text.startswith("OpenYSM Wine Fox model assets (CC BY-NC-SA 4.0)")), "")
    assert upstream in chapter and REFERENCE_GUI_REVISION in chapter and "CC BY-NC-SA 4.0" in chapter \
        and "docs/licenses/CC-BY-NC-SA-4.0.txt" in chapter and all(model_id in chapter for model_id in expected_models), \
        "Wine Fox assets need their separate author/source/complete-license notice"
    return {"upstream": upstream, "revision": REFERENCE_GUI_REVISION, "sourceManifest": "client/builtin-wine-fox-manifest.json",
            "sourceManifestSha256": WINE_FOX_MANIFEST_SHA256, "originalFileCount": 77, "originalBytes": total_bytes,
            "modelIds": list(expected_models), "originalSourceAndJarBytesMatch": True,
            "license": "CC BY-NC-SA 4.0", "bundledLicensePath": license_entry, "licenseSha256": WINE_FOX_LICENSE_SHA256,
            "distributionLicensePath": "docs/licenses/CC-BY-NC-SA-4.0.txt", "sourceManifestData": manifest}


def validate_focused_server(jar: Path, client_jar: Path, version: str, tested_at: int,
                            game: dict, observer: dict) -> tuple[dict, dict[str, bytes]]:
    server_root = PROJECT / "build/e2e-server"
    path = server_root / "focused-server-proof.json"
    assert path.is_file() and path.stat().st_mtime_ns >= tested_at, "Fresh focused runtime server proof required"
    proof = read_json(path)
    assert proof.get("scope") == proof.get("validationProfile") == "interactions" and proof.get("passed") is True
    assert proof.get("serverVersion") == version and proof.get("serverJarSha256") == digest(jar) \
        and proof.get("clientJarSha256") == digest(client_jar), "Focused server proof used other artifacts"
    required = {"runtimeLoadedPluginVersion", "installedServerJarMatches", "loopbackListener25591",
                "helperAuthorityCurrentBoot", "clientsAfterCurrentBoot"}
    assert required <= set(proof.get("requiredChecks", [])) and proof.get("checks")
    assert all(row.get("passed") is True for row in proof["checks"])
    assert set(proof["requiredChecks"]) <= {row["name"] for row in proof["checks"]}, "Missing focused server checks"
    installed = proof.get("installedJar", {})
    installed_path = Path(installed["path"]).resolve()
    assert installed_path.is_relative_to((server_root / "plugins").resolve()) and installed_path.suffix == ".jar"
    assert installed.get("sha256") == digest(installed_path) == digest(jar), "Actually installed server JAR differs"
    startup = proof.get("startupLog", {})
    startup_path = Path(startup["path"]).resolve()
    assert startup_path.is_relative_to((server_root / "logs").resolve()) and startup_path.suffix in {".log", ".txt"}
    assert startup_path.stat().st_size <= 10 * 1024 * 1024 and startup.get("sha256") == digest(startup_path), "Startup log evidence changed"
    log = startup_path.read_text(encoding="utf-8-sig")
    assert f"[MEPlayerActions] Enabling MEPlayerActions v{version}" in log and "Starting Minecraft server on 127.0.0.1:25591" in log \
        and "Done (" in log, "Server startup did not confirm the actual plugin and listener"
    runtime = proof.get("runtime", {})
    started = runtime.get("serverStartedAtMillis")
    assert type(started) is int and started > 0 and started == runtime.get("helperAuthorityServerStartedAtMillis")
    process_started = runtime.get("processStartedAtMillis")
    assert type(process_started) is int and 0 < process_started <= started and started - process_started < 60_000, \
        "Helper authority is not associated with this server process startup"
    assert runtime.get("pid", 0) > 0 and runtime.get("listenerPort") == 25591 \
        and runtime.get("listenersLoopbackOnly") is True and runtime.get("pluginVersionFromRuntime") == version
    for label, report in (("A", game), ("B", observer)):
        assert started <= report["startedAtMillis"] <= report["completedAtMillis"], f"{label} report belongs to another server boot"
    return proof, {"server/focused-server-proof.json": path.read_bytes(), "server/runtime-startup.log": startup_path.read_bytes()}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path: Path) -> dict:
    # Acceptance helpers may run in either Windows PowerShell or PowerShell 7.
    return json.loads(path.read_text(encoding="utf-8-sig"))


def documentation_entries() -> dict[str, bytes]:
    """Install the curated Markdown navigation and complete local text licenses."""
    return {path.relative_to(PROJECT).as_posix(): path.read_bytes()
            for path in sorted((PROJECT / "docs").rglob("*")) if path.is_file() and path.suffix in {".md", ".txt"}}


def numeric_axis(value: object) -> bool:
    # Blockbench stores channel values as numeric strings; expressions must be absent in ME assets.
    if type(value) not in (int, float, str):
        return False
    if isinstance(value, str) and not re.fullmatch(r"[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?", value.strip()):
        return False
    try:
        return math.isfinite(float(value))
    except (ValueError, OverflowError):
        return False


def write_zip(path: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, data)
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None, f"Corrupt ZIP: {path}"
        assert set(archive.namelist()) == set(entries)
        for name, expected in entries.items():
            assert archive.read(name) == expected, f"Stale entry: {name}"


def rebase_documentation(entries: dict[str, bytes], version: str, prefix: str = "") -> None:
    """Keep navigation local; source links omitted from an installer point to its release tag."""
    origins = {}
    protocol = PROJECT / "docs/CLIENT_PROTOCOL.md"
    for entry in entries:
        relative = entry.removeprefix(prefix)
        origin = protocol if relative == "docs/CLIENT_PROTOCOL.md" else PROJECT / relative
        if origin.is_file():
            origins[entry] = origin.resolve()
    destinations = {}
    for entry, origin in sorted(origins.items(), key=lambda row: len(row[0]), reverse=True):
        destinations[origin] = entry

    def present(target: str) -> bool:
        return target in entries or any(entry.startswith(target.rstrip("/") + "/") for entry in entries)

    link = re.compile(r"(\[[^\]\n]*\]\()([^\s)]+)(\))")
    for entry, origin in origins.items():
        if not entry.endswith(".md"):
            continue

        def replace(match: re.Match) -> str:
            href = match.group(2)
            if href.startswith("#") or ":" in href:
                return match.group(0)
            path, separator, fragment = href.partition("#")
            archive_target = posixpath.normpath(posixpath.join(posixpath.dirname(entry), path))
            if present(archive_target):
                return match.group(0)
            target = (origin.parent / path).resolve()
            if target == (PROJECT / "src/main/java/com/simmc/meplayeractions/client/CLIENT_PROTOCOL.md").resolve():
                target = protocol.resolve()
            assert target.exists() and target.is_relative_to(PROJECT), f"Missing documentation source: {entry}: {href}"
            mapped = destinations.get(target)
            if mapped:
                corrected = posixpath.relpath(mapped, posixpath.dirname(entry))
            else:
                kind = "tree" if target.is_dir() else "blob"
                corrected = f"https://github.com/uwuhhhj/MEPlayerActions/{kind}/v{version}/" \
                    + quote(target.relative_to(PROJECT).as_posix(), safe="/")
            return match.group(1) + corrected + (separator + fragment if separator else "") + match.group(3)

        entries[entry] = link.sub(replace, entries[entry].decode("utf-8")).encode("utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=("full", "interactions"), default="full",
                        help="Default full gates; interactions reuses the verified 0.4.2 server baseline and requires fresh 0.4.3 interaction proofs")
    profile = parser.parse_args().profile
    focused = profile == "interactions"
    baseline_proof = None
    targeted_client_proof = None
    version = ET.parse(PROJECT / "pom.xml").findtext("m:version", namespaces=NAMESPACE)
    assert version and re.fullmatch(r"\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?", version)
    name = f"MEPlayerActions-{version}"
    jar = PROJECT / "target" / f"{name}.jar"
    assert jar.is_file(), "Run mvn package first"

    compiler_inputs = [PROJECT / "pom.xml"]
    compiler_inputs += sorted((PROJECT / "src/main/java").rglob("*.java"))
    compiler_inputs += sorted((PROJECT / "src/main/resources").rglob("*"))
    compiler_inputs += sorted((PROJECT / "examples/models").glob("*.bbmodel"))
    compiler_inputs = [p for p in compiler_inputs if p.is_file()]
    assert all(p.stat().st_mtime_ns <= jar.stat().st_mtime_ns for p in compiler_inputs), \
        "Source changed after the JAR; rerun mvn package"

    if focused:
        totals, suites, baseline_proof = validate_interaction_baseline(jar, version)
    else:
        reports = sorted((PROJECT / "target/surefire-reports").glob("TEST-*.xml"))
        assert reports, "Test reports are required"
        assert all(report.stat().st_mtime_ns >= max(p.stat().st_mtime_ns for p in compiler_inputs)
                   for report in reports), "Server tests predate the current build inputs"
        totals = dict(tests=0, failures=0, errors=0, skipped=0)
        suites = []
        for report in reports:
            suite = ET.parse(report).getroot()
            suites.append(suite.attrib["name"])
            for key in totals:
                totals[key] += int(suite.attrib.get(key, "0"))
        assert totals["tests"] > 0 and not any(totals[k] for k in ("failures", "errors", "skipped")), totals
        for source in (PROJECT / "src/test/java").rglob("*Test.java"):
            relative = source.relative_to(PROJECT / "src/test/java").with_suffix("")
            suite_name = ".".join(relative.parts)
            report = PROJECT / "target/surefire-reports" / f"TEST-{suite_name}.xml"
            assert report.is_file() and report.stat().st_mtime_ns >= source.stat().st_mtime_ns, \
                f"Missing or outdated test report: {source.name}"

    with zipfile.ZipFile(jar) as archive:
        assert archive.testzip() is None
        descriptor = archive.read("plugin.yml").decode("utf-8")
        assert f"version: '{version}'" in descriptor
        assert "main: com.simmc.meplayeractions.MEPlayerActionsPlugin" in descriptor
        assert "api-version: '1.21.11'" in descriptor
        assert "depend: [ModelEngine]" in descriptor
        assert "authors: [SIMMC, Loliiiico]" in descriptor, "Missing updated author credit"
        assert "mact.disguise.effects:" in descriptor, "Missing disguise effect permission"
        assert "  meplayeractions:" in descriptor and "usage: /meplayeractions help" in descriptor
        assert "  mact:" not in descriptor and "aliases:" not in descriptor, "Legacy command aliases were retained"
        assert "${" not in descriptor, "Unfiltered plugin descriptor"
        expected_descriptor = (PROJECT / "src/main/resources/plugin.yml").read_bytes().replace(
            b"${version}", version.encode("utf-8"))
        assert archive.read("plugin.yml") == expected_descriptor, "Stale plugin metadata"
        assert archive.read("config.yml") == (PROJECT / "src/main/resources/config.yml").read_bytes()
        for model_file in (PROJECT / "examples/models").glob("*.bbmodel"):
            assert archive.read(f"models/{model_file.name}") == model_file.read_bytes(), "Stale server client asset"
        classes = [n for n in archive.namelist() if n.endswith(".class")]
        assert "com/simmc/meplayeractions/MEPlayerActionsPlugin.class" in classes
        assert "com/simmc/meplayeractions/action/DisguiseOptions.class" in classes
        assert "com/simmc/meplayeractions/gameplay/DisguiseEffects.class" in classes
        assert "com/simmc/meplayeractions/me/YsmAnimations.class" in classes
        assert all(n.startswith("com/simmc/meplayeractions/") for n in classes), "Dependency classes were bundled"
        assert not any(n.endswith(".jar") for n in archive.namelist()), "Dependency JAR was bundled"
        for class_name in classes:
            data = archive.read(class_name)
            magic, minor, major = struct.unpack(">IHH", data[:8])
            assert magic == 0xCAFEBABE and major == 65 and minor == 0, f"Not Java 21: {class_name}"
            compiled = PROJECT / "target/classes" / class_name
            assert compiled.read_bytes() == data, f"Stale compiled class: {class_name}"

    model_facts = {}
    model_ids = MODEL_IDS
    for directory in ("models", "blueprints"):
        assert {p.stem for p in (PROJECT / "examples" / directory).glob("*.bbmodel")} == set(model_ids), \
            f"Unexpected example models in {directory}"
    for model_id in model_ids:
        raw_path = PROJECT / f"examples/models/{model_id}.bbmodel"
        fact = read_json(raw_path.with_suffix(".manifest.json"))
        raw_model = read_json(raw_path)
        baked_path = PROJECT / f"examples/blueprints/{model_id}.bbmodel"
        baked = read_json(baked_path)
        assert fact["model_id"] == model_id and raw_model["model_identifier"] == model_id
        assert digest(raw_path) == fact["raw_sha256"], f"Stale manifest: {model_id}"
        assert fact["geometry_and_original_size_preserved"] is True
        assert raw_model["mpa_runtime"]["original_size"] is True
        assert raw_model["mpa_runtime"]["physics_step_seconds"] == .01
        assert len(raw_model["animations"]) == 60 and len(baked["animations"]) == 57
        raw_names = [action["name"] for action in raw_model["animations"]]
        baked_names = [action["name"] for action in baked["animations"]]
        assert raw_names == fact["animations"] and len(set(raw_names)) == len(raw_names)
        assert len(set(baked_names)) == len(baked_names)
        assert set(baked_names) == set(raw_names) - {"pre_parallel0", "parallel1", "parallel2"}
        assert all(raw_model[key] == baked[key] for key in ("elements", "outliner", "textures", "resolution"))
        for action in baked["animations"]:
            for animator in action["animators"].values():
                for frame in animator["keyframes"]:
                    for point in frame["data_points"]:
                        for axis in ("x", "y", "z"):
                            assert numeric_axis(point[axis]), \
                                f"Non-numeric ME keyframe: {model_id}/{action['name']}/{axis}"
        appearance = PROJECT.parent / fact["appearance_source"]
        if appearance.exists():
            assert digest(appearance) == fact["appearance_sha256"], f"Appearance source changed: {model_id}"
            source_appearance = read_json(appearance)
            assert all(raw_model[key] == source_appearance[key] for key in ("elements", "outliner", "resolution")), \
                f"Original model geometry was changed: {model_id}"
        animation_source = WORKSPACE / "dist/ysm_07_jk.bbmodel"
        if animation_source.exists():
            assert digest(animation_source) == fact["animation_source_sha256"], "Original animation source changed"
        fact["baked_sha256"] = digest(baked_path)
        model_facts[model_id] = fact
    animation_names = model_facts["ysm_01_jk"]["animations"]
    assert len(set(animation_names)) == 60
    protocol = PROJECT / "docs/CLIENT_PROTOCOL.md"
    install = {
        f"plugins/{name}.jar": jar.read_bytes(),
        "plugins/MEPlayerActions/config.yml": (PROJECT / "src/main/resources/config.yml").read_bytes(),
        "README.md": (PROJECT / "README.md").read_bytes(),
        "docs/CLIENT_PROTOCOL.md": protocol.read_bytes(),
        "docs/CLIENT_RESOURCE_PACK.md": (PROJECT / "docs/CLIENT_RESOURCE_PACK.md").read_bytes(),
        "THIRD_PARTY_NOTICES.md": (PROJECT / "THIRD_PARTY_NOTICES.md").read_bytes(),
    }
    install.update(documentation_entries())
    for model_id in model_ids:
        # Shipped raw models already live inside the server JAR. An installation must not
        # create persistent overrides which shadow a later JAR upgrade.
        install[f"examples/models/{model_id}.bbmodel"] = (PROJECT / f"examples/models/{model_id}.bbmodel").read_bytes()
        install[f"plugins/ModelEngine/blueprints/meplayeractions/{model_id}.bbmodel"] = (PROJECT / f"examples/blueprints/{model_id}.bbmodel").read_bytes()
        install[f"docs/{model_id}.manifest.json"] = (PROJECT / f"examples/models/{model_id}.manifest.json").read_bytes()
    # The tutorial link remains usable after unpacking the installation ZIP.
    install["README.md"] = install["README.md"].replace(
        b"src/main/java/com/simmc/meplayeractions/client/CLIENT_PROTOCOL.md", b"docs/CLIENT_PROTOCOL.md")
    architecture = PROJECT / "ARCHITECTURE.md"
    if architecture.is_file():
        install["ARCHITECTURE.md"] = architecture.read_bytes()
    source_entries = {}
    for root_name in ("src", "examples", "tools", "docs"):
        for path in sorted((PROJECT / root_name).rglob("*")):
            if not path.is_file() or "__pycache__" in path.parts or path.suffix in (".pyc", ".log"):
                continue
            source_entries[f"MEPlayerActions/{path.relative_to(PROJECT).as_posix()}"] = path.read_bytes()
    for filename in ("pom.xml", "README.md", ".gitignore", ".gitattributes", "THIRD_PARTY_NOTICES.md"):
        source_entries[f"MEPlayerActions/{filename}"] = (PROJECT / filename).read_bytes()
    if architecture.is_file():
        source_entries["MEPlayerActions/ARCHITECTURE.md"] = architecture.read_bytes()
    source_entries["MEPlayerActions/docs/CLIENT_PROTOCOL.md"] = protocol.read_bytes()

    client = PROJECT / "client"
    client_jar = client / "build/libs" / f"MEPlayerActions-Client-{version}.jar"
    assert client_jar.is_file(), "Run client gradlew build first"
    client_inputs = [client / p for p in ("build.gradle", "settings.gradle", "gradle.properties")]
    client_inputs += [p for p in (client / "src/main").rglob("*") if p.is_file()]
    client_inputs += list((PROJECT / "examples/models").glob("*.bbmodel"))
    client_inputs += list((PROJECT / "src/main/java/com/simmc/meplayeractions/expression").glob("*.java"))
    client_inputs += [PROJECT / "src/main/java/com/simmc/meplayeractions/config/AnimationLabels.java"]
    assert all(p.stat().st_mtime_ns <= client_jar.stat().st_mtime_ns for p in client_inputs), "Client source changed after JAR"
    if focused:
        client_totals, targeted_client_proof = validate_targeted_client_reports(client, client_inputs)
    else:
        client_totals = dict(tests=0, failures=0, errors=0, skipped=0)
        client_reports = list((client / "build/test-results/test").glob("TEST-*.xml"))
        assert client_reports, "Client test reports required"
        assert all(report.stat().st_mtime_ns >= max(p.stat().st_mtime_ns for p in client_inputs)
                   for report in client_reports), "Client tests predate the current build inputs"
        client_report_names = set()
        for report in client_reports:
            suite = ET.parse(report).getroot()
            client_report_names.add(suite.attrib["name"])
            for key in client_totals:
                client_totals[key] += int(suite.attrib.get(key, "0"))
        assert client_totals["tests"] > 0 and not any(client_totals[k] for k in ("failures", "errors", "skipped")), client_totals
        for test_source in (client / "src/test/java").rglob("*Test.java"):
            suite_name = ".".join(test_source.relative_to(client / "src/test/java").with_suffix("").parts)
            assert suite_name in client_report_names, f"Missing client test suite: {suite_name}"
            assert (client / "build/test-results/test" / f"TEST-{suite_name}.xml").stat().st_mtime_ns >= test_source.stat().st_mtime_ns
    client_gui_proof = None
    client_wine_fox_proof = None
    with zipfile.ZipFile(client_jar) as archive:
        assert archive.testzip() is None
        metadata = json.loads(archive.read("fabric.mod.json"))
        assert metadata["version"] == version and metadata["id"] == "meplayeractions"
        assert metadata["environment"] == "client" and metadata["depends"]["minecraft"] == "~1.21.11"
        assert metadata["depends"]["java"] == ">=21"
        assert set(metadata["depends"]) == {"fabricloader", "minecraft", "java", "fabric-api"}, \
            "Independent client acquired a server engine dependency"
        assert not any(n.endswith(".jar") for n in archive.namelist()), "Unexpected bundled client dependency"
        for model_file in (PROJECT / "examples/models").glob("*.bbmodel"):
            assert f"assets/meplayeractions/models/{model_file.name}" not in archive.namelist(), "Server model leaked into the independent client"
        for asset in (client / "src/main/resources/assets/meplayeractions/builtin").rglob("*"):
            if asset.is_file():
                assert archive.read(asset.relative_to(client / "src/main/resources").as_posix()) == asset.read_bytes()
        client_gui_proof = validate_gui_assets(client, archive, focused)
        client_wine_fox_proof = validate_wine_fox_assets(client, archive, focused)
        if focused:
            assert "com/simmc/meplayeractions/client/ui/ActionsScreen.class" not in archive.namelist(), "Removed actions grid was retained"
            for class_name in ("PlayerModelScreen", "RadialSliceRenderState", "NativeGuiPreviewCamera", "NativeGuiRenderBackend", "NativeGuiRenderBackend$State"):
                assert f"com/simmc/meplayeractions/client/ui/{class_name}.class" in archive.namelist(), f"Missing reference UI class: {class_name}"
            assert "com/simmc/meplayeractions/client/AnimationFormatValidator.class" in archive.namelist(), "Missing authored loop validator"
            assert "com/simmc/meplayeractions/client/model/YsmRenderScale.class" in archive.namelist(), "Missing shared native author-scale implementation"
        assert "com/simmc/meplayeractions/client/ui/AnimationWheelScreen.class" in archive.namelist()
        assert "com/simmc/meplayeractions/client/ui/ModelSettingsScreen.class" in archive.namelist()
        client_classes = [n for n in archive.namelist() if n.endswith(".class")]
        assert "com/simmc/meplayeractions/client/MEPlayerActionsClient.class" in client_classes
        for name_in_jar in client_classes:
            magic, minor, major = struct.unpack(">IHH", archive.read(name_in_jar)[:8])
            assert magic == 0xCAFEBABE and minor == 0 and major == 65, f"Not Java 21: {name_in_jar}"
    for path in client.rglob("*"):
        relative = path.relative_to(client)
        if not path.is_file() or any(part in {"build", ".gradle", "run", "logs", "__pycache__"} for part in relative.parts):
            continue
        if path.suffix in {".log", ".pyc"}:
            continue
        source_entries[f"MEPlayerActions/client/{relative.as_posix()}"] = path.read_bytes()
    rebase_documentation(install, version)
    rebase_documentation(source_entries, version, "MEPlayerActions/")

    delivered_jar = DIST / jar.name
    install_zip = DIST / f"{name}-install.zip"
    source_zip = DIST / f"{name}-source.zip"
    delivered_client = DIST / client_jar.name
    client_install_zip = DIST / f"MEPlayerActions-Client-{version}-install.zip"
    game_report_path = client / "build/e2e/results.json"
    game_report = read_json(game_report_path) if game_report_path.is_file() else None
    observer_report_path = client / "build/observer-test/results/observer-results.json"
    observer_report = read_json(observer_report_path) if observer_report_path.is_file() else None
    tested_at = max(jar.stat().st_mtime_ns, client_jar.stat().st_mtime_ns)
    game_current = bool(game_report and game_report_path.stat().st_mtime_ns >= tested_at)
    observer_current = bool(observer_report and observer_report_path.stat().st_mtime_ns >= tested_at)
    game_passed = bool(game_current and game_report.get("passed") is True)
    observer_passed = bool(observer_current and observer_report.get("passed") is True
                           and observer_report.get("installedMpa") is False)
    # A release must carry proof from these exact JARs, not an earlier successful run.
    game_launch_path = game_report_path.parent / "launch.json"
    observer_launch_path = observer_report_path.parent / "launch.json"
    assert game_passed and observer_passed, "Fresh, passing two-client game reports are required"
    assert game_report["clientVersion"] == version and game_report["minecraft"] == "1.21.11"
    assert observer_report.get("complete") is True and observer_report["minecraft"] == "1.21.11"
    assert game_report["artifactProof"]["clientFromJar"] is True, "Client acceptance ran from development classes"
    game_checks = {check["name"]: check["passed"] for check in game_report["checks"]}
    observer_checks = {check["name"]: check["passed"] for check in observer_report["checks"]}
    assert game_checks and all(check["passed"] is True for check in game_report["checks"]), "Client report contains a failed check"
    assert observer_checks and all(check["passed"] is True for check in observer_report["checks"]), "Observer report contains a failed check"
    if focused:
        interaction_delivery = checked_delivery(game_report, "interactionDelivery", interaction_required_checks())
        validate_held_item_delivery(interaction_delivery, client_jar)
        assert observer_report.get("validationProfile") == "interactions" and observer_report.get("scope") == "interactions"
        validate_interaction_frames(game_report, observer_report)
        gallery_home = next(row for row in interaction_delivery["observations"] if row.get("stage") == "local-appearance-own")
        gallery_evidence = gallery_home["evidence"]
        gallery_contexts = gallery_evidence["nativeContextProof"]
        gallery_key = gallery_evidence["gallery"]["leftPreview"]["key"]
        assert gallery_contexts["owner"]["key"] == gallery_key \
            and any(binding["motionSource"] == "local-self" and gallery_key == "openysm_default:" + binding["hash"]
                    for binding in gallery_home["bindings"]), "A GUI owner preview does not use its actual current private binding"
        with zipfile.ZipFile(client_jar) as archive:
            validate_native_gui_preview(gallery_evidence["nativePreviewSource"], gallery_contexts, archive, "openysm_default",
                                        "assets/meplayeractions/builtin/openysm_default/",
                                        [gallery_contexts["owner"], gallery_contexts["card"]])
        validate_default_png_visibility(gallery_evidence["defaultVisibilityPixels"], gallery_contexts, game_report_path, game_report)
    else:
        for check_name in (
                "other-undisguiseRestoresNativePlayer", "undisguisedRemoteKeepsMoving",
                "other-undisguise-againRestoresNativePlayer", "other-second-modelReferenceModelLocalWithExpressions",
                "other-second-undisguiseRestoresNativePlayer", "other-first-modelLocalModelReturns",
                "own-second-modelReferenceModelLocalWithExpressions", "ownSecondUndisguiseRestoresPlayer",
                "ownFirstModelReturnsAfterReference", "boatDedicatedLocalClip", "extraDedicatedLocalClip", "hungerExpressionRuntime",
                "firstPersonAccessoryTimelineRuns", "thirdPersonAccessoryStatePersists",
                "hiddenSelfAccessoryTimelineRuns", "hiddenSelfAccessoryStatePersists",
                "firstPersonAccessoryStartsClear", "firstPersonAccessoryNotPremature",
                "firstPersonAccessoryEventDeadline", "firstPersonAccessoryActionCompleted",
                "hiddenSelfAccessoryStartsClear", "hiddenSelfAccessoryNotPremature",
                "hiddenSelfAccessoryEventDeadline", "hiddenSelfAccessoryActionCompleted",
                "remoteAccessoryStateBeforeRange", "remoteAccessoryStateAfterRange",
                "localAppearanceServerBaseline", "local-appearance-ownPrivateModelAndTransform",
                "local-appearance-ownRemoteBindingUnchanged", "local-appearance-ownNoServerRequest",
                "local-appearance-actionPrivateModelAndTransform", "local-appearance-actionRemoteBindingUnchanged",
                "local-appearance-actionNoServerRequest", "localAppearanceLocalActionLayer",
                "localAppearanceRestoresServerBinding", "localAppearanceDisableNoServerRequest"):
            assert game_checks.get(check_name) is True, f"Missing lifecycle regression proof: {check_name}"
        # Push delivery is verified by the runtime harness against an ordinary ME pack.
        assert game_report.get("serverPushDelivery", {}).get("passed") is True, \
            "Missing server-push first-download, reconnect/cache and authorization proof"
        for check_name in (
                "serverPushNegotiated", "serverPushPlainMePack", "serverPushColdCacheDownloaded",
                "serverPushDefaultJarAssetSource", "serverPushActualReconnect",
                "serverPushReconnectCacheHitNoDownload", "serverPushInvalidOwnAssetAuthoritative",
                "serverPushInvalidAssetFallback", "serverPushInvalidAssetNoStaleTransfer",
                "serverPushFaultFixtureRestored", "serverPushRepairRecovered",
                "serverPushRepairCacheNoDownload", "serverPushPackDisabledNoIndex",
                "serverPushPackDisabledModelRetained", "serverPushPackDisabledFreshFrames",
                "serverPushPackDisabledOwnerLeaseSameInstance", "serverPushPackDisabledNoDownloadFreshAck",
                "packRestoredHashRevalidated", "packRestoredOwnerLeaseSameInstance",
                "packRestoredFreshFrames", "serverPushPackRestoredNoDownloadFreshAck",
                "serverPushNeverAssetRequested", "other-range-outPushOwnerReleased",
                "other-undisguisePushOwnerReleased", "own-second-modelPushNewInstance"):
            assert game_checks.get(check_name) is True, f"Missing server-push acceptance proof: {check_name}"
        for item_stage in ("other-item-01-hold", "other-item-01-swing", "other-item-01-use",
                           "other-item-02-hold", "other-item-02-swing", "other-item-02-use"):
            for suffix in ("UnmoddedOwnerEquipment", "CurrentBindingPostsubmitBothHands", "NativeActionMovesItemLocator"):
                assert game_checks.get(item_stage + suffix) is True, f"Missing native remote-owner held-item proof: {item_stage + suffix}"
            assert observer_checks.get(item_stage + "UnmoddedNativeEquipmentAction") is True, \
                f"Missing independent unmodded-owner action proof: {item_stage}"
        assert observer_checks.get("unmoddedNativeItemStagesAllObserved") is True, "Incomplete unmodded-owner item stages"
        assert observer_checks.get("ownSecondModelCurrentAuthorityStableGeometry") is True, "Missing actual second-model readiness proof from the unmodded viewer"
        for item_model in ("01", "02"):
            for stage, suffix in (("hold", "NativeHeldShieldModelResolved"), ("use", "NativeBlockingShieldModelResolved")):
                check_name = f"other-item-{item_model}-{stage}{suffix}"
                assert game_checks.get(check_name) is True, f"Missing actual native shield model branch proof: {check_name}"
        assert observer_checks.get("AUndisguiseRestoresNativePlayerForUnmoddedB") is True
        for check_name in ("privateAppearanceBServerModelBaseline",
                           "local-appearance-ownBServerModelAndTransformUnchanged",
                           "local-appearance-actionBServerModelAndTransformUnchanged",
                           "local-appearance-offBServerModelAndTransformUnchanged"):
            assert observer_checks.get(check_name) is True, f"Missing private appearance observer proof: {check_name}"
    game_launch = read_json(game_launch_path)
    observer_launch = read_json(observer_launch_path)
    layout_path = game_report_path.parent / "window-layout.json"
    layout = read_json(layout_path)
    windows = {row["client"]: row for row in layout}
    assert set(windows) == {"A", "B"}, "Missing two-client window arrangement"
    for label, report_path in (("A", game_report_path), ("B", observer_report_path)):
        assert windows[label]["pid"] == read_json(report_path.parent / "process.json")["pid"], "Window proof used another client"
    sizes = [(windows[label]["right"] - windows[label]["left"], windows[label]["bottom"] - windows[label]["top"])
             for label in ("A", "B")]
    assert sizes[0] == sizes[1] and min(sizes[0]) > 0 \
        and windows["A"]["right"] <= windows["B"]["left"], "Client windows differ in size or overlap"
    assert layout_path.stat().st_mtime_ns // 1_000_000 >= max(game_launch["startedAtMillis"], observer_launch["startedAtMillis"]), \
        "Window arrangement proof predates this run"
    assert game_launch["clientJarSha256"].lower() == digest(client_jar), "Game run used another client JAR"
    assert game_launch["serverJarSha256"].lower() == digest(jar), "Game run used another server JAR"
    assert game_report["testedClientSha256"].lower() == digest(client_jar), "Loaded client differs from launch proof"
    assert game_report["testedServerSha256"].lower() == digest(jar), "Server report differs from launch proof"
    assert game_report["artifactProof"]["clientArtifactSha256"].lower() == digest(client_jar)
    assert game_report["artifactProof"]["serverArtifactSha256"].lower() == digest(jar)
    assert observer_launch["serverJarSha256"].lower() == digest(jar), "Observer run used another server JAR"
    assert observer_launch.get("mpaClientInstalled") is False
    assert observer_report["observerHelperSha256"].lower() == observer_launch["helperJarSha256"].lower(), \
        "Observer loaded another acceptance helper"
    observed_hashes = observer_report["observedArtifactHashes"]
    assert observed_hashes["clientArtifactSha256"].lower() == digest(client_jar)
    assert observed_hashes["serverArtifactSha256"].lower() == digest(jar)
    assert not any("MEPlayerActions-Client" in mod for mod in observer_launch["mods"]), \
        "Observer run accidentally installed the client mod"
    standalone_report_path = client / "build/standalone-e2e/results.json"
    assert standalone_report_path.is_file(), "Independent client acceptance report required"
    standalone_report = read_json(standalone_report_path)
    standalone_tested_at = client_jar.stat().st_mtime_ns
    assert standalone_report_path.stat().st_mtime_ns >= standalone_tested_at, "Stale independent client report"
    assert standalone_report.get("passed") is True and standalone_report.get("checks") \
        and all(check.get("passed") is True for check in standalone_report["checks"]), "Independent client acceptance failed"
    assert standalone_report.get("testedClientSha256", "").lower() == digest(client_jar), "Independent client used another JAR"
    assert standalone_report.get("scope") == "standalone-no-server-plugins" \
        and standalone_report.get("runtimeServerConnected") is False \
        and standalone_report.get("serverChannelAvailable") is False \
        and standalone_report.get("artifactProof", {}).get("clientFromJar") is True, "Independent run used a server bridge"
    standalone_checks = {check["name"]: check["passed"] for check in standalone_report["checks"]}
    if focused:
        checked_delivery(standalone_report, "interactionDelivery", standalone_interaction_required_checks())
        validate_wine_fox_delivery(standalone_report, client_jar)
        model_home = [row for row in standalone_report["interactionDelivery"]["observations"] if row.get("phase") == "model-home"]
        assert len(model_home) == 1, "Missing actual default-model GUI home observation"
        home = model_home[0]["diagnostics"]
        home_source, home_contexts = home["nativePreviewSource"], home["nativeContextProof"]
        assert home_contexts["owner"]["key"] == home["leftPreview"]["key"]
        with zipfile.ZipFile(client_jar) as archive:
            validate_native_gui_preview(home_source, home_contexts, archive, "openysm_default",
                                        "assets/meplayeractions/builtin/openysm_default/",
                                        [home_contexts["owner"], home_contexts["card"]])
        validate_default_png_visibility(home["defaultVisibilityPixels"], home_contexts, standalone_report_path, standalone_report)
    else:
        for check_name in ("standaloneNoServerChannel", "standaloneNoServerBridge", "standaloneLocalModelVisible",
                           "transformStandaloneTransform", "standaloneLocalActionLayer", "standaloneProfileSaved",
                           "standaloneSavedProfileReloaded", "standaloneResourceReload", "standaloneDisableRestoresNative",
                           "standaloneAllStagesCaptured", "standaloneBridgeStayedDisconnected",
                           "standaloneSettingsUiScreen", "standaloneSettingsUiProfileAndModel",
                           "settings-uiStandaloneControlsInBounds", "standaloneActionsUiSeparateModes",
                           "local-actions-uiStandaloneControlsInBounds", "standaloneActionsUiUsesLocalApi",
                           "standaloneFirstPersonNativeArm", "standaloneGalleryFixturesAvailable",
                           "standaloneGallerySearchField", "settings-uiStandaloneGalleryPreview",
                           "standaloneGallerySearchAndDraft", "standaloneGalleryFavoriteFilter",
                           "standaloneGalleryFavoriteSearchRebuild", "standaloneGalleryPagination",
                           "standaloneGalleryPreviousPage", "standaloneGalleryDraftNeverApplied",
                           "gallery-browse-uiStandaloneControlsInBounds", "gallery-browse-uiStandaloneGalleryPreview",
                           "standaloneModelSettingsUiScreen", "model-settings-uiStandaloneControlsInBounds",
                           "standaloneModelSettingsHeaddressGeometry", "standaloneModelSettingsHeaddressRestored",
                           "standaloneModelSettingsSaveCallback", "standaloneModelSettingsPreview",
                           "standaloneWheelPageAndLock", "standaloneWheelHover", "standaloneWheelUsesLocalApi",
                           "standaloneWheelNumberConsumesHold", "standaloneWheelStopButton", "standaloneWheelStopped",
                           "standaloneWheelReleaseSelectsOnce", "standaloneWheelLockOnlyKeepsUi",
                           "standaloneWheelUiSeparateModes", "standaloneWheelLabelsAndWidgetsSeparate",
                           "wheel-uiStandaloneControlsInBounds", "standaloneGalleryPreviewAfterReload",
                           "ui-reload-uiStandaloneControlsInBounds", "ui-reload-uiStandaloneGalleryPreview"):
            assert standalone_checks.get(check_name) is True, f"Missing independent-client regression proof: {check_name}"
        for check_name in (
                "standaloneBuiltinPreviewSourceEvidence", "standaloneGallerySameSelectionCallback", "standaloneGalleryFirstEntryProgressesOnce",
                "standaloneGalleryPreviewClockStable", "standaloneGalleryEyeAtlasSourceBounds",
                "standaloneGalleryMainAndThumbnailEyes", "standaloneGalleryEyesAfterReload",
                "standaloneModelSettingsEyes", "standaloneFirstPersonYsmArmMesh",
                "standaloneWheelActionLockIndependent", "standaloneWheelActionLockRestored",
                "standaloneWheelAuthorConfigEntry", "standaloneAuthorConfigSource",
                "standaloneAuthorConfigDraftPrivate", "standaloneAuthorConfigApplyPrivate",
                "standaloneAuthorConfigChangesWorldGeometry", "standaloneAuthorConfigRestoreCallback",
                "standaloneAuthorConfigRestoredGeometry", "standaloneAuthorConfigCloseCallback",
                "standaloneGalleryAuthorGuiLayers", "standaloneGalleryAuthorRotationLocked",
                "standaloneModelSettingsAuthorGuiLayers", "standaloneModelSettingsAuthorRotationLocked",
                "standaloneGalleryAuthorGuiLayersAfterReload",
                "standaloneVehicleComponentActualOwnerAndBinding", "standaloneVehicleComponentActualDraw",
                "standaloneProjectileComponentActualOwnerAndBinding", "standaloneProjectileComponentActualDraw",
                "standaloneComponentFixtureCleanup",
                "standaloneYsmEffectsFixtureUsesCurrentBinding", "standaloneYsmEffectsTimelineStarted",
                "standaloneYsmSoundTimelineCallsNativeBackend", "standaloneYsmParticleTimelineCallsNativeBackend",
                "standaloneYsmCustomOggStreamActuallyOpened", "standaloneYsmCustomLoopSoundActuallyPlaying",
                "standaloneYsmNativePackSoundActuallyPlaying", "standaloneYsmParticlesActuallySpawned",
                "standaloneYsmStopSoundScoped", "standaloneYsmStopAllScoped", "standaloneYsmEffectsReloadCleanup",
                "standaloneYsmEffectsResetCleanup", "standaloneYsmEffectsOriginalProfileRestored",
                "standaloneYsmEffectsNoServerRequests", "standaloneYsmEffectsFixtureCleaned",
                "standaloneYsmEffectsActiveScreenshot"):
            assert standalone_checks.get(check_name) is True, f"Missing local YSM migration proof: {check_name}"
        assert standalone_report.get("guiPreviewRegression", {}).get("passed") is True, \
            "Missing real GUI preview/eyes/entry acceptance"
        assert standalone_report.get("modelEffects", {}).get("passed") is True, "Missing actual sound/particle lifecycle acceptance"
    standalone_launch = read_json(standalone_report_path.parent / "launch.json")
    assert standalone_launch["clientJarSha256"].lower() == digest(client_jar), "Independent launch used another JAR"
    standalone_server_path = PROJECT / "build/standalone-server/standalone-proof.json"
    standalone_server = read_json(standalone_server_path)
    assert standalone_server_path.stat().st_mtime_ns >= standalone_tested_at, "Stale empty-server proof"
    assert standalone_server["pluginJarCount"] == 0 and standalone_server["unexpectedNestedJarCount"] == 0 \
        and standalone_server["forbiddenPluginsAbsentFromRuntimeLog"] is True and standalone_server["zeroPluginsConfirmedByLog"] is True \
        and standalone_server["serverStartupComplete"] is True and standalone_server["listenersLoopbackOnly"] is True, \
        "Independent client must be tested on a running empty-plugin server"
    evidence = {"standalone/server-proof.json": standalone_server_path.read_bytes()}
    focused_server_proof = None
    if focused:
        focused_server_proof, runtime_evidence = validate_focused_server(jar, client_jar, version, tested_at, game_report, observer_report)
        evidence.update(runtime_evidence)
    for log_source in standalone_server.get("runtimeLogSources", []):
        if str(log_source.get("kind", "")).startswith("archived startup"):
            log_path = Path(log_source["path"]).resolve()
            assert log_path.is_relative_to((PROJECT / "build/standalone-server/logs").resolve())
            assert log_path.name.endswith(".log.gz") and log_path.stat().st_size <= 10 * 1024 * 1024
            assert digest(log_path) == log_source["sha256"].lower(), "Archived empty-server startup log changed"
            evidence[f"standalone/runtime-logs/{log_path.name}"] = log_path.read_bytes()
    evidence["two-client/window-layout.json"] = layout_path.read_bytes()
    for label, report_path, report, current in (
            ("client", game_report_path, game_report, game_current),
            ("observer-without-mpa", observer_report_path, observer_report, observer_current),
            ("standalone", standalone_report_path, standalone_report, True)):
        if not current:
            continue
        evidence[f"{label}/results.json"] = report_path.read_bytes()
        evidence[f"{label}/launch.json"] = (report_path.parent / "launch.json").read_bytes()
        screenshots = report.get("screenshots", [])
        assert screenshots, f"No validation screenshots: {label}"
        for relative in screenshots:
            assert isinstance(relative, str)
            screenshot = (report_path.parent / relative).resolve()
            assert screenshot.is_relative_to(report_path.parent.resolve()) and screenshot.suffix == ".png"
            assert screenshot.is_file(), f"Missing validation screenshot: {relative}"
            screenshot_minimum_time = standalone_tested_at if label == "standalone" else tested_at
            assert screenshot.stat().st_mtime_ns >= screenshot_minimum_time, f"Stale validation screenshot: {relative}"
            pixels = screenshot.read_bytes()
            assert pixels.startswith(b"\x89PNG\r\n\x1a\n"), f"Invalid PNG screenshot: {relative}"
            evidence[f"{label}/{relative}"] = pixels
    evidence_zip = None
    if evidence:
        for filename in ("model-bounds-probe.txt", "model-bounds-corrected.txt", "animation-axes-research.md"):
            evidence_file = client / "build/observer-test" / filename
            if evidence_file.is_file() and evidence_file.stat().st_mtime_ns >= tested_at:
                evidence[f"model-coordinate-checks/{filename}"] = evidence_file.read_bytes()
        if focused:
            focused_proof = {"validationProfile": "interactions", "scope": "interactions", "passed": True,
                             "serverJarSha256": digest(jar), "clientJarSha256": digest(client_jar),
                             "serverValidation": baseline_proof, "clientValidation": targeted_client_proof,
                             "serverRuntime": focused_server_proof,
                             "clientGuiAssets": client_gui_proof,
                             "clientWineFoxAssets": client_wine_fox_proof,
                             "interactionDelivery": game_report["interactionDelivery"],
                             "standaloneDelivery": standalone_report["interactionDelivery"],
                             "observerChecks": observer_report["checks"],
                             "fullAcceptanceRerun": False}
            evidence["server/focused-proof.json"] = (json.dumps(focused_proof, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
            evidence["baseline/validation.json"] = (DIST / f"MEPlayerActions-{BASELINE_VERSION}-validation.json").read_bytes()
            evidence["source-assets/builtin-wine-fox-manifest.json"] = (client / "builtin-wine-fox-manifest.json").read_bytes()
            evidence["source-assets/LICENSE.CC-BY-NC-SA-4.0.txt"] = (PROJECT / "docs/licenses/CC-BY-NC-SA-4.0.txt").read_bytes()
            with zipfile.ZipFile(DIST / f"MEPlayerActions-{BASELINE_VERSION}-tests.zip") as archive:
                evidence["baseline/server-matrix-proof.json"] = archive.read("server/matrix-proof.json")
            for fact in targeted_client_proof["reports"]:
                evidence["unit-tests/" + Path(fact["path"]).name] = (PROJECT / fact["path"]).read_bytes()
        else:
            server_proof_path = PROJECT / "build/e2e-server/matrix-proof.json"
            assert server_proof_path.is_file(), "Server acceptance matrix proof required"
            server_proof = read_json(server_proof_path)
            assert server_proof_path.stat().st_mtime_ns >= tested_at, "Stale server matrix proof timestamp"
            assert server_proof["serverJarSha256"].lower() == digest(jar), "Stale server matrix proof"
            assert server_proof["clientJarSha256"].lower() == digest(client_jar), "Server matrix used another client JAR"
            assert server_proof["serverTests"] == totals["tests"], "Server matrix has outdated unit test totals"
            assert server_proof["passed"] is True and server_proof["checks"] \
                and all(check["passed"] is True for check in server_proof["checks"]), "Server matrix proof failed"
            evidence["server/matrix-proof.json"] = server_proof_path.read_bytes()
        evidence["tested-artifacts.json"] = (json.dumps({
            "minecraft": "1.21.11", "server_sha256": digest(jar), "client_sha256": digest(client_jar),
            "client_passed": game_passed, "observer_without_mpa_passed": observer_passed,
        }, indent=2) + "\n").encode("utf-8")
        for relative in ("client/build/e2e/launch_client.py", "build/demo/arrange-windows.ps1", "client/build/observer-test/src/ObserverHarness.java",
                         "client/build/observer-test/build_observer.ps1", "build/e2e-server/write-matrix-proof.ps1",
                         "build/standalone-server/start-standalone.ps1", "build/standalone-server/write-standalone-proof.ps1",
                         "build/e2e-helper/MPATestHelper.java", "build/e2e-helper/build_helper.ps1",
                         "build/e2e-helper/plugin.yml", "build/e2e-helper/classpath.txt"):
            fixture = PROJECT / relative
            assert fixture.is_file(), f"Missing acceptance fixture: {relative}"
            evidence[f"fixtures/{relative}"] = fixture.read_bytes()
        if focused:
            relative = "build/e2e-server/write-focused-proof.ps1"
            fixture = PROJECT / relative
            assert fixture.is_file(), "Focused runtime proof writer is required for reproducible evidence"
            evidence[f"fixtures/{relative}"] = fixture.read_bytes()
        evidence_zip = DIST / f"{name}-tests.zip"

    import subprocess, sys
    if not focused:
        subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", str(PROJECT / "tools/tests"), "-v"], check=True, cwd=PROJECT)
    engine_pack = PROJECT / "build/e2e-server/plugins/ModelEngine/resource pack.zip"
    assert engine_pack.is_file(), "Ordinary ME resource pack used by acceptance is required"
    engine_pack_hash = digest(engine_pack)
    with zipfile.ZipFile(engine_pack) as pack:
        assert pack.testzip() is None, "Invalid ordinary ME acceptance pack"
        assert "pack.mcmeta" in pack.namelist()
        assert not any(entry.startswith("assets/meplayeractions/") for entry in pack.namelist()), \
            "Push acceptance accidentally relied on MPA resources in the ME pack"
        assert any(entry.startswith("assets/modelengine/") for entry in pack.namelist())
    for launch in (game_launch, observer_launch):
        assert launch.get("resourcePackSha256") == engine_pack_hash, "Acceptance used another ME pack"
    evidence["resource-pack/ordinary-me-pack-proof.json"] = (json.dumps({
        "sha256": engine_pack_hash, "mpaIndexAbsent": True,
        "generationOwner": "ModelEngine", "validationProfile": profile,
        "serverPushDelivery": read_json(DIST / f"MEPlayerActions-{BASELINE_VERSION}-validation.json")["server_model_delivery"]["acceptance"] if focused else game_report["serverPushDelivery"],
        "serverPushEvidenceMode": "reused-baseline" if focused else "fresh-full",
    }, ensure_ascii=False, indent=2) + "\n").encode("utf-8")

    # Do not create apparently deliverable artifacts until every acceptance gate passes.
    DIST.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(jar, delivered_jar)
    shutil.copyfile(client_jar, delivered_client)
    write_zip(install_zip, install)
    write_zip(source_zip, source_entries)
    client_install = {f"mods/{client_jar.name}": client_jar.read_bytes(),
                                   "README.md": install["README.md"],
                                   "ARCHITECTURE.md": architecture.read_bytes(),
                                   "docs/CLIENT_PROTOCOL.md": protocol.read_bytes(),
                                   "docs/CLIENT_RESOURCE_PACK.md": (PROJECT / "docs/CLIENT_RESOURCE_PACK.md").read_bytes(),
                                   **documentation_entries(),
                                   "THIRD_PARTY_NOTICES.md": (PROJECT / "THIRD_PARTY_NOTICES.md").read_bytes()}
    rebase_documentation(client_install, version)
    write_zip(client_install_zip, client_install)
    assert digest(delivered_jar) == digest(jar)
    assert digest(delivered_client) == digest(client_jar)
    assert evidence_zip is not None, "Missing two-client evidence bundle"
    write_zip(evidence_zip, evidence)

    def report_summary(report: dict | None) -> dict | None:
        if report is None:
            return None
        return {key: report[key] for key in ("passed", "complete", "minecraft", "clientVersion",
                "installedMpa", "testedClientSha256", "testedServerSha256", "artifactProof",
                "checks", "screenshots", "enabledPacks", "validationProfile", "interactionDelivery") if key in report}

    fingerprint = hashlib.sha256()
    for path in compiler_inputs:
        fingerprint.update(path.relative_to(PROJECT).as_posix().encode())
        fingerprint.update(b"\0")
        fingerprint.update(path.read_bytes())
    validation = {
        "plugin": "MEPlayerActions", "version": version, "authors": ["SIMMC", "Loliiiico"],
        "validation_profile": profile, "full_acceptance_rerun": not focused,
        "server_test_execution": baseline_proof if focused else {"mode": "fresh-full", "testsRerun": True},
        "focused_server_runtime": focused_server_proof,
        "client_test_execution": targeted_client_proof if focused else {"mode": "fresh-full"},
        "client_gui_assets": client_gui_proof,
        "client_wine_fox_assets": client_wine_fox_proof,
        "target": {"paper": "1.21.11", "modelengine": "R4.1.1", "optional_gsit": "3.5.1 (tested; public posture API checked at runtime)", "java_release": 21},
        "test_totals": totals, "test_suites": suites, "client_test_totals": client_totals,
        "client_target": {"minecraft": "1.21.11", "loader": "Fabric", "java_release": 21,
                          "renderer": "RenderCommandQueue custom geometry", "protocol": 3},
        "compiled_source_fingerprint": fingerprint.hexdigest(),
        "compiled_class_count": len(classes), "animations": animation_names,
        "models": model_facts,
        "server_model_delivery": {"mode": "server-push", "capability": "server_push_models",
                                  "ordinary_me_pack_sha256": engine_pack_hash, "mpa_index_required": False,
                                  "evidence_mode": "reused-baseline" if focused else "fresh-full",
                                  "acceptance": read_json(DIST / f"MEPlayerActions-{BASELINE_VERSION}-validation.json")["server_model_delivery"]["acceptance"] if focused else game_report["serverPushDelivery"]},
        "ysm_physics_step_seconds": .01,
        "runtime_expressions": "Per-instance bounded interpreter; frame-based client and tick-based ModelEngine",
        "compatibility_modpack": game_launch.get("sourceModpack"),
        "compatibility_mods": game_launch.get("mods"),
        "compatibility_runtime_overrides": game_launch.get("compatibilityOverrides", []),
        "owned_disguise_anchor": "shared visual history; player feet, native bed center/top, or GSit seat contact plane; no player mounting",
        "owned_disguise_audience": {"show_self_default": True, "strict_distance_blocks_default": 8,
                                    "max_other_viewers_default": 10, "selection": "nearest tracked eligible players, UUID tie break",
                                    "update_ticks": 1, "protocol_recipient_filter": True},
        "disguise_effect_allowlist": ["slowness"], "disguise_model_argument_required_first": True,
        "root_command": "meplayeractions", "command_aliases": [],
        "organized_subcommands": ["help", "disguise", "undisguise", "models", "attach", "menu", "animations",
                                   "play", "stop", "reset", "pose", "sync", "status", "reload"],
        "bundled_animation_labels": "Chinese defaults, with configuration overrides and custom action label precedence",
        "compile_dependency_hashes": {p.name: digest(p) for p in
            [PROJECT.parent / "ModelEngine-R4.1.1.jar", *sorted((PROJECT / "build/deps").glob("paper-api-*.jar"))]
            if p.is_file()},
        "artifacts": {p.name: {"sha256": digest(p), "bytes": p.stat().st_size}
                      for p in (delivered_jar, install_zip, source_zip, delivered_client, client_install_zip, evidence_zip)
                      if p is not None},
        "zip_integrity_and_content_match": True, "dependency_jars_included": False,
        "in_game_verified": game_passed,
        "two_client_in_game_verified": game_passed and observer_passed,
        "standalone_client_verified": True,
        "interaction_delivery": game_report.get("interactionDelivery") if focused else None,
        "standalone_interaction_delivery": standalone_report.get("interactionDelivery") if focused else None,
        "standalone_game_validation": report_summary(standalone_report),
        "game_validation_current_for_artifacts": game_current,
        "observer_validation_current_for_artifacts": observer_current,
        "game_validation": report_summary(game_report),
        "observer_game_validation": report_summary(observer_report),
        "resource_pack_in_install_zip": False,
        "client_local_rendering_implemented": True,
    }
    validation_path = DIST / f"{name}-validation.json"
    validation_path.write_text(json.dumps(validation, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"version": version, "tests": totals, "jar": str(delivered_jar),
                      "install_zip": str(install_zip), "source_zip": str(source_zip),
                      "client_jar": str(delivered_client), "client_install_zip": str(client_install_zip),
                      "validation": str(validation_path), "test_evidence_zip": str(evidence_zip) if evidence_zip else None},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
