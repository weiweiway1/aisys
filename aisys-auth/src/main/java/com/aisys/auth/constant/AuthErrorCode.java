package com.aisys.auth.constant;

import com.aisys.common.core.response.ErrorCode;

/** Auth Service 错误码（服务号 100，DDD 3.4）。 */
public enum AuthErrorCode implements ErrorCode {
    INVALID_CREDENTIALS(100001, "用户名或密码错误"),
    USER_DISABLED(100002, "用户已被禁用"),
    USERNAME_EXISTS(100003, "用户名已存在"),
    USER_NOT_FOUND(100004, "用户不存在"),
    REFRESH_TOKEN_INVALID(100005, "刷新令牌无效或已过期"),
    REFRESH_TOKEN_REUSE_DETECTED(100006, "刷新令牌已被使用，疑似泄露，请重新登录"),
    OLD_PASSWORD_WRONG(100007, "原密码错误"),
    TENANT_CODE_EXISTS(100008, "租户编码已存在"),
    TENANT_NOT_FOUND(100009, "租户不存在"),
    NO_PERMISSION(100010, "无操作权限"),
    REGISTER_DISABLED(100011, "注册功能未开放"),
    BAD_REQUEST(100012, "请求参数错误");

    private final int code;
    private final String message;

    AuthErrorCode(int code, String message) { this.code = code; this.message = message; }
    @Override public int code() { return code; }
    @Override public String message() { return message; }
}
