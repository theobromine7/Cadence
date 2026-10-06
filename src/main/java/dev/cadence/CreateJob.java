package dev.cadence;

import jakarta.validation.constraints.*;
import java.time.Instant;

public record CreateJob(
    @NotBlank @Size(max = 10000) String payload,
    @Min(0) @Max(9) int priority,
    Instant runAt,
    @Min(0) @Max(31536000) long intervalSeconds,
    @Min(1) @Max(20) int maxAttempts) {}
