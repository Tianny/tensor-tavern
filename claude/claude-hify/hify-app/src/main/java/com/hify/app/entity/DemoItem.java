package com.hify.app.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.entity.BaseEntity;

import lombok.Getter;
import lombok.Setter;

/**
 * CRUD 流程演示实体。公共字段（id/createdAt/updatedAt/deleted）继承自 BaseEntity。
 */
@Getter
@Setter
@TableName("demo_item")
public class DemoItem extends BaseEntity {

    /** 名称，唯一 */
    private String name;

    /** 状态：0=停用 1=启用 */
    private Integer status;
}
