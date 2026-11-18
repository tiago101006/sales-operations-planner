package pt.tiagodias.sop.catalog;

import java.net.URI;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import pt.tiagodias.sop.security.PhotoUpload;

@RestController
@RequestMapping("/api/v1/produtos")
public class ProdutoController {
    private final ProdutoService service;
    public ProdutoController(ProdutoService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ProdutoDto> create(@Valid @RequestBody CreateProduto request) {
        return created(service.create(request));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProdutoDto> createWithPhoto(@Valid @RequestPart("produto") CreateProduto request,
                                                       @RequestPart(value = "foto", required = false) MultipartFile file) {
        return created(service.create(request, file == null ? null : PhotoUpload.normalize(file)));
    }

    private static ResponseEntity<ProdutoDto> created(ProdutoDto produto) {
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

    @PatchMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProdutoDto updateWithPhoto(@PathVariable Long id, @Valid @RequestPart("produto") UpdateProduto request,
                                      @RequestPart(value = "foto", required = false) MultipartFile file) {
        return service.update(id, request, file == null ? null : PhotoUpload.normalize(file));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @RequestParam long versaoEsperada) {
        service.delete(id, versaoEsperada);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/{id}/foto", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> photo(@PathVariable Long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG)
                .body(service.photo(id));
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
            @NotNull @PositiveOrZero Long versaoEsperada,
            Boolean removerFoto) {
        @JsonCreator
        public UpdateProduto {}

        public UpdateProduto(String codigo, String nome, String unidade, Boolean ativo, Long versaoEsperada) {
            this(codigo, nome, unidade, ativo, versaoEsperada, false);
        }
    }

    public record ProdutoDto(String id, String codigo, String nome, String unidade, boolean ativo, long versao,
                             boolean temFoto) {}
    public record PaginaProdutos(List<ProdutoDto> items, int page, int size, long totalElements, int totalPages) {}
}
