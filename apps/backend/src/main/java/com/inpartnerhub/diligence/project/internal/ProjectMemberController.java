package com.inpartnerhub.diligence.project.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.inpartnerhub.diligence.admin.Directory.UserSummary;
import com.inpartnerhub.diligence.admin.Directory;
import com.inpartnerhub.diligence.project.ProjectRole;
import com.inpartnerhub.diligence.shared.web.ApiPaths;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping(ApiPaths.V1 + "/projects/{projectId}/members")
@Tag(name = "Projects")
class ProjectMemberController {

    record MemberResponse(UUID id, UUID userId, String displayName, String email, ProjectRole role,
            UUID workstreamId, Instant accessExpiresAt, Instant createdAt) {
    }

    record AddMemberRequest(@NotNull UUID userId, @NotNull ProjectRole role, UUID workstreamId,
            Instant accessExpiresAt) {
    }

    private final MemberService members;
    private final Directory directory;

    ProjectMemberController(MemberService members, Directory directory) {
        this.members = members;
        this.directory = directory;
    }

    @GetMapping
    @Operation(summary = "Project team. External users see only their own membership (row-level security) — PRJ-02")
    List<MemberResponse> list(@PathVariable UUID projectId) {
        var list = members.list(projectId);
        var users = directory.findUsers(list.stream().map(ProjectMember::getUserId).distinct().toList()).stream()
                .collect(Collectors.toMap(UserSummary::id, Function.identity()));
        return list.stream().map(m -> {
            var user = users.get(m.getUserId());
            return new MemberResponse(m.getId(), m.getUserId(), user == null ? null : user.displayName(),
                    user == null ? null : user.email(), m.getRole(), m.getWorkstreamId(), m.getAccessExpiresAt(),
                    m.getCreatedAt());
        }).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a team member with a project role, optionally for one workstream — PRJ-02")
    MemberResponse add(@PathVariable UUID projectId, @Valid @RequestBody AddMemberRequest request) {
        var member = members.add(projectId, new MemberService.AddCommand(request.userId(), request.role(),
                request.workstreamId(), request.accessExpiresAt()));
        var user = directory.findUser(member.getUserId()).orElse(null);
        return new MemberResponse(member.getId(), member.getUserId(), user == null ? null : user.displayName(),
                user == null ? null : user.email(), member.getRole(), member.getWorkstreamId(),
                member.getAccessExpiresAt(), member.getCreatedAt());
    }

    @DeleteMapping("/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a team member — PRJ-02")
    void remove(@PathVariable UUID projectId, @PathVariable UUID memberId) {
        members.remove(projectId, memberId);
    }
}
