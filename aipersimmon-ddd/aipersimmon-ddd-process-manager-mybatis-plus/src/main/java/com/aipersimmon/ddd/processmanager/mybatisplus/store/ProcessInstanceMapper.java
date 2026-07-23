package com.aipersimmon.ddd.processmanager.mybatisplus.store;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * MyBatis mapper for {@code aipersimmon_process_instance}. The SQL mirrors {@code
 * JdbcProcessInstanceStore} statement-for-statement; only positional {@code ?} placeholders become
 * named {@code #{}} parameters. Registered explicitly via a {@code MapperFactoryBean}.
 */
public interface ProcessInstanceMapper {

  @Select("SELECT * FROM aipersimmon_process_instance WHERE instance_id = #{instanceId}")
  InstanceRow find(@Param("instanceId") String instanceId);

  @Select("SELECT * FROM aipersimmon_process_instance WHERE instance_id = #{instanceId} FOR UPDATE")
  InstanceRow findForUpdate(@Param("instanceId") String instanceId);

  @Select(
      "SELECT * FROM aipersimmon_process_instance WHERE process_type = #{processType} AND"
          + " business_key = #{businessKey} FOR UPDATE")
  InstanceRow findByBusinessKey(
      @Param("processType") String processType, @Param("businessKey") String businessKey);

  @Select(
      "SELECT * FROM aipersimmon_process_instance WHERE process_type = #{processType} AND"
          + " business_key = #{businessKey}")
  InstanceRow readByBusinessKey(
      @Param("processType") String processType, @Param("businessKey") String businessKey);

  @Update(
      "INSERT INTO aipersimmon_process_instance ( instance_id, process_type, business_key,"
          + " definition_version, state_schema_version, lifecycle, resume_lifecycle,"
          + " suspension_reason, business_step, outcome, revision, state_payload_type,"
          + " state_payload, created_at, updated_at, ended_at) VALUES (#{instanceId},"
          + " #{processType}, #{businessKey}, #{definitionVersion}, #{stateSchemaVersion},"
          + " #{lifecycle}, #{resumeLifecycle,jdbcType=VARCHAR}, #{suspensionReason,jdbcType=VARCHAR}, #{businessStep}, #{outcome,jdbcType=VARCHAR},"
          + " #{revision}, #{statePayloadType}, #{statePayload}, #{createdAt}, #{updatedAt},"
          + " #{endedAt,jdbcType=TIMESTAMP})")
  void insert(Map<String, Object> row);

  @Update(
      "UPDATE aipersimmon_process_instance SET lifecycle = #{lifecycle}, resume_lifecycle ="
          + " #{resumeLifecycle,jdbcType=VARCHAR}, suspension_reason ="
          + " #{suspensionReason,jdbcType=VARCHAR}, business_step ="
          + " #{businessStep}, outcome = #{outcome,jdbcType=VARCHAR}, revision = #{revision}, state_payload_type ="
          + " #{statePayloadType}, state_payload = #{statePayload}, updated_at = #{updatedAt},"
          + " ended_at = #{endedAt,jdbcType=TIMESTAMP} WHERE instance_id = #{instanceId} AND revision ="
          + " #{expectedRevision}")
  int updateSnapshot(Map<String, Object> row);

  @Update(
      "UPDATE aipersimmon_process_instance SET lifecycle = 'SUSPENDED', resume_lifecycle ="
          + " #{resumeLifecycle}, suspension_reason = #{reason}, suspension_source = #{source},"
          + " suspending_work_id = #{workId}, updated_at = #{now} WHERE instance_id ="
          + " #{instanceId}")
  void suspend(
      @Param("instanceId") String instanceId,
      @Param("resumeLifecycle") String resumeLifecycle,
      @Param("reason") String reason,
      @Param("source") String source,
      @Param("workId") String workId,
      @Param("now") Timestamp now);

  @Update(
      "UPDATE aipersimmon_process_instance SET lifecycle = #{toLifecycle}, resume_lifecycle ="
          + " NULL, suspension_reason = NULL, suspension_source = NULL, suspending_work_id = NULL,"
          + " updated_at = #{now} WHERE instance_id = #{instanceId}")
  void resume(
      @Param("instanceId") String instanceId,
      @Param("toLifecycle") String toLifecycle,
      @Param("now") Timestamp now);

  @Select(
      "SELECT COALESCE(suspension_source, 'UNKNOWN') AS src, COUNT(*) AS cnt FROM"
          + " aipersimmon_process_instance WHERE lifecycle = 'SUSPENDED' GROUP BY"
          + " suspension_source")
  List<CountBySource> countSuspendedBySource();

  @Select(
      "SELECT COUNT(*) FROM aipersimmon_process_instance i WHERE i.lifecycle IN ('RUNNING',"
          + " 'COMPENSATING') AND i.updated_at < #{updatedBefore} AND NOT EXISTS (SELECT 1 FROM"
          + " aipersimmon_process_effect e WHERE e.instance_id = i.instance_id AND e.status IN"
          + " ('PENDING', 'IN_FLIGHT')) AND NOT EXISTS (SELECT 1 FROM"
          + " aipersimmon_process_deadline d WHERE d.instance_id = i.instance_id AND d.status IN"
          + " ('PENDING', 'IN_FLIGHT'))")
  long countStuck(@Param("updatedBefore") Timestamp updatedBefore);

  @Select({
    "<script>",
    "SELECT * FROM aipersimmon_process_instance WHERE 1 = 1",
    "<if test='processType != null'> AND process_type = #{processType}</if>",
    "<if test='businessKey != null'> AND business_key = #{businessKey}</if>",
    "<if test='lifecycle != null'> AND lifecycle = #{lifecycle}</if>",
    "<if test='step != null'> AND business_step = #{step}</if>",
    "<if test='definitionVersion != null'> AND definition_version = #{definitionVersion}</if>",
    "ORDER BY created_at, instance_id LIMIT #{limit} OFFSET #{offset}",
    "</script>"
  })
  List<InstanceRow> search(
      @Param("processType") String processType,
      @Param("businessKey") String businessKey,
      @Param("lifecycle") String lifecycle,
      @Param("step") String step,
      @Param("definitionVersion") String definitionVersion,
      @Param("limit") int limit,
      @Param("offset") int offset);

  @Select(
      "SELECT * FROM aipersimmon_process_instance i WHERE i.lifecycle IN ('RUNNING',"
          + " 'COMPENSATING') AND i.updated_at < #{updatedBefore} AND NOT EXISTS (SELECT 1 FROM"
          + " aipersimmon_process_effect e WHERE e.instance_id = i.instance_id AND e.status IN"
          + " ('PENDING', 'IN_FLIGHT')) AND NOT EXISTS (SELECT 1 FROM"
          + " aipersimmon_process_deadline d WHERE d.instance_id = i.instance_id AND d.status IN"
          + " ('PENDING', 'IN_FLIGHT')) ORDER BY i.updated_at, i.instance_id LIMIT #{limit}")
  List<InstanceRow> findStuck(
      @Param("updatedBefore") Timestamp updatedBefore, @Param("limit") int limit);

  @Select(
      "SELECT DISTINCT process_type, definition_version, state_schema_version FROM"
          + " aipersimmon_process_instance WHERE lifecycle IN ('RUNNING', 'COMPENSATING',"
          + " 'SUSPENDED')")
  List<InstanceRow> distinctVersionsInUse();
}
