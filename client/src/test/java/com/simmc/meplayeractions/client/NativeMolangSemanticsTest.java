package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.expression.Molang;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Golden semantic cases from Sparkle-Morpher b1230a4 ExpressionEvaluatorImpl/ValueConversions. */
class NativeMolangSemanticsTest {
    private static Molang.Context nativeContext() { var c=new Molang.Context(4); c.enableNativeYsm(); return c; }
    private static Object eval(String expression,Molang.Context c) { return Molang.compileNativeYsm(expression).evaluateValue(c); }

    @Test void nullableValuesCoalesceAfterFunctionsPropertiesAndIndexing() {
        var c=nativeContext(); c.functions((name,args)->null);
        c.query("args",new Molang.SequenceValue(Arrays.asList(null,5d)));
        assertEquals(9d,eval("fn.absent() ?? 9",c));
        assertEquals(7d,eval("args[0] ?? 7",c));
        assertEquals(8d,eval("args[20] ?? 8",c));
        assertEquals(6d,eval("(fn.absent()).x ?? 6",c));
        assertNull(eval("v.nil=fn.absent();return v.nil;",c));
        assertEquals(11d,eval("v.nil ?? 11",c));
        assertEquals(0,Molang.compileNativeYsm("v.nil+v.nil").evaluate(c));
    }
    @Test void valueConversionsMatchTheMatureNativeRuntimeWhileLegacyIsUnchanged() {
        var c=nativeContext();c.query("q.health",(Object)null);
        assertEquals(1d,eval("'' ? 1 : 2",c));
        assertEquals(3d,eval("'text'+2",c));
        assertEquals(1d,eval("'text'==1",c));
        assertEquals(0d,eval("q.health=='text'",c));
        assertEquals(0d,eval("'a'=='b'",c));
        assertEquals(1d,eval("'a'=='a'",c));
        var legacy=new Molang.Context();
        assertEquals(2,Molang.compile("'' ? 1 : 2").evaluate(legacy));
        assertEquals(2,Molang.compile("'text'+2").evaluate(legacy));
        assertEquals(0,Molang.compile("'text'==1").evaluate(legacy));
        assertEquals(0d,legacy.setValue("v.nil",null));
    }
    @Test void nativeIndicesTruncateAndClampNegativeToZero() {
        var c=nativeContext(); c.query("args",new Molang.SequenceValue(List.of(3d,4d)));
        assertEquals(3d,eval("args[-5]",c));
        assertEquals(4d,eval("args[1.9]",c));
        assertNull(eval("args[9]",c));
        var legacy=new Molang.Context(); legacy.query("args",new Molang.SequenceValue(List.of(3d,4d)));
        assertEquals(0,Molang.compile("args[-5]+args[1.9]").evaluate(legacy));
    }
    @Test void regularStructAssignmentCopiesButReadOnlyLiveBindingsStayLive() {
        var c=nativeContext();
        eval("v.a.x=2;v.a.custom=3;v.b=v.a;v.a.x=5;",c);
        assertEquals(2d,eval("v.b.x",c)); assertEquals(5d,eval("v.a.x",c));
        assertEquals(3d,eval("v.b.custom",c));
        assertTrue(c.has("v.b.custom"));assertFalse(c.has("v.b.unknown"));
        double[] x={8};
        Molang.StructValue live=new Molang.StructValue() {
            public Object getProperty(String name){return name.equals("x")?x[0]:null;}
            public void putProperty(String name,Object value){ }
            public Molang.StructValue copy(){return this;}
        };
        c.functions((name,args)->live);
        eval("v.bone=ysm.bone_pos('root');v.saved=v.bone.x;",c); x[0]=12;
        assertEquals(12d,eval("v.bone.x",c)); assertEquals(8d,eval("v.saved",c));
        eval("v.bone.x=999;",c); assertEquals(12d,eval("v.bone.x",c));
        eval("v.b=v.bone;",c); assertSame(live,c.value("v.b"));
    }
    @Test void nestedStructInsertionIsIgnoredAndUnknownPropertiesRemainNullable() {
        var c=nativeContext(); eval("v.a.x=3;v.b.x=4;v.a.child=v.b;",c);
        assertNull(eval("v.a.child",c));
        assertEquals(6d,eval("v.a.missing ?? 6",c));
        eval("c.a.x=2;t.a.x=1;",c);
        assertEquals(3d,eval("c.a.x+t.a.x",c));
        assertEquals(3d,c.variables().get("variable.a.x"));
        c.frame(Map.of()); assertNull(eval("t.a.x",c)); assertEquals(2d,eval("c.a.x",c));
    }
    @Test void arrowReadsAnExplicitBoundedChildContextAndNullOwnerStaysNull() {
        var c=nativeContext();var owner=nativeContext(); owner.query("q.health",6d);
        c.query("ysm.projectile_owner",(Molang.ContextValue)()->owner);c.query("q.health",99d);
        assertEquals(6d,eval("ysm.projectile_owner->q.health",c));
        assertEquals(7d,eval("ysm.projectile_owner->q.health+1",c));
        c.query("ysm.projectile_owner",(Object)null);
        assertEquals(4d,eval("(ysm.projectile_owner->q.health) ?? 4",c));
        assertThrows(IllegalArgumentException.class,()->Molang.compile("ysm.projectile_owner->q.health"));
    }
    @Test void childEntityQueriesShareParentAuthorTemporaryAndControllerStorage() {
        var c=nativeContext();var child=nativeContext();child.query("q.health",6d);
        c.query("ysm.projectile_owner",(Molang.ContextValue)()->child);c.query("q.health",99d);
        eval("v.saved=3;t.counter=4;c.state=5;",c);c.currentValue(7);c.effectScope("playback:stable");
        c.childScope(scoped->assertEquals("playback:stable",scoped.effectScope()));
        assertEquals(16d,eval("ysm.projectile_owner->(v.saved+=q.health+this)",c));
        assertEquals(16d,c.value("v.saved"));
        assertEquals(5d,eval("ysm.projectile_owner->(t.counter+=1)",c));
        assertEquals(6d,eval("ysm.projectile_owner->(c.state+=1)",c));
        assertEquals(5d,c.value("t.counter"));assertEquals(6d,c.value("c.state"));
        assertNull(child.value("v.saved"));assertNull(child.effectScope());
        c.frame(Map.of());assertNull(c.value("t.counter"));assertEquals(16d,c.value("v.saved"));
    }
    @Test void nullCoalescingUsesTheUpstreamPrecedenceBelowConditional() {
        var c=nativeContext();c.query("q.health",(Object)null);
        assertEquals(3d,eval("q.health ?? 0 ? 2 : 3",c));
        assertEquals(1d,eval("1 ?? 0 ? 2 : 3",c));
        assertEquals(3,Molang.compile("q.missing ?? 0 ? 2 : 3").evaluate(new Molang.Context()));
        assertEquals(2,Molang.compile("1 ?? 0 ? 2 : 3").evaluate(new Molang.Context()));
    }
    @Test void authorMathAliasesAndIntegerRangesMatchFixedSource() {
        var c=nativeContext();
        assertEquals(1d,eval("math.hermite_blend(.5)",c));
        assertEquals(-4d,eval("math.hermite(1.1)",c));
        assertEquals(45d,eval("math.lerprotate(720,810,.5)",c));
        // Fixed-source MathHelper wraps +180 to -180; normalizeYaw leaves an exact 180 delta intact.
        assertEquals(-90d,eval("math.lerprotate(0,180,.5)",c));
        assertEquals(-90d,eval("math.lerprotate(0,-180,.5)",c));
        assertEquals(180d,eval("math.lerprotate(170,-170,.5)",c));
        assertEquals(0d,eval("math.randomi(7,7)",c));
        for(int i=0;i<100;i++) {
            double value=(Double)eval("math.random_integer(4,2)",c);
            assertTrue(value>=2 && value<4);
            assertEquals(6d,eval("math.die_roll_integer(3,2,3)",c));
            double random=(Double)eval("math.random(4,2,999)",c);assertTrue(random>=2 && random<4);
        }
        assertEquals(0d,eval("math.max(1,2,3)",c));
        assertEquals(.5,Molang.compile("math.hermite_blend(.5)").evaluate(new Molang.Context()));
    }
    @Test void thisUsesTheCurrentAxisValueAndDiagnosticsKeepTypedFallbacks() {
        var c=nativeContext();c.currentValue(8);c.enableDiagnostics();
        assertEquals(10d,eval("this+2",c));
        c.query("q.absent",(Object)null);
        assertEquals("blue",eval("q.absent ?? 'blue'",c));
        assertEquals("blue",c.diagnostics().getFirst().actualFallback());
        c.clear();assertEquals(0d,eval("this",c));
    }
    @Test void childContextsCannotRestartTheOuterEvaluationBudget() {
        var c=nativeContext();var child=nativeContext();
        c.query("ysm.projectile_owner",(Molang.ContextValue)()->child);
        assertThrows(IllegalArgumentException.class,()->eval("loop(1024,{ysm.projectile_owner->loop(1024,{v.n+=1;});})",c));
    }
    @Test void unregisteredNativeFunctionsDegradeTheWholeFormulaWithoutExecutingAnyPart() {
        var c=nativeContext();c.enableDiagnostics();int[] calls={0};
        c.functions((name,args)->{calls[0]++;return 7d;});
        assertEquals(0d,eval("v.effect=4;ysm.optional_mod(1)+3",c));
        assertNull(c.value("v.effect"));assertEquals(0,calls[0]);
        assertEquals("ysm.optional_mod",c.diagnostics().getFirst().name());
        assertEquals("unregistered_function",c.diagnostics().getFirst().reason());
        assertEquals(0d,c.diagnostics().getFirst().actualFallback());
        assertThrows(IllegalArgumentException.class,()->Molang.compile("ysm.optional_mod(1)+3"));
        assertThrows(IllegalArgumentException.class,()->Molang.compileNativeYsm("java.lang.Runtime.exec('x')"));
        assertThrows(IllegalArgumentException.class,()->Molang.compileNativeYsm("math.exec(1)"));
        assertEquals(0d,eval("v.side=4;ysm.bone_pos('root',ysm.play_sound('id','sound'))",c));
        assertNull(c.value("v.side"));assertEquals(0,calls[0]);
        assertEquals("invalid_function_arity",c.diagnostics().getLast().reason());
    }
}
