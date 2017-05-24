package org.apache.nifi.authorization;

import org.apache.nifi.authorization.exception.AuthorizerCreationException;
import org.apache.nifi.authorization.exception.UserGroupProviderCreationException;

public interface UserGroupProvider {

    /**
     * Called to configure the User Group Provider.
     *
     * @param configurationContext at the time of configuration
     * @throws AuthorizerCreationException for any issues configuring the provider
     */
    void onConfigured(UserGroupProviderConfigurationContext configurationContext) throws UserGroupProviderCreationException;

    /**
     * Called immediately after instance creation for implementers to perform additional setup
     *
     * @param initializationContext in which to initialize
     */
    void initialize(AuthorizerInitializationContext initializationContext) throws UserGroupProviderCreationException;

}
