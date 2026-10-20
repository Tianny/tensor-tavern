package com.hify.app.controller;

import com.hify.app.dto.DemoItemCreateReq;
import com.hify.app.dto.DemoItemResp;
import com.hify.app.dto.DemoItemUpdateReq;
import com.hify.app.service.DemoItemService;

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

/**
 * CRUD 流程演示接口，验证从 DTO 校验到逻辑删除的完整链路。
 * Controller 只做参数校验和调用 Service，业务规则在 Service 层。
 */
@RestController
@RequestMapping("/api/v1/demo-items")
public class DemoItemController {

    private final DemoItemService demoItemService;

    public DemoItemController(DemoItemService demoItemService) {
        this.demoItemService = demoItemService;
    }

    /** 分页列表，name 可选模糊过滤 */
    @GetMapping
    public Result<PageResult<DemoItemResp>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String name) {
        return Result.ok(demoItemService.list(page, pageSize, name));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody DemoItemCreateReq req) {
        return Result.ok(demoItemService.create(req));
    }

    @GetMapping("/{id}")
    public Result<DemoItemResp> get(@PathVariable Long id) {
        return Result.ok(demoItemService.getById(id));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody DemoItemUpdateReq req) {
        demoItemService.update(id, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        demoItemService.delete(id);
        return Result.ok();
    }
}
