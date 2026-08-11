package br.com.bergamin.reconciliation.domain.model;

/**
 * O que pode dar errado entre o que a loja vendeu e o que o adquirente repassou.
 *
 * <p>A severidade existe para tornar o relatorio acionavel. Um arquivo de fechamento
 * mensal produz centenas de apontamentos, e quem vai resolver precisa saber onde comecar --
 * dinheiro que nao entrou vem antes de uma diferenca de centavo.</p>
 */
public enum DivergenceType {

    /** Vendeu e o dinheiro nao veio. E a divergencia que mais custa caro. */
    VENDA_SEM_REPASSE(Severity.CRITICA,
            "Venda registrada no sistema sem linha correspondente no repasse"),

    /** Veio dinheiro de uma venda que a loja nao tem. Pode ser venda nao registrada ou erro do adquirente. */
    REPASSE_SEM_VENDA(Severity.ATENCAO,
            "Linha de repasse sem venda correspondente no sistema"),

    /** Valor bruto do repasse diferente do valor da venda. */
    VALOR_DIVERGENTE(Severity.CRITICA,
            "Valor bruto do repasse diferente do registrado na venda"),

    /** Taxa efetiva acima da contratada. Silenciosa: cada linha isolada parece correta. */
    TAXA_ACIMA_DO_CONTRATADO(Severity.CRITICA,
            "Taxa efetiva cobrada acima da contratada para o meio de pagamento"),

    /** Mesma transacao repassada mais de uma vez. */
    REPASSE_DUPLICADO(Severity.CRITICA,
            "Mesma transacao aparece mais de uma vez no arquivo de repasse"),

    /** A propria linha do adquirente nao fecha: liquido diferente de bruto menos taxa. */
    LIQUIDO_INCONSISTENTE(Severity.ATENCAO,
            "Valor liquido diferente de bruto menos taxa na mesma linha");

    private final Severity severity;
    private final String description;

    DivergenceType(Severity severity, String description) {
        this.severity = severity;
        this.description = description;
    }

    public Severity severity() {
        return severity;
    }

    public String description() {
        return description;
    }

    public boolean isCritical() {
        return severity == Severity.CRITICA;
    }

    public enum Severity {
        CRITICA,
        ATENCAO
    }
}
