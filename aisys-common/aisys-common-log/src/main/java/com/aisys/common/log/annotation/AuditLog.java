package com.aisys.common.log.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计日志注解（DDD 8.3）。标注于 Controller/Service 方法上，由 {@code AuditLogAspect} 切面记录。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** 操作类型，如 CREATE / UPDATE / DELETE / LOGIN */
    String action();

    /** 资源类型，如 MODEL / DATASET / TASK */
    String resource();

    /** 操作描述 */
    String description() default "";

    /** 资源ID的 SpEL 表达式（可选），如 "#id" / "#result.id"。空表示不记录具体ID */
    String resourceId() default "";
}
