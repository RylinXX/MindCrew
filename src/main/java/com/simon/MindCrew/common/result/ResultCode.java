package com.simon.MindCrew.common.result;

import lombok.Getter;

/**
 * 响应状态码枚举
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    ERROR(500, "服务器内部错误"),
    PARAM_ERROR(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权，请先登录"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),

    // 用户相关
    USER_NOT_FOUND(1001, "用户不存在"),
    USER_PASSWORD_ERROR(1002, "用户名或密码错误"),
    USER_DISABLED(1003, "账号已被禁用"),
    USER_ALREADY_EXISTS(1004, "用户名已存在"),
    PHONE_ALREADY_EXISTS(1005, "手机号已被注册"),

    // 知识库相关
    KNOWLEDGE_UPLOAD_FAIL(2001, "文件上传失败"),
    KNOWLEDGE_PARSE_FAIL(2002, "文件解析失败"),
    KNOWLEDGE_NOT_FOUND(2003, "知识库文档不存在"),

    // 会话相关
    CONVERSATION_NOT_FOUND(3001, "会话不存在"),
    MESSAGE_NOT_FOUND(3002, "消息不存在");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
