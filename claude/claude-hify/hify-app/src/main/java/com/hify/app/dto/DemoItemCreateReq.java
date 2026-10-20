package com.hify.app.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 创建请求。校验注解的中文消息见 hify-common 的 ValidationMessages.properties。
 */
@Data
public class DemoItemCreateReq {

    @NotBlank
    @Size(max = 64)
    private String name;

    @NotNull
    @Min(0)
    @Max(1)
    private Integer status;
}
