package com.PixelFlow.app.util;

import com.PixelFlow.app.Streams.WorkItem;
import org.junit.Test;
import static org.junit.Assert.*;

public class AuthorIdentityTest {
    @Test public void sameNicknameDoesNotShareFollowState() {
        assertNotEquals(AuthorIdentity.followKey(new WorkItem.Author("1", "同名", "")),
                AuthorIdentity.followKey(new WorkItem.Author("2", "同名", "")));
    }
    @Test public void renamingDoesNotChangeKey() {
        assertEquals(AuthorIdentity.followKey(new WorkItem.Author("1", "旧名", "")),
                AuthorIdentity.followKey(new WorkItem.Author("1", "新名", "")));
    }
    @Test public void missingIdDisablesFollowInsteadOfUsingNickname() {
        assertNull(AuthorIdentity.followKey(null));
        assertNull(AuthorIdentity.followKey(new WorkItem.Author(null, "昵称", "")));
        assertNull(AuthorIdentity.followKey(new WorkItem.Author(" ", "昵称", "")));
    }
    @Test public void idKeyCannotCollideWithLegacyNumericNickname() {
        assertEquals("user:123", AuthorIdentity.followKey(new WorkItem.Author("123", "昵称", "")));
        assertNotEquals("123", AuthorIdentity.followKey(new WorkItem.Author("123", "昵称", "")));
    }
}
