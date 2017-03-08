package org.apache.nifi.ldap;

import org.apache.nifi.authorization.UserGroupProvider;
import org.apache.nifi.authorization.UserGroupProviderConfigurationContext;
import org.apache.nifi.authorization.exception.UserGroupProviderCreationException;

public class LdapUserGroupProvider implements UserGroupProvider {

    @Override
    public void onConfigured(UserGroupProviderConfigurationContext configurationContext) throws UserGroupProviderCreationException {
        // TODO Auto-generated method stub

    }

}
