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
    public record SequenceValue(List<Object> elements) {
        public SequenceValue {
            if(elements.size()>256)throw invalid("Sequence limit exceeded");
            elements=elements.stream().map(Molang::checked).toList();
            if(elements.stream().anyMatch(SequenceValue.class::isInstance))throw invalid("Nested sequence limit");
        }
    }
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
            "ctrl.set_beginning_transition_length", "ctrl.reset", "ctrl.indicate_reload", "ysm.has_any_curios", "ysm.sync");
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
        public double evaluate(Context context) { return number(evaluateValue(context)); }
        public Object evaluateValue(Context context) {
            try { return checked(root.eval(Objects.requireNonNull(context))); }
            catch (Return returned) { return returned.value; }
        }
        public Set<String> references() { return references; }
    }
    public static final class Context {
        private final Map<String, Object> variables = new HashMap<>(), queries = new HashMap<>();
        private final Map<String, Object> controllerVariables = new HashMap<>();
        private final Random random;
        private FunctionResolver resolver;
        private int budget = 250_000;
        public Context() { this(0); }
        public Context(long seed) { random = new Random(seed); }
        public void frame(Map<String, Double> values) {
            queries.clear(); values.forEach((key, value) -> queries.put(normalize(key), finite(value)));
            variables.keySet().removeIf(key -> key.startsWith("temp.")); budget = 250_000;
        }
        public void clear() { variables.clear(); queries.clear(); controllerVariables.clear(); budget = 250_000; }
        public void functions(FunctionResolver resolver) { this.resolver = resolver; }
        public FunctionResolver functionResolver() { return resolver; }
        public double get(String name) { return number(value(name)); }
        public Object value(String name) {
            spend(); name = normalize(name);
            if (CONSTANTS.containsKey(name)) return CONSTANTS.get(name);
            if (name.startsWith("context.")) return lookup(controllerVariables,name,lookup(queries,name,0d));
            return writable(name) ? lookup(variables,name,0d) : lookup(queries,name,0d);
        }
        public boolean has(String name) { name = normalize(name); if(CONSTANTS.containsKey(name))return true;return name.startsWith("context.")
                ? controllerVariables.containsKey(name) || queries.containsKey(name)
                : writable(name) ? variables.containsKey(name) : queries.containsKey(name); }
        public void query(String name, double value) { queries.put(normalize(name), finite(value)); }
        public void query(String name, Object value) { queries.put(normalize(name), checked(value)); }
        public void stringQuery(String name, String value) { queries.put(normalize(name), checked(value)); }
        public Map<String,Object> queryValues() { return Map.copyOf(queries); }
        public void restoreQueries(Map<String,Object> values) { queries.clear(); values.forEach(this::query); }
        public Map<String,Object> contextValues() { return Map.copyOf(controllerVariables); }
        public Map<String,Object> tempValues() {
            Map<String,Object> values=new HashMap<>();variables.forEach((name,value)->{if(name.startsWith("temp."))values.put(name,value);});
            return Map.copyOf(values);
        }
        public void restoreTempValues(Map<String,Object> values) {
            Map<String,Object> restored=new HashMap<>();
            values.forEach((name,value)->{
                String key=normalize(name);if(!key.startsWith("temp."))throw invalid("Temporary variable binding");
                restored.put(key,checked(value));
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
                checkedValues.put(name, checked(value));
            });
            controllerVariables.clear(); controllerVariables.putAll(checkedValues);
        }
        public double set(String name, double value) { return number(setValue(name, value)); }
        public Object setValue(String name, Object value) {
            spend(); name = normalize(name);
            if (!writable(name)) throw invalid("Read-only binding: " + name);
            if (name.startsWith("context.")) {
                if (!controllerVariables.containsKey(name) && controllerVariables.size() >= 1024) throw invalid("Controller variable limit exceeded");
                value = checked(value); controllerVariables.put(name,value); return value;
            }
            if (!variables.containsKey(name) && variables.size() >= 4096) throw invalid("Variable limit exceeded");
            value = checked(value); variables.put(name, value); return value;
        }
        public Map<String, Double> variables() {
            Map<String, Double> numbers = new HashMap<>(); variables.forEach((k,v) -> numbers.put(k, number(v)));
            return Map.copyOf(numbers);
        }
        public Map<String, Object> values() { return Map.copyOf(variables); }
        private void spend() { if (--budget < 0) throw invalid("Evaluation budget exceeded"); }
        private Object call(String name, Node[] arguments) {
            spend(); Object[] values = new Object[arguments.length]; double[] a = new double[arguments.length];
            for (int i = 0; i < a.length; i++) { values[i] = arguments[i].eval(this); a[i] = number(values[i]); }
            if (modelFunction(name) && resolver != null)
                return checked(resolver.call(name, List.of(values)));
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
                case "math.exp" -> Math.exp(Math.max(-80, Math.min(80, a[0])));
                case "math.ln" -> Math.log(a[0]);
                case "math.pow" -> Math.pow(a[0], a[1]);
                case "math.min" -> Arrays.stream(a).min().orElse(0);
                case "math.max" -> Arrays.stream(a).max().orElse(0);
                case "math.clamp" -> Math.max(a[1], Math.min(a[2], a[0]));
                case "math.lerp" -> a[0] + (a[1] - a[0]) * a[2];
                case "math.lerprotate" -> a[0] + wrap(a[1] - a[0]) * a[2];
                case "math.floor" -> Math.floor(a[0]);
                case "math.ceil" -> Math.ceil(a[0]);
                case "math.round" -> Math.floor(a[0] + .5);
                case "math.trunc" -> a[0] < 0 ? Math.ceil(a[0]) : Math.floor(a[0]);
                case "math.mod" -> a[1] == 0 ? 0 : a[0] % a[1];
                case "math.sign" -> Math.signum(a[0]);
                case "math.hermite_blend", "math.hermite" -> a[0] * a[0] * (3 - 2 * a[0]);
                case "math.min_angle" -> wrap(a[0]);
                case "math.random" -> a[0] + random.nextDouble() * (a[1] - a[0]);
                case "math.random_integer", "math.randomi" -> Math.floor(a[0] + random.nextDouble() * (a[1] - a[0] + 1));
                case "math.die_roll", "math.roll" -> dice(a[0],a[1],a[2],false);
                case "math.die_roll_integer", "math.rolli" -> dice(a[0],a[1],a[2],true);
                case "query.position_delta" -> get("query.position_delta_" + (int) a[0]);
                case "query.position" -> get("query.position_" + (int) a[0]);
                case "ysm.has_any_curios" -> 0d; // Optional Curios integration is absent in the vanilla client.
                default -> throw invalid("Model function has no binding: " + name);
            });
        }
        private double dice(double requested,double min,double max,boolean integers){
            if(requested<0 || requested>1024)throw invalid("Dice count exceeds limit");
            double low=Math.min(min,max),high=Math.max(min,max),value=0;
            int count=integers?(int)Math.round(requested):(int)requested;
            for(int i=0;i<count;i++){spend();double roll=low+random.nextDouble()*(high-low+(integers?1:0));value+=integers?Math.floor(roll):roll;}
            return value;
        }
    }
    private record Literal(Object literal) implements Node { public Object eval(Context c) { return literal; } }
    private record Lookup(String name) implements Node { public Object eval(Context c) { return c.value(name); } }
    private static final class Return extends RuntimeException {
        final Object value; Return(Object value) { super(null, null, false, false); this.value = checked(value); }
    }
    private static final class LoopExit extends RuntimeException {
        final boolean continuing; LoopExit(boolean continuing) { super(null, null, false, false); this.continuing = continuing; }
    }
    public static Program compile(String text) {
        Objects.requireNonNull(text);
        if (text.length() > 32_768) throw invalid("Expression length limit exceeded");
        Parser parser = new Parser(text); Node statements = parser.statements(false);
        if (!parser.end()) throw invalid("Unexpected trailing expression");
        return new Program(statements, parser.references);
    }
    public static double finite(double value) { return Double.isFinite(value) ? value : 0; }
    private static double number(Object value) {
        return value instanceof Number n ? finite(n.doubleValue()) : value instanceof Boolean b && b ? 1 : 0;
    }
    private static boolean truth(Object value) { return value instanceof String s ? !s.isEmpty() : number(value) != 0; }
    private static Object checked(Object value) {
        if (value == null) return 0d;
        if (value instanceof Number n) return finite(n.doubleValue());
        if (value instanceof Boolean b) return b ? 1d : 0d;
        if (value instanceof String s && s.length() <= 4096) return s;
        if (value instanceof VectorValue) return value;
        if (value instanceof SequenceValue) return value;
        throw invalid("Unsupported or oversized value");
    }
    private static Object lookup(Map<String,Object> values,String name,Object fallback) {
        if(values.containsKey(name))return values.get(name);
        int point=name.lastIndexOf('.');
        if(point>0 && values.get(name.substring(0,point)) instanceof VectorValue vector)
            return property(vector,name.substring(point+1));
        return fallback;
    }
    private static Object property(Object value,String name) {
        if(!(value instanceof VectorValue vector))return 0d;
        return switch(name){case "x"->vector.x;case "y"->vector.y;case "z"->vector.z;default->throw invalid("Unsupported vector property");};
    }
    private static Object element(Object value,Object index) {
        if(!(value instanceof SequenceValue sequence) || !(index instanceof Number numeric))return 0d;
        double requested=numeric.doubleValue();int position=(int)requested;
        if(!Double.isFinite(requested) || requested!=position || position<0 || position>=sequence.elements().size())return 0d;
        return sequence.elements().get(position);
    }
    private static boolean writable(String name) { return name.startsWith("variable.") || name.startsWith("temp.") || name.startsWith("context."); }
    private static double wrap(double value) { return value - Math.floor((value + 180) / 360) * 360; }
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
        private int at, depth, loopDepth;
        Parser(String source) {
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
                    if (Set.of("<=", ">=", "==", "!=", "&&", "||", "+=", "-=", "*=", "/=", "??").contains(pair)) {
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
            if (take("-")) { Node right = expression(10); left = c -> -number(right.eval(c)); }
            else if (take("+")) { Node right = expression(10); left = c -> number(right.eval(c)); }
            else if (take("!")) { Node right = expression(10); left = c -> truth(right.eval(c)) ? 0d : 1d; }
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
                            double requested = number(count.eval(c)); if (requested < 0 || requested > 1024) throw invalid("Loop count exceeds limit");
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
                            case "math.atan2", "math.pow", "math.mod", "math.random", "math.random_integer", "math.randomi" -> 2;
                            case "math.clamp", "math.lerp", "math.lerprotate", "math.die_roll", "math.die_roll_integer", "math.roll", "math.rolli" -> 3;
                            case "math.min", "math.max" -> -1;
                            default -> modelFunction(name) ? -1 : -2;
                        };
                        if (arity == -2) throw invalid("Unsupported function: " + name);
                        if (args.size() > 32 || arity >= 0 && args.size() != arity
                                || Set.of("math.min", "math.max").contains(name) && args.isEmpty()) throw invalid("Function arity: " + name);
                        references.add(name); Node[] arguments = args.toArray(Node[]::new); left = c -> c.call(name, arguments);
                    }
                } else {
                    if (!writable(name) && !name.startsWith("query.") && !name.startsWith("ysm.")
                            && !name.startsWith("ctrl.") && !name.startsWith("context.") && !name.startsWith("args.")
                            && !name.equals("args") && !name.equals("math.pi") && !name.equals("math.e")) throw invalid("Unsupported binding: " + name);
                    references.add(name); assignable = name; left = new Lookup(name);
                }
            }
            while(!end() && (peek().equals(".") || peek().equals("["))) {
                if(take("[")) {
                    Node index=expression(0);require("]");Node value=left;
                    left=c->element(value.eval(c),index.eval(c));assignable=null;
                } else {
                    require(".");if(end())throw invalid("Missing vector property");
                    Token token=tokens.get(at++);String property=token.text;
                    if(token.string || !Set.of("x","y","z").contains(property))throw invalid("Unsupported vector property");
                    Node value=left;left=c->property(value.eval(c),property);assignable=null;
                }
            }
            while (!end()) {
                String op = peek(); int precedence = switch (op) {
                    case "=", "+=", "-=", "*=", "/=" -> 1;
                    case "?" -> 2; case "??" -> 3; case "||" -> 4; case "&&" -> 5;
                    case "==", "!=" -> 6; case "<", ">", "<=", ">=" -> 7;
                    case "+", "-" -> 8; case "*", "/", "%" -> 9; default -> -1;
                };
                if (precedence < minimum) break;
                at++; Node a = left;
                if (op.equals("?")) {
                    Node yes = expression(0); Node no = take(":") ? expression(precedence) : new Literal(0d);
                    left = c -> truth(a.eval(c)) ? yes.eval(c) : no.eval(c);
                } else if (precedence == 1) {
                    if (assignable == null || !writable(assignable)) throw invalid("Invalid assignment target");
                    String name = assignable; Node b = expression(precedence);
                    left = c -> c.setValue(name, op.equals("=") ? b.eval(c) : binary(op.substring(0, 1), c.value(name), b.eval(c)));
                } else {
                    Node b = expression(precedence + 1);
                    left = switch (op) {
                        case "&&" -> c -> truth(a.eval(c)) && truth(b.eval(c)) ? 1d : 0d;
                        case "||" -> c -> truth(a.eval(c)) || truth(b.eval(c)) ? 1d : 0d;
                        case "??" -> c -> a instanceof Lookup lookup && !c.has(lookup.name) ? b.eval(c) : a.eval(c);
                        default -> c -> binary(op, a.eval(c), b.eval(c));
                    };
                }
                assignable = null;
            }
            depth--; return left;
        }
    }
    private static Object binary(String op, Object left, Object right) {
        if (op.equals("==") || op.equals("!=")) {
            boolean equal = left instanceof String || right instanceof String || left instanceof VectorValue || right instanceof VectorValue
                    ? Objects.equals(left, right) : number(left) == number(right);
            return equal == op.equals("==") ? 1d : 0d;
        }
        double a = number(left), b = number(right);
        return finite(switch (op) {
            case "+" -> a + b; case "-" -> a - b; case "*" -> a * b;
            case "/" -> b == 0 ? 0 : a / b; case "%" -> b == 0 ? 0 : a % b;
            case "<" -> a < b ? 1 : 0; case ">" -> a > b ? 1 : 0;
            case "<=" -> a <= b ? 1 : 0; case ">=" -> a >= b ? 1 : 0;
            default -> throw invalid("Unknown operator");
        });
    }
}
