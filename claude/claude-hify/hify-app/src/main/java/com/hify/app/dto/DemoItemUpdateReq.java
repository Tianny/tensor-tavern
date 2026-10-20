package com.hify.app.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 更新请求。PUT 语义：全量替换。
 */
@Data
public class DemoItemUpdateReq {

    @NotBlank
    @Size(max = 64)
    private String name;

    @NotNull
    @Min(0)
    @Max(1)
    private Integer status;
}
