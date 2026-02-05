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
package org.apache.nifi.processors.aws.credentials.provider.factory.strategies;

import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.context.PropertyContext;
import org.apache.nifi.processors.aws.credentials.provider.factory.CredentialsStrategy;
import org.apache.nifi.proxy.ProxyConfiguration;
import org.apache.nifi.proxy.ProxyConfigurationService;
import org.apache.nifi.ssl.SSLContextProvider;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import javax.net.ssl.SSLContext;

import static org.apache.nifi.processors.aws.credentials.provider.service.AWSCredentialsProviderControllerService.ASSUME_ROLE_PROXY_CONFIGURATION_SERVICE;
import static org.apache.nifi.processors.aws.credentials.provider.service.AWSCredentialsProviderControllerService.ASSUME_ROLE_SSL_CONTEXT_SERVICE;
import static org.apache.nifi.processors.aws.credentials.provider.service.AWSCredentialsProviderControllerService.ASSUME_ROLE_STS_ENDPOINT;
import static org.apache.nifi.processors.aws.credentials.provider.service.AWSCredentialsProviderControllerService.ASSUME_ROLE_STS_REGION;
import static org.apache.nifi.processors.aws.credentials.provider.service.AWSCredentialsProviderControllerService.MAX_SESSION_TIME;
import static org.apache.nifi.processors.aws.credentials.provider.service.AWSCredentialsProviderControllerService.USE_WEB_IDENTITY_TOKEN_FILE;
import static org.apache.nifi.processors.aws.credentials.provider.service.AWSCredentialsProviderControllerService.WEB_IDENTITY_ROLE_ARN;
import static org.apache.nifi.processors.aws.credentials.provider.service.AWSCredentialsProviderControllerService.WEB_IDENTITY_ROLE_SESSION_NAME;
import static org.apache.nifi.processors.aws.credentials.provider.service.AWSCredentialsProviderControllerService.WEB_IDENTITY_TOKEN_FILE;

/**
 * Supports AWS credentials using STS AssumeRoleWithWebIdentity with a web identity token read from a file.
 * This is designed for EKS (Elastic Kubernetes Service) environments where the token is provided via
 * a mounted file (AWS_WEB_IDENTITY_TOKEN_FILE environment variable).
 *
 * <p>This strategy creates a NiFi-managed StsClient to avoid issues with the AWS SDK's internal
 * WebIdentityTokenFileCredentialsProvider, which can have connection pool lifecycle problems.</p>
 *
 * @see <a href="https://docs.aws.amazon.com/eks/latest/userguide/iam-roles-for-service-accounts.html">
 *     IAM roles for service accounts</a>
 */
public class EksWebIdentityCredentialsStrategy extends AbstractCredentialsStrategy implements CredentialsStrategy {

    private static final String AWS_WEB_IDENTITY_TOKEN_FILE_ENV = "AWS_WEB_IDENTITY_TOKEN_FILE";
    private static final String AWS_ROLE_ARN_ENV = "AWS_ROLE_ARN";
    private static final String AWS_ROLE_SESSION_NAME_ENV = "AWS_ROLE_SESSION_NAME";
    private static final String DEFAULT_SESSION_NAME = "nifi-eks-session";

    public EksWebIdentityCredentialsStrategy() {
        super("EKS Web Identity Token File", new PropertyDescriptor[]{
                USE_WEB_IDENTITY_TOKEN_FILE
        });
    }

    @Override
    public boolean canCreatePrimaryCredential(final PropertyContext propertyContext) {
        final Boolean useWebIdentityFile = propertyContext.getProperty(USE_WEB_IDENTITY_TOKEN_FILE).asBoolean();
        return Boolean.TRUE.equals(useWebIdentityFile);
    }

    @Override
    public Collection<ValidationResult> validate(final ValidationContext validationContext, final CredentialsStrategy primaryStrategy) {
        final Collection<ValidationResult> results = new ArrayList<>();
        final boolean thisIsSelectedStrategy = this == primaryStrategy;
        final Boolean useWebIdentityFile = validationContext.getProperty(USE_WEB_IDENTITY_TOKEN_FILE).asBoolean();

        if (!thisIsSelectedStrategy && Boolean.TRUE.equals(useWebIdentityFile)) {
            results.add(new ValidationResult.Builder()
                    .subject(USE_WEB_IDENTITY_TOKEN_FILE.getName())
                    .valid(false)
                    .explanation(String.format("property %s cannot be used with %s",
                            USE_WEB_IDENTITY_TOKEN_FILE.getName(), primaryStrategy.getName()))
                    .build());
        }

        if (thisIsSelectedStrategy) {
            // Validate that we can resolve the token file path
            final String tokenFilePath = resolveTokenFilePath(validationContext);
            if (tokenFilePath == null || tokenFilePath.isEmpty()) {
                results.add(new ValidationResult.Builder()
                        .subject(WEB_IDENTITY_TOKEN_FILE.getName())
                        .valid(false)
                        .explanation("Web Identity Token File must be specified or AWS_WEB_IDENTITY_TOKEN_FILE environment variable must be set")
                        .build());
            } else {
                final Path path = Paths.get(tokenFilePath);
                if (!Files.exists(path)) {
                    results.add(new ValidationResult.Builder()
                            .subject(WEB_IDENTITY_TOKEN_FILE.getName())
                            .valid(false)
                            .explanation("Web Identity Token File does not exist: " + tokenFilePath)
                            .build());
                } else if (!Files.isReadable(path)) {
                    results.add(new ValidationResult.Builder()
                            .subject(WEB_IDENTITY_TOKEN_FILE.getName())
                            .valid(false)
                            .explanation("Web Identity Token File is not readable: " + tokenFilePath)
                            .build());
                }
            }

            // Validate that we can resolve the role ARN
            final String roleArn = resolveRoleArn(validationContext);
            if (roleArn == null || roleArn.isEmpty()) {
                results.add(new ValidationResult.Builder()
                        .subject(WEB_IDENTITY_ROLE_ARN.getName())
                        .valid(false)
                        .explanation("Web Identity Role ARN must be specified or AWS_ROLE_ARN environment variable must be set")
                        .build());
            }
        }

        return results;
    }

    @Override
    public AwsCredentialsProvider getAwsCredentialsProvider(final PropertyContext propertyContext) {
        final String tokenFilePath = resolveTokenFilePath(propertyContext);
        final String roleArn = resolveRoleArn(propertyContext);
        final String roleSessionName = resolveRoleSessionName(propertyContext);
        final Integer sessionSeconds = propertyContext.getProperty(MAX_SESSION_TIME).asInteger();
        final String stsRegionId = propertyContext.getProperty(ASSUME_ROLE_STS_REGION).getValue();
        final String stsEndpoint = propertyContext.getProperty(ASSUME_ROLE_STS_ENDPOINT).getValue();
        final SSLContextProvider sslContextProvider = propertyContext.getProperty(ASSUME_ROLE_SSL_CONTEXT_SERVICE).asControllerService(SSLContextProvider.class);
        final ProxyConfigurationService proxyConfigurationService = propertyContext.getProperty(ASSUME_ROLE_PROXY_CONFIGURATION_SERVICE).asControllerService(ProxyConfigurationService.class);

        final ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder();

        if (sslContextProvider != null) {
            final SSLContext sslContext = sslContextProvider.createContext();
            httpClientBuilder.socketFactory(new SSLConnectionSocketFactory(sslContext));
        }

        if (proxyConfigurationService != null) {
            final ProxyConfiguration proxyConfiguration = proxyConfigurationService.getConfiguration();
            if (proxyConfiguration.getProxyType() == Proxy.Type.HTTP) {
                final software.amazon.awssdk.http.apache.ProxyConfiguration.Builder proxyConfigBuilder = software.amazon.awssdk.http.apache.ProxyConfiguration.builder()
                        .endpoint(URI.create(String.format("http://%s:%s", proxyConfiguration.getProxyServerHost(), proxyConfiguration.getProxyServerPort())));

                if (proxyConfiguration.hasCredential()) {
                    proxyConfigBuilder.username(proxyConfiguration.getProxyUserName());
                    proxyConfigBuilder.password(proxyConfiguration.getProxyUserPassword());
                }

                httpClientBuilder.proxyConfiguration(proxyConfigBuilder.build());
            }
        }

        final StsClientBuilder stsClientBuilder = StsClient.builder().httpClient(httpClientBuilder.build());

        if (stsRegionId != null && !stsRegionId.isEmpty()) {
            stsClientBuilder.region(Region.of(stsRegionId));
        }

        if (stsEndpoint != null && !stsEndpoint.isEmpty()) {
            stsClientBuilder.endpointOverride(URI.create(stsEndpoint));
        }

        final StsClient stsClient = stsClientBuilder.build();

        return new EksWebIdentityRefreshingCredentialsProvider(
                stsClient,
                Paths.get(tokenFilePath),
                roleArn,
                roleSessionName,
                sessionSeconds
        );
    }

    private String resolveTokenFilePath(final PropertyContext propertyContext) {
        final String configuredPath = propertyContext.getProperty(WEB_IDENTITY_TOKEN_FILE).getValue();
        if (configuredPath != null && !configuredPath.isEmpty()) {
            return configuredPath;
        }
        return System.getenv(AWS_WEB_IDENTITY_TOKEN_FILE_ENV);
    }

    private String resolveRoleArn(final PropertyContext propertyContext) {
        final String configuredArn = propertyContext.getProperty(WEB_IDENTITY_ROLE_ARN).getValue();
        if (configuredArn != null && !configuredArn.isEmpty()) {
            return configuredArn;
        }
        return System.getenv(AWS_ROLE_ARN_ENV);
    }

    private String resolveRoleSessionName(final PropertyContext propertyContext) {
        final String configuredName = propertyContext.getProperty(WEB_IDENTITY_ROLE_SESSION_NAME).getValue();
        if (configuredName != null && !configuredName.isEmpty()) {
            return configuredName;
        }
        final String envName = System.getenv(AWS_ROLE_SESSION_NAME_ENV);
        if (envName != null && !envName.isEmpty()) {
            return envName;
        }
        return DEFAULT_SESSION_NAME;
    }

    /**
     * A credentials provider that reads web identity tokens from a file and uses STS to
     * assume a role. This provider manages its own StsClient, avoiding issues with the
     * AWS SDK's internal credential provider lifecycle.
     */
    private static final class EksWebIdentityRefreshingCredentialsProvider implements AwsCredentialsProvider {
        private static final Duration SKEW = Duration.ofSeconds(60);

        private final StsClient stsClient;
        private final Path tokenFilePath;
        private final String roleArn;
        private final String roleSessionName;
        private final Integer sessionSeconds;

        private volatile AwsSessionCredentials cached;
        private volatile Instant expiration;

        private EksWebIdentityRefreshingCredentialsProvider(final StsClient stsClient,
                                                            final Path tokenFilePath,
                                                            final String roleArn,
                                                            final String roleSessionName,
                                                            final Integer sessionSeconds) {
            this.stsClient = Objects.requireNonNull(stsClient, "stsClient required");
            this.tokenFilePath = Objects.requireNonNull(tokenFilePath, "tokenFilePath required");
            this.roleArn = Objects.requireNonNull(roleArn, "roleArn required");
            this.roleSessionName = Objects.requireNonNull(roleSessionName, "roleSessionName required");
            this.sessionSeconds = sessionSeconds;
        }

        @Override
        public AwsCredentials resolveCredentials() {
            final Instant now = Instant.now();
            final AwsSessionCredentials current = cached;
            final Instant currentExpiration = expiration;
            if (current != null && currentExpiration != null && now.isBefore(currentExpiration.minus(SKEW))) {
                return current;
            }

            synchronized (this) {
                if (cached != null && expiration != null && Instant.now().isBefore(expiration.minus(SKEW))) {
                    return cached;
                }

                final String webIdentityToken = readTokenFromFile();

                final AssumeRoleWithWebIdentityRequest.Builder reqBuilder = AssumeRoleWithWebIdentityRequest.builder()
                        .roleArn(roleArn)
                        .roleSessionName(roleSessionName)
                        .webIdentityToken(webIdentityToken);

                if (sessionSeconds != null) {
                    reqBuilder.durationSeconds(sessionSeconds);
                }

                final AssumeRoleWithWebIdentityResponse resp = stsClient.assumeRoleWithWebIdentity(reqBuilder.build());
                final Credentials creds = resp.credentials();
                final AwsSessionCredentials sessionCreds = AwsSessionCredentials.create(
                        creds.accessKeyId(), creds.secretAccessKey(), creds.sessionToken());

                this.cached = sessionCreds;
                this.expiration = creds.expiration();
                return sessionCreds;
            }
        }

        private String readTokenFromFile() {
            try {
                return Files.readString(tokenFilePath, StandardCharsets.UTF_8).trim();
            } catch (final IOException e) {
                throw new UncheckedIOException("Failed to read web identity token from file: " + tokenFilePath, e);
            }
        }
    }
}
