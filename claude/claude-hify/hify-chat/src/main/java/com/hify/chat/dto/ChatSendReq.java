package com.hify.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 发送消息请求（响应是 SSE 流，不是 Result JSON）。
 */
@Data
public class ChatSendReq {

    @NotBlank
    @Size(max = 8000)
    private String content;
}
