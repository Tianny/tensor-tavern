package com.hify.workflow.service;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.hify.workflow.dto.WorkflowRunReq;
import com.hify.workflow.dto.WorkflowRunResp;
import com.hify.workflow.entity.WorkflowRun;

/**
 * 工作流运行服务接口。运行异步执行（LLM 节点走 llmExecutor），
 * 提交后返回 runId，前端轮询 getRun。
 */
public interface WorkflowRunService {

    /** 提交运行，返回运行记录 id。必填 start 变量缺失同步报错（6009） */
    Long run(Long workflowId, WorkflowRunReq req);

    /**
     * 在调用线程上同步执行（引擎不再二次提交线程池）。
     * 供对话链路复用：ChatServiceImpl 的流式段已在 llmExecutor 上，符合线程池规则。
     *
     * @param progress 节点执行进度回调（推给对话 SSE），可为 null
     * @return 执行完成的运行记录（终态）
     */
    WorkflowRun runInline(Long workflowId, Map<String, String> inputs, Consumer<String> progress);

    /** 运行详情（含节点轨迹），不存在抛 6006 */
    WorkflowRunResp getRun(Long runId);

    /** 某工作流最近运行记录（不含轨迹） */
    List<WorkflowRunResp.Summary> listRuns(Long workflowId, int limit);
}
