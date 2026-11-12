-- Keep identities and their business history; revoke sessions on lifecycle changes.
ALTER TABLE utilizador ADD COLUMN versao_acesso BIGINT NOT NULL DEFAULT 0;

CREATE TABLE evento_estado_utilizador (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    utilizador_id BIGINT NOT NULL,
    alterado_por BIGINT NOT NULL,
    ativo_anterior BOOLEAN NOT NULL,
    ativo_novo BOOLEAN NOT NULL,
    alterado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_estado_utilizador FOREIGN KEY (utilizador_id) REFERENCES utilizador(id),
    CONSTRAINT fk_estado_autor FOREIGN KEY (alterado_por) REFERENCES utilizador(id),
    CONSTRAINT ck_estado_alterado CHECK (ativo_anterior <> ativo_novo),
    INDEX ix_estado_utilizador (utilizador_id, alterado_em)
) ENGINE=InnoDB;
