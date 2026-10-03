package com.simmc.meplayeractions.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.simmc.meplayeractions.client.network.ActionPayload;
import com.simmc.meplayeractions.client.render.ModelRenderer;
import com.simmc.meplayeractions.client.ui.ActionsScreen;
import com.simmc.meplayeractions.client.ui.LocalAppearanceScreen;
import com.simmc.meplayeractions.client.ui.AnimationWheelScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.*;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;

public final class MEPlayerActionsClient implements ClientModInitializer {
    public static final Logger LOGGER=LoggerFactory.getLogger("MEPlayerActions/Client");
    public static ClientRuntime runtime;
    @Override public void onInitializeClient() {
        MinecraftClient client=MinecraftClient.getInstance();runtime=new ClientRuntime(client);
        PayloadTypeRegistry.playS2C().register(ActionPayload.ID,ActionPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ActionPayload.ID,ActionPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ActionPayload.ID,(payload,context)->
                context.client().execute(()->runtime.receive(payload.data())));
        ClientPlayConnectionEvents.JOIN.register((handler,sender,mc)->runtime.joined());
        ClientPlayConnectionEvents.DISCONNECT.register((handler,mc)->runtime.reset());
        ModelRenderer.register(runtime);
        KeyBinding menu=KeyBindingHelper.registerKeyBinding(new KeyBinding("key.meplayeractions.menu",InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_N,KeyBinding.Category.create(Identifier.of("meplayeractions","actions"))));
        KeyBinding models=KeyBindingHelper.registerKeyBinding(new KeyBinding("key.meplayeractions.models",InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_Y,menu.getCategory()));
        KeyBinding wheel=KeyBindingHelper.registerKeyBinding(new KeyBinding("key.meplayeractions.wheel",InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,menu.getCategory()));
        ClientTickEvents.END_CLIENT_TICK.register(mc->{runtime.tick();
            while(models.wasPressed())if(mc.world!=null)mc.setScreen(new LocalAppearanceScreen(runtime));
            while(wheel.wasPressed())if(mc.world!=null) {
                var screen=new AnimationWheelScreen(runtime);screen.setReleaseKey(KeyBindingHelper.getBoundKeyOf(wheel).getCode());mc.setScreen(screen);
            }
            while(menu.wasPressed()){
            if(mc.world!=null)mc.setScreen(new ActionsScreen(runtime));
        }});
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,registry)->dispatcher.register(literal("mpaclient")
                .executes(ctx->{client.setScreen(new ActionsScreen(runtime));return 1;})
                .then(literal("settings").executes(ctx->{client.setScreen(new LocalAppearanceScreen(runtime));return 1;}))
                .then(literal("local")
                        .executes(ctx->{client.setScreen(new LocalAppearanceScreen(runtime));return 1;})
                        .then(literal("off").executes(ctx->{runtime.disableLocalAppearance();return 1;}))
                        .then(literal("reset").executes(ctx->{runtime.updateLocalAppearance(LocalAppearanceSettings.defaults());return 1;}))
                        .then(literal("stop").executes(ctx->{runtime.stopLocal();return 1;}))
                        .then(literal("model").then(argument("model",StringArgumentType.greedyString())
                                .suggests((ctx,builder)->{runtime.localModels().forEach(model->builder.suggest(model.id()));return builder.buildFuture();})
                                .executes(ctx->{runtime.selectLocalModel(StringArgumentType.getString(ctx,"model"));return 1;})))
                        .then(literal("play").then(argument("animation",StringArgumentType.word())
                                .suggests((ctx,builder)->{runtime.localActions().forEach(action->builder.suggest(action.id()));return builder.buildFuture();})
                                .executes(ctx->{boolean played=runtime.playLocal(StringArgumentType.getString(ctx,"animation"));
                                    if(!played)ctx.getSource().sendFeedback(Text.literal("先启用本地外观，并选择可用动作"));return played?1:0;}))))
                .then(literal("status").executes(ctx->{runtime.status().forEach(line->ctx.getSource().sendFeedback(Text.literal(line)));return 1;}))
                .then(literal("toggle").executes(ctx->{runtime.toggleEnabled();return 1;}))
                .then(literal("preview").then(argument("model",StringArgumentType.word())
                        .suggests((ctx,builder)->{builder.suggest("ysm_02_jk");builder.suggest("ysm_01_jk");builder.suggest("off");return builder.buildFuture();})
                        .executes(ctx->{runtime.preview(StringArgumentType.getString(ctx,"model"));return 1;})))));
        LOGGER.info("MEPlayerActions Client 0.4.1 initialized for Minecraft 1.21.11 (local bone rendering, protocol 3)");
    }
}
