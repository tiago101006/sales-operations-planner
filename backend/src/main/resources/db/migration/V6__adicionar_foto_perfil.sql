CREATE TABLE foto_perfil (
    utilizador_id BIGINT NOT NULL PRIMARY KEY,
    imagem MEDIUMBLOB NOT NULL,
    CONSTRAINT fk_foto_perfil_utilizador FOREIGN KEY (utilizador_id) REFERENCES utilizador(id),
    CONSTRAINT ck_foto_perfil_tamanho CHECK (OCTET_LENGTH(imagem) BETWEEN 1 AND 262144)
) ENGINE=InnoDB;
