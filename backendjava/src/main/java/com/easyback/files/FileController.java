package com.easyback.files;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {
    private final FileService service;
    public FileController(FileService service) { this.service=service; }
    public record Version(@NotNull @PositiveOrZero Long version) {}
    private UUID actor(Authentication a) { return UUID.fromString(a.getName()); }
    @GetMapping public List<FileService.Metadata> list(@RequestParam FileService.Kind kind,@RequestParam UUID targetId,Authentication a) { return service.list(kind,targetId,actor(a)); }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public FileService.Metadata upload(@RequestParam FileService.Kind kind,@RequestParam UUID targetId,@RequestPart MultipartFile file,Authentication a) throws IOException { return service.upload(kind,targetId,file,actor(a)); }
    @GetMapping("/{id}") public ResponseEntity<byte[]> download(@PathVariable UUID id,Authentication a) {
        var c=service.download(id,actor(a));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(c.file().contentType())).cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options","nosniff").header("Content-Disposition",ContentDisposition.attachment().filename(c.file().filename(),StandardCharsets.UTF_8).build().toString()).body(c.bytes());
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id,@Valid @RequestBody Version p,Authentication a) { service.delete(id,p.version(),actor(a)); }
}
