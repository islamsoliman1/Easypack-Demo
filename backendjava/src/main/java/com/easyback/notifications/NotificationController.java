package com.easyback.notifications;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service=service; }
    private UUID user(Authentication a) { return UUID.fromString(a.getName()); }
    @GetMapping
    public NotificationService.Page list(@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit,
        @RequestParam(defaultValue="false") boolean unreadOnly,@RequestParam(required=false) String severity,Authentication a) {
        return service.list(user(a),cursor,limit,unreadOnly,severity);
    }
    @GetMapping("/unread-count") public Map<String,Long> count(Authentication a) { return Map.of("count",service.unread(user(a))); }
    @PostMapping("/{id}/read") @ResponseStatus(HttpStatus.NO_CONTENT) public void read(@PathVariable UUID id,Authentication a) { service.read(user(a),id); }
    @PostMapping("/read-all") @ResponseStatus(HttpStatus.NO_CONTENT) public void readAll(Authentication a) { service.readAll(user(a)); }
}
