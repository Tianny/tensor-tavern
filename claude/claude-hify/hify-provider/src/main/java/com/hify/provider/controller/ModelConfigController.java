package com.hify.provider.controller;

import com.hify.common.dto.PageResult;
import com.hify.common.dto.Result;
import com.hify.provider.dto.ModelConfigCreateReq;
import com.hify.provider.dto.ModelConfigResp;
import com.hify.provider.dto.ModelConfigUpdateReq;
import com.hify.provider.service.ModelConfigService;

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
 * 模型配置接口。拉取远端模型列表挂在 provider 下（依赖 provider 的鉴权配置）：
 * GET /api/v1/providers/{providerId}/remote-models。
 */
@RestController
@RequestMapping("/api/v1/model-configs")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelConfigService modelConfigService;

    /** 分页列表：providerId / modelType 过滤 */
    @GetMapping
    public Result<PageResult<ModelConfigResp>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Long providerId,
            @RequestParam(required = false) String modelType) {
        return Result.ok(modelConfigService.page(page, pageSize, providerId, modelType));
    }

    @PostMapping
    public Result<ModelConfigResp> create(@Valid @RequestBody ModelConfigCreateReq req) {
        return Result.ok(modelConfigService.create(req));
    }

    @PutMapping("/{id}")
    public Result<ModelConfigResp> update(@PathVariable Long id, @Valid @RequestBody ModelConfigUpdateReq req) {
        return Result.ok(modelConfigService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        modelConfigService.delete(id);
        return Result.ok();
    }
}
