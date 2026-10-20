package com.hify.common.dto;

import java.util.Collections;
import java.util.List;

import lombok.Data;

/**
 * 分页响应数据体，包在统一响应 {@code Result<PageResult<T>>} 中返回：
 * <pre>
 * { "code": 200, "message": "success",
 *   "data": { "list": [...], "total": 100, "page": 1, "pageSize": 20 } }
 * </pre>
 * 前端 request.ts 拦截器统一 unwrap data，拿到的正好是本对象。
 *
 * @param <T> 列表元素类型
 */
@Data
public class PageResult<T> {

    /** 当前页数据；空页返回 []，不返回 null */
    private List<T> list;

    /** 总条数（大表用估算值，避免 SELECT COUNT(*)） */
    private long total;

    /** 当前页码，从 1 开始 */
    private int page;

    /** 每页条数 */
    private int pageSize;

    public static <T> PageResult<T> of(List<T> list, long total, int page, int pageSize) {
        PageResult<T> result = new PageResult<>();
        // 列表字段空时返回 []，不返回 null
        result.setList(list == null ? Collections.emptyList() : list);
        result.setTotal(total);
        result.setPage(page);
        result.setPageSize(pageSize);
        return result;
    }
}
