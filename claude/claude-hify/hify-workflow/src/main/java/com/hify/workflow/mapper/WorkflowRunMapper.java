package com.hify.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hify.workflow.entity.WorkflowRun;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 工作流运行记录 Mapper。运行历史随工作流删除级联清理（物理删除，纯历史数据）。
 */
public interface WorkflowRunMapper extends BaseMapper<WorkflowRun> {

    @Delete("DELETE FROM workflow_run WHERE workflow_id = #{workflowId}")
    int physicalDeleteByWorkflow(@Param("workflowId") Long workflowId);

    @Select("SELECT * FROM workflow_run WHERE workflow_id = #{workflowId} AND deleted = 0 ORDER BY id DESC LIMIT #{limit}")
    List<WorkflowRun> selectRecentByWorkflow(@Param("workflowId") Long workflowId, @Param("limit") int limit);
}
