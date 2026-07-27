package com.example;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Reads {@code aipersimmon_dead_letter} so an operator can see what the relay gave up on.
 *
 * <p>The application should not be writing this statement at all: the table belongs to the outbox
 * component, and querying it here couples the sample to a schema it does not own. {@link
 * com.aipersimmon.ddd.outbox.DeadLetterStore} offers only {@code store} and {@code replay}, so
 * there is no supported way to ask what is in there — see issue-00066. When the port grows a read
 * side, this interface goes away.
 *
 * <p>A MyBatis mapper rather than a {@code JdbcTemplate}: it matches how the rest of this
 * application reads, and it keeps the query in one declared place instead of inside a controller.
 * The tenant-line interceptor leaves it alone — the framework's own tables are not in {@code
 * tenancy.mybatis-plus.tenant-tables}, because a dead letter belongs to the deployment and carries
 * its tenant as data rather than as a query predicate.
 */
@Mapper
public interface DeadLetterMapper {

  @Select(
      """
      SELECT event_id  AS eventId,
             type      AS type,
             version   AS version,
             attempts  AS attempts,
             reason    AS reason,
             last_error AS lastError,
             failed_at AS failedAt
        FROM aipersimmon_dead_letter
       ORDER BY failed_at DESC
       LIMIT #{limit}
      """)
  List<DeadLetterOpsController.DeadLetter> recent(@Param("limit") int limit);
}
