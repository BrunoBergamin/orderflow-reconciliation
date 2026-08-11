package br.com.bergamin.reconciliation.infrastructure.batch.mapper;

import br.com.bergamin.reconciliation.domain.model.Money;
import br.com.bergamin.reconciliation.domain.model.SettlementRecord;
import org.springframework.batch.item.file.mapping.FieldSetMapper;
import org.springframework.batch.item.file.transform.FieldSet;

import java.math.BigDecimal;
import java.time.LocalDate;

import static br.com.bergamin.reconciliation.infrastructure.batch.mapper.SaleFieldSetMapper.normalizeDecimal;

/** Converte uma linha do arquivo de repasse do adquirente. */
public class SettlementFieldSetMapper implements FieldSetMapper<SettlementRecord> {

    @Override
    public SettlementRecord mapFieldSet(FieldSet fieldSet) {
        return new SettlementRecord(
                fieldSet.readString("transactionId").trim(),
                LocalDate.parse(fieldSet.readString("settlementDate").trim()),
                money(fieldSet.readString("grossAmount")),
                money(fieldSet.readString("feeAmount")),
                money(fieldSet.readString("netAmount")));
    }

    private Money money(String raw) {
        return Money.of(new BigDecimal(normalizeDecimal(raw)));
    }
}
