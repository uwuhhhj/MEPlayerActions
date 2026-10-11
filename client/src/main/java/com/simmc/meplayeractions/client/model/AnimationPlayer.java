package com.simmc.meplayeractions.client.model;

import org.joml.Vector3f;
import org.joml.Matrix4f;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import com.simmc.meplayeractions.expression.Molang;

import java.util.*;

/** Per-model-instance playback state. Repeated snapshots preserve the original clock. */
public final class AnimationPlayer
{
    private final BbModel model;
    private final Map<String, State> states = new LinkedHashMap<>();
    private double lastTick = Double.NaN;
    private final Molang.Context expressions = new Molang.Context();
    private boolean initialized;
    private double physicsRemainder;
    private final YsmAnimationController ysm;
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final Map<String, Spring> springs = new HashMap<>();
    private final Map<String, NativeYsmPhysics> nativeSprings = new HashMap<>();
    private Consumer<Molang.Context> configureFrame = context ->
    {
    };
    private BbModel.Pose sampledPose;
    private BbModel.Pose readingPose;
    private double instanceStart = Double.NaN;
    private Molang.FunctionResolver installedResolver;
    private int functionDepth;
    private int eventDepth;
    private boolean lifecycleInitialized;
    private final Map<String, Molang.StructValue> boneValues = new HashMap<>();
    private Consumer<List<Double>> syncListener;
    private boolean applyingSync;
    private final Deque<List<Double>> pendingSync = new ArrayDeque<>();
    private final Map<String, Double> roamingInputs = new HashMap<>();
    private final NativeChannelReset nativeReset;
    private final NativeYsmAnimationProcessor nativeProcessor;
    private BiConsumer<BbModel.Pose, Molang.Context> nativeFallback = (pose, context) ->
    {
    };

    public AnimationPlayer(BbModel model)
    {
        this.model = Objects.requireNonNull(model);
        this.ysm = model.ysmControllers() ? new YsmAnimationController(model) : null;
        this.sampledPose = model.emptyPose();
        this.readingPose = this.sampledPose;
        this.nativeReset = new NativeChannelReset(this.sampledPose.channels.length);
        this.nativeProcessor = new NativeYsmAnimationProcessor(model);
    }

    public void reset()
    {
        if (this.ysm != null)
        {
            this.ysm.reset(this.expressions);
        }
        this.states.clear();
        this.lastTick = Double.NaN;
        this.expressions.clear();
        this.initialized = false;
        this.lifecycleInitialized = false;
        this.physicsRemainder = 0;
        this.groups.clear();
        this.nativeReset.clear();
        this.nativeProcessor.reset();
        this.springs.clear();
        this.nativeSprings.clear();
        this.boneValues.clear();
        this.pendingSync.clear();
        this.roamingInputs.clear();
        this.sampledPose = this.model.emptyPose();
        this.readingPose = this.sampledPose;
        this.instanceStart = Double.NaN;
    }

    /** Disposing a model drops pending author work; it never dispatches effects from the old world. */
    public void dispose()
    {
        reset();
        this.configureFrame = context ->
        {
        };
        this.nativeFallback = (pose, context) ->
        {
        };
        this.expressions.functions(null);
        this.installedResolver = null;
        this.syncListener = null;
    }

    public Map<String, Double> expressionVariables()
    {
        return this.expressions.variables();
    }

    public Map<String, Object> expressionValues()
    {
        return this.expressions.values();
    }

    /** The caller opts in only for the trusted local YSM loading path. */
    public void enableQueryDiagnostics()
    {
        this.expressions.enableDiagnostics();
    }

    public void enableNativeYsm()
    {
        this.expressions.enableNativeYsm();
        this.expressions.enableNativeRoaming();
    }

    public boolean nativeYsm()
    {
        return this.expressions.nativeYsm();
    }

    /** Root consumes this only for its locally owned world-body instance, never GUI/arm/observer instances. */
    public Map<String, Double> consumeRoamingChanges()
    {
        return this.expressions.consumeRoamingChanges();
    }

    /** Installed only for the locally owned private YSM instance when transport is available. */
    public void syncListener(Consumer<List<Double>> listener)
    {
        this.syncListener = listener;
    }

    /** Apply a server-confirmed author event without forwarding it back to the server. */
    public boolean applySync(List<Double> arguments)
    {
        List<Object> values = BbModel.syncArguments(new ArrayList<>(arguments));
        if (values == null || this.eventDepth >= 16)
        {
            return false;
        }

        if (!this.lifecycleInitialized || this.installedResolver == null)
        {
            if (this.pendingSync.size() >= 32)
            {
                return false;
            }

            this.pendingSync.addLast(List.copyOf(arguments));
            return true;
        }
        dispatchSync(values);
        return true;
    }

    private void dispatchSync(List<Object> values)
    {
        boolean previous = this.applyingSync;
        this.applyingSync = true;
        this.eventDepth++;
        try
        {
            Runnable dispatch = () -> this.model.authorEvent("sync", values, this.expressions);
            if (this.ysm == null)
            {
                dispatch.run();
            }
            else
            {
                this.ysm.withoutBuiltinEvent(dispatch);
            }

        }
        finally
        {
            this.eventDepth--;
            this.applyingSync = previous;
        }
    }

    public List<Molang.QueryDiagnostic> expressionDiagnostics()
    {
        return this.expressions.diagnostics();
    }

    /** Explicit UI author scripts use normal native fn/query bindings in a separate variable scope. */
    public Map<String, Double> authorConfiguration(List<String> scripts, Map<String, Double> draftVariables)
    {
        if (scripts.size() > 64)
        {
            throw new IllegalArgumentException("Author configuration script limit");
        }

        Molang.Context scope = configurationContext(draftVariables, true);
        for (String script : scripts)
        {
            Molang.compileNativeYsm(script).evaluateValue(scope);
        }

        return scope.variables();
    }

    /** Reading a form never mutates running variables or sends world effects/network events. */
    public double readConfiguration(String expression, Map<String, Double> draftVariables)
    {
        return Molang.compileNativeYsm(expression).evaluate(configurationContext(draftVariables, false));
    }

    private Molang.Context configurationContext(Map<String, Double> draftVariables, boolean effects)
    {
        if (draftVariables.size() > 4096)
        {
            throw new IllegalArgumentException("Author configuration variable limit");
        }

        Molang.Context scope = new Molang.Context();
        scope.enableNativeYsm();
        scope.frame(Map.of());
        scope.restoreValues(this.expressions.values());
        scope.enableNativeRoaming();
        this.model.initialVariables().forEach((name, value) ->
        {
            if (!scope.has(name))
            {
                scope.set(name, value);
            }
        });
        draftVariables.forEach((name, value) ->
        {
            if (!name.startsWith("variable.") || value == null || !Double.isFinite(value))
            {
                throw new IllegalArgumentException("Author configuration variable");
            }

            scope.set(name, value);
        });
        this.configureFrame.accept(scope);
        Molang.FunctionResolver fallback = scope.functionResolver();
        Map<String, NativeYsmPhysics> physics = new HashMap<>();
        scope.functions((name, args) -> resolve(name, args, fallback, scope, effects, physics));
        installChildBindings(scope, fallback, effects, physics);
        return scope;
    }

    private void seedChangedRoamingInputs(Map<String, Double> parameters)
    {
        this.expressions.enableNativeRoaming();
        Map<String, Double> changed = new LinkedHashMap<>();
        parameters.forEach((key, value) ->
        {
            if (!key.startsWith("variable.roaming."))
            {
                return;
            }

            String name = key.substring("variable.roaming.".length());
            if (name.contains(".") || name.length() > Molang.Context.MAX_ROAMING_VARIABLE_NAME_LENGTH)
            {
                return;
            }

            if (!Objects.equals(this.roamingInputs.get(key), value))
            {
                changed.put(key, value);
            }

            this.roamingInputs.put(key, value);
        });
        this.expressions.seedRoamingValues(changed);
    }

    private static void applyLocalParameters(Molang.Context context, Map<String, Double> parameters)
    {
        parameters.forEach((name, value) ->
        {
            // The source's numeric roaming channel owns its live values; an unchanged saved profile is not a frame override.
            if (!context.nativeYsm() || !name.startsWith("variable.roaming."))
            {
                context.set(name, value);
            }

        });
    }

    private void installChildBindings(Molang.Context parent, Molang.FunctionResolver world, boolean effects,
            Map<String, NativeYsmPhysics> physics)
    {
        parent.childScope(child ->
        {
            Molang.FunctionResolver entity = child.functionResolver();
            Molang.FunctionResolver fallback = (name, args) ->
            {
                boolean worldEffect = Set.of("ysm.play_sound", "ysm.stop_sound", "ysm.stop_all_sounds", "ysm.particle",
                        "ysm.abs_particle").contains(name);
                Molang.FunctionResolver selected = worldEffect ? world : entity;
                return selected == null ? null : selected.call(name, args);
            };
            child.functions((name, args) -> resolve(name, args, fallback, child, effects, physics));
        });
    }

    /** Native bindings are injected after frame inputs, before any author code executes. */
    public void configureFrame(Consumer<Molang.Context> configure)
    {
        this.configureFrame = Objects.requireNonNull(configure);
    }

    /** ImportedVanillaPoseController fallback runs after author ownership is known, before final channel reset. */
    void configureNativeFallback(BiConsumer<BbModel.Pose, Molang.Context> fallback)
    {
        this.nativeFallback = Objects.requireNonNull(fallback);
    }

    public Map<String, Matrix4f> boneTransforms()
    {
        return this.model.boneTransforms(this.sampledPose);
    }

    /** Imported BBModel VANILLA_EQUIPMENT locator chains intentionally omit authored bone scale. */
    public Map<String, Matrix4f> equipmentLocatorTransforms()
    {
        return this.model.equipmentLocatorTransforms(this.sampledPose);
    }

    public boolean hasBone(String name)
    {
        return this.model.hasBone(name);
    }

    public Optional<Matrix4f> boneTransform(String name)
    {
        return Optional.ofNullable(boneTransforms().get(name));
    }

    public List<BbModel.Vertex> filteredVertices(Set<String> selected)
    {
        return this.model.vertices(this.sampledPose, Set.copyOf(selected));
    }

    public Map<String, String> controllerStates()
    {
        return this.ysm == null ? Map.of() : this.ysm.states();
    }

    public List<BbModel.Vertex> sample(double serverTick, List<BbModel.Layer> layers)
    {
        return sample(serverTick, layers, 0, 0);
    }

    public List<BbModel.Vertex> sample(double serverTick, List<BbModel.Layer> layers, float relativeHeadYaw,
            float headPitch)
    {
        return sample(serverTick, layers, relativeHeadYaw, headPitch, Map.of());
    }

    public List<BbModel.Vertex> sample(double serverTick, List<BbModel.Layer> layers, float relativeHeadYaw,
            float headPitch, Map<String, Double> queries)
    {
        return sample(serverTick, layers, relativeHeadYaw, headPitch, queries, Map.of());
    }

    public List<BbModel.Vertex> sample(double serverTick, List<BbModel.Layer> layers, float relativeHeadYaw,
            float headPitch, Map<String, Double> queries, Map<String, Double> accessories)
    {
        return sample(serverTick, layers, relativeHeadYaw, headPitch, queries, accessories, Map.of());
    }

    public List<BbModel.Vertex> sample(double serverTick, List<BbModel.Layer> layers, float relativeHeadYaw,
            float headPitch, Map<String, Double> queries, Map<String, Double> accessories,
            Map<String, Double> localParameters)
    {
        BbModel.checkedTick(serverTick);
        if (!Double.isNaN(this.lastTick) && serverTick < this.lastTick && this.lastTick - serverTick <= 1)

        {
            return sample(this.lastTick, layers, relativeHeadYaw, headPitch, queries, accessories, localParameters);
        }

        if (localParameters.size() > 128)
        {
            throw new IllegalArgumentException("Local parameter count");
        }

        localParameters.forEach((key, value) ->
        {
            if (!key.matches("variable\\.[\\p{L}\\p{N}_]+(?:\\.[\\p{L}\\p{N}_]+)*") || key.length() > 128
                    || value == null || !Double.isFinite(value) || Math.abs(value) > 1_000_000)

            {
                throw new IllegalArgumentException("Local parameter value");
            }

        });
        if (!accessories.isEmpty())
        {
            if (!accessories.keySet().equals(Set.of("a", "b")))
            {
                throw new IllegalArgumentException("Accessory fields");
            }

            for (Double value : accessories.values())

            {
                if (value == null || !Double.isFinite(value) || value < 0 || value > 1)

                {
                    throw new IllegalArgumentException("Accessory range");
                }

            }

        }
        List<BbModel.Layer> incoming = BbModel.ordered(this.model.withParallelLayers(layers));
        for (BbModel.Layer layer : incoming)
        {
            if (!this.model.animations().contains(layer.animation()))

            {
                throw new IllegalArgumentException("Unknown animation: " + layer.animation());
            }

        }
        // A world/clock reset must not blend a pose from the previous connection.
        if (!Double.isNaN(this.lastTick) && serverTick + 1 < this.lastTick)
        {
            reset();
        }

        Map<String, Double> inputs = new HashMap<>(queries);
        // Native local/private models and explicitly marked server YSM exports use the same author angles.
        // Keep raw inputs for automatic look, which is applied separately from authored hair/physics.
        YsmHeadQueries.Angles head = YsmHeadQueries.angles(relativeHeadYaw, headPitch,
                this.expressions.nativeYsm() || this.model.usesYsmHeadQueries());
        inputs.put("ysm.head_yaw", head.yaw());
        inputs.put("ysm.head_pitch", head.pitch());
        // Preserve the upstream QueryBinding aliases (X is yaw; Y is pitch).
        inputs.put("query.head_x_rotation", head.yaw());
        inputs.put("query.head_y_rotation", head.pitch());
        inputs.putIfAbsent("ysm.food_level", 20d);
        inputs.put("query.life_time", serverTick / 20);
        this.expressions.frame(inputs);
        if (Double.isNaN(this.instanceStart))
        {
            this.instanceStart = serverTick;
        }

        if (this.ysm != null)
        {
            this.expressions.query("query.life_time", (serverTick - this.instanceStart) / 20);
        }

        if (this.expressions.functionResolver() == this.installedResolver)
        {
            this.expressions.functions(null);
        }

        this.configureFrame.accept(this.expressions);
        Molang.FunctionResolver nativeResolver = this.expressions.functionResolver();
        this.installedResolver = (name, arguments) -> resolve(name, arguments, nativeResolver);
        this.expressions.functions(this.installedResolver);
        if (this.expressions.nativeYsm())
        {
            installChildBindings(this.expressions, nativeResolver, true, this.nativeSprings);
        }

        if (this.expressions.nativeYsm())
        {
            seedChangedRoamingInputs(localParameters);
        }

        if (!this.lifecycleInitialized)
        {
            this.lifecycleInitialized = true;
            this.model.authorEvent("player_init", List.of(), this.expressions);
        }
        while (!this.pendingSync.isEmpty())
        {
            dispatchSync(new ArrayList<>(this.pendingSync.removeFirst()));
        }

        boolean firstPerson = this.expressions.has("ysm.is_first_person")
                ? this.expressions.get("ysm.is_first_person") != 0
                : (this.expressions.has("query.is_first_person") ? this.expressions.get("query.is_first_person") != 0
                        : Set.of("fp.arm", "fp_arm").contains(this.model.controllerFamily()));
        this.model.authorEvent("player_update", List.of(firstPerson), this.expressions);
        for (BbModel.Layer layer : incoming)
        {
            if (!this.expressions.has("ctrl." + layer.animation()))
            {
                this.expressions.query("ctrl." + layer.animation(), 1);
            }

        }

        this.expressions.query("ctrl.playing_extra_animation",
                incoming.stream().anyMatch(layer -> layer.layer().equals("manual")) ? 1 : 0);
        advanceFunctionPhysics(serverTick);
        if (this.ysm != null)
        {
            applyLocalParameters(this.expressions, localParameters);
            if (!accessories.isEmpty())
            {
                this.expressions.set("variable.roaming.a", accessories.get("a"));
                this.expressions.set("variable.roaming.b", accessories.get("b"));
            }
            return sampleYsm(serverTick, incoming, relativeHeadYaw, headPitch, accessories, localParameters);
        }
        if (!this.initialized)
        {
            this.model.initializePhysics(this.expressions);
            this.initialized = true;
        }
        this.model.helperInputs(serverTick, incoming, this.expressions);
        // The source physics uses a 10 ms integration step. Bound catch-up after pauses.
        this.physicsRemainder += Double.isNaN(this.lastTick) ? 0
                : Math.max(0, Math.min(.25, (serverTick - this.lastTick) / 20));
        while (this.physicsRemainder + 1e-9 >= .01)
        {
            this.model.stepPhysics(this.expressions);
            this.physicsRemainder -= .01;
        }
        this.lastTick = serverTick;
        for (BbModel.Layer layer : incoming)
        {
            State state = this.states.get(layer.layer());
            double before = state == null || !layer.equals(state.layer) ? layer.startedAtTick() - 1e-5
                    : state.eventTick;
            this.model.events(layer, before, serverTick, this.expressions);
        }
        // A late viewer can replay an old toggle here. The server's resulting values win before any pose sampling.
        if (!accessories.isEmpty())
        {
            this.expressions.set("variable.roaming.a", accessories.get("a"));
            this.expressions.set("variable.roaming.b", accessories.get("b"));
        }
        applyLocalParameters(this.expressions, localParameters);
        Set<String> present = new HashSet<>();
        for (BbModel.Layer layer : incoming)
        {
            present.add(layer.layer());
            State state = this.states.get(layer.layer());
            if (state == null)
            {
                state = new State(layer, empty(), layer.startedAtTick(), layer.inTicks());
                this.states.put(layer.layer(), state);
            }
            else if (!layer.equals(state.layer))
            {
                Contribution previous = state.at(serverTick);
                int duration = Math.max(layer.inTicks(), state.layer == null ? 0 : state.layer.outTicks());
                state.previous = previous;
                state.layer = layer;
                state.changedAt = Math.max(serverTick, layer.startedAtTick());
                state.duration = duration;
            }
            state.eventTick = serverTick;
        }
        for (Map.Entry<String, State> entry : this.states.entrySet())
        {
            State state = entry.getValue();
            if (!present.contains(entry.getKey()) && state.layer != null)
            {
                state.previous = state.at(serverTick);
                state.duration = state.layer.outTicks();
                state.layer = null;
                state.changedAt = serverTick;
            }
        }
        BbModel.Pose combined = this.model.emptyPose();
        double[] headWeights = this.model.defaultHeadWeights();
        List<Map.Entry<String, State>> ordered = new ArrayList<>(this.states.entrySet());
        ordered.sort(Comparator.comparingInt(entry -> BbModel.priority(entry.getKey())));
        for (Map.Entry<String, State> entry : ordered)
        {
            Contribution contribution = entry.getValue().at(serverTick);
            apply(combined, contribution, BbModel.additive(entry.getKey()));
            if (entry.getKey().equalsIgnoreCase("manual"))
            {
                for (int i = 0; i < headWeights.length; i++)

                {
                    if (contribution.pose.channels[i][1] != null)
                    {
                        headWeights[i] *= 1 - BbModel.clamp(contribution.weights[i][1]);
                    }

                }

            }
        }
        this.states.entrySet().removeIf(entry -> entry.getValue().layer == null
                && serverTick >= entry.getValue().changedAt + entry.getValue().duration);
        if (incoming.stream().noneMatch(layer -> this.model.ownsHeadLook(layer.animation())))
        {
            this.model.applyLook(combined, relativeHeadYaw, headPitch, headWeights);
        }

        this.sampledPose = combined.copy();
        this.readingPose = this.sampledPose;
        return this.model.vertices(combined);
    }

    private List<BbModel.Vertex> sampleYsm(double tick, List<BbModel.Layer> incoming, float yaw, float pitch,
            Map<String, Double> accessories, Map<String, Double> localParameters)
    {
        this.lastTick = tick;
        BbModel.Pose combined = this.model.emptyPose();
        this.readingPose = this.sampledPose;
        if (this.expressions.nativeYsm())
        {
            this.nativeProcessor.beginFrame();
        }

        double[] headWeights = this.model.defaultHeadWeights();
        boolean[] authoredLook =
        { false };
        this.ysm.sample(tick, incoming, this.expressions, (frame, context) ->
        {
            BbModel.Pose target = this.model.emptyPose();
            List<NativeYsmAnimationProcessor.Contribution> nativeSources = new ArrayList<>();
            for (YsmAnimationController.Run run : frame.runs())
            {
                this.ysm.withPlayback(run.playback(), false, context, () ->
                {
                    if (run.events())
                    {
                        this.ysm.withCapture(run.active(), () -> this.model.ysmEvents(run.animation(), run.playback(),
                                run.elapsed(), false, run.active(), context));
                    }

                    // Saved user fields and authoritative server accessories win over first-frame author defaults.
                    applyLocalParameters(context, localParameters);
                    if (!accessories.isEmpty())
                    {
                        context.set("variable.roaming.a", accessories.get("a"));
                        context.set("variable.roaming.b", accessories.get("b"));
                    }
                    if (this.expressions.nativeYsm())
                    {
                        NativeYsmAnimationProcessor.Contribution contribution = this.nativeProcessor.prepare(run,
                                context, this.sampledPose);
                        if (contribution != null)
                        {
                            nativeSources.add(contribution);
                        }

                        if (run.active())
                        {
                            authoredLook[0] |= this.model.ownsHeadLook(run.animation());
                        }

                    }
                    else if (run.active())
                    {
                        BbModel.Evaluated evaluated = this.model.evaluateClip(run.animation(), run.elapsed(),
                                run.loop(), context);
                        // Multiple clips in the same state sum position/rotation and multiply weighted scale.
                        addState(target, evaluated.pose(), evaluated.weight());
                        authoredLook[0] |= this.model.ownsHeadLook(run.animation());
                        if (!this.expressions.nativeYsm())
                        {
                            this.readingPose = previewPose(combined, target, frame.deprecated());
                        }

                    }
                });
            }
            if (this.expressions.nativeYsm())
            {
                this.nativeProcessor.apply(combined, nativeSources, frame, this.ysm);
                if (frame.cap())
                {
                    BbModel.Pose owned = this.model.emptyPose();
                    this.nativeProcessor.apply(owned, nativeSources, frame, this.ysm);
                    this.model.suppressManualLook(headWeights, owned, frame.blend().fraction(tick - frame.changedAt()));
                }
                return;
            }
            Group group = this.groups.get(frame.name());
            if (group == null)
            {
                group = new Group(frame.revision(), this.model.emptyPose(), frame.changedAt(), frame.blend());
                this.groups.put(frame.name(), group);
            }
            else if (group.revision != frame.revision())
            {
                group.previous = group.at(tick);
                group.revision = frame.revision();
                group.changedAt = frame.changedAt();
                group.blend = frame.blend();
            }
            group.current = target;
            if (frame.suppressed())
            {
                return;
            }

            BbModel.Pose pose = group.at(tick);
            applyGroup(combined, pose, frame.deprecated());
            if (frame.cap())
            {
                this.model.suppressManualLook(headWeights, pose, frame.blend().fraction(tick - frame.changedAt()));
            }

            if (!this.expressions.nativeYsm())
            {
                this.readingPose = combined;
            }

        }, context ->
        {
            applyLocalParameters(context, localParameters);
            if (!accessories.isEmpty())
            {
                context.set("variable.roaming.a", accessories.get("a"));
                context.set("variable.roaming.b", accessories.get("b"));
            }
        });
        if (this.expressions.nativeYsm())
        {
            this.nativeProcessor.finishFrame();
            this.nativeFallback.accept(combined, this.expressions);
            this.nativeReset.complete(combined, this.sampledPose, tick);
        }
        if (!authoredLook[0])
        {
            this.model.applyLook(combined, yaw, pitch, headWeights);
        }

        this.sampledPose = combined.copy();
        this.readingPose = this.sampledPose;
        return this.model.vertices(combined);
    }

    /** Upstream PhysicsManager advances an instance once before sampling its author animation expressions. */
    private void advanceFunctionPhysics(double tick)
    {
        double delta = Double.isNaN(this.lastTick) ? 0 : Math.max(0, Math.min(.25, (tick - this.lastTick) / 20));
        this.springs.values().forEach(spring -> spring.update(delta));
        // Preserve the source's initial positive-tick guard and each native spring's own interval rules.
        if (!Double.isNaN(this.lastTick) && this.lastTick > 0 && tick > this.lastTick)

        {
            this.nativeSprings.values().forEach(spring -> spring.update((float) ((tick - this.lastTick) / 20)));
        }

    }

    private static void addState(BbModel.Pose target, BbModel.Pose clip, double weight)
    {
        for (int i = 0; i < target.channels.length; i++)
        {
            for (int c = 0; c < 3; c++)
            {
                Vector3f value = clip.channels[i][c];
                if (value == null)
                {
                    continue;
                }

                Vector3f base = target.channels[i][c];
                if (base == null)
                {
                    base = BbModel.defaultValue(c);
                }

                target.channels[i][c] = c == 2 ? new Vector3f(base).mul(new Vector3f(1).lerp(value, (float) weight))
                        : new Vector3f(base).add(new Vector3f(value).mul((float) weight));
            }
        }

    }

    private static BbModel.Pose previewPose(BbModel.Pose base, BbModel.Pose top, boolean deprecated)
    {
        BbModel.Pose result = base.copy();
        applyGroup(result, top, deprecated);
        return result;
    }

    private static void applyGroup(BbModel.Pose target, BbModel.Pose top, boolean deprecated)
    {
        for (int i = 0; i < target.channels.length; i++)
        {
            for (int c = 0; c < 3; c++)
            {
                Vector3f value = top.channels[i][c];
                if (value == null)
                {
                    continue;
                }

                Vector3f base = target.channels[i][c];
                target.channels[i][c] = deprecated && c == 1 && base != null ? new Vector3f(base).add(value)
                        : new Vector3f(value);
            }
        }

    }

    private final class Group
    {
        long revision;
        BbModel.Pose previous, current;
        double changedAt;
        YsmAnimationController.Blend blend;

        Group(long revision, BbModel.Pose previous, double changedAt, YsmAnimationController.Blend blend)
        {
            this.revision = revision;
            this.previous = previous;
            this.current = AnimationPlayer.this.model.emptyPose();
            this.changedAt = changedAt;
            this.blend = blend;
        }

        BbModel.Pose at(double tick)
        {
            double fraction = BbModel.clamp(this.blend.fraction(tick - this.changedAt));
            if (fraction >= 1)
            {
                return this.current.copy();
            }

            BbModel.Pose result = AnimationPlayer.this.model.emptyPose();
            for (int i = 0; i < result.channels.length; i++)
            {
                for (int c = 0; c < 3; c++)
                {
                    Vector3f a = this.previous.channels[i][c], b = this.current.channels[i][c];
                    if (a == null && b == null)
                    {
                        continue;
                    }

                    if (a == null)
                    {
                        a = BbModel.defaultValue(c);
                    }
                    if (b == null)
                    {
                        b = BbModel.defaultValue(c);
                    }

                    result.channels[i][c] = c == 1 ? rotationBlend(a, b, (float) fraction)
                            : new Vector3f(a).lerp(b, (float) fraction);
                }
            }

            return result;
        }
    }

    /** Port of OpenYSM 0306e1f MathUtil.nlerpEulerAngles: include initial Euler rotation before quaternion mixing. */
    private Vector3f nativeRotationBlend(Vector3f from, Vector3f to, int bone, float fraction)
    {
        return NativeYsmAnimationProcessor.rotation(this.model, bone, from, to, fraction);
    }

    /** Port of AnimationProcessor's final BoneTopLevelSnapshot reset, separate from every controller's transition. */
    private final class NativeChannelReset
    {
        final double[][] mostRecent;
        final Vector3f[][] previous;

        NativeChannelReset(int bones)
        {
            this.mostRecent = new double[bones][3];
            this.previous = new Vector3f[bones][3];
            clear();
        }

        void clear()
        {
            for (int i = 0; i < this.mostRecent.length; i++)
            {
                Arrays.fill(this.mostRecent[i], Double.NaN);
                Arrays.fill(this.previous[i], null);
            }
        }

        void complete(BbModel.Pose current, BbModel.Pose last, double tick)
        {
            for (int i = 0; i < current.channels.length; i++)
            {
                for (int c = 0; c < 3; c++)
                {
                    if (current.channels[i][c] != null)
                    {
                        this.mostRecent[i][c] = tick;
                        this.previous[i][c] = null;
                        continue;
                    }
                    if (Double.isNaN(this.mostRecent[i][c]))
                    {
                        continue;
                    }

                    if (this.previous[i][c] == null)
                    {
                        this.previous[i][c] = last.channels[i][c] == null ? BbModel.defaultValue(c)
                                : new Vector3f(last.channels[i][c]);
                    }

                    float reset = (float) BbModel.clamp((tick - this.mostRecent[i][c]) / 3);
                    current.channels[i][c] = c == 1
                            ? nativeRotationBlend(this.previous[i][c], BbModel.defaultValue(c), i, reset)
                            : new Vector3f(this.previous[i][c]).lerp(BbModel.defaultValue(c), reset);
                }
            }

        }
    }

    private static Vector3f rotationBlend(Vector3f a, Vector3f b, float t)
    {
        // Quaternion normalized interpolation avoids the +/-180-degree transition discontinuity.
        org.joml.Quaternionf qa = new org.joml.Quaternionf().rotationZYX((float) Math.toRadians(a.z),
                (float) Math.toRadians(a.y), (float) Math.toRadians(a.x));
        org.joml.Quaternionf qb = new org.joml.Quaternionf().rotationZYX((float) Math.toRadians(b.z),
                (float) Math.toRadians(b.y), (float) Math.toRadians(b.x));
        Vector3f euler = qa.nlerp(qb, t).getEulerAnglesZYX(new Vector3f());
        return euler.mul((float) (180 / Math.PI));
    }

    private Object resolve(String name, List<Object> args, Molang.FunctionResolver fallback)
    {
        return resolve(name, args, fallback, this.expressions, true, this.nativeSprings);
    }

    private Object resolve(String name, List<Object> args, Molang.FunctionResolver fallback, Molang.Context target,
            boolean effects, Map<String, NativeYsmPhysics> physics)
    {
        if (!effects && Set.of("ysm.sync", "ysm.defer", "ysm.play_sound", "ysm.stop_sound", "ysm.stop_all_sounds",
                "ysm.particle", "ysm.abs_particle").contains(name))
        {
            return target.nativeYsm() ? null : 0d;
        }

        if (name.equals("ysm.defer"))
        {
            return this.ysm == null ? null : this.ysm.defer(args);
        }

        if (name.equals("ysm.sync"))
        {
            List<Object> values = BbModel.syncArguments(args);
            if (values == null || this.eventDepth >= 16 || this.applyingSync)
            {
                return target.nativeYsm() ? null : 0d;
            }

            if (this.syncListener != null && target.nativeYsm())
            {
                this.syncListener.accept(values.stream().map(value -> ((Number) value).doubleValue()).toList());
                return null;
            }
            this.eventDepth++;
            try
            {
                Runnable dispatch = () -> this.model.authorEvent("sync", values, target);
                if (this.ysm == null)
                {
                    dispatch.run();
                }
                else
                {
                    this.ysm.withoutBuiltinEvent(dispatch);
                }

            }
            finally
            {
                this.eventDepth--;
            }
            return target.nativeYsm() ? null : 0d;
        }
        if (name.startsWith("fn."))
        {
            Molang.Program program = this.model.authorFunctions().get(name.substring(3));
            if (program == null || args.size() > 32 || this.functionDepth >= (target.nativeYsm() ? 32 : 16))
            {
                return target.nativeYsm() ? null : 0d;
            }

            this.functionDepth++;
            try
            {
                return BbModel.callAuthorFunction(target, program, args);
            }
            finally
            {
                this.functionDepth--;
            }
        }
        if (name.equals("ysm.first_order") || name.equals("ysm.second_order"))
        {
            if (args.size() < 2 || !(args.get(0) instanceof String key) || key.isEmpty() || key.length() > 128)
            {
                return 0d;
            }

            if (target.nativeYsm())
            {
                boolean second = name.equals("ysm.second_order");
                String scoped = second && target.physicsScope() != null ? target.physicsScope() + "\0" + key : key;
                NativeYsmPhysics spring = physics.get(scoped);
                float input = nativeFloat(args.get(1));
                boolean created = spring == null;
                if (created)
                {
                    if (physics.size() >= 256)
                    {
                        return (double) input;
                    }

                    spring = new NativeYsmPhysics(second);
                    physics.put(scoped, spring);
                }
                float frequency = second && args.size() > 2 ? nativeFloat(args.get(2)) : 1;
                float damping = second && args.size() > 3 ? nativeFloat(args.get(3)) : 1;
                float response = args.size() > (second ? 4 : 2) ? nativeFloat(args.get(second ? 4 : 2)) : 1;
                spring.args(input, second ? frequency : response, second ? damping : 0, second ? response : 0);
                return (double) (created ? input : spring.value());
            }
            Spring spring = this.springs.get(key);
            double input = safeNumber(args.get(1), 36_000);
            if (spring == null)
            {
                if (this.springs.size() >= 256)
                {
                    return input;
                }

                spring = new Spring(name.equals("ysm.second_order"));
                this.springs.put(key, spring);
                spring.args(args, input);
                return input;
            }
            spring.args(args, input);
            return spring.value;
        }
        if (Set.of("ysm.bone_rot", "ysm.bone_pos", "ysm.bone_scale", "ysm.bone_pivot_abs").contains(name))
        {
            String bone = args.isEmpty() ? "" : String.valueOf(args.get(0));
            int channel = switch (name)
            {
            case "ysm.bone_rot" -> 1;
            case "ysm.bone_scale" -> 2;
            case "ysm.bone_pivot_abs" -> 3;
            default -> 0;
            };
            if (target.nativeYsm())
            {
                if (args.size() != 1 || !(args.get(0) instanceof String) || !this.model.hasBone(bone))
                {
                    return null;
                }

                return this.boneValues.computeIfAbsent(channel + ":" + bone, key -> new LiveBoneValue(bone, channel));
            }
            Vector3f value = this.model.boneValue(bone, channel, this.readingPose);
            if (channel == 1)
            {
                value.x = -value.x;
                value.y = -value.y;
            }

            return new Molang.VectorValue(value.x, value.y, value.z);
        }
        if (name.startsWith("ctrl.") && this.ysm != null)
        {
            return this.ysm.function(name, args, fallback);
        }

        if (Set.of("ctrl.set_animation", "ctrl.set_beginning_transition_length", "ctrl.reset", "ctrl.indicate_reload")
                .contains(name))
        {
            return 0d;
        }

        if (Set.of("ysm.play_sound", "ysm.stop_sound", "ysm.stop_all_sounds", "ysm.particle", "ysm.abs_particle")
                .contains(name))
        {
            if (effects && target.nativeYsm() && this.ysm != null && name.equals("ysm.play_sound"))
            {
                this.ysm.soundStarted(args);
            }

            return fallback == null ? 0d : fallback.call(name, args);
        }
        return fallback == null ? 0d : fallback.call(name, args);
    }

    private static float nativeFloat(Object value)
    {
        double number = value == null ? 0
                : (value instanceof Number n ? n.doubleValue() : (value instanceof Boolean b ? (b ? 1 : 0) : 1));
        float result = (float) number;
        return Float.isFinite(result) ? result : 0;
    }

    /** Like upstream Vec3fStruct.copy(), a captured bone remains a live readonly bone handle. */
    private final class LiveBoneValue implements Molang.StructValue
    {
        private final String bone;
        private final int channel;

        private LiveBoneValue(String bone, int channel)
        {
            this.bone = bone;
            this.channel = channel;
        }

        public Object getProperty(String name)
        {
            if (!Set.of("x", "y", "z").contains(name))
            {
                return null;
            }

            Vector3f value = AnimationPlayer.this.model.boneValue(this.bone, this.channel,
                    AnimationPlayer.this.readingPose);
            if (this.channel == 1)
            {
                value.x = -value.x;
                value.y = -value.y;
            }
            return (double) switch (name)
            {
            case "x" -> value.x;
            case "y" -> value.y;
            default -> value.z;
            };
        }

        public void putProperty(String name, Object value)
        {
        }

        public Molang.StructValue copy()
        {
            return this;
        }

        public String toString()
        {
            return "vec3{x=" + getProperty("x") + ", y=" + getProperty("y") + ", z=" + getProperty("z") + "}";
        }
    }

    private static double safeNumber(Object value, double bound)
    {
        double number = value instanceof Number n ? n.doubleValue() : 0;
        return Double.isFinite(number) ? Math.max(-bound, Math.min(bound, number)) : 0;
    }

    private static final class Spring
    {
        final boolean secondOrder;
        double input, frequency = 1, damping = 1, response = 1, value, velocity, previousInput;

        Spring(boolean secondOrder)
        {
            this.secondOrder = secondOrder;
        }

        void args(List<Object> args, double input)
        {
            this.input = input;
            if (this.secondOrder)
            {
                this.frequency = args.size() > 2 ? Math.max(0, Math.min(5, safeNumber(args.get(2), 5))) : 1;
                this.damping = args.size() > 3 ? Math.max(0, Math.min(1, safeNumber(args.get(3), 1))) : 1;
                this.response = args.size() > 4 ? safeNumber(args.get(4), 100) : 1;
            }
            else
            {
                this.response = args.size() > 2 ? Math.max(.001, safeNumber(args.get(2), 100)) : 1;
            }

        }

        void update(double dt)
        {
            if (dt <= 0)
            {
                return;
            }

            if (!this.secondOrder)
            {
                this.value += Math.min(1, dt / this.response) * (this.input - this.value);
                return;
            }
            if (this.frequency <= 0)
            {
                return;
            }

            double k1 = this.damping / Math.PI / this.frequency, k2 = 1 / Math.pow(2 * Math.PI * this.frequency, 2);
            double k3 = this.response * this.damping / (2 * Math.PI * this.frequency);
            double derivative = (this.input - this.previousInput) / dt;
            this.previousInput = this.input;
            double maxStep = Math.sqrt(4 * k2 + k1 * k1) - k1;
            int cycles = Math.max(1, Math.min(256, (int) Math.ceil(dt / maxStep)));
            double step = dt / cycles;
            for (int i = 0; i < cycles; i++)
            {
                this.value += step * this.velocity;
                this.velocity += step * (k3 * derivative + this.input - this.value - k1 * this.velocity) / k2;
            }
            if (!Double.isFinite(this.value) || !Double.isFinite(this.velocity))
            {
                this.value = this.input;
                this.velocity = 0;
            }
            this.value = Math.max(-36_000, Math.min(36_000, this.value));
            this.velocity = Math.max(-1e6, Math.min(1e6, this.velocity));
        }
    }

    private Contribution empty()
    {
        return new Contribution(this.model.emptyPose());
    }

    private final class State
    {
        BbModel.Layer layer;
        Contribution previous;
        double changedAt;
        int duration;
        double eventTick;

        State(BbModel.Layer layer, Contribution previous, double changedAt, int duration)
        {
            this.layer = layer;
            this.previous = previous;
            this.changedAt = changedAt;
            this.duration = duration;
            this.eventTick = layer.startedAtTick() - 1e-5;
        }

        Contribution at(double tick)
        {
            Contribution current = empty();
            if (this.layer != null)
            {
                BbModel.Evaluated target = AnimationPlayer.this.model.evaluate(tick, this.layer, false,
                        AnimationPlayer.this.expressions);
                current = new Contribution(target.pose());
                for (int i = 0; i < current.pose.channels.length; i++)
                {
                    for (int c = 0; c < 3; c++)

                    {
                        if (current.pose.channels[i][c] != null)
                        {
                            current.weights[i][c] = target.weight();
                        }

                    }

                }

            }
            double t = this.duration == 0 ? (tick >= this.changedAt ? 1 : 0)
                    : BbModel.clamp((tick - this.changedAt) / this.duration);
            if (t >= 1)
            {
                return current;
            }

            return blend(this.previous, current, t);
        }
    }

    private static final class Contribution
    {
        final BbModel.Pose pose;
        final double[][] weights;

        Contribution(BbModel.Pose pose)
        {
            this.pose = pose;
            this.weights = new double[pose.channels.length][3];
        }
    }

    private static Contribution blend(Contribution a, Contribution b, double fraction)
    {
        Contribution result = new Contribution(new BbModel.Pose(a.pose.channels.length));
        for (int i = 0; i < a.pose.channels.length; i++)
        {
            for (int c = 0; c < 3; c++)
            {
                double left = (1 - fraction) * a.weights[i][c], right = fraction * b.weights[i][c];
                double total = left + right;
                if (total <= 0)
                {
                    continue;
                }

                Vector3f av = a.pose.channels[i][c], bv = b.pose.channels[i][c];
                if (av == null)
                {
                    av = BbModel.defaultValue(c);
                }

                if (bv == null)
                {
                    bv = BbModel.defaultValue(c);
                }

                result.pose.channels[i][c] = new Vector3f(av).mul((float) (left / total))
                        .add(new Vector3f(bv).mul((float) (right / total)));
                result.weights[i][c] = total;
            }
        }

        return result;
    }

    private static void apply(BbModel.Pose below, Contribution top, boolean additive)
    {
        for (int i = 0; i < below.channels.length; i++)
        {
            for (int c = 0; c < 3; c++)
            {
                Vector3f value = top.pose.channels[i][c];
                if (value == null || top.weights[i][c] <= 0)
                {
                    continue;
                }

                Vector3f base = below.channels[i][c];
                if (base == null)
                {
                    base = BbModel.defaultValue(c);
                }

                below.channels[i][c] = BbModel.combine(base, value, c, (float) BbModel.clamp(top.weights[i][c]),
                        additive);
            }
        }

    }
}
