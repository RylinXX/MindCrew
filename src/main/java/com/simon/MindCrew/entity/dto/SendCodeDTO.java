package com.simon.MindCrew.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 发送验证码请求
 */
@Data
public class SendCodeDTO {

    @NotBlank(message = "用户名不能为空")
    private String username;
}
