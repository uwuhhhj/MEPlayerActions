package com.simmc.meplayeractions.expression;

import java.util.*;

/** Small numeric Molang interpreter shared by both render paths. No host-language evaluation. */
public final class Molang {
    private Molang() { }
    private interface Node { double eval(Context context); }
    public static final class Program {
        private final Node root;
        private final Set<String> references;
        private Program(Node root, Set<String> references) { this.root = root; this.references = Set.copyOf(references); }
        public double evaluate(Context context) { return finite(root.eval(context)); }
        public Set<String> references() { return references; }
    }
    public static final class Context {
        private final Map<String, Double> variables = new HashMap<>();
        private final Map<String, Double> queries = new HashMap<>();
        private final Random random;
        private int budget = 250_000;
        public Context() { this(0); }
        public Context(long seed) { random = new Random(seed); }
        public void frame(Map<String, Double> values) {
            Map<String, Double> checked = new HashMap<>();
            values.forEach((key, value) -> checked.put(normalize(key), finite(value)));
            queries.clear(); queries.putAll(checked); budget = 250_000;
        }
        public void clear() { variables.clear(); queries.clear(); budget = 250_000; }
        public double get(String name) {
            spend(); name = normalize(name);
            if (name.equals("math.pi")) return Math.PI;
            return name.startsWith("variable.") || name.startsWith("temp.")
                    ? variables.getOrDefault(name, 0d) : queries.getOrDefault(name, 0d);
        }
        public void query(String name, double value) { // Animation time is controller-local.
            queries.put(normalize(name), finite(value));
        }
        public double set(String name, double value) {
            spend(); name = normalize(name);
            if (!name.startsWith("variable.") && !name.startsWith("temp.")) throw invalid("Read-only binding: " + name);
            if (!variables.containsKey(name) && variables.size() >= 4096) throw invalid("Variable limit exceeded");
            value = finite(value); variables.put(name, value); return value;
        }
        public Map<String, Double> variables() { return Map.copyOf(variables); }
        private void spend() { if (--budget < 0) throw invalid("Evaluation budget exceeded"); }
        private double call(String name, Node[] arguments) {
            spend(); double[] a = new double[arguments.length];
            for (int i = 0; i < a.length; i++) a[i] = arguments[i].eval(this);
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
                case "math.min" -> Math.min(a[0], a[1]);
                case "math.max" -> Math.max(a[0], a[1]);
                case "math.clamp" -> Math.max(a[1], Math.min(a[2], a[0]));
                case "math.lerp" -> a[0] + (a[1] - a[0]) * a[2];
                case "math.lerprotate" -> a[0] + wrap(a[1] - a[0]) * a[2];
                case "math.floor" -> Math.floor(a[0]);
                case "math.ceil" -> Math.ceil(a[0]);
                case "math.round" -> Math.floor(a[0] + .5);
                case "math.trunc" -> a[0] < 0 ? Math.ceil(a[0]) : Math.floor(a[0]);
                case "math.mod" -> a[1] == 0 ? 0 : a[0] % a[1];
                case "math.sign" -> Math.signum(a[0]);
                case "math.random" -> a[0] + random.nextDouble() * (a[1] - a[0]);
                case "math.random_integer" -> Math.floor(a[0] + random.nextDouble() * (a[1] - a[0] + 1));
                case "query.position_delta" -> get("query.position_delta_" + (int) a[0]);
                default -> throw invalid("Unknown function: " + name);
            });
        }
    }
    public static Program compile(String text) {
        Objects.requireNonNull(text);
        if (text.length() > 32_768) throw invalid("Expression length limit exceeded");
        Parser parser = new Parser(text); List<Node> statements = new ArrayList<>();
        while (!parser.end()) {
            if (parser.take(";")) continue;
            boolean returning = parser.take("return");
            Node statement = parser.expression(0);
            statements.add(statement);
            if (returning) { if (!parser.end()) parser.require(";"); break; }
            if (!parser.end()) parser.require(";");
        }
        if (!parser.end()) throw invalid("Unexpected trailing expression");
        Node[] program = statements.toArray(Node[]::new);
        return new Program(c -> { double value = 0; for (Node node : program) value = node.eval(c); return value; }, parser.references);
    }
    public static double finite(double value) { return Double.isFinite(value) ? value : 0; }
    private static double wrap(double value) { return value - Math.floor((value + 180) / 360) * 360; }
    private static String normalize(String name) {
        name = name.toLowerCase(Locale.ROOT);
        if (name.startsWith("q.")) return "query." + name.substring(2);
        if (name.startsWith("v.")) return "variable." + name.substring(2);
        if (name.startsWith("t.")) return "temp." + name.substring(2);
        return name;
    }
    private static IllegalArgumentException invalid(String why) { return new IllegalArgumentException("Molang: " + why); }
    private record Token(String text, boolean string) { }
    private static final class Parser {
        private final List<Token> tokens = new ArrayList<>();
        private final Set<String> references = new HashSet<>();
        private int at, depth;
        Parser(String source) {
            for (int i = 0; i < source.length();) {
                char ch = source.charAt(i);
                if (Character.isWhitespace(ch)) { i++; continue; }
                if (ch == '\'' || ch == '"') {
                    char quote = ch; int start = ++i;
                    while (i < source.length() && source.charAt(i) != quote) i++;
                    if (i == source.length()) throw invalid("Unterminated string");
                    tokens.add(new Token(source.substring(start, i++), true));
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
                    if (Set.of("<=", ">=", "==", "!=", "&&", "||", "+=", "-=", "*=", "/=").contains(pair)) {
                        tokens.add(new Token(pair, false)); i += 2;
                    } else if ("+-*/%()?:,;=!<>".indexOf(ch) >= 0) { tokens.add(new Token(String.valueOf(ch), false)); i++; }
                    else throw invalid("Unsupported character at " + i);
                }
                if (tokens.size() > 12_000) throw invalid("Token limit exceeded");
            }
        }
        boolean end() { return at >= tokens.size(); }
        String peek() { return end() ? "" : tokens.get(at).text; }
        boolean take(String text) { if (!end() && !tokens.get(at).string && peek().equals(text)) { at++; return true; } return false; }
        void require(String text) { if (!take(text)) throw invalid("Expected '" + text + "' at token " + at); }
        Node expression(int minimum) {
            if (++depth > 64) throw invalid("Expression depth limit exceeded");
            Node left;
            String assignable = null;
            if (take("-")) { Node right = expression(9); left = c -> -right.eval(c); }
            else if (take("+")) left = expression(9);
            else if (take("!")) { Node right = expression(9); left = c -> right.eval(c) == 0 ? 1 : 0; }
            else if (take("(")) { left = expression(0); require(")"); }
            else {
                if (end()) throw invalid("Missing operand");
                Token token = tokens.get(at++); String name = normalize(token.text);
                if (token.string) left = c -> 0; // Quoted explanatory statements in physics timelines.
                else if (name.equals("true")) left = c -> 1;
                else if (name.equals("false")) left = c -> 0;
                else if (!name.isEmpty() && (Character.isDigit(name.charAt(0)) || name.charAt(0) == '.')) {
                    final double value;
                    try { value = Double.parseDouble(name); } catch (NumberFormatException error) { throw invalid("Invalid number"); }
                    if (!Double.isFinite(value)) throw invalid("Non-finite literal"); left = c -> value;
                } else if (take("(")) {
                    List<Node> args = new ArrayList<>();
                    if (!take(")")) { do { args.add(expression(0)); } while (take(",")); require(")"); }
                    int arity = switch (name) {
                        case "math.sin", "math.cos", "math.tan", "math.asin", "math.acos", "math.atan", "math.abs", "math.sqrt", "math.exp", "math.ln", "math.floor", "math.ceil", "math.round", "math.trunc", "math.sign", "query.position_delta" -> 1;
                        case "math.atan2", "math.pow", "math.min", "math.max", "math.mod", "math.random", "math.random_integer" -> 2;
                        case "math.clamp", "math.lerp", "math.lerprotate" -> 3;
                        default -> throw invalid("Unsupported function: " + name);
                    };
                    if (args.size() != arity) throw invalid("Function arity: " + name);
                    references.add(name); Node[] arguments = args.toArray(Node[]::new); left = c -> c.call(name, arguments);
                } else {
                    if (!name.startsWith("variable.") && !name.startsWith("temp.") && !name.startsWith("query.")
                            && !name.startsWith("ysm.") && !name.startsWith("ctrl.") && !name.equals("math.pi")) throw invalid("Unsupported binding: " + name);
                    references.add(name); assignable = name; left = c -> c.get(name);
                }
            }
            while (!end()) {
                String op = peek(); int precedence = switch (op) {
                    case "=", "+=", "-=", "*=", "/=" -> 1;
                    case "?" -> 2; case "||" -> 3; case "&&" -> 4;
                    case "==", "!=" -> 5; case "<", ">", "<=", ">=" -> 6;
                    case "+", "-" -> 7; case "*", "/", "%" -> 8; default -> -1;
                };
                if (precedence < minimum) break;
                at++; Node a = left;
                if (op.equals("?")) {
                    Node yes = expression(0); require(":"); Node no = expression(precedence);
                    left = c -> a.eval(c) != 0 ? yes.eval(c) : no.eval(c);
                } else if (precedence == 1) {
                    if (assignable == null || !(assignable.startsWith("variable.") || assignable.startsWith("temp."))) throw invalid("Invalid assignment target");
                    String name = assignable; Node b = expression(precedence);
                    left = c -> c.set(name, op.equals("=") ? b.eval(c) : binary(op.substring(0, 1), c.get(name), b.eval(c)));
                } else {
                    Node b = expression(precedence + 1);
                    left = switch (op) {
                        case "&&" -> c -> a.eval(c) != 0 && b.eval(c) != 0 ? 1 : 0;
                        case "||" -> c -> a.eval(c) != 0 || b.eval(c) != 0 ? 1 : 0;
                        default -> c -> binary(op, a.eval(c), b.eval(c));
                    };
                }
                assignable = null;
            }
            depth--; return left;
        }
    }
    private static double binary(String op, double a, double b) {
        return finite(switch (op) {
            case "+" -> a + b; case "-" -> a - b; case "*" -> a * b;
            case "/" -> b == 0 ? 0 : a / b; case "%" -> b == 0 ? 0 : a % b;
            case "==" -> a == b ? 1 : 0; case "!=" -> a != b ? 1 : 0;
            case "<" -> a < b ? 1 : 0; case ">" -> a > b ? 1 : 0;
            case "<=" -> a <= b ? 1 : 0; case ">=" -> a >= b ? 1 : 0;
            default -> throw invalid("Unknown operator");
        });
    }
}
