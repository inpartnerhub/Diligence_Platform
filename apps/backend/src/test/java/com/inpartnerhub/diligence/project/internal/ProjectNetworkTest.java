package com.inpartnerhub.diligence.project.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

import com.inpartnerhub.diligence.project.ProjectEvents.ProjectAccessBlocked;
import com.inpartnerhub.diligence.security.AuthenticatedUser;
import com.inpartnerhub.diligence.security.CurrentUserProvider;
import com.inpartnerhub.diligence.security.Role;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** PRD Bab 10.1: per-project IP allow-list for external users. */
class ProjectNetworkTest {

    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ProjectNetworkGuard guard = new ProjectNetworkGuard(projects, events, meters);

    private final Project project = new Project("DD-1", "Alpha", UUID.randomUUID(), UUID.randomUUID(), null, null,
            null, UUID.randomUUID());

    @Test
    void matchesIpv4AndIpv6Ranges() {
        assertThat(IpRange.parse("203.0.113.0/24").contains("203.0.113.77")).isTrue();
        assertThat(IpRange.parse("203.0.113.0/24").contains("203.0.114.1")).isFalse();
        assertThat(IpRange.parse("198.51.100.7").contains("198.51.100.7")).isTrue();
        assertThat(IpRange.parse("198.51.100.7").contains("198.51.100.8")).isFalse();
        assertThat(IpRange.parse("10.0.0.0/9").contains("10.127.255.255")).isTrue();
        assertThat(IpRange.parse("10.0.0.0/9").contains("10.128.0.0")).isFalse();
        assertThat(IpRange.parse("2001:db8::/32").contains("2001:db8:1::5")).isTrue();
        assertThat(IpRange.parse("2001:db8::/32").contains("2001:db9::5")).isFalse();
        assertThat(IpRange.parse("203.0.113.0/24").contains("2001:db8::1")).isFalse();
        assertThat(IpRange.parse("0.0.0.0/0").contains("8.8.8.8")).isTrue();
    }

    @Test
    void rejectsInvalidRangesAndNeverResolvesHostnames() {
        for (var invalid : List.of("example.com", "10.0.0.0/33", "2001:db8::/129", "10.0.0.0/x", "", "1.2.3.4/-1")) {
            assertThatThrownBy(() -> IpRange.parse(invalid)).as(invalid).isInstanceOf(InvalidRequestException.class);
        }
        assertThat(IpRange.parse("10.0.0.0/8").contains("localhost")).isFalse();
    }

    @Test
    void projectStoresANormalisedAllowList() {
        assertThat(project.allowsAddress("8.8.8.8")).isTrue(); // no list, no restriction

        project.updateAllowedIpRanges(List.of(" 203.0.113.0/24 ", "", "203.0.113.0/24", "2001:db8::/32"));
        assertThat(project.getAllowedIpRanges()).containsExactly("203.0.113.0/24", "2001:db8::/32");
        assertThat(project.allowsAddress("203.0.113.9")).isTrue();
        assertThat(project.allowsAddress("8.8.8.8")).isFalse();

        assertThatThrownBy(() -> project.updateAllowedIpRanges(List.of("not-an-ip")))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> project.updateAllowedIpRanges(manyRanges()))
                .isInstanceOf(InvalidRequestException.class);

        project.updateAllowedIpRanges(List.of());
        assertThat(project.allowsAddress("8.8.8.8")).isTrue();
    }

    @Test
    void blocksExternalUsersOutsideTheAllowListAndAuditsIt() {
        project.updateAllowedIpRanges(List.of("203.0.113.0/24"));
        when(projects.findById(project.getId())).thenReturn(Optional.of(project));
        var configuration = configuration();

        as(Role.TARGET);
        configuration.check(request(project.getId(), "203.0.113.20"));
        verify(events, never()).publishEvent(any(Object.class));

        assertThatThrownBy(() -> configuration.check(request(project.getId(), "198.51.100.1")))
                .isInstanceOf(AccessDeniedException.class);
        verify(events).publishEvent(any(ProjectAccessBlocked.class));
        assertThat(meters.counter("diligence.project.access.blocked").count()).isEqualTo(1.0);
    }

    @Test
    void internalTeamIsNotRestricted() {
        project.updateAllowedIpRanges(List.of("203.0.113.0/24"));
        when(projects.findById(project.getId())).thenReturn(Optional.of(project));
        var configuration = configuration();

        as(Role.ANALYST);
        configuration.check(request(project.getId(), "198.51.100.1"));
        as(Role.SUPER_ADMIN);
        configuration.check(request(project.getId(), "198.51.100.1"));
        verify(projects, never()).findById(any());
    }

    private static List<String> manyRanges() {
        return java.util.stream.IntStream.range(0, Project.MAX_IP_RANGES + 1).mapToObj(i -> "10.0." + i + ".0/24")
                .toList();
    }

    private ProjectNetworkConfiguration configuration() {
        return new ProjectNetworkConfiguration(provider(guard), provider(currentUser));
    }

    private void as(Role role) {
        when(currentUser.currentUser()).thenReturn(Optional.of(
                new AuthenticatedUser(UUID.randomUUID(), "u", "u@x.test", "U", Set.of(role))));
    }

    private static MockHttpServletRequest request(UUID projectId, String ip) {
        var request = new MockHttpServletRequest("GET", "/api/v1/projects/" + projectId + "/folders");
        request.setRemoteAddr(ip);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        var provider = (ObjectProvider<T>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }
}
