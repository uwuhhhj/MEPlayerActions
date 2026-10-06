package com.simmc.meplayeractions.client;

import com.google.gson.*;
import com.simmc.meplayeractions.config.ModelComplexityLimits;
import java.io.IOException;
import java.util.Set;

/** Counts whole-bundle work before the native parsers instantiate geometry or animation state. */
final class ModelComplexity {
    private static final Set<String> FORMULAS = Set.of("script", "scripts", "pre_animation", "initialize", "initialization",
            "animation_time_update", "anim_time_update", "animation_length", "start_delay", "loop_delay",
            "blend_weight", "blend_transition", "on_entry", "on_exit", "condition", "expression");
    private static final Set<String> CHANNELS = Set.of("rotation", "position", "scale", "data_points", "transitions",
            "variables", "timeline", "pre", "post");
    private static final Set<String> VECTOR_CHANNELS = Set.of("rotation", "position", "scale");
    private final ModelComplexityLimits limits;
    private long nodes, bones, animations, keyframes, expressions, expressionChars;
    ModelComplexity(ModelComplexityLimits limits) { this.limits = java.util.Objects.requireNonNull(limits); }
    void document(JsonElement value) throws IOException { visit(value, false, false, 0); }
    void script(String value) throws IOException {
        if (++expressions > limits.maxExpressions() || (expressionChars += value.length()) > limits.maxExpressionChars()) reject("expression_chars");
    }
    private void visit(JsonElement value, boolean formula, boolean outliner, int depth) throws IOException {
        if ((nodes & 1023) == 0 && Thread.currentThread().isInterrupted()) throw new IOException("task_cancelled");
        if (depth > 64 || ++nodes > limits.maxJsonNodes()) reject("json_nodes");
        if (value.isJsonArray()) {
            for (JsonElement child : value.getAsJsonArray()) visit(child, formula, outliner, depth + 1);
        } else if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (outliner) { if (++bones > limits.maxBones()) reject("bones"); }
            for (var field : object.entrySet()) {
                String name = field.getKey(); JsonElement child = field.getValue();
                if (name.equals("bones") && child.isJsonArray()) addBones(child.getAsJsonArray().size());
                // Independent Blockbench group tables and nested outliner forms are alternatives.
                if (name.equals("groups") && child.isJsonArray()) addBones(child.getAsJsonArray().size());
                if (name.equals("animations") && depth == 0) {
                    if (child.isJsonArray()) addAnimations(child.getAsJsonArray().size());
                    else if (child.isJsonObject()) addAnimations(child.getAsJsonObject().size());
                }
                if (name.equals("keyframes") && child.isJsonArray()) addFrames(child.getAsJsonArray().size());
                // Native channels use timestamp-keyed objects rather than a keyframes list.
                if (VECTOR_CHANNELS.contains(name) && child.isJsonObject()) {
                    long frames = child.getAsJsonObject().keySet().stream().filter(ModelComplexity::timestamp).count();
                    addFrames(frames);
                }
                boolean nextFormula = formula || FORMULAS.contains(name) || CHANNELS.contains(name)
                        || name.equals("animations") && depth > 0;
                boolean nextOutliner = name.equals("outliner") && !object.has("groups") || outliner && name.equals("children");
                // Outliner context applies only to group objects, not their transform metadata.
                visit(child, nextFormula, nextOutliner, depth + 1);
            }
        } else if (formula && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) expression(value.getAsString());
    }
    private static boolean timestamp(String name) {
        try { return Double.isFinite(Double.parseDouble(name)); } catch (NumberFormatException invalid) { return false; }
    }
    private void expression(String value) throws IOException {
        if (value.isBlank()) return;
        // Numeric coordinate text does not produce a Molang program in either source parser.
        try { if (Double.isFinite(Double.parseDouble(value))) return; } catch (NumberFormatException ignored) { }
        if (value.length() > 32_768 || ++expressions > limits.maxExpressions()) reject("expressions");
        expressionChars += value.length();
        if (expressionChars > limits.maxExpressionChars()) reject("expression_chars");
    }
    private void addBones(long value) throws IOException { bones += value; if (bones > limits.maxBones()) reject("bones"); }
    private void addAnimations(long value) throws IOException { animations += value; if (animations > limits.maxAnimations()) reject("animations"); }
    private void addFrames(long value) throws IOException { keyframes += value; if (keyframes > limits.maxKeyframes()) reject("keyframes"); }
    private static void reject(String detail) throws IOException { throw new IOException("model_complexity:" + detail); }
}
