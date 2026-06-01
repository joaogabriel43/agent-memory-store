package com.agentmemorystore.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response payload for job execution status")
public record JobStatusResponse(

        @Schema(description = "Unique ID of the job execution", example = "123")
        long jobExecutionId,

        @Schema(description = "Current status of the job execution", example = "RUNNING")
        String status,

        @Schema(description = "URL to poll for job status", example = "/api/v1/jobs/consolidation/123/status")
        String statusUrl
) {
}
