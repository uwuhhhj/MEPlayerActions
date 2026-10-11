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
        if (method.getName().equals("getPlugin")) {
            return null;
        }
        // GSit is deliberately optional here.
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
        this.state = new FlightPlayer();
        this.player = proxy(Player.class, this.state);
        PLAYERS.put(this.state.id, this.player);
        this.backend = new GameplayBackend(PLUGIN);
    }

    @AfterEach
    void cleanUpOnlyThisTestsSession() {
        this.backend.close();
        PLAYERS.remove(this.state.id);
    }

    @Test
    void survivalGrantRestoresOriginalAbilitiesAndCleanupIsIdempotent() {
        assertFalse(this.backend.available());
        this.backend.enableFlight(this.player, 0.25);
        assertAbilities(true, true, 0.25f);

        this.backend.cleanup(this.player);
        assertAbilities(false, false, 0.1f);
        int writesAfterCleanup = this.state.writes;

        this.backend.cleanup(this.player);
        this.backend.disableOwnedFlight(this.player);
        assertEquals(writesAfterCleanup, this.state.writes, "A completed grant must not write again");
    }

    @Test
    void creativePlayersExistingFlightIsPreserved() {
        this.state.mode = GameMode.CREATIVE;
        this.state.allowFlight = true;
        this.state.flying = true;
        this.state.speed = 0.2f;

        this.backend.enableFlight(this.player, 0.4);
        this.backend.disableOwnedFlight(this.player);

        assertAbilities(true, true, 0.2f);
    }

    @Test
    void externallyChangedSpeedSurvivesWhileOtherOwnedFieldsAreRestored() {
        this.backend.enableFlight(this.player, 0.25);
        // Another plugin has taken ownership of the speed after our grant.
        this.state.speed = 0.6f;

        this.backend.disableOwnedFlight(this.player);

        assertAbilities(false, false, 0.6f);
    }

    @Test
    void gameModeChangeDiscardsTheOldSnapshotEvenIfThePlayerLaterReturns() {
        this.backend.enableFlight(this.player, 0.25);
        this.state.mode = GameMode.CREATIVE;
        this.state.allowFlight = true;
        this.state.flying = false;
        this.state.speed = 0.3f;
        int writesBeforeCleanup = this.state.writes;

        this.backend.disableOwnedFlight(this.player);
        assertAbilities(true, false, 0.3f);
        assertEquals(writesBeforeCleanup, this.state.writes);

        this.state.mode = GameMode.SURVIVAL;
        this.backend.disableOwnedFlight(this.player);
        assertAbilities(true, false, 0.3f);
        assertEquals(writesBeforeCleanup, this.state.writes,
                "Returning to survival must not revive an obsolete snapshot");
    }

    @Test
    void invalidSpeedNeverMutatesOrReplacesAnExistingGrant() {
        this.backend.enableFlight(this.player, 0.25);
        int writesBeforeInvalidRequest = this.state.writes;

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> this.backend.enableFlight(this.player, Double.NaN)),
                () -> assertThrows(IllegalArgumentException.class, () -> this.backend.enableFlight(this.player, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> this.backend.enableFlight(this.player, 1.01)));
        assertAbilities(true, true, 0.25f);
        assertEquals(writesBeforeInvalidRequest, this.state.writes);

        this.backend.disableOwnedFlight(this.player);
        assertAbilities(false, false, 0.1f);
    }

    @Test
    void replacingOurGrantStillRestoresTheOriginalSurvivalSnapshot() {
        this.backend.enableFlight(this.player, 0.25);
        this.backend.enableFlight(this.player, 0.5);
        assertAbilities(true, true, 0.5f);

        this.backend.disableOwnedFlight(this.player);

        assertAbilities(false, false, 0.1f);
    }

    @Test
    void aFailedAbilityWriteRollsBackTheFieldsAlreadyChanged() {
        this.state.failNextSpeedWrite = true;

        assertThrows(IllegalStateException.class, () -> this.backend.enableFlight(this.player, 0.25));

        assertAbilities(false, false, 0.1f);
        int writesAfterRollback = this.state.writes;
        this.backend.disableOwnedFlight(this.player);
        assertEquals(writesAfterRollback, this.state.writes);
    }

    private void assertAbilities(boolean allowFlight, boolean flying, float speed) {
        assertAll(() -> assertEquals(allowFlight, this.state.allowFlight, "allowFlight"),
                () -> assertEquals(flying, this.state.flying, "flying"),
                () -> assertEquals(speed, this.state.speed, "flySpeed"));
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
            case "getUniqueId" -> this.id;
            case "getName" -> "FlightTestPlayer";
            case "isOnline" -> true;
            case "isDead" -> false;
            case "getGameMode" -> this.mode;
            case "getAllowFlight" -> this.allowFlight;
            case "isFlying" -> this.flying;
            case "getFlySpeed" -> this.speed;
            case "setAllowFlight" -> {
                this.writes++;
                this.allowFlight = (boolean) args[0];
                if (!this.allowFlight) {
                    this.flying = false;
                }

                yield null;
            }
            case "setFlying" -> {
                this.writes++;
                boolean requested = (boolean) args[0];
                if (requested && !this.allowFlight) {
                    throw new IllegalArgumentException("Flight was not allowed");
                }

                this.flying = requested;
                yield null;
            }
            case "setFlySpeed" -> {
                this.writes++;
                if (this.failNextSpeedWrite) {
                    this.failNextSpeedWrite = false;
                    throw new IllegalStateException("Simulated ability update failure");
                }
                this.speed = (float) args[0];
                yield null;
            }
            default -> throw unexpected(method);
            };
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(
                Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (instance, method, args) -> {
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
