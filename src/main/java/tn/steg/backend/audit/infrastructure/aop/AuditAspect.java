package tn.steg.backend.audit.infrastructure.aop;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tn.steg.backend.audit.application.AuditService;

import java.lang.reflect.Method;
import java.util.UUID;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @Around("@annotation(tn.steg.backend.audit.infrastructure.aop.Audited)")
    public Object auditMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Audited audited = method.getAnnotation(Audited.class);

        String action = audited.action();
        String entityType = audited.entityType();

        Object[] args = joinPoint.getArgs();
        UUID entityId = extractEntityId(args);
        String ipAddress = extractIpAddress();

        UUID actorId = extractActorId();

        Object result = joinPoint.proceed();

        auditService.log(action, entityType, entityId, null, result, actorId, ipAddress);

        return result;
    }

    private UUID extractEntityId(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof UUID uuid) {
                return uuid;
            }
            if (arg instanceof HasId hasId) {
                return hasId.getId();
            }
        }
        return null;
    }

    private UUID extractActorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof tn.steg.backend.common.domain.model.UserPrincipal principal) {
            return principal.getId();
        }
        return null;
    }

    private String extractIpAddress() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                String xForwardedFor = request.getHeader("X-Forwarded-For");
                if (xForwardedFor != null && !xForwardedFor.isBlank()) {
                    return xForwardedFor.split(",")[0].trim();
                }
                return request.getRemoteAddr();
            }
        } catch (Exception ex) {
            log.debug("Could not extract IP address: {}", ex.getMessage());
        }
        return null;
    }

    public interface HasId {
        UUID getId();
    }
}
