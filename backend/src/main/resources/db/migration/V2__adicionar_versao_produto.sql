-- Versao para detetar pedidos de alteracao baseados em dados desatualizados.
ALTER TABLE produto ADD COLUMN versao_lock BIGINT NOT NULL DEFAULT 0;
