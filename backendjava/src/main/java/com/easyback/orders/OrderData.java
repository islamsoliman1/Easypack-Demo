package com.easyback.orders;

import com.easyback.catalog.CatalogData.Item;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class OrderData {
    private OrderData() {}
    public record Input(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]{0,63}") String code,
        @NotBlank @Size(max=120) String title, @NotNull UUID machineId, @NotNull UUID bomId,
        @NotNull UUID responsibleId, @NotNull Instant deliveryAt, @NotNull @Size(max=1000) String notes,
        @NotNull @PositiveOrZero Long version) {}
    public record CancelInput(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max=1000) String reason) {}
    public record Person(UUID id, String name, String email) {}
    public record Order(UUID id, String code, String title, UUID machineId, UUID bomId, UUID responsibleId,
        String responsibleName, Instant deliveryAt, String notes, String status, String machineSerial,
        String machineName, String modelName, int bomRevision, long version, Instant createdAt,
        Instant openedAt, Instant cancelledAt, String cancellationReason, List<Item> items,
        long requiredCount,long installedCount,Instant assemblyCompletedAt,long configurationVersion,
        Instant testedAt,Instant completedAt,Instant deliveredAt,String deliveryNotes,UUID passedTrialId,boolean reworkRequired) {}
}
