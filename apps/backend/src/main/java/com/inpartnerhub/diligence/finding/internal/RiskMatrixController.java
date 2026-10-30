package com.inpartnerhub.diligence.finding.internal;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.inpartnerhub.diligence.finding.RiskMatrix;
import com.inpartnerhub.diligence.shared.web.ApiPaths;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@RestController
@Tag(name = "Findings")
class RiskMatrixController {

    record MatrixDto(@NotNull @Min(2) @Max(25) Integer mediumFrom, @NotNull @Min(2) @Max(25) Integer highFrom,
            @NotNull @Min(2) @Max(25) Integer criticalFrom) {
    }

    record UpdateResponse(MatrixDto matrix, int rerated) {
    }

    private final RiskMatrixService matrix;

    RiskMatrixController(RiskMatrixService matrix) {
        this.matrix = matrix;
    }

    @GetMapping(ApiPaths.V1 + "/risk-matrix")
    @Operation(summary = "Current impact × likelihood thresholds (score at or above reaches the level) — FND-03")
    MatrixDto current() {
        return dto(matrix.current());
    }

    @PutMapping(ApiPaths.V1 + "/admin/risk-matrix")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Change the thresholds; open findings are re-rated, released ones keep their level — FND-03")
    UpdateResponse update(@Valid @RequestBody MatrixDto request) {
        var rerated = matrix.update(request.mediumFrom(), request.highFrom(), request.criticalFrom());
        return new UpdateResponse(dto(matrix.current()), rerated);
    }

    private static MatrixDto dto(RiskMatrix m) {
        return new MatrixDto(m.mediumFrom(), m.highFrom(), m.criticalFrom());
    }
}
