package com.hify.workflow.service;

import java.util.List;

import com.hify.workflow.dto.WorkflowResp;
import com.hify.workflow.dto.WorkflowSaveReq;
import com.hify.workflow.entity.Workflow;

/**
 * 工作流管理服务接口。
 */
public interface WorkflowService {

    /** 列表（含全量节点 / 连线，数量级小不分页） */
    List<WorkflowResp> list();

    /** 详情（含全量节点 / 连线），不存在抛 6001 */
    WorkflowResp getDetail(Long id);

    WorkflowResp create(WorkflowSaveReq req);

    /** 整体图替换：基础信息 + nodes + edges 全量保存 */
    WorkflowResp update(Long id, WorkflowSaveReq req);

    /** 级联清理节点 / 连线 / 运行记录，工作流本体逻辑删除 */
    void delete(Long id);

    /** 供跨服务取实体（运行入口校验用），不存在抛 6001 */
    Workflow getEntity(Long id);

    /**
     * 对话入口契约：start 节点须声明至少一个变量（用户消息填入第一个变量），
     * 且除第一个变量外不允许必填（对话界面无法补充其余输入）。
     * 不满足抛 6011；满足返回第一个变量名。
     */
    String chatEntryVariable(Long workflowId);
}
