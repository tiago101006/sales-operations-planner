-- Proposta inicial para MySQL 8.4 / InnoDB. Executar numa base vazia.
-- Meses: DATE no primeiro dia. Instantes: UTC. Quantidades: DECIMAL.
-- Sem DELETE CASCADE: preservar a cadeia de proveniencia.
-- FKs nao substituem autorizacao, estados e imutabilidade no servico.

SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE utilizador (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    nome VARCHAR(120) NOT NULL,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    ativo BOOLEAN NOT NULL DEFAULT TRUE,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

CREATE TABLE utilizador_papel (
    utilizador_id BIGINT NOT NULL,
    papel ENUM('PLANEADOR','COMERCIAL','APROVADOR','CONSUMIDOR','ADMIN') NOT NULL,
    PRIMARY KEY (utilizador_id, papel),
    CONSTRAINT fk_papel_utilizador FOREIGN KEY (utilizador_id) REFERENCES utilizador(id)
) ENGINE=InnoDB;

CREATE TABLE produto (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    codigo VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL UNIQUE,
    nome VARCHAR(160) NOT NULL,
    unidade VARCHAR(20) NOT NULL,
    ativo BOOLEAN NOT NULL DEFAULT TRUE,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_produto_unidade CHECK (CHAR_LENGTH(TRIM(unidade)) > 0)
) ENGINE=InnoDB;

CREATE TABLE lote (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tipo ENUM('HISTORICO','CALENDARIO','PROMOCOES','REALIZADOS','OPERACIONAL') NOT NULL,
    estado ENUM('RECEBIDO','VALIDANDO','VALIDADO','REJEITADO') NOT NULL DEFAULT 'RECEBIDO',
    origem VARCHAR(160) NOT NULL,
    nome_ficheiro VARCHAR(255) NOT NULL,
    localizacao_ficheiro VARCHAR(1024) NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    versao_esquema VARCHAR(20) NOT NULL,
    substitui_lote_id BIGINT NULL,
    importado_por BIGINT NOT NULL,
    importado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    disponivel_em DATETIME(6) NOT NULL,
    validado_em DATETIME(6) NULL,
    total_linhas INT NOT NULL DEFAULT 0,
    versao_lock BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_lote_linhas CHECK (total_linhas >= 0),
    CONSTRAINT fk_lote_anterior FOREIGN KEY (substitui_lote_id) REFERENCES lote(id),
    CONSTRAINT fk_lote_autor FOREIGN KEY (importado_por) REFERENCES utilizador(id)
) ENGINE=InnoDB;

CREATE TABLE erro_importacao (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    lote_id BIGINT NOT NULL,
    numero_linha INT NOT NULL,
    campo VARCHAR(80) NOT NULL,
    codigo VARCHAR(64) NOT NULL,
    mensagem VARCHAR(500) NOT NULL,
    CONSTRAINT ck_erro_linha CHECK (numero_linha >= 0),
    CONSTRAINT fk_erro_lote FOREIGN KEY (lote_id) REFERENCES lote(id)
) ENGINE=InnoDB;

CREATE TABLE observacao (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    lote_id BIGINT NOT NULL,
    produto_id BIGINT NOT NULL,
    periodo DATE NOT NULL,
    tipo ENUM('VENDAS','CONSUMO','PROCURA_REALIZADA') NOT NULL,
    quantidade DECIMAL(18,4) NOT NULL,
    disponivel_em DATETIME(6) NOT NULL,
    UNIQUE KEY uk_observacao_serie (lote_id, produto_id, periodo, tipo),
    UNIQUE KEY uk_observacao_proveniencia (id, lote_id, produto_id, periodo),
    KEY ix_observacao_produto_periodo (produto_id, periodo),
    CONSTRAINT ck_observacao_mes CHECK (DAYOFMONTH(periodo) = 1),
    CONSTRAINT ck_observacao_quantidade CHECK (quantidade >= 0),
    CONSTRAINT fk_observacao_lote FOREIGN KEY (lote_id) REFERENCES lote(id),
    CONSTRAINT fk_observacao_produto FOREIGN KEY (produto_id) REFERENCES produto(id)
) ENGINE=InnoDB;

CREATE TABLE calendario (
    lote_id BIGINT NOT NULL,
    periodo DATE NOT NULL,
    dias_uteis SMALLINT NOT NULL,
    feriados SMALLINT NOT NULL DEFAULT 0,
    disponivel_em DATETIME(6) NOT NULL,
    PRIMARY KEY (lote_id, periodo),
    CONSTRAINT ck_calendario_mes CHECK (DAYOFMONTH(periodo) = 1),
    CONSTRAINT ck_calendario_dias CHECK (dias_uteis BETWEEN 0 AND DAYOFMONTH(LAST_DAY(periodo)) AND feriados BETWEEN 0 AND DAYOFMONTH(LAST_DAY(periodo))),
    CONSTRAINT fk_calendario_lote FOREIGN KEY (lote_id) REFERENCES lote(id)
) ENGINE=InnoDB;

CREATE TABLE promocao (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    lote_id BIGINT NOT NULL,
    produto_id BIGINT NOT NULL,
    descricao VARCHAR(255) NOT NULL,
    inicio DATE NOT NULL,
    fim DATE NOT NULL,
    disponivel_em DATETIME(6) NOT NULL,
    CONSTRAINT ck_promocao_datas CHECK (fim >= inicio),
    CONSTRAINT fk_promocao_lote FOREIGN KEY (lote_id) REFERENCES lote(id),
    CONSTRAINT fk_promocao_produto FOREIGN KEY (produto_id) REFERENCES produto(id)
) ENGINE=InnoDB;

CREATE TABLE execucao_previsao (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    lote_historico_id BIGINT NOT NULL,
    lote_calendario_id BIGINT NULL,
    lote_promocoes_id BIGINT NULL,
    tipo_serie ENUM('VENDAS','CONSUMO') NOT NULL,
    corte_temporal DATETIME(6) NOT NULL,
    ultimo_periodo_treino DATE NOT NULL,
    horizonte_meses SMALLINT NOT NULL DEFAULT 3,
    metodo_pedido ENUM('MEDIA_MOVEL','ETS','INGENUO_SAZONAL') NOT NULL,
    parametros JSON NOT NULL,
    versao_software VARCHAR(80) NOT NULL,
    versao_motor VARCHAR(80) NOT NULL,
    estado ENUM('PENDENTE','EM_EXECUCAO','CONCLUIDA','FALHOU') NOT NULL DEFAULT 'PENDENTE',
    criado_por BIGINT NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    concluido_em DATETIME(6) NULL,
    CONSTRAINT ck_execucao_mes CHECK (DAYOFMONTH(ultimo_periodo_treino) = 1),
    CONSTRAINT ck_execucao_horizonte CHECK (horizonte_meses > 0),
    CONSTRAINT ck_execucao_corte CHECK (LAST_DAY(ultimo_periodo_treino) <= DATE(corte_temporal)),
    CONSTRAINT fk_execucao_historico FOREIGN KEY (lote_historico_id) REFERENCES lote(id),
    CONSTRAINT fk_execucao_calendario FOREIGN KEY (lote_calendario_id) REFERENCES lote(id),
    CONSTRAINT fk_execucao_promocoes FOREIGN KEY (lote_promocoes_id) REFERENCES lote(id),
    CONSTRAINT fk_execucao_autor FOREIGN KEY (criado_por) REFERENCES utilizador(id)
) ENGINE=InnoDB;

CREATE TABLE execucao_produto (
    execucao_id BIGINT NOT NULL,
    produto_id BIGINT NOT NULL,
    metodo_aplicado VARCHAR(64) NULL,
    parametros_aplicados JSON NOT NULL,
    estado ENUM('PREVISTO','RECURSO','INSUFICIENTE','ERRO') NOT NULL,
    diagnostico VARCHAR(1000) NULL,
    PRIMARY KEY (execucao_id, produto_id),
    CONSTRAINT fk_ep_execucao FOREIGN KEY (execucao_id) REFERENCES execucao_previsao(id),
    CONSTRAINT fk_ep_produto FOREIGN KEY (produto_id) REFERENCES produto(id)
) ENGINE=InnoDB;

CREATE TABLE linha_previsao (
    execucao_id BIGINT NOT NULL,
    produto_id BIGINT NOT NULL,
    periodo DATE NOT NULL,
    quantidade_base DECIMAL(18,4) NOT NULL,
    PRIMARY KEY (execucao_id, produto_id, periodo),
    CONSTRAINT ck_previsao_mes CHECK (DAYOFMONTH(periodo) = 1),
    CONSTRAINT ck_previsao_quantidade CHECK (quantidade_base >= 0),
    CONSTRAINT fk_previsao_serie FOREIGN KEY (execucao_id, produto_id) REFERENCES execucao_produto(execucao_id, produto_id)
) ENGINE=InnoDB;

CREATE TABLE cenario (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    execucao_id BIGINT NOT NULL,
    nome VARCHAR(120) NOT NULL,
    tipo ENUM('BASE','OTIMISTA','CONSERVADOR','PERSONALIZADO') NOT NULL,
    criado_por BIGINT NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_cenario_execucao (id, execucao_id),
    CONSTRAINT fk_cenario_execucao FOREIGN KEY (execucao_id) REFERENCES execucao_previsao(id),
    CONSTRAINT fk_cenario_autor FOREIGN KEY (criado_por) REFERENCES utilizador(id)
) ENGINE=InnoDB;

CREATE TABLE revisao_cenario (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    cenario_id BIGINT NOT NULL,
    execucao_id BIGINT NOT NULL,
    numero INT NOT NULL,
    revisao_origem_id BIGINT NULL,
    fator DECIMAL(12,6) NOT NULL DEFAULT 1.000000,
    pressupostos JSON NOT NULL,
    estado ENUM('RASCUNHO','EM_REVISAO','DEVOLVIDA','APROVADA') NOT NULL DEFAULT 'RASCUNHO',
    criado_por BIGINT NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    submetido_em DATETIME(6) NULL,
    decidido_em DATETIME(6) NULL,
    versao_lock BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_revisao_numero (cenario_id, numero),
    UNIQUE KEY uk_revisao_execucao (id, execucao_id),
    UNIQUE KEY uk_revisao_origem_contexto (id, cenario_id, execucao_id),
    CONSTRAINT ck_revisao_numero CHECK (numero > 0),
    CONSTRAINT ck_revisao_fator CHECK (fator >= 0),
    CONSTRAINT fk_revisao_cenario FOREIGN KEY (cenario_id, execucao_id) REFERENCES cenario(id, execucao_id),
    CONSTRAINT fk_revisao_origem FOREIGN KEY (revisao_origem_id, cenario_id, execucao_id) REFERENCES revisao_cenario(id, cenario_id, execucao_id),
    CONSTRAINT fk_revisao_autor FOREIGN KEY (criado_por) REFERENCES utilizador(id)
) ENGINE=InnoDB;

CREATE TABLE linha_cenario (
    revisao_id BIGINT NOT NULL,
    execucao_id BIGINT NOT NULL,
    produto_id BIGINT NOT NULL,
    periodo DATE NOT NULL,
    quantidade_calculada DECIMAL(18,4) NOT NULL,
    quantidade_final DECIMAL(18,4) NOT NULL,
    PRIMARY KEY (revisao_id, produto_id, periodo),
    CONSTRAINT ck_lc_quantidades CHECK (quantidade_calculada >= 0 AND quantidade_final >= 0),
    CONSTRAINT fk_lc_revisao FOREIGN KEY (revisao_id, execucao_id) REFERENCES revisao_cenario(id, execucao_id),
    CONSTRAINT fk_lc_base FOREIGN KEY (execucao_id, produto_id, periodo) REFERENCES linha_previsao(execucao_id, produto_id, periodo)
) ENGINE=InnoDB;

CREATE TABLE evento_revisao (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    revisao_id BIGINT NOT NULL,
    tipo ENUM('CRIACAO','AJUSTE','SUBMISSAO','APROVACAO','DEVOLUCAO') NOT NULL,
    autor_id BIGINT NOT NULL,
    ocorrido_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    motivo VARCHAR(1000) NULL,
    UNIQUE KEY uk_evento_revisao (id, revisao_id),
    KEY ix_evento_historico (revisao_id, ocorrido_em, id),
    CONSTRAINT ck_evento_motivo CHECK (tipo NOT IN ('DEVOLUCAO','AJUSTE') OR (motivo IS NOT NULL AND CHAR_LENGTH(TRIM(motivo)) > 0)),
    CONSTRAINT fk_evento_revisao FOREIGN KEY (revisao_id) REFERENCES revisao_cenario(id),
    CONSTRAINT fk_evento_autor FOREIGN KEY (autor_id) REFERENCES utilizador(id)
) ENGINE=InnoDB;

CREATE TABLE ajuste (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    revisao_id BIGINT NOT NULL,
    produto_id BIGINT NOT NULL,
    periodo DATE NOT NULL,
    sequencia INT NOT NULL,
    evento_id BIGINT NOT NULL UNIQUE,
    valor_anterior DECIMAL(18,4) NOT NULL,
    valor_novo DECIMAL(18,4) NOT NULL,
    justificacao VARCHAR(1000) NOT NULL,
    UNIQUE KEY uk_ajuste_sequencia (revisao_id, produto_id, periodo, sequencia),
    CONSTRAINT ck_ajuste_sequencia CHECK (sequencia > 0),
    CONSTRAINT ck_ajuste_valores CHECK (valor_anterior >= 0 AND valor_novo >= 0),
    CONSTRAINT ck_ajuste_justificacao CHECK (CHAR_LENGTH(TRIM(justificacao)) > 0),
    CONSTRAINT fk_ajuste_celula FOREIGN KEY (revisao_id, produto_id, periodo) REFERENCES linha_cenario(revisao_id, produto_id, periodo),
    CONSTRAINT fk_ajuste_evento FOREIGN KEY (evento_id, revisao_id) REFERENCES evento_revisao(id, revisao_id)
) ENGINE=InnoDB;

CREATE TABLE plano_publicado (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    revisao_id BIGINT NOT NULL UNIQUE,
    evento_aprovacao_id BIGINT NOT NULL UNIQUE,
    versao_contrato VARCHAR(20) NOT NULL DEFAULT 'v1',
    publicado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_plano_revisao (id, revisao_id),
    CONSTRAINT fk_plano_revisao FOREIGN KEY (revisao_id) REFERENCES revisao_cenario(id),
    CONSTRAINT fk_plano_aprovacao FOREIGN KEY (evento_aprovacao_id, revisao_id) REFERENCES evento_revisao(id, revisao_id)
) ENGINE=InnoDB;

CREATE TABLE linha_plano (
    plano_id BIGINT NOT NULL,
    revisao_id BIGINT NOT NULL,
    produto_id BIGINT NOT NULL,
    periodo DATE NOT NULL,
    codigo_produto VARCHAR(64) NOT NULL,
    unidade VARCHAR(20) NOT NULL,
    quantidade_base DECIMAL(18,4) NOT NULL,
    quantidade_aprovada DECIMAL(18,4) NOT NULL,
    PRIMARY KEY (plano_id, produto_id, periodo),
    CONSTRAINT ck_lp_quantidades CHECK (quantidade_base >= 0 AND quantidade_aprovada >= 0),
    CONSTRAINT fk_lp_plano FOREIGN KEY (plano_id, revisao_id) REFERENCES plano_publicado(id, revisao_id),
    CONSTRAINT fk_lp_celula FOREIGN KEY (revisao_id, produto_id, periodo) REFERENCES linha_cenario(revisao_id, produto_id, periodo)
) ENGINE=InnoDB;

CREATE TABLE resultado_operacional (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    lote_id BIGINT NOT NULL,
    produto_id BIGINT NOT NULL,
    periodo DATE NOT NULL,
    unidades_solicitadas DECIMAL(18,4) NULL,
    unidades_satisfeitas_no_prazo DECIMAL(18,4) NULL,
    dias_observados_com_procura SMALLINT NULL,
    dias_com_procura_sem_stock SMALLINT NULL,
    stock_fecho DECIMAL(18,4) NULL,
    limite_stock DECIMAL(18,4) NULL,
    unidades_perdidas DECIMAL(18,4) NULL,
    prazo_medio_dias DECIMAL(10,4) NULL,
    disponivel_em DATETIME(6) NOT NULL,
    UNIQUE KEY uk_operacional_serie (lote_id, produto_id, periodo),
    CONSTRAINT ck_operacional_mes CHECK (DAYOFMONTH(periodo) = 1),
    CONSTRAINT ck_operacional_servico CHECK (
        (unidades_solicitadas IS NULL AND unidades_satisfeitas_no_prazo IS NULL)
        OR (unidades_solicitadas IS NOT NULL AND unidades_satisfeitas_no_prazo IS NOT NULL
            AND unidades_solicitadas >= 0 AND unidades_satisfeitas_no_prazo BETWEEN 0 AND unidades_solicitadas)),
    CONSTRAINT ck_operacional_rutura CHECK (
        (dias_observados_com_procura IS NULL AND dias_com_procura_sem_stock IS NULL)
        OR (dias_observados_com_procura IS NOT NULL AND dias_com_procura_sem_stock IS NOT NULL
            AND dias_observados_com_procura BETWEEN 0 AND DAYOFMONTH(LAST_DAY(periodo))
            AND dias_com_procura_sem_stock BETWEEN 0 AND dias_observados_com_procura)),
    CONSTRAINT ck_operacional_stock CHECK ((stock_fecho IS NULL OR stock_fecho >= 0) AND (limite_stock IS NULL OR limite_stock >= 0)),
    CONSTRAINT ck_operacional_perdas CHECK (unidades_perdidas IS NULL OR unidades_perdidas >= 0),
    CONSTRAINT ck_operacional_prazo CHECK (prazo_medio_dias IS NULL OR prazo_medio_dias >= 0),
    CONSTRAINT fk_operacional_lote FOREIGN KEY (lote_id) REFERENCES lote(id),
    CONSTRAINT fk_operacional_produto FOREIGN KEY (produto_id) REFERENCES produto(id)
) ENGINE=InnoDB;

CREATE TABLE avaliacao (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    plano_id BIGINT NOT NULL,
    lote_realizados_id BIGINT NOT NULL,
    lote_operacional_id BIGINT NULL,
    periodo_inicio DATE NOT NULL,
    periodo_fim DATE NOT NULL,
    corte_realizados DATETIME(6) NOT NULL,
    versao_calculo VARCHAR(80) NOT NULL,
    criado_por BIGINT NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_avaliacao_contexto (id, plano_id, lote_realizados_id),
    CONSTRAINT ck_avaliacao_meses CHECK (DAYOFMONTH(periodo_inicio) = 1 AND DAYOFMONTH(periodo_fim) = 1 AND periodo_fim >= periodo_inicio),
    CONSTRAINT fk_avaliacao_plano FOREIGN KEY (plano_id) REFERENCES plano_publicado(id),
    CONSTRAINT fk_avaliacao_realizados FOREIGN KEY (lote_realizados_id) REFERENCES lote(id),
    CONSTRAINT fk_avaliacao_operacional FOREIGN KEY (lote_operacional_id) REFERENCES lote(id),
    CONSTRAINT fk_avaliacao_autor FOREIGN KEY (criado_por) REFERENCES utilizador(id)
) ENGINE=InnoDB;

CREATE TABLE avaliacao_celula (
    avaliacao_id BIGINT NOT NULL,
    plano_id BIGINT NOT NULL,
    lote_realizados_id BIGINT NOT NULL,
    produto_id BIGINT NOT NULL,
    periodo DATE NOT NULL,
    observacao_id BIGINT NULL,
    elegivel_temporal BOOLEAN NOT NULL,
    motivo_exclusao VARCHAR(255) NULL,
    PRIMARY KEY (avaliacao_id, produto_id, periodo),
    CONSTRAINT fk_ac_avaliacao FOREIGN KEY (avaliacao_id, plano_id, lote_realizados_id) REFERENCES avaliacao(id, plano_id, lote_realizados_id),
    CONSTRAINT fk_ac_plano FOREIGN KEY (plano_id, produto_id, periodo) REFERENCES linha_plano(plano_id, produto_id, periodo),
    CONSTRAINT fk_ac_realizado FOREIGN KEY (observacao_id, lote_realizados_id, produto_id, periodo) REFERENCES observacao(id, lote_id, produto_id, periodo)
) ENGINE=InnoDB;
