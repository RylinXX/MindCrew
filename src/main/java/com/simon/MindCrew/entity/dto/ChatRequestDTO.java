package com.simon.MindCrew.entity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 聊天请求相关 DTO
 */
public class ChatRequestDTO {

    @Data
    public static class SendMessageDTO {
        private Long conversationId;

        @NotBlank(message = "消息内容不能为空")
        private String message;
    }

    @Data
    public static class FeedbackDTO {
        private Long messageId;
        /** 1=有用，-1=无用 */
        private Integer rating;
        private String comment;
    }
}
