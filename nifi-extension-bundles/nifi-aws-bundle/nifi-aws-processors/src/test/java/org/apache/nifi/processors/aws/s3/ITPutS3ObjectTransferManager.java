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
package org.apache.nifi.processors.aws.s3;

import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.processor.DataUnit;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.multipart.MultipartConfiguration;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.FileUpload;
import software.amazon.awssdk.transfer.s3.model.ResumableFileUpload;
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ITPutS3ObjectTransferManager extends AbstractS3IT {

    private static final Pattern ETAG_PATTERN = Pattern.compile("[0-9a-fA-F\\-]{32,}");
    private static final String TRANSFER_MANAGER_STATE_SUFFIX = ".tm";
    private static final String TRANSFER_MANAGER_STAGING_SUFFIX = ".upload";
    private Path multipartTempDir;
    private Path largeContentFile;

    @AfterEach
    void cleanupArtifacts() throws IOException {
        if (largeContentFile != null) {
            Files.deleteIfExists(largeContentFile);
            largeContentFile = null;
        }

        if (multipartTempDir != null) {
            deleteRecursively(multipartTempDir);
            multipartTempDir = null;
        }
    }

    @Test
    void testMultipartUploadWithTransferManager() throws Exception {
        multipartTempDir = Files.createTempDirectory("put-s3-tm-success");

        final TestRunner runner = initRunner(PutS3Object.class);
        configureTransferManagerProperties(runner, "50 MB", "50 MB", "2", multipartTempDir);

        final long objectSize = 55L * 1024L * 1024L;
        largeContentFile = createTempFile(objectSize);

        final String objectKey = "transfer-manager-success.bin";
        runner.enqueue(largeContentFile, Map.of(CoreAttributes.FILENAME.key(), objectKey));
        runner.assertValid();

        runner.run();
        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS, 1);

        final MockFlowFile success = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS).getFirst();
        assertEquals(PutS3Object.S3_API_METHOD_MULTIPARTUPLOAD, success.getAttribute(PutS3Object.S3_API_METHOD_ATTR_KEY));
        assertTrue(ETAG_PATTERN.matcher(success.getAttribute(PutS3Object.S3_ETAG_ATTR_KEY)).matches());
        assertEquals(BUCKET_NAME, success.getAttribute(PutS3Object.S3_BUCKET_KEY));
        assertEquals(objectKey, success.getAttribute(PutS3Object.S3_OBJECT_KEY));

        final HeadObjectResponse head = getClient().headObject(HeadObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(objectKey)
                .build());
        assertEquals(objectSize, head.contentLength());

        runner.shutdown();
    }

    @Test
    void testTransferManagerResumeAfterPause() throws Exception {
        multipartTempDir = Files.createTempDirectory("put-s3-tm-resume");

        final TestRunner runner = initRunner(PutS3Object.class);
        configureTransferManagerProperties(runner, "60 MB", "60 MB", "32", multipartTempDir);

        final long objectSize = 512L * 1024L * 1024L;
        largeContentFile = createTempFile(objectSize);

        final String objectKey = "transfer-manager-resume.bin";
        final String flowFileUuid = UUID.randomUUID().toString();
        runner.enqueue(largeContentFile, Map.of(
                CoreAttributes.FILENAME.key(), objectKey,
                CoreAttributes.UUID.key(), flowFileUuid));
        runner.assertValid();

        final PutS3Object processor = (PutS3Object) runner.getProcessor();
        final String cacheKey = processor.getIdentifier() + "/" + BUCKET_NAME + "/" + objectKey;

        final Path stagingDir = multipartTempDir.resolve("transfer-manager");
       Files.createDirectories(stagingDir);
       final Path stagingFile = stagingDir.resolve(hashIdentifier(cacheKey + ":" + flowFileUuid) + TRANSFER_MANAGER_STAGING_SUFFIX);
       Files.copy(largeContentFile, stagingFile, StandardCopyOption.REPLACE_EXISTING);

        final double partSizeValue = runner.getProcessContext()
                .getProperty(PutS3Object.MULTIPART_PART_SIZE)
                .asDataSize(DataUnit.B);
        final long partSizeBytes = (long) partSizeValue;

        final ResumableFileUpload resumableUpload = createResumableUpload(objectKey, stagingFile, partSizeBytes);
        persistResumableState(processor, cacheKey, resumableUpload);

        runner.run();
        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS, 1);
        assertEquals(0, runner.getQueueSize().getObjectCount(), "Queue should be empty after successful resume");

        final MockFlowFile success = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS).getFirst();
        assertEquals(objectKey, success.getAttribute(PutS3Object.S3_OBJECT_KEY));
        assertEquals(PutS3Object.S3_API_METHOD_MULTIPARTUPLOAD, success.getAttribute(PutS3Object.S3_API_METHOD_ATTR_KEY));

        final HeadObjectResponse head = getClient().headObject(HeadObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(objectKey)
                .build());
        assertEquals(objectSize, head.contentLength());

        final Properties clearedState = loadTransferManagerState(processor);
        assertTrue(clearedState.isEmpty() || clearedState.getProperty(cacheKey + TRANSFER_MANAGER_STATE_SUFFIX) == null,
                "Transfer manager state should be removed after successful completion: " + clearedState);
        for (int attempt = 0; attempt < 25 && Files.exists(stagingFile); attempt++) {
            Thread.sleep(200);
        }
        if (Files.exists(stagingFile)) {
            Files.deleteIfExists(stagingFile);
        }
        assertTrue(Files.notExists(stagingFile), "Staging file should be removed after successful completion");

        runner.shutdown();
    }

    private ResumableFileUpload createResumableUpload(final String objectKey,
                                                      final Path stagingFile,
                                                      final long partSizeInBytes) throws Exception {
        final NettyNioAsyncHttpClient.Builder httpClientBuilder = NettyNioAsyncHttpClient.builder()
                .maxConcurrency(4)
                .maxPendingConnectionAcquires(8);

        final S3AsyncClient asyncClient = S3AsyncClient.builder()
                .httpClientBuilder(httpClientBuilder)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .multipartEnabled(true)
                .multipartConfiguration(MultipartConfiguration.builder()
                        .minimumPartSizeInBytes(partSizeInBytes)
                        .thresholdInBytes(partSizeInBytes)
                        .build())
                .endpointOverride(getEndpoint())
                .region(Region.of(getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("accessKey", "secretKey")))
                .build();

        final S3TransferManager transferManager = S3TransferManager.builder()
                .s3Client(asyncClient)
                .build();

        try {
            final UploadFileRequest uploadFileRequest = UploadFileRequest.builder()
                    .source(stagingFile)
                    .putObjectRequest(PutObjectRequest.builder()
                            .bucket(BUCKET_NAME)
                            .key(objectKey)
                            .build())
                    .build();

            final FileUpload fileUpload = transferManager.uploadFile(uploadFileRequest);

            final long waitDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (fileUpload.progress().snapshot().transferredBytes() == 0 && System.nanoTime() < waitDeadlineNanos) {
                TimeUnit.MILLISECONDS.sleep(100);
            }

            final ResumableFileUpload resumableUpload = fileUpload.pause();
            fileUpload.completionFuture().cancel(true);

            return resumableUpload.toBuilder()
                    .fileLastModified(Files.getLastModifiedTime(stagingFile).toInstant())
                    .fileLength(Files.size(stagingFile))
                    .partSizeInBytes(partSizeInBytes)
                    .build();
        } finally {
            transferManager.close();
            asyncClient.close();
        }
    }

    private void configureTransferManagerProperties(final TestRunner runner,
                                                    final String threshold,
                                                    final String partSize,
                                                    final String maxConcurrency,
                                                    final Path tempDirectory) {
        runner.setProperty(PutS3Object.MULTIPART_THRESHOLD, threshold);
        runner.setProperty(PutS3Object.MULTIPART_PART_SIZE, partSize);
        runner.setProperty(PutS3Object.MULTIPART_UPLOAD_MAX_CONCURRENCY, maxConcurrency);
        runner.setProperty(PutS3Object.MULTIPART_TEMP_DIR, tempDirectory.toString());
    }

    private void persistResumableState(final PutS3Object processor, final String cacheKey, final ResumableFileUpload resumableUpload)
            throws IOException {
        final File persistenceFile = processor.getPersistenceFile();
        final Properties properties = new Properties();
        if (persistenceFile.exists()) {
            try (FileInputStream fis = new FileInputStream(persistenceFile)) {
                properties.load(fis);
            }
        }

        properties.setProperty(cacheKey + TRANSFER_MANAGER_STATE_SUFFIX,
                System.currentTimeMillis() + ":" + resumableUpload.serializeToString());

        try (FileOutputStream fos = new FileOutputStream(persistenceFile)) {
            properties.store(fos, null);
        }
    }

    private Properties loadTransferManagerState(final PutS3Object processor) throws IOException {
        final File persistenceFile = processor.getPersistenceFile();
        final Properties properties = new Properties();
        if (persistenceFile.isFile()) {
            try (FileInputStream fis = new FileInputStream(persistenceFile)) {
                properties.load(fis);
            }
        }
        return properties;
    }

    private String hashIdentifier(final String value) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            final StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private Path createTempFile(final long size) throws IOException {
        final Path file = Files.createTempFile("put-s3-transfer-manager-", ".bin");
        try (OutputStream out = Files.newOutputStream(file)) {
            final byte[] buffer = new byte[8192];
            long remaining = size;
            byte value = 0;
            while (remaining > 0) {
                final int length = (int) Math.min(buffer.length, remaining);
                for (int i = 0; i < length; i++) {
                    buffer[i] = value;
                    value++;
                }
                out.write(buffer, 0, length);
                remaining -= length;
            }
        }
        return file;
    }

    private void deleteRecursively(final Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
