package com.PixelFlow.app.creation;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.*;

public class AssistUndoTest {
    @Test public void editedTextRequiresExplicitConfirmation() {
        AssistUndo undo = new AssistUndo();
        assertFalse(undo.canRestore("原", "正文", true));
        undo.record("原", "正文", "建议", "改写");
        assertTrue(undo.canRestore("建议", "改写", false));
        assertFalse(undo.canRestore("手写标题", "改写", false));
        assertFalse(undo.canRestore("建议", "继续编辑", false));
        assertTrue(undo.canRestore("建议", "继续编辑", true));
    }
    @Test public void recreationPreservesUndoAndNextApplicationReplacesIt() {
        AssistUndo undo = new AssistUndo(); undo.record("原", "正文", "建议", "改写");
        AssistUndo restored = new Gson().fromJson(new Gson().toJson(undo), AssistUndo.class);
        assertEquals("正文", restored.beforeContent);
        assertTrue(restored.canRestore("建议", "改写", false));
        restored.record("建议", "手写调整", "新建议", "新改写");
        assertEquals("手写调整", restored.beforeContent);
        restored.available = false;
        assertFalse(restored.canRestore("新建议", "新改写", true));
    }
}
