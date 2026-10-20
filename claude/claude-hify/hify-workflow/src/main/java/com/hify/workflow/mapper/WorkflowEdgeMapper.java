package com.hify.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.workflow.entity.WorkflowEdge;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 工作流连线 Mapper。同节点：编排的纯派生数据，保存时整体物理删除重插。
 * selectByWorkflow 按 id 排序——condition 节点出边的求值顺序即此顺序。
 */
public interface WorkflowEdgeMapper extends BaseMapper<WorkflowEdge> {

    @Delete("DELETE FROM workflow_edge WHERE workflow_id = #{workflowId}")
    int physicalDeleteByWorkflow(@Param("workflowId") Long workflowId);

    @Select("SELECT * FROM workflow_edge WHERE workflow_id = #{workflowId} AND deleted = 0 ORDER BY id")
    List<WorkflowEdge> selectByWorkflow(@Param("workflowId") Long workflowId);
}
