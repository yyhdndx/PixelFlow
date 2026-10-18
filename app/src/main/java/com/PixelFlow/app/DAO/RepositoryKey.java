package com.PixelFlow.app.DAO;

import java.util.Objects;

/** Typed repository identities; never infer an unknown type as LIKE_STATUS. */
public final class RepositoryKey {
    private RepositoryKey() {}

    public static String create(DataType type, Class<?> model) {
        return Objects.requireNonNull(type, "type").name() + "_"
                + Objects.requireNonNull(model, "model").getName();
    }

    public static DataType parseType(String key) {
        Objects.requireNonNull(key, "key");
        for (DataType type : DataType.values()) {
            String prefix = type.name() + "_";
            if (key.startsWith(prefix) && key.length() > prefix.length()) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown repository key");
    }
}
