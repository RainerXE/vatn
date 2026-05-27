/**
 * VATN Workflow SPI — Airflow-inspired DAG execution for any VATN application.
 *
 * <p>Core primitives: {@link dev.vatn.api.workflow.VDag}, {@link dev.vatn.api.workflow.VDagTask},
 * {@link dev.vatn.api.workflow.VDagRun}, {@link dev.vatn.api.workflow.VTaskInstance}.
 *
 * <p>Extension points: {@link dev.vatn.api.workflow.VOperator} (implement to add operators),
 * {@link dev.vatn.api.workflow.VDagEngine} (the execution engine SPI).
 *
 * <p>No AI, LLM, agent, or application-specific concepts may appear in this package.
 */
package dev.vatn.api.workflow;
