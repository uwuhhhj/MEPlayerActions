package com.simmc.meplayeractions.expression;

import java.util.*;

/** Bounded Molang evaluator. Whitelisted bindings never execute host-language code. */
public final class Molang {
    private Molang() { }
    private interface Node { Object eval(Context context); }
    @FunctionalInterface public interface FunctionResolver {
        Object call(String name, List<Object> arguments);
    }
    public record VectorValue(double x,double y,double z) {
        public VectorValue { x=finite(x);y=finite(y);z=finite(z); }
    }
    /** Author structs, including live bone vectors. These are bindings, never arbitrary host objects. */
    public interface StructValue {
        Object getProperty(String name);
        void putProperty(String name, Object value);
        StructValue copy();
    }
    /** A query such as projectile_owner can explicitly expose a bounded child query context. */
    @FunctionalInterface public interface ContextValue { Context context(); }
    public static final class MapStruct implements StructValue {
        private final Map<String,Object> properties = new HashMap<>();
        public Object getProperty(String name) { return properties.get(name); }
        public void putProperty(String name,Object value) {
            if (value instanceof StructValue) return; // Upstream forbids nested author structs.
            if (!properties.containsKey(name) && properties.size() >= 256) throw invalid("Struct property limit exceeded");
            properties.put(name,nativeChecked(value));
        }
        public StructValue copy() { MapStruct copy = new MapStruct(); copy.properties.putAll(properties); return copy; }
        public String toString() { return "struct" + properties; }
    }
    /** Source RoamingStruct is a float-only, flat author state channel, separate from physics storage. */
    private static final class RoamingValue implements StructValue {
        private final Map<String,Float> values=new LinkedHashMap<>();
        private final Set<String> names=new LinkedHashSet<>();
        private final Map<String,Double> changed=new LinkedHashMap<>();
        public Object getProperty(String name) { return name.contains(".")?null:(double)values.getOrDefault(name,0f); }
        public void putProperty(String name,Object value) {
            if(name.contains("."))return; // A numeric roaming property cannot contain another struct.
            if(!values.containsKey(name)&&values.size()>=256)throw invalid("Struct property limit exceeded");
            float next=(float)nativeNumber(value),previous=values.getOrDefault(name,0f);
            values.put(name,next);
            if(next==previous)return;
            names.add(name);
            // Preserve the source cumulative-name budget, including its local-only overflow behavior.
            if(names.size()>Context.MAX_ROAMING_VARIABLES)return;
            changed.put(name,(double)next);
        }
        private void seed(String name,double value) {
            if(name.isBlank()||name.contains(".")||name.length()>Context.MAX_ROAMING_VARIABLE_NAME_LENGTH)return;
            float next=(float)value;if(!Float.isFinite(next))throw invalid("Roaming variable value");
            if(!values.containsKey(name)&&values.size()>=256)throw invalid("Struct property limit exceeded");
            values.put(name,next);names.add(name);changed.remove(name);
        }
        private Map<String,Double> consume() {
            Map<String,Double> result=new LinkedHashMap<>();
            changed.forEach((name,value)->{if(!name.isBlank()&&name.length()<=Context.MAX_ROAMING_VARIABLE_NAME_LENGTH&&Double.isFinite(value))result.put("variable.roaming."+name,value);});
            changed.clear();return Collections.unmodifiableMap(result);
        }
        public StructValue copy() {
            MapStruct copy=new MapStruct();values.forEach((name,value)->copy.putProperty(name,(double)value));return copy;
        }
    }
    public record SequenceValue(List<Object> elements) {
        public SequenceValue {
            if(elements.size()>256)throw invalid("Sequence limit exceeded");
            elements=elements.stream().map(Molang::nativeChecked).toList();
            if(elements.stream().anyMatch(SequenceValue.class::isInstance))throw invalid("Nested sequence limit");
        }
    }
    /** Per-name degradation count; actualFallback is the most recently evaluated fallback. */
    public record QueryDiagnostic(String name, String reason, long count, Object actualFallback) { }
    private static final Set<String> MODEL_FUNCTIONS = Set.of(
            "query.position", "query.position_delta", "query.rotation_to_camera",
            "query.is_item_name_any", "query.equipped_item_all_tags", "query.equipped_item_any_tag",
            "query.biome_has_all_tags", "query.biome_has_any_tag", "query.relative_block_has_all_tags",
            "query.relative_block_has_any_tag", "query.max_durability", "query.remaining_durability",
            "query.debug_output", "ysm.equipped_enchantment_level", "ysm.effect_level",
            "ysm.relative_block_name", "ysm.relative_block_name_any", "ysm.bone_rot", "ysm.bone_pos",
            "ysm.bone_scale", "ysm.bone_pivot_abs", "ysm.first_order", "ysm.second_order",
            "ysm.perlin_noise", "ysm.keyboard", "ysm.mouse", "ysm.play_sound", "ysm.stop_sound",
            "ysm.stop_all_sounds", "ysm.particle", "ysm.abs_particle", "ysm.mod_version",
            "ctrl.hold", "ctrl.swing", "ctrl.use", "ctrl.armor", "ctrl.ride", "ctrl.set_animation",
            "ctrl.set_beginning_transition_length", "ctrl.reset", "ctrl.indicate_reload", "ysm.has_any_curios", "ysm.sync", "ysm.defer",
            "ysm.get_equipped_item_name", "ysm.get_root_locator_offset", "ysm.dump_equipped_item", "ysm.dump_relative_block");
    private static final Map<String,Double> CONSTANTS=Map.of(
            "math.pi",Math.PI,"math.e",Math.E,
            "ctrl.state_continue",2d,"ctrl.state_stop",3d,"ctrl.state_pause",4d,"ctrl.state_bypass",5d,
            "ctrl.loop",10d,"ctrl.play_once",11d,"ctrl.hold_on_last_frame",12d);
    private static boolean modelFunction(String name) {
        return MODEL_FUNCTIONS.contains(name) || name.length()<=131
                && name.matches("fn\\.[\\p{L}\\p{N}_]+(?:\\.[\\p{L}\\p{N}_]+)*");
    }
    public static final class Program {
        private final Node root;
        private final Set<String> references;
        private Program(Node root, Set<String> references) { this.root = root; this.references = Set.copyOf(references); }
        public double evaluate(Context context) { return context.numeric(evaluateValue(context)); }
        public Object evaluateValue(Context context) {
            try { return context.check(root.eval(Objects.requireNonNull(context))); }
            catch (Return returned) { return context.check(returned.value); }
        }
        public Set<String> references() { return references; }
    }
    public static final class Context {
        public static final int MAX_ROAMING_VARIABLES=64,MAX_ROAMING_VARIABLE_NAME_LENGTH=32;
        private static final int MAX_QUERY_DIAGNOSTICS = 128, MAX_DIAGNOSTIC_NAME_LENGTH = 256;
        private Map<String, Object> variables = new HashMap<>();
        private final Map<String, Object> queries = new HashMap<>();
        private Map<String, Object> controllerVariables = new HashMap<>();
        private final Map<String, QueryDiagnostic> queryDiagnostics = new LinkedHashMap<>();
        private Random random;
        private FunctionResolver resolver;
        private boolean diagnosticsEnabled;
        private boolean nativeYsm;
        private double currentValue;
        private String physicsScope;
        private String effectScope;
        private java.util.function.Consumer<Context> childScope = context -> { };
        private RoamingValue roaming;
        private int budget = 250_000;
        public Context() { this(0); }
        public Context(long seed) { random = new Random(seed); }
        public void frame(Map<String, Double> values) {
            queries.clear(); values.forEach((key, value) -> queries.put(normalize(key), finite(value)));
            variables.keySet().removeIf(key -> key.startsWith("temp.")); budget = 250_000;
        }
        public void clear() { variables.clear(); queries.clear(); controllerVariables.clear(); queryDiagnostics.clear(); roaming=null;currentValue = 0; physicsScope = effectScope = null; budget = 250_000; }
        /** Opt in only for local YSM playback; default client/server contexts retain their old null semantics. */
        public void enableDiagnostics() { diagnosticsEnabled = true; }
        /** Only native local YSM opts into the mature runtime's nullable and structured values. */
        public void enableNativeYsm() { nativeYsm = true; }
        public boolean nativeYsm() { return nativeYsm; }
        /** Install the source roaming struct once per model instance. Ordinary BB contexts stay unchanged. */
        public void enableNativeRoaming() {
            if(!nativeYsm)throw invalid("Native roaming requires a YSM context");
            if(roaming==null) {
                roaming=new RoamingValue();Object previous=variables.get("variable.roaming");
                if(previous instanceof MapStruct struct)struct.properties.forEach((name,value)->roaming.seed(name,nativeNumber(value)));
            }
            variables.put("variable.roaming",roaming);
        }
        /** External persisted/server values initialize or deliberately replace a baseline without echoing dirt. */
        public void seedRoamingValues(Map<String,Double> values) {
            if(values.size()>MAX_ROAMING_VARIABLES)throw invalid("Roaming variable count");
            enableNativeRoaming();values.forEach((name,value)-> {
                String key=normalize(name);
                if(!key.startsWith("variable.roaming.")||value==null||!Double.isFinite(value))throw invalid("Roaming variable binding");
                roaming.seed(key.substring("variable.roaming.".length()),value);
            });
        }
        /** Consumed only by the locally owned world body; previews and observers never persist these deltas. */
        public Map<String,Double> consumeRoamingChanges() {
            return roaming==null?Map.of():roaming.consume();
        }
        public void currentValue(double value) { currentValue = finite(value); }
        public double currentValue() { return currentValue; }
        public void physicsScope(String scope) { physicsScope = scope; }
        public String physicsScope() { return physicsScope; }
        /** Internal audio manager identity, never an author-visible query or variable. */
        public void effectScope(String scope) { effectScope = scope; }
        public String effectScope() { return effectScope; }
        /** Rebind model functions while a typed child still owns its entity query bindings. */
        public void childScope(java.util.function.Consumer<Context> configure) { childScope = Objects.requireNonNull(configure); }
        public List<QueryDiagnostic> diagnostics() { return List.copyOf(queryDiagnostics.values()); }
        public void functions(FunctionResolver resolver) { this.resolver = resolver; }
        public FunctionResolver functionResolver() { return resolver; }
        public double get(String name) { return numeric(value(name)); }
        public Object value(String name) {
            spend(); name = normalize(name);
            if (name.equals("this")) return currentValue;
            if (CONSTANTS.containsKey(name)) return CONSTANTS.get(name);
            Object absent = nativeYsm ? null : 0d;
            if (name.startsWith("context.")) return lookup(controllerVariables,name,lookup(queries,name,absent,nativeYsm),nativeYsm);
            if (writable(name)) return lookup(variables,name,absent,nativeYsm);
            Object value = lookup(queries,name,absent,nativeYsm);
            if (diagnosticsEnabled && queryBinding(name)) {
                if (queries.containsKey(name) && value == null) recordQueryDiagnostic(name,"unavailable_query",0d);
                else if (!queries.containsKey(name) && !structuredPropertyBound(queries,name)) recordQueryDiagnostic(name,"missing_query",0d);
            }
            return value == null && !nativeYsm ? 0d : value;
        }
        public boolean has(String name) {
            name=normalize(name);if(CONSTANTS.containsKey(name) || nativeYsm && name.equals("this"))return true;
            if(name.startsWith("context."))return bound(controllerVariables,name) || bound(queries,name);
            return bound(writable(name)?variables:queries,name);
        }
        private boolean bound(Map<String,Object> values,String name) {
            if(values.containsKey(name))return true;
            if(!nativeYsm)return false;
            int root=name.indexOf('.',name.indexOf('.')+1);
            if(root<0)return false;
            Object value=values.get(name.substring(0,root));String property=name.substring(root+1);
            return value instanceof MapStruct struct ? struct.properties.containsKey(property)
                    : value instanceof RoamingValue roaming ? roaming.values.containsKey(property)
                    : value instanceof VectorValue ? Set.of("x","y","z").contains(property)
                    : value instanceof StructValue struct && struct.getProperty(property)!=null;
        }
        public void query(String name, double value) { queries.put(normalize(name), finite(value)); }
        public void query(String name, Object value) {
            name = normalize(name);
            // Only opted-in query/ysm bindings retain unavailable metadata; expression values still read as zero.
            queries.put(name,nativeYsm ? nativeChecked(value) : diagnosticsEnabled && queryBinding(name) && value == null ? null : checked(value));
        }
        public void stringQuery(String name, String value) { query(name,(Object)value); }
        /** Immutable copy preserves opted-in unavailable bindings across author function/event scopes. */
        public Map<String,Object> queryValues() { return Collections.unmodifiableMap(new HashMap<>(queries)); }
        public void restoreQueries(Map<String,Object> values) { queries.clear(); values.forEach(this::query); }
        public Map<String,Object> contextValues() { return Collections.unmodifiableMap(new HashMap<>(controllerVariables)); }
        public Map<String,Object> tempValues() {
            Map<String,Object> values=new HashMap<>();variables.forEach((name,value)->{if(name.startsWith("temp."))values.put(name,value);});
            return Collections.unmodifiableMap(values);
        }
        public void restoreTempValues(Map<String,Object> values) {
            Map<String,Object> restored=new HashMap<>();
            values.forEach((name,value)->{
                String key=normalize(name);if(!key.startsWith("temp."))throw invalid("Temporary variable binding");
                restored.put(key,check(value));
            });
            long globals=variables.keySet().stream().filter(name->!name.startsWith("temp.")).count();
            if(globals+restored.size()>4096)throw invalid("Variable limit exceeded");
            variables.keySet().removeIf(name->name.startsWith("temp."));variables.putAll(restored);
        }
        public void restoreContextValues(Map<String,Object> values) {
            if (values.size() > 1024) throw invalid("Controller variable limit exceeded");
            Map<String,Object> checkedValues = new HashMap<>();
            values.forEach((key,value) -> {
                String name = normalize(key);
                if (!name.startsWith("context.")) throw invalid("Controller variable binding");
                checkedValues.put(name, check(value));
            });
            controllerVariables.clear(); controllerVariables.putAll(checkedValues);
        }
        public double set(String name, double value) { return number(setValue(name, value)); }
        public Object setValue(String name, Object value) {
            spend(); name = normalize(name);
            if (!writable(name)) throw invalid("Read-only binding: " + name);
            if (nativeYsm) {
                int point = name.indexOf('.',name.indexOf('.') + 1);
                if (point > 0) {
                    String base = name.substring(0,point), path = name.substring(point + 1);
                    Map<String,Object> storage = name.startsWith("context.") ? controllerVariables : variables;
                    Object existing = storage.get(base);
                    if (value instanceof StructValue) return value;
                    StructValue struct;
                    if (existing instanceof StructValue bound) struct = bound;
                    else { struct = new MapStruct(); setValue(base,struct); struct = (StructValue) storage.get(base); }
                    struct.putProperty(path,check(value)); return check(value);
                }
                if (value instanceof StructValue struct) value = struct.copy();
            }
            if (name.startsWith("context.")) {
                if (!controllerVariables.containsKey(name) && controllerVariables.size() >= 1024) throw invalid("Controller variable limit exceeded");
                value = check(value); controllerVariables.put(name,value); return value;
            }
            if (!variables.containsKey(name) && variables.size() >= 4096) throw invalid("Variable limit exceeded");
            value = check(value); variables.put(name, value); return value;
        }
        public Map<String, Double> variables() {
            Map<String, Double> numbers = new HashMap<>(); variables.forEach((k,v) -> {
                if (!(nativeYsm && v instanceof StructValue)) numbers.put(k, numeric(v));
                // Existing status consumers retain numeric dotted fields for author structs.
                if (v instanceof MapStruct struct) struct.properties.forEach((property,value) -> numbers.put(k + "." + property,numeric(value)));
                if (v instanceof RoamingValue roaming) roaming.values.forEach((property,value)->numbers.put(k+"."+property,numeric(value)));
            });
            return Map.copyOf(numbers);
        }
        public Map<String, Object> values() { return Collections.unmodifiableMap(new HashMap<>(variables)); }
        /** Isolated GUI scopes start with copies of author structs; live readonly bindings keep identity. */
        public void restoreValues(Map<String,Object> values) {
            if (values.size()>4096) throw invalid("Variable limit exceeded");
            variables.clear();roaming=null;
            values.forEach((key,value)->{
                String name=normalize(key);
                if (!name.startsWith("variable.") && !name.startsWith("temp.")) throw invalid("Variable binding");
                variables.put(name,check(value instanceof StructValue struct ? struct.copy() : value));
            });
        }
        private Object check(Object value) { return nativeYsm ? nativeChecked(value) : checked(value); }
        private double numeric(Object value) { return nativeYsm ? nativeNumber(value) : number(value); }
        private boolean booleanValue(Object value) { return nativeYsm ? nativeTruth(value) : truth(value); }
        private Object binaryValue(String op,Object a,Object b) { return binary(op,a,b,nativeYsm); }
        private Object coalesce(String name, Node primary, Node fallback) {
            boolean missing = !has(name);
            boolean unavailable = diagnosticsEnabled && queryBinding(name) && queries.containsKey(name) && queries.get(name) == null;
            if (!missing && !unavailable) return primary.eval(this);
            if (diagnosticsEnabled && queryBinding(name)) spend();
            Object value = fallback.eval(this);
            if (diagnosticsEnabled && queryBinding(name))
                recordQueryDiagnostic(name,unavailable ? "unavailable_query" : "missing_query",check(value));
            return value;
        }
        private Object nativeCoalesce(String name,Node primary,Node fallback) {
            Object value = name == null ? primary.eval(this) : lookup(queries,normalize(name),null,true);
            if (name != null && !queryBinding(normalize(name))) value = primary.eval(this);
            if (value != null) return value;
            spend(); value = fallback.eval(this);
            if (diagnosticsEnabled && name != null && queryBinding(normalize(name)))
                recordQueryDiagnostic(normalize(name),has(name) ? "unavailable_query" : "missing_query",check(value));
            return value;
        }
        private Object childValue(Object value,Node expression) {
            if (!nativeYsm || !(value instanceof ContextValue scoped)) return nativeYsm ? null : 0d;
            Context child = Objects.requireNonNull(scoped.context());
            child.enableNativeYsm(); child.budget = Math.min(child.budget,budget);
            // Sparkle AnimationContext(child,parent) shares author/temp/controller storage and random.
            // Queries remain entity-specific; sharing references makes child assignment visible immediately.
            Map<String,Object> ownVariables=child.variables,ownController=child.controllerVariables;
            Random ownRandom=child.random;double ownValue=child.currentValue;
            String ownPhysics=child.physicsScope,ownEffects=child.effectScope;
            FunctionResolver ownResolver=child.resolver;var ownChildScope=child.childScope;
            child.variables=variables;child.controllerVariables=controllerVariables;child.random=random;
            child.currentValue=currentValue;child.physicsScope=physicsScope;child.effectScope=effectScope;
            child.childScope=childScope;
            try { childScope.accept(child);return expression.eval(child); }
            finally {
                budget=Math.min(budget,child.budget);
                child.variables=ownVariables;child.controllerVariables=ownController;child.random=ownRandom;
                child.currentValue=ownValue;child.physicsScope=ownPhysics;child.effectScope=ownEffects;
                child.resolver=ownResolver;child.childScope=ownChildScope;
            }
        }
        private void recordQueryDiagnostic(String name, String reason, Object actualFallback) {
            String boundedName = name.substring(0,Math.min(name.length(),MAX_DIAGNOSTIC_NAME_LENGTH));
            QueryDiagnostic previous = queryDiagnostics.get(boundedName);
            if (previous == null && queryDiagnostics.size() >= MAX_QUERY_DIAGNOSTICS) return;
            long count = previous == null ? 1 : previous.count() == Long.MAX_VALUE ? Long.MAX_VALUE : previous.count() + 1;
            queryDiagnostics.put(boundedName,new QueryDiagnostic(boundedName,reason,count,actualFallback));
        }
        private void spend() { if (--budget < 0) throw invalid("Evaluation budget exceeded"); }
        private Object call(String name, Node[] arguments) {
            spend(); Object[] values = new Object[arguments.length]; double[] a = new double[arguments.length];
            for (int i = 0; i < a.length; i++) { values[i] = arguments[i].eval(this); a[i] = numeric(values[i]); }
            if (modelFunction(name) && resolver != null)
                return check(resolver.call(name, Collections.unmodifiableList(Arrays.asList(values))));
            return finite(switch (name) {
                case "math.sin" -> Math.sin(Math.toRadians(a[0]));
                case "math.cos" -> Math.cos(Math.toRadians(a[0]));
                case "math.tan" -> Math.tan(Math.toRadians(a[0]));
                case "math.asin" -> Math.toDegrees(Math.asin(a[0]));
                case "math.acos" -> Math.toDegrees(Math.acos(a[0]));
                case "math.atan" -> Math.toDegrees(Math.atan(a[0]));
                case "math.atan2" -> Math.toDegrees(Math.atan2(a[0], a[1]));
                case "math.abs" -> Math.abs(a[0]);
                case "math.sqrt" -> Math.sqrt(Math.max(0, a[0]));
                case "math.exp" -> Math.exp(nativeYsm ? a[0] : Math.max(-80, Math.min(80, a[0])));
                case "math.ln" -> Math.log(a[0]);
                case "math.pow" -> Math.pow(a[0], a[1]);
                case "math.min" -> Arrays.stream(a).min().orElse(0);
                case "math.max" -> Arrays.stream(a).max().orElse(0);
                case "math.clamp" -> Math.max(a[1], Math.min(a[2], a[0]));
                case "math.lerp" -> a[0] + (a[1] - a[0]) * a[2];
                case "math.lerprotate" -> nativeYsm ? nativeLerpRotate(a[0],a[1],a[2]) : a[0] + wrap(a[1] - a[0]) * a[2];
                case "math.floor" -> Math.floor(a[0]);
                case "math.ceil" -> Math.ceil(a[0]);
                case "math.round" -> Math.floor(a[0] + .5);
                case "math.trunc" -> a[0] < 0 ? Math.ceil(a[0]) : Math.floor(a[0]);
                case "math.mod" -> a[1] == 0 ? 0 : a[0] % a[1];
                case "math.sign" -> Math.signum(a[0]);
                case "math.hermite_blend", "math.hermite" -> nativeYsm ? Math.floor(3 * Math.pow(Math.ceil(a[0]),2) - 2 * Math.pow(Math.ceil(a[0]),3)) : a[0] * a[0] * (3 - 2 * a[0]);
                case "math.min_angle" -> wrap(a[0]);
                case "math.random" -> nativeYsm ? Math.min(a[0],a[1]) + random.nextFloat() * Math.abs(a[1] - a[0]) : a[0] + random.nextDouble() * (a[1] - a[0]);
                case "math.random_integer", "math.randomi" -> nativeYsm ? nativeRandomInteger(a[0],a[1]) : Math.floor(a[0] + random.nextDouble() * (a[1] - a[0] + 1));
                case "math.die_roll", "math.roll" -> dice(a[0],a[1],a[2],false);
                case "math.die_roll_integer", "math.rolli" -> dice(a[0],a[1],a[2],true);
                case "query.position_delta" -> get("query.position_delta_" + (int) a[0]);
                case "query.position" -> get("query.position_" + (int) a[0]);
                case "ysm.has_any_curios" -> 0d; // Optional Curios integration is absent in the vanilla client.
                default -> throw invalid("Model function has no binding: " + name);
            });
        }
        private double nativeRandomInteger(double a,double b) {
            int low=(int)Math.min(a,b), high=(int)Math.max(a,b);
            long range=(long)high-low;
            if (range <= 0 || range > Integer.MAX_VALUE) return 0;
            return low + random.nextInt((int)range);
        }
        private double dice(double requested,double min,double max,boolean integers){
            if(requested<0 || requested>1024)throw invalid("Dice count exceeds limit");
            double low=Math.min(min,max),high=Math.max(min,max),value=0;
            int count=integers?(int)Math.round(requested):(int)requested;
            for(int i=0;i<count;i++){spend();double roll=nativeYsm ? integers ? nativeRandomInteger(low,high) : low+random.nextFloat()*(high-low) : low+random.nextDouble()*(high-low+(integers?1:0));value+=integers?Math.floor(roll):roll;}
            return value;
        }
    }
    private record Literal(Object literal) implements Node { public Object eval(Context c) { return literal; } }
    private record Lookup(String name) implements Node { public Object eval(Context c) { return c.value(name); } }
    private record Property(Node base,String path) implements Node {
        public Object eval(Context c) { return property(base.eval(c),path,c.nativeYsm); }
    }
    private static final class Return extends RuntimeException {
        final Object value; Return(Object value) { super(null, null, false, false); this.value = nativeChecked(value); }
    }
    private static final class LoopExit extends RuntimeException {
        final boolean continuing; LoopExit(boolean continuing) { super(null, null, false, false); this.continuing = continuing; }
    }
    public static Program compile(String text) {
        return compile(text,false);
    }
    public static Program compileNativeYsm(String text) { return compile(text,true); }
    private static Program compile(String text,boolean nativeSyntax) {
        Objects.requireNonNull(text);
        if (text.length() > 32_768) throw invalid("Expression length limit exceeded");
        Parser parser = new Parser(text,nativeSyntax); Node statements = parser.statements(false);
        if (!parser.end()) throw invalid("Unexpected trailing expression");
        if (!parser.degradedFunctions.isEmpty()) {
            Map<String,String> functions=Collections.unmodifiableMap(new LinkedHashMap<>(parser.degradedFunctions));
            // The upstream native parser degrades the entire unregistered formula to ZERO.
            statements=c->{functions.forEach((function,reason)->{c.spend();if(c.diagnosticsEnabled)c.recordQueryDiagnostic(function,reason,0d);});return 0d;};
        }
        return new Program(statements, parser.references);
    }
    public static double finite(double value) { return Double.isFinite(value) ? value : 0; }
    private static double number(Object value) {
        return value instanceof Number n ? finite(n.doubleValue()) : value instanceof Boolean b && b ? 1 : 0;
    }
    private static boolean truth(Object value) { return value instanceof String s ? !s.isEmpty() : number(value) != 0; }
    private static double nativeNumber(Object value) { return value == null ? 0 : value instanceof Number n ? finite(n.doubleValue()) : value instanceof Boolean b ? b ? 1 : 0 : 1; }
    private static boolean nativeTruth(Object value) { return value != null && (!(value instanceof Number) && !(value instanceof Boolean) || nativeNumber(value) != 0); }
    private static Object nativeChecked(Object value) {
        if (value == null || value instanceof StructValue || value instanceof ContextValue || value instanceof SequenceValue) return value;
        return checked(value);
    }
    private static Object checked(Object value) {
        if (value == null) return 0d;
        if (value instanceof Number n) return finite(n.doubleValue());
        if (value instanceof Boolean b) return b ? 1d : 0d;
        if (value instanceof String s && s.length() <= 4096) return s;
        if (value instanceof VectorValue) return value;
        if (value instanceof SequenceValue sequence) return new SequenceValue(sequence.elements.stream().map(Molang::checked).toList());
        throw invalid("Unsupported or oversized value");
    }
    private static Object lookup(Map<String,Object> values,String name,Object fallback,boolean nullable) {
        if(values.containsKey(name))return values.get(name);
        int point=name.lastIndexOf('.');
        if(point>0) {
            Object base=values.get(name.substring(0,point));
            if(base instanceof VectorValue || base instanceof StructValue) return property(base,name.substring(point+1),nullable);
            int root=name.indexOf('.',name.indexOf('.')+1);
            if(root>0 && values.get(name.substring(0,root)) instanceof StructValue struct)
                return struct.getProperty(name.substring(root+1));
        }
        return fallback;
    }
    private static boolean queryBinding(String name) { return name.startsWith("query.") || name.startsWith("ysm."); }
    private static boolean structuredPropertyBound(Map<String,Object> values,String name) {
        int point = name.lastIndexOf('.');
        return point > 0 && (values.get(name.substring(0,point)) instanceof VectorValue || values.get(name.substring(0,point)) instanceof StructValue);
    }
    private static Object property(Object value,String name,boolean nullable) {
        if(value instanceof StructValue struct)return struct.getProperty(name);
        if(!(value instanceof VectorValue vector))return nullable ? null : 0d;
        return switch(name){case "x"->vector.x;case "y"->vector.y;case "z"->vector.z;default->{if(nullable)yield null;throw invalid("Unsupported vector property");}};
    }
    private static Object element(Object value,Object index,boolean nativeYsm) {
        if(!(value instanceof SequenceValue sequence) || !(index instanceof Number numeric))return nativeYsm ? null : 0d;
        double requested=numeric.doubleValue();int position=(int)requested;
        if (nativeYsm) position=Math.max(0,position);
        if(!Double.isFinite(requested) || !nativeYsm && requested!=position || position<0 || position>=sequence.elements().size())return nativeYsm ? null : 0d;
        return sequence.elements().get(position);
    }
    private static boolean writable(String name) { return name.startsWith("variable.") || name.startsWith("temp.") || name.startsWith("context."); }
    private static double wrap(double value) { return value - Math.floor((value + 180) / 360) * 360; }
    private static double nativeLerpRotate(double a,double b,double t) {
        a=wrap(a); b=wrap(b); double diff=a-b;
        if(diff>180 || diff<-180)b=a+Math.copySign(360-Math.abs(diff),diff);
        return a+(b-a)*t;
    }
    /** Exact fixed-source validateArgumentSize contracts; unregistered functions are handled separately. */
    private static boolean nativeFunctionArity(String name,int size) {
        return switch(name) {
            case "ysm.bone_rot", "ysm.bone_pos", "ysm.bone_scale", "ysm.bone_pivot_abs", "ysm.mod_version", "ysm.mouse",
                    "ysm.get_equipped_item_name", "ysm.dump_equipped_item", "query.position", "query.position_delta",
                    "query.rotation_to_camera", "query.max_durability", "query.remaining_durability", "ctrl.set_beginning_transition_length" -> size==1;
            case "ysm.relative_block_name", "ysm.dump_relative_block" -> size==3;
            case "ctrl.hold", "ctrl.swing", "ctrl.use", "ctrl.armor", "ctrl.ride" -> size==2 || size==3;
            case "ctrl.set_animation", "ysm.stop_sound" -> size==1 || size==2;
            case "ctrl.reset", "ctrl.indicate_reload" -> size==0;
            case "ysm.stop_all_sounds" -> size<=1;
            case "ysm.play_sound" -> size>=2 && size<=5;
            case "ysm.sync" -> size<=16;
            case "ysm.equipped_enchantment_level", "ysm.first_order", "ysm.second_order", "ysm.perlin_noise",
                    "query.equipped_item_all_tags", "query.equipped_item_any_tag", "query.is_item_name_any" -> size>=2;
            case "ysm.effect_level", "ysm.particle", "ysm.abs_particle", "ysm.keyboard", "ysm.has_any_curios",
                    "query.biome_has_all_tags", "query.biome_has_any_tag", "query.debug_output" -> size>=1;
            case "ysm.relative_block_name_any", "query.relative_block_has_all_tags", "query.relative_block_has_any_tag" -> size>=4;
            default -> true;
        };
    }
    private static String normalize(String name) {
        name = name.toLowerCase(Locale.ROOT);
        if (name.startsWith("q.")) return "query." + name.substring(2);
        if (name.startsWith("v.")) return "variable." + name.substring(2);
        if (name.startsWith("t.")) return "temp." + name.substring(2);
        if (name.startsWith("c.")) return "context." + name.substring(2);
        if (name.startsWith("controller.")) return "context." + name.substring(11);
        return name;
    }
    private static IllegalArgumentException invalid(String why) { return new IllegalArgumentException("Molang: " + why); }
    private record Token(String text, boolean string) { }
    private static final class Parser {
        private final List<Token> tokens = new ArrayList<>();
        private final Set<String> references = new HashSet<>();
        private final Map<String,String> degradedFunctions = new LinkedHashMap<>();
        private int at, depth, loopDepth;
        private final boolean nativeSyntax;
        Parser(String source,boolean nativeSyntax) {
            this.nativeSyntax = nativeSyntax;
            for (int i = 0; i < source.length();) {
                char ch = source.charAt(i);
                if (Character.isWhitespace(ch)) { i++; continue; }
                if (ch == '/' && i + 1 < source.length()) {
                    if (source.charAt(i + 1) == '/') {
                        i += 2;
                        while (i < source.length() && source.charAt(i) != '\n' && source.charAt(i) != '\r') i++;
                        continue;
                    }
                    if (source.charAt(i + 1) == '*') {
                        int end = source.indexOf("*/", i + 2);
                        if (end < 0) throw invalid("Unterminated comment");
                        i = end + 2;
                        continue;
                    }
                }
                if (ch == '\'' || ch == '"') {
                    char quote = ch; StringBuilder value = new StringBuilder(); i++;
                    while (i < source.length() && source.charAt(i) != quote) {
                        char next = source.charAt(i++);
                        if (next == '\\' && i < source.length()) next = source.charAt(i++);
                        value.append(next);
                    }
                    if (i == source.length()) throw invalid("Unterminated string");
                    tokens.add(new Token(value.toString(), true)); i++;
                } else if (Character.isLetter(ch) || ch == '_') {
                    int start = i++;
                    while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || "_.".indexOf(source.charAt(i)) >= 0)) i++;
                    tokens.add(new Token(source.substring(start, i).toLowerCase(Locale.ROOT), false));
                } else if (Character.isDigit(ch) || ch == '.' && i + 1 < source.length() && Character.isDigit(source.charAt(i + 1))) {
                    int start = i++;
                    while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '.')) i++;
                    if (i < source.length() && "eE".indexOf(source.charAt(i)) >= 0) {
                        i++; if (i < source.length() && "+-".indexOf(source.charAt(i)) >= 0) i++;
                        while (i < source.length() && Character.isDigit(source.charAt(i))) i++;
                    }
                    tokens.add(new Token(source.substring(start, i), false));
                } else {
                    String pair = i + 1 < source.length() ? source.substring(i, i + 2) : "";
                    if (Set.of("<=", ">=", "==", "!=", "&&", "||", "+=", "-=", "*=", "/=", "??", "->").contains(pair)) {
                        tokens.add(new Token(pair, false)); i += 2;
                    } else if (".+-*/%(){}[]?:,;=!<>".indexOf(ch) >= 0) { tokens.add(new Token(String.valueOf(ch), false)); i++; }
                    else throw invalid("Unsupported character");
                }
                if (tokens.size() > 16_384) throw invalid("Token limit exceeded");
            }
        }
        boolean end() { return at == tokens.size(); }
        String peek() { return end() ? "" : tokens.get(at).text; }
        boolean take(String text) { if (!end() && !tokens.get(at).string && peek().equals(text)) { at++; return true; } return false; }
        void require(String text) { if (!take(text)) throw invalid("Expected " + text); }
        Node statements(boolean block) {
            if (++depth > 64) throw invalid("Expression depth limit exceeded");
            List<Node> nodes = new ArrayList<>();
            while (!end() && !(block && peek().equals("}"))) {
                if (take(";")) continue;
                if (take("return")) { Node value = expression(0); nodes.add(c -> { throw new Return(value.eval(c)); }); }
                else if (take("break")) {
                    if (loopDepth == 0) throw invalid("Break outside loop"); nodes.add(c -> { throw new LoopExit(false); });
                } else if (take("continue")) {
                    if (loopDepth == 0) throw invalid("Continue outside loop"); nodes.add(c -> { throw new LoopExit(true); });
                } else nodes.add(expression(0));
                if (!end() && !(block && peek().equals("}"))) require(";");
            }
            if (block) require("}"); depth--;
            Node[] program = nodes.toArray(Node[]::new);
            return c -> { Object value = 0d; for (Node node : program) { c.spend(); value = node.eval(c); } return value; };
        }
        Node expression(int minimum) {
            if (++depth > 64) throw invalid("Expression depth limit exceeded");
            Node left; String assignable = null;
            if (take("-")) { Node right = expression(10); left = c -> -c.numeric(right.eval(c)); }
            else if (take("+")) { Node right = expression(10); left = c -> c.numeric(right.eval(c)); }
            else if (take("!")) { Node right = expression(10); left = c -> c.booleanValue(right.eval(c)) ? 0d : 1d; }
            else if (take("(")) { left = expression(0); require(")"); }
            else if (take("{")) left = statements(true);
            else {
                if (end()) throw invalid("Missing operand");
                Token token = tokens.get(at++); String name = normalize(token.text);
                if (token.string) left = new Literal(token.text);
                else if (name.equals("true")) left = new Literal(1d);
                else if (name.equals("false")) left = new Literal(0d);
                else if (!name.isEmpty() && (Character.isDigit(name.charAt(0)) || name.charAt(0) == '.')) {
                    final double value;
                    try { value = Double.parseDouble(name); } catch (NumberFormatException error) { throw invalid("Invalid number"); }
                    if (!Double.isFinite(value)) throw invalid("Non-finite literal"); left = new Literal(value);
                } else if (take("(")) {
                    if (name.equals("loop")) {
                        Node count = expression(0); require(","); loopDepth++; Node body = expression(0); loopDepth--; require(")");
                        if (count instanceof Literal literal && (number(literal.literal) < 0 || number(literal.literal) > 1024))
                            throw invalid("Loop count exceeds limit");
                        left = c -> {
                            double requested = c.numeric(count.eval(c)); if (requested < 0 || requested > 1024) throw invalid("Loop count exceeds limit");
                            Object value = 0d;
                            for (int i = 0; i < (int) requested; i++) {
                                c.spend(); try { value = body.eval(c); } catch (LoopExit exit) { if (!exit.continuing) break; }
                            } return value;
                        };
                    } else if(name.equals("for_each")){
                        Node target=expression(0);require(",");Node collection=expression(0);require(",");
                        loopDepth++;Node body=expression(0);loopDepth--;require(")");
                        if(!(target instanceof Lookup lookup)||!writable(lookup.name))throw invalid("For-each variable binding");
                        String targetName=((Lookup)target).name;
                        left=c->{Object values=collection.eval(c);if(!(values instanceof SequenceValue sequence))return 0d;Object result=0d;
                            for(Object value:sequence.elements){c.spend();c.setValue(targetName,value);try{result=body.eval(c);}catch(LoopExit exit){if(!exit.continuing)break;}}
                            return result;};
                    } else {
                        List<Node> args = new ArrayList<>();
                        if (!take(")")) { do { args.add(expression(0)); } while (take(",")); require(")"); }
                        int arity = switch (name) {
                            case "math.sin", "math.cos", "math.tan", "math.asin", "math.acos", "math.atan", "math.abs", "math.sqrt", "math.exp", "math.ln", "math.floor", "math.ceil", "math.round", "math.trunc", "math.sign", "math.hermite_blend", "math.hermite", "math.min_angle" -> 1;
                            case "math.atan2", "math.pow", "math.mod", "math.random_integer", "math.randomi" -> 2;
                            case "math.random" -> nativeSyntax ? -1 : 2;
                            case "math.clamp", "math.lerp", "math.lerprotate", "math.die_roll", "math.die_roll_integer", "math.roll", "math.rolli" -> 3;
                            case "math.min", "math.max" -> nativeSyntax ? 2 : -1;
                            default -> modelFunction(name) ? -1 : -2;
                        };
                        if (arity == -2) {
                            if (nativeSyntax && (queryBinding(name) || name.startsWith("ctrl."))) {
                                degradedFunctions.put(name,"unregistered_function");arity=-1;
                            } else throw invalid((queryBinding(name) ? "未接入的查询函数: " : "不支持的函数: ")
                                    + name + "；该表达式已拒绝，未执行此函数");
                        }
                        if (args.size()>32) throw invalid("Function argument limit: " + name);
                        boolean invalidArity=arity>=0 && args.size()!=arity
                                || Set.of("math.min", "math.max").contains(name) && args.isEmpty()
                                || nativeSyntax && name.equals("math.random") && args.size()!=2 && args.size()!=3
                                || nativeSyntax && !nativeFunctionArity(name,args.size());
                        if(invalidArity) {
                            if(nativeSyntax)degradedFunctions.put(name,"invalid_function_arity");
                            else throw invalid("Function arity: " + name);
                        }
                        references.add(name); Node[] arguments = args.toArray(Node[]::new); left = c -> c.call(name, arguments);
                    }
                } else {
                    if (!writable(name) && !name.startsWith("query.") && !name.startsWith("ysm.")
                            && !name.startsWith("ctrl.") && !name.startsWith("context.") && !name.startsWith("args.")
                            && !name.equals("args") && !(nativeSyntax && name.equals("this")) && !name.equals("math.pi") && !name.equals("math.e")) throw invalid("Unsupported binding: " + name);
                    references.add(name); assignable = name; left = new Lookup(name);
                }
            }
            while(!end() && (peek().equals(".") || peek().equals("["))) {
                if(take("[")) {
                    Node index=expression(0);require("]");Node value=left;
                    left=c->element(value.eval(c),index.eval(c),c.nativeYsm);assignable=null;
                } else {
                    require(".");if(end())throw invalid("Missing vector property");
                    Token token=tokens.get(at++);String property=token.text;
                    if(token.string || !nativeSyntax && !Set.of("x","y","z").contains(property)
                            || nativeSyntax && !property.matches("[\\p{L}_][\\p{L}\\p{N}_.]{0,127}"))throw invalid("Unsupported vector property");
                    left=new Property(left,property);assignable=null;
                }
            }
            while (!end()) {
                String op = peek(); int precedence = switch (op) {
                    case "=", "+=", "-=", "*=", "/=" -> 1;
                    case "?" -> nativeSyntax ? 3 : 2; case "??" -> nativeSyntax ? 2 : 3; case "||" -> 4; case "&&" -> 5;
                    case "==", "!=" -> 6; case "<", ">", "<=", ">=" -> 7;
                    case "+", "-" -> 8; case "*", "/", "%" -> 9; case "->" -> nativeSyntax ? 11 : -1; default -> -1;
                };
                if (precedence < minimum) break;
                at++; Node a = left;
                if (op.equals("?")) {
                    Node yes = expression(0); Node no = take(":") ? expression(precedence) : new Literal(nativeSyntax ? null : 0d);
                    left = c -> c.booleanValue(a.eval(c)) ? yes.eval(c) : no.eval(c);
                } else if (precedence == 1) {
                    if (nativeSyntax && a instanceof Property property) {
                        Node b=expression(precedence);
                        left=c->{ Object base=property.base.eval(c),value=op.equals("=") ? b.eval(c) : c.binaryValue(op.substring(0,1),property.eval(c),b.eval(c));
                            if(base instanceof StructValue struct && !(value instanceof StructValue))struct.putProperty(property.path,c.check(value));
                            return value; };
                        assignable=null;continue;
                    }
                    if (assignable == null || !writable(assignable)) throw invalid("Invalid assignment target");
                    String name = assignable; Node b = expression(precedence);
                    left = c -> c.setValue(name, op.equals("=") ? b.eval(c) : c.binaryValue(op.substring(0, 1), c.value(name), b.eval(c)));
                } else {
                    Node b = expression(precedence + 1);
                    left = switch (op) {
                        case "&&" -> c -> c.booleanValue(a.eval(c)) && c.booleanValue(b.eval(c)) ? 1d : 0d;
                        case "||" -> c -> c.booleanValue(a.eval(c)) || c.booleanValue(b.eval(c)) ? 1d : 0d;
                        case "??" -> c -> c.nativeYsm ? c.nativeCoalesce(a instanceof Lookup lookup ? lookup.name : null,a,b)
                                : a instanceof Lookup lookup ? c.coalesce(lookup.name,a,b) : a.eval(c);
                        case "->" -> c -> c.childValue(a.eval(c),b);
                        default -> c -> c.binaryValue(op, a.eval(c), b.eval(c));
                    };
                }
                assignable = null;
            }
            depth--; return left;
        }
    }
    private static Object binary(String op, Object left, Object right,boolean nativeYsm) {
        if (op.equals("==") || op.equals("!=")) {
            boolean equal = nativeYsm ? left == right || (left instanceof Number || right instanceof Number ? nativeNumber(left)==nativeNumber(right)
                    : left instanceof String && right instanceof String && Objects.equals(left,right))
                    : left instanceof String || right instanceof String || left instanceof VectorValue || right instanceof VectorValue
                    ? Objects.equals(left, right) : number(left) == number(right);
            return equal == op.equals("==") ? 1d : 0d;
        }
        double a = nativeYsm ? nativeNumber(left) : number(left), b = nativeYsm ? nativeNumber(right) : number(right);
        return finite(switch (op) {
            case "+" -> a + b; case "-" -> a - b; case "*" -> a * b;
            case "/" -> b == 0 ? 0 : a / b; case "%" -> b == 0 ? 0 : a % b;
            case "<" -> a < b ? 1 : 0; case ">" -> a > b ? 1 : 0;
            case "<=" -> a <= b ? 1 : 0; case ">=" -> a >= b ? 1 : 0;
            default -> throw invalid("Unknown operator");
        });
    }
}
