package com.easyback.catalog;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class CatalogData {
    private CatalogData() {}
    public enum Kind { SERIALIZED_PIECE, MEASURED_STOCK }
    public enum Shape { STANDARD, RECTANGULAR, ROUND }
    public enum StockUnit { PIECE, LENGTH, AREA }
    public record ModelInput(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]{0,63}") String code,
        @NotBlank @Size(max=120) String name, @NotNull @Size(max=1000) String description,
        @NotNull Boolean active, @NotNull @PositiveOrZero Long version) {}
    public record Model(UUID id, String code, String name, String description, boolean active, long version) {}
    public record PartInput(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]{0,63}") String code,
        @NotBlank @Size(max=120) String name, @NotNull @Size(max=1000) String description,
        @NotNull Kind kind, @NotNull Shape shape, @NotNull StockUnit stockUnit,
        @DecimalMin("0.001") @Digits(integer=6, fraction=3) BigDecimal lengthMm,
        @DecimalMin("0.001") @Digits(integer=6, fraction=3) BigDecimal widthMm,
        @DecimalMin("0.001") @Digits(integer=6, fraction=3) BigDecimal diameterMm,
        @NotNull @DecimalMin("0") @Digits(integer=7, fraction=2) BigDecimal defaultInstallMinutes,
        @NotNull Boolean active, @NotNull @PositiveOrZero Long version) {}
    public record Part(UUID id, String code, String name, String description, Kind kind, Shape shape, StockUnit stockUnit,
        BigDecimal lengthMm, BigDecimal widthMm, BigDecimal diameterMm, BigDecimal defaultInstallMinutes, boolean active, long version) {}
    public record MachineInput(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]{0,63}") String serialNumber,
        @NotNull UUID modelId, @NotBlank @Size(max=120) String name,
        @NotNull @Size(max=120) String location, @NotNull @Size(max=1000) String notes,
        @NotNull Boolean active, @NotNull @PositiveOrZero Long version) {}
    public record Machine(UUID id, String serialNumber, UUID modelId, String modelCode, String modelName,
        String name, String location, String notes, boolean active, long version) {}
    public record ItemInput(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]{0,39}") String positionCode,
        @NotNull UUID partId, @NotNull @Min(1) @Max(10000) Integer quantity,
        @NotNull @DecimalMin("0.01") @Digits(integer=7, fraction=2) BigDecimal expectedInstallMinutes,
        @NotNull @Size(max=1000) String notes, @NotNull @PositiveOrZero Long version) {}
    public record Item(UUID id, String positionCode, UUID partId, String partCode, String partName,
        Shape shape, BigDecimal lengthMm, BigDecimal widthMm, BigDecimal diameterMm,
        int quantity, BigDecimal expectedInstallMinutes, String notes) {}
    public record Bom(UUID id, UUID modelId, UUID machineId, int revision, String status, long version, Instant publishedAt, List<Item> items) {}
    public record LinkedPartInput(@NotNull @Valid PartInput part, @NotNull UUID modelId,
        @NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]{0,39}") String positionCode,
        @NotNull @Min(1) @Max(10000) Integer quantity) {}
    public record VersionInput(@NotNull @PositiveOrZero Long version) {}
    public record DraftInput(UUID sourceBomId) {}
}
