package br.com.bergamin.reconciliation.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(
        packages = "br.com.bergamin.reconciliation",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String DOMINIO = "..domain..";
    private static final String APLICACAO = "..application..";
    private static final String INFRAESTRUTURA = "..infrastructure..";

    @ArchTest
    static final ArchRule dependencias_apontam_para_dentro = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Dominio").definedBy(DOMINIO)
            .layer("Aplicacao").definedBy(APLICACAO)
            .layer("Infraestrutura").definedBy(INFRAESTRUTURA)
            .whereLayer("Infraestrutura").mayNotBeAccessedByAnyLayer()
            .whereLayer("Aplicacao").mayOnlyBeAccessedByLayers("Infraestrutura")
            .whereLayer("Dominio").mayOnlyBeAccessedByLayers("Aplicacao", "Infraestrutura");

    /**
     * A regra mais importante deste projeto.
     *
     * <p>O motor de conciliacao e o que da valor ao sistema. Ele nao pode depender de Spring
     * Batch nem de JDBC. Se depender, so da para testa-lo subindo um job inteiro contra um
     * banco, e a regra de negocio deixa de ser verificavel em milissegundos.</p>
     */
    @ArchTest
    static final ArchRule dominio_nao_conhece_framework = noClasses()
            .that().resideInAPackage(DOMINIO)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta..",
                    "javax.sql..",
                    "java.sql..",
                    "com.fasterxml..");

    @ArchTest
    static final ArchRule aplicacao_nao_conhece_infraestrutura = noClasses()
            .that().resideInAPackage(APLICACAO)
            .should().dependOnClassesThat().resideInAPackage(INFRAESTRUTURA);

    /** Spring Batch e detalhe de execucao; o caso de uso fala com a porta. */
    @ArchTest
    static final ArchRule batch_fica_na_borda = noClasses()
            .that().resideInAnyPackage(DOMINIO, APLICACAO)
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.batch..");

    @ArchTest
    static final ArchRule sql_so_na_infraestrutura = noClasses()
            .that().resideInAnyPackage(DOMINIO, APLICACAO)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.jdbc..", "java.sql..");

    @ArchTest
    static final ArchRule portas_sao_interfaces = classes()
            .that().resideInAnyPackage("..application.port.in..", "..application.port.out..")
            .and().areTopLevelClasses()
            .should().beInterfaces();

    @ArchTest
    static final ArchRule controllers_nao_acessam_banco = noClasses()
            .that().resideInAPackage("..adapter.in.rest..")
            .should().dependOnClassesThat().resideInAPackage("..persistence..");

    @ArchTest
    static final ArchRule sem_dependencias_circulares = slices()
            .matching("br.com.bergamin.reconciliation.(*)..")
            .should().beFreeOfCycles();
}
