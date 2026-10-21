package com.easyback.notifications;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="easyback.notifications.scheduler-enabled",havingValue="true",matchIfMissing=true)
public class DeliveryReminderScheduler {
    private final DeliveryReminderEvaluator evaluator;
    public DeliveryReminderScheduler(DeliveryReminderEvaluator evaluator) { this.evaluator=evaluator; }
    @Scheduled(fixedDelayString="${easyback.notifications.scan-ms:900000}",initialDelayString="${easyback.notifications.scan-ms:900000}")
    public void scan() {
        for(var id:evaluator.candidates()) try { evaluator.evaluate(id); }
        catch(RuntimeException e) { LoggerFactory.getLogger(getClass()).error("Delivery reminder evaluation failed for order {}",id,e); }
    }
}
