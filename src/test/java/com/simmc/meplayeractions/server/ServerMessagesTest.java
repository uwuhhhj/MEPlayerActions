package com.simmc.meplayeractions.server;

import com.simmc.meplayeractions.action.DisguiseOptions;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ServerMessagesTest {
    @Test void disguiseMessageSeparatesBlueprintOptionsAndCommandsIntoShortLines() {
        DisguiseOptions options = new DisguiseOptions("dragon", .8, true, 2, true, 8, 10,
                List.of(new DisguiseOptions.Effect("slowness", 1, 0)));
        List<String> lines = ServerMessages.disguise(options);
        assertEquals(5, lines.size());
        assertTrue(lines.getFirst().contains("ModelEngine 蓝图"));
        assertTrue(lines.stream().anyMatch(line -> line.contains("0.8")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("slowness:1")));
        assertTrue(lines.getLast().contains("undisguise"));
    }
    @Test void readyResourceDoesNotClaimAnyViewerHasConfirmedTakeover() {
        String text = String.join("\n", ServerMessages.modelResource("dragon", "ready", "own", "prepared", true));
        assertTrue(text.contains("MPA models/"));
        assertTrue(text.contains("取得并验证资源后"));
        assertTrue(text.contains("可复用本地有效缓存"));
        assertFalse(text.contains("已确认接管"));
    }
    @Test void missingPendingAndInvalidResourcesExplainMeRenderingContinues() {
        for (String state : List.of("pending", "missing", "invalid", "memory_limit")) {
            String text = String.join("\n", ServerMessages.modelResource("dragon", state, "none", "详细原因", true));
            assertTrue(text.contains("ModelEngine 伪装照常显示"), state);
        }
        String invalid = String.join("\n", ServerMessages.modelResource("dragon", "invalid", "own", "语法不正确", true));
        assertTrue(invalid.contains("语法不正确"));
        assertTrue(invalid.contains("不会改用另一来源"));
    }
    @Test void instanceEligibilityCannotBeOverriddenByResourceReadiness() {
        String text = String.join("\n", ServerMessages.modelResource("dragon", "ready", "jar", "prepared", false));
        assertTrue(text.contains("不允许客户端接管"));
        assertFalse(text.contains("取得并验证资源后"));
    }
    @Test void modelAndAnimationListsWrapWithoutLosingDistinctIds() {
        List<String> entries = List.of("a".repeat(40), "b".repeat(40), "mounted_ing", "idle_flight", "jumpattack");
        List<String> lines = ServerMessages.list("模型", entries);
        assertTrue(lines.size() > 2);
        String text = String.join("\n", lines);
        for (String entry : entries) assertTrue(text.contains(entry));
        assertEquals(List.of("模型§7（0 个）", "§7暂无。"), ServerMessages.list("模型", List.of()));
        assertEquals(3, ServerMessages.wrapped("原因：", "很".repeat(150)).size());
    }
    @Test void wrappedMessagesKeepSupplementaryUnicodeCharactersIntact() {
        String value = "a".repeat(63) + "😀" + "b";
        List<String> lines = ServerMessages.wrapped("", value);
        assertEquals(2, lines.size());
        assertEquals("a".repeat(63) + "😀", lines.getFirst());
        assertEquals("§7  b", lines.getLast());
        String text = String.join("\n", ServerMessages.modelResource("dragon", "ready", "modelengine", "prepared", true));
        assertTrue(text.contains("可能已烘焙"));
    }
}
