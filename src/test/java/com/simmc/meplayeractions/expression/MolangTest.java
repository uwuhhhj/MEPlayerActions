package com.simmc.meplayeractions.expression;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;

class MolangTest {
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
}
