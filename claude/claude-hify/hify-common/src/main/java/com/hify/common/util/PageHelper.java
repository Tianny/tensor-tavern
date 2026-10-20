package com.hify.common.util;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hify.common.dto.PageResult;

/**
 * 分页工具：前端分页参数与 MyBatis-Plus Page / 统一响应 PageResult 之间的转换。
 * 契约（CLAUDE.md 接口规范）：page 从 1 开始，pageSize 默认 20、最大 100。
 */
public final class PageHelper {

    /** pageSize 默认值 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** pageSize 上限 */
    public static final int MAX_PAGE_SIZE = 100;

    private PageHelper() {
    }

    /**
     * 前端分页参数转 MyBatis-Plus 分页对象。
     * null 或非法值一律按契约归位：page 取 1，pageSize 取默认值，超上限截到 100。
     *
     * @param page     页码，从 1 开始，可为 null（可选查询参数）
     * @param pageSize 每页条数，可为 null
     */
    public static <T> Page<T> toPage(Integer page, Integer pageSize) {
        int current = (page == null || page < 1) ? 1 : page;
        int size;
        if (pageSize == null || pageSize < 1) {
            size = DEFAULT_PAGE_SIZE;
        } else {
            size = Math.min(pageSize, MAX_PAGE_SIZE);
        }
        return new Page<>(current, size);
    }

    /**
     * 查询结果转分页数据体。Service 返回本对象，Controller 包一层 {@code Result.ok(...)}，
     * 最终 HTTP 响应为 {@code Result<PageResult<T>>} 嵌套结构。
     */
    public static <T> PageResult<T> toPageResult(IPage<T> pageResult) {
        return PageResult.of(pageResult.getRecords(), pageResult.getTotal(),
                (int) pageResult.getCurrent(), (int) pageResult.getSize());
    }
}
