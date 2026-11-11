package dev.msgforge.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Desabilita os testes de integração quando não há Postgres/Redis (ver {@link TestInfrastructure}).
 * Registrada com {@code @ExtendWith} na classe base porque, ao contrário de {@code @EnabledIf},
 * {@code @ExtendWith} é herdada pelas subclasses.
 */
class RequiresInfrastructure implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return TestInfrastructure.INSTANCE != null
                ? ConditionEvaluationResult.enabled("infraestrutura disponível")
                : ConditionEvaluationResult.disabled(
                        "Sem Docker e sem IT_DATABASE_URL: testes de integração pulados (ver TestInfrastructure)");
    }
}
