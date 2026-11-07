package com.PixelFlow.app.creation;

import java.util.Objects;

/** One reversible application; survives editor recreation with the draft state. */
public final class AssistUndo {
    public String beforeTitle, beforeContent, appliedTitle, appliedContent;
    public boolean available;

    public void record(String title, String content, String nextTitle, String nextContent) {
        beforeTitle = title; beforeContent = content;
        appliedTitle = nextTitle; appliedContent = nextContent; available = true;
    }
    public boolean needsConfirmation(String title, String content) {
        return available && (!Objects.equals(appliedTitle, title) || !Objects.equals(appliedContent, content));
    }
    public boolean canRestore(String title, String content, boolean confirmed) {
        return available && (confirmed || !needsConfirmation(title, content));
    }
}
