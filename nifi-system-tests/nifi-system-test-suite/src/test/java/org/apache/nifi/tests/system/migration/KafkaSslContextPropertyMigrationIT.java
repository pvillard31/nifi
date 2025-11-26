/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.tests.system.migration;

import org.apache.nifi.tests.system.InstanceConfiguration;
import org.apache.nifi.tests.system.NiFiInstanceFactory;
import org.apache.nifi.tests.system.NiFiSystemIT;
import org.apache.nifi.tests.system.SpawnedStandaloneNiFiInstanceFactory;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class KafkaSslContextPropertyMigrationIT extends NiFiSystemIT {
    private static final Path FLOW_JSON_GZ = Path.of("src/test/resources/flows/migration/kafka-ssl-context-migration.json.gz");
    private static final String BOOTSTRAP_CONF = "src/test/resources/conf/migration/bootstrap.conf";
    private static final Path ASSEMBLY_LIB_DIR = resolveAssemblyLibDir();

    static {
        copyIfPresent("nifi-extension-bundles/nifi-kafka-bundle/nifi-kafka-nar/target", "nifi-kafka-nar-*.nar");
        copyIfPresent("nifi-extension-bundles/nifi-kafka-bundle/nifi-kafka-3-service-nar/target", "nifi-kafka-3-service-nar-*.nar");
        copyIfPresent("nifi-extension-bundles/nifi-kafka-bundle/nifi-kafka-service-api-nar/target", "nifi-kafka-service-api-nar-*.nar");
        copyIfPresent("nifi-extension-bundles/nifi-standard-services/nifi-ssl-context-bundle/nifi-ssl-context-service-nar/target", "nifi-ssl-context-service-nar-*.nar");
    }

    @Override
    public NiFiInstanceFactory getInstanceFactory() {
        return new SpawnedStandaloneNiFiInstanceFactory(
                new InstanceConfiguration.Builder()
                        .bootstrapConfig(BOOTSTRAP_CONF)
                        .instanceDirectory("target/kafka-ssl-context-migration-instance")
                        .flowJson(FLOW_JSON_GZ.toFile())
                        .build()
        );
    }

    @Override
    protected boolean isAllowFactoryReuse() {
        return false;
    }

    @Override
    protected boolean isDestroyFlowAfterEachTest() {
        return false;
    }

    @Test
    public void testNiFiStartsWithinOneMinuteWithSslContextPropertyMigration() throws IOException {
        final File instanceDirectory = getNiFiInstance().getInstanceDirectory();
        final Path instanceFlowPath = instanceDirectory.toPath().resolve("conf").resolve("flow.json.gz");

        getNiFiInstance().stop();
        Files.copy(FLOW_JSON_GZ, instanceFlowPath, StandardCopyOption.REPLACE_EXISTING);

        final Instant start = Instant.now();
        getNiFiInstance().start(true);
        final Duration startupDuration = Duration.between(start, Instant.now());

        assertTrue(startupDuration.compareTo(Duration.ofMinutes(1)) < 0,
                () -> "NiFi startup exceeded 1 minute with SSL context migration flow: " + startupDuration);
    }

    private static Path resolveAssemblyLibDir() {
        final Path moduleRelative = Path.of("target/nifi-lib-assembly/lib");
        if (Files.isDirectory(moduleRelative)) {
            return moduleRelative;
        }

        final Path repoRelative = Path.of("nifi-system-tests/nifi-system-test-suite/target/nifi-lib-assembly/lib");
        return repoRelative;
    }

    private static void copyIfPresent(final String sourceDir, final String glob) {
        final Path source = Path.of(sourceDir);
        if (!Files.isDirectory(source)) {
            return;
        }

        try {
            Files.createDirectories(ASSEMBLY_LIB_DIR);
            Files.list(source)
                    .filter(path -> path.getFileName().toString().matches(glob.replace("*", ".*")))
                    .forEach(path -> {
                        final Path target = ASSEMBLY_LIB_DIR.resolve(path.getFileName());
                        try {
                            Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                        } catch (final IOException e) {
                            throw new RuntimeException("Failed to copy NAR " + path + " to assembly lib dir", e);
                        }
                    });
        } catch (final IOException e) {
            throw new RuntimeException("Failed to copy NARs matching " + glob + " from " + source, e);
        }
    }
}
