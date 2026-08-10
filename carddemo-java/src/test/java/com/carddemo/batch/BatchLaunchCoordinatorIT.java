/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 */
package com.carddemo.batch;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.carddemo.service.BatchLaunchGateway.LaunchRejectedException;
import com.carddemo.service.BatchLaunchGateway.RejectionReason;
import com.carddemo.support.AbstractPostgresIT;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the launch guard uses a real cross-connection PostgreSQL advisory lock.
 */
@DisplayName("batch launch coordination against PostgreSQL")
final class BatchLaunchCoordinatorIT extends AbstractPostgresIT {

    private static final String JOB_NAME = "advisoryLockProbeJob";

    @Test
    @DisplayName("a lock held by another database session refuses the launch before metadata changes")
    void aCompetingDatabaseSessionOwnsTheLaunchGuard() throws Exception {
        final javax.sql.DataSource dataSource = new DriverManagerDataSource(
                jdbcUrl(), databaseUser(), databasePassword());
        final BatchLaunchCoordinator coordinator = new BatchLaunchCoordinator(
                mock(JobRepository.class),
                mock(JobExplorer.class),
                new JdbcTemplate(dataSource));
        final Job job = mock(Job.class);
        when(job.getName()).thenReturn(JOB_NAME);

        try (Connection owner = connect();
                PreparedStatement lock = owner.prepareStatement(
                        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            owner.setAutoCommit(false);
            lock.setString(1, BatchLaunchCoordinator.LOCK_NAMESPACE + JOB_NAME);
            try (ResultSet acquired = lock.executeQuery()) {
                assertThat(acquired.next()).isTrue();
            }

            assertThatThrownBy(() -> coordinator.start(job, Map.of()))
                    .isInstanceOfSatisfying(LaunchRejectedException.class,
                            rejected -> assertThat(rejected.rejectionReason())
                                    .isEqualTo(RejectionReason.ACTIVE_EXECUTION));
            owner.rollback();
        }
    }
}
