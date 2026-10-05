package com.simmc.meplayeractions.client.model;

import com.google.gson.*;
import com.simmc.meplayeractions.expression.Molang;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class YsmAnimationControllerTest {
    @Test void orderedTransitionsRunExitThenEntryExactlyOnceAndPreserveRepeatedFrame() {
        JsonObject raw = fixture(); clip(raw, "idle", 0, "LOOP", "position", "0"); clip(raw, "active", 0, "LOOP", "position", "16");
        controllers(raw, """
                {"player.post_main":{"initial_state":"idle","states":{
                  "idle":{"animations":["idle"],"on_entry":["v.entries += 1;"],"on_exit":["v.order = 2;"],
                     "transitions":[{"active":"q.move"},{"wrong":"q.move"}]},
                  "active":{"animations":["active"],"on_entry":["v.order = v.order*10+3;v.entries += 1;"]},
                  "wrong":{"animations":["idle"]}}}}
                """);
        AnimationPlayer player = new AnimationPlayer(parse(raw)); player.sample(0, List.of(), 0, 0, Map.of("query.move", 0d));
        assertEquals(1d, player.expressionVariables().get("variable.entries"), 1e-5);
        assertEquals(2, x(player.sample(1, List.of(), 0, 0, Map.of("query.move", 1d))), 1e-5);
        assertEquals(23d, player.expressionVariables().get("variable.order"), 1e-5);
        player.sample(1, List.of(), 0, 0, Map.of("query.move", 1d));
        assertEquals(2d, player.expressionVariables().get("variable.entries"), 1e-5);
        assertEquals("active", player.controllerStates().get("player.post_main"));
    }
    @Test void emptyEntriesChainInOneFrameButCyclesAreBounded() {
        JsonObject raw = fixture(); clip(raw, "idle", 0, "LOOP", "position", "16");
        controllers(raw, """
                {"player.post_main":{"states":{
                 "default":{"on_entry":["v.n += 1;"],"transitions":[{"b":"1"}]},
                 "b":{"on_entry":["v.n += 1;"],"transitions":[{"c":"1"}]},
                 "c":{"animations":["idle"],"on_entry":["v.n += 1;"]}}}}
                """);
        AnimationPlayer player = new AnimationPlayer(parse(raw)); assertEquals(2, x(player.sample(0, List.of())), 1e-5);
        assertEquals(3d, player.expressionVariables().get("variable.n"), 1e-5);
        JsonObject cycle = fixture(); controllers(cycle, """
                {"player.post_main":{"states":{"default":{"transitions":[{"b":"1"}]},"b":{"transitions":[{"default":"1"}]}}}}
                """);
        assertDoesNotThrow(() -> new AnimationPlayer(parse(cycle)).sample(0, List.of()));
    }
    @Test void conditionsAreBooleanClipWeightsAreNumericAndSameStateScaleMultiplies() {
        JsonObject raw = fixture(); clip(raw, "a", 0, "LOOP", "position", "8"); clip(raw, "b", 0, "LOOP", "position", "8");
        raw.getAsJsonArray("animations").get(1).getAsJsonObject().addProperty("blend_weight", "2");
        clip(raw, "c", 0, "LOOP", "scale", "2"); clip(raw, "d", 0, "LOOP", "scale", "3");
        controllers(raw, """
                {"player.post_main":{"states":{"default":{"animations":[{"a":"0.25"},"b","c","d"]}}}}
                """);
        AnimationPlayer player = new AnimationPlayer(parse(raw)); assertEquals(7.5, x(player.sample(0, List.of())), 1e-5);
        assertEquals(6, player.boneTransform("bone").orElseThrow().m00(), 1e-5);
    }
    @Test void transitionHoldsClipTimeZeroThenStartsClockAndUsesAuthoredBlendCurve() {
        JsonObject raw = fixture(); clip(raw, "a", 0, "LOOP", "position", "0"); clip(raw, "b", 2, "HOLD", "position", "q.anim_time*16");
        controllers(raw, """
                {"player.post_main":{"states":{
                 "default":{"animations":["a"],"transitions":[{"b":"q.move"}]},
                 "b":{"animations":["b"],"blend_transition":{"0":1,"0.5":0.75,"1":0}}}}}
                """);
        AnimationPlayer player = new AnimationPlayer(parse(raw)); player.sample(0, List.of());
        player.sample(1, List.of(), 0, 0, Map.of("query.move", 1d));
        assertEquals(1, x(player.sample(11, List.of())), 1e-5);
        assertEquals(1, x(player.sample(21, List.of())), 1e-5);
        assertEquals(1.5, x(player.sample(31, List.of())), 1e-5);
    }
    @Test void finishedQueriesArePerControllerAndLoopFinishedIsSticky() {
        JsonObject raw = fixture(); clip(raw, "a", 1, "LOOP", "position", "0"); clip(raw, "b", 0, "LOOP", "position", "16");
        controllers(raw, """
                {"player.post_main":{"states":{
                 "default":{"animations":["a"],"transitions":[{"b":"q.all_animations_finished"}]},
                 "b":{"animations":["b"]}}}}
                """);
        AnimationPlayer player = new AnimationPlayer(parse(raw)); player.sample(0, List.of()); player.sample(20, List.of());
        player.sample(21, List.of());
        assertEquals(2, x(player.sample(22, List.of())), 1e-5);
        assertEquals("b", player.controllerStates().get("player.post_main"));
    }
    @Test void contextVariablesAreIsolatedButEntityVariablesAreSharedAndResetWithClock() {
        JsonObject raw = fixture(); clip(raw, "a", 0, "LOOP", "position", "c.amount");
        controllers(raw, """
                {"player.pre_main":{"states":{"default":{"on_entry":["c.amount=16;v.n+=1;"],"animations":["a"]}}},
                 "player.post_main":{"states":{"default":{"on_entry":["v.other=c.amount;v.n+=1;"]}}}}
                """);
        AnimationPlayer player = new AnimationPlayer(parse(raw)); player.sample(10, List.of());
        assertEquals(0d, player.expressionVariables().get("variable.other"), 1e-5);
        assertEquals(2d, player.expressionVariables().get("variable.n"), 1e-5);
        player.sample(1, List.of()); assertEquals(2d, player.expressionVariables().get("variable.n"), 1e-5);
    }
    @Test void parallelRotationAddsButPositionOverridesAndMainDoesNotDiscardNativeHandSlot() {
        JsonObject raw = fixture(); clip(raw, "idle", 0, "LOOP", "position", "16"); clip(raw, "hold", 0, "LOOP", "position", "32");
        clip(raw, "parallel0", 0, "LOOP", "position", "48");
        AnimationPlayer player = new AnimationPlayer(parse(raw));
        assertEquals(4, x(player.sample(0, List.of(layer("posture", "idle", 0), layer("player.hold_mainhand", "hold", 0)))), 1e-5);
    }
    @Test void timelineRunsOncePerCycleAndPauseSuppressesContributionWithoutRewindingClock() {
        JsonObject raw = fixture(); clip(raw, "idle", 1, "LOOP", "position", "q.anim_time*16"); timeline(raw, "idle", 0, "v.events += 1;");
        AnimationPlayer player = new AnimationPlayer(parse(raw)); List<BbModel.Layer> layers = List.of(layer("player.hold_mainhand", "idle", 0));
        player.sample(0, layers); player.sample(0, layers); assertEquals(1d, player.expressionVariables().get("variable.events"), 1e-5);
        assertEquals(1, x(player.sample(10, layers, 0, 0, Map.of("ysm.pause.player.hold_mainhand", 1d))), 1e-5);
        assertEquals(1.75, x(player.sample(15, layers)), 1e-5);
        player.sample(20, layers); assertEquals(1d, player.expressionVariables().get("variable.events"), 1e-5);
        player.sample(21, layers); assertEquals(2d, player.expressionVariables().get("variable.events"), 1e-5);
    }
    @Test void physicsNameIdentityAndModelInstancesRemainIndependent() {
        JsonObject raw = fixture(); clip(raw, "parallel0", 0, "LOOP", "position", "ysm.second_order('same', q.target, 1, 0.5, 0)");
        AnimationPlayer a = new AnimationPlayer(parse(raw)), b = new AnimationPlayer(parse(raw));
        a.sample(0, List.of(), 0, 0, Map.of("query.target", 16d)); b.sample(0, List.of(), 0, 0, Map.of("query.target", 0d));
        for (int i=1;i<=100;i++) { a.sample(i, List.of(), 0, 0, Map.of("query.target", 16d)); b.sample(i, List.of(), 0, 0, Map.of("query.target", 0d)); }
        assertEquals(2, x(a.sample(101, List.of(), 0, 0, Map.of("query.target", 16d))), .01);
        assertEquals(1, x(b.sample(101, List.of(), 0, 0, Map.of("query.target", 0d))), 1e-5);
    }
    @Test void basisAndDefaultsNeverExecuteEntryOrWorldFunctionsAndTransformsAreCopies() {
        JsonObject raw = fixture(); clip(raw, "parallel1", 0, "LOOP", "position", "0"); timeline(raw, "parallel1", 0, "v.roaming.a = v.roaming.a ?? 1;ysm.play_sound('test');");
        controllers(raw, """ 
                {"player.post_main":{"states":{"default":{"on_entry":["v.entry=1;ysm.play_sound('test');"]}}}}
                """);
        BbModel model = parse(raw); assertEquals(1d, model.initialVariables().get("variable.roaming.a"), 1e-5);
        assertFalse(model.initialVariables().containsKey("variable.entry")); assertEquals(1, x(model.basisVertices()), 1e-5);
        AnimationPlayer player = new AnimationPlayer(model); player.sample(0, List.of());
        player.boneTransform("bone").orElseThrow().translation(100,100,100);
        assertEquals(0, player.boneTransform("bone").orElseThrow().m30(), 1e-5);
        assertEquals(model.basisVertices(), player.filteredVertices(Set.of("bone")));
    }
    @Test void nestedEntryControllersResolveOnlyBoundedNamedChildren() {
        JsonObject raw = fixture(); clip(raw, "a", 0, "LOOP", "position", "16");
        controllers(raw, """
             {"player.post_main":{"initial_state":"ysm-entry-inner","states":{"ysm-entry-inner":{}}},
              "player.post_main.inner":{"states":{"default":{"animations":["a"],"on_entry":["v.child+=1;"]}}}}
             """);
        AnimationPlayer player = new AnimationPlayer(parse(raw)); assertEquals(2, x(player.sample(0,List.of())), 1e-5);
        player.sample(1,List.of()); assertEquals(1d, player.expressionVariables().get("variable.child"), 1e-5);
    }
    @Test void controllerEventSetAnimationDoesNotRestartEveryFrameAndDelegatesWorldEffects() {
        JsonObject raw = fixture(); clip(raw,"a",2,"HOLD","position","q.anim_time*16");
        raw.add("ysm_events",JsonParser.parseString("{\"player_ctrl_post_main\":\"ctrl.set_animation('a',12);return 2;\"}"));
        timeline(raw,"a",0,"ysm.play_sound('safe:event');");
        List<String> effects = new ArrayList<>(); AnimationPlayer player = new AnimationPlayer(parse(raw));
        player.configureFrame(context -> context.functions((name,args) -> { effects.add(name); return 0d; }));
        assertEquals(1,x(player.sample(0,List.of())),1e-5);
        assertEquals(1.5,x(player.sample(10,List.of())),1e-5);
        assertEquals(2,x(player.sample(20,List.of())),1e-5);
        assertEquals(List.of("ysm.play_sound"),effects);
    }
    @Test void onetimeClipsFinishAndFadeRatherThanHoldingLastPose() {
        JsonObject raw=fixture();clip(raw,"a",1,"ONCE","position","16");
        AnimationPlayer player=new AnimationPlayer(parse(raw));
        List<BbModel.Layer> layers=List.of(new BbModel.Layer("manual","a",0,1,"ONCE",0,4));
        assertEquals(2,x(player.sample(0,layers)),1e-5);
        // OpenYSM Instance ends ONCE at >=length and uses its fixed three-tick ending transition.
        assertEquals(2,x(player.sample(20,layers)),1e-5);
        assertEquals(1+2d/3,x(player.sample(21,layers)),1e-5);
        assertEquals(1+1d/3,x(player.sample(22,layers)),1e-5);
        assertEquals(1,x(player.sample(23,layers)),1e-5);
    }
    @Test void unspecifiedConstantLengthRemainsUnfinishedUntilExplicitStop() {
        JsonObject raw=fixture();clip(raw,"constant",0,"ONCE","position","16");
        raw.getAsJsonArray("animations").get(0).getAsJsonObject().addProperty("ysm_infinite",true);
        controllers(raw,"""
           {"player.post_main":{"states":{"default":{"animations":["constant"],"transitions":[{"ended":"q.all_animations_finished"}]},"ended":{}}}}
           """);
        BbModel model=parse(raw);assertEquals(Double.POSITIVE_INFINITY,model.animationLengthTicks("constant"));
        AnimationPlayer player=new AnimationPlayer(model);player.sample(0,List.of());
        assertEquals(2,x(player.sample(1000,List.of())),1e-5);
        assertEquals("default",player.controllerStates().get("player.post_main"));
    }
    @Test void hiddenBoneMatricesAreExcludedWhileStaticShoulderBasisRemainsAvailable() {
        JsonObject raw=fixture();raw.getAsJsonArray("outliner").get(0).getAsJsonObject().addProperty("visibility",false);
        BbModel model=parse(raw);AnimationPlayer player=new AnimationPlayer(model);player.sample(0,List.of());
        assertTrue(player.boneTransform("bone").isEmpty()); assertTrue(model.basisBoneTransforms().containsKey("bone"));
    }
    @Test void invalidStateTargetsAndOversizedControllerGraphsAreRejected() {
        JsonObject raw = fixture(); controllers(raw,"{\"player.main\":{\"states\":{\"default\":{\"transitions\":[{\"missing\":\"1\"}]}}}}");
        assertThrows(IllegalArgumentException.class, () -> parse(raw));
        JsonObject definitions = new JsonObject(); for (int i=0;i<65;i++) definitions.add("player.parallel_"+i, JsonParser.parseString("{\"states\":{\"default\":{}}}"));
        assertThrows(IllegalArgumentException.class, () -> YsmAnimationController.parse(definitions));
    }
    @Test void firstEventAloneControlsBuiltinAndBypassRestoresTheNativePredicate() {
        JsonObject raw=fixture();clip(raw,"script",2,"HOLD","position","q.anim_time*16");clip(raw,"native",0,"LOOP","position","16");
        event(raw,"player_ctrl_post_main","q.mode==0 ? ctrl.set_animation('script',12) : 0;return q.mode==0 ? 2 : 5;",
                "v.secondExecuted=1;return 3;");
        AnimationPlayer player=new AnimationPlayer(parse(raw));List<BbModel.Layer> nativeLayers=List.of(layer("player.post_main","native",0));
        assertEquals(1,x(player.sample(0,nativeLayers)),1e-5);
        assertEquals(1.5,x(player.sample(10,nativeLayers)),1e-5);
        assertEquals(2,x(player.sample(11,nativeLayers,0,0,Map.of("query.mode",1d))),1e-5);
        assertEquals(1,x(player.sample(12,nativeLayers)),1e-5);
        assertFalse(player.expressionVariables().containsKey("variable.secondexecuted"));
    }
    @Test void continueDoesNotSelectNewNativeAnimationAndPausePreservesTheClock() {
        JsonObject raw=fixture();clip(raw,"a",2,"HOLD","position","q.anim_time*16");clip(raw,"b",0,"LOOP","position","32");
        event(raw,"player_ctrl_post_main","return q.mode==1 ? 2 : q.mode==2 ? 4 : 5;");
        AnimationPlayer player=new AnimationPlayer(parse(raw));List<BbModel.Layer> a=List.of(layer("player.post_main","a",0)),b=List.of(layer("player.post_main","b",0));
        player.sample(0,a);
        assertEquals(1.5,x(player.sample(10,b,0,0,Map.of("query.mode",1d))),1e-5);
        assertEquals(1,x(player.sample(12,b,0,0,Map.of("query.mode",2d))),1e-5);
        assertEquals(1.75,x(player.sample(15,b,0,0,Map.of("query.mode",1d))),1e-5);
        assertEquals(3,x(player.sample(16,b)),1e-5);
        JsonObject noSelection=fixture();clip(noSelection,"a",0,"LOOP","position","16");event(noSelection,"player_ctrl_post_main","return 2;");
        assertEquals(1,x(new AnimationPlayer(parse(noSelection)).sample(0,List.of(layer("player.post_main","a",0)))),1e-5);
    }
    @Test void stopBeginsEndingTransitionWhileResetIsImmediateAndPreservesDefinitionVariables() {
        JsonObject stop=fixture();clip(stop,"a",0,"LOOP","position","16");event(stop,"player_ctrl_post_main","return q.stop ? 3 : 5;");
        AnimationPlayer stopped=new AnimationPlayer(parse(stop));List<BbModel.Layer> nativeLayers=List.of(new BbModel.Layer("player.post_main","a",0,1,"LOOP",0,4));
        stopped.sample(0,nativeLayers);stopped.sample(1,nativeLayers,0,0,Map.of("query.stop",1d));
        assertEquals(1+1d/3,x(stopped.sample(3,nativeLayers,0,0,Map.of("query.stop",1d))),1e-5);
        assertEquals(1,x(stopped.sample(4,nativeLayers,0,0,Map.of("query.stop",1d))),1e-5);
        JsonObject reset=fixture();clip(reset,"a",2,"HOLD","position","q.anim_time*16");
        controllers(reset,"{\"player.post_main\":{\"initial_state\":\"ysm-builtin\",\"states\":{\"ysm-builtin\":{\"on_entry\":[\"v.entries+=1;\"]}}}}");
        event(reset,"player_ctrl_post_main","c.saved+=1;v.saved=c.saved;q.mode==0 ? ctrl.set_animation('a',12) : ctrl.reset();return 2;");
        AnimationPlayer player=new AnimationPlayer(parse(reset));player.sample(0,List.of());assertEquals(1.5,x(player.sample(10,List.of())),1e-5);
        assertEquals(1,x(player.sample(11,List.of(),0,0,Map.of("query.mode",1d))),1e-5);
        // First IDLE activation flushes and clears instance c storage before the new animation runs.
        assertEquals(2d,player.expressionVariables().get("variable.saved"),1e-5);
        assertEquals(1d,player.expressionVariables().get("variable.entries"),1e-5);
        assertEquals("ysm-builtin",player.controllerStates().get("player.post_main"));
    }
    @Test void indicateReloadLeavesCurrentClockUntilNextMatchingSetRequestRestarts() {
        JsonObject raw=fixture();clip(raw,"a",2,"HOLD","position","q.anim_time*16");
        event(raw,"player_ctrl_post_main","q.mode==1 ? ctrl.indicate_reload() : ctrl.set_animation('a',12);return 2;");
        AnimationPlayer player=new AnimationPlayer(parse(raw));player.sample(0,List.of());
        assertEquals(1.5,x(player.sample(10,List.of(),0,0,Map.of("query.mode",1d))),1e-5);
        assertEquals(1,x(player.sample(11,List.of())),1e-5);
        assertEquals(1.05,x(player.sample(12,List.of())),1e-5);
    }
    @Test void requestDedupIncludesExplicitLoopOverrideAndInvalidNamesCancelCurrent() {
        JsonObject raw=fixture();clip(raw,"a",2,"HOLD","position","q.anim_time*16");
        event(raw,"player_ctrl_post_main","q.mode==0 ? ctrl.set_animation('a') : q.mode==1 ? ctrl.set_animation('a',12) : ctrl.set_animation('missing',12);return 2;");
        AnimationPlayer player=new AnimationPlayer(parse(raw));player.sample(0,List.of());assertEquals(1.5,x(player.sample(10,List.of())),1e-5);
        assertEquals(1,x(player.sample(11,List.of(),0,0,Map.of("query.mode",1d))),1e-5);
        assertEquals(1.05,x(player.sample(12,List.of(),0,0,Map.of("query.mode",1d))),1e-5);
        assertEquals(1,x(player.sample(13,List.of(),0,0,Map.of("query.mode",2d))),1e-5);
    }
    @Test void controllerMutationsInEntryTimelineAndNonbuiltinEventsAreInert() {
        JsonObject raw=fixture();clip(raw,"a",0,"LOOP","position","16");clip(raw,"b",0,"LOOP","position","32");
        controllers(raw,"{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[\"a\"],\"on_entry\":[\"ctrl.set_animation('b',10);ctrl.reset();\"]}}}}");
        event(raw,"player_ctrl_post_main","v.eventExecuted=1;ctrl.set_animation('b',10);return 2;");
        timeline(raw,"a",0,"ctrl.reset();ctrl.set_animation('b',10);ctrl.indicate_reload();");
        List<String> delegated=new ArrayList<>();AnimationPlayer player=new AnimationPlayer(parse(raw));
        player.configureFrame(context->context.functions((name,args)->{delegated.add(name);return 0d;}));
        assertEquals(2,x(player.sample(0,List.of())),1e-5);assertEquals(2,x(player.sample(10,List.of())),1e-5);
        assertFalse(player.expressionVariables().containsKey("variable.eventexecuted"));assertTrue(delegated.isEmpty());
    }
    @Test void namedFunctionsUseTypedZeroBasedArgsAndIsolateNestedTempsButPersistVariables() {
        JsonObject raw=fixture();clip(raw,"parallel0",0,"LOOP","position","v.result");
        functions(raw,"""
                {"Outer":"t.x=99;v.inner=fn.inner('text',args[0]);v.outer=args[0];v.restoredTemp=t.x;c.calls+=1;v.calls=c.calls;return args[0]*16;",
                 "inner":"v.text=args[0];v.nestedTemp=t.x;t.x=5;return args[1];"}
                """);
        timeline(raw,"parallel0",0,"t.x=3;v.result=fn.outer(2);v.callerTemp=t.x;v.argLeaks=args[0];");
        AnimationPlayer player=new AnimationPlayer(parse(raw));assertEquals(3,x(player.sample(0,List.of())),1e-5);
        assertEquals("text",player.expressionValues().get("variable.text"));
        assertEquals(2d,player.expressionVariables().get("variable.inner"),1e-5);
        assertEquals(0d,player.expressionVariables().get("variable.nestedtemp"),1e-5);
        assertEquals(99d,player.expressionVariables().get("variable.restoredtemp"),1e-5);
        assertEquals(3d,player.expressionVariables().get("variable.callertemp"),1e-5);
        assertEquals(0d,player.expressionVariables().get("variable.argleaks"),1e-5);
        assertEquals(1d,player.expressionVariables().get("variable.calls"),1e-5);
        AnimationPlayer separate=new AnimationPlayer(parse(raw));separate.sample(0,List.of());assertEquals(1d,separate.expressionVariables().get("variable.calls"),1e-5);
    }
    @Test void recursiveFunctionsAreBoundedAndCallsShareTheFrameExecutionBudget() {
        JsonObject raw=fixture();clip(raw,"parallel0",0,"LOOP","position","0");
        functions(raw,"{\"recurse\":\"v.n+=1;return fn.recurse();\"}");timeline(raw,"parallel0",0,"v.result=fn.recurse();");
        AnimationPlayer player=new AnimationPlayer(parse(raw));assertDoesNotThrow(()->player.sample(0,List.of()));
        assertEquals(16d,player.expressionVariables().get("variable.n"),1e-5);
        JsonObject expensive=fixture();clip(expensive,"parallel0",0,"LOOP","position","0");
        functions(expensive,"{\"work\":\"loop(1024,v.count+=1);\"}");timeline(expensive,"parallel0",0,"loop(1024,fn.work());");
        IllegalArgumentException failure=assertThrows(IllegalArgumentException.class,()->new AnimationPlayer(parse(expensive)).sample(0,List.of()));
        assertTrue(failure.getMessage().contains("Evaluation budget"));
    }
    @Test void functionsReturnTypedStringsAndVectorsWithoutExposingHostObjects() {
        JsonObject raw=fixture();clip(raw,"parallel0",0,"LOOP","position","fn.identity(ysm.bone_scale('bone')).x*16");
        functions(raw,"{\"identity\":\"return args[0];\"}");timeline(raw,"parallel0",0,"v.label=fn.identity('label');");
        AnimationPlayer player=new AnimationPlayer(parse(raw));assertEquals(2,x(player.sample(0,List.of())),1e-5);
        assertEquals("label",player.expressionValues().get("variable.label"));
    }
    @Test void functionDefaultsRemainInertAndDefinitionsRejectAmbiguityAndOversizedInput() {
        JsonObject raw=fixture();clip(raw,"parallel0",0,"LOOP","position","0");functions(raw,"{\"defaultBow\":\"ysm.play_sound('ignored');return 1;\"}");
        timeline(raw,"parallel0",0,"v.bow=fn.defaultBow();ctrl.set_animation('missing');");
        assertEquals(1d,parse(raw).initialVariables().get("variable.bow"),1e-5);
        functions(raw,"{\"印度格挡（格挡连招控制器）\":\"return 0;\"}");assertDoesNotThrow(()->parse(raw));
        functions(raw,"{\"Foo\":\"return 1;\",\"foo\":\"return 2;\"}");assertThrows(IllegalArgumentException.class,()->parse(raw));
        JsonObject tooMany=new JsonObject();for(int i=0;i<65;i++)tooMany.addProperty("f"+i,"return 0;");raw.add("ysm_functions",tooMany);assertThrows(IllegalArgumentException.class,()->parse(raw));
        JsonObject tooLong=new JsonObject();tooLong.addProperty("f"," ".repeat(32769));raw.add("ysm_functions",tooLong);assertThrows(IllegalArgumentException.class,()->parse(raw));
        functions(raw,"{\"f\":\"return 0;\"}");timeline(raw,"parallel0",0,"fn.f("+String.join(",",Collections.nCopies(33,"1"))+");");
        assertThrows(IllegalArgumentException.class,()->parse(raw));
    }
    @Test void lifecycleRunsAllInitProgramsOnceAndAllUpdateProgramsOnEverySampleAfterReset() {
        JsonObject raw=fixture();clip(raw,"a",0,"LOOP","position","16");
        event(raw,"player_init","v.init+=1;v.order=1;ysm.play_sound('init');ctrl.reset();","v.order=v.order*10+2;");
        event(raw,"player_update","v.updates+=1;v.fp=args[0];","v.order=v.order*10+3;");
        List<String> effects=new ArrayList<>();AnimationPlayer player=new AnimationPlayer(parse(raw));
        player.configureFrame(context->context.functions((name,args)->{effects.add(name);return 0d;}));
        List<BbModel.Layer> nativeLayers=List.of(layer("posture","a",0));
        assertEquals(2,x(player.sample(0,nativeLayers)),1e-5);assertEquals(123d,player.expressionVariables().get("variable.order"),1e-5);
        player.sample(0,nativeLayers);assertEquals(1d,player.expressionVariables().get("variable.init"),1e-5);
        assertEquals(2d,player.expressionVariables().get("variable.updates"),1e-5);
        assertEquals(List.of("ysm.play_sound"),effects);
        player.reset();player.sample(0,nativeLayers);assertEquals(1d,player.expressionVariables().get("variable.init"),1e-5);
        assertEquals(1d,player.expressionVariables().get("variable.updates"),1e-5);assertEquals(List.of("ysm.play_sound","ysm.play_sound"),effects);
    }
    @Test void lifecycleArgumentsAndTempsAreFreshPerProgramAndFirstPersonHasAnExplicitOverride() {
        JsonObject raw=fixture();
        event(raw,"player_init","t.x=9;v.initArgs=args[0];","v.initTemp=t.x;");
        event(raw,"player_update","t.x=9;v.fp=args[0];","v.updateTemp=t.x;v.secondFp=args[0];");
        AnimationPlayer body=new AnimationPlayer(parse(raw));body.sample(0,List.of());
        assertEquals(0d,body.expressionVariables().get("variable.initargs"),1e-5);
        assertEquals(0d,body.expressionVariables().get("variable.inittemp"),1e-5);
        assertEquals(0d,body.expressionVariables().get("variable.updatetemp"),1e-5);
        assertEquals(0d,body.expressionVariables().get("variable.fp"),1e-5);
        body.sample(1,List.of(),0,0,Map.of("ysm.is_first_person",1d));assertEquals(1d,body.expressionVariables().get("variable.secondfp"),1e-5);
        raw.addProperty("ysm_controller_family","fp.arm");AnimationPlayer arms=new AnimationPlayer(parse(raw));arms.sample(0,List.of());
        assertEquals(1d,arms.expressionVariables().get("variable.fp"),1e-5);
        arms.sample(1,List.of(),0,0,Map.of("query.is_first_person",0d));assertEquals(0d,arms.expressionVariables().get("variable.fp"),1e-5);
    }
    @Test void inactiveAuthorDefaultsUseAllInitProgramsAndFunctionsWithoutRunningUpdateOrEntry() {
        JsonObject raw=fixture();functions(raw,"{\"bow\":\"ysm.play_sound('inert');return 1;\"}");
        event(raw,"player_init","v.bow=fn.bow();t.x=7;","v.initTemp=t.x;v.initialized=1;");
        event(raw,"player_update","v.updated=1;");
        controllers(raw,"{\"player.post_main\":{\"states\":{\"default\":{\"on_entry\":[\"v.entered=1;\"]}}}}");
        Map<String,Double> values=parse(raw).initialVariables();assertEquals(1d,values.get("variable.bow"),1e-5);
        assertEquals(0d,values.get("variable.inittemp"),1e-5);assertEquals(1d,values.get("variable.initialized"),1e-5);
        assertFalse(values.containsKey("variable.updated"));assertFalse(values.containsKey("variable.entered"));
    }
    @Test void syncDispatchesEveryLocalSubscriberWithNumericArgsAndIndependentTemps() {
        JsonObject raw=fixture();clip(raw,"parallel0",0,"LOOP","position","v.horn");
        event(raw,"sync","v.calls+=1;t.x=9;v.firstArg=args[0];v.horn=args[1]*16;v.global+=3;",
                "v.secondArg=args[1];v.subscriberTemp=t.x;v.global+=5;");
        timeline(raw,"parallel0",0,"t.x=7;v.syncResult=ysm.sync(0,1);v.callerTemp=t.x;v.outerArgs=args[0];");
        List<String> delegated=new ArrayList<>();AnimationPlayer player=new AnimationPlayer(parse(raw));
        player.configureFrame(context->context.functions((name,args)->{delegated.add(name);return 0d;}));
        assertEquals(2,x(player.sample(0,List.of())),1e-5);
        assertEquals(1d,player.expressionVariables().get("variable.calls"),1e-5);
        assertEquals(0d,player.expressionVariables().get("variable.firstarg"),1e-5);
        assertEquals(1d,player.expressionVariables().get("variable.secondarg"),1e-5);
        assertEquals(0d,player.expressionVariables().get("variable.subscribertemp"),1e-5);
        assertEquals(7d,player.expressionVariables().get("variable.callertemp"),1e-5);
        assertEquals(8d,player.expressionVariables().get("variable.global"),1e-5);
        assertEquals(0d,player.expressionVariables().get("variable.outerargs"),1e-5);assertTrue(delegated.isEmpty());
        assertEquals(16d,parse(raw).initialVariables().get("variable.horn"),1e-5);
    }
    @Test void syncRejectsInvalidArgsAndBoundsRecursiveEventsWithoutRenewingTheirBudget() {
        JsonObject raw=fixture();clip(raw,"parallel0",0,"LOOP","position","0");event(raw,"sync","v.calls+=1;");
        timeline(raw,"parallel0",0,"ysm.sync("+String.join(",",Collections.nCopies(17,"1"))+");ysm.sync('text');ysm.sync(ysm.bone_scale('bone'));");
        AnimationPlayer invalid=new AnimationPlayer(parse(raw));invalid.sample(0,List.of());assertFalse(invalid.expressionVariables().containsKey("variable.calls"));
        assertNull(BbModel.syncArguments(List.of(Double.NaN)));assertNull(BbModel.syncArguments(List.of(Double.POSITIVE_INFINITY)));
        assertEquals(List.of(2d),BbModel.syncArguments(List.of(2d)));
        event(raw,"sync","v.n+=1;ysm.sync(args[0]+1);v.restoredSum+=args[0];");timeline(raw,"parallel0",0,"ysm.sync(0);");
        AnimationPlayer recursive=new AnimationPlayer(parse(raw));assertDoesNotThrow(()->recursive.sample(0,List.of()));
        assertEquals(16d,recursive.expressionVariables().get("variable.n"),1e-5);
        assertEquals(120d,recursive.expressionVariables().get("variable.restoredsum"),1e-5);
        event(raw,"sync","loop(1024,v.n+=1);");timeline(raw,"parallel0",0,"loop(1024,ysm.sync());");
        IllegalArgumentException failure=assertThrows(IllegalArgumentException.class,()->new AnimationPlayer(parse(raw)).sample(0,List.of()));
        assertTrue(failure.getMessage().contains("Evaluation budget"));
    }
    @Test void syncSubscribersCannotMutateTheBuiltinControllerAndCallerPhaseIsRestored() {
        JsonObject raw=fixture();clip(raw,"a",0,"LOOP","position","16");event(raw,"sync","ctrl.set_animation('a',10);");
        event(raw,"player_ctrl_post_main","ysm.sync();q.select ? ctrl.set_animation('a',10) : 0;return 2;");
        List<String> delegated=new ArrayList<>();AnimationPlayer player=new AnimationPlayer(parse(raw));
        player.configureFrame(context->context.functions((name,args)->{delegated.add(name);return 0d;}));
        assertEquals(1,x(player.sample(0,List.of())),1e-5);
        assertEquals(2,x(player.sample(1,List.of(),0,0,Map.of("query.select",1d))),1e-5);assertTrue(delegated.isEmpty());
    }
    @Test void deferWaitsForStrictLoopBoundaryUsesLifoAndCurrentTimeAndClearsSlotContext() {
        JsonObject raw=fixture();clip(raw,"a",1,"LOOP","position","q.anim_time*16");
        timeline(raw,"a",0,"c.n=7;ysm.defer('one',1,q.anim_time,c.n);ysm.defer('two',2);ysm.defer('',9);");
        event(raw,"player_update","ysm.defer('outside',8);");
        event(raw,"defer","v.order=v.order*10+args[0];v.callbackTime=q.anim_time;v.c=c.n;args[0]==1 ? v.captured=args[2] : 0;",
                "c.n+=1;v.calls+=1;ysm.defer('recursive',9);");
        controllers(raw,"{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[\"a\"]}}}}");
        AnimationPlayer player=new AnimationPlayer(parse(raw));player.sample(0,List.of());player.sample(10,List.of());
        assertFalse(player.expressionVariables().containsKey("variable.calls"));
        assertEquals(2,x(player.sample(20,List.of())),1e-5);
        player.sample(21,List.of());
        assertEquals(21d,player.expressionVariables().get("variable.order"));
        assertEquals(2d,player.expressionVariables().get("variable.calls"));
        assertEquals(1d,player.expressionVariables().get("variable.callbacktime"));
        assertEquals(8d,player.expressionVariables().get("variable.c"));
        assertEquals(7d,player.expressionVariables().get("variable.captured"));
        player.sample(21,List.of());assertEquals(2d,player.expressionVariables().get("variable.calls"));
    }
    @Test void onceRemainingEventsUseEndTimeAndFlushAtExactEndWhileHoldKeepsPendingUntilStateChange() {
        JsonObject raw=fixture();clip(raw,"a",1,"ONCE","position","0");
        timeline(raw,"a",.5,"ysm.defer('all',q.anim_time);");event(raw,"defer","v.n+=1;v.captured=args[0];v.callback=q.anim_time;");
        controllers(raw,"{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[\"a\"]}}}}");
        AnimationPlayer player=new AnimationPlayer(parse(raw));player.sample(0,List.of());player.sample(20,List.of());
        assertEquals(1d,player.expressionVariables().get("variable.n"));
        assertEquals(1d,player.expressionVariables().get("variable.captured"));
        assertEquals(1d,player.expressionVariables().get("variable.callback"));
        player.sample(20,List.of());assertEquals(1d,player.expressionVariables().get("variable.n"));
        JsonObject hold=fixture();clip(hold,"a",1,"HOLD","position","q.anim_time*16");clip(hold,"b",1,"HOLD","position","0");
        timeline(hold,"a",0,"c.tag=11;ysm.defer('same',1);");timeline(hold,"b",0,"c.tag=22;ysm.defer('same',2);");
        event(hold,"defer","v.a=args[0]==1 ? c.tag : v.a;v.b=args[0]==2 ? c.tag : v.b;v.n+=1;");
        controllers(hold,"{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[\"a\",\"b\"],\"transitions\":[{\"next\":\"q.change\"}]},\"next\":{}}}}");
        AnimationPlayer held=new AnimationPlayer(parse(hold));held.sample(0,List.of());held.sample(30,List.of());
        assertFalse(held.expressionVariables().containsKey("variable.n"));
        held.sample(31,List.of(),0,0,Map.of("query.change",1d));
        assertEquals(11d,held.expressionVariables().get("variable.a"));assertEquals(22d,held.expressionVariables().get("variable.b"));
        assertEquals(2d,held.expressionVariables().get("variable.n"));
    }
    @Test void ysmTimelineKeysObserveCurrentSampleTimeAndBeginningBlendDefersZeroKey() {
        JsonObject raw=fixture();clip(raw,"a",2,"HOLD","position","0");timeline(raw,"a",.1,"v.time=q.anim_time;");
        controllers(raw,"{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[\"a\"],\"blend_transition\":0.5}}}}");
        AnimationPlayer player=new AnimationPlayer(parse(raw));player.sample(0,List.of());player.sample(9,List.of());
        assertFalse(player.expressionVariables().containsKey("variable.time"));
        player.sample(20,List.of());assertEquals(.5,player.expressionVariables().get("variable.time"),1e-8);
    }
    @Test void deferCapturesAreBoundedAndResetDropsPendingReferences() {
        JsonObject raw=fixture();clip(raw,"a",1,"LOOP","position","0");
        timeline(raw,"a",0,"loop(300,ysm.defer('all',1));");event(raw,"defer","v.n+=1;");
        controllers(raw,"{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[\"a\"]}}}}");
        AnimationPlayer player=new AnimationPlayer(parse(raw));player.sample(0,List.of());player.sample(21,List.of());
        assertEquals(256d,player.expressionVariables().get("variable.n"));
        player.reset();player.sample(0,List.of());assertFalse(player.expressionVariables().containsKey("variable.n"));
    }
    @Test void boneSamplingCannotCaptureDeferOutsideTheClientEventPhase() {
        JsonObject raw=fixture();clip(raw,"a",1,"LOOP","position","ysm.defer('bone',9);return 0;");
        timeline(raw,"a",0,"ysm.defer('timeline',1);");event(raw,"defer","v.n+=1;v.argument=args[0];");
        controllers(raw,"{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[\"a\"]}}}}");
        AnimationPlayer player=new AnimationPlayer(parse(raw));player.sample(0,List.of());player.sample(10,List.of());player.sample(21,List.of());
        assertEquals(1d,player.expressionVariables().get("variable.n"));assertEquals(1d,player.expressionVariables().get("variable.argument"));
    }
    @Test void nativeTimingIsAbsoluteAliasesAreExclusiveAndRawBbKeepsRejectingFields() {
        JsonObject raw=fixture();raw.addProperty("ysm_format_version",65535);clip(raw,"a",2,"HOLD","position","q.anim_time*16");
        JsonObject clip=raw.getAsJsonArray("animations").get(0).getAsJsonObject();clip.addProperty("anim_time_update",".75");
        controllers(raw,"{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[\"a\"]}}}}");
        AnimationPlayer player=new AnimationPlayer(parse(raw));assertEquals(1.75,x(player.sample(0,List.of())),1e-5);
        assertEquals(1.75,x(player.sample(20,List.of())),1e-5);
        clip.addProperty("animation_time_update",".75");assertThrows(IllegalArgumentException.class,()->parse(raw));
        clip.remove("anim_time_update");assertDoesNotThrow(()->parse(raw));raw.remove("ysm_format_version");
        assertThrows(IllegalArgumentException.class,()->parse(raw));
    }
    @Test void authoredSeekMovesEventCursorWithoutReverseReplayAndSameTickCannotReevaluate() {
        JsonObject raw=fixture();raw.addProperty("ysm_format_version",65535);clip(raw,"a",2,"HOLD","position","q.anim_time*16");
        raw.getAsJsonArray("animations").get(0).getAsJsonObject().addProperty("anim_time_update","v.updates+=1;return q.target;");
        timeline(raw,"a",.5,"v.events+=1;v.eventTime=q.anim_time;");
        controllers(raw,"{\"player.post_main\":{\"states\":{\"default\":{\"animations\":[\"a\"]}}}}");
        AnimationPlayer player=new AnimationPlayer(parse(raw));player.sample(0,List.of(),0,0,Map.of("query.target",.75));
        assertEquals(1d,player.expressionVariables().get("variable.events"));
        assertEquals(1.25,x(player.sample(1,List.of(),0,0,Map.of("query.target",.25))),1e-5);
        assertEquals(1d,player.expressionVariables().get("variable.events"));
        assertEquals(1.6,x(player.sample(2,List.of(),0,0,Map.of("query.target",.6))),1e-5);
        assertEquals(2d,player.expressionVariables().get("variable.events"));assertEquals(.6,player.expressionVariables().get("variable.eventtime"),1e-8);
        player.sample(2,List.of(),0,0,Map.of("query.target",1d));assertEquals(3d,player.expressionVariables().get("variable.updates"));
    }
    @Test void startDelayIsOncePerActivationAndLoopDelayWaitsWithoutReplayingZeroKey() {
        JsonObject raw=fixture();raw.addProperty("ysm_format_version",65535);clip(raw,"a",.5,"LOOP","position","q.anim_time*16");
        JsonObject clip=raw.getAsJsonArray("animations").get(0).getAsJsonObject();
        clip.addProperty("start_delay","v.starts+=1;return .5;");clip.addProperty("loop_delay","v.loops+=1;return .5;");
        timeline(raw,"a",0,"v.events+=1;");
        AnimationPlayer player=new AnimationPlayer(parse(raw));List<BbModel.Layer> layers=List.of(layer("player.hold_mainhand","a",0));
        player.sample(0,layers);player.sample(9,layers);assertEquals(1d,player.expressionVariables().get("variable.starts"));
        assertFalse(player.expressionVariables().containsKey("variable.events"));
        player.sample(10,layers);assertEquals(1d,player.expressionVariables().get("variable.events"));
        player.sample(20,layers);assertFalse(player.expressionVariables().containsKey("variable.loops"));
        player.sample(21,layers);player.sample(30,layers);assertEquals(1d,player.expressionVariables().get("variable.loops"));
        assertEquals(1d,player.expressionVariables().get("variable.events"));
        player.sample(31,layers);player.sample(31,layers);assertEquals(2d,player.expressionVariables().get("variable.events"));
        assertEquals(1d,player.expressionVariables().get("variable.starts"));
        player.sample(32,List.of(layer("player.hold_mainhand","a",32)));assertEquals(2d,player.expressionVariables().get("variable.starts"));
    }
    @Test void explicitStopKeepsPausedCapturesThroughEndingThenIdleFlushesLifoAndClearsOnlyControllerScope() {
        JsonObject raw=fixture();clip(raw,"a",1,"HOLD","position","16");
        timeline(raw,"a",0,"c.tag=7;v.kept=42;ysm.defer('all',1);ysm.defer('all',2);");
        event(raw,"player_ctrl_post_main","v.before=c.tag;return q.mode==1 ? 3 : q.mode==2 ? 4 : 5;");
        event(raw,"defer","v.order=v.order*10+args[0];v.c=c.tag;c.tag+=1;v.callbackTime=q.anim_time;v.kept+=1;ysm.play_sound('safe:defer');");
        List<String> effects=new ArrayList<>();AnimationPlayer player=new AnimationPlayer(parse(raw));
        player.configureFrame(context->context.functions((name,args)->{effects.add(name);return 0d;}));
        List<BbModel.Layer> layers=List.of(new BbModel.Layer("player.post_main","a",0,1,"HOLD",0,7));
        player.sample(0,layers);player.sample(1,layers,0,0,Map.of("query.mode",2d));
        player.sample(2,layers,0,0,Map.of("query.mode",1d));
        player.sample(3,layers,0,0,Map.of("query.mode",1d));player.sample(4,layers,0,0,Map.of("query.mode",1d));
        assertTrue(effects.isEmpty());assertFalse(player.expressionVariables().containsKey("variable.order"));
        player.sample(5,layers,0,0,Map.of("query.mode",1d));
        assertEquals(21d,player.expressionVariables().get("variable.order"));assertEquals(8d,player.expressionVariables().get("variable.c"));
        assertEquals(.1,player.expressionVariables().get("variable.callbacktime"),1e-8);
        assertEquals(44d,player.expressionVariables().get("variable.kept"));assertEquals(List.of("ysm.play_sound","ysm.play_sound"),effects);
        player.sample(5,layers,0,0,Map.of("query.mode",1d));assertEquals(2,effects.size());
        assertEquals(0d,player.expressionVariables().get("variable.before"));
    }
    @Test void authorResetInIdleStopPreservesContextUntilContinueWhileDisposalDropsWorldEffects() {
        JsonObject raw=fixture();clip(raw,"a",1,"HOLD","position","16");
        timeline(raw,"a",0,"c.tag=7;v.kept=42;ysm.defer('all',1);");
        event(raw,"player_ctrl_post_main","q.reset ? ctrl.reset() : 0;return q.continue ? 2 : q.stop ? 3 : 5;");
        event(raw,"defer","v.n+=1;v.c=c.tag;ysm.play_sound('safe:defer');");
        List<String> effects=new ArrayList<>();AnimationPlayer player=new AnimationPlayer(parse(raw));
        player.configureFrame(context->context.functions((name,args)->{effects.add(name);return 0d;}));
        List<BbModel.Layer> layers=List.of(new BbModel.Layer("player.post_main","a",0,1,"HOLD",0,0));
        player.sample(0,layers);
        assertEquals(1,x(player.sample(1,layers,0,0,Map.of("query.reset",1d,"query.stop",1d))),1e-5);
        player.sample(2,layers,0,0,Map.of("query.stop",1d));assertTrue(effects.isEmpty());
        player.sample(3,layers,0,0,Map.of("query.continue",1d));
        assertEquals(1d,player.expressionVariables().get("variable.n"));assertEquals(7d,player.expressionVariables().get("variable.c"));
        assertEquals(42d,player.expressionVariables().get("variable.kept"));assertEquals(List.of("ysm.play_sound"),effects);
        player.sample(3,layers,0,0,Map.of("query.continue",1d));assertEquals(1,effects.size());
        AnimationPlayer disposed=new AnimationPlayer(parse(raw));
        disposed.configureFrame(context->context.functions((name,args)->{effects.add(name);return 0d;}));
        disposed.sample(0,layers);disposed.dispose();
        disposed.sample(5,List.of(),0,0,Map.of("query.continue",1d));
        assertEquals(1,effects.size());assertFalse(disposed.expressionVariables().containsKey("variable.n"));
    }
    @Test void stopWhileIdlePreservesPendingAnimationAndInvalidLookupDoesNotEraseIt() {
        JsonObject raw=fixture();clip(raw,"a",2,"HOLD","position","q.anim_time*16");timeline(raw,"a",0,"v.started+=1;");
        event(raw,"player_ctrl_post_main","q.phase==0 ? ctrl.set_animation('a',12) : 0;q.phase==0 ? ctrl.set_animation('missing') : 0;return q.phase==0 ? 3 : 2;");
        AnimationPlayer player=new AnimationPlayer(parse(raw));assertEquals(1,x(player.sample(0,List.of())),1e-5);
        assertFalse(player.expressionVariables().containsKey("variable.started"));
        assertEquals(1,x(player.sample(10,List.of(),0,0,Map.of("query.phase",1d))),1e-5);
        assertEquals(1d,player.expressionVariables().get("variable.started"));
        assertEquals(1.5,x(player.sample(20,List.of(),0,0,Map.of("query.phase",1d))),1e-5);
    }
    private static void event(JsonObject raw,String name,String... scripts) {
        JsonArray values=new JsonArray();for(String script:scripts)values.add(script);
        JsonObject events=raw.has("ysm_events")?raw.getAsJsonObject("ysm_events"):new JsonObject();events.add(name,values);raw.add("ysm_events",events);
    }
    private static void functions(JsonObject raw,String json) {raw.add("ysm_functions",JsonParser.parseString(json));}
    private static BbModel.Layer layer(String name, String clip, long tick) { return new BbModel.Layer(name,clip,tick,1,"LOOP",0,0); }
    private static double x(List<BbModel.Vertex> vertices) { return vertices.getFirst().x(); }
    private static BbModel parse(JsonObject raw) { return BbModel.parse(raw.toString().getBytes(StandardCharsets.UTF_8)); }
    private static void controllers(JsonObject raw, String json) { raw.add("ysm_animation_controllers",JsonParser.parseString(json)); }
    private static void timeline(JsonObject raw, String clip, double time, String script) {
        JsonObject animator=JsonParser.parseString("{\"type\":\"effect\",\"keyframes\":[]}").getAsJsonObject();
        JsonObject point=new JsonObject();point.addProperty("script",script); JsonArray points=new JsonArray();points.add(point);
        JsonObject frame=new JsonObject();frame.addProperty("channel","timeline");frame.addProperty("time",time);frame.add("data_points",points);
        animator.getAsJsonArray("keyframes").add(frame);
        for(JsonElement item:raw.getAsJsonArray("animations")) if(item.getAsJsonObject().get("name").getAsString().equals(clip)) item.getAsJsonObject().getAsJsonObject("animators").add("effects",animator);
    }
    private static void clip(JsonObject raw,String name,double length,String loop,String channel,String x) {
        JsonObject point=new JsonObject();point.addProperty("x",x);point.addProperty("y",channel.equals("scale")?1:0);point.addProperty("z",channel.equals("scale")?1:0);
        JsonArray points=new JsonArray();points.add(point);JsonObject frame=new JsonObject();frame.addProperty("channel",channel);frame.addProperty("time",0);frame.add("data_points",points);
        JsonArray frames=new JsonArray();frames.add(frame);JsonObject animator=new JsonObject();animator.add("keyframes",frames);JsonObject animators=new JsonObject();animators.add("bone",animator);
        JsonObject clip=new JsonObject();clip.addProperty("name",name);clip.addProperty("length",length);clip.addProperty("loop",loop);clip.add("animators",animators);raw.getAsJsonArray("animations").add(clip);
    }
    private static JsonObject fixture() {
        JsonObject raw=JsonParser.parseString("""
             {"meta":{"format_version":"5.0"},"ysm_controller_family":"player","textures":[],"animations":[],
              "elements":[{"uuid":"cube","from":[0,0,0],"to":[16,16,16],"faces":{"north":{"uv":[0,0,16,16],"texture":0}}}],
              "outliner":[{"uuid":"bone","name":"bone","origin":[0,0,0],"children":["cube"]}]}
             """).getAsJsonObject();
        try { BufferedImage image=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,0xffffffff);ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
            JsonObject texture=new JsonObject();texture.addProperty("source","data:image/png;base64,"+Base64.getEncoder().encodeToString(bytes.toByteArray()));raw.getAsJsonArray("textures").add(texture);
        } catch(Exception exception) { throw new AssertionError(exception); } return raw;
    }
}
