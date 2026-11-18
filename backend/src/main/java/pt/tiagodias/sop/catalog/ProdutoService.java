package pt.tiagodias.sop.catalog;

import java.util.ArrayList;
import java.util.Locale;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pt.tiagodias.sop.catalog.ProdutoController.*;

@Service
@Transactional(readOnly = true)
public class ProdutoService {
    private final ProdutoRepository repository;
    private final JdbcTemplate jdbc;
    public ProdutoService(ProdutoRepository repository, JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    @Transactional
    public ProdutoDto create(CreateProduto request) {
        return create(request, null);
    }

    @Transactional
    public ProdutoDto create(CreateProduto request, byte[] image) {
        if (repository.existsByCodigo(request.codigo())) { throw conflict("Código de produto já existente."); }
        var produto = new Produto(request.codigo(), request.nome().strip(), request.unidade().strip());
        if (image != null) produto.marcarNovaFoto();
        repository.saveAndFlush(produto);
        if (image != null) savePhoto(produto.getId(), image);
        return dto(produto);
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
        return update(id, request, null);
    }

    @Transactional
    public ProdutoDto update(Long id, UpdateProduto request, byte[] image) {
        boolean removePhoto = Boolean.TRUE.equals(request.removerFoto());
        if (removePhoto && image != null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Escolhe entre remover ou substituir a foto.");
        }
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
        if (image != null) {
            savePhoto(id, image);
            produto.marcarNovaFoto();
        } else if (removePhoto && produto.temFoto()) {
            jdbc.update("DELETE FROM foto_produto WHERE produto_id=?", id);
            produto.limparFoto();
        }
        return dto(repository.saveAndFlush(produto));
    }

    @Transactional
    public void delete(Long id, long versaoEsperada) {
        if (versaoEsperada < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "A versão esperada deve ser igual ou superior a zero.");
        }
        var produto = repository.findForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Produto não encontrado."));
        if (!produto.getVersao().equals(versaoEsperada)) {
            throw conflict("Versão desatualizada. Consulta novamente o produto antes de eliminar.");
        }
        if (repository.usage(id) != 0) {
            throw conflict("O produto tem histórico associado. Arquiva-o para preservar os dados e os planos.");
        }
        jdbc.update("DELETE FROM foto_produto WHERE produto_id=?", id);
        repository.delete(produto);
        repository.flush();
    }

    public byte[] photo(Long id) {
        return jdbc.query("SELECT imagem FROM foto_produto WHERE produto_id=?", (rs, row) -> rs.getBytes(1), id)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "O produto não tem foto."));
    }

    private void savePhoto(Long id, byte[] image) {
        jdbc.update("""
                INSERT INTO foto_produto (produto_id, imagem) VALUES (?, ?) AS nova
                ON DUPLICATE KEY UPDATE imagem=nova.imagem
                """, id, image);
    }

    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ProdutoDto dto(Produto produto) {
        return new ProdutoDto(produto.getId().toString(), produto.getCodigo(), produto.getNome(),
                produto.getUnidade(), produto.isAtivo(), produto.getVersao(), produto.temFoto());
    }
}
