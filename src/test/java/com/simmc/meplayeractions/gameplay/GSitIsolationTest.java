package com.simmc.meplayeractions.gameplay;

import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class GSitIsolationTest {
    private final List<LogRecord> warnings = new ArrayList<>();
    private final UUID playerId = UUID.randomUUID();
    private Plugin plugin;
    private Plugin dependency;
    private Player player;
    private FakeGSit gsit;
    private boolean dependencyEnabled = true;
    private String version = "3.5.1";
    private Pose nativePose = Pose.STANDING;
    private boolean allowFlight;
    private boolean flying;
    private float flySpeed = 0.1f;

    @BeforeEach
    void setup() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    GSitIsolationTest.this.warnings.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        this.dependency = proxy(Plugin.class, (instance, method, args) -> switch (method.getName()) {
            case "isEnabled" -> this.dependencyEnabled;
            case "getDescription" -> new PluginDescriptionFile("GSit", this.version, "unused.GSit");
            default -> throw new UnsupportedOperationException(method.getName());
        });
        PluginManager manager = proxy(PluginManager.class, (instance, method, args) -> switch (method.getName()) {
            case "getPlugin" -> this.dependency;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        Server server = proxy(Server.class, (instance, method, args) -> switch (method.getName()) {
            case "getPluginManager" -> manager;
            case "isPrimaryThread" -> true;
            case "getPlayer" -> this.player;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        this.plugin = proxy(Plugin.class, (instance, method, args) -> switch (method.getName()) {
            case "getServer" -> server;
            case "getLogger" -> logger;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        this.player = proxy(Player.class, (instance, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> this.playerId;
            case "getName" -> "IsolationPlayer";
            case "isOnline" -> true;
            case "isDead", "isInsideVehicle", "isInWater", "isGliding", "isSleeping" -> false;
            case "getVehicle" -> null;
            case "getPose" -> this.nativePose;
            case "getGameMode" -> GameMode.SURVIVAL;
            case "getAllowFlight" -> this.allowFlight;
            case "isFlying" -> this.flying;
            case "getFlySpeed" -> this.flySpeed;
            case "setAllowFlight" -> {
                this.allowFlight = (boolean) args[0];
                yield null;
            }
            case "setFlying" -> {
                this.flying = (boolean) args[0];
                yield null;
            }
            case "setFlySpeed" -> {
                this.flySpeed = (float) args[0];
                yield null;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
        this.gsit = new FakeGSit();
    }

    @ParameterizedTest
    @ValueSource(strings = { "absent", "disabled", "4.0.0" })
    void unavailableDependenciesNeverConstructTheAdapter(String state) {
        if (state.equals("absent")) {
            this.dependency = null;
        } else if (state.equals("disabled")) {
            this.dependencyEnabled = false;
        } else {
            this.version = state;
        }
        try (GameplayBackend backend = new GameplayBackend(this.plugin, dependency -> {
            fail("Unavailable GSit must not load its adapter");
            return null;
        })) {
            assertFalse(backend.available());
            assertNativeFallback(backend);
            assertThrows(IllegalStateException.class, () -> backend.sit(this.player));
            backend.enableFlight(this.player, 0.3);
            backend.cleanup(this.player);
            assertFalse(this.flying);
            assertFalse(this.allowFlight);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void initializationRuntimeAndLinkageFailuresAreIsolated(boolean linkage) {
        try (GameplayBackend backend = new GameplayBackend(this.plugin, dependency -> {
            failCall(linkage);
            return this.gsit;
        })) {
            assertFalse(backend.available());
            assertNativeFallback(backend);
            assertEquals(1, this.warnings.size());
        }
    }

    @Test
    void anotherThreeXVersionKeepsPublicApiButNeverStartsPrivateHooks() {
        this.version = "3.6.0";
        this.gsit.pose = new Object();
        try (GameplayBackend backend = backend()) {
            assertTrue(backend.available());
            assertNull(this.gsit.onFailure);
            backend.syncPoseReplica(this.player, true);
            assertEquals(0, this.gsit.calls.getOrDefault("pose", 0));
            assertTrue(this.warnings.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "seat", "crawl", "poseType", "anchor", "enabled", "pose" })
    void runtimeSamplingFailuresFallBackWithoutRetrying(String operation) {
        try (GameplayBackend backend = backend()) {
            this.gsit.failures.put(operation, () -> {
                throw new IllegalStateException("Broken GSit service");
            });
            if (operation.equals("anchor")) {
                assertNull(backend.visualAnchor(this.player));
            } else if (operation.equals("pose")) {
                backend.syncPoseReplica(this.player, true);
            } else {
                assertDoesNotThrow(() -> backend.observe(this.player));
            }
            assertFalse(backend.available());
            int calls = this.gsit.calls.get(operation);
            assertNativeFallback(backend);
            assertEquals(calls, this.gsit.calls.get(operation));
            int warningsBeforeRepeat = this.warnings.size();
            assertNativeFallback(backend);
            assertEquals(warningsBeforeRepeat, this.warnings.size());
        }
    }

    @Test
    void lateMissingMethodDoesNotEscapeOrBlockFlightCleanup() {
        try (GameplayBackend backend = backend()) {
            backend.enableFlight(this.player, 0.3);
            this.gsit.failures.put("poseType", () -> {
                throw new NoSuchMethodError("GSit API changed");
            });
            assertDoesNotThrow(() -> backend.observe(this.player));
            assertFalse(backend.available());
            backend.cleanup(this.player);
            assertFalse(this.flying);
            assertFalse(this.allowFlight);
            assertEquals(0.1f, this.flySpeed);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "create", "start" })
    void mutationLinkageFailureProducesAUserMessageAndDisablesOnlyGSit(String operation) {
        try (GameplayBackend backend = backend()) {
            this.gsit.failures.put(operation, () -> {
                throw new NoSuchMethodError("GSit mutation changed");
            });
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
                if (operation.equals("create")) {
                    backend.sit(this.player);
                } else {
                    backend.crawl(this.player);
                }
            });
            assertTrue(failure.getMessage().contains("其他模型动作仍可使用"));
            assertFalse(backend.available());
            backend.enableFlight(this.player, 0.3);
            assertTrue(this.flying);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "remove", "stop" })
    void ownedPostureCleanupFailureDoesNotPreventOtherCleanup(String operation) {
        try (GameplayBackend backend = backend()) {
            if (operation.equals("remove")) {
                backend.sit(this.player);
            } else {
                backend.crawl(this.player);
            }
            this.gsit.failures.put(operation, () -> {
                throw new NoClassDefFoundError("Missing GSit implementation");
            });
            assertDoesNotThrow(() -> backend.cleanup(this.player));
            assertFalse(backend.available());
            backend.enableFlight(this.player, 0.3);
            backend.cleanup(this.player);
            assertFalse(this.flying);
        }
    }

    @Test
    void cancelledCreationDoesNotDisableAHealthyBackend() {
        this.gsit.cancelCreation = true;
        try (GameplayBackend backend = backend()) {
            assertThrows(IllegalStateException.class, () -> backend.sit(this.player));
            assertTrue(backend.available());
            assertTrue(this.warnings.isEmpty());
        }
    }

    @Test
    void disablingDependencyAtRuntimeStopsCallingItsObjects() {
        try (GameplayBackend backend = backend()) {
            backend.sit(this.player);
            this.dependencyEnabled = false;
            assertDoesNotThrow(() -> backend.cleanup(this.player));
            assertFalse(backend.available());
            assertEquals(0, this.gsit.calls.getOrDefault("remove", 0));
            assertNativeFallback(backend);
        }
    }

    @Test
    void privateHookFailureDoesNotDisablePublicPostureSampling() {
        this.gsit.pose = new Object();
        try (GameplayBackend backend = backend()) {
            backend.syncPoseReplica(this.player, true);
            assertTrue(backend.available());
            assertTrue(backend.observe(this.player).sleeping());
            int warnings = this.warnings.size();
            backend.syncPoseReplica(this.player, true);
            assertEquals(1, this.gsit.calls.get("pose"));
            assertEquals(warnings, this.warnings.size());
            assertTrue(backend.diagnosis().contains("假玩家隐藏适配已因故障停用"));
        }
    }

    @Test
    void lifecycleRegistrationAndCallbackFailuresStayInsidePrivateAdapter() {
        this.gsit.failures.put("register", () -> {
            throw new NoClassDefFoundError("Pose stop event missing");
        });
        try (GameplayBackend backend = backend()) {
            assertTrue(backend.available());
            assertNativeFallback(backend);
        }
        this.gsit.failures.clear();
        try (GameplayBackend backend = backend()) {
            assertDoesNotThrow(() -> this.gsit.onFailure.accept(new NoSuchMethodError("Event accessor changed")));
            assertTrue(backend.available());
            assertNativeFallback(backend);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void actualBootstrapLoadsAndRunsWithEveryGSitClassUnavailable(boolean installed) throws Exception {
        if (!installed) {
            this.dependency = null;
        }
        URL classes = GameplayBackend.class.getProtectionDomain().getCodeSource().getLocation();
        List<String> blocked = new ArrayList<>();
        try (URLClassLoader isolated = new URLClassLoader(new URL[] { classes }, getClass().getClassLoader()) {
            @Override
            protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("dev.geco.gsit.")) {
                    blocked.add(name);
                    throw new ClassNotFoundException(name);
                }
                if (!name.startsWith("com.simmc.meplayeractions.gameplay.")) {
                    return super.loadClass(name, resolve);
                }
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    type = findClass(name);
                }
                if (resolve) {
                    resolveClass(type);
                }
                return type;
            }
        }) {
            Class<?> type = Class.forName(GameplayBackend.class.getName(), true, isolated);
            assertDoesNotThrow(type::getDeclaredMethods);
            assertDoesNotThrow(type::getDeclaredFields);
            Object backend = type.getConstructor(Plugin.class).newInstance(this.plugin);
            assertEquals(false, type.getMethod("available").invoke(backend));
            type.getMethod("syncPoseReplica", Player.class, boolean.class).invoke(backend, this.player, true);
            type.getMethod("enableFlight", Player.class, double.class).invoke(backend, this.player, 0.3);
            type.getMethod("close").invoke(backend);
            assertFalse(this.flying);
            if (installed) {
                assertFalse(blocked.isEmpty(), "Incompatible installed GSit must exercise actual adapter linkage");
                assertEquals(1, this.warnings.size());
            } else {
                assertTrue(blocked.isEmpty(), "Absent dependency must not even attempt GSit class loading: " + blocked);
            }
        }
    }

    private GameplayBackend backend() {
        return new GameplayBackend(this.plugin, dependency -> this.gsit);
    }

    private void assertNativeFallback(GameplayBackend backend) {
        this.nativePose = Pose.SWIMMING;
        assertTrue(backend.observe(this.player).crawling());
        this.nativePose = Pose.SLEEPING;
        assertTrue(backend.observe(this.player).sleeping());
        assertNull(backend.visualAnchor(this.player));
        assertFalse(backend.isSitting(this.player));
        assertFalse(backend.isCrawling(this.player));
        assertDoesNotThrow(() -> backend.syncPoseReplica(this.player, true));
        this.nativePose = Pose.STANDING;
    }

    private static void failCall(boolean linkage) {
        if (linkage) {
            throw new NoSuchMethodError("Incompatible GSit API");
        }
        throw new IllegalStateException("Broken GSit service");
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                (instance, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "hashCode" -> System.identityHashCode(instance);
                            case "equals" -> instance == args[0];
                            case "toString" -> type.getSimpleName();
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    }
                    return handler.invoke(instance, method, args);
                }));
    }

    private final class FakeGSit implements GSitAccess {
        private final Map<String, Runnable> failures = new HashMap<>();
        private final Map<String, Integer> calls = new HashMap<>();
        private Object seat;
        private Object crawl;
        private Object pose;
        private boolean cancelCreation;
        private Consumer<Throwable> onFailure;

        private void call(String operation) {
            this.calls.merge(operation, 1, Integer::sum);
            Runnable failure = this.failures.get(operation);
            if (failure != null) {
                failure.run();
            }
        }

        @Override
        public boolean enabled() {
            call("enabled");
            return GSitIsolationTest.this.dependencyEnabled;
        }

        @Override
        public Object createSeat(Player player) {
            call("create");
            this.seat = this.cancelCreation ? null : new Object();
            return this.seat;
        }

        @Override
        public Object seat(Player player) {
            call("seat");
            return this.seat;
        }

        @Override
        public boolean removeSeat(Object seat, boolean useSafeDismount) {
            call("remove");
            assertSame(this.seat, seat);
            this.seat = null;
            return true;
        }

        @Override
        public Object startCrawl(Player player) {
            call("start");
            this.crawl = new Object();
            return this.crawl;
        }

        @Override
        public Object crawl(Player player) {
            call("crawl");
            return this.crawl;
        }

        @Override
        public boolean stopCrawl(Object crawl) {
            call("stop");
            assertSame(this.crawl, crawl);
            this.crawl = null;
            return true;
        }

        @Override
        public Object pose(Player player) {
            call("pose");
            return this.pose;
        }

        @Override
        public String poseType(Player player) {
            call("poseType");
            return this.pose == null ? "" : "LAY";
        }

        @Override
        public GSitAnchor visualAnchor(Player player) {
            call("anchor");
            return null;
        }

        @Override
        public void registerPoseLifecycle(Plugin plugin, Listener listener, BiConsumer<Player, Object> beforeRemoval,
                Consumer<Throwable> onFailure) {
            call("register");
            this.onFailure = onFailure;
        }
    }
}
