package com.easyback.assembly;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class AssemblyData {
    private AssemblyData() {}
    public record Assignment(@NotNull @PositiveOrZero Long version,UUID workerId,@NotNull @Size(max=1000) String notes) {}
    public record Action(@NotNull @PositiveOrZero Long version,@NotNull @Size(max=1000) String notes) {}
    public record Removal(@NotNull @PositiveOrZero Long version,@NotBlank @Size(max=1000) String reason) {}
    public record Person(UUID id,String name,String email) {}
    public record Task(UUID id,UUID requirementId,UUID orderId,String orderCode,String orderTitle,String orderStatus,
        UUID machineId,String machineSerial,String machineName,Instant deliveryAt,String positionCode,int occurrence,
        String partCode,String partName,BigDecimal expectedMinutes,String requirementNotes,
        UUID workerId,String workerName,String status,long version,Instant startedAt,
        UUID pieceId,String serialNumber,String pieceStatus,UUID installationId,Instant installedAt,boolean workRunning,UUID partId) {}
    public record Event(UUID id,String action,String workerName,String actorName,String serialNumber,String notes,Instant createdAt) {}
    public record Detail(Task task,List<Event> events,com.easyback.time.WorkData.Detail workTime) {}
    public record Installation(UUID id,UUID taskId,UUID orderId,String orderCode,String positionCode,int occurrence,
        String serialNumber,String partCode,String partName,String workerName,Instant startedAt,Instant installedAt,
        String installNotes,String removedBy,Instant removedAt,String removalReason) {}
    public record MachineAssembly(UUID machineId,String serialNumber,String name,long configurationVersion,List<Installation> installations) {}
}
