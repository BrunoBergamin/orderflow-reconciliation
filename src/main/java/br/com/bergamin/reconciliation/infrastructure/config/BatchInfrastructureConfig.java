package br.com.bergamin.reconciliation.infrastructure.config;

import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * Executor do job.
 *
 * <p>Em producao o lancamento e assincrono: a requisicao HTTP devolve 202 com o id da
 * execucao e o processamento segue em segundo plano. Um arquivo de fechamento mensal leva
 * minutos, e segurar a conexao HTTP durante isso garante timeout no cliente e um job cujo
 * resultado ninguem sabe.</p>
 *
 * <p>Nos testes vira sincrono ({@code reconciliation.job.async=false}) para que a assercao
 * venha depois do processamento, sem espera arbitraria.</p>
 */
@Configuration
public class BatchInfrastructureConfig {

    /**
     * Nome proprio e {@code @Primary} porque o Boot ja registra um {@code jobLauncher}
     * sincrono. Em vez de sobrescrever a definicao dele (o que exigiria ligar
     * {@code allow-bean-definition-overriding}, que esconde conflito de verdade), este
     * convive com o outro e tem precedencia na injecao.
     */
    @Bean
    @Primary
    public JobLauncher reconciliationJobLauncher(
            JobRepository jobRepository,
            @Value("${reconciliation.job.async:true}") boolean async) throws Exception {
        TaskExecutor executor = async
                ? new SimpleAsyncTaskExecutor("conciliacao-")
                : new SyncTaskExecutor();

        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(executor);
        launcher.afterPropertiesSet();
        return launcher;
    }
}
