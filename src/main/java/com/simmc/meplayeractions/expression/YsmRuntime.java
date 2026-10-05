package com.simmc.meplayeractions.expression;

import com.google.gson.*;
import java.util.*;
import java.util.function.Function;

/** Instance-owned script state and the original model's fixed-step physics controller. */
public final class YsmRuntime {
    private final Molang.Context context;
    private final List<Molang.Program> initialization, physics;
    private final Map<String,List<Molang.Program>> helpers;
    private record Event(double time,Molang.Program script) { }
    private final Map<String,List<Event>> events;
    private final Map<String,Double> eventTimes = new HashMap<>();
    private boolean initialized;
    private double last = Double.NaN, remainder;
    private Map<String,Double> inputs = Map.of();
    public YsmRuntime(JsonObject model, long seed) {
        this(Template.compile(model), seed);
    }
    public YsmRuntime(Template template, long seed) {
        Objects.requireNonNull(template);
        context = new Molang.Context(seed);
        initialization = template.initialization; physics = template.physics;
        helpers = template.helpers; events = template.events;
    }

    /** Programs hold immutable syntax nodes; all evaluation state lives in the supplied Context. */
    public static final class Template {
        private final List<Molang.Program> initialization, physics;
        private final Map<String,List<Molang.Program>> helpers;
        private final Map<String,List<Event>> events;
        private final Map<String,Molang.Program> expressions;

        private Template(List<Molang.Program> initialization, List<Molang.Program> physics,
                         Map<String,List<Molang.Program>> helpers, Map<String,List<Event>> events,
                         Map<String,Molang.Program> expressions) {
            this.initialization = List.copyOf(initialization); this.physics = List.copyOf(physics);
            this.helpers = Map.copyOf(helpers); this.events = Map.copyOf(events);
            this.expressions = Map.copyOf(expressions);
        }

        public static Template compile(JsonObject model) { return compile(model, Molang::compile); }
        static Template compile(JsonObject model, Function<String,Molang.Program> compiler) {
            List<Molang.Program> initialization = new ArrayList<>(), physics = new ArrayList<>();
            Map<String,List<Molang.Program>> helpers = new HashMap<>();
            Map<String,List<Event>> events = new HashMap<>();
            Map<String,Molang.Program> expressions = new HashMap<>();
            Function<String,Molang.Program> program = text -> expressions.computeIfAbsent(text, compiler);
            for (JsonElement element : model.getAsJsonArray("animations")) {
                JsonObject animation = element.getAsJsonObject(); String name = animation.get("name").getAsString();
                List<Molang.Program> clipHelpers = new ArrayList<>(); List<Event> clipEvents = new ArrayList<>();
                for (var entry : animation.getAsJsonObject("animators").entrySet()) {
                    JsonObject animator = entry.getValue().getAsJsonObject();
                    boolean bone = animator.has("type") && animator.get("type").getAsString().equals("bone");
                    for (JsonElement key : animator.getAsJsonArray("keyframes")) {
                        JsonObject frame = key.getAsJsonObject();
                        for (JsonElement data : frame.getAsJsonArray("data_points")) {
                            JsonObject point = data.getAsJsonObject();
                            if (point.has("script")) {
                                Molang.Program script = program.apply(point.get("script").getAsString());
                                if (name.equals("parallel1")) initialization.add(script);
                                if (name.equals("parallel2")) physics.add(script);
                                if (!name.equals("parallel1") && !name.equals("parallel2"))
                                    clipEvents.add(new Event(frame.get("time").getAsDouble(), script));
                            } else if (animator.get("name").getAsString().toLowerCase(Locale.ROOT).startsWith("molang")) {
                                for (String axis : List.of("x","y","z")) if (point.has(axis))
                                    clipHelpers.add(program.apply(point.get(axis).getAsString()));
                            }
                            if (bone) for (String axis : List.of("x","y","z")) if (point.has(axis)) {
                                String text = point.get(axis).getAsString();
                                try { Double.parseDouble(text); }
                                catch (NumberFormatException expression) { program.apply(text); }
                            }
                        }
                    }
                }
                helpers.put(name, List.copyOf(clipHelpers)); events.put(name, List.copyOf(clipEvents));
            }
            return new Template(initialization, physics, helpers, events, expressions);
        }

        /** A precompiled axis expression from this model; no new compilation on instance creation. */
        public Molang.Program expression(String text) {
            Molang.Program program = expressions.get(text);
            if (program == null) throw new IllegalArgumentException("Expression is not in the model template");
            return program;
        }
    }
    public void update(double tick, Map<String,Double> queries, Map<String,Double> activeAnimations) {
        if (!Double.isNaN(last) && (tick < last || tick-last > 100)) reset();
        Map<String,Double> values = new HashMap<>(queries); values.put("query.life_time",tick/20);
        activeAnimations.keySet().forEach(name->values.put("ctrl."+name,1d));
        context.frame(values); inputs = Map.copyOf(values);
        if (!initialized) { initialization.forEach(p->p.evaluate(context)); initialized=true; }
        for (var active : activeAnimations.entrySet()) {
            context.query("query.anim_time",active.getValue());
            for (Molang.Program helper : helpers.getOrDefault(active.getKey(),List.of())) helper.evaluate(context);
            double before=eventTimes.getOrDefault(active.getKey(),-1e-6),after=active.getValue();
            if(after<before)before=-1e-6;
            for(Event event:events.getOrDefault(active.getKey(),List.of()))if(event.time>before&&event.time<=after)event.script.evaluate(context);
            eventTimes.put(active.getKey(),after);
        }
        eventTimes.keySet().retainAll(activeAnimations.keySet());
        remainder += Double.isNaN(last)?0:Math.max(0,Math.min(.25,(tick-last)/20));
        while (remainder+1e-9>=.01) { physics.forEach(p->p.evaluate(context)); remainder-=.01; }
        last=tick;
    }
    public Snapshot snapshot() { return new Snapshot(inputs,context.variables()); }
    public void reset() { context.clear(); initialized=false; remainder=0; last=Double.NaN; eventTimes.clear(); }
    public record Snapshot(Map<String,Double> inputs, Map<String,Double> variables) {
        public Molang.Context context(double animationTime) {
            Molang.Context result=new Molang.Context(); result.frame(inputs);
            variables.forEach(result::set);result.query("query.anim_time",animationTime); return result;
        }
    }
}
