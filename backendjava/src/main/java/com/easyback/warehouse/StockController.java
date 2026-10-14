package com.easyback.warehouse;

import static com.easyback.warehouse.StockData.*;
import com.easyback.catalog.CatalogData.VersionInput;
import com.easyback.assembly.AssemblyService;
import com.easyback.assembly.AssemblyData.Person;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class StockController {
    private final StockService service;
    private final AssemblyService assembly;
    public StockController(StockService service,AssemblyService assembly) { this.service=service; this.assembly=assembly; }
    private static UUID actor(Authentication a) { return UUID.fromString(a.getName()); }
    private static boolean all(Authentication a) { return a.getAuthorities().stream().anyMatch(p -> p.getAuthority().equals("orders.view")); }
    private static final String STOCK="hasAuthority('stock.view')";
    private static final String ORDER_STOCK=STOCK+" and hasAuthority('orders.view')";
    @GetMapping("/warehouse/balances") @PreAuthorize(STOCK)
    public List<Balance> balances() { return service.balances(); }
    @GetMapping("/warehouse/pieces") @PreAuthorize(STOCK)
    public List<Piece> pieces() { return service.pieces(); }
    @GetMapping("/warehouse/movements") @PreAuthorize(STOCK)
    public List<Movement> movements() { return service.movements(); }
    @PostMapping("/warehouse/receipts") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(STOCK+" and hasAuthority('stock.receive') and hasAuthority('catalog.view')")
    public List<Piece> receive(@Valid @RequestBody Receipt p,Authentication a) { return service.receive(p,actor(a)); }
    @PostMapping("/warehouse/pieces/{id}/restore") @PreAuthorize(STOCK+" and hasAuthority('stock.receive')")
    public Piece restore(@PathVariable UUID id,@Valid @RequestBody RestoreInput p,Authentication a) { return service.restore(id,p,actor(a)); }
    @GetMapping("/manufacturing-orders/{id}/stock") @PreAuthorize("hasAnyAuthority('orders.view','orders.view-assigned')")
    public OrderStock orderStock(@PathVariable UUID id,Authentication a) { return service.orderStock(id,actor(a),all(a)); }
    @GetMapping("/manufacturing-orders/{id}/stock/recipients") @PreAuthorize(ORDER_STOCK+" and hasAuthority('stock.issue')")
    public List<Person> recipients(@PathVariable UUID id) { return assembly.recipients(id); }
    @PostMapping("/manufacturing-orders/{id}/stock/reserve") @PreAuthorize(ORDER_STOCK+" and hasAuthority('stock.reserve')")
    public OrderStock reserve(@PathVariable UUID id,@Valid @RequestBody VersionInput p,Authentication a) { return service.reserve(id,p.version(),actor(a)); }
    @PostMapping("/manufacturing-orders/{id}/stock/issue") @PreAuthorize(ORDER_STOCK+" and hasAuthority('stock.issue')")
    public OrderStock issue(@PathVariable UUID id,@Valid @RequestBody Selection p,Authentication a) { return service.issue(id,p,actor(a)); }
    @PostMapping("/manufacturing-orders/{id}/stock/pieces/{piece}/return") @PreAuthorize(ORDER_STOCK+" and hasAuthority('stock.return')")
    public OrderStock returnPiece(@PathVariable UUID id,@PathVariable UUID piece,@Valid @RequestBody ReturnInput p,Authentication a) { return service.returnPiece(id,piece,p,actor(a)); }
    @PostMapping("/manufacturing-orders/{id}/stock/preparation") @PreAuthorize(ORDER_STOCK+" and hasAuthority('stock.reserve')")
    public OrderStock requestPreparation(@PathVariable UUID id,@Valid @RequestBody PreparationInput p,Authentication a) { return service.requestPreparation(id,p,actor(a)); }
    @GetMapping("/warehouse/preparations") @PreAuthorize("hasAuthority('orders.view') and (hasAuthority('stock.view') or hasAuthority('preparation.manage'))")
    public List<Preparation> preparations() { return service.preparations(); }
    @PostMapping("/warehouse/preparations/{id}/start") @PreAuthorize("hasAuthority('orders.view') and hasAuthority('preparation.manage')")
    public List<Preparation> start(@PathVariable UUID id,@Valid @RequestBody VersionInput p,Authentication a) { return service.startPreparation(id,p.version(),actor(a)); }
}
