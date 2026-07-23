package com.aipersimmon.ddd.processmanager.mybatisplus.store;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * MyBatis mapper for {@code aipersimmon_process_transition}. SQL mirrors {@code
 * JdbcProcessTransitionStore} statement-for-statement.
 */
public interface ProcessTransitionMapper {

  @Select(
      "SELECT transition_id FROM aipersimmon_process_transition WHERE instance_id = #{instanceId}"
          + " AND input_message_id = #{inputMessageId}")
  String findTransitionIdByInput(
      @Param("instanceId") String instanceId, @Param("inputMessageId") String inputMessageId);

  @Select(
      "SELECT transition_id FROM aipersimmon_process_transition WHERE instance_id = #{instanceId}"
          + " ORDER BY transition_seq DESC LIMIT 1")
  String findLatestTransitionId(@Param("instanceId") String instanceId);

  @Select(
      "SELECT MAX(transition_seq) FROM aipersimmon_process_transition WHERE instance_id ="
          + " #{instanceId}")
  Long maxTransitionSeq(@Param("instanceId") String instanceId);

  @Update(
      "INSERT INTO aipersimmon_process_transition ( transition_id, instance_id, transition_seq,"
          + " input_message_id, input_type, input_version, input_payload, from_lifecycle,"
          + " to_lifecycle, from_step, to_step, decision_code, transition_kind, correlation_id,"
          + " created_at) VALUES (#{transitionId}, #{instanceId}, #{transitionSeq},"
          + " #{inputMessageId}, #{inputType}, #{inputVersion}, #{inputPayload},"
          + " #{fromLifecycle,jdbcType=VARCHAR}, #{toLifecycle}, #{fromStep,jdbcType=VARCHAR},"
          + " #{toStep}, #{decisionCode}, #{transitionKind}, #{correlationId,jdbcType=VARCHAR},"
          + " #{createdAt})")
  void append(Map<String, Object> row);

  @Update(
      "INSERT INTO aipersimmon_process_transition ( transition_id, instance_id, transition_seq,"
          + " input_message_id, input_type, input_version, input_payload, from_lifecycle,"
          + " to_lifecycle, from_step, to_step, decision_code, transition_kind, correlation_id,"
          + " operator_id, operation_reason, created_at) VALUES (#{transitionId}, #{instanceId},"
          + " #{transitionSeq}, #{inputMessageId}, #{inputType}, #{inputVersion}, #{inputPayload},"
          + " #{fromLifecycle}, #{toLifecycle}, #{fromStep}, #{toStep}, #{decisionCode},"
          + " #{transitionKind}, #{correlationId,jdbcType=VARCHAR}, #{operatorId},"
          + " #{operationReason,jdbcType=VARCHAR}, #{createdAt})")
  void appendOperator(Map<String, Object> row);

  @Select(
      "SELECT transition_id, input_message_id, from_lifecycle, to_lifecycle, from_step, to_step,"
          + " decision_code, transition_kind, operator_id, operation_reason, created_at FROM"
          + " aipersimmon_process_transition WHERE instance_id = #{instanceId} ORDER BY"
          + " transition_seq")
  List<TransitionViewRow> timeline(@Param("instanceId") String instanceId);

  @Select(
      "SELECT input_message_id, input_type, input_version, input_payload, correlation_id FROM"
          + " aipersimmon_process_transition WHERE instance_id = #{instanceId} AND transition_kind"
          + " = 'PARKED' ORDER BY transition_seq")
  List<ParkedRow> findParkedInputs(@Param("instanceId") String instanceId);
}
