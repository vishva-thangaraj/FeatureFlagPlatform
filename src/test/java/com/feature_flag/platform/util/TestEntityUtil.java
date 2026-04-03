package com.feature_flag.platform.util;

import com.feature_flag.platform.common.entity.BaseEntity;

import java.lang.reflect.Field;

public final class TestEntityUtil {

    private TestEntityUtil() {
    }

    public static <T extends BaseEntity> T withId(T entity, Long id) {
        try {
            Field idField = BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
            return entity;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to set entity id", e);
        }
    }
}
