package com.aisys.common.core.response;

/**
 * 错误码契约（DDD 3.4）。6 位数字编码 SSSEEE：
 * SSS=服务号(100 Auth…900 Monitor)，EEE=序号。
 */
public interface ErrorCode {
    int code();
    String message();
}
