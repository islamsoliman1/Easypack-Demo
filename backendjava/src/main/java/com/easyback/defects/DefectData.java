package com.easyback.defects;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class DefectData {
    private DefectData() {}
    public record Action(@NotNull @PositiveOrZero Long version,@NotBlank @Size(max=1000) String notes) {}
    public record Receipt(@NotNull @PositiveOrZero Long version,@NotBlank @Size(max=1000) String notes,@NotBlank @Size(max=120) String location) {}
    public record Case(UUID id,UUID taskId,UUID pieceId,UUID orderId,String orderCode,UUID machineId,String machineSerial,String positionCode,int occurrence,
        String serialNumber,UUID partId,String partCode,String partName,UUID reportedBy,String reporterName,String reason,String status,String pieceStatus,long version,Instant reportedAt) {}
    public record Event(UUID id,String action,String actorName,String notes,Instant createdAt) {}
    public record Detail(Case defect,List<Event> events) {}
}
