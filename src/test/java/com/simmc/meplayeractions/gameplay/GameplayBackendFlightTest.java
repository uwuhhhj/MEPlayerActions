package com.simmc.meplayeractions.gameplay;

import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises real backend code through the public Paper interfaces, without a live server. */
class GameplayBackendFlightTest {
    private static final Map<UUID, Player> PLAYERS = new HashMap<>();
    private static final Logger LOGGER = quietLogger();
    private static final PluginManager PLUGIN_MANAGER = proxy(PluginManager.class, (instance, method, args) -> {
        if (method.getName().equals("getPlugin")) return null; // GSit is deliberately optional here.
        throw unexpected(method);
    });
    private static final Server SERVER = proxy(Server.class, (instance, method, args) -> switch (method.getName()) {
        case "isPrimaryThread" -> true;
        case "getPluginManager" -> PLUGIN_MANAGER;
        case "getLogger" -> LOGGER;
        case "getPlayer" -> PLAYERS.get(args[0]);
        default -> throw unexpected(method);
    });
    private static final Plugin PLUGIN = proxy(Plugin.class, (instance, method, args) -> switch (method.getName()) {
        case "getServer" -> SERVER;
        case "getLogger" -> LOGGER;
        default -> throw unexpected(method);
    });

    private GameplayBackend backend;
    private FlightPlayer state;
    private Player player;

    @BeforeEach
    void createBackendWithoutGSit() {
        state = new FlightPlayer();
        player = proxy(Player.class, state);
        PLAYERS.put(state.id, player);
        backend = new GameplayBackend(PLUGIN);
    }

    @AfterEach
    void cleanUpOnlyThisTestsSession() {
        backend.close();
        PLAYERS.remove(state.id);
    }

    @Test
    void survivalGrantRestoresOriginalAbilitiesAndCleanupIsIdempotent() {
        assertFalse(backend.available());
        backend.enableFlight(player, 0.25);
        assertAbilities(true, true, 0.25f);

        backend.cleanup(player);
        assertAbilities(false, false, 0.1f);
        int writesAfterCleanup = state.writes;

        backend.cleanup(player);
        backend.disableOwnedFlight(player);
        assertEquals(writesAfterCleanup, state.writes, "A completed grant must not write again");
    }

    @Test
    void creativePlayersExistingFlightIsPreserved() {
        state.mode = GameMode.CREATIVE;
        state.allowFlight = true;
        state.flying = true;
        state.speed = 0.2f;

        backend.enableFlight(player, 0.4);
        backend.disableOwnedFlight(player);

        assertAbilities(true, true, 0.2f);
    }

    @Test
    void externallyChangedSpeedSurvivesWhileOtherOwnedFieldsAreRestored() {
        backend.enableFlight(player, 0.25);
        // Another plugin has taken ownership of the speed after our grant.
        state.speed = 0.6f;

        backend.disableOwnedFlight(player);

        assertAbilities(false, false, 0.6f);
    }

    @Test
    void gameModeChangeDiscardsTheOldSnapshotEvenIfThePlayerLaterReturns() {
        backend.enableFlight(player, 0.25);
        state.mode = GameMode.CREATIVE;
        state.allowFlight = true;
        state.flying = false;
        state.speed = 0.3f;
        int writesBeforeCleanup = state.writes;

        backend.disableOwnedFlight(player);
        assertAbilities(true, false, 0.3f);
        assertEquals(writesBeforeCleanup, state.writes);

        state.mode = GameMode.SURVIVAL;
        backend.disableOwnedFlight(player);
        assertAbilities(true, false, 0.3f);
        assertEquals(writesBeforeCleanup, state.writes, "Returning to survival must not revive an obsolete snapshot");
    }

    @Test
    void invalidSpeedNeverMutatesOrReplacesAnExistingGrant() {
        backend.enableFlight(player, 0.25);
        int writesBeforeInvalidRequest = state.writes;

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> backend.enableFlight(player, Double.NaN)),
                () -> assertThrows(IllegalArgumentException.class, () -> backend.enableFlight(player, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> backend.enableFlight(player, 1.01))
        );
        assertAbilities(true, true, 0.25f);
        assertEquals(writesBeforeInvalidRequest, state.writes);

        backend.disableOwnedFlight(player);
        assertAbilities(false, false, 0.1f);
    }

    @Test
    void replacingOurGrantStillRestoresTheOriginalSurvivalSnapshot() {
        backend.enableFlight(player, 0.25);
        backend.enableFlight(player, 0.5);
        assertAbilities(true, true, 0.5f);

        backend.disableOwnedFlight(player);

        assertAbilities(false, false, 0.1f);
    }

    @Test
    void aFailedAbilityWriteRollsBackTheFieldsAlreadyChanged() {
        state.failNextSpeedWrite = true;

        assertThrows(IllegalStateException.class, () -> backend.enableFlight(player, 0.25));

        assertAbilities(false, false, 0.1f);
        int writesAfterRollback = state.writes;
        backend.disableOwnedFlight(player);
        assertEquals(writesAfterRollback, state.writes);
    }

    private void assertAbilities(boolean allowFlight, boolean flying, float speed) {
        assertAll(
                () -> assertEquals(allowFlight, state.allowFlight, "allowFlight"),
                () -> assertEquals(flying, state.flying, "flying"),
                () -> assertEquals(speed, state.speed, "flySpeed")
        );
    }

    private static final class FlightPlayer implements InvocationHandler {
        final UUID id = UUID.randomUUID();
        GameMode mode = GameMode.SURVIVAL;
        boolean allowFlight, flying, failNextSpeedWrite;
        float speed = 0.1f;
        int writes;

        @Override
        public Object invoke(Object instance, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "FlightTestPlayer";
                case "isOnline" -> true;
                case "isDead" -> false;
                case "getGameMode" -> mode;
                case "getAllowFlight" -> allowFlight;
                case "isFlying" -> flying;
                case "getFlySpeed" -> speed;
                case "setAllowFlight" -> {
                    writes++;
                    allowFlight = (boolean) args[0];
                    if (!allowFlight) flying = false;
                    yield null;
                }
                case "setFlying" -> {
                    writes++;
                    boolean requested = (boolean) args[0];
                    if (requested && !allowFlight) throw new IllegalArgumentException("Flight was not allowed");
                    flying = requested;
                    yield null;
                }
                case "setFlySpeed" -> {
                    writes++;
                    if (failNextSpeedWrite) {
                        failNextSpeedWrite = false;
                        throw new IllegalStateException("Simulated ability update failure");
                    }
                    speed = (float) args[0];
                    yield null;
                }
                default -> throw unexpected(method);
            };
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (instance, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> instance == args[0];
                    case "hashCode" -> System.identityHashCode(instance);
                    case "toString" -> type.getSimpleName() + "TestDouble";
                    default -> throw unexpected(method);
                };
            }
            return handler.invoke(instance, method, args);
        }));
    }

    private static UnsupportedOperationException unexpected(Method method) {
        return new UnsupportedOperationException("Unexpected API call: " + method);
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setLevel(Level.OFF);
        return logger;
    }
}
