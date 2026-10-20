package com.hify.common.config;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;

import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

/**
 * 时间字段自动填充。
 * 实体侧配合注解使用：
 * <pre>
 * {@code @TableField(fill = FieldFill.INSERT)}         private LocalDateTime createdAt;
 * {@code @TableField(fill = FieldFill.INSERT_UPDATE)}  private LocalDateTime updatedAt;
 * </pre>
 * strict 填充只覆盖 null 值，手工设置的时间不会被冲掉。
 */
@Component
public class AutoFillMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}
