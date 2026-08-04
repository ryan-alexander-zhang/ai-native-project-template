/**
 * The adapters, and the one line of the library that had never been exercised before this sample.
 *
 * <p>{@code ClearedColumns.isEmittedAnyway} excludes a MyBatis-Plus logic-delete column from the assignments
 * it forces onto an aggregate's update, with the comment "deleting a row is not something saving an aggregate
 * does". Until S27 nothing in the samples tree used {@code @TableLogic} at all — it was the only mention of it
 * in the whole repository — so that exclusion was a correct-looking line nobody had run. {@code
 * ClearedColumnsTest} runs it, and runs the case it does not cover.
 */
package com.example.samples.s27.customer.infrastructure;
