package com.lumen.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumen.common.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private final AuditLogMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Around("@annotation(com.lumen.common.audit.Audit)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method m = sig.getMethod();
        Audit ann = m.getAnnotation(Audit.class);
        AuditLog rec = new AuditLog();
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest req = attrs.getRequest();
                rec.setUri(req.getRequestURI());
                rec.setMethod(req.getMethod());
                Object tidAttr = req.getAttribute("lumen.traceId");
                if (tidAttr != null) {
                    rec.setTraceId(tidAttr.toString());
                }
                Object tenantAttr = req.getAttribute("lumen.tenantId");
                if (tenantAttr != null) {
                    rec.setTenantId(((Number) tenantAttr).longValue());
                }
                if (ann.recordRequest()) {
                    rec.setRequest(truncate(toJson(pjp.getArgs()), 2000));
                }
            }
            rec.setAction(ann.action().isEmpty() ? m.getName() : ann.action());
            rec.setResource(ann.resource());
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                rec.setUsername(auth.getName());
                try {
                    rec.setUserId(Long.parseLong(auth.getName()));
                } catch (NumberFormatException ignored) {
                    // username is not a numeric id, leave userId null
                }
            }
            if (rec.getTenantId() == null) {
                rec.setTenantId(TenantContext.get());
            }
            Object result = pjp.proceed();
            rec.setStatus(1);
            if (ann.recordResponse()) {
                rec.setResponse(truncate(toJson(result), 2000));
            }
            return result;
        } catch (Throwable ex) {
            rec.setStatus(0);
            rec.setErrorMsg(ex.getMessage());
            throw ex;
        } finally {
            rec.setCostMs(System.currentTimeMillis() - start);
            rec.setCreatedAt(LocalDateTime.now());
            saveAsync(rec);
        }
    }

    @Async(AuditConstants.POOL_NAME)
    public void saveAsync(AuditLog rec) {
        try {
            mapper.insert(rec);
        } catch (Exception e) {
            log.warn("audit log save failed", e);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}