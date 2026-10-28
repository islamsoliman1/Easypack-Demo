package com.easyback.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class MachineChatController {
    private final MachineChatService service;
    public MachineChatController(MachineChatService service) { this.service=service; }
    public record Send(@NotBlank @Size(max=2000) String body) {}
    public record Read(@Positive long through) {}
    private UUID actor(Authentication a) { return UUID.fromString(a.getName()); }
    @GetMapping("/machine-chat/unread")
    public List<MachineChatService.Unread> unread(Authentication a) { return service.unread(actor(a)); }
    @GetMapping("/machines/{machine}/chat")
    public MachineChatService.Page messages(@PathVariable UUID machine,@RequestParam(required=false) Long before,Authentication a) { return service.messages(machine,before,actor(a)); }
    @PostMapping("/machines/{machine}/chat") @ResponseStatus(HttpStatus.CREATED)
    public MachineChatService.Message send(@PathVariable UUID machine,@Valid @RequestBody Send body,Authentication a) { return service.send(machine,body.body(),actor(a)); }
    @PutMapping("/machines/{machine}/chat/read") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID machine,@Valid @RequestBody Read body,Authentication a) { service.read(machine,body.through(),actor(a)); }
}
