package com.localai.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 统一 API 响应体
 *
 * <p>所有 REST 接口都使用此格式返回，方便前端统一处理：
 * <pre>
 *   成功：{"code":200, "message":"ok", "data": {...}}
 *   失败：{"code":500, "message":"错误原因", "data": null}
 * </pre>
 *
 * @param <T> 数据类型
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    /** HTTP 状态码风格的业务码 */
    private int code;

    /** 结果描述 */
    private String message;

    /** 响应数据 */
    private T data;

    /** 请求时间戳（毫秒） */
    private long timestamp;

    private ApiResponse(int code, String message, T data) {
        this.code      = code;
        this.message   = message;
        this.data      = data;
        this.timestamp = System.currentTimeMillis();
    }

    /** 成功（带数据） */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "ok", data);
    }

    /** 成功（无数据） */
    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(200, "ok", null);
    }

    /** 失败 */
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(500, message, null);
    }

    /** 自定义码 */
    public static <T> ApiResponse<T> of(int code, String message, T data) {
        return new ApiResponse<>(code, message, data);
    }
}
