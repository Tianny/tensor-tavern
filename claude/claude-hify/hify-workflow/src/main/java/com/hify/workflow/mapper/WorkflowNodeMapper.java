package com.hify.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.workflow.entity.WorkflowNode;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 工作流节点 Mapper。节点是编排的纯派生数据（workflow 持有全量图），
 * 保存时整体替换：物理删除旧节点再插入新图。
 */
public interface WorkflowNodeMapper extends BaseMapper<WorkflowNode> {

    @Delete("DELETE FROM workflow_node WHERE workflow_id = #{workflowId}")
    int physicalDeleteByWorkflow(@Param("workflowId") Long workflowId);

    @Select("SELECT * FROM workflow_node WHERE workflow_id = #{workflowId} AND deleted = 0 ORDER BY id")
    List<WorkflowNode> selectByWorkflow(@Param("workflowId") Long workflowId);
}
