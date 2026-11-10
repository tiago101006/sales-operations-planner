package pt.tiagodias.sop.catalog;

import java.util.ArrayList;
import java.util.Locale;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pt.tiagodias.sop.catalog.ProdutoController.*;

@Service
@Transactional(readOnly = true)
public class ProdutoService {
    private final ProdutoRepository repository;
    public ProdutoService(ProdutoRepository repository) { this.repository = repository; }

    @Transactional
    public ProdutoDto create(CreateProduto request) {
        if (repository.existsByCodigo(request.codigo())) { throw conflict("Código de produto já existente."); }
        return dto(repository.saveAndFlush(new Produto(request.codigo(), request.nome().strip(), request.unidade().strip())));
    }

    public ProdutoDto get(Long id) {
        return repository.findById(id).map(ProdutoService::dto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto não encontrado."));
    }

    public PaginaProdutos list(int page, int size, String q, Boolean ativo) {
        if (page < 0 || size < 1 || size > 200 || (q != null && q.length() > 160)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Usa page >= 0, size entre 1 e 200 e pesquisa até 160 caracteres.");
        }
        String search = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        String escaped = search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        Specification<Produto> spec = (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            if (ativo != null) { predicates.add(cb.equal(root.get("ativo"), ativo)); }
            if (!search.isEmpty()) {
                predicates.add(cb.or(cb.like(cb.lower(root.get("codigo")), "%" + escaped + "%", '\\'),
                        cb.like(cb.lower(root.get("nome")), "%" + escaped + "%", '\\')));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        var result = repository.findAll(spec, PageRequest.of(page, size, Sort.by("codigo").and(Sort.by("id"))));
        return new PaginaProdutos(result.getContent().stream().map(ProdutoService::dto).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public ProdutoDto update(Long id, UpdateProduto request) {
        var produto = repository.findForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto não encontrado."));
        if (!produto.getVersao().equals(request.versaoEsperada())) { throw conflict("Versão desatualizada. Consulta novamente o produto antes de alterar."); }
        if ((request.nome() != null && request.nome().isBlank()) || (request.unidade() != null && request.unidade().isBlank())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Nome e unidade não podem ficar vazios.");
        }
        boolean identityChanged = (request.codigo() != null && !request.codigo().equals(produto.getCodigo()))
                || (request.unidade() != null && !request.unidade().strip().equals(produto.getUnidade()));
        if (identityChanged && repository.usage(id) != 0) { throw conflict("Código e unidade ficam fixos depois de o produto ter dados associados."); }
        if (request.codigo() != null && repository.existsByCodigoAndIdNot(request.codigo(), id)) { throw conflict("Código de produto já existente."); }
        produto.update(request.codigo(), request.nome(), request.unidade(), request.ativo());
        return dto(repository.saveAndFlush(produto));
    }

    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ProdutoDto dto(Produto produto) {
        return new ProdutoDto(produto.getId().toString(), produto.getCodigo(), produto.getNome(),
                produto.getUnidade(), produto.isAtivo(), produto.getVersao());
    }
}
