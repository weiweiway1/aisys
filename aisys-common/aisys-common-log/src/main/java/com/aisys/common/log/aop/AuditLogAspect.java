package com.aisys.common.log.aop;

import com.aisys.common.core.context.UserContext;
import com.aisys.common.log.annotation.AuditLog;
import com.aisys.common.log.mapper.AuditLogMapper;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * 审计日志切面（DDD 8.3）。拦截 {@link AuditLog}，记录操作人/资源/前后差异/IP/UA，
 * 异步写入 audit_log（独立事务，不阻塞业务）。
 */
@Aspect
@Component
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;
    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer pnd = new DefaultParameterNameDiscoverer();

    @Autowired
    public AuditLogAspect(AuditLogMapper auditLogMapper, ObjectMapper objectMapper) {
        this.auditLogMapper = auditLogMapper;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {
        Object result = null;
        boolean success = true;
        String errorMsg = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            success = false;
            errorMsg = t.getMessage();
            throw t;
        } finally {
            try {
                String resourceId = evalResourceId(auditLog, pjp, result);
                writeAsync(auditLog, resourceId, success, errorMsg);
            } catch (Exception e) {
                log.warn("写审计日志失败: {}", e.getMessage());
            }
        }
    }

    private String evalResourceId(AuditLog auditLog, ProceedingJoinPoint pjp, Object result) {
        String expr = auditLog.resourceId();
        if (expr == null || expr.isBlank()) return null;
        try {
            MethodSignature sig = (MethodSignature) pjp.getSignature();
            Method method = sig.getMethod();
            EvaluationContext ctx = new StandardEvaluationContext();
            String[] names = pnd.getParameterNames(method);
            Object[] args = pjp.getArgs();
            if (names != null) {
                for (int i = 0; i < names.length; i++) {
                    ctx.setVariable(names[i], args[i]);
                }
            }
            if (result != null) {
                ctx.setVariable("result", result);
            }
            Expression e = parser.parseExpression(expr);
            Object v = e.getValue(ctx);
            return v == null ? null : String.valueOf(v);
        } catch (Exception ex) {
            return null;
        }
    }

    @Async("auditLogExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeAsync(AuditLog auditLog, String resourceId, boolean success, String errorMsg) {
        UserContext.CurrentUser user = UserContext.get();
        Long tenantId = user == null ? null : user.tenantId();
        Long userId = user == null ? null : user.userId();
        String username = user == null ? null : user.username();
        HttpServletRequest req = currentRequest();
        String ip = req == null ? null : clientIp(req);
        String ua = req == null ? null : req.getHeader("User-Agent");

        String detail = null;
        try {
            if (!success && errorMsg != null) {
                detail = objectMapper.writeValueAsString(
                        java.util.Map.of("success", false, "error", errorMsg));
            } else {
                detail = "{\"success\":" + success + "}";
            }
        } catch (Exception ignored) {
            detail = "{\"success\":" + success + "}";
        }

        auditLogMapper.insert(tenantId, "USER", userId, username,
                success ? auditLog.action() : auditLog.action() + "_FAILED",
                auditLog.resource(), resourceId, detail, ip, ua);
    }

    private static HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
