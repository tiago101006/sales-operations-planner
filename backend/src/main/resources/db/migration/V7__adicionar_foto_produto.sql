ALTER TABLE produto ADD COLUMN revisao_foto BIGINT NOT NULL DEFAULT 0;

CREATE TABLE foto_produto (
    produto_id BIGINT NOT NULL PRIMARY KEY,
    imagem MEDIUMBLOB NOT NULL,
    CONSTRAINT fk_foto_produto_produto FOREIGN KEY (produto_id) REFERENCES produto(id),
    CONSTRAINT ck_foto_produto_tamanho CHECK (OCTET_LENGTH(imagem) BETWEEN 1 AND 262144)
) ENGINE=InnoDB;
