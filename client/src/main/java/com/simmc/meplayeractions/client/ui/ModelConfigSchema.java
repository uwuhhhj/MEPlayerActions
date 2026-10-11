package com.simmc.meplayeractions.client.ui;

import com.simmc.meplayeractions.client.ClientOptions;
import com.simmc.meplayeractions.client.model.YsmModelProfile;
import com.simmc.meplayeractions.expression.Molang;

import com.google.gson.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Author-ordered, bounded form definitions. Reading a form never executes its option scripts. */
public final class ModelConfigSchema
{
    public enum Kind
    {
        CHECKBOX, RANGE, RADIO
    }

    public record Radio(String label, String script)
    {
    }

    public record Form(String key, Kind kind, String title, String description, String expression, double minimum,
            double maximum, double step, List<Radio> choices)
    {
        public Form
        {
            choices = List.copyOf(choices);
        }

        public double read(Map<String, Double> variables)
        {
            Molang.Context context = new Molang.Context();
            variables.forEach(context::set);
            return Molang.compile(this.expression).evaluate(context);
        }

        public int selectedIndex(double value)
        {
            int selected = Double.isFinite(value) ? Math.round((float) value) : 0;
            return selected >= 0 && selected < this.choices.size() ? selected : 0;
        }

        public double snap(double value)
        {
            if (!Double.isFinite(value))
            {
                throw new IllegalArgumentException("请输入有效数字");
            }

            double low = Math.min(this.minimum, this.maximum), high = Math.max(this.minimum, this.maximum);
            double result = Math.max(low, Math.min(high, value));
            if (this.step > 0)
            {
                result = this.step * Math.round(result / this.step);
            }

            return Math.max(low, Math.min(high, result));
        }

        public String checkboxScript(boolean selected)
        {
            if (this.kind != Kind.CHECKBOX)
            {
                throw new IllegalStateException("Not a checkbox");
            }

            return this.expression + "=" + (selected ? "1" : "0") + ";";
        }

        public String rangeScript(double value)
        {
            if (this.kind != Kind.RANGE)
            {
                throw new IllegalStateException("Not a range");
            }

            return this.expression + "=" + Double.toString(snap(value)) + ";";
        }

        public String radioScript(int index)
        {
            if (this.kind != Kind.RADIO || index < 0 || index >= this.choices.size())
            {
                throw new IllegalArgumentException("Radio index");
            }

            return this.choices.get(index).script();
        }

        public Set<String> variables()
        {
            Set<String> result = new LinkedHashSet<>();
            collect(this.expression, result);
            this.choices.forEach(choice -> collect(choice.script(), result));
            return Collections.unmodifiableSet(result);
        }

        private static void collect(String script, Set<String> target)
        {
            // GUI metadata must not reject mature scripts because the legacy numeric parser lacks a type.
            // Ignore quoted text; the runtime engine validates and executes the complete author script.
            Matcher references = Pattern.compile(
                    "(?i)(?<![\\p{L}\\p{N}_.])(?:v|variable)\\.[\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)*")
                    .matcher(unquotedCode(script));
            while (references.find())
            {
                try
                {
                    target.add(ClientOptions.normalizeModelVariable(references.group()));
                }
                catch (IllegalArgumentException ignored)
                {
                }
            }
        }
    }

    public record Group(String id, String name, String description, List<Form> forms)
    {
        public Group
        {
            forms = List.copyOf(forms);
        }
    }

    private final List<Group> groups;
    private final Set<String> functionVariables;

    private ModelConfigSchema(List<Group> groups)
    {
        this(groups, Set.of());
    }

    private ModelConfigSchema(List<Group> groups, Set<String> functionVariables)
    {
        this.groups = List.copyOf(groups);
        this.functionVariables = Set.copyOf(functionVariables);
    }

    public List<Group> groups()
    {
        return this.groups;
    }

    public Group group(String id)
    {
        for (Group group : this.groups)
        {
            if (group.id().equals(id))
            {
                return group;
            }
        }
        return null;
    }

    public Set<String> variables()
    {
        Set<String> variables = new LinkedHashSet<>(this.functionVariables);
        this.groups.forEach(group -> group.forms().forEach(form -> variables.addAll(form.variables())));
        if (variables.size() > ClientOptions.MAX_MODEL_VARIABLES)
        {
            throw new IllegalArgumentException("模型配置变量过多");
        }

        return Collections.unmodifiableSet(variables);
    }

    public static ModelConfigSchema from(YsmModelProfile profile, String locale)
    {
        if (profile == null)
        {
            return parse(new JsonArray());
        }

        ModelConfigSchema original = parse(profile.extraAnimationButtons());
        List<Group> localized = new ArrayList<>();
        for (Group group : original.groups)
        {
            List<Form> forms = new ArrayList<>();
            for (int index = 0; index < group.forms().size(); index++)
            {
                Form form = group.forms().get(index);
                String path = "properties.extra_animation_buttons." + group.id() + ".config_forms." + index;
                List<Radio> choices = new ArrayList<>();
                for (int choice = 0; choice < form.choices().size(); choice++)
                {
                    Radio radio = form.choices().get(choice);
                    choices.add(new Radio(localized(profile, locale, path + ".labels." + choice, radio.label()),
                            radio.script()));
                }
                forms.add(new Form(form.key(), form.kind(), localized(profile, locale, path + ".title", form.title()),
                        localized(profile, locale, path + ".description", form.description()), form.expression(),
                        form.minimum(), form.maximum(), form.step(), choices));
            }
            String path = "properties.extra_animation_buttons." + group.id();
            localized.add(new Group(group.id(), localized(profile, locale, path + ".name", group.name()),
                    localized(profile, locale, path + ".description", group.description()), forms));
        }
        ModelConfigSchema result = new ModelConfigSchema(localized,
                configurationFunctionVariables(original, profile.functions()));
        result.variables();
        return result;
    }

    public static ModelConfigSchema parse(JsonArray buttons)
    {
        if (buttons.size() > 32)
        {
            throw new IllegalArgumentException("模型配置分组过多");
        }

        List<Group> groups = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int count = 0;
        for (JsonElement entry : buttons)
        {
            JsonObject group = entry.getAsJsonObject();
            String id = text(group, "id", "");
            if (id.isBlank() || id.length() > 128 || id.chars().anyMatch(Character::isISOControl) || !ids.add(id))
            {
                throw new IllegalArgumentException("模型配置分组 ID 无效");
            }

            JsonArray definitions = group.has("config_forms") ? group.getAsJsonArray("config_forms") : new JsonArray();
            if ((count += definitions.size()) > 128)
            {
                throw new IllegalArgumentException("模型配置项目过多");
            }

            List<Form> forms = new ArrayList<>();
            int index = 0;
            for (JsonElement definition : definitions)
            {
                JsonObject config = definition.getAsJsonObject();
                Kind kind = switch (text(config, "type", ""))
                {
                case "checkbox" -> Kind.CHECKBOX;
                case "range" -> Kind.RANGE;
                case "radio" -> Kind.RADIO;
                default -> throw new IllegalArgumentException("不支持的模型配置类型");
                };
                String expression = text(config, "value", "");
                if (expression.isBlank())
                {
                    throw new IllegalArgumentException("模型配置表达式为空");
                }

                if (kind != Kind.RADIO)
                {
                    ClientOptions.normalizeModelVariable(expression);
                }

                double min = number(config, "min", 0), max = number(config, "max", 0),
                        step = Math.abs(number(config, "step", 0));
                if (kind == Kind.RANGE && (step > 1_000_000 || Math.abs(min) > 1_000_000 || Math.abs(max) > 1_000_000))

                {
                    throw new IllegalArgumentException("模型范围配置无效");
                }

                List<Radio> choices = new ArrayList<>();
                if (kind == Kind.RADIO)
                {
                    JsonObject labels = config.has("labels") ? config.getAsJsonObject("labels") : new JsonObject();
                    if (labels.isEmpty() || labels.size() > 64)
                    {
                        throw new IllegalArgumentException("模型单选选项无效");
                    }

                    for (Map.Entry<String, JsonElement> label : labels.entrySet())
                    {
                        if (!label.getValue().isJsonPrimitive() || !label.getValue().getAsJsonPrimitive().isString())
                        {
                            throw new IllegalArgumentException("模型单选脚本无效");
                        }

                        String script = label.getValue().getAsString();
                        if (script.isBlank())
                        {
                            throw new IllegalArgumentException("模型单选脚本为空");
                        }

                        choices.add(new Radio(bounded(label.getKey()), bounded(script)));
                    }
                }
                Form form = new Form(id + ":" + index++, kind, text(config, "title", expression),
                        text(config, "description", ""), expression, min, max, step, choices);
                forms.add(form);
            }
            groups.add(new Group(id, text(group, "name", id), text(group, "description", ""), forms));
        }
        ModelConfigSchema schema = new ModelConfigSchema(groups);
        schema.variables();
        return schema;
    }

    private static String localized(YsmModelProfile profile, String locale, String path, String fallback)
    {
        return profile.localized(locale, path, profile.localized(locale, fallback, fallback));
    }

    private static Set<String> configurationFunctionVariables(ModelConfigSchema schema, Map<String, String> functions)
    {
        Set<String> variables = new LinkedHashSet<>(), visited = new HashSet<>();
        Deque<String> scripts = new ArrayDeque<>();
        for (ModelConfigSchema.Group group : schema.groups)
        {
            for (ModelConfigSchema.Form form : group.forms())
            {
                scripts.add(form.expression());
                for (ModelConfigSchema.Radio choice : form.choices())
                {
                    scripts.add(choice.script());
                }

            }
        }

        Pattern calls = Pattern.compile("(?i)(?<![\\p{L}\\p{N}_.])fn\\.([\\p{L}_][\\p{L}\\p{N}_]*)\\s*\\(");
        while (!scripts.isEmpty())
        {
            Matcher references = calls.matcher(unquotedCode(scripts.removeFirst()));
            while (references.find())
            {
                String name = references.group(1).toLowerCase(Locale.ROOT);
                if (!visited.add(name))
                {
                    continue;
                }

                String script = functions.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
                if (script != null)
                {
                    Form.collect(script, variables);
                    scripts.addLast(script);
                }
            }
        }
        return variables;
    }

    private static String unquotedCode(String script)
    {
        StringBuilder code = new StringBuilder(script.length());
        char quote = 0;
        boolean escape = false;
        for (int i = 0; i < script.length(); i++)
        {
            char c = script.charAt(i);
            if (quote != 0)
            {
                code.append(' ');
                if (escape)
                {
                    escape = false;
                }
                else if (c == '\\')
                {
                    escape = true;
                }
                else if (c == quote)
                {
                    quote = 0;
                }

            }
            else if (c == '\'' || c == '"')
            {
                quote = c;
                code.append(' ');
            }
            else
            {
                code.append(c);
            }

        }
        return code.toString();
    }

    private static double number(JsonObject object, String key, double fallback)
    {
        if (!object.has(key))
        {
            return fallback;
        }

        if (!object.getAsJsonPrimitive(key).isNumber())
        {
            throw new IllegalArgumentException("模型配置数字无效");
        }

        double value = object.get(key).getAsDouble();
        if (!Double.isFinite(value))
        {
            throw new IllegalArgumentException("模型配置数字无效");
        }
        return value;
    }

    private static String text(JsonObject object, String key, String fallback)
    {
        if (!object.has(key))
        {
            return fallback;
        }

        if (!object.getAsJsonPrimitive(key).isString())
        {
            throw new IllegalArgumentException("模型配置文字无效");
        }

        return bounded(object.get(key).getAsString());
    }

    private static String bounded(String value)
    {
        if (value.length() > 16_384)
        {
            throw new IllegalArgumentException("模型配置文字过长");
        }
        return value;
    }
}
