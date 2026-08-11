-- Impressao digital dos arquivos importados.
--
-- Reenviar o mesmo arquivo de repasse por engano e o erro operacional mais comum aqui: o
-- fechamento e mensal, o arquivo fica no e-mail, e alguem importa de novo achando que a
-- primeira tentativa falhou. O resultado sao duas conciliacoes identicas e a duvida sobre
-- qual delas vale. Justamente a confusao que este sistema existe para eliminar.
ALTER TABLE reconciliation_run ADD COLUMN sales_file_hash      VARCHAR(64);
ALTER TABLE reconciliation_run ADD COLUMN settlement_file_hash VARCHAR(64);

-- Nao e UNIQUE: reimportar pode ser legitimo (o operador confirma com force=true, por
-- exemplo depois de corrigir a tabela de taxas). O indice serve para achar a execucao
-- anterior e avisar, nao para proibir.
CREATE INDEX idx_run_hashes ON reconciliation_run (sales_file_hash, settlement_file_hash)
    WHERE sales_file_hash IS NOT NULL;
