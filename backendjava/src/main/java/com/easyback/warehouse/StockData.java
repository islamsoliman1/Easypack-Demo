package com.easyback.warehouse;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class StockData {
    private StockData() {}
    public record Receipt(@NotNull UUID partId, @NotEmpty @Size(max=200) List<@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]{0,63}") String> serials,
        @NotNull @Size(max=120) String location, @NotNull @Size(max=120) String reference, @NotNull @Size(max=1000) String notes) {}
    public record Selection(@NotNull @PositiveOrZero Long version, @NotEmpty @Size(max=200) List<@NotNull UUID> pieceIds,
        @NotNull @Size(max=1000) String notes, UUID recipientId) {}
    public enum Condition { GOOD, DEFECTIVE }
    public record ReturnInput(@NotNull @PositiveOrZero Long version, @NotNull Condition condition, @NotBlank @Size(max=1000) String notes) {}
    public record RestoreInput(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max=1000) String notes) {}
    public record PreparationInput(@NotNull @PositiveOrZero Long version, @NotNull UUID requirementId, @NotNull @Size(max=1000) String notes) {}
    public record Piece(UUID id, String serialNumber, UUID partId, String partCode, String partName, String location,
        String status, long version, UUID requirementId, UUID orderId, String orderCode, String positionCode, String recipientName,UUID taskId,UUID defectId) {}
    public record Balance(UUID partId, String partCode, String partName, long available, long reserved, long issued, long quarantined,long installed,
        long rejected,long inRepair,long repaired,long scrapped) {}
    public record Coverage(UUID requirementId, String positionCode, String partCode, String partName, int required, long reserved, long issued,
        long missing, UUID preparationId, String preparationStatus,long installed) {}
    public record OrderStock(UUID orderId, long version, String status, List<Coverage> requirements, List<Piece> pieces) {}
    public record Movement(UUID id, String serialNumber, String action, String orderCode, String positionCode, String recipientName,
        String actorName, String reference, String notes, Instant createdAt) {}
    public record Preparation(UUID id, UUID orderId, String orderCode, String orderTitle, String positionCode, String partCode, String partName,
        int requestedQuantity, long missing, String status, String notes, long version, Instant requestedAt) {}
}
