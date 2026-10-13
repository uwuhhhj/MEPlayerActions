package com.simmc.meplayeractions;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;

/** Isolated dependency checks without loading ModelEngine or starting a server. */
class OptionalModelEngineBootstrapTest {
    @Test
    void descriptorMakesBothIntegrationsOptionalAndNeverGrantsPrivateAssetsByDefault() {
        YamlConfiguration descriptor = YamlConfiguration.loadConfiguration(
                new InputStreamReader(getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8));
        assertTrue(descriptor.getStringList("depend").isEmpty());
        assertTrue(descriptor.getStringList("softdepend").containsAll(List.of("ModelEngine", "GSit")));
        assertFalse(descriptor.getBoolean("permissions.mact.private.upload.default"));
        assertFalse(descriptor.getBoolean("permissions.mact.private.view.default"));
    }

    @Test
    void descriptorDeniesOrdinaryPlayersAndOperatorsByDefault() throws Exception {
        try (InputStreamReader source = new InputStreamReader(getClass().getResourceAsStream("/plugin.yml"),
                StandardCharsets.UTF_8)) {
            PluginDescriptionFile descriptor = new PluginDescriptionFile(source);
            assertEquals("mact.use", descriptor.getCommands().get("meplayeractions").get("permission"));
            assertFalse(descriptor.getPermissions().isEmpty());
            for (Permission permission : descriptor.getPermissions()) {
                assertFalse(permission.getDefault().getValue(false), permission.getName());
                assertFalse(permission.getDefault().getValue(true), permission.getName());
            }
        }
    }

    @Test
    void missingDisabledAndIncompatibleDependenciesSelectThePrivateMode() {
        assertNotNull(MEPlayerActionsPlugin.unavailableReason(false, false, null));
        assertNotNull(MEPlayerActionsPlugin.unavailableReason(true, false, "R4.1.1"));
        assertNotNull(MEPlayerActionsPlugin.unavailableReason(true, true, "R4.0.9"));
        assertNull(MEPlayerActionsPlugin.unavailableReason(true, true, "R4.1.1"));
    }

    @Test
    void bootstrapCommandSignaturesAndPrivatePolicyLoadWithAllModelEngineClassesUnavailable() throws Exception {
        URL classes = MEPlayerActionsPlugin.class.getProtectionDomain().getCodeSource().getLocation();
        try (OptionalModelEngineBootstrapTest.WithoutModelEngine isolated = new WithoutModelEngine(classes,
                getClass().getClassLoader())) {
            Class<?> bootstrap = Class.forName("com.simmc.meplayeractions.MEPlayerActionsPlugin", true, isolated);
            Class<?> privateMode = Class.forName("com.simmc.meplayeractions.server.PrivateOnlyBackend", true, isolated);
            for (Class<?> type : List.of(bootstrap, privateMode)) {
                assertDoesNotThrow(type::getDeclaredConstructors);
                assertDoesNotThrow(type::getDeclaredFields);
                assertDoesNotThrow(type::getDeclaredMethods);
            }
            assertNotNull(bootstrap.getDeclaredMethod("handleAction", Player.class, String[].class));
            List<Method> listeners = Arrays.stream(bootstrap.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(EventHandler.class)).toList();
            assertEquals(1, listeners.size());
            assertArrayEquals(new Class<?>[] { PlayerQuitEvent.class }, listeners.getFirst().getParameterTypes());
            Class<?> policy = Class.forName("com.simmc.meplayeractions.client.PrivateModelSyncService$Policy", true,
                    isolated);
            YamlConfiguration config = new YamlConfiguration();
            config.set("client-sync.enabled", false);
            config.set("client-sync.private-models.enabled", true);
            Object parsed = policy.getMethod("fromConfiguration", ConfigurationSection.class).invoke(null, config);
            assertEquals(false, policy.getMethod("enabled").invoke(parsed));
            assertTrue(isolated.blocked.isEmpty(),
                    "Bootstrap must not even attempt to load a ModelEngine-dependent class: " + isolated.blocked);
        }
    }

    private static final class WithoutModelEngine extends URLClassLoader {
        final List<String> blocked = new ArrayList<>();

        WithoutModelEngine(URL classes, ClassLoader parent) {
            super(new URL[] { classes }, parent);
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("com.ticxo.modelengine.") || name.startsWith("com.simmc.meplayeractions.me.")
                    || name.startsWith("com.simmc.meplayeractions.action.")
                    || name.startsWith("com.simmc.meplayeractions.config.")
                            && !name.equals("com.simmc.meplayeractions.config.PerformanceSettings")
                    || name.startsWith("com.simmc.meplayeractions.gameplay.")
                    || name.startsWith("com.simmc.meplayeractions.ui.")
                    || name.equals("com.simmc.meplayeractions.client.ClientSyncService")) {
                this.blocked.add(name);
                throw new ClassNotFoundException("Optional dependency intentionally unavailable: " + name);
            }
            if (!name.startsWith("com.simmc.meplayeractions.")) {
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
    }
}
