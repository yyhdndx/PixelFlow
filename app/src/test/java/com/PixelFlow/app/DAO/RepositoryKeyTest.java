package com.PixelFlow.app.DAO;

import org.junit.Test;
import static org.junit.Assert.*;

public class RepositoryKeyTest {
    @Test public void sameClassHasSeparateKeysForEachType() {
        assertNotEquals(RepositoryKey.create(DataType.LIKE_STATUS, String.class),
                RepositoryKey.create(DataType.FOLLOW_STATUS, String.class));
    }

    @Test public void allTypesRoundTripIncludingUnderscores() {
        for (DataType type : DataType.values()) {
            assertEquals(type, RepositoryKey.parseType(RepositoryKey.create(type, String.class)));
        }
    }

    @Test public void classNameIsFullyQualified() {
        assertEquals("DRAFT_java.lang.String", RepositoryKey.create(DataType.DRAFT, String.class));
    }

    @Test(expected = IllegalArgumentException.class)
    public void untypedClassNameCannotSilentlyUseLikes() {
        RepositoryKey.parseType("com.PixelFlow.app.DAO.PersistState.FollowStatus");
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingClassIsRejected() {
        RepositoryKey.parseType("FOLLOW_STATUS_");
    }
}
