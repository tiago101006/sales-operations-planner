-- Existing accounts keep their credentials. Only new invitations are pending.
ALTER TABLE utilizador ADD COLUMN password_definida BOOLEAN NOT NULL DEFAULT TRUE;
CREATE TABLE convite_utilizador (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    utilizador_id BIGINT NOT NULL,
    criado_por BIGINT NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expira_em DATETIME(6) NOT NULL,
    usado_em DATETIME(6) NULL,
    revogado_em DATETIME(6) NULL,
    CONSTRAINT fk_convite_utilizador FOREIGN KEY (utilizador_id) REFERENCES utilizador(id),
    CONSTRAINT fk_convite_autor FOREIGN KEY (criado_por) REFERENCES utilizador(id),
    INDEX ix_convite_utilizador (utilizador_id)
) ENGINE=InnoDB;
