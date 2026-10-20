package com.hify.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.hify.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工作流定义。节点 / 连线单独建表（workflow_node / workflow_edge），保存时整体替换。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow")
public class Workflow extends BaseEntity {

    /** 工作流名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 启用：1=可运行 0=停用 */
    private Integer enabled;
}
