package com.gitsolve.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mirroring the token_usage table.
 * Records per-agent LLM token consumption for budget enforcement and cost reporting.
 */
@Entity
@Table(name = "token_usage")
public class TokenUsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "agent_name", nullable = false)
    private String agentName;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private Integer inputTokens = 0;

    @Column(name = "output_tokens", nullable = false)
    private Integer outputTokens = 0;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    // ------------------------------------------------------------------ //
    // Getters and setters                                                  //
    // ------------------------------------------------------------------ //

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }

    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }

    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
}
