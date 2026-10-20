package com.hify.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 检索测试请求。
 */
@Data
public class RetrievalReq {

    @NotBlank(message = "查询内容不能为空")
    @Size(max = 2000, message = "查询内容最长 2000 字")
    private String query;

    @Min(value = 1, message = "topK 最小 1")
    @Max(value = 10, message = "topK 最大 10")
    private Integer topK;
}
