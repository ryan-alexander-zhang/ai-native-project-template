/**
 * Persistence for the points aggregate. Nothing here mentions Seata: the data-source proxy is installed
 * by Seata's own auto-configuration and wraps the {@code DataSource} bean, so the adapter is written as
 * if the transaction were local — because from the adapter's point of view it is.
 */
package com.example.samples.s10.points.infrastructure;
