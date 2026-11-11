---
type: "query"
date: "2026-11-11T14:02:40.053674+00:00"
question: "Por que @EnabledIf na classe base não desabilitava os testes de integração?"
contributor: "graphify"
outcome: "corrected"
correction: "Não usar @EnabledIf/@DisabledIf em classe base; usar @ExtendWith com ExecutionCondition."
source_nodes: ["IntegrationTest", "RequiresInfrastructure"]
---

# Q: Por que @EnabledIf na classe base não desabilitava os testes de integração?

## Answer

@EnabledIf (JUnit 5) não é @Inherited: na classe base abstrata ele é ignorado pelas subclasses, e o contexto Spring tenta subir sem banco. Use um ExecutionCondition registrado com @ExtendWith (que é herdado), como RequiresInfrastructure em src/test/java/dev/msgforge/support.

## Outcome

- Signal: corrected
- Correction: Não usar @EnabledIf/@DisabledIf em classe base; usar @ExtendWith com ExecutionCondition.

## Source Nodes

- IntegrationTest
- RequiresInfrastructure