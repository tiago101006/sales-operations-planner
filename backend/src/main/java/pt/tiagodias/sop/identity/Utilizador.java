package pt.tiagodias.sop.identity;

import java.util.LinkedHashSet;
import java.util.Set;
import jakarta.persistence.*;

@Entity
@Table(name = "utilizador")
public class Utilizador {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 120)
    private String nome;
    @Column(nullable = false, length = 254, unique = true)
    private String email;
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;
    @Column(nullable = false)
    private boolean ativo = true;
    @Column(name = "password_definida", nullable = false)
    private boolean passwordDefinida = true;
    @Version
    @Column(name = "versao_acesso", nullable = false)
    private long versao;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "utilizador_papel", joinColumns = @JoinColumn(name = "utilizador_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "papel", nullable = false)
    private Set<Papel> papeis = new LinkedHashSet<>();

    protected Utilizador() {}

    public Utilizador(String nome, String email, String passwordHash, Set<Papel> papeis) {
        this.nome = nome;
        this.email = email;
        this.passwordHash = passwordHash;
        this.papeis.addAll(papeis);
    }

    public Long getId() { return id; }
    public String getNome() { return nome; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isPasswordDefinida() { return passwordDefinida; }
    void pendenteAtivacao() { passwordDefinida = false; }
    public long getVersao() { return versao; }
    public void setAtivo(boolean ativo) { this.ativo = ativo; }
    public boolean isAtivo() { return ativo; }
    public Set<Papel> getPapeis() { return Set.copyOf(papeis); }
}
