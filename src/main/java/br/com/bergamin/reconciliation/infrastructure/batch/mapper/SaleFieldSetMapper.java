package br.com.bergamin.reconciliation.infrastructure.batch.mapper;

import br.com.bergamin.reconciliation.domain.model.Money;
import br.com.bergamin.reconciliation.domain.model.PaymentMethod;
import br.com.bergamin.reconciliation.domain.model.SaleRecord;
import org.springframework.batch.item.file.mapping.FieldSetMapper;
import org.springframework.batch.item.file.transform.FieldSet;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Converte uma linha do arquivo de vendas no registro de dominio.
 *
 * <p>Qualquer erro aqui (data invalida, valor com virgula, meio de pagamento desconhecido)
 * sobe como excecao e o Spring Batch a embrulha em {@code FlatFileParseException} -- que o
 * passo esta configurado para pular, registrando a linha em {@code import_error}. A
 * validacao do dominio, portanto, e o que separa linha boa de linha ruim.</p>
 */
public class SaleFieldSetMapper implements FieldSetMapper<SaleRecord> {

    @Override
    public SaleRecord mapFieldSet(FieldSet fieldSet) {
        return new SaleRecord(
                fieldSet.readString("transactionId").trim(),
                emptyToNull(fieldSet.readString("orderReference")),
                LocalDate.parse(fieldSet.readString("saleDate").trim()),
                Money.of(new BigDecimal(normalizeDecimal(fieldSet.readString("grossAmount")))),
                PaymentMethod.fromFile(fieldSet.readString("paymentMethod")),
                fieldSet.readInt("installments"));
    }

    /** Aceita tanto 1234.56 quanto o 1.234,56 que vem de planilha brasileira. */
    static String normalizeDecimal(String raw) {
        String value = raw.trim();
        if (value.contains(",")) {
            value = value.replace(".", "").replace(",", ".");
        }
        return value;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
