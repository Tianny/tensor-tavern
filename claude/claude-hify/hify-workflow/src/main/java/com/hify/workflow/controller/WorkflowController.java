package com.hify.workflow.controller;

import java.util.List;

import com.hify.common.dto.Result;
import com.hify.workflow.dto.WorkflowResp;
import com.hify.workflow.dto.WorkflowRunReq;
import com.hify.workflow.dto.WorkflowRunResp;
import com.hify.workflow.dto.WorkflowSaveReq;
import com.hify.workflow.service.WorkflowRunService;
import com.hify.workflow.service.WorkflowService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 工作流管理接口。数量级小（团队内部），列表不分页。
 */
@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    private final WorkflowRunService workflowRunService;

    @GetMapping
    public Result<List<WorkflowResp>> list() {
        return Result.ok(workflowService.list());
    }

    @PostMapping
    public Result<WorkflowResp> create(@Valid @RequestBody WorkflowSaveReq req) {
        return Result.ok(workflowService.create(req));
    }

    @GetMapping("/{id}")
    public Result<WorkflowResp> detail(@PathVariable Long id) {
        return Result.ok(workflowService.getDetail(id));
    }

    @PutMapping("/{id}")
    public Result<WorkflowResp> update(@PathVariable Long id, @Valid @RequestBody WorkflowSaveReq req) {
        return Result.ok(workflowService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        workflowService.delete(id);
        return Result.ok();
    }

    /** 提交运行（异步），返回运行记录 id，前端轮询 GET /api/v1/workflow-runs/{runId} */
    @PostMapping("/{id}/run")
    public Result<Long> run(@PathVariable Long id, @Valid @RequestBody WorkflowRunReq req) {
        return Result.ok(workflowRunService.run(id, req));
    }

    /** 最近运行记录（不含轨迹） */
    @GetMapping("/{id}/runs")
    public Result<List<WorkflowRunResp.Summary>> runs(@PathVariable Long id,
            @RequestParam(defaultValue = "20") int limit) {
        return Result.ok(workflowRunService.listRuns(id, limit));
    }
}
