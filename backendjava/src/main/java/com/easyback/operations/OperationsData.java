package com.easyback.operations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class OperationsData {
    private OperationsData() {}
    public record CheckInput(@NotBlank @Size(max=200) String label,@NotBlank @Size(max=500) String criterion) {}
    public record Start(@NotNull @PositiveOrZero Long orderVersion,@NotNull @PositiveOrZero Long configurationVersion,
        @NotEmpty @Size(max=30) List<@NotNull @Valid CheckInput> checks,@NotNull @Size(max=1000) String notes) {}
    public enum Outcome { PASSED, FAILED }
    public enum CheckResult { PASS, FAIL }
    public record Result(@NotNull UUID id,@NotNull CheckResult result,@NotNull @Size(max=1000) String notes) {}
    public record Finish(@NotNull @PositiveOrZero Long version,@NotNull Outcome outcome,@NotBlank @Size(max=1000) String notes,
        @NotEmpty @Size(max=30) List<@NotNull @Valid Result> checks) {}
    public record Action(@NotNull @PositiveOrZero Long version,@NotBlank @Size(max=1000) String notes) {}
    public record Resolution(@NotNull @PositiveOrZero Long version,@NotNull @PositiveOrZero Long orderVersion,@NotBlank @Size(max=1000) String notes) {}
    public record Summary(UUID id,String code,String title,UUID machineId,String machineSerial,String machineName,String status,
        long version,long configurationVersion,long requiredCount,long installedCount,long blockingDefects,boolean reworkRequired,
        Instant deliveryAt,Instant assemblyCompletedAt,Instant testedAt,Instant completedAt,Instant deliveredAt,String deliveryNotes,UUID latestTrialId) {}
    public record Check(UUID id,String label,String criterion,String result,String notes) {}
    public record Configuration(UUID installationId,String positionCode,int occurrence,String serialNumber,String partCode,String partName) {}
    public record Trial(UUID id,UUID orderId,long configurationVersion,String status,long version,String startedBy,Instant startedAt,String startNotes,
        String finishedBy,Instant finishedAt,String resultNotes,String resolvedBy,Instant resolvedAt,String resolutionNotes,List<Check> checks,List<Configuration> configuration) {}
    public record Event(UUID id,UUID trialId,String action,String actorName,String notes,Instant createdAt) {}
    public record Detail(Summary order,List<Trial> trials,List<Event> events) {}
}
