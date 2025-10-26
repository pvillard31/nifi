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

import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.annotation.behavior.DynamicProperty;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.fileresource.service.api.FileResource;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.migration.PropertyConfiguration;
import org.apache.nifi.processor.DataUnit;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.proxy.ProxyConfiguration;
import org.apache.nifi.processors.aws.AwsHttpClientConfigurer;
import org.apache.nifi.processors.aws.region.RegionUtil;
import org.apache.nifi.processors.aws.s3.encryption.StandardS3EncryptionService;
import org.apache.nifi.processors.transfer.ResourceTransferSource;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.TlsKeyManagersProvider;
import software.amazon.awssdk.http.TlsTrustManagersProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.DefaultRetryStrategy;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.S3CrtAsyncClientBuilder;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.SdkPartType;
import software.amazon.awssdk.services.s3.model.StorageClass;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.services.s3.multipart.MultipartConfiguration;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.CompletedFileUpload;
import software.amazon.awssdk.transfer.s3.model.FileUpload;
import software.amazon.awssdk.transfer.s3.model.ResumableFileUpload;
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.Proxy;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.apache.nifi.processors.aws.s3.util.Expiration;

import static org.apache.nifi.processors.aws.region.RegionUtil.CUSTOM_REGION_WITH_FF_EL;
import static org.apache.nifi.processors.aws.region.RegionUtil.REGION;
import static org.apache.nifi.processors.aws.s3.util.S3Util.getResourceUrl;
import static org.apache.nifi.processors.aws.s3.util.S3Util.parseExpirationHeader;
import static org.apache.nifi.processors.aws.s3.util.S3Util.sanitizeETag;
import static org.apache.nifi.processors.transfer.ResourceTransferProperties.FILE_RESOURCE_SERVICE;
import static org.apache.nifi.processors.transfer.ResourceTransferProperties.RESOURCE_TRANSFER_SOURCE;
import static org.apache.nifi.processors.transfer.ResourceTransferUtils.getFileResource;

@SupportsBatching
@SeeAlso({FetchS3Object.class, DeleteS3Object.class, ListS3.class, CopyS3Object.class, GetS3ObjectMetadata.class, GetS3ObjectTags.class, TagS3Object.class})
@InputRequirement(Requirement.INPUT_REQUIRED)
@Tags({"Amazon", "S3", "AWS", "Archive", "Put"})
@CapabilityDescription("Writes the contents of a FlowFile as an S3 Object to an Amazon S3 Bucket.")
@DynamicProperty(name = "The name of a User-Defined Metadata field to add to the S3 Object",
        value = "The value of a User-Defined Metadata field to add to the S3 Object",
        description = "Allows user-defined metadata to be added to the S3 object as key/value pairs",
        expressionLanguageScope = ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
@ReadsAttribute(attribute = "filename", description = "Uses the FlowFile's filename as the filename for the S3 object")
@WritesAttributes({
    @WritesAttribute(attribute = "s3.url", description = "The URL that can be used to access the S3 object"),
    @WritesAttribute(attribute = "s3.bucket", description = "The S3 bucket where the Object was put in S3"),
    @WritesAttribute(attribute = "s3.key", description = "The S3 key within where the Object was put in S3"),
    @WritesAttribute(attribute = "s3.contenttype", description = "The S3 content type of the S3 Object that put in S3"),
    @WritesAttribute(attribute = "s3.version", description = "The version of the S3 Object that was put to S3"),
    @WritesAttribute(attribute = "s3.exception", description = "The class name of the exception thrown during processor execution"),
    @WritesAttribute(attribute = "s3.additionalDetails", description = "The S3 supplied detail from the failed operation"),
    @WritesAttribute(attribute = "s3.statusCode", description = "The HTTP error code (if available) from the failed operation"),
    @WritesAttribute(attribute = "s3.errorCode", description = "The S3 moniker of the failed operation"),
    @WritesAttribute(attribute = "s3.errorMessage", description = "The S3 exception message from the failed operation"),
    @WritesAttribute(attribute = "s3.etag", description = "The ETag of the S3 Object"),
    @WritesAttribute(attribute = "s3.contentdisposition", description = "The content disposition of the S3 Object that put in S3"),
    @WritesAttribute(attribute = "s3.cachecontrol", description = "The cache-control header of the S3 Object"),
    @WritesAttribute(attribute = "s3.uploadId", description = "The uploadId used to upload the Object to S3"),
    @WritesAttribute(attribute = "s3.expirationTime", description = "If the S3 object has been assigned an expiration time, this attribute will be set, " +
            "containing the milliseconds since epoch in UTC time"),
    @WritesAttribute(attribute = "s3.expirationTimeRuleId", description = "If the S3 object has been assigned an expiration time, this attribute will be set, " +
            "containing the ID of the rule that dictates this object's expiration time"),
    @WritesAttribute(attribute = "s3.sseAlgorithm", description = "The server side encryption algorithm of the object"),
    @WritesAttribute(attribute = "s3.usermetadata", description = "A human-readable form of the User Metadata of " +
            "the S3 object, if any was set"),
    @WritesAttribute(attribute = "s3.encryptionStrategy", description = "The name of the encryption strategy, if any was set"), })
public class PutS3Object extends AbstractS3Processor {

    public static final long MIN_S3_PART_SIZE = 50L * 1024L * 1024L;
    public static final long MAX_S3_PUTOBJECT_SIZE = 5L * 1024L * 1024L * 1024L;
    public static final String CONTENT_DISPOSITION_INLINE = "inline";
    public static final String CONTENT_DISPOSITION_ATTACHMENT = "attachment";

    private static final String OBSOLETE_EXPIRATION_RULE_ID = "Expiration Time Rule";
    private static final String OBSOLETE_SERVER_SIDE_ENCRYPTION_1 = "server-side-encryption";
    private static final String OBSOLETE_SERVER_SIDE_ENCRYPTION_2 = "Server Side Encryption";
    private static final String OBSOLETE_SERVER_SIDE_ENCRYPTION_AES256 = "AES256";

    private static final Set<String> STORAGE_CLASSES = Collections.unmodifiableSortedSet(new TreeSet<>(
            Arrays.stream(StorageClass.values()).map(StorageClass::name).collect(Collectors.toSet())
    ));

    public static final PropertyDescriptor CONTENT_TYPE = new PropertyDescriptor.Builder()
            .name("Content Type")
            .description("Sets the Content-Type HTTP header indicating the type of content stored in the associated " +
                    "object. The value of this header is a standard MIME type.\n" +
                    "AWS S3 Java client will attempt to determine the correct content type if one hasn't been set" +
                    " yet. Users are responsible for ensuring a suitable content type is set when uploading streams. If " +
                    "no content type is provided and cannot be determined by the filename, the default content type " +
                    "\"application/octet-stream\" will be used.")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor CONTENT_DISPOSITION = new PropertyDescriptor.Builder()
            .name("Content Disposition")
            .description("Sets the Content-Disposition HTTP header indicating if the content is intended to be displayed inline or should be downloaded.\n " +
                    "Possible values are 'inline' or 'attachment'. If this property is not specified, object's content-disposition will be set to filename. " +
                    "When 'attachment' is selected, '; filename=' plus object key are automatically appended to form final value 'attachment; filename=\"filename.jpg\"'.")
            .required(false)
            .allowableValues(CONTENT_DISPOSITION_INLINE, CONTENT_DISPOSITION_ATTACHMENT)
            .build();

    public static final PropertyDescriptor CACHE_CONTROL = new PropertyDescriptor.Builder()
            .name("Cache Control")
            .description("Sets the Cache-Control HTTP header indicating the caching directives of the associated object. Multiple directives are comma-separated.")
            .required(false)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor STORAGE_CLASS = new PropertyDescriptor.Builder()
            .name("Storage Class")
            .required(true)
            .allowableValues(STORAGE_CLASSES)
            .defaultValue(StorageClass.STANDARD.name())
            .build();

    public static final PropertyDescriptor MULTIPART_THRESHOLD = new PropertyDescriptor.Builder()
            .name("Multipart Threshold")
            .description("Specifies the file size threshold for switch from the PutS3Object API to the " +
                    "PutS3MultipartUpload API.  Flow files bigger than this limit will be sent using the stateful " +
                "multipart process. The valid range is 50MB to 5GB.")
            .required(true)
            .defaultValue("5 GB")
            .addValidator(StandardValidators.createDataSizeBoundsValidator(MIN_S3_PART_SIZE, MAX_S3_PUTOBJECT_SIZE))
            .build();

    public static final PropertyDescriptor MULTIPART_PART_SIZE = new PropertyDescriptor.Builder()
            .name("Multipart Part Size")
        .description("Specifies the part size for use when the PutS3Multipart Upload API is used. " +
                    "Flow files will be broken into chunks of this size for the upload process, but the last part " +
            "sent can be smaller since it is not padded. The valid range is 50MB to 5GB.")
            .required(true)
            .defaultValue("5 GB")
            .addValidator(StandardValidators.createDataSizeBoundsValidator(MIN_S3_PART_SIZE, MAX_S3_PUTOBJECT_SIZE))
            .build();

    public static final PropertyDescriptor MULTIPART_UPLOAD_MAX_CONCURRENCY = new PropertyDescriptor.Builder()
            .name("Multipart Upload Max Concurrency")
            .description("Maximum number of concurrent HTTP requests used by the S3 Transfer Manager when performing multipart uploads.")
            .required(true)
            .defaultValue("8")
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();

    public static final PropertyDescriptor MULTIPART_S3_AGEOFF_INTERVAL = new PropertyDescriptor.Builder()
            .name("Multipart Upload AgeOff Interval")
            .description("Specifies the interval at which existing multipart uploads in AWS S3 will be evaluated " +
                    "for ageoff.  When processor is triggered it will initiate the ageoff evaluation if this interval has been " +
                    "exceeded.")
            .required(true)
            .defaultValue("60 min")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();

    public static final PropertyDescriptor MULTIPART_S3_MAX_AGE = new PropertyDescriptor.Builder()
            .name("Multipart Upload Max Age Threshold")
            .description("Specifies the maximum age for existing multipart uploads in AWS S3.  When the ageoff " +
                    "process occurs, any upload older than this threshold will be aborted.")
            .required(true)
            .defaultValue("7 days")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();

    public static final PropertyDescriptor OBJECT_TAGS_PREFIX = new PropertyDescriptor.Builder()
            .name("Object Tags Prefix")
            .description("Specifies the prefix which would be scanned against the incoming FlowFile's attributes and the matching attribute's " +
                    "name and value would be considered as the outgoing S3 object's Tag name and Tag value respectively. For Ex: If the " +
                    "incoming FlowFile carries the attributes tagS3country, tagS3PII, the tag prefix to be specified would be 'tagS3'")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_EL_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    public static final PropertyDescriptor REMOVE_TAG_PREFIX = new PropertyDescriptor.Builder()
            .name("Remove Tag Prefix")
            .description("If set to 'True', the value provided for '" + OBJECT_TAGS_PREFIX.getDisplayName() + "' will be removed from " +
                    "the attribute(s) and then considered as the Tag name. For ex: If the incoming FlowFile carries the attributes tagS3country, " +
                    "tagS3PII and the prefix is set to 'tagS3' then the corresponding tag values would be 'country' and 'PII'")
            .allowableValues("true", "false")
            .defaultValue("false")
            .build();

    public static final PropertyDescriptor MULTIPART_TEMP_DIR = new PropertyDescriptor.Builder()
            .name("Temporary Directory Multipart State")
            .description("Directory in which, for multipart uploads, the processor will locally save the state tracking the upload ID and parts "
                    + "uploaded which must both be provided to complete the upload.")
            .required(true)
            .addValidator(StandardValidators.FILE_EXISTS_VALIDATOR)
            .defaultValue("${java.io.tmpdir}")
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final List<PropertyDescriptor> PROPERTY_DESCRIPTORS = List.of(
            BUCKET_WITH_DEFAULT_VALUE,
            KEY,
            REGION,
            CUSTOM_REGION_WITH_FF_EL,
            AWS_CREDENTIALS_PROVIDER_SERVICE,
            RESOURCE_TRANSFER_SOURCE,
            FILE_RESOURCE_SERVICE,
            STORAGE_CLASS,
            ENCRYPTION_SERVICE,
            CONTENT_TYPE,
            CONTENT_DISPOSITION,
            CACHE_CONTROL,
            OBJECT_TAGS_PREFIX,
            REMOVE_TAG_PREFIX,
            TIMEOUT,
            FULL_CONTROL_USER_LIST,
            READ_USER_LIST,
            READ_ACL_LIST,
            WRITE_ACL_LIST,
            CANNED_ACL,
            SSL_CONTEXT_SERVICE,
            ENDPOINT_OVERRIDE,
            MULTIPART_THRESHOLD,
            MULTIPART_PART_SIZE,
            MULTIPART_UPLOAD_MAX_CONCURRENCY,
            MULTIPART_S3_AGEOFF_INTERVAL,
            MULTIPART_S3_MAX_AGE,
            MULTIPART_TEMP_DIR,
            USE_CHUNKED_ENCODING,
            USE_PATH_STYLE_ACCESS,
            PROXY_CONFIGURATION_SERVICE
    );

    final static String S3_BUCKET_KEY = "s3.bucket";
    final static String S3_OBJECT_KEY = "s3.key";
    final static String S3_CONTENT_TYPE = "s3.contenttype";
    final static String S3_CONTENT_DISPOSITION = "s3.contentdisposition";
    final static String S3_UPLOAD_ID_ATTR_KEY = "s3.uploadId";
    final static String S3_VERSION_ATTR_KEY = "s3.version";
    final static String S3_ETAG_ATTR_KEY = "s3.etag";
    final static String S3_CACHE_CONTROL = "s3.cachecontrol";
    final static String S3_EXPIRATION_TIME_ATTR_KEY = "s3.expirationTime";
    final static String S3_EXPIRATION_TIME_RULE_ID_ATTR_KEY = "s3.expirationTimeRuleId";
    final static String S3_STORAGECLASS_ATTR_KEY = "s3.storeClass";
    final static String S3_USERMETA_ATTR_KEY = "s3.usermetadata";
    final static String S3_API_METHOD_ATTR_KEY = "s3.apimethod";
    final static String S3_API_METHOD_PUTOBJECT = "putobject";
    final static String S3_API_METHOD_MULTIPARTUPLOAD = "multipartupload";

    final static String S3_PROCESS_UNSCHEDULED_MESSAGE = "Processor unscheduled, stopping upload";

    // maps AWS SDK v1 StorageClass to v2 StorageClass
    private static final Map<String, String> STORAGE_CLASS_MAPPING = Map.of(
            "Standard", StorageClass.STANDARD.name(),
            "ReducedRedundancy", StorageClass.REDUCED_REDUNDANCY.name(),
            "Glacier", StorageClass.GLACIER.name(),
            "StandardInfrequentAccess", StorageClass.STANDARD_IA.name(),
            "OneZoneInfrequentAccess", StorageClass.ONEZONE_IA.name(),
            "IntelligentTiering", StorageClass.INTELLIGENT_TIERING.name(),
            "DeepArchive", StorageClass.DEEP_ARCHIVE.name(),
            "Outposts", StorageClass.OUTPOSTS.name(),
            "GlacierInstantRetrieval", StorageClass.GLACIER_IR.name(),
            "Snow", StorageClass.SNOW.name()
    );

    private volatile String tempDirMultipart = System.getProperty("java.io.tmpdir");

    private static final String TRANSFER_MANAGER_STATE_SUFFIX = ".tm";
    private static final String TRANSFER_MANAGER_FILE_SUFFIX = ".upload";

    private final Map<TransferManagerKey, TransferResources> transferResources = new ConcurrentHashMap<>();

    @OnScheduled
    public void setTempDir(final ProcessContext context) {
        this.tempDirMultipart = context.getProperty(MULTIPART_TEMP_DIR).evaluateAttributeExpressions().getValue();
        resetTransferResources();
    }

    @OnStopped
    public void closeTransferResources() {
        resetTransferResources();
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTY_DESCRIPTORS;
    }

    @Override
    protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(final String propertyDescriptorName) {
        return new PropertyDescriptor.Builder()
                .name(propertyDescriptorName)
                .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
                .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
                .dynamic(true)
                .build();
    }

    @Override
    public void migrateProperties(PropertyConfiguration config) {
        super.migrateProperties(config);

        config.renameProperty("s3-object-tags-prefix", OBJECT_TAGS_PREFIX.getName());
        config.renameProperty("s3-object-remove-tags-prefix", REMOVE_TAG_PREFIX.getName());
        config.renameProperty("s3-temporary-directory-multipart", MULTIPART_TEMP_DIR.getName());

        migrateStorageClass(config);

        migrateServerSideEncryption(config);

        config.removeProperty(OBSOLETE_WRITE_USER_LIST);
        config.removeProperty(OBSOLETE_OWNER);
        config.removeProperty(OBSOLETE_EXPIRATION_RULE_ID);
    }

    private void migrateStorageClass(final PropertyConfiguration config) {
        config.getPropertyValue(STORAGE_CLASS).ifPresent(existingStorageClass ->
                Optional.ofNullable(STORAGE_CLASS_MAPPING.get(existingStorageClass)).ifPresent(mappedStorageClass ->
                        config.setProperty(STORAGE_CLASS, mappedStorageClass)
                )
        );
    }

    private void migrateServerSideEncryption(PropertyConfiguration config) {
        final String propertyName;
        if (config.hasProperty(OBSOLETE_SERVER_SIDE_ENCRYPTION_1)) {
            propertyName = OBSOLETE_SERVER_SIDE_ENCRYPTION_1;
        } else if (config.hasProperty(OBSOLETE_SERVER_SIDE_ENCRYPTION_2)) {
            propertyName = OBSOLETE_SERVER_SIDE_ENCRYPTION_2;
        } else {
            propertyName = null;
        }

        if (propertyName != null) {
            config.getPropertyValue(propertyName)
                    .filter(serverSideEncryption -> serverSideEncryption.equals(OBSOLETE_SERVER_SIDE_ENCRYPTION_AES256))
                    .ifPresent(serverSideEncryption -> {
                        final String serviceId = config.createControllerService(StandardS3EncryptionService.class.getName(), Map.of(
                                StandardS3EncryptionService.ENCRYPTION_STRATEGY.getName(), AmazonS3EncryptionService.STRATEGY_NAME_SSE_S3
                        ));
                        config.setProperty(ENCRYPTION_SERVICE, serviceId);
                    });

            config.removeProperty(propertyName);
        }
    }

    private synchronized void resetTransferResources() {
        transferResources.values().forEach(resources -> {
            try {
                resources.close();
            } catch (IOException e) {
                getLogger().warn("Failed to close S3 Transfer Manager resources", e);
            }
        });
        transferResources.clear();
    }

    protected File getPersistenceFile() {
        return new File(this.tempDirMultipart + File.separator + getIdentifier());
    }

    private InputStream openInputStream(final Optional<FileResource> optFileResource, final ProcessSession session, final FlowFile flowFile) {
        return optFileResource.map(FileResource::getInputStream).orElseGet(() -> session.read(flowFile));
    }

    private PutObjectRequest.Builder createPutObjectRequestBuilder(
            final String bucket,
            final String key,
            final String contentType,
            final String cacheControl,
            final String contentDisposition,
            final Map<String, String> userMetadata,
            final StorageClass storageClass,
            final String grantFullControl,
            final String grantRead,
            final String grantReadACP,
            final String grantWriteACP,
            final ObjectCannedACL cannedAcl,
            final List<Tag> objectTags) {
        final PutObjectRequest.Builder builder = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .metadata(userMetadata)
                .storageClass(storageClass);

        if (contentType != null) {
            builder.contentType(contentType);
        }
        if (cacheControl != null) {
            builder.cacheControl(cacheControl);
        }
        if (contentDisposition != null) {
            builder.contentDisposition(contentDisposition);
        }
        if (grantFullControl != null) {
            builder.grantFullControl(grantFullControl);
        }
        if (grantRead != null) {
            builder.grantRead(grantRead);
        }
        if (grantReadACP != null) {
            builder.grantReadACP(grantReadACP);
        }
        if (grantWriteACP != null) {
            builder.grantWriteACP(grantWriteACP);
        }
        if (cannedAcl != null) {
            builder.acl(cannedAcl);
        }
        if (!objectTags.isEmpty()) {
            builder.tagging(Tagging.builder()
                    .tagSet(objectTags)
                    .build());
        }

        return builder;
    }

    protected boolean localUploadExistsInS3(final S3Client client, final String bucket, final MultipartState localState) {
        final ListMultipartUploadsRequest listRequest = ListMultipartUploadsRequest.builder()
                .bucket(bucket)
                .build();
        ListMultipartUploadsResponse listResponse = client.listMultipartUploads(listRequest);

        for (MultipartUpload upload : listResponse.uploads()) {
            if (upload.uploadId().equals(localState.getUploadId())) {
                return true;
            }
        }
        return false;
    }

    protected synchronized MultipartState getLocalStateIfInS3(final S3Client client, final String bucket,
            final String s3ObjectKey) throws IOException {
        MultipartState currState = getLocalState(s3ObjectKey);
        if (currState == null) {
            return null;
        }
        if (localUploadExistsInS3(client, bucket, currState)) {
            getLogger().info("Local state for {} loaded with uploadId {} and {} partETags", s3ObjectKey, currState.getUploadId(), currState.getCompletedParts().size());
            return currState;
        } else {
            getLogger().info("Local state for {} with uploadId {} does not exist in S3, deleting local state", s3ObjectKey, currState.getUploadId());
            persistLocalState(s3ObjectKey, null);
            return null;
        }
    }

    protected synchronized MultipartState getLocalState(final String s3ObjectKey) throws IOException {
        // get local state if it exists
        final File persistenceFile = getPersistenceFile();

        if (persistenceFile.exists()) {
            final Properties props = new Properties();
            try (final FileInputStream fis = new FileInputStream(persistenceFile)) {
                props.load(fis);
            } catch (IOException ioe) {
                getLogger().warn("Assuming no local state and restarting upload since failed to recover local state for {}", s3ObjectKey, ioe);
                return null;
            }
            if (props.containsKey(s3ObjectKey)) {
                final String localSerialState = props.getProperty(s3ObjectKey);
                if (localSerialState != null) {
                    try {
                        return new MultipartState(localSerialState);
                    } catch (final RuntimeException rte) {
                        getLogger().warn("Failed to recover local state for {} due to corrupt data in state.", s3ObjectKey, rte);
                        return null;
                    }
                }
            }
        }
        return null;
    }

    protected synchronized void persistLocalState(final String s3ObjectKey, final MultipartState currState) throws IOException {
        final String currStateStr = (currState == null) ? null : currState.toString();
        final File persistenceFile = getPersistenceFile();
        final File parentDir = persistenceFile.getParentFile();
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("Persistence directory (" + parentDir.getAbsolutePath() + ") does not exist and " +
                    "could not be created.");
        }
        final Properties props = new Properties();
        if (persistenceFile.exists()) {
            try (final FileInputStream fis = new FileInputStream(persistenceFile)) {
                props.load(fis);
            }
        }
        if (currStateStr != null) {
            currState.setTimestamp(System.currentTimeMillis());
            props.setProperty(s3ObjectKey, currStateStr);
        } else {
            props.remove(s3ObjectKey);
        }

        if (!props.isEmpty()) {
            try (final FileOutputStream fos = new FileOutputStream(persistenceFile)) {
                props.store(fos, null);
            } catch (IOException ioe) {
                getLogger().error("Could not store state {}", persistenceFile.getAbsolutePath(), ioe);
            }
        } else {
            if (persistenceFile.exists()) {
                try {
                    Files.delete(persistenceFile.toPath());
                } catch (IOException ioe) {
                    getLogger().error("Could not remove state file {}", persistenceFile.getAbsolutePath(), ioe);
                }
            }
        }
    }

    protected synchronized void removeLocalState(final String s3ObjectKey) throws IOException {
        persistLocalState(s3ObjectKey, null);
    }

    private Optional<ResumableUploadState> loadResumableUploadState(final String s3ObjectKey) throws IOException {
        final File persistenceFile = getPersistenceFile();
        if (!persistenceFile.exists()) {
            return Optional.empty();
        }

        final Properties props = new Properties();
        try (final FileInputStream fis = new FileInputStream(persistenceFile)) {
            props.load(fis);
        }

        final String stored = props.getProperty(s3ObjectKey + TRANSFER_MANAGER_STATE_SUFFIX);
        if (StringUtils.isBlank(stored)) {
            return Optional.empty();
        }

        try {
            return Optional.of(parseResumableUploadState(stored));
        } catch (RuntimeException e) {
            getLogger().warn("Failed to deserialize transfer manager state for {}", s3ObjectKey, e);
            return Optional.empty();
        }
    }

    private ResumableUploadState parseResumableUploadState(final String stored) {
        long timestamp = System.currentTimeMillis();
        String serialized = stored;
        final int separatorIndex = stored.indexOf(':');
        if (separatorIndex > 0) {
            try {
                timestamp = Long.parseLong(stored.substring(0, separatorIndex));
                serialized = stored.substring(separatorIndex + 1);
            } catch (NumberFormatException ignored) {
                serialized = stored.substring(separatorIndex + 1);
            }
        }

        final ResumableFileUpload resumableUpload = ResumableFileUpload.fromString(serialized);
        return new ResumableUploadState(resumableUpload, timestamp);
    }

    private synchronized void persistResumableUploadState(final String s3ObjectKey, final ResumableFileUpload resumableUpload) throws IOException {
        final File persistenceFile = getPersistenceFile();
        final File parentDir = persistenceFile.getParentFile();
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("Persistence directory (" + parentDir.getAbsolutePath() + ") does not exist and could not be created.");
        }

        final Properties props = new Properties();
        if (persistenceFile.exists()) {
            try (final FileInputStream fis = new FileInputStream(persistenceFile)) {
                props.load(fis);
            }
        }

        props.setProperty(s3ObjectKey + TRANSFER_MANAGER_STATE_SUFFIX, System.currentTimeMillis() + ":" + resumableUpload.serializeToString());

        try (final FileOutputStream fos = new FileOutputStream(persistenceFile)) {
            props.store(fos, null);
        }
    }

    private synchronized void removeResumableUploadState(final String s3ObjectKey) throws IOException {
        final File persistenceFile = getPersistenceFile();
        if (!persistenceFile.exists()) {
            return;
        }

        final Properties props = new Properties();
        try (final FileInputStream fis = new FileInputStream(persistenceFile)) {
            props.load(fis);
        }

        props.remove(s3ObjectKey + TRANSFER_MANAGER_STATE_SUFFIX);

        if (props.isEmpty()) {
            Files.deleteIfExists(persistenceFile.toPath());
        } else {
            try (final FileOutputStream fos = new FileOutputStream(persistenceFile)) {
                props.store(fos, null);
            }
        }
    }

    private Path resolveStagingFile(final String cacheKey, final FlowFile flowFile) throws IOException {
        final String flowFileIdentifier = Optional.ofNullable(flowFile.getAttribute(CoreAttributes.UUID.key()))
                .orElseGet(() -> String.valueOf(flowFile.getId()));
        final String fileName = hashIdentifier(cacheKey + ':' + flowFileIdentifier) + TRANSFER_MANAGER_FILE_SUFFIX;
        final Path directory = Path.of(tempDirMultipart, "transfer-manager");
        Files.createDirectories(directory);
        return directory.resolve(fileName);
    }

    private void exportContentToFile(final Optional<FileResource> optFileResource, final ProcessSession session, final FlowFile flowFile,
                                     final Path stagingFile, final long expectedSize) throws IOException {
        Files.createDirectories(stagingFile.getParent());
        Files.deleteIfExists(stagingFile);

        if (optFileResource.isPresent()) {
            try (InputStream in = optFileResource.get().getInputStream();
                 OutputStream out = Files.newOutputStream(stagingFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                in.transferTo(out);
            }
        } else {
            try (OutputStream out = Files.newOutputStream(stagingFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                session.exportTo(flowFile, out);
            }
        }

        final long actualSize = Files.size(stagingFile);
        if (actualSize != expectedSize) {
            throw new ProcessException("Exported staging file size mismatch: expected " + expectedSize + " bytes but was " + actualSize);
        }
    }

    private void deleteStagingFile(final Path stagingFile) {
        try {
            Files.deleteIfExists(stagingFile);
        } catch (IOException e) {
            getLogger().warn("Failed to delete staging file {}", stagingFile, e);
        }
    }

    private CompletedFileUpload waitForUploadCompletion(final String cacheKey, final FileUpload upload, final FlowFile flowFile)
            throws IOException, SdkException {
        final CompletableFuture<CompletedFileUpload> future = upload.completionFuture();
        while (true) {
            try {
                return future.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                if (!isScheduled()) {
                    final ResumableFileUpload resumableUpload = upload.pause();
                    persistResumableUploadState(cacheKey, resumableUpload);
                    future.cancel(true);
                    throw new IOException(S3_PROCESS_UNSCHEDULED_MESSAGE + " flowfile=" + flowFile.getAttribute(CoreAttributes.FILENAME.key())
                            + " uploadId=" + resumableUpload.multipartUploadId().orElse("unknown"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                final ResumableFileUpload resumableUpload = upload.pause();
                persistResumableUploadState(cacheKey, resumableUpload);
                future.cancel(true);
                throw new IOException("Upload interrupted for flowfile=" + flowFile.getAttribute(CoreAttributes.FILENAME.key()), e);
            } catch (ExecutionException e) {
                future.cancel(true);
                final Throwable cause = e.getCause();
                if (cause instanceof IOException ioe) {
                    throw ioe;
                } else if (cause instanceof SdkException sdk) {
                    throw sdk;
                } else if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                } else {
                    throw new ProcessException("Failed to upload to S3 using transfer manager", cause);
                }
            }
        }
    }

    private TransferResources createTransferResources(final ProcessContext context, final TransferManagerKey key) {
        final NettyNioAsyncHttpClient.Builder nettyHttpClientBuilder = NettyNioAsyncHttpClient.builder();
        final AtomicReference<Duration> communicationsTimeoutRef = new AtomicReference<>();
        final AtomicReference<ProxyConfiguration> proxyConfigurationRef = new AtomicReference<>(ProxyConfiguration.DIRECT_CONFIGURATION);
        final AtomicBoolean customTlsConfigured = new AtomicBoolean(false);

        final AwsHttpClientConfigurer httpClientConfigurer = new AwsHttpClientConfigurer() {
            @Override
            public void configureBasicSettings(final Duration communicationsTimeout, final int maxConcurrentTasks) {
                communicationsTimeoutRef.set(communicationsTimeout);
                nettyHttpClientBuilder.connectionTimeout(communicationsTimeout);
                nettyHttpClientBuilder.readTimeout(communicationsTimeout);
                nettyHttpClientBuilder.writeTimeout(communicationsTimeout);
                nettyHttpClientBuilder.connectionAcquisitionTimeout(communicationsTimeout);
                nettyHttpClientBuilder.maxConcurrency(key.maxConcurrency());
                nettyHttpClientBuilder.maxPendingConnectionAcquires(Math.max(key.maxConcurrency(), 1) * 2);
            }

            @Override
            public void configureTls(final TlsTrustManagersProvider trustManagersProvider, final TlsKeyManagersProvider keyManagersProvider) {
                customTlsConfigured.set(true);
                nettyHttpClientBuilder.tlsTrustManagersProvider(trustManagersProvider);
                if (keyManagersProvider != null) {
                    nettyHttpClientBuilder.tlsKeyManagersProvider(keyManagersProvider);
                }
            }

            @Override
            public void configureProxy(final ProxyConfiguration proxyConfiguration) {
                proxyConfigurationRef.set(proxyConfiguration);
                final software.amazon.awssdk.http.nio.netty.ProxyConfiguration.Builder proxyBuilder = software.amazon.awssdk.http.nio.netty.ProxyConfiguration.builder()
                        .host(proxyConfiguration.getProxyServerHost())
                        .port(proxyConfiguration.getProxyServerPort());
                if (proxyConfiguration.hasCredential()) {
                    proxyBuilder.username(proxyConfiguration.getProxyUserName());
                    proxyBuilder.password(proxyConfiguration.getProxyUserPassword());
                }
                nettyHttpClientBuilder.proxyConfiguration(proxyBuilder.build());
            }
        };

        configureSdkHttpClient(context, httpClientConfigurer);

        final S3Configuration serviceConfiguration = buildS3Configuration(context);
        S3AsyncClient asyncClient = null;

        if (isCrtAvailable() && !customTlsConfigured.get()) {
            try {
                asyncClient = buildCrtAsyncClient(context, key, communicationsTimeoutRef.get(), proxyConfigurationRef.get(), serviceConfiguration);
            } catch (RuntimeException | LinkageError e) {
                getLogger().warn("Failed to initialize AWS CRT S3 client; falling back to Netty implementation", e);
            }
        } else if (customTlsConfigured.get() && isCrtAvailable()) {
            getLogger().debug("Custom TLS context detected; using Netty S3 client for multipart uploads");
        }

        if (asyncClient == null) {
            asyncClient = buildNettyAsyncClient(context, key, nettyHttpClientBuilder, serviceConfiguration);
        }

        final S3TransferManager transferManager = S3TransferManager.builder()
                .s3Client(asyncClient)
                .build();

        return new TransferResources(asyncClient, transferManager);
    }

    private S3AsyncClient buildNettyAsyncClient(final ProcessContext context, final TransferManagerKey key,
                                                final NettyNioAsyncHttpClient.Builder httpClientBuilder,
                                                final S3Configuration serviceConfiguration) {
        final S3AsyncClientBuilder asyncClientBuilder = S3AsyncClient.builder()
                .httpClientBuilder(httpClientBuilder)
                .serviceConfiguration(serviceConfiguration)
                .multipartEnabled(true)
                .multipartConfiguration(MultipartConfiguration.builder()
                        .minimumPartSizeInBytes(key.minimumPartSize())
                        .thresholdInBytes(key.multipartThreshold())
                        .build());

        asyncClientBuilder.overrideConfiguration(cfg -> cfg.putAdvancedOption(SdkAdvancedClientOption.USER_AGENT_PREFIX, DEFAULT_USER_AGENT));
        asyncClientBuilder.overrideConfiguration(cfg -> cfg.retryStrategy(DefaultRetryStrategy.doNotRetry()));

        if (key.region() != null) {
            asyncClientBuilder.region(key.region());
        }

        configureEndpoint(context, asyncClientBuilder, ENDPOINT_OVERRIDE);
        asyncClientBuilder.credentialsProvider(getCredentialsProvider(context));

        return asyncClientBuilder.build();
    }

    private S3AsyncClient buildCrtAsyncClient(final ProcessContext context, final TransferManagerKey key,
                                              final Duration communicationsTimeout,
                                              final ProxyConfiguration proxyConfiguration,
                                              final S3Configuration serviceConfiguration) {
        final S3CrtAsyncClientBuilder asyncClientBuilder = S3AsyncClient.crtBuilder()
                .minimumPartSizeInBytes(key.minimumPartSize())
                .thresholdInBytes(key.multipartThreshold())
                .maxConcurrency(key.maxConcurrency())
                .targetThroughputInGbps(calculateTargetThroughputGbps(key))
                .forcePathStyle(serviceConfiguration.pathStyleAccessEnabled())
                .accelerate(serviceConfiguration.accelerateModeEnabled());

        asyncClientBuilder.credentialsProvider(getCredentialsProvider(context));

        if (key.region() != null) {
            asyncClientBuilder.region(key.region());
        }

        final String endpointOverride = context.getProperty(ENDPOINT_OVERRIDE)
                .evaluateAttributeExpressions()
                .getValue();
        if (endpointOverride != null && !endpointOverride.isBlank()) {
            getLogger().info("Overriding endpoint with {}", endpointOverride);
            asyncClientBuilder.endpointOverride(URI.create(endpointOverride));
        }

        if (communicationsTimeout != null || (proxyConfiguration != null && !Proxy.Type.DIRECT.equals(proxyConfiguration.getProxyType()))) {
            asyncClientBuilder.httpConfiguration(http -> {
                if (communicationsTimeout != null) {
                    http.connectionTimeout(communicationsTimeout);
                }
                if (proxyConfiguration != null && !Proxy.Type.DIRECT.equals(proxyConfiguration.getProxyType())) {
                    http.proxyConfiguration(proxy -> {
                        proxy.host(proxyConfiguration.getProxyServerHost());
                        proxy.port(proxyConfiguration.getProxyServerPort());
                        proxy.scheme("http");
                        if (proxyConfiguration.hasCredential()) {
                            proxy.username(proxyConfiguration.getProxyUserName());
                            proxy.password(proxyConfiguration.getProxyUserPassword());
                        }
                    });
                }
            });
        }

        return asyncClientBuilder.build();
    }

    private boolean isCrtAvailable() {
        try {
            final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            Class.forName("software.amazon.awssdk.services.s3.S3CrtAsyncClientBuilder", false, classLoader);
            Class.forName("software.amazon.awssdk.crt.CRT", false, classLoader);
            Class.forName("software.amazon.awssdk.crt.s3.S3MetaRequestResponseHandler", false, classLoader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private double calculateTargetThroughputGbps(final TransferManagerKey key) {
        final double baseline = Math.max(1d, (double) key.minimumPartSize() / (64d * 1024d * 1024d));
        final double estimated = key.maxConcurrency() * baseline;
        return Math.min(200d, Math.max(1d, estimated));
    }

    private void performTransferManagerUpload(
            final ProcessContext context,
            final ProcessSession session,
            final FlowFile flowFile,
            final Map<String, String> attributes,
            final String bucket,
            final String key,
            final String cacheKey,
            final String contentType,
            final String cacheControl,
            final String contentDisposition,
            final Map<String, String> userMetadata,
            final StorageClass storageClass,
            final String grantFullControl,
            final String grantRead,
            final String grantReadACP,
            final String grantWriteACP,
            final ObjectCannedACL cannedAcl,
            final List<Tag> objectTags,
            final long contentLength,
            final long multipartThreshold,
            final long multipartPartSize,
            final Optional<FileResource> optFileResource) throws IOException {

        final int maxConcurrency = context.getProperty(MULTIPART_UPLOAD_MAX_CONCURRENCY).asInteger();
        final Region region = RegionUtil.getRegion(context, flowFile.getAttributes());
        final TransferManagerKey managerKey = new TransferManagerKey(region, maxConcurrency, multipartPartSize, multipartThreshold);
        final TransferResources resources = transferResources.computeIfAbsent(managerKey, keyObj -> createTransferResources(context, keyObj));

        Optional<ResumableUploadState> resumableState = loadResumableUploadState(cacheKey);
        Path stagingFile = resolveStagingFile(cacheKey, flowFile);

        if (resumableState.isPresent() && Files.notExists(stagingFile)) {
            getLogger().info("Resumable multipart state found for {} but staging file {} does not exist; restarting upload", cacheKey, stagingFile);
            removeResumableUploadState(cacheKey);
            resumableState = Optional.empty();
        }

        if (resumableState.isPresent()) {
            try {
                final long existingSize = Files.size(stagingFile);
                if (existingSize != contentLength) {
                    getLogger().warn("Staging file {} size {} does not match FlowFile size {} for {}; restarting upload", stagingFile, existingSize, contentLength, cacheKey);
                    removeResumableUploadState(cacheKey);
                    resumableState = Optional.empty();
                }
            } catch (IOException e) {
                getLogger().warn("Failed to inspect staging file {} for {}; restarting upload", stagingFile, cacheKey, e);
                removeResumableUploadState(cacheKey);
                resumableState = Optional.empty();
            }
        }

        if (resumableState.isEmpty()) {
            exportContentToFile(optFileResource, session, flowFile, stagingFile, contentLength);
        }

        final PutObjectRequest.Builder requestBuilder = createPutObjectRequestBuilder(
                bucket,
                key,
                contentType,
                cacheControl,
                contentDisposition,
                userMetadata,
                storageClass,
                grantFullControl,
                grantRead,
                grantReadACP,
                grantWriteACP,
                cannedAcl,
                objectTags);

        FileUpload fileUpload;
        if (resumableState.isPresent()) {
            fileUpload = resources.transferManager().resumeUploadFile(resumableState.get().getResumableUpload());
        } else {
            final UploadFileRequest uploadFileRequest = UploadFileRequest.builder()
                    .source(stagingFile)
                    .putObjectRequest(requestBuilder.build())
                    .build();

            fileUpload = resources.transferManager().uploadFile(uploadFileRequest);
        }

        CompletedFileUpload completedUpload = null;
        boolean unscheduled = false;
        try {
            completedUpload = waitForUploadCompletion(cacheKey, fileUpload, flowFile);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains(S3_PROCESS_UNSCHEDULED_MESSAGE)) {
                unscheduled = true;
                throw e;
            }
            removeResumableUploadState(cacheKey);
            deleteStagingFile(stagingFile);
            throw e;
        } catch (RuntimeException e) {
            removeResumableUploadState(cacheKey);
            deleteStagingFile(stagingFile);
            throw e;
        }

        if (!unscheduled && completedUpload != null) {
            removeResumableUploadState(cacheKey);
            deleteStagingFile(stagingFile);

            final PutObjectResponse response = completedUpload.response();
            if (response.versionId() != null) {
                attributes.put(S3_VERSION_ATTR_KEY, response.versionId());
            }
            if (response.eTag() != null) {
                attributes.put(S3_ETAG_ATTR_KEY, sanitizeETag(response.eTag()));
            }
            final Expiration expiration = parseExpirationHeader(response.expiration());
            if (expiration != null) {
                attributes.put(S3_EXPIRATION_TIME_ATTR_KEY, String.valueOf(expiration.expirationTime().toEpochMilli()));
                attributes.put(S3_EXPIRATION_TIME_RULE_ID_ATTR_KEY, expiration.expirationTimeRuleId());
            }
            setEncryptionAttributes(attributes, response.serverSideEncryption(), response.sseCustomerAlgorithm(), null);
            attributes.put(S3_API_METHOD_ATTR_KEY, S3_API_METHOD_MULTIPARTUPLOAD);
        }
    }


    private void uploadSinglePart(
            final S3Client client,
            final AmazonS3EncryptionService encryptionService,
            final InputStream in,
            final long contentLength,
            final Map<String, String> attributes,
            final String ffFilename,
            final String bucket,
            final String key,
            final String contentType,
            final String cacheControl,
            final String contentDisposition,
            final Map<String, String> userMetadata,
            final StorageClass storageClass,
            final String grantFullControl,
            final String grantRead,
            final String grantReadACP,
            final String grantWriteACP,
            final ObjectCannedACL cannedAcl,
            final List<Tag> objectTags) throws IOException {
        final PutObjectRequest.Builder requestBuilder = createPutObjectRequestBuilder(
                bucket,
                key,
                contentType,
                cacheControl,
                contentDisposition,
                userMetadata,
                storageClass,
                grantFullControl,
                grantRead,
                grantReadACP,
                grantWriteACP,
                cannedAcl,
                objectTags);
        requestBuilder.contentLength(contentLength);

        if (encryptionService != null) {
            encryptionService.configurePutObjectRequest(requestBuilder);
        }

        final RequestBody requestBody = RequestBody.fromInputStream(in, contentLength);

        try {
            final PutObjectResponse response = client.putObject(requestBuilder.build(), requestBody);
            if (response.versionId() != null) {
                attributes.put(S3_VERSION_ATTR_KEY, response.versionId());
            }
            if (response.eTag() != null) {
                attributes.put(S3_ETAG_ATTR_KEY, sanitizeETag(response.eTag()));
            }
            final Expiration expiration = parseExpirationHeader(response.expiration());
            if (expiration != null) {
                attributes.put(S3_EXPIRATION_TIME_ATTR_KEY, String.valueOf(expiration.expirationTime().toEpochMilli()));
                attributes.put(S3_EXPIRATION_TIME_RULE_ID_ATTR_KEY, expiration.expirationTimeRuleId());
            }
            setEncryptionAttributes(attributes, response.serverSideEncryption(), response.sseCustomerAlgorithm(), encryptionService);
            attributes.put(S3_API_METHOD_ATTR_KEY, S3_API_METHOD_PUTOBJECT);
        } catch (SdkException e) {
            getLogger().info("Failure completing upload flowfile={} bucket={} key={} reason={}",
                    ffFilename, bucket, key, e.getMessage());
            throw e;
        }
    }

    private void performLegacyMultipartUpload(
            final ProcessContext context,
            final S3Client client,
            final AmazonS3EncryptionService encryptionService,
            final InputStream in,
            final long contentLength,
            final long multipartPartSize,
            final Map<String, String> attributes,
            final String bucket,
            final String key,
            final String cacheKey,
            final String contentType,
            final String cacheControl,
            final String contentDisposition,
            final Map<String, String> userMetadata,
            final StorageClass storageClass,
            final String grantFullControl,
            final String grantRead,
            final String grantReadACP,
            final String grantWriteACP,
            final ObjectCannedACL cannedAcl,
            final List<Tag> objectTags,
            final String ffFilename) throws IOException {
        MultipartState currentState;
        try {
            currentState = getLocalStateIfInS3(client, bucket, cacheKey);
            if (currentState != null) {
                if (!currentState.getCompletedParts().isEmpty()) {
                    final CompletedPart lastCompletedPart = currentState.getCompletedParts().getLast();
                    getLogger().info("Resuming upload for flowfile='{}' bucket='{}' key='{}' "
                                    + "uploadID='{}' filePosition='{}' partSize='{}' storageClass='{}' "
                                    + "contentLength='{}' partsLoaded={} lastPart={}/{}",
                            ffFilename, bucket, key, currentState.getUploadId(),
                            currentState.getFilePosition(), currentState.getPartSize(),
                            currentState.getStorageClass(), currentState.getContentLength(),
                            currentState.getCompletedParts().size(),
                            Integer.toString(lastCompletedPart.partNumber()),
                            lastCompletedPart.eTag());
                } else {
                    getLogger().info("Resuming upload for flowfile='{}' bucket='{}' key='{}' "
                                    + "uploadID='{}' filePosition='{}' partSize='{}' storageClass='{}' "
                                    + "contentLength='{}' no partsLoaded",
                            ffFilename, bucket, key, currentState.getUploadId(),
                            currentState.getFilePosition(), currentState.getPartSize(),
                            currentState.getStorageClass(), currentState.getContentLength());
                }
            } else {
                currentState = new MultipartState();
                currentState.setPartSize(multipartPartSize);
                currentState.setStorageClass(storageClass);
                currentState.setContentLength(contentLength);
                persistLocalState(cacheKey, currentState);
                getLogger().info("Starting new upload for flowfile='{}' bucket='{}' key='{}'",
                        ffFilename, bucket, key);
            }
        } catch (IOException e) {
            getLogger().error("IOException initiating cache state while processing flow files", e);
            throw e;
        }

        if (currentState.getUploadId().isEmpty()) {
            final CreateMultipartUploadRequest.Builder createRequestBuilder = CreateMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .cacheControl(cacheControl)
                    .contentDisposition(contentDisposition)
                    .metadata(userMetadata)
                    .storageClass(storageClass)
                    .grantFullControl(grantFullControl)
                    .grantRead(grantRead)
                    .grantReadACP(grantReadACP)
                    .grantWriteACP(grantWriteACP)
                    .acl(cannedAcl);

            if (encryptionService != null) {
                encryptionService.configureCreateMultipartUploadRequest(createRequestBuilder);
            }

            if (!objectTags.isEmpty()) {
                createRequestBuilder.tagging(Tagging.builder()
                        .tagSet(objectTags)
                        .build());
            }

            try {
                final CreateMultipartUploadResponse createResponse = client.createMultipartUpload(createRequestBuilder.build());
                if (StringUtils.isBlank(createResponse.uploadId())) {
                    throw new ProcessException(String.format("UploadId is missing in CreateMultipartUploadResponse [%s]", createResponse));
                }
                currentState.setUploadId(createResponse.uploadId());
                currentState.getCompletedParts().clear();
                try {
                    persistLocalState(cacheKey, currentState);
                } catch (Exception e) {
                    getLogger().info("Exception saving cache state while processing flow file", e);
                    throw new ProcessException("Exception saving cache state", e);
                }
                getLogger().info("Success initiating upload flowfile={} available={} position={} length={} bucket={} key={} uploadId={}",
                        ffFilename, in.available(), currentState.getFilePosition(),
                        currentState.getContentLength(), bucket, key,
                        currentState.getUploadId());
                attributes.put(S3_UPLOAD_ID_ATTR_KEY, createResponse.uploadId());
                setEncryptionAttributes(attributes, createResponse.serverSideEncryption(), createResponse.sseCustomerAlgorithm(), encryptionService);
            } catch (SdkException e) {
                getLogger().info("Failure initiating upload flowfile={} bucket={} key={}", ffFilename, bucket, key, e);
                throw e;
            }
        } else if (currentState.getFilePosition() > 0) {
            try {
                final long skipped = in.skip(currentState.getFilePosition());
                if (skipped != currentState.getFilePosition()) {
                    getLogger().info("Failure skipping to resume upload flowfile={} bucket={} key={} position={} skipped={}",
                            ffFilename, bucket, key, currentState.getFilePosition(), skipped);
                }
            } catch (Exception e) {
                getLogger().info("Failure skipping to resume upload flowfile={} bucket={} key={} position={}",
                        ffFilename, bucket, key, currentState.getFilePosition(), e);
                throw new ProcessException(e);
            }
        }

        for (int part = currentState.getCompletedParts().size() + 1;
             currentState.getFilePosition() < currentState.getContentLength(); part++) {
            if (!isScheduled()) {
                throw new IOException(S3_PROCESS_UNSCHEDULED_MESSAGE + " flowfile=" + ffFilename +
                        " part=" + part + " uploadId=" + currentState.getUploadId());
            }
            final long thisPartSize = Math.min(currentState.getPartSize(),
                    currentState.getContentLength() - currentState.getFilePosition());
            final boolean isLastPart = currentState.getContentLength() == currentState.getFilePosition() + thisPartSize;
            final UploadPartRequest.Builder uploadRequestBuilder = UploadPartRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(currentState.getUploadId())
                    .partNumber(part)
                    .sdkPartType(isLastPart ? SdkPartType.LAST : SdkPartType.DEFAULT)
                    .contentLength(thisPartSize);

            if (encryptionService != null) {
                encryptionService.configureUploadPartRequest(uploadRequestBuilder);
            }

            final RequestBody requestBody = RequestBody.fromInputStream(in, thisPartSize);
            try {
                final UploadPartResponse uploadPartResponse = client.uploadPart(uploadRequestBuilder.build(), requestBody);
                currentState.addCompletedPart(CompletedPart.builder()
                        .partNumber(part)
                        .eTag(uploadPartResponse.eTag())
                        .build());
                currentState.setFilePosition(currentState.getFilePosition() + thisPartSize);
                try {
                    persistLocalState(cacheKey, currentState);
                } catch (Exception e) {
                    getLogger().info("Exception saving cache state processing flow file", e);
                }
                int available = 0;
                try {
                    available = in.available();
                } catch (IOException ignored) {
                    // Stream may already be exhausted on the last part
                }
                getLogger().info("Success uploading part flowfile={} part={} available={} etag={} uploadId={}",
                        ffFilename, part, available, uploadPartResponse.eTag(), currentState.getUploadId());
            } catch (SdkException e) {
                getLogger().info("Failure uploading part flowfile={} part={} bucket={} key={}",
                        ffFilename, part, bucket, key, e);
                throw e;
            }
        }

        final CompleteMultipartUploadRequest completeRequest = CompleteMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .uploadId(currentState.getUploadId())
                .multipartUpload(CompletedMultipartUpload.builder()
                        .parts(currentState.getCompletedParts())
                        .build())
                .build();

        try {
            final CompleteMultipartUploadResponse completeResponse = client.completeMultipartUpload(completeRequest);
            getLogger().info("Success completing upload flowfile={} etag={} uploadId={}",
                    ffFilename, completeResponse.eTag(), currentState.getUploadId());
            if (completeResponse.versionId() != null) {
                attributes.put(S3_VERSION_ATTR_KEY, completeResponse.versionId());
            }
            if (completeResponse.eTag() != null) {
                attributes.put(S3_ETAG_ATTR_KEY, sanitizeETag(completeResponse.eTag()));
            }
            final Expiration expiration = parseExpirationHeader(completeResponse.expiration());
            if (expiration != null) {
                attributes.put(S3_EXPIRATION_TIME_ATTR_KEY, String.valueOf(expiration.expirationTime().toEpochMilli()));
                attributes.put(S3_EXPIRATION_TIME_RULE_ID_ATTR_KEY, expiration.expirationTimeRuleId());
            }
            attributes.put(S3_API_METHOD_ATTR_KEY, S3_API_METHOD_MULTIPARTUPLOAD);
        } catch (SdkException e) {
            getLogger().info("Failure completing upload flowfile={} bucket={} key={}",
                    ffFilename, bucket, key, e);
            throw e;
        }
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

    private synchronized void ageoffLocalState(long ageCutoff) {
        // get local state if it exists
        final File persistenceFile = getPersistenceFile();
        if (persistenceFile.exists()) {
            Properties props = new Properties();
            try (final FileInputStream fis = new FileInputStream(persistenceFile)) {
                props.load(fis);
            } catch (final IOException ioe) {
                getLogger().warn("Failed to ageoff remove local state", ioe);
                return;
            }
            for (Entry<Object, Object> entry: props.entrySet()) {
                final String key = (String) entry.getKey();
                final String storedState = props.getProperty(key);
                if (storedState == null) {
                    continue;
                }

                if (key.endsWith(TRANSFER_MANAGER_STATE_SUFFIX)) {
                    final String baseKey = key.substring(0, key.length() - TRANSFER_MANAGER_STATE_SUFFIX.length());
                    try {
                        final ResumableUploadState resumableState = parseResumableUploadState(storedState);
                        if (resumableState.getTimestamp() < ageCutoff) {
                            getLogger().warn("Removing transfer manager state for {} due to exceeding ageoff time", baseKey);
                            removeResumableUploadState(baseKey);
                            deleteStagingFile(resumableState.getResumableUpload().uploadFileRequest().source());
                        }
                    } catch (RuntimeException | IOException e) {
                        getLogger().warn("Failed to remove transfer manager state for {}", baseKey, e);
                    }
                } else {
                    final MultipartState state = new MultipartState(storedState);
                    if (state.getTimestamp() < ageCutoff) {
                        getLogger().warn("Removing local state for {} due to exceeding ageoff time", key);
                        try {
                            removeLocalState(key);
                        } catch (final IOException ioe) {
                            getLogger().warn("Failed to remove local state for {}", key, ioe);

                        }
                    }
                }
            }
        }
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) {

        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        final S3Client client;

        try {
            client = getClient(context, flowFile.getAttributes());
        } catch (Exception e) {
            getLogger().error("Failed to initialize S3 client", e);
            flowFile = session.penalize(flowFile);
            session.transfer(flowFile, REL_FAILURE);
            return;
        }

        final long startNanos = System.nanoTime();

        final String bucket = context.getProperty(BUCKET_WITH_DEFAULT_VALUE).evaluateAttributeExpressions(flowFile).getValue();
        final String key = context.getProperty(KEY).evaluateAttributeExpressions(flowFile).getValue();
        final String cacheKey = getIdentifier() + "/" + bucket + "/" + key;

        final Map<String, String> attributes = new HashMap<>();
        final String ffFilename = flowFile.getAttribute(CoreAttributes.FILENAME.key());
        final ResourceTransferSource resourceTransferSource = context.getProperty(RESOURCE_TRANSFER_SOURCE).asAllowableValue(ResourceTransferSource.class);

        attributes.put(S3_BUCKET_KEY, bucket);
        attributes.put(S3_OBJECT_KEY, key);

        final long multipartThreshold = context.getProperty(MULTIPART_THRESHOLD).asDataSize(DataUnit.B).longValue();
        final long multipartPartSize = context.getProperty(MULTIPART_PART_SIZE).asDataSize(DataUnit.B).longValue();

        final long now = System.currentTimeMillis();
        ageoffS3Uploads(context, client, now, bucket);

        try {
            final Optional<FileResource> optFileResource = getFileResource(resourceTransferSource, context, flowFile.getAttributes());
            final long contentLength = optFileResource.map(FileResource::getSize).orElseGet(flowFile::getSize);

            final String contentType = context.getProperty(CONTENT_TYPE).evaluateAttributeExpressions(flowFile).getValue();
            if (contentType != null) {
                attributes.put(S3_CONTENT_TYPE, contentType);
            }

            final String cacheControl = context.getProperty(CACHE_CONTROL).evaluateAttributeExpressions(flowFile).getValue();
            if (cacheControl != null) {
                attributes.put(S3_CACHE_CONTROL, cacheControl);
            }

            final String fileName = URLEncoder.encode(ffFilename, StandardCharsets.UTF_8);
            final String dispositionProperty = context.getProperty(CONTENT_DISPOSITION).getValue();
            final String contentDisposition;
            if (CONTENT_DISPOSITION_INLINE.equals(dispositionProperty)) {
                contentDisposition = CONTENT_DISPOSITION_INLINE;
                attributes.put(S3_CONTENT_DISPOSITION, contentDisposition);
            } else if (CONTENT_DISPOSITION_ATTACHMENT.equals(dispositionProperty)) {
                contentDisposition = CONTENT_DISPOSITION_ATTACHMENT + "; filename=\"" + fileName + "\"";
                attributes.put(S3_CONTENT_DISPOSITION, contentDisposition);
            } else {
                contentDisposition = fileName;
            }

            final Map<String, String> userMetadata = new HashMap<>();
            for (Entry<PropertyDescriptor, String> entry : context.getProperties().entrySet()) {
                if (entry.getKey().isDynamic()) {
                    final String value = context.getProperty(entry.getKey()).evaluateAttributeExpressions(flowFile).getValue();
                    userMetadata.put(entry.getKey().getName(), value);
                }
            }

            final String userMetadataAttributeValue = userMetadata.entrySet().stream()
                    .map(entry -> String.format("%s=%s", entry.getKey(), entry.getValue()))
                    .collect(Collectors.joining("\n"));
            if (StringUtils.isNotBlank(userMetadataAttributeValue)) {
                attributes.put(S3_USERMETA_ATTR_KEY, userMetadataAttributeValue);
            }

            final AmazonS3EncryptionService encryptionService = context.getProperty(ENCRYPTION_SERVICE).asControllerService(AmazonS3EncryptionService.class);

            final StorageClass storageClass = StorageClass.valueOf(context.getProperty(STORAGE_CLASS).getValue());
            attributes.put(S3_STORAGECLASS_ATTR_KEY, storageClass.name());

            final ObjectCannedACL cannedAcl = createCannedACL(context, flowFile);
            final String grantFullControl = getFullControlGranteeSpec(context, flowFile);
            final String grantRead = getReadGranteeSpec(context, flowFile);
            final String grantReadACP = getReadACPGranteeSpec(context, flowFile);
            final String grantWriteACP = getWriteACPGranteeSpec(context, flowFile);
            final List<Tag> objectTags = context.getProperty(OBJECT_TAGS_PREFIX).isSet()
                    ? getObjectTags(context, flowFile)
                    : Collections.emptyList();

            final boolean transferManagerSupported = encryptionService == null;
            final boolean useTransferManager = transferManagerSupported && contentLength > multipartThreshold;

            if (contentLength <= multipartThreshold) {
                try (InputStream in = openInputStream(optFileResource, session, flowFile)) {
                    uploadSinglePart(client, encryptionService, in, contentLength, attributes, ffFilename, bucket, key,
                            contentType, cacheControl, contentDisposition, userMetadata, storageClass,
                            grantFullControl, grantRead, grantReadACP, grantWriteACP, cannedAcl, objectTags);
                }
            } else if (useTransferManager) {
                performTransferManagerUpload(context, session, flowFile, attributes, bucket, key, cacheKey,
                        contentType, cacheControl, contentDisposition, userMetadata, storageClass,
                        grantFullControl, grantRead, grantReadACP, grantWriteACP, cannedAcl, objectTags,
                        contentLength, multipartThreshold, multipartPartSize, optFileResource);
            } else {
                try (InputStream in = openInputStream(optFileResource, session, flowFile)) {
                    performLegacyMultipartUpload(context, client, encryptionService, in, contentLength, multipartPartSize,
                            attributes, bucket, key, cacheKey, contentType, cacheControl, contentDisposition,
                            userMetadata, storageClass, grantFullControl, grantRead, grantReadACP, grantWriteACP, cannedAcl,
                            objectTags, ffFilename);
                }
            }

            final String url = getResourceUrl(client, bucket, key);
            attributes.put("s3.url", url);
            flowFile = session.putAllAttributes(flowFile, attributes);
            session.transfer(flowFile, REL_SUCCESS);

            final long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            session.getProvenanceReporter().send(flowFile, url, millis);

            getLogger().info("Successfully put {} to Amazon S3 in {} milliseconds", flowFile, millis);
            try {
                removeLocalState(cacheKey);
            } catch (IOException e) {
                getLogger().info("Error trying to delete key {} from cache:", cacheKey, e);
            }

        } catch (IllegalArgumentException | ProcessException | SdkException | IOException e) {
            extractExceptionDetails(e, session, flowFile);
            if (e.getMessage() != null && e.getMessage().contains(S3_PROCESS_UNSCHEDULED_MESSAGE)) {
                getLogger().info(e.getMessage());
                session.rollback();
            } else {
                getLogger().error("Failed to put {} to Amazon S3 due to {}", flowFile, e);
                flowFile = session.penalize(flowFile);
                session.transfer(flowFile, REL_FAILURE);
            }
        }
    }
    private final Lock s3BucketLock = new ReentrantLock();
    private final AtomicLong lastS3AgeOff = new AtomicLong(0L);

    protected void ageoffS3Uploads(final ProcessContext context, final S3Client client, final long now, String bucket) {
        final List<MultipartUpload> oldUploads = getS3AgeoffListAndAgeoffLocalState(context, client, now, bucket);
        for (MultipartUpload upload : oldUploads) {
            abortS3MultipartUpload(client, bucket, upload);
        }
    }

    protected List<MultipartUpload> getS3AgeoffListAndAgeoffLocalState(final ProcessContext context, final S3Client client, final long now, String bucket) {
        final long ageoffInterval = context.getProperty(MULTIPART_S3_AGEOFF_INTERVAL).asTimePeriod(TimeUnit.MILLISECONDS);
        final Long maxAge = context.getProperty(MULTIPART_S3_MAX_AGE).asTimePeriod(TimeUnit.MILLISECONDS);
        final long ageCutoff = now - maxAge;

        final List<MultipartUpload> ageoffList = new ArrayList<>();
        if ((lastS3AgeOff.get() < now - ageoffInterval) && s3BucketLock.tryLock()) {
            try {

                final ListMultipartUploadsRequest listRequest = ListMultipartUploadsRequest.builder()
                        .bucket(bucket)
                        .build();
                final ListMultipartUploadsResponse listResponse = client.listMultipartUploads(listRequest);
                for (MultipartUpload upload : listResponse.uploads()) {
                    long uploadTime = upload.initiated().toEpochMilli();
                    if (uploadTime < ageCutoff) {
                        ageoffList.add(upload);
                    }
                }

                // ageoff any local state
                ageoffLocalState(ageCutoff);
                lastS3AgeOff.set(System.currentTimeMillis());
            } catch (SdkException e) {
                if (e instanceof S3Exception s3e
                        && s3e.statusCode() == 403
                        && s3e.awsErrorDetails().errorCode().equals("AccessDenied")) {
                    getLogger().warn("AccessDenied checking S3 Multipart Upload list for {}: {} " +
                            "** The configured user does not have the s3:ListBucketMultipartUploads permission " +
                            "for this bucket, S3 ageoff cannot occur without this permission.  Next ageoff check " +
                            "time is being advanced by interval to prevent checking on every upload **", bucket, e.getMessage());
                    lastS3AgeOff.set(System.currentTimeMillis());
                } else {
                    getLogger().error("Error checking S3 Multipart Upload list for {}", bucket, e);
                }
            } finally {
                s3BucketLock.unlock();
            }
        }

        return ageoffList;
    }

    protected void abortS3MultipartUpload(final S3Client client, final String bucket, final MultipartUpload upload) {
        final String key = upload.key();
        final String uploadId = upload.uploadId();
        final AbortMultipartUploadRequest abortRequest = AbortMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .uploadId(uploadId)
                .build();
        // No call to an encryption service is necessary for an AbortMultipartUploadRequest.
        try {
            client.abortMultipartUpload(abortRequest);
            getLogger().info("Aborting out of date multipart upload, bucket {} key {} ID {}, initiated {}",
                    bucket, key, uploadId, upload.initiated());
        } catch (SdkException e) {
            getLogger().info("Error trying to abort multipart upload from bucket {} with key {} and ID {}: {}",
                    bucket, key, uploadId, e.getMessage());
        }
    }

    private List<Tag> getObjectTags(ProcessContext context, FlowFile flowFile) {
        final String prefix = context.getProperty(OBJECT_TAGS_PREFIX).evaluateAttributeExpressions(flowFile).getValue();
        final List<Tag> objectTags = new ArrayList<>();
        final Map<String, String> attributesMap = flowFile.getAttributes();

        attributesMap.entrySet().stream()
        .filter(attribute -> attribute.getKey().startsWith(prefix))
        .forEach(attribute -> {
            String tagKey = attribute.getKey();
            String tagValue = attribute.getValue();

            if (context.getProperty(REMOVE_TAG_PREFIX).asBoolean()) {
                tagKey = tagKey.replace(prefix, "");
            }
            objectTags.add(Tag.builder()
                    .key(tagKey)
                    .value(tagValue)
                    .build());
        });

        return objectTags;
    }

    private static final class TransferManagerKey {
        private final Region region;
        private final int maxConcurrency;
        private final long minimumPartSize;
        private final long multipartThreshold;

        private TransferManagerKey(final Region region, final int maxConcurrency, final long minimumPartSize, final long multipartThreshold) {
            this.region = region;
            this.maxConcurrency = maxConcurrency;
            this.minimumPartSize = minimumPartSize;
            this.multipartThreshold = multipartThreshold;
        }

        private Region region() {
            return region;
        }

        private int maxConcurrency() {
            return maxConcurrency;
        }

        private long minimumPartSize() {
            return minimumPartSize;
        }

        private long multipartThreshold() {
            return multipartThreshold;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TransferManagerKey)) {
                return false;
            }
            final TransferManagerKey that = (TransferManagerKey) o;
            return maxConcurrency == that.maxConcurrency
                    && minimumPartSize == that.minimumPartSize
                    && multipartThreshold == that.multipartThreshold
                    && Objects.equals(region, that.region);
        }

        @Override
        public int hashCode() {
            return Objects.hash(region, maxConcurrency, minimumPartSize, multipartThreshold);
        }
    }

    private static final class TransferResources implements Closeable {
        private final S3AsyncClient asyncClient;
        private final S3TransferManager transferManager;

        private TransferResources(final S3AsyncClient asyncClient, final S3TransferManager transferManager) {
            this.asyncClient = asyncClient;
            this.transferManager = transferManager;
        }

        private S3TransferManager transferManager() {
            return transferManager;
        }

        @Override
        public void close() throws IOException {
            transferManager.close();
            asyncClient.close();
        }
    }

    private static final class ResumableUploadState {
        private final ResumableFileUpload resumableUpload;
        private final long timestamp;

        private ResumableUploadState(final ResumableFileUpload resumableUpload, final long timestamp) {
            this.resumableUpload = resumableUpload;
            this.timestamp = timestamp;
        }

        private ResumableFileUpload getResumableUpload() {
            return resumableUpload;
        }

        private long getTimestamp() {
            return timestamp;
        }
    }

    protected static class MultipartState implements Serializable {

        private static final long serialVersionUID = 9006072180563519740L;

        private static final String SEPARATOR = "#";

        private String uploadId;
        private Long filePosition;
        private List<CompletedPart> completedParts;
        private Long partSize;
        private StorageClass storageClass;
        private Long contentLength;
        private Long timestamp;

        public MultipartState() {
            uploadId = "";
            filePosition = 0L;
            completedParts = new ArrayList<>();
            partSize = 0L;
            storageClass = StorageClass.STANDARD;
            contentLength = 0L;
            timestamp = System.currentTimeMillis();
        }

        // create from a previous toString() result
        public MultipartState(final String buf) {
            String[] fields = buf.split(SEPARATOR);
            uploadId = fields[0];
            filePosition = Long.parseLong(fields[1]);
            completedParts = new ArrayList<>();
            for (String part : fields[2].split(",")) {
                if (part != null && !part.isEmpty()) {
                    String[] partFields = part.split("/");
                    this.completedParts.add(CompletedPart.builder()
                            .partNumber(Integer.parseInt(partFields[0]))
                            .eTag(partFields[1])
                            .build());
                }
            }
            partSize = Long.parseLong(fields[3]);
            storageClass = StorageClass.fromValue(fields[4]);
            contentLength = Long.parseLong(fields[5]);
            timestamp = Long.parseLong(fields[6]);
        }

        public String getUploadId() {
            return uploadId;
        }

        public void setUploadId(String id) {
            uploadId = id;
        }

        public Long getFilePosition() {
            return filePosition;
        }

        public void setFilePosition(Long pos) {
            filePosition = pos;
        }

        public List<CompletedPart> getCompletedParts() {
            return completedParts;
        }

        public void addCompletedPart(CompletedPart completedPart) {
            completedParts.add(completedPart);
        }

        public Long getPartSize() {
            return partSize;
        }

        public void setPartSize(Long size) {
            partSize = size;
        }

        public StorageClass getStorageClass() {
            return storageClass;
        }

        public void setStorageClass(StorageClass aClass) {
            storageClass = aClass;
        }

        public Long getContentLength() {
            return contentLength;
        }

        public void setContentLength(Long length) {
            contentLength = length;
        }

        public Long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Long timestamp) {
            this.timestamp = timestamp;
        }

        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder();
            buf.append(uploadId).append(SEPARATOR)
                .append(filePosition.toString()).append(SEPARATOR);
            if (!completedParts.isEmpty()) {
                boolean first = true;
                for (CompletedPart completedPart : completedParts) {
                    if (!first) {
                        buf.append(",");
                    } else {
                        first = false;
                    }
                    buf.append(String.format("%d/%s", completedPart.partNumber(), completedPart.eTag()));
                }
            }
            buf.append(SEPARATOR)
                .append(partSize.toString()).append(SEPARATOR)
                .append(storageClass.toString()).append(SEPARATOR)
                .append(contentLength.toString()).append(SEPARATOR)
                .append(timestamp.toString());
            return buf.toString();
        }
    }
}
