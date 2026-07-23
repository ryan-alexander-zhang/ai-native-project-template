/**
 * MyBatis-Plus storage backend for the Operation Log component: {@link
 * com.aipersimmon.ddd.operationlog.mybatisplus.OperationLogRecord} + {@link
 * com.aipersimmon.ddd.operationlog.mybatisplus.OperationLogMapper} behind {@link
 * com.aipersimmon.ddd.operationlog.mybatisplus.MybatisPlusOperationLogSink}. Behaviorally
 * equivalent to the JDBC backend (dialect-native duplicate convergence); an alternative to it, not
 * additive. Carries no DDL — the shared migrations live in the engine module.
 */
package com.aipersimmon.ddd.operationlog.mybatisplus;
