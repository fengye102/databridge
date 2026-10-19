package com.databridge.verify;

import com.databridge.verify.VerificationRepository.VerificationReport;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks/{taskId}/verifications")
public class VerificationController {
    private final Verifier verifier;
    private final Duration timeout;
    private final int batchSize;

    public VerificationController(Verifier verifier,
            @Value("${databridge.verification-timeout-seconds:30}") long timeoutSeconds,
            @Value("${databridge.verification-batch-size:500}") int batchSize) {
        this.verifier = verifier;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.batchSize = batchSize;
    }

    @PostMapping
    public ResponseEntity<VerificationReport> create(@PathVariable UUID taskId) {
        var report = verifier.verify(taskId, timeout, batchSize);
        return ResponseEntity.created(URI.create(
                "/api/tasks/" + taskId + "/verifications/" + report.runId())).body(report);
    }

    @GetMapping
    public List<VerificationReport> list(@PathVariable UUID taskId) {
        return verifier.reports(taskId);
    }

    @GetMapping("/{runId}")
    public VerificationReport get(@PathVariable UUID taskId, @PathVariable UUID runId) {
        return verifier.report(taskId, runId);
    }
}
