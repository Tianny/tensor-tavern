package com.hify.app.dto;

import java.time.LocalDateTime;

import com.hify.app.entity.DemoItem;

import lombok.Data;

/**
 * 响应 DTO：对外字段与实体解耦，时间按 JacksonConfig 全局 ISO 格式序列化。
 */
@Data
public class DemoItemResp {

    private Long id;

    private String name;

    private Integer status;

    private LocalDateTime createdAt;

    public static DemoItemResp of(DemoItem entity) {
        DemoItemResp resp = new DemoItemResp();
        resp.setId(entity.getId());
        resp.setName(entity.getName());
        resp.setStatus(entity.getStatus());
        resp.setCreatedAt(entity.getCreatedAt());
        return resp;
    }
}
