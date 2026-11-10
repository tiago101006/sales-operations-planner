package pt.tiagodias.sop.catalog;

import java.net.URI;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/produtos")
public class ProdutoController {
    private final ProdutoService service;
    public ProdutoController(ProdutoService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ProdutoDto> create(@Valid @RequestBody CreateProduto request) {
        var produto = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/produtos/" + produto.id())).body(produto);
    }

    @GetMapping
    public PaginaProdutos list(@RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "50") int size,
                              @RequestParam(required = false) String q,
                              @RequestParam(required = false) Boolean ativo) {
        return service.list(page, size, q, ativo);
    }

    @GetMapping("/{id}")
    public ProdutoDto get(@PathVariable Long id) { return service.get(id); }

    @PatchMapping("/{id}")
    public ProdutoDto update(@PathVariable Long id, @Valid @RequestBody UpdateProduto request) {
        return service.update(id, request);
    }

    public record CreateProduto(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String codigo,
            @NotBlank @Size(max = 160) String nome,
            @NotBlank @Size(max = 20) String unidade) {}

    public record UpdateProduto(
            @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]+") String codigo,
            @Size(max = 160) @Pattern(regexp = "(?s).*\\S.*") String nome,
            @Size(max = 20) @Pattern(regexp = "(?s).*\\S.*") String unidade,
            Boolean ativo,
            @NotNull @PositiveOrZero Long versaoEsperada) {}

    public record ProdutoDto(String id, String codigo, String nome, String unidade, boolean ativo, long versao) {}
    public record PaginaProdutos(List<ProdutoDto> items, int page, int size, long totalElements, int totalPages) {}
}
