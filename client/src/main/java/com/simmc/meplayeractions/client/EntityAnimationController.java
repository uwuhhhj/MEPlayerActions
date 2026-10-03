package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.client.model.BbModel.Layer;
import java.util.*;

/** Per-owner motion sampled from the viewer's vanilla entity, using a local tick clock. */
public final class EntityAnimationController {
    public record Sample(double x, double y, double z, boolean grounded, boolean bedSleeping, boolean prone,
                         boolean inWater, boolean flying, boolean gliding, boolean sneaking, boolean sprinting,
                         String vehicle, boolean swinging, int swingTick, boolean offhand, boolean mining, boolean localPlayer, boolean climbing) {
        public Sample(double x,double y,double z,boolean grounded,boolean bedSleeping,boolean prone,boolean inWater,
                      boolean flying,boolean gliding,boolean sneaking,boolean sprinting,String vehicle,boolean swinging,
                      int swingTick,boolean offhand,boolean mining,boolean localPlayer) {
            this(x,y,z,grounded,bedSleeping,prone,inWater,flying,gliding,sneaking,sprinting,vehicle,swinging,swingTick,offhand,mining,localPlayer,false);
        }
    }
    private Sample previous;
    private long lastTick = Long.MIN_VALUE, postureStarted, jumpStarted, landed = -1, swingStarted;
    private boolean jumping;
    private String state = "", previousPosture = "", suppressedManual = "";
    private List<Layer> layers = List.of();

    public void update(long tick, Sample sample, LocalMotionPolicy policy, List<Layer> serverLayers) {
        if (tick == lastTick) return;
        double dx = previous == null ? 0 : sample.x - previous.x, dy = previous == null ? 0 : sample.y - previous.y;
        double dz = previous == null ? 0 : sample.z - previous.z;
        boolean discontinuity = previous == null || tick < lastTick || dx * dx + dy * dy + dz * dz > 16 || tick - lastTick > 40;
        if (discontinuity) { jumping = false; landed = -1; state = ""; suppressedManual = ""; }
        boolean moving = !discontinuity && (dx * dx + dz * dz > policy.movementThreshold() * policy.movementThreshold()
                || (sample.flying || sample.climbing) && Math.abs(dy) > policy.movementThreshold());
        String posture = posture(sample, policy);
        boolean blocked = !posture.equals("standing") && !posture.equals("sneak") || !policy.enabled("jump");
        boolean newJump = !discontinuity && !blocked && previous.grounded && !sample.grounded && dy > .03;
        if (blocked) { jumping = false; landed = -1; }
        else if (newJump) { jumping = true; jumpStarted = tick; landed = -1; }
        String air = "";
        if (!blocked) {
            if (!sample.grounded) {
                landed = -1;
                air = jumping && tick - jumpStarted < policy.jumpMinTicks() ? "jump" : "fall";
            } else if (jumping) {
                if (landed < 0) landed = tick;
                if (tick - jumpStarted < policy.jumpMinTicks() && tick - landed < policy.landingGraceTicks()) air = "jump";
                else jumping = false;
            }
        }
        String next = select(sample, policy, posture, moving, air);
        if (!next.equals(state) || newJump && next.equals("jump")) { state = next; postureStarted = tick; }
        List<Layer> result = new ArrayList<>();
        Layer automatic = policy.layer(state, "posture", postureStarted);
        if (automatic != null) result.add(automatic);
        Layer manual = serverLayers.stream().filter(layer -> layer.layer().equals("manual")).findFirst().orElse(null);
        String manualKey = manual == null ? "" : manual.animation() + ":" + manual.startedAtTick();
        if (manual == null) suppressedManual = "";
        else if (policy.interruptMove() && (moving || newJump || sample.sneaking)
                || policy.interruptPosture() && !discontinuity && !posture.equals(previousPosture)) suppressedManual = manualKey;
        boolean showManual = manual != null && !manualKey.equals(suppressedManual);
        if (showManual) result.add(manual);
        if (!showManual && !posture.equals("sleep") && !posture.equals("bed-sleep")) {
            Layer interaction = serverLayers.stream().filter(layer -> layer.layer().equals("interaction")).findFirst().orElse(null);
            Layer mining = policy.clips().get("mining");
            boolean remoteMining = !sample.localPlayer && policy.interaction().equals("mining") && interaction != null && mining != null;
            if (mining != null && policy.enabled("mining") && (sample.mining || remoteMining)) {
                Layer clip = policy.layer("mining", "interaction", interaction == null ? tick : interaction.startedAtTick());
                // A local mining loop must retain its start across ticks without waiting for server acknowledgement.
                if (interaction == null) {
                    Layer existing = layers.stream().filter(layer -> layer.layer().equals("interaction") && layer.animation().equals(mining.animation())).findFirst().orElse(null);
                    if (existing != null) clip = existing;
                }
                if (clip != null) result.add(clip);
            } else if (policy.enabled("swing") && sample.swinging) {
                if (previous == null || !previous.swinging || sample.swingTick < previous.swingTick) swingStarted = tick;
                Layer clip = policy.layer(sample.offhand ? "swing-offhand" : "swing-mainhand", "interaction", swingStarted);
                if (clip != null) result.add(clip);
            }
        }
        layers = List.copyOf(result); previous = sample; previousPosture = posture; lastTick = tick;
    }
    private static String posture(Sample sample, LocalMotionPolicy policy) {
        if (!policy.specialPose().isEmpty()) return policy.specialPose();
        if (sample.bedSleeping) return "bed-sleep";
        if ((sample.prone || policy.forcedPose().equals("crawl")) && !sample.inWater && !sample.flying && !sample.gliding) return "crawl";
        if (!sample.vehicle.isEmpty()) return sample.vehicle;
        if (sample.gliding) return "elytra";
        if (sample.flying) return "flight";
        if (sample.prone && sample.inWater) return "swim-prone";
        if (sample.inWater && !sample.grounded) return "swim";
        if (sample.climbing) return "ladder";
        return sample.sneaking ? "sneak" : "standing";
    }
    private static String select(Sample sample, LocalMotionPolicy p, String posture, boolean moving, String air) {
        return switch (posture) {
            case "bed-sleep", "sleep" -> p.enabled("sleep") ? posture : "";
            case "sit" -> p.enabled("sit") ? "sit" : "";
            case "crawl" -> p.enabled("crawl") ? moving ? "crawl-walk" : "crawl-idle" : "";
            case "boat", "minecart", "ride", "ride-pig" -> p.enabled("ride") ? posture : "";
            case "ladder" -> p.enabled("movement") ? moving ? "ladder-move" : "ladder-idle" : "";
            case "elytra" -> p.enabled("elytra") ? "elytra" : "";
            case "flight" -> p.enabled("flight") ? moving ? "fly" : "hover" : "";
            case "swim-prone" -> p.enabled("swim") ? moving ? "swim-walk" : "swim-prone-idle" : "";
            case "swim" -> p.enabled("swim") ? "swim-idle" : "";
            default -> !air.isEmpty() ? air : sample.sneaking && sample.grounded ? p.enabled("sneak")
                    ? moving ? "crouch-walk" : "crouch-idle" : "" : !p.enabled("movement") ? ""
                    : moving ? sample.sprinting && p.enabled("sprint") ? "run" : "walk" : "idle";
        };
    }
    public String state() { return state; }
    public List<Layer> layers() { return layers; }
}
