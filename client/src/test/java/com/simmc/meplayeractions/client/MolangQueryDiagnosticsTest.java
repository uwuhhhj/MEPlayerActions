package com.simmc.meplayeractions.client;

import com.simmc.meplayeractions.expression.Molang;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MolangQueryDiagnosticsTest {
    @Test void ordinaryClientAndServerContextsRetainZeroNullAndNoDiagnostics() {
        var context = new Molang.Context();
        context.query("q.biome_category", (Object) null);
        assertTrue(context.has("query.biome_category"));
        assertEquals(0d, context.queryValues().get("query.biome_category"));
        assertEquals(0d, context.value("q.biome_category"));
        assertEquals(0, Molang.compile("q.biome_category ?? 7").evaluate(context));
        assertEquals(7, Molang.compile("q.missing ?? 7").evaluate(context));
        assertEquals(1, Molang.compile("q.missing + 1").evaluate(context));
        assertTrue(context.diagnostics().isEmpty());
        var saved = context.queryValues();
        context.query("q.biome_category", 20d);
        context.restoreQueries(saved);
        assertEquals(0, Molang.compile("q.biome_category ?? 7").evaluate(context));
    }

    @Test void optedInUnavailableBindingsReadZeroButUseLazyAuthorDefaults() {
        var context = localContext();
        context.query("ysm.biome_category", (Object) null);
        assertTrue(context.has("ysm.biome_category"));
        assertNull(context.queryValues().get("ysm.biome_category"));
        assertEquals(0d, context.value("ysm.biome_category"));
        assertEquals(12, Molang.compile("ysm.biome_category ?? (v.fallback=12)").evaluate(context));
        assertEquals(12, context.get("v.fallback"));
        var diagnostic = context.diagnostics().getFirst();
        assertEquals("ysm.biome_category", diagnostic.name());
        assertEquals("unavailable_query", diagnostic.reason());
        assertEquals(2, diagnostic.count());
        assertEquals(12d, diagnostic.actualFallback());

        context.query("ysm.biome_category", 0d);
        assertEquals(0, Molang.compile("ysm.biome_category ?? (v.fallback=99)").evaluate(context));
        assertEquals(12, context.get("v.fallback"));
        assertEquals(2, context.diagnostics().getFirst().count());
    }

    @Test void missingQueriesMergeAliasesAndRecordTheActualTypedFallback() {
        var context = localContext();
        assertFalse(context.has("q.projectile_owner"));
        assertTrue(context.diagnostics().isEmpty());
        assertEquals(0d, context.value("Q.PROJECTILE_OWNER"));
        assertEquals("unknown owner", Molang.compile("query.projectile_owner ?? 'unknown owner'").evaluateValue(context));
        var diagnostic = context.diagnostics().getFirst();
        assertEquals("query.projectile_owner", diagnostic.name());
        assertEquals("missing_query", diagnostic.reason());
        assertEquals(2, diagnostic.count());
        assertEquals("unknown owner", diagnostic.actualFallback());
        assertEquals(9, Molang.compile("v.unset ?? 9").evaluate(context));
        assertEquals(4, Molang.compile("c.unset ?? 4").evaluate(context));
        assertEquals(3, Molang.compile("ctrl.unset ?? 3").evaluate(context));
        assertEquals(1, context.diagnostics().size());
    }

    @Test void snapshotsStayImmutableAndPreserveUnavailableBindingsThroughScopes() {
        var context = localContext();
        context.query("query.absent", (Object) null);
        context.query("ysm.texture_name", "blue");
        context.query("query.present", 0d);
        var before = context.queryValues();
        assertThrows(UnsupportedOperationException.class, () -> before.put("query.new", 1d));
        assertThrows(UnsupportedOperationException.class, () -> before.remove("query.absent"));
        context.query("query.absent", 5d);
        context.query("ysm.texture_name", "changed");
        context.query("args", new Molang.SequenceValue(List.of(9d)));
        assertNull(before.get("query.absent"));
        assertEquals("blue", before.get("ysm.texture_name"));
        context.restoreQueries(before);
        assertTrue(context.has("q.absent"));
        assertFalse(context.has("args"));
        assertEquals("blue", context.value("ysm.texture_name"));
        assertEquals(7, Molang.compile("q.absent ?? 7").evaluate(context));
        assertEquals(0, Molang.compile("q.present ?? 7").evaluate(context));
        assertEquals(before, context.queryValues());

        var ordinary = new Molang.Context();
        ordinary.restoreQueries(before);
        assertEquals(0d, ordinary.queryValues().get("query.absent"));
        assertEquals(0, Molang.compile("q.absent ?? 7").evaluate(ordinary));
        assertTrue(ordinary.diagnostics().isEmpty());
    }

    @Test void diagnosticsSurviveFramesButResetWithoutChangingExistingSnapshots() {
        var context = localContext();
        context.get("q.missing");
        var first = context.diagnostics();
        assertThrows(UnsupportedOperationException.class, first::clear);
        context.frame(Map.of());
        context.get("query.missing");
        assertEquals(1, first.getFirst().count());
        assertEquals(2, context.diagnostics().getFirst().count());
        context.clear();
        assertTrue(context.diagnostics().isEmpty());
        assertEquals(1, first.size());
        context.query("q.empty", (Object) null);
        assertEquals(6, Molang.compile("q.empty ?? 6").evaluate(context));
        assertEquals(1, context.diagnostics().getFirst().count());
    }

    @Test void diagnosticsAreBoundedAndContinueCountingAlreadyTrackedNames() {
        var context = localContext();
        for (int index = 0; index < 300; index++) context.get("ysm.unbound_" + index);
        assertEquals(128, context.diagnostics().size());
        context.get("ysm.unbound_0");
        assertEquals(2, context.diagnostics().getFirst().count());
        context.clear();
        context.get("query." + "x".repeat(500));
        assertEquals(256, context.diagnostics().getFirst().name().length());
    }

    @Test void registeredZeroAndVectorPropertiesAreNotMissingQueries() {
        var context = localContext();
        context.query("query.health", 0d);
        context.query("ysm.position", new Molang.VectorValue(1, 2, 3));
        assertEquals(0, Molang.compile("q.health ?? 9").evaluate(context));
        assertEquals(6, Molang.compile("ysm.position.x + ysm.position.y + ysm.position.z").evaluate(context));
        assertTrue(context.diagnostics().isEmpty());
        context.setValue("v.nil", null);
        context.query("args", (Object) null);
        assertEquals(0, Molang.compile("v.nil ?? 9").evaluate(context));
        assertEquals(0, Molang.compile("args ?? 9").evaluate(context));
    }

    @Test void unknownFunctionsAndHostCallsRemainExplicitlyRejected() {
        for (String source : List.of("q.unimplemented(1)", "ysm.unimplemented(1)", "java.lang.Runtime.exec('x')", "math.exec(1)")) {
            var rejected = assertThrows(IllegalArgumentException.class, () -> Molang.compile(source));
            assertTrue(rejected.getMessage().contains("表达式已拒绝"), source);
        }
        assertThrows(IllegalArgumentException.class, () -> Molang.compile("q.health=9"));
        assertThrows(IllegalArgumentException.class, () -> Molang.compile("loop(1025,{v.a=1;})"));
    }

    @Test void deferUsesOnlyTheWhitelistedResolverAndKeepsItsArgumentTypes() {
        var context = localContext();
        int[] calls = { 0 };
        context.functions((name, arguments) -> {
            assertEquals("ysm.defer", name);
            assertEquals(List.of("once", 9d, "blue"), arguments);
            calls[0]++;
            return 0d;
        });
        assertEquals(0, Molang.compile("ysm.defer('once',9,'blue')").evaluate(context));
        assertEquals(1, calls[0]);
        assertThrows(IllegalArgumentException.class,
                () -> Molang.compile("ysm.defer('once',9)").evaluate(new Molang.Context()));
    }

    @Test void diagnosticsAndAuthorDefaultsDoNotBypassEvaluationBudgets() {
        var context = localContext();
        context.query("q.empty", (Object) null);
        assertThrows(IllegalArgumentException.class,
                () -> Molang.compile("loop(1024,{loop(1024,{q.empty ?? 9;});})").evaluate(context));
        assertThrows(IllegalArgumentException.class, () -> Molang.compile("q.empty ?? 9").evaluate(context));
        assertEquals(1, context.diagnostics().size());
        context.frame(Map.of());
        context.query("q.empty", (Object) null);
        assertEquals(9, Molang.compile("q.empty ?? 9").evaluate(context));

        var ordinary = new Molang.Context();
        assertThrows(IllegalArgumentException.class,
                () -> Molang.compile("loop(1024,{loop(1024,{v.a+=1;});})").evaluate(ordinary));
        assertTrue(ordinary.diagnostics().isEmpty());
    }

    private static Molang.Context localContext() {
        var context = new Molang.Context();
        context.enableDiagnostics();
        context.enableDiagnostics();
        return context;
    }
}
