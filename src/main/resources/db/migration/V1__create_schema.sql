-- Conciliacao: uma execucao produz as linhas importadas dos dois arquivos,
-- os apontamentos encontrados e o registro das linhas que nao puderam ser lidas.

CREATE TABLE reconciliation_run (
    id                    UUID          PRIMARY KEY,
    sales_file            VARCHAR(255)  NOT NULL,
    settlement_file       VARCHAR(255)  NOT NULL,
    reference_date        DATE,
    status                VARCHAR(20)   NOT NULL,
    started_at            TIMESTAMPTZ   NOT NULL,
    finished_at           TIMESTAMPTZ,
    sales_read            BIGINT        NOT NULL DEFAULT 0,
    settlements_read      BIGINT        NOT NULL DEFAULT 0,
    matched               BIGINT        NOT NULL DEFAULT 0,
    divergences           BIGINT        NOT NULL DEFAULT 0,
    critical_divergences  BIGINT        NOT NULL DEFAULT 0,
    amount_at_risk        NUMERIC(19,2) NOT NULL DEFAULT 0,
    failure_reason        VARCHAR(500)
);

CREATE TABLE sale_record (
    id              UUID          PRIMARY KEY,
    run_id          UUID          NOT NULL REFERENCES reconciliation_run (id) ON DELETE CASCADE,
    transaction_id  VARCHAR(80)   NOT NULL,
    order_reference VARCHAR(80),
    sale_date       DATE          NOT NULL,
    gross_amount    NUMERIC(19,2) NOT NULL,
    payment_method  VARCHAR(30)   NOT NULL,
    installments    INTEGER       NOT NULL DEFAULT 1
);

CREATE TABLE settlement_record (
    id              UUID          PRIMARY KEY,
    run_id          UUID          NOT NULL REFERENCES reconciliation_run (id) ON DELETE CASCADE,
    transaction_id  VARCHAR(80)   NOT NULL,
    settlement_date DATE          NOT NULL,
    gross_amount    NUMERIC(19,2) NOT NULL,
    fee_amount      NUMERIC(19,2) NOT NULL,
    net_amount      NUMERIC(19,2) NOT NULL
);

CREATE TABLE divergence (
    id              UUID          PRIMARY KEY,
    run_id          UUID          NOT NULL REFERENCES reconciliation_run (id) ON DELETE CASCADE,
    type            VARCHAR(40)   NOT NULL,
    severity        VARCHAR(20)   NOT NULL,
    transaction_id  VARCHAR(80)   NOT NULL,
    order_reference VARCHAR(80),
    expected_amount NUMERIC(19,2),
    found_amount    NUMERIC(19,2),
    difference      NUMERIC(19,2),
    details         VARCHAR(500)
);

-- Linha que o parser nao conseguiu ler. Existe para que uma linha ruim seja pulada sem
-- derrubar o job E sem sumir: arquivo de fechamento com 3 linhas quebradas em 50 mil nao
-- pode nem parar o processamento, nem virar dinheiro perdido em silencio.
CREATE TABLE import_error (
    id           UUID         PRIMARY KEY,
    run_id       UUID         NOT NULL REFERENCES reconciliation_run (id) ON DELETE CASCADE,
    source_file  VARCHAR(20)  NOT NULL,
    line_number  BIGINT,
    raw_line     VARCHAR(1000),
    message      VARCHAR(500) NOT NULL,
    occurred_at  TIMESTAMPTZ  NOT NULL
);

-- A conciliacao cruza os dois lados por transaction_id dentro de uma execucao;
-- sem estes indices o join vira varredura completa em arquivo grande.
CREATE INDEX idx_sale_run_transaction       ON sale_record (run_id, transaction_id);
CREATE INDEX idx_settlement_run_transaction ON settlement_record (run_id, transaction_id);
CREATE INDEX idx_divergence_run             ON divergence (run_id);
CREATE INDEX idx_divergence_run_type        ON divergence (run_id, type);
CREATE INDEX idx_import_error_run           ON import_error (run_id);
