package com.agentmemorystore.presentation.controller;

import com.agentmemorystore.application.dto.JobStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for managing the Memory Consolidation Spring Batch job.
 */
@RestController
@RequestMapping("/api/v1/jobs/consolidation")
@Tag(name = "Consolidation Job", description = "Endpoints for triggering and monitoring the semantic consolidation batch job")
public class ConsolidationJobController {

    private static final Logger log = LoggerFactory.getLogger(ConsolidationJobController.class);

    private final TaskExecutorJobLauncher jobLauncher;
    private final Job consolidationJob;
    private final JobExplorer jobExplorer;

    public ConsolidationJobController(TaskExecutorJobLauncher jobLauncher,
                                      Job consolidationJob,
                                      JobExplorer jobExplorer) {
        this.jobLauncher = jobLauncher;
        this.consolidationJob = consolidationJob;
        this.jobExplorer = jobExplorer;
    }

    @PostMapping
    @Operation(
            summary = "Trigger the memory consolidation job",
            description = "Asynchronously starts the Spring Batch job to consolidate old episodic memories into semantic ones."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Job accepted and started asynchronously"),
            @ApiResponse(responseCode = "500", description = "Failed to launch job")
    })
    public ResponseEntity<JobStatusResponse> startConsolidationJob() {
        try {
            // Pitfall fix: Use a unique run.id to prevent JobInstanceAlreadyCompleteException
            JobExecution execution = jobLauncher.run(consolidationJob, new JobParametersBuilder()
                    .addLong("run.id", System.currentTimeMillis())
                    .toJobParameters());

            JobStatusResponse response = new JobStatusResponse(
                    execution.getId(),
                    execution.getStatus().name(),
                    "/api/v1/jobs/consolidation/" + execution.getId() + "/status"
            );

            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);

        } catch (Exception e) {
            log.error("Failed to start consolidation job", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/{executionId}/status")
    @Operation(
            summary = "Check job execution status",
            description = "Poll the status of a previously triggered consolidation job."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Job status retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Job execution ID not found")
    })
    public ResponseEntity<JobStatusResponse> getJobStatus(@PathVariable long executionId) {
        JobExecution execution = jobExplorer.getJobExecution(executionId);

        if (execution == null) {
            return ResponseEntity.notFound().build();
        }

        JobStatusResponse response = new JobStatusResponse(
                execution.getId(),
                execution.getStatus().name(),
                "/api/v1/jobs/consolidation/" + execution.getId() + "/status"
        );

        return ResponseEntity.ok(response);
    }
}
