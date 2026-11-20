CREATE TABLE analise_inteligente (
    avaliacao_id BIGINT NOT NULL PRIMARY KEY,
    estado VARCHAR(16) NOT NULL,
    modelo VARCHAR(100) NOT NULL,
    versao_prompt VARCHAR(64) NOT NULL,
    hash_dados CHAR(64) NOT NULL,
    dados_json LONGTEXT NOT NULL,
    analise_json LONGTEXT,
    mensagem VARCHAR(500),
    solicitado_em DATETIME(6) NOT NULL,
    gerado_em DATETIME(6),
    criado_por BIGINT NOT NULL,
    execucao_token CHAR(36) NOT NULL,
    CONSTRAINT fk_analise_avaliacao FOREIGN KEY (avaliacao_id) REFERENCES avaliacao(id),
    CONSTRAINT fk_analise_autor FOREIGN KEY (criado_por) REFERENCES utilizador(id),
    CONSTRAINT ck_analise_estado CHECK (estado IN ('EM_CURSO','CONCLUIDA','FALHOU')),
    CONSTRAINT ck_analise_dados CHECK (JSON_VALID(dados_json) AND OCTET_LENGTH(dados_json) <= 8388608),
    CONSTRAINT ck_analise_texto CHECK (analise_json IS NULL OR (JSON_VALID(analise_json) AND OCTET_LENGTH(analise_json) <= 131072))
) ENGINE=InnoDB;
