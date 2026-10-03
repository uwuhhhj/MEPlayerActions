package com.simmc.meplayeractions.client.model;

import com.google.gson.*;
import com.simmc.meplayeractions.expression.Molang;
import java.util.*;
import java.util.function.BiConsumer;

/** Bounded, per-instance interpretation of OpenYSM controller definitions.
 * Semantics audited against OpenYSM-Updated 0306e1f (MIT); this is an independent implementation.
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
    record Run(String animation, String loop, double before, double elapsed, boolean active, boolean events) { }
    record Frame(String name, long revision, double changedAt, Blend blend, List<Run> runs,
                 boolean suppressed, boolean deprecated, boolean cap) { }

    private final BbModel model;
    private final Map<String, Runtime> runtimes = new LinkedHashMap<>();
    private final String family;
    private Runtime evaluating;
    private boolean builtinEventPhase;
    private double tick;
    private long sequence;
    private double firstTick = Double.NaN;

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
    public void reset() { runtimes.values().forEach(Runtime::reset); firstTick = Double.NaN; }
    public Map<String, String> states() {
        Map<String, String> result = new LinkedHashMap<>();
        runtimes.forEach((name, runtime) -> result.put(name, runtime.state == null ? "ysm-builtin" : runtime.state.name));
        return Collections.unmodifiableMap(result);
    }
    void sample(double tick, List<BbModel.Layer> layers, Molang.Context context, BiConsumer<Frame, Molang.Context> visitor) {
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
        Map<String,Object> queries = context.queryValues(); Map<String,Object> scope = context.contextValues();
        try {
            for (Runtime runtime : runtimes.values()) runtime.process(tick, builtins, context, visitor, new HashSet<>());
        } finally { evaluating = null; builtinEventPhase = false; context.restoreQueries(queries); context.restoreContextValues(scope); }
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
                        evaluating.clearPlayback(false);
                        evaluating.lastRequestedAnimation = animation; evaluating.lastRequestedLoop = requestedLoop;
                        if (model.animations().contains(animation)) {
                            evaluating.scriptStarted = tick; evaluating.restartRequested = true;
                            evaluating.scriptAnimation = animation;
                            evaluating.scriptLoop = requestedLoop == null ? model.animationLoop(animation) : requestedLoop;
                        }
                    }
                    return null;
                }
                case "ctrl.set_beginning_transition_length" -> {
                    if (args.size() == 1) evaluating.scriptBlend = Blend.ticks(Math.max(0, Math.min(200, number(args.get(0)) * 20)));
                    return null;
                }
                case "ctrl.reset" -> { if (args.isEmpty()) evaluating.clearPlayback(true); return null; }
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
        final Definition definition;
        final Map<String,Object> scope = new HashMap<>();
        State state; Runtime child;
        List<Slot> slots = new ArrayList<>();
        long revision; double changedAt; Blend blend = Blend.ticks(0);
        BbModel.Layer builtinLayer;
        String scriptAnimation, scriptLoop = "ONCE"; double scriptStarted;
        String lastRequestedAnimation, lastRequestedLoop;
        Blend scriptBlend; boolean restartRequested;
        Runtime(String name, String definitionName, int depth) {
            this.name = name; this.definitionName = definitionName; this.depth = depth;
            definition = model.controllerDefinitions().definitions.get(definitionName);
        }
        void reset() { state = null; child = null; scope.clear(); scriptBlend = null; clearPlayback(true); }
        void clearPlayback(boolean clearRequest) {
            slots.clear(); builtinLayer = null; scriptAnimation = null; restartRequested = false;
            if (clearRequest) { lastRequestedAnimation = null; lastRequestedLoop = null; }
            revision = ++sequence; changedAt = tick; blend = Blend.ticks(0);
        }
        void scoped(Molang.Context context) { context.restoreContextValues(scope); evaluating = this; }
        void save(Molang.Context context) { scope.clear(); scope.putAll(context.contextValues()); }
        void process(double tick, Map<String,BbModel.Layer> builtins, Molang.Context context,
                     BiConsumer<Frame,Molang.Context> visitor, Set<String> ancestors) {
            if (!ancestors.add(definitionName) || depth > 5) return;
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
                save(context); child.process(tick, builtins, context, visitor, ancestors); return;
            }
            boolean builtin = state == null || state.name.equals("ysm-builtin");
            if (builtin) {
                double decision = 5;
                List<Molang.Program> events = model.controllerEvents().getOrDefault(name, List.of());
                if (!events.isEmpty()) {
                    builtinEventPhase = true;
                    try { decision = (int)number(events.getFirst().evaluateValue(context)); }
                    finally { builtinEventPhase = false; }
                }
                boolean scriptStop = decision == 3, scriptPause = decision == 4;
                context.query("ysm.pause." + name, scriptPause || context.get("ysm.pause." + name) != 0 ? 1d : 0d);
                BbModel.Layer desired;
                if (decision == 2 || scriptPause) {
                    desired = scriptAnimation == null ? builtinLayer : new BbModel.Layer(name, scriptAnimation, (long)scriptStarted, 1, scriptLoop,
                            0, 4);
                } else if (scriptStop) {
                    desired = null; scriptAnimation = null; lastRequestedAnimation = null;
                } else {
                    // A bypass result returns control to the native predicate for this frame.
                    scriptAnimation = null; restartRequested = false; lastRequestedAnimation = null; lastRequestedLoop = null; desired = builtins.get(name);
                    String slot = name.substring(family.length()+1);
                    String clip = slot.replace("pre_parallel_", "pre_parallel").replace("parallel_", "parallel");
                    if (desired == null && (slot.startsWith("pre_parallel_") || slot.startsWith("parallel_")) && model.animations().contains(clip))
                        desired = new BbModel.Layer(name, clip, (long)firstTick, 1, "LOOP", 0, 0);
                }
                if (!Objects.equals(desired, builtinLayer) || restartRequested) {
                    BbModel.Layer previous = builtinLayer;
                    builtinLayer = desired; slots.clear(); revision = ++sequence; changedAt = tick;
                    blend = desired == null ? scriptBlend != null ? scriptBlend : Blend.ticks(previous == null ? 4 : previous.outTicks())
                            : scriptBlend != null ? scriptBlend : Blend.ticks(desired.inTicks());
                    if (desired != null) {
                        Slot slot = new Slot(new Animation(desired.animation(), null), desired.loop(), desired.startedAtTick(), desired.speed());
                        slot.delay = blend.duration(); slots.add(slot);
                    }
                    restartRequested = false;
                }
            }
            List<Run> runs = new ArrayList<>();
            boolean suppressed = context.get("ysm.pause." + name) != 0;
            for (Slot slot : slots) {
                context.query("query.anim_time", 0d);
                slot.active = slot.animation.enabled == null || slot.animation.enabled.evaluate(context) != 0;
                double elapsed = Math.max(0, (tick - slot.started - slot.delay) * slot.speed);
                double length = model.animationLengthTicks(slot.animation.name);
                slot.finished |= elapsed >= length;
                boolean ended = slot.loop.equals("ONCE") && elapsed > length;
                if (ended && !slot.ending) {
                    slot.ending = true; revision = ++sequence; changedAt = tick;
                    blend = Blend.ticks(builtinLayer == null ? state == null ? 4 : state.blend.duration() : builtinLayer.outTicks());
                }
                runs.add(new Run(slot.animation.name, slot.loop, slot.eventTick, elapsed, slot.active && !ended && !suppressed, slot.active && !suppressed));
                slot.eventTick = elapsed;
            }
            visitor.accept(new Frame(name, revision, changedAt, blend, List.copyOf(runs), suppressed,
                    name.contains(".parallel_"), name.endsWith(".cap")), context);
            save(context);
        }
        void enter(State next, double tick, Molang.Context context) {
            if (next == null) throw new IllegalArgumentException("Missing controller initial state");
            if (child != null) { child.exit(context); child = null; scoped(context); }
            if (state != null) for (Molang.Program program : state.exit) program.evaluateValue(context);
            state = next; changedAt = tick; revision = ++sequence; blend = next.blend; slots.clear(); builtinLayer = null;
            context.stringQuery("ysm.current_state", next.name);
            for (Molang.Program program : next.entry) program.evaluateValue(context);
            for (String sound : next.sounds) if (context.functionResolver() != null)
                context.functionResolver().call("ysm.play_sound", List.of(sound));
            if (!next.name.equals("ysm-builtin") && !next.name.startsWith("ysm-entry-"))
                for (Animation animation : next.animations) {
                    if (!model.animations().contains(animation.name)) continue;
                    Slot slot = new Slot(animation, model.animationLoop(animation.name), tick, 1); slot.delay = blend.duration(); slots.add(slot);
                }
        }
        void exit(Molang.Context context) {
            if (child != null) child.exit(context);
            scoped(context); if (state != null) for (Molang.Program program : state.exit) program.evaluateValue(context); save(context);
        }
    }
    private static final class Slot {
        final Animation animation; final String loop; final double started, speed;
        double eventTick = -1e-5, delay; boolean active = true, finished, ending;
        Slot(Animation animation, String loop, double started, double speed) {
            this.animation = animation; this.loop = loop; this.started = started; this.speed = speed;
        }
    }

    public static Definitions parse(JsonObject json) {
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
                        animations.add(new Animation(checkedName(clip.getKey()), program(clip.getValue())));
                    if (animations.size() > 32) throw new IllegalArgumentException("YSM state clip count exceeded");
                }
                List<Transition> transitions = new ArrayList<>();
                for (JsonElement transition : array(node, "transitions", 32)) {
                    for (var item : object(transition).entrySet()) transitions.add(new Transition(checkedName(item.getKey()), program(item.getValue())));
                    if (transitions.size() > 32) throw new IllegalArgumentException("YSM transition count exceeded");
                }
                List<String> sounds = new ArrayList<>();
                for (JsonElement sound : array(node, "sound_effects", 16)) {
                    String effect = sound.isJsonObject() ? string(object(sound), "effect", "") : sound.getAsString();
                    if (!effect.isEmpty()) sounds.add(checkedName(effect));
                }
                parsed.put(stateName, new State(stateName, List.copyOf(animations), List.copyOf(transitions),
                        scripts(node.get("on_entry")), scripts(node.get("on_exit")), blend(node.get("blend_transition")), List.copyOf(sounds)));
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
    private static List<Molang.Program> scripts(JsonElement value) {
        if (value == null) return List.of();
        if (value.isJsonPrimitive()) return List.of(program(value));
        if (!value.isJsonArray() || value.getAsJsonArray().size() > 32) throw new IllegalArgumentException("YSM script count exceeded");
        List<Molang.Program> scripts = new ArrayList<>(); for (JsonElement script : value.getAsJsonArray()) scripts.add(program(script));
        return List.copyOf(scripts);
    }
    private static Molang.Program program(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) throw new IllegalArgumentException("Expected YSM expression");
        return Molang.compile(value.getAsString());
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
