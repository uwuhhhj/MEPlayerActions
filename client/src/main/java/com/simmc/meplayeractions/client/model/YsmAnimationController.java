package com.simmc.meplayeractions.client.model;

import com.google.gson.*;
import com.simmc.meplayeractions.expression.Molang;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Bounded, per-instance interpretation of OpenYSM controller definitions.
 * Controller and playback routines migrated from OpenYSM-Updated 0306e1f (MIT), adapted to the bounded MPA model representation.
 * State animation expressions enable slots; clip blend_weight supplies their numeric influence.
 */
public final class YsmAnimationController {
    record Animation(String name, Molang.Program enabled) { }
    record Transition(String target, Molang.Program condition) { }
    record State(String name, List<Animation> animations, List<Transition> transitions,
                 List<Molang.Program> entry, List<Molang.Program> exit, Blend blend, List<String> sounds) { }
    record Definition(String initial, Map<String, State> states) { }
    public static final class Definitions {
        private final Map<String, Definition> definitions;
        private Definitions(Map<String, Definition> values) { definitions = Collections.unmodifiableMap(values); }
        public Set<String> names() { return definitions.keySet(); }
    }
    static final class Blend {
        final double[] times, values;
        Blend(double[] times, double[] values) { this.times = times; this.values = values; }
        double duration() { return times.length == 0 ? 0 : times[times.length - 1]; }
        double fraction(double elapsed) {
            if (times.length == 0 || elapsed >= duration()) return 1;
            if (elapsed <= times[0]) return values[0];
            for (int i = 1; i < times.length; i++) if (elapsed <= times[i]) {
                double t = (elapsed - times[i-1]) / (times[i] - times[i-1]);
                return values[i-1] + (values[i] - values[i-1]) * t;
            }
            return 1;
        }
        static Blend ticks(double duration) {
            return duration <= 0 ? new Blend(new double[0], new double[0])
                    : new Blend(new double[]{0, duration}, new double[]{0, 1});
        }
    }
    record Run(String animation, String loop, double before, double elapsed, boolean active, boolean events,
               Playback playback, boolean contributes, boolean beginning, boolean ending, double progress, long activation,
               double weight) { }
    record Frame(String name, long revision, double changedAt, Blend blend, List<Run> runs,
                 boolean suppressed, boolean deprecated, boolean cap, boolean builtin) { }

    private final BbModel model;
    private final Map<String, Runtime> runtimes = new LinkedHashMap<>();
    private final String family;
    private Runtime evaluating;
    private Playback evaluatingPlayback;
    private boolean captureAllowed;
    private boolean builtinEventPhase;
    private double tick;
    private long sequence;
    private long playbackSequence;
    private double firstTick = Double.NaN;
    private int pendingCaptures;
    private boolean globalSounds;

    /** OpenYSM 0306e1f AnimationControllerContext belongs to one animation instance/slot.
     * Captures wait for playback/state boundaries; they are not a frame-end queue.
     * Whole bone VectorValues remain local snapshots, unlike upstream's live IBone structs. */
    static final class Playback {
        static final int MAX_CAPTURES = 256, MAX_ARGUMENTS = 32;
        final Map<String,Object> scope = new HashMap<>();
        final String effectId = UUID.randomUUID().toString();
        final List<List<Object>> captures = new ArrayList<>();
        double animTime;
        int instructionIndex, effectIndex;
        boolean flushing;
        boolean sounds;
        boolean capture(List<Object> args) {
            if (flushing || args.isEmpty() || !(args.getFirst() instanceof String key) || key.isEmpty()
                    || args.size() - 1 > MAX_ARGUMENTS || captures.size() >= MAX_CAPTURES) return false;
            captures.add(Collections.unmodifiableList(new ArrayList<>(args.subList(1, args.size()))));
            return true;
        }
        void resetEvents() { instructionIndex = effectIndex = 0; }
        void clear() { captures.clear(); scope.clear(); animTime = 0; sounds = false; resetEvents(); }
    }

    Object defer(List<Object> args) {
        if (captureAllowed && evaluatingPlayback != null && pendingCaptures < 4096 && evaluatingPlayback.capture(args)) pendingCaptures++;
        return null;
    }
    void withCapture(boolean clientSide, Runnable action) {
        boolean previous = captureAllowed; captureAllowed = clientSide;
        try { action.run(); } finally { captureAllowed = previous; }
    }
    private void discard(Playback playback, Molang.Context context) { stopPlayback(playback, context); pendingCaptures = Math.max(0, pendingCaptures - playback.captures.size()); playback.clear(); }
    /** Source has distinct instance, controller PlaybackFlags and per-animation SoundKeyFrameExecutor managers. */
    void soundStarted(List<Object> arguments) {
        boolean global = arguments.size() >= 3 && ((int)number(arguments.get(2)) & 2) != 0;
        if (global || evaluating == null && evaluatingPlayback == null) globalSounds = true;
        else if (evaluatingPlayback != null) evaluatingPlayback.sounds = true;
        else evaluating.sounds = true;
    }
    private void stopScope(String scope, Molang.Context context, boolean global) {
        if (context == null || !context.nativeYsm() || context.functionResolver() == null) return;
        String previous = context.effectScope(); context.effectScope(scope);
        try { context.functionResolver().call("ysm.stop_all_sounds", global ? List.of(1d) : List.of()); }
        finally { context.effectScope(previous); }
    }
    private void stopPlayback(Playback playback, Molang.Context context) {
        if (!playback.sounds) return;
        playback.sounds = false; stopScope("playback:" + playback.effectId, context, false);
    }
    void withPlayback(Playback playback, boolean clientSide, Molang.Context context, Runnable action) {
        String previousEffectScope=context.effectScope();context.effectScope("playback:"+playback.effectId);
        Map<String,Object> previousScope = context.contextValues(), previousQueries = context.queryValues();
        Playback previous = evaluatingPlayback; boolean allowed = captureAllowed;
        evaluatingPlayback = playback; captureAllowed = clientSide;
        context.restoreContextValues(playback.scope); context.query("query.anim_time", playback.animTime);
        try { action.run(); }
        finally {
            playback.scope.clear(); playback.scope.putAll(context.contextValues());
            evaluatingPlayback = previous; captureAllowed = allowed;
            context.restoreContextValues(previousScope); context.restoreQueries(previousQueries);
            context.effectScope(previousEffectScope);
        }
    }
    private void flush(Playback playback, Molang.Context context) {
        withPlayback(playback, true, context, () -> {
            playback.flushing = true;
            try {
                // The first string is only an upstream nonempty-string gate, not an event/function selector.
                for (int i = playback.captures.size() - 1; i >= 0; i--) {
                    List<Object> arguments = playback.captures.get(i);
                    withoutBuiltinEvent(() -> model.authorEvent("defer", arguments, context));
                }
            } finally {
                pendingCaptures = Math.max(0, pendingCaptures - playback.captures.size());
                playback.flushing = false; playback.captures.clear(); context.restoreContextValues(Map.of());
            }
        });
    }

    YsmAnimationController(BbModel model) {
        this.model = model; family = model.controllerFamily();
        Set<String> names = new HashSet<>();
        for (String name : model.controllerDefinitions().definitions.keySet())
            if (name.startsWith(family + ".") && !isChild(name)) names.add(name);
        for (String fixed : List.of("vehicle", "main", "hold_offhand", "hold_mainhand", "swing", "use", "passenger", "cap", "gui_hover", "gui_focus"))
            names.add(family + "." + fixed);
        if (family.equals("vehicle")) for (String fixed : List.of("move", "origin", "ride")) names.add(family + "." + fixed);
        for (String event : model.controllerEvents().keySet())
            if (event.startsWith(family + ".") && !isChild(event)) names.add(event);
        for (String animation : model.animations()) {
            if (animation.matches("pre_parallel[0-7]")) names.add(family + ".pre_parallel_" + animation.substring(12));
            if (animation.matches("parallel[0-7]")) names.add(family + ".parallel_" + animation.substring(8));
        }
        names.stream().sorted(Comparator.comparingInt(YsmAnimationController::priority).thenComparing(s -> s))
                .forEach(name -> runtimes.put(name, new Runtime(name, name, 1)));
    }
    private boolean isChild(String name) { return name.substring(family.length()+1).contains("."); }
    private static int priority(String name) {
        String slot = name.substring(name.startsWith("fp.arm.")?7:name.indexOf('.') + 1);
        if (slot.startsWith("pre_parallel")) return 0;
        if (slot.equals("vehicle")) return 10;
        if (slot.startsWith("pre_main")) return 20;
        if (slot.equals("main")) return 30;
        if (name.startsWith("vehicle.")) {
            if (slot.equals("move")) return 31;
            if (slot.equals("origin")) return 32;
            if (slot.equals("ride")) return 33;
        }
        if (slot.startsWith("post_main")) return 40;
        if (slot.startsWith("pre_hold")) return 50;
        if (slot.equals("hold_offhand")) return 60;
        if (slot.equals("hold_mainhand")) return 61;
        if (slot.startsWith("post_hold")) return 70;
        if (slot.startsWith("pre_swing")) return 80;
        if (slot.equals("swing")) return 90;
        if (slot.startsWith("post_swing")) return 100;
        if (slot.startsWith("pre_use")) return 110;
        if (slot.equals("use")) return 120;
        if (slot.startsWith("post_use")) return 130;
        if (slot.equals("passenger")) return 140;
        if (slot.equals("cap")) return 150;
        if (slot.equals("gui_hover")) return 160;
        if (slot.equals("gui_focus")) return 170;
        if (slot.startsWith("parallel")) return 180;
        if (slot.startsWith("armor")) return 190;
        return 145;
    }
    public void reset() { reset(null); }
    void reset(Molang.Context context) {
        runtimes.values().forEach(runtime -> runtime.reset(context));
        if (globalSounds) stopScope("", context, true);
        globalSounds = false; firstTick = Double.NaN; evaluatingPlayback = null; captureAllowed = false; pendingCaptures = 0;
    }
    public Map<String, String> states() {
        Map<String, String> result = new LinkedHashMap<>();
        runtimes.forEach((name, runtime) -> result.put(name, runtime.state == null ? "ysm-builtin" : runtime.state.name));
        return Collections.unmodifiableMap(result);
    }
    void sample(double tick, List<BbModel.Layer> layers, Molang.Context context, BiConsumer<Frame, Molang.Context> visitor) {
        sample(tick, layers, context, visitor, frame -> { });
    }
    void sample(double tick, List<BbModel.Layer> layers, Molang.Context context, BiConsumer<Frame, Molang.Context> visitor,
                Consumer<Molang.Context> beforeNativeWeight) {
        this.tick = tick; if (Double.isNaN(firstTick)) firstTick = tick; Map<String, BbModel.Layer> builtins = new HashMap<>();
        for (BbModel.Layer layer : layers) {
            String slot = switch (layer.layer()) {
                case "posture", "movement", "main", "locomotion" -> family + ".main";
                case "manual" -> family + ".cap";
                case "interaction", "arms", "swing" -> family + ".swing";
                case "use" -> family + ".use";
                case "gui" -> family + ".gui_focus";
                default -> layer.layer();
            };
            // GUI clips own their separate slot, not the native entity posture.
            if (layer.layer().equals("gui") && (layer.animation().equals("gui_focus") || layer.animation().equals("gui_hover")))
                slot = family + "." + layer.animation();
            builtins.put(slot, layer);
        }
        Map<String,Object> queries = context.queryValues(); Map<String,Object> scope = context.contextValues();String effectScope=context.effectScope();
        try {
            for (Runtime runtime : runtimes.values()) runtime.process(tick, builtins, context, visitor, beforeNativeWeight, new HashSet<>());
        } finally { evaluating = null; evaluatingPlayback = null; captureAllowed = false; builtinEventPhase = false; context.restoreQueries(queries); context.restoreContextValues(scope);context.effectScope(effectScope); }
    }
    Object function(String name, List<Object> args, Molang.FunctionResolver fallback) {
        if (Set.of("ctrl.set_animation", "ctrl.set_beginning_transition_length", "ctrl.reset", "ctrl.indicate_reload").contains(name)
                && (!builtinEventPhase || evaluating == null)) return 0d;
        if (builtinEventPhase && evaluating != null) {
            switch (name) {
                case "ctrl.set_animation" -> {
                    if (args.size() == 1 || args.size() == 2) {
                        String animation = String.valueOf(args.get(0));
                        if (animation.isEmpty()) return null;
                        String requestedLoop = args.size() > 1 ? loop(args.get(1), null) : null;
                        if (animation.equals(evaluating.lastRequestedAnimation) && Objects.equals(requestedLoop, evaluating.lastRequestedLoop)) return null;
                        String pendingAnimation = evaluating.restartRequested ? evaluating.scriptAnimation : null;
                        String pendingLoop = evaluating.scriptLoop; double pendingStarted = evaluating.scriptStarted;
                        evaluating.cancelBuiltinRequest(false);
                        evaluating.lastRequestedAnimation = animation; evaluating.lastRequestedLoop = requestedLoop;
                        if (model.animations().contains(animation)) {
                            evaluating.scriptStarted = tick; evaluating.restartRequested = true;
                            evaluating.scriptAnimation = animation;
                            evaluating.scriptLoop = requestedLoop == null ? model.animationLoop(animation) : requestedLoop;
                        } else if (pendingAnimation != null) {
                            // Upstream lookup failure clears current animation, but does not discard an already pending one.
                            evaluating.scriptAnimation = pendingAnimation; evaluating.scriptLoop = pendingLoop;
                            evaluating.scriptStarted = pendingStarted; evaluating.restartRequested = true;
                        }
                    }
                    return null;
                }
                case "ctrl.set_beginning_transition_length" -> {
                    if (args.size() == 1) evaluating.scriptBlend = Blend.ticks(Math.max(0, Math.min(200, number(args.get(0)) * 20)));
                    return null;
                }
                case "ctrl.reset" -> { if (args.isEmpty()) evaluating.cancelBuiltinRequest(true); return null; }
                case "ctrl.indicate_reload" -> { if (args.isEmpty()) evaluating.lastRequestedAnimation = null; return null; }
            }
        }
        return fallback == null ? 0d : fallback.call(name, args);
    }
    void withoutBuiltinEvent(Runnable event) {
        boolean previous = builtinEventPhase; builtinEventPhase = false;
        try { event.run(); } finally { builtinEventPhase = previous; }
    }
    private static String loop(Object value, String fallback) {
        if (value instanceof Number number) return switch (number.intValue()) { case 10 -> "LOOP"; case 11 -> "ONCE"; case 12 -> "HOLD"; default -> fallback; };
        return switch (String.valueOf(value).toUpperCase(Locale.ROOT)) {
            case "TRUE", "LOOP" -> "LOOP"; case "FALSE", "ONCE", "PLAY_ONCE" -> "ONCE";
            case "HOLD", "HOLD_ON_LAST_FRAME" -> "HOLD"; default -> fallback;
        };
    }
    private final class Runtime {
        final String name, definitionName; final int depth;
        final String audioScope = "controller:" + UUID.randomUUID();
        final Definition definition;
        final Playback builtinPlayback = new Playback();
        State state; Runtime child;
        List<Slot> slots = new ArrayList<>();
        long revision; double changedAt; Blend blend = Blend.ticks(0);
        BbModel.Layer builtinLayer;
        String scriptAnimation, scriptLoop = "ONCE"; double scriptStarted;
        String lastRequestedAnimation, lastRequestedLoop;
        Blend scriptBlend; boolean restartRequested;
        boolean stopping, endingOnce, idleFlushPending, sounds;
        Molang.Context currentContext;
        double stoppedAt;
        BbModel.Layer completedLayer;
        Runtime(String name, String definitionName, int depth) {
            this.name = name; this.definitionName = definitionName; this.depth = depth;
            definition = model.controllerDefinitions().definitions.get(definitionName);
        }
        void reset(Molang.Context context) { if (child != null) child.reset(context); stopController(context); state = null; child = null; scriptBlend = null; currentContext = context; clearPlayback(true); }
        void stopController(Molang.Context context) {
            if (!sounds) return;
            sounds = false; stopScope(audioScope, context, false);
        }
        void clearPlayback(boolean clearRequest) {
            if (clearRequest) {
                slots.forEach(slot -> { discard(slot.playback, currentContext); if (slot.clock != null) slot.clock.cancel(); }); discard(builtinPlayback, currentContext);
            }
            slots.clear(); builtinLayer = null; scriptAnimation = null; restartRequested = false;
            stopping = endingOnce = idleFlushPending = false; completedLayer = null;
            if (clearRequest) { lastRequestedAnimation = null; lastRequestedLoop = null; }
            revision = ++sequence; changedAt = tick; blend = Blend.ticks(0);
        }
        void cancelBuiltinRequest(boolean clearRequest) {
            // Ctrl.Reset -> Predicate.clearAnimation -> Instance.cancelAnimation leaves its context intact.
            // A subsequent CONTINUE/PAUSE process reaches IDLE and flushes it. World reset uses clearPlayback.
            slots.forEach(slot -> { if (slot.clock != null) slot.clock.cancel(); });
            stopPlayback(builtinPlayback, currentContext);
            clearPlayback(false); idleFlushPending = true;
            if (clearRequest) { lastRequestedAnimation = null; lastRequestedLoop = null; }
        }
        void startStopping(double tick, boolean once) {
            if (stopping || slots.isEmpty()) return;
            stopping = true; endingOnce = once; stoppedAt = tick;
            if (!once) {
                lastRequestedAnimation = null; lastRequestedLoop = null;
                if (!restartRequested) scriptAnimation = null;
            }
            revision = ++sequence; changedAt = tick; blend = Blend.ticks(3);
            for (Slot slot : slots) {
                slot.endingTime = tick - slot.started < slot.delay ? 0 : slot.clock.stoppingTimeTicks(tick);
                slot.playback.animTime = slot.endingTime / 20; slot.clock.cancel();
                slot.ending = slot.finished = true;
                slot.endingAt = tick;
            }
        }
        void finishStopping(Molang.Context context) {
            flush(builtinPlayback, context); stopPlayback(builtinPlayback, context); builtinPlayback.resetEvents();
            completedLayer = endingOnce ? builtinLayer : null;
            slots.clear(); builtinLayer = null; stopping = endingOnce = idleFlushPending = false;
        }
        void scoped(Molang.Context context) { context.restoreContextValues(Map.of()); evaluating = this;context.effectScope(audioScope); }
        void save(Molang.Context context) { context.restoreContextValues(Map.of()); }
        void process(double tick, Map<String,BbModel.Layer> builtins, Molang.Context context,
                     BiConsumer<Frame,Molang.Context> visitor, Consumer<Molang.Context> beforeNativeWeight, Set<String> ancestors) {
            if (!ancestors.add(definitionName) || depth > 5) return;
            currentContext = context;
            scoped(context); context.query("query.anim_time", 0d);
            boolean allFinished = true, anyFinished = false, anyActive = false;
            for (Slot slot : slots) if (slot.active) { anyActive = true; allFinished &= slot.finished; anyFinished |= slot.finished; }
            context.query("query.all_animations_finished", allFinished ? 1d : 0d);
            context.query("query.any_animation_finished", !anyActive || anyFinished ? 1d : 0d);
            context.stringQuery("ysm.current_controller", name);
            context.stringQuery("ysm.current_state", state == null ? "" : state.name);
            if (definition != null) {
                Set<String> visited = new HashSet<>();
                if (state == null) {
                    enter(definition.states.get(definition.initial), tick, context); visited.add(state.name);
                    // Initial entries without clips may chain through empty transition states immediately.
                }
                if (slots.isEmpty() || !visited.contains(state.name)) {
                    while (true) {
                        boolean any = false, all = true, active = false;
                        for (Slot slot : slots) if (slot.active) { active = true; any |= slot.finished; all &= slot.finished; }
                        if (!active) any = true;
                        context.query("query.all_animations_finished", all ? 1d : 0d);
                        context.query("query.any_animation_finished", any ? 1d : 0d);
                        State next = null;
                        for (Transition transition : state.transitions) if (transition.condition.evaluate(context) != 0) {
                            next = definition.states.get(transition.target); break;
                        }
                        if (next == null || !visited.add(next.name)) break;
                        enter(next, tick, context);
                        if (!slots.isEmpty()) break;
                    }
                }
            }
            if (state != null && state.name.startsWith("ysm-entry-") && depth < 5) {
                String suffix = state.name.substring(10);
                String childName = definitionName + "." + suffix;
                if (!model.controllerDefinitions().definitions.containsKey(childName)) { child = null; save(context); return; }
                if (child == null || !child.definitionName.equals(childName)) child = new Runtime(name, childName, depth + 1);
                save(context); child.process(tick, builtins, context, visitor, beforeNativeWeight, ancestors); return;
            }
            boolean builtin = state == null || state.name.equals("ysm-builtin");
            if (builtin) {
                double decision = 5;
                List<Molang.Program> events = model.controllerEvents().getOrDefault(name, List.of());
                if (!events.isEmpty()) {
                    builtinEventPhase = true;
                    Object[] value = { 5d };
                    try { withPlayback(builtinPlayback, true, context, () -> value[0] = events.getFirst().evaluateValue(context));
                        decision = (int)number(value[0]); }
                    finally { builtinEventPhase = false; }
                }
                boolean scriptStop = decision == 3, scriptPause = decision == 4;
                context.query("ysm.pause." + name, scriptPause || context.get("ysm.pause." + name) != 0 ? 1d : 0d);
                BbModel.Layer desired;
                if (decision == 2 || scriptPause) {
                    if (restartRequested && idleFlushPending && scriptAnimation != null) scriptStarted = tick;
                    desired = scriptAnimation == null ? builtinLayer : new BbModel.Layer(name, scriptAnimation, (long)scriptStarted, 1, scriptLoop,
                            0, 4);
                } else if (scriptStop) {
                    // STOP while already IDLE must leave newly requested pending animation and its request key intact.
                    desired = builtinLayer;
                } else {
                    // A bypass result returns control to the native predicate for this frame.
                    scriptAnimation = null; restartRequested = false; lastRequestedAnimation = null; lastRequestedLoop = null; desired = builtins.get(name);
                    String slot = name.substring(family.length()+1);
                    String clip = slot.replace("pre_parallel_", "pre_parallel").replace("parallel_", "parallel");
                    if (desired == null && (slot.startsWith("pre_parallel_") || slot.startsWith("parallel_")) && model.animations().contains(clip))
                        desired = new BbModel.Layer(name, clip, (long)firstTick, 1, "LOOP", 0, 0);
                }
                boolean predicateStop = context.nativeYsm() && !scriptStop && !scriptPause && decision != 2 && desired == null;
                if (scriptStop || predicateStop) startStopping(tick, false);
                // PlayerAnimationController binds PlayerCustomAnimationPredicate (markDirty) to gui_hover.
                if (name.endsWith(".gui_hover") && desired == null) stopPlayback(builtinPlayback, context);
                boolean resumedFromStop = false;
                // A native predicate may request a fresh animation after STOP cleared the request key.
                if (stopping && !endingOnce && !scriptStop && !scriptPause && decision != 2 && !predicateStop) {
                    finishStopping(context); resumedFromStop = true;
                }
                if (stopping) {
                    if (tick - stoppedAt >= 3) {
                        boolean wasOnce = endingOnce;
                        finishStopping(context);
                        if (scriptStop || predicateStop || scriptPause || wasOnce || decision == 2) desired = null;
                    } else desired = builtinLayer;
                }
                if (completedLayer != null && Objects.equals(desired, completedLayer) && !restartRequested) desired = null;
                if (idleFlushPending && !scriptStop) { flush(builtinPlayback, context); idleFlushPending = false; }
                if (!scriptStop && (!Objects.equals(desired, builtinLayer) || restartRequested)) {
                    BbModel.Layer previous = builtinLayer;
                    flush(builtinPlayback, context); stopPlayback(builtinPlayback, context); builtinPlayback.resetEvents();
                    slots.forEach(slot -> { if (slot.clock != null) slot.clock.cancel(); });
                    builtinLayer = desired; slots.clear(); revision = ++sequence; changedAt = tick;
                    blend = desired == null ? scriptBlend != null ? scriptBlend : Blend.ticks(previous == null ? 4 : previous.outTicks())
                            : scriptBlend != null ? scriptBlend : Blend.ticks(desired.inTicks());
                    if (desired != null) {
                        completedLayer = null;
                        Slot slot = new Slot(new Animation(desired.animation(), null), desired.loop(),
                                resumedFromStop ? tick : desired.startedAtTick(), desired.speed(), builtinPlayback);
                        slot.delay = blend.duration(); prepare(slot, context); slots.add(slot);
                    }
                    restartRequested = false;
                }
            }
            List<Run> runs = new ArrayList<>();
            boolean suppressed = context.get("ysm.pause." + name) != 0;
            Playback conditionContext = null;
            for (Slot slot : slots) {
                if (context.nativeYsm() && conditionContext != null) {
                    withPlayback(conditionContext, false, context,
                            () -> slot.active = slot.animation.enabled == null || slot.animation.enabled.evaluate(context) != 0);
                } else {
                    context.query("query.anim_time", 0d);
                    slot.active = slot.animation.enabled == null || slot.animation.enabled.evaluate(context) != 0;
                }
                if (slot.ending) {
                    double weight = context.nativeYsm() && tick - slot.endingAt < 3
                            ? nativeWeight(slot, false, false, beforeNativeWeight, context) : 1;
                    runs.add(new Run(slot.animation.name, slot.loop, slot.eventTick, slot.endingTime, false, false, slot.playback,
                            slot.active && !suppressed && tick - slot.endingAt < 3, false, true, Math.min(1, (tick - slot.endingAt) / 3), slot.activation, weight));
                    if (context.nativeYsm()) conditionContext = slot.playback;
                    continue;
                }
                YsmAnimationClock.Step[] advanced = new YsmAnimationClock.Step[1];
                withPlayback(slot.playback, false, context, () -> advanced[0] = slot.clock.advance(tick, context));
                YsmAnimationClock.Step step = advanced[0]; double elapsed = step.timeTicks();
                double length = model.animationLengthTicks(slot.animation.name);
                boolean beginning = tick - slot.started < slot.delay || step.waiting();
                boolean clientSide = slot.active && !suppressed;
                slot.finished |= step.finished();
                if (step.crossedBoundary() && !slot.ending && slot.loop.equals("ONCE")) {
                    slot.playback.animTime = length / 20;
                    withPlayback(slot.playback, clientSide, context,
                            () -> model.ysmEvents(slot.animation.name, slot.playback, length, true, clientSide, context));
                    stopPlayback(slot.playback, context);
                    flush(slot.playback, context);
                    slot.ending = true; revision = ++sequence; changedAt = tick;
                    slot.endingTime = length;
                    slot.endingAt = tick;
                    slot.finished = true; blend = Blend.ticks(3);
                    if (builtin) { stopping = endingOnce = true; stoppedAt = tick; }
                } else if (step.crossedBoundary() && !slot.ending) {
                    if (slot.loop.equals("LOOP")) {
                        // Upstream flushes the prior context before remaining events and before wrapping.
                        flush(slot.playback, context); slot.playback.animTime = length / 20;
                        withPlayback(slot.playback, clientSide, context,
                                () -> model.ysmEvents(slot.animation.name, slot.playback, length, true, clientSide, context));
                        stopPlayback(slot.playback, context);
                    }
                }
                if (step.restarted()) slot.playback.resetEvents();
                else if (step.timeTicks() < step.beforeTimeTicks()) model.seekYsmEvents(slot.animation.name, slot.playback, elapsed);
                slot.playback.animTime = slot.ending ? length / 20 : elapsed / 20;
                double weight = context.nativeYsm()
                        ? nativeWeight(slot, !beginning && !slot.ending, clientSide, beforeNativeWeight, context) : 1;
                runs.add(new Run(slot.animation.name, slot.loop, slot.eventTick, elapsed,
                        slot.active && !slot.ending && !suppressed, !context.nativeYsm() && !beginning && !slot.ending, slot.playback,
                        slot.active && !suppressed, beginning && !slot.ending, slot.ending,
                        slot.ending ? 0 : blend.fraction(tick - slot.activatedAt), slot.activation, weight));
                slot.eventTick = elapsed;
                if (context.nativeYsm()) conditionContext = slot.playback;
            }
            visitor.accept(new Frame(name, revision, changedAt, blend, List.copyOf(runs), suppressed,
                    name.contains(".parallel_") && (!context.nativeYsm() || builtin && !stopping && tick - changedAt >= blend.duration()),
                    name.endsWith(".cap"), builtin), context);
            save(context);
        }
        /** Runtime processes each condition and Instance completely before the next condition; channel queues stay lazy. */
        double nativeWeight(Slot slot, boolean events, boolean clientSide, Consumer<Molang.Context> beforeWeight, Molang.Context context) {
            double[] weight = {1};
            withPlayback(slot.playback, false, context, () -> {
                if (events) withCapture(clientSide, () -> model.ysmEvents(slot.animation.name, slot.playback,
                        slot.playback.animTime * 20, false, clientSide, context));
                beforeWeight.accept(context);
                weight[0] = model.animationWeight(slot.animation.name, context);
            });
            return weight[0];
        }
        void enter(State next, double tick, Molang.Context context) {
            if (next == null) throw new IllegalArgumentException("Missing controller initial state");
            if (child != null) { child.exit(context); child = null; scoped(context); }
            // AnimationControllerRuntime.transitionToEntry clears PlaybackFlags audio before exit/entry scripts.
            stopController(context);
            if (state != null) for (Molang.Program program : state.exit) program.evaluateValue(context);
            List<Slot> previousSlots = slots;
            boolean previousBuiltin = state == null || state.name.equals("ysm-builtin");
            state = next; changedAt = tick; revision = ++sequence; blend = next.blend; slots = new ArrayList<>(); builtinLayer = null;
            stopping = endingOnce = idleFlushPending = false; completedLayer = null;
            context.stringQuery("ysm.current_state", next.name);
            for (Molang.Program program : next.entry) program.evaluateValue(context);
            for (String sound : next.sounds) if (context.functionResolver() != null)
                context.functionResolver().call("ysm.play_sound", context.nativeYsm()?List.of(0d,sound):List.of(sound));
            for (Slot slot : previousSlots) { flush(slot.playback, context); stopPlayback(slot.playback, context); slot.clock.cancel(); }
            if (previousBuiltin && previousSlots.isEmpty()) { flush(builtinPlayback, context); stopPlayback(builtinPlayback, context); }
            context.restoreContextValues(Map.of());
            if (!next.name.equals("ysm-builtin") && !next.name.startsWith("ysm-entry-"))
                for (Animation animation : next.animations) {
                    if (!model.animations().contains(animation.name)) continue;
                    Slot slot = new Slot(animation, model.animationLoop(animation.name), tick, 1, new Playback());
                    slot.delay = blend.duration(); prepare(slot, context); slots.add(slot);
                }
        }
        void exit(Molang.Context context) {
            if (child != null) child.exit(context);
            stopController(context);
            scoped(context); if (state != null) for (Molang.Program program : state.exit) program.evaluateValue(context);
            for (Slot slot : slots) { flush(slot.playback, context); stopPlayback(slot.playback, context); }
            if (slots.isEmpty()) { flush(builtinPlayback, context); stopPlayback(builtinPlayback, context); }
            save(context);
        }
        void prepare(Slot slot, Molang.Context context) {
            slot.activation = ++playbackSequence; slot.activatedAt = tick;
            slot.clock = model.animationClock(slot.animation.name, slot.loop, slot.speed, slot.started + slot.delay);
            withPlayback(slot.playback, false, context, () -> slot.clock.initialize(context));
        }
    }
    private static final class Slot {
        final Animation animation; final String loop; final double started, speed;
        final Playback playback;
        YsmAnimationClock clock;
        double eventTick = -1e-5, delay, endingTime, endingAt, activatedAt; long activation; boolean active = true, finished, ending;
        Slot(Animation animation, String loop, double started, double speed, Playback playback) {
            this.animation = animation; this.loop = loop; this.started = started; this.speed = speed; this.playback = playback;
        }
    }

    public static Definitions parse(JsonObject json) {
        return parse(json, false);
    }
    public static Definitions parse(JsonObject json, boolean nativeYsm) {
        if (json.size() > 64) throw new IllegalArgumentException("YSM controller count exceeded");
        Map<String,Definition> definitions = new LinkedHashMap<>(); int total = 0;
        for (var entry : json.entrySet()) {
            String name = checkedName(entry.getKey());
            if (name.startsWith("#")) continue;
            if (name.startsWith("controller.animation.")) name = name.substring(21);
            JsonObject value = object(entry.getValue()); JsonObject states = object(value.get("states"));
            if (states.size() == 0 || states.size() > 128 || (total += states.size()) > 1024)
                throw new IllegalArgumentException("YSM state count exceeded");
            Map<String,State> parsed = new LinkedHashMap<>();
            for (var stateEntry : states.entrySet()) {
                String stateName = checkedName(stateEntry.getKey()); JsonObject node = object(stateEntry.getValue());
                List<Animation> animations = new ArrayList<>();
                for (JsonElement animation : array(node, "animations", 32)) {
                    if (animation.isJsonPrimitive()) animations.add(new Animation(checkedName(animation.getAsString()), null));
                    else for (var clip : object(animation).entrySet())
                        animations.add(new Animation(checkedName(clip.getKey()), animationCondition(clip.getValue(), nativeYsm)));
                    if (animations.size() > 32) throw new IllegalArgumentException("YSM state clip count exceeded");
                }
                List<Transition> transitions = new ArrayList<>();
                for (JsonElement transition : array(node, "transitions", 32)) {
                    for (var item : object(transition).entrySet()) transitions.add(new Transition(checkedName(item.getKey()), program(item.getValue(), nativeYsm)));
                    if (transitions.size() > 32) throw new IllegalArgumentException("YSM transition count exceeded");
                }
                List<String> sounds = new ArrayList<>();
                for (JsonElement sound : array(node, "sound_effects", 16)) {
                    String effect = sound.isJsonObject() ? string(object(sound), "effect", "") : sound.getAsString();
                    if (!effect.isEmpty()) sounds.add(checkedName(effect));
                }
                parsed.put(stateName, new State(stateName, List.copyOf(animations), List.copyOf(transitions),
                        scripts(node.get("on_entry"), nativeYsm), scripts(node.get("on_exit"), nativeYsm), blend(node.get("blend_transition")), List.copyOf(sounds)));
            }
            String initial = checkedName(string(value, "initial_state", "default"));
            if (!parsed.containsKey(initial)) throw new IllegalArgumentException("Unknown initial YSM state");
            for (State state : parsed.values()) for (Transition transition : state.transitions)
                if (!parsed.containsKey(transition.target)) throw new IllegalArgumentException("Unknown YSM transition target");
            if (definitions.put(name, new Definition(initial, Collections.unmodifiableMap(parsed))) != null)
                throw new IllegalArgumentException("Duplicate normalized YSM controller");
        }
        return new Definitions(definitions);
    }
    private static Blend blend(JsonElement value) {
        if (value == null) return Blend.ticks(0);
        if (value.isJsonPrimitive()) return Blend.ticks(bound(literal(value.getAsString()), 0, 10) * 20);
        TreeMap<Double,Double> sorted = new TreeMap<>();
        for (var entry : object(value).entrySet()) {
            double time = bound(literal(entry.getKey()), 0, 10) * 20;
            if (sorted.put(time, 1 - bound(literal(entry.getValue().getAsString()), 0, 1)) != null)
                throw new IllegalArgumentException("Duplicate blend time");
            if (sorted.size() > 64) throw new IllegalArgumentException("YSM blend key count exceeded");
        }
        double[] times = new double[sorted.size()], values = new double[sorted.size()]; int i = 0;
        for (var entry : sorted.entrySet()) { times[i] = entry.getKey(); values[i++] = entry.getValue(); }
        return new Blend(times, values);
    }
    private static List<Molang.Program> scripts(JsonElement value, boolean nativeYsm) {
        if (value == null) return List.of();
        if (value.isJsonPrimitive()) return List.of(program(value, nativeYsm));
        if (!value.isJsonArray() || value.getAsJsonArray().size() > 32) throw new IllegalArgumentException("YSM script count exceeded");
        List<Molang.Program> scripts = new ArrayList<>(); for (JsonElement script : value.getAsJsonArray()) scripts.add(program(script, nativeYsm));
        return List.copyOf(scripts);
    }
    private static Molang.Program program(JsonElement value, boolean nativeYsm) {
        if (value == null || !value.isJsonPrimitive()) throw new IllegalArgumentException("Expected YSM expression");
        return nativeYsm ? Molang.compileNativeYsm(value.getAsString()) : Molang.compile(value.getAsString());
    }
    /** Sparkle AnimationMapper.buildControllers leaves empty conditions absent; its parse helper returns ZERO on failure. */
    private static Molang.Program animationCondition(JsonElement value, boolean nativeYsm) {
        if (!nativeYsm) return program(value, false);
        if (value != null && value.isJsonPrimitive() && value.getAsString().isEmpty()) return null;
        try { return program(value, true); }
        catch (IllegalArgumentException invalidExpression) { return Molang.compileNativeYsm("0"); }
    }
    private static JsonObject object(JsonElement value) {
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("Expected YSM controller object");
        return value.getAsJsonObject();
    }
    private static JsonArray array(JsonObject node, String key, int limit) {
        JsonElement value = node.get(key); if (value == null) return new JsonArray();
        if (!value.isJsonArray() || value.getAsJsonArray().size() > limit) throw new IllegalArgumentException("YSM " + key + " count/type");
        return value.getAsJsonArray();
    }
    private static String string(JsonObject node, String key, String fallback) { return node.has(key) ? node.get(key).getAsString() : fallback; }
    private static String checkedName(String name) {
        if (name.isBlank() || name.length() > 128 || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid YSM name");
        return name;
    }
    private static double literal(String value) {
        try { return Double.parseDouble(value); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid YSM number"); }
    }
    private static double number(Object value) {
        try { return value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException invalid) { return 0; }
    }
    private static double bound(double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("YSM numeric range");
        return value;
    }
}
