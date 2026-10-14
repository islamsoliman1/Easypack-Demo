package com.easyback.warehouse;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class RawData {
    private RawData() {}
    public record Receipt(@NotNull UUID partId,
        @NotEmpty @Size(max=200) List<@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]{0,63}") String> codes,
        @NotNull @DecimalMin("0.001") @Digits(integer=6,fraction=3) BigDecimal lengthMm,
        @DecimalMin("0.001") @Digits(integer=6,fraction=3) BigDecimal widthMm,
        @NotNull @Size(max=120) String location,@NotNull @Size(max=120) String reference,@NotNull @Size(max=1000) String notes) {}
    public record Issue(@NotNull @PositiveOrZero Long version,@NotNull UUID preparationId,
        @NotNull @PositiveOrZero Long preparationVersion,@NotNull @Size(max=1000) String notes) {}
    public record ReturnInput(@NotNull @PositiveOrZero Long version,@NotBlank @Size(max=1000) String notes) {}
    public record CutInput(@NotNull @PositiveOrZero Long version,@NotNull @PositiveOrZero Long preparationVersion,
        @NotEmpty @Size(max=200) List<@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._/-]{0,63}") String> serials,
        @NotNull @Min(1) @Max(200) Integer columns,@NotNull Boolean rotated,
        @NotNull Boolean returnFirst,@NotNull Boolean returnSecond,@NotNull @Size(max=1000) String notes) {}
    public record Unit(UUID id,String code,UUID partId,String partCode,String partName,String stockUnit,String shape,
        BigDecimal lengthMm,BigDecimal widthMm,BigDecimal diameterMm,String location,String status,long version,
        UUID preparationId,UUID parentId,String parentCode,String orderCode,String positionCode) {}
    public record Balance(UUID partId,String partCode,String partName,String stockUnit,BigDecimal available,BigDecimal issued,BigDecimal scrap) {}
    public record Remainder(int index,BigDecimal lengthMm,BigDecimal widthMm,BigDecimal amount,boolean returned) {}
    public record Plan(String stockUnit,BigDecimal sourceAmount,BigDecimal outputLengthMm,BigDecimal outputWidthMm,
        int quantity,int rows,int columns,BigDecimal outputAmount,BigDecimal returnedAmount,BigDecimal scrapAmount,List<Remainder> remainders) {}
    public record Movement(UUID id,String code,String partCode,String stockUnit,String action,BigDecimal amount,
        String orderCode,String positionCode,String actorName,String reference,String notes,Instant createdAt) {}
    public record Cut(UUID id,String sourceCode,String stockUnit,String orderCode,String positionCode,int quantity,
        BigDecimal outputAmount,BigDecimal returnedAmount,BigDecimal scrapAmount,boolean rotated,int columns,
        BigDecimal outputLengthMm,BigDecimal outputWidthMm,String actorName,String notes,Instant createdAt,List<String> serials) {}
}
