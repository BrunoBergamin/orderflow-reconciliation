# Conciliação financeira — OrderFlow Reconciliation

[![CI](https://github.com/BrunoBergamin/orderflow-reconciliation/actions/workflows/ci.yml/badge.svg)](https://github.com/BrunoBergamin/orderflow-reconciliation/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Batch](https://img.shields.io/badge/Spring%20Batch-5-brightgreen)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)

Trabalho com operação de e-commerce e já tive que conferir repasse de maquininha na mão,
comparando planilha de vendas com o arquivo do adquirente. É um trabalho chato, demorado e
que ninguém faz até o fim — e é justamente aí que o dinheiro escapa.

Este serviço faz isso sozinho: recebe o relatório de vendas da loja e o arquivo de repasse
do adquirente, cruza transação por transação e aponta o que não fecha.

## O que ele encontra

O óbvio primeiro: **venda que não teve repasse** (o dinheiro simplesmente não entrou),
**repasse sem venda** e **valor divergente** entre o que a loja registrou e o que o
adquirente pagou.

O que quase ninguém confere é a **taxa**. Cada linha isolada parece certa — o bruto bate, o
líquido bate — mas a taxa efetiva cobrada está acima da contratada. Em uma venda isso é
troco; em alguns milhares de vendas por mês, é um salário. O serviço calcula a taxa efetiva
de cada transação com 4 casas decimais e compara com a tabela contratada por meio de
pagamento.

Ainda pega **repasse duplicado** (a mesma transação paga duas vezes) e **linha
inconsistente** (o próprio arquivo do adquirente onde líquido ≠ bruto − taxa).

Cada apontamento sai com valor esperado, valor encontrado e a diferença — para dar pra
decidir na hora se vale abrir chamado, sem reabrir os dois arquivos.

## Rodando

```bash
docker compose up --build
```

Swagger em http://localhost:8082/swagger-ui.html. Tem dois CSV de exemplo em `exemplos/`,
já montados com um problema de cada tipo (inclusive duas linhas corrompidas de propósito):

```bash
curl -X POST http://localhost:8082/api/v1/reconciliations \
  -F "salesFile=@exemplos/vendas.csv" \
  -F "settlementFile=@exemplos/repasse.csv"
# 202 Accepted + o id da execução

curl http://localhost:8082/api/v1/reconciliations/{id} | jq
curl http://localhost:8082/api/v1/reconciliations/{id}/divergences | jq
curl http://localhost:8082/api/v1/reconciliations/{id}/import-errors | jq
```

## Como funciona

O job tem quatro passos: importa as vendas, importa o repasse, concilia e fecha o resumo.

Importar os dois arquivos antes de comparar custa uma passada extra no banco. Em troca, os
arquivos podem vir em qualquer ordem, o cruzamento sai por índice e dá para reprocessar a
comparação sem reler nada. E o job reinicia do passo onde parou, que é a razão de usar
Spring Batch em vez de um `for` lendo arquivo.

A comparação em si mora no `ReconciliationEngine` — Java puro, sem Spring, sem SQL. Ele
recebe uma venda e as linhas de repasse daquela transação e devolve a lista de apontamentos.
Isso deixa a regra que dá valor ao sistema testável em milissegundos: são 11 testes cobrindo
os seis tipos de divergência e as duas tolerâncias, sem subir contexto nenhum.

O último passo é SQL: achar repasse órfão é um anti-join, e consolidar o resumo é agregação.
Trazer isso para a JVM só para percorrer em Java seria mais lento e não ficaria mais legível.

## Decisões que valem explicar

**Linha ruim não derruba o arquivo, mas também não some.** Um fechamento com 50 mil linhas
não pode ser rejeitado inteiro por três linhas mal formatadas. O passo pula a linha e grava
o número, o conteúdo original e o motivo em `import_error`, consultável pela API. Pular em
silêncio seria trocar um problema visível (job quebrado) por um invisível (venda que sumiu
do relatório).

**Tolerância de um centavo.** O arquivo do adquirente já vem arredondado. Sem tolerância, um
relatório de 10 mil linhas viria com milhares de alertas de centavo e ninguém olharia mais
para ele — que é como um sistema de conciliação morre na prática. Mesma ideia na taxa: folga
de 0,05 ponto percentual antes de acusar.

**O POST devolve 202, não 200.** Quando a resposta sai, o processamento ainda não terminou.
Um arquivo de fechamento leva minutos, e segurar a conexão HTTP até o fim garante timeout no
cliente com o job rodando sem ninguém para receber o resultado.

**As tabelas do Spring Batch entram no Flyway** (`V2__spring_batch_schema.sql`), em vez de
`initialize-schema=always`. Em produção ninguém quer a aplicação criando tabela sozinha na
subida — e assim o schema do Batch passa pela mesma revisão que o resto.

**Sem JPA.** Não há agregado com ciclo de vida aqui: o serviço importa em massa e responde
consulta de relatório. Nos dois casos o ORM só colocaria uma camada entre o SQL e o
resultado. JDBC direto, e o motor de domínio continua limpo.

**As taxas contratadas vêm de configuração**, não do código. Quando a loja renegocia a taxa
do crédito, isso é variável de ambiente, não deploy.

**O nome do arquivo enviado nunca monta caminho.** Ele é gravado com um UUID e o nome
original fica só no banco, para exibição — `../../etc/passwd` num campo de upload é o ataque
mais antigo que existe.

## Testes

```bash
./mvnw test      # 24 testes, ~5s, não precisa de Docker
./mvnw verify    # + 10 testes de integração com PostgreSQL real
```

O teste que mais gosto é o `ReconciliationJobIT`: monta dois CSV com um problema de cada
tipo, roda o job inteiro e confere não só a quantidade de apontamentos, mas os valores — e o
total em risco, que é o número que o dono da loja realmente olha. Naquele cenário dá
R$ 1.264,48, e o teste falha se mudar um centavo.

O PostgreSQL é de verdade, via Testcontainers. Aqui não havia escolha: o job usa
`gen_random_uuid()`, índice parcial e as próprias tabelas de controle do Spring Batch — em
banco em memória nada disso se comporta igual.

As 8 regras de arquitetura são testadas com ArchUnit e rodam no CI. A mais importante:
o motor de conciliação não pode depender de Spring Batch nem de JDBC. Se depender, só dá
para testá-lo subindo um job contra um banco, e a regra de negócio deixa de ser verificável
em segundos.

## Formato dos arquivos

CSV separado por `;`, com cabeçalho. Aceita valor no formato brasileiro (`1.234,56`) ou com
ponto (`1234.56`).

```
vendas:  transaction_id;order_reference;sale_date;gross_amount;payment_method;installments
repasse: transaction_id;settlement_date;gross_amount;fee_amount;net_amount
```

Meios de pagamento: `CREDITO`, `CREDITO_PARCELADO`, `DEBITO`, `PIX`, `BOLETO`.

## Stack

Java 21, Spring Boot 3.5, Spring Batch 5, PostgreSQL 16, Flyway, springdoc, Actuator +
Prometheus. Testes com JUnit 5, AssertJ, Testcontainers e ArchUnit. Docker multi-stage em
camadas e GitHub Actions.

## Os outros dois serviços

Este é o terceiro de um sistema que montei para estudar arquitetura de back-end:

- [orderflow](https://github.com/BrunoBergamin/orderflow) — API de pedidos com idempotência,
  reserva de estoque com lock otimista e Transactional Outbox
- [orderflow-fulfillment](https://github.com/BrunoBergamin/orderflow-fulfillment) — consumidor
  dos eventos, com DLQ, circuit breaker e cache

---

**Bruno Alves Bergamin** — back-end Java ·
[LinkedIn](https://www.linkedin.com/in/bruno-alves-bergamin-6b711a347) · Licença MIT
