package com.hify.agent.controller;

import com.hify.agent.dto.AgentCreateReq;
import com.hify.agent.dto.AgentResp;
import com.hify.agent.dto.AgentUpdateReq;
import com.hify.agent.service.AgentService;
import com.hify.common.dto.PageResult;
import com.hify.common.dto.Result;

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
 * Agent 接口。
 */
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    /** 分页列表：name 模糊 */
    @GetMapping
    public Result<PageResult<AgentResp>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String name) {
        return Result.ok(agentService.page(page, pageSize, name));
    }

    @PostMapping
    public Result<AgentResp> create(@Valid @RequestBody AgentCreateReq req) {
        return Result.ok(agentService.create(req));
    }

    @GetMapping("/{id}")
    public Result<AgentResp> detail(@PathVariable Long id) {
        return Result.ok(agentService.getDetail(id));
    }

    @PutMapping("/{id}")
    public Result<AgentResp> update(@PathVariable Long id, @Valid @RequestBody AgentUpdateReq req) {
        return Result.ok(agentService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        agentService.delete(id);
        return Result.ok();
    }
}
