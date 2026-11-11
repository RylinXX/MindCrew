package com.simon.MindCrew.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求 DTO
 */
@Data
public class RegisterDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 20, message = "用户名长度为3-20个字符")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度为6-20个字符")
    private String password;

    private String nickname;

    /** 任务 7 · 注册时可选：部门 ID（可空，注册后管理员可调整） */
    private Long departmentId;

    /** 任务 7 · 注册时可选：职位 ID（可空 → 仅能看 public KB） */
    private Long positionId;
}
