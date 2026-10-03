package com.simmc.meplayeractions.expression;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;

class MolangTest {
    @Test void authorCommentsRemainLexicalAndCannotHideHostOperationsInsideStrings() {
        var c = new Molang.Context(); c.frame(Map.of());
        assertEquals(5, Molang.compile("// 中文作者说明\r\nv.a=8/* 保留除法运算 */ /2; // 尾部\nv.a+1").evaluate(c));
        assertEquals("https://host/*literal*/", Molang.compile("return 'https://host/*literal*/';// end").evaluateValue(c));
        assertThrows(IllegalArgumentException.class, () -> Molang.compile("1;/* unclosed"));
        assertThrows(IllegalArgumentException.class, () -> Molang.compile("/* comment */java.lang.Runtime.exec('x')"));
    }
    @Test void precedenceAssignmentAndLazyBranchesMatchAuthoredExpressions() {
        Molang.Context c = new Molang.Context();
        c.frame(Map.of("ysm.food_level", 5d, "ysm.head_yaw", -30d));
        assertEquals(-25, Molang.compile("ysm.food_level<=6?-25:0").evaluate(c));
        assertEquals(7, Molang.compile("v.a=1+2*3; v.a").evaluate(c));
        assertEquals(8, Molang.compile("false ? (v.a=90) : (v.a=v.a+1); v.a").evaluate(c));
        assertEquals(1, Molang.compile("math.sin(90)").evaluate(c), 1e-9);
        assertEquals(-23.2866+12, Molang.compile("(!query.is_sneaking)?((ysm.head_yaw<0)?(-23.2866-0.4*ysm.head_yaw):(-23.2866+0.4*ysm.head_yaw)):(-23.2866)").evaluate(c), 1e-8);
    }
    @Test void sourcePhysicsAssignmentsPersistAndInstancesAreIsolated() {
        var update = Molang.compile("'hair';v.p=v.p+0.01*v.d;v.d=v.d+0.01*(30-v.p-0.8*v.d)/0.025;");
        var a = new Molang.Context(); var b = new Molang.Context();
        for (int i=0;i<800;i++) { a.frame(Map.of()); update.evaluate(a); }
        assertEquals(30, a.get("v.p"), .1); assertEquals(0,b.get("v.p"));
        a.clear(); assertEquals(0,a.get("v.p"));
    }
    @Test void unsupportedHostOperationsAndExcessiveRecursionAreRejected() {
        for (String source : new String[]{"java.lang.Runtime.exec('x')", "query.food_level=9", "loop(100000,v.a=1)", "1e999"})
            assertThrows(IllegalArgumentException.class, () -> Molang.compile(source), source);
        assertThrows(IllegalArgumentException.class, () -> Molang.compile("(".repeat(100) + "1" + ")".repeat(100)));
        assertEquals(0,Molang.compile("0/0").evaluate(new Molang.Context()));
    }
    @Test void modelStringsAndTypedArgumentsSelectOnlyMatchingEquipment() {
        var c = new Molang.Context(); c.frame(Map.of());
        c.stringQuery("ysm.texture_name", "blue");
        c.functions((name, args) -> {
            assertEquals("query.is_item_name_any", name);
            assertEquals(java.util.List.of("slot.weapon.mainhand", "minecraft:bow"), args);
            return true;
        });
        assertEquals(1, Molang.compile("ysm.texture_name=='blue' && q.is_item_name_any('slot.weapon.mainhand','minecraft:bow')").evaluate(c));
        assertEquals(0, Molang.compile("ysm.texture_name=='default'").evaluate(c));
        assertEquals("blue", Molang.compile("return ysm.texture_name;").evaluateValue(c));
    }
    @Test void boundedBlocksReturnAndRadioScriptsPreserveValues() {
        var c = new Molang.Context(); c.frame(Map.of());
        assertEquals(3, Molang.compile("v.player_size=2.5; v.roaming.mode=3; true?{v.a=1;return v.roaming.mode;};v.a=99;").evaluate(c));
        assertEquals(1, c.get("v.a")); assertEquals(2.5,c.get("v.player_size"));
        assertEquals(4, Molang.compile("v.count=0; loop(4,{v.count+=1;}); v.count").evaluate(c));
        assertEquals(2, Molang.compile("v.count=0;loop(9,{v.count+=1;v.count==2?{break;};});v.count").evaluate(c));
        assertThrows(IllegalArgumentException.class, () -> Molang.compile("loop(1025,{v.a+=1;})"));
        assertThrows(IllegalArgumentException.class, () -> Molang.compile("break;"));
        assertThrows(IllegalArgumentException.class, () -> Molang.compile("loop(1024,{loop(1024,{v.a+=1;});})").evaluate(c));
    }
    @Test void queryScopesAndMissingValueFallbackDoNotLeakBetweenFrames() {
        var c=new Molang.Context(); c.frame(Map.of("ctrl.loop",10d));
        c.query("c.state", "walking"); var before=c.queryValues();
        c.query("c.state", "idle"); c.restoreQueries(before);
        assertEquals("walking", Molang.compile("c.state").evaluateValue(c));
        assertEquals(9, Molang.compile("v.missing ?? 9").evaluate(c));
        c.set("v.missing",0); assertEquals(0,Molang.compile("v.missing ?? 9").evaluate(c));
        c.set("t.once",7); c.frame(Map.of()); assertEquals(0,c.get("t.once"));
        assertEquals(0,c.get("c.state")); assertEquals(0,c.get("v.missing"));
    }
    @Test void controllerLocalsSwitchScopesWhileModelVariablesRemainShared() {
        var c=new Molang.Context(); c.frame(Map.of());
        Molang.compile("c.count=1;v.shared=4").evaluate(c);
        var first=c.contextValues(); c.restoreContextValues(Map.of());
        assertEquals(0,c.get("c.count")); assertEquals(4,c.get("v.shared"));
        Molang.compile("c.count=9;v.shared+=1").evaluate(c);
        c.restoreContextValues(first); assertEquals(1,c.get("c.count")); assertEquals(5,c.get("v.shared"));
        assertThrows(IllegalArgumentException.class,()->c.restoreContextValues(Map.of("query.health",9d)));
    }
    @Test void boneQueryVectorsHaveOnlySafeReadOnlyCoordinates() {
        var c=new Molang.Context();c.frame(Map.of());
        c.functions((name,args)->{assertEquals("ysm.bone_rot",name);assertEquals(java.util.List.of("AllBody"),args);return new Molang.VectorValue(1,2,3);});
        assertEquals(6,Molang.compile("ysm.bone_rot('AllBody').x+ysm.bone_rot('AllBody').y+ysm.bone_rot('AllBody').z").evaluate(c));
        assertEquals(3,Molang.compile("v.pos=ysm.bone_rot('AllBody');v.pos.z").evaluate(c));
        assertThrows(IllegalArgumentException.class,()->Molang.compile("ysm.bone_rot('AllBody').getClass()"));
    }
    @Test void forEachAndMathAliasesRemainBoundedAndDoNotReflectOverHostObjects() {
        var c=new Molang.Context();c.frame(Map.of());
        c.query("q.parts",new Molang.SequenceValue(java.util.List.of(1d,2d,3d)));
        assertEquals(6,Molang.compile("v.sum=0;for_each(t.part,q.parts,{v.sum+=t.part;});v.sum").evaluate(c));
        assertEquals(.5,Molang.compile("math.hermite(.5)").evaluate(c));
        assertEquals(6,Molang.compile("math.roll(3,2,2)").evaluate(c));
        assertThrows(IllegalArgumentException.class,()->Molang.compile("math.roll(1025,0,1)").evaluate(c));
        assertThrows(IllegalArgumentException.class,()->c.query("q.parts",java.util.List.of(1,2,3)));
        assertThrows(IllegalArgumentException.class,()->Molang.compile("for_each(q.health,q.parts,{v.x=1;})"));
    }
    @Test void authorControllerConstantsAreReadOnlyAndAvailableWithoutNativeBindings() {
        var c=new Molang.Context();c.frame(Map.of());
        assertEquals(12,Molang.compile("ctrl.hold_on_last_frame ?? 99").evaluate(c));
        assertEquals(24,Molang.compile("ctrl.loop+ctrl.play_once+ctrl.state_stop").evaluate(c));
        c.query("ctrl.loop",0);assertEquals(10,c.get("ctrl.loop"));
        c.clear();assertEquals(5,c.get("ctrl.state_bypass"));
        assertThrows(IllegalArgumentException.class,()->Molang.compile("ctrl.loop=99"));
    }
    @Test void authorFunctionsUseOnlyBoundedReadOnlyTypedArgumentsAndRestoreCallTemporaries() {
        var c=new Molang.Context();c.frame(Map.of());
        c.query("args",new Molang.SequenceValue(java.util.List.of(2d,"blue",new Molang.VectorValue(3,4,5))));
        assertEquals(7,Molang.compile("args[0]+args[2].z").evaluate(c));
        assertEquals("blue",Molang.compile("args[1]").evaluateValue(c));
        assertEquals(0,Molang.compile("args[-1]+args[99]+args[0.5]").evaluate(c));
        c.functions((name,args)->{assertEquals("fn.author.scale",name);return args.getFirst();});
        assertEquals(12,Molang.compile("fn.author.scale(ctrl.hold_on_last_frame)").evaluate(c));
        Molang.compile("t.local=3;v.global=1").evaluate(c);var before=c.tempValues();
        Molang.compile("t.local=9;v.global=2").evaluate(c);c.restoreTempValues(before);
        assertEquals(3,c.get("t.local"));assertEquals(2,c.get("v.global"));
        assertThrows(IllegalArgumentException.class,()->c.restoreTempValues(Map.of("v.global",0d)));
        assertThrows(IllegalArgumentException.class,()->Molang.compile("args[0]=99"));
        assertThrows(IllegalArgumentException.class,()->Molang.compile("args[0].getClass()"));
        assertThrows(IllegalArgumentException.class,()->Molang.compile("java.exec(args[0])"));
    }
}
