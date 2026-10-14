package com.easyback.orders;

import static com.easyback.orders.OrderData.*;
import com.easyback.catalog.CatalogData.VersionInput;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/manufacturing-orders")
public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service) { this.service=service; }
    private static UUID actor(Authentication a) { return UUID.fromString(a.getName()); }
    private static boolean all(Authentication a) { return a.getAuthorities().stream().anyMatch(p -> p.getAuthority().equals("orders.view")); }
    private static final String READ="hasAnyAuthority('orders.view','orders.view-assigned')";
    private static final String WRITE="hasAuthority('orders.view') and hasAuthority('orders.manage') and hasAuthority('catalog.view') and hasAuthority('machines.view') and hasAuthority('boms.view')";
    @GetMapping @PreAuthorize(READ)
    public List<Order> orders(Authentication a) { return service.orders(actor(a),all(a)); }
    @GetMapping("/responsible-people") @PreAuthorize(WRITE)
    public List<Person> people() { return service.responsiblePeople(); }
    @GetMapping("/{id}") @PreAuthorize(READ)
    public Order order(@PathVariable UUID id, Authentication a) { return service.order(id,actor(a),all(a)); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(WRITE)
    public Order create(@Valid @RequestBody Input p, Authentication a) { return service.save(null,p,actor(a)); }
    @PutMapping("/{id}") @PreAuthorize(WRITE)
    public Order update(@PathVariable UUID id, @Valid @RequestBody Input p, Authentication a) { return service.save(id,p,actor(a)); }
    @PostMapping("/{id}/release") @PreAuthorize("hasAuthority('orders.view') and hasAuthority('orders.release')")
    public Order release(@PathVariable UUID id, @Valid @RequestBody VersionInput p, Authentication a) { return service.release(id,p.version(),actor(a)); }
    @PostMapping("/{id}/cancel") @PreAuthorize("hasAuthority('orders.view') and hasAuthority('orders.cancel')")
    public Order cancel(@PathVariable UUID id, @Valid @RequestBody CancelInput p, Authentication a) { return service.cancel(id,p,actor(a)); }
}
