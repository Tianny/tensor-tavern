package com.hify.workflow.controller;

import com.hify.common.dto.Result;
import com.hify.workflow.dto.WorkflowRunResp;
import com.hify.workflow.service.WorkflowRunService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 工作流运行记录接口（运行轮询 / 查看轨迹）。
 */
@RestController
@RequestMapping("/api/v1/workflow-runs")
@RequiredArgsConstructor
public class WorkflowRunController {

    private final WorkflowRunService workflowRunService;

    @GetMapping("/{id}")
    public Result<WorkflowRunResp> detail(@PathVariable Long id) {
        return Result.ok(workflowRunService.getRun(id));
    }
}
