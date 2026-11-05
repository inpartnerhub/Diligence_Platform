package com.inpartnerhub.diligence.security.internal;

import java.time.Clock;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;

import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.RequiresRecentSignIn;

/**
 * Applies {@link RequiresRecentSignIn} to Spring beans (#65). Its own configuration, not part of
 * {@link SecurityConfiguration}, so web slice tests that import the filter chain do not proxy their mocks.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StepUpProperties.class)
class StepUpConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor recentSignInAdvisor(ObjectProvider<CurrentUserProvider> users,
            ObjectProvider<StepUpProperties> properties) {
        var pointcut = AnnotationMatchingPointcut.forMethodAnnotation(RequiresRecentSignIn.class);
        var advisor = new DefaultPointcutAdvisor(pointcut,
                new RecentSignInInterceptor(users, properties, Clock.systemUTC()));
        // Before transactions and method security, so nothing is opened for a call that is refused.
        advisor.setOrder(Integer.MIN_VALUE + 100);
        return advisor;
    }
}
