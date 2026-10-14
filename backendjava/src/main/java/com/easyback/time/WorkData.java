package com.easyback.time;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

public final class WorkData {
    private WorkData() {}
    public record Break(@NotNull LocalTime start,@NotNull LocalTime end) {}
    public record Calendar(long version,List<Integer> weekdays,List<Break> breaks,boolean breaksConfirmed) {}
    public record CalendarInput(@NotNull @PositiveOrZero Long version,@NotEmpty List<@NotNull @Min(1) @Max(7) Integer> weekdays,
        @NotNull @Size(max=8) List<@NotNull @Valid Break> breaks,@AssertTrue boolean breaksConfirmed) {}
    public record Day(LocalDate day,boolean working,boolean extended,List<Break> breaks,boolean breaksConfirmed,long version,String reason) {}
    public record DayInput(@NotNull @PositiveOrZero Long version,boolean working,boolean extended,@NotBlank @Size(max=1000) String reason) {}
    public record Session(UUID id,UUID workerId,String workerName,Instant startedAt,Instant endedAt,String reason,LocalDate day,boolean running,long grossSeconds,Long netSeconds) {}
    public record Total(long grossSeconds,Long netSeconds,int unconfirmedSessions) {}
    public record Detail(Total total,List<Session> sessions) {}
}
