package com.easyback.time;

import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration("workClockConfiguration")
public class WorkClock {
    @Bean public Clock workClock() { return Clock.systemUTC(); }
}
