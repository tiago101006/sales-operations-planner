package pt.tiagodias.sop.catalog;

import jakarta.persistence.*;

@Entity
@Table(name = "produto")
public class Produto {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 64)
    private String codigo;
    @Column(nullable = false, length = 160)
    private String nome;
    @Column(nullable = false, length = 20)
    private String unidade;
    @Column(nullable = false)
    private boolean ativo = true;
    @Version @Column(name = "versao_lock", nullable = false)
    private Long versao;

    protected Produto() {}

    public Produto(String codigo, String nome, String unidade) {
        this.codigo = codigo;
        this.nome = nome;
        this.unidade = unidade;
    }

    public Long getId() { return id; }
    public String getCodigo() { return codigo; }
    public String getNome() { return nome; }
    public String getUnidade() { return unidade; }
    public boolean isAtivo() { return ativo; }
    public Long getVersao() { return versao; }

    void update(String codigo, String nome, String unidade, Boolean ativo) {
        if (codigo != null) { this.codigo = codigo; }
        if (nome != null) { this.nome = nome.strip(); }
        if (unidade != null) { this.unidade = unidade.strip(); }
        if (ativo != null) { this.ativo = ativo; }
    }
}
