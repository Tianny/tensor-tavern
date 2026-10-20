package com.hify.provider.controller;

import java.util.List;

import com.hify.common.dto.PageResult;
import com.hify.common.dto.Result;
import com.hify.provider.dto.ProviderCreateReq;
import com.hify.provider.dto.ProviderResp;
import com.hify.provider.dto.ProviderUpdateReq;
import com.hify.provider.dto.TestConnectionResp;
import com.hify.provider.service.ModelConfigService;
import com.hify.provider.service.ProviderService;

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
 * Provider 接口。
 */
@RestController
@RequestMapping("/api/v1/providers")
@RequiredArgsConstructor
public class ProviderController {

    private final ProviderService providerService;

    private final ModelConfigService modelConfigService;

    /** 分页列表：name 模糊、type 精确 */
    @GetMapping
    public Result<PageResult<ProviderResp>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String type) {
        return Result.ok(providerService.page(page, pageSize, name, type));
    }

    @PostMapping
    public Result<ProviderResp> create(@Valid @RequestBody ProviderCreateReq req) {
        return Result.ok(providerService.create(req));
    }

    @GetMapping("/{id}")
    public Result<ProviderResp> detail(@PathVariable Long id) {
        return Result.ok(providerService.getDetail(id));
    }

    @PutMapping("/{id}")
    public Result<ProviderResp> update(@PathVariable Long id, @Valid @RequestBody ProviderUpdateReq req) {
        return Result.ok(providerService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        providerService.delete(id);
        return Result.ok();
    }

    /** 连通性测试：非 CRUD 操作用动词 */
    @PostMapping("/{id}/test-connection")
    public Result<TestConnectionResp> testConnection(@PathVariable Long id) {
        return Result.ok(providerService.testConnection(id));
    }

    /** 从提供商拉取可用模型名列表（只读远端，入库由 model-configs 接口完成） */
    @GetMapping("/{id}/remote-models")
    public Result<List<String>> remoteModels(@PathVariable Long id) {
        return Result.ok(modelConfigService.fetchRemoteModels(id));
    }
}
