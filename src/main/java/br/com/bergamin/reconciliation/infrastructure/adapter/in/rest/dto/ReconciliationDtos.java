package br.com.bergamin.reconciliation.infrastructure.adapter.in.rest.dto;

import br.com.bergamin.reconciliation.domain.model.Divergence;
import br.com.bergamin.reconciliation.domain.model.Money;
import br.com.bergamin.reconciliation.domain.model.ReconciliationRun;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class ReconciliationDtos {

    private ReconciliationDtos() {
    }

    public record StartedResponse(UUID runId, String status, String statusUrl) {
    }

    public record RunResponse(
            UUID id,
            String salesFile,
            String settlementFile,
            LocalDate referenceDate,
            String status,
            Instant startedAt,
            Instant finishedAt,
            Summary summary,
            String failureReason) {

        public record Summary(
                long salesRead,
                long settlementsRead,
                long matched,
                long divergences,
                long criticalDivergences,
                BigDecimal amountAtRisk,
                BigDecimal matchRate) {
        }

        public static RunResponse from(ReconciliationRun run) {
            var summary = run.summary();
            return new RunResponse(
                    run.id(), run.salesFile(), run.settlementFile(), run.referenceDate(),
                    run.status().name(), run.startedAt(), run.finishedAt(),
                    new Summary(
                            summary.salesRead(), summary.settlementsRead(), summary.matched(),
                            summary.divergences(), summary.criticalDivergences(),
                            summary.amountAtRisk().amount(), summary.matchRate()),
                    run.failureReason());
        }
    }

    public record DivergenceResponse(
            String type,
            String severity,
            String description,
            String transactionId,
            String orderReference,
            BigDecimal expectedAmount,
            BigDecimal foundAmount,
            BigDecimal difference,
            String details) {

        public static DivergenceResponse from(Divergence divergence) {
            return new DivergenceResponse(
                    divergence.type().name(),
                    divergence.type().severity().name(),
                    divergence.type().description(),
                    divergence.transactionId(),
                    divergence.orderReference(),
                    amount(divergence.expectedAmount()),
                    amount(divergence.foundAmount()),
                    amount(divergence.difference()),
                    divergence.details());
        }

        private static BigDecimal amount(Money money) {
            return money == null ? null : money.amount();
        }
    }
}
