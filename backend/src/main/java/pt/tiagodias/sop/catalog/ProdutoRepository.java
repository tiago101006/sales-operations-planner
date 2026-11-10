package pt.tiagodias.sop.catalog;

import java.util.Optional;
import java.util.Collection;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ProdutoRepository extends JpaRepository<Produto, Long>, JpaSpecificationExecutor<Produto> {
    boolean existsByCodigo(String codigo);
    boolean existsByCodigoAndIdNot(String codigo, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Produto p where p.id = :id")
    Optional<Produto> findForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Produto p where p.codigo in :codigos order by p.id")
    List<Produto> findForImport(@Param("codigos") Collection<String> codigos);

    // As restantes linhas de previsao/cenario/plano dependem destes registos por FK.
    @Query(value = """
            SELECT (EXISTS(SELECT 1 FROM observacao WHERE produto_id=:id)
                 OR EXISTS(SELECT 1 FROM promocao WHERE produto_id=:id)
                 OR EXISTS(SELECT 1 FROM execucao_produto WHERE produto_id=:id)
                 OR EXISTS(SELECT 1 FROM resultado_operacional WHERE produto_id=:id))
            """, nativeQuery = true)
    int usage(@Param("id") Long id);
}
