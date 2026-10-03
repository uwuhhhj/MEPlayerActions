package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.client.model.BbModel.Layer;
import java.util.*;

/** Per-owner motion sampled from the viewer's vanilla entity, using a local tick clock. */
public final class EntityAnimationController {
    public record Sample(double x, double y, double z, boolean grounded, boolean bedSleeping, boolean prone,
                         boolean inWater, boolean flying, boolean gliding, boolean sneaking, boolean sprinting,
                         String vehicle, boolean swinging, int swingTick, boolean offhand, boolean mining, boolean localPlayer,
                         boolean climbing, VanillaYsmAnimations.VanillaState vanilla) {
        public Sample(double x,double y,double z,boolean grounded,boolean bedSleeping,boolean prone,boolean inWater,
                      boolean flying,boolean gliding,boolean sneaking,boolean sprinting,String vehicle,boolean swinging,
                      int swingTick,boolean offhand,boolean mining,boolean localPlayer,boolean climbing) {
            this(x,y,z,grounded,bedSleeping,prone,inWater,flying,gliding,sneaking,sprinting,vehicle,swinging,swingTick,offhand,mining,localPlayer,climbing,null);
        }
        public Sample(double x,double y,double z,boolean grounded,boolean bedSleeping,boolean prone,boolean inWater,
                      boolean flying,boolean gliding,boolean sneaking,boolean sprinting,String vehicle,boolean swinging,
                      int swingTick,boolean offhand,boolean mining,boolean localPlayer) {
            this(x,y,z,grounded,bedSleeping,prone,inWater,flying,gliding,sneaking,sprinting,vehicle,swinging,swingTick,offhand,mining,localPlayer,false,null);
        }
    }
    private Sample previous;
    private long lastTick = Long.MIN_VALUE, postureStarted, jumpStarted, landed = -1, swingStarted;
    private boolean jumping;
    private String state = "", previousPosture = "", suppressedManual = "";
    private List<Layer> layers = List.of();
    private final Map<String,Layer> handLayers = new LinkedHashMap<>();
    private final VanillaYsmAnimations.HandPlayback handPlayback = new VanillaYsmAnimations.HandPlayback();
    private List<String> animationNames = List.of();
    private VanillaYsmAnimations.Catalog catalog, legacyCatalog;
    private Set<String> pausedControllers = Set.of();
    private String automaticAnimation = "";

    public void update(long tick, Sample sample, LocalMotionPolicy policy, List<Layer> serverLayers) {
        var names = new LinkedHashSet<String>();
        policy.clips().values().forEach(clip -> names.add(clip.animation()));
        update(tick,sample,policy,serverLayers,names);
    }
    public void update(long tick, Sample sample, LocalMotionPolicy policy, List<Layer> serverLayers, Collection<String> availableAnimations) {
        if (tick == lastTick) return;
        List<String> names = List.copyOf(availableAnimations);
        if(legacyCatalog==null || !animationNames.equals(names)) { animationNames=names;legacyCatalog=new VanillaYsmAnimations.Catalog(names); }
        update(tick, sample, policy, serverLayers, legacyCatalog);
    }
    public void update(long tick, Sample sample, LocalMotionPolicy policy, List<Layer> serverLayers,
                       VanillaYsmAnimations.Catalog availableAnimations) {
        if (tick == lastTick) return;
        catalog = Objects.requireNonNull(availableAnimations);
        double dx = previous == null ? 0 : sample.x - previous.x, dy = previous == null ? 0 : sample.y - previous.y;
        double dz = previous == null ? 0 : sample.z - previous.z;
        boolean discontinuity = previous == null || tick < lastTick || dx * dx + dy * dy + dz * dz > 16 || tick - lastTick > 40;
        if (discontinuity) { jumping = false; landed = -1; state = ""; suppressedManual = "";handLayers.clear();handPlayback.reset();automaticAnimation=""; }
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
        String next = select(sample, policy, posture, moving, air, dy);
        if(sample.vanilla!=null && sample.vanilla.hurtTime()>0 && policy.enabled("movement")
                && Set.of("standing","sneak").contains(posture) && !catalog.first("attacked","hurt").isEmpty())next="attacked";
        boolean newHurt=next.equals("attacked") && sample.vanilla!=null && (previous==null || previous.vanilla==null
                || sample.vanilla.hurtTime()>previous.vanilla.hurtTime());
        if (!next.equals(state) || newJump && next.equals("jump") || newHurt) { state = next; postureStarted = tick; }
        List<Layer> result = new ArrayList<>();
        Layer automatic = policy.layer(state, "posture", postureStarted);
        if(sample.vanilla!=null) {
            boolean riding=sample.vanilla.vehicleAlive() && !sample.vehicle.isEmpty() && policy.specialPose().isEmpty()
                    && !sample.bedSleeping && !sample.vanilla.sleeping() && !sample.vanilla.dead();
            String condition=riding && policy.enabled("ride")?catalog.vehicle(sample.vanilla):"";
            String nativeClip=!condition.isEmpty()?condition:automatic==null?nativeClip(state):automatic.animation();
            if(!nativeClip.isEmpty()) {
                if(!nativeClip.equals(automaticAnimation))postureStarted=tick;
                String loop=automatic==null?Set.of("death","attacked").contains(state)?"ONCE":"LOOP":automatic.loop();
                automatic=new Layer(riding?"player.vehicle":"posture",nativeClip,postureStarted,
                        automatic==null?1:automatic.speed(),loop,automatic==null?2:automatic.inTicks(),automatic==null?2:automatic.outTicks());
            }
        }
        automaticAnimation=automatic==null?"":automatic.animation();
        if (automatic != null) result.add(automatic);
        Layer manual = serverLayers.stream().filter(layer -> layer.layer().equals("manual")).findFirst().orElse(null);
        String manualKey = manual == null ? "" : manual.animation() + ":" + manual.startedAtTick();
        if (manual == null) suppressedManual = "";
        else if (policy.interruptMove() && (moving || newJump || sample.sneaking)
                || policy.interruptPosture() && !discontinuity && !posture.equals(previousPosture)) suppressedManual = manualKey;
        boolean showManual = manual != null && !manualKey.equals(suppressedManual) && (sample.vanilla==null || !sample.vanilla.dead());
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
            } else if (policy.enabled("swing") && sample.swinging && sample.vanilla==null) {
                if (previous == null || !previous.swinging || sample.swingTick < previous.swingTick) swingStarted = tick;
                Layer clip = policy.layer(sample.offhand ? "swing-offhand" : "swing-mainhand", "interaction", swingStarted);
                if (clip != null) result.add(clip);
            }
        }
        pausedControllers=Set.of();
        if(sample.vanilla!=null) {
            var decision=VanillaYsmAnimations.select(sample.vanilla,catalog);
            pausedControllers=decision.pauseSlots();
            for(var entry:decision.slots().entrySet()) {
                String slot=entry.getKey();var choice=entry.getValue();
                handPlayback.observe(slot,choice,sample.vanilla);
                boolean disabled=slot.equals("player.swing") && (!policy.enabled("swing") || showManual
                        || result.stream().anyMatch(layer->layer.layer().equals("interaction")))
                        || slot.equals("player.use") && showManual;
                if(disabled || choice.directive()==VanillaYsmAnimations.Directive.STOP) {
                    handLayers.remove(slot);handPlayback.stop(slot);continue;
                }
                if(choice.directive()==VanillaYsmAnimations.Directive.PAUSE || choice.directive()==VanillaYsmAnimations.Directive.CONTINUE) {
                    // OpenYSM PAUSE keeps controller time but submits no transforms; the renderer
                    // receives pausedControllers via numeric queries and owns that controller clock.
                    Layer retained=handLayers.get(slot);if(retained!=null)result.add(retained);
                    continue;
                }
                long started=handPlayback.start(slot,choice,sample.vanilla,tick);
                Layer layer=new Layer(slot,choice.animation(),started,1,choice.loop(),slot.equals("player.swing")?0:2,2);
                handLayers.put(slot,layer);result.add(layer);
            }
        }
        layers = List.copyOf(result); previous = sample; previousPosture = posture; lastTick = tick;
    }
    private static String posture(Sample sample, LocalMotionPolicy policy) {
        if(sample.vanilla!=null && sample.vanilla.dead())return "death";
        if (!policy.specialPose().isEmpty()) return policy.specialPose();
        if (sample.bedSleeping || sample.vanilla!=null && sample.vanilla.sleeping()) return "bed-sleep";
        if(sample.vanilla!=null && sample.vanilla.vehicleAlive() && !sample.vehicle.isEmpty())return sample.vehicle;
        if(sample.vanilla!=null && sample.vanilla.riptide())return "riptide";
        if(sample.vanilla!=null && sample.vanilla.swimming())return "swim-prone";
        if ((sample.prone || policy.forcedPose().equals("crawl")) && (!sample.inWater || sample.vanilla!=null)
                && !sample.flying && !sample.gliding) return "crawl";
        if (!sample.vehicle.isEmpty() && sample.vanilla==null) return sample.vehicle;
        if (sample.gliding) return "elytra";
        if (sample.flying) return "flight";
        if (sample.prone && sample.inWater) return "swim-prone";
        if (sample.inWater && !sample.grounded) return "swim";
        if (sample.climbing) return "ladder";
        return sample.sneaking ? "sneak" : "standing";
    }
    private static String select(Sample sample, LocalMotionPolicy p, String posture, boolean moving, String air, double vertical) {
        return switch (posture) {
            case "death" -> "death";
            case "riptide" -> p.enabled("swim") ? "riptide" : "";
            case "bed-sleep", "sleep" -> p.enabled("sleep") ? posture : "";
            case "sit" -> p.enabled("sit") ? "sit" : "";
            case "crawl" -> p.enabled("crawl") ? moving ? "crawl-walk" : "crawl-idle" : "";
            case "boat", "minecart", "ride", "ride-pig" -> p.enabled("ride") ? posture : "";
            case "ladder" -> p.enabled("movement") ? moving ? vertical<0 && sample.vanilla!=null ? "ladder-down" : "ladder-move" : "ladder-idle" : "";
            case "elytra" -> p.enabled("elytra") ? "elytra" : "";
            case "flight" -> p.enabled("flight") ? moving ? "fly" : "hover" : "";
            case "swim-prone" -> p.enabled("swim") ? sample.vanilla!=null && sample.vanilla.swimming() || moving ? "swim-walk" : "swim-prone-idle" : "";
            case "swim" -> p.enabled("swim") ? "swim-idle" : "";
            default -> !air.isEmpty() ? air : sample.sneaking && sample.grounded ? p.enabled("sneak")
                    ? moving ? "crouch-walk" : "crouch-idle" : "" : !p.enabled("movement") ? ""
                    : moving ? sample.sprinting && p.enabled("sprint") ? "run" : "walk" : "idle";
        };
    }
    private String nativeClip(String state) {
        return switch(state) {
            case "death" -> catalog.first("death");
            case "attacked" -> catalog.first("attacked","hurt");
            case "riptide" -> catalog.first("riptide");
            case "ladder-down" -> catalog.first("ladder_down","ladder_up","climb");
            default -> "";
        };
    }
    public String state() { return state; }
    public List<Layer> layers() { return layers; }
    public Set<String> pausedControllers() { return pausedControllers; }
}
