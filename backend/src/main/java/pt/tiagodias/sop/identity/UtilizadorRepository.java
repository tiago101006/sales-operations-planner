package pt.tiagodias.sop.identity;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UtilizadorRepository extends JpaRepository<Utilizador, Long> {
    Optional<Utilizador> findByEmail(String email);
    boolean existsByEmail(String email);
}
