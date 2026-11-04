package com.inpartnerhub.diligence.project.internal;

import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.ApiPaths;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Enforces the per-project IP allow-list (PRD Bab 10.1) on every project-scoped endpoint for external users (client,
 * target, observer). The client address is the one Tomcat resolved from our trusted proxies
 * (docs/security/client-addresses.md). Beans are looked up lazily so web slice tests run without them.
 */
@Configuration(proxyBeanMethods = false)
class ProjectNetworkConfiguration implements WebMvcConfigurer {

    private static final Pattern PROJECT_PATH = Pattern.compile(
            "^" + Pattern.quote(ApiPaths.V1) + "/projects/([0-9a-fA-F-]{36})(/.*)?$");

    private final ObjectProvider<ProjectNetworkGuard> guard;
    private final ObjectProvider<CurrentUserProvider> currentUser;

    ProjectNetworkConfiguration(ObjectProvider<ProjectNetworkGuard> guard,
            ObjectProvider<CurrentUserProvider> currentUser) {
        this.guard = guard;
        this.currentUser = currentUser;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                check(request);
                return true;
            }
        }).addPathPatterns(ApiPaths.V1 + "/projects/*", ApiPaths.V1 + "/projects/*/**");
    }

    void check(HttpServletRequest request) {
        var projectGuard = guard.getIfAvailable();
        var users = currentUser.getIfAvailable();
        if (projectGuard == null || users == null) {
            return;
        }
        var user = users.currentUser().orElse(null);
        if (user == null || user.roles().isEmpty() || !user.roles().stream().allMatch(Role::isExternal)) {
            return;
        }
        var matcher = PROJECT_PATH.matcher(request.getRequestURI());
        if (!matcher.matches()) {
            return;
        }
        UUID projectId;
        try {
            projectId = UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!projectGuard.permits(projectId, request.getRemoteAddr())) {
            throw new AccessDeniedException("This project can only be accessed from approved network locations");
        }
    }
}
