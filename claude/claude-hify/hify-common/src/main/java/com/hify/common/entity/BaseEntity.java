package com.hify.common.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;

import lombok.Getter;
import lombok.Setter;

/**
 * 业务实体基类：所有表结构公共字段，业务实体继承本类后只需声明自己的字段。
 * 与 schema.sql 的建表约定一一对应：
 * id 自增主键、created_at/updated_at datetime、deleted 逻辑删除。
 * 时间自动填充由 {@link com.hify.common.config.AutoFillMetaObjectHandler} 完成
 * （strict 填充只覆盖 null，手工设置的时间不会被冲掉）。
 */
@Getter
@Setter
public abstract class BaseEntity {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 创建时间，插入时自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间，插入和更新时自动填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除：0=正常 1=删除。全局字段配置见 MybatisPlusConfig，此处显式标注以自文档化 */
    @TableLogic
    private Integer deleted = 0;
}
