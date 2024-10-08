/*
 * Copyright (c) 2012, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.security.services.config;

import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.security.auth.login.FileLoginModule;

import jakarta.inject.Inject;

import java.beans.PropertyVetoException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.api.admin.config.ConfigurationUpgrade;
import org.glassfish.hk2.api.PerLookup;
import org.glassfish.hk2.api.PostConstruct;
import org.glassfish.security.services.provider.authorization.AuthorizationProviderConfig;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.config.Transaction;
import org.jvnet.hk2.config.TransactionFailure;
import org.jvnet.hk2.config.types.Property;

/**
 * Upgrades the configuration to use the default file realm and the
 * login module which handles non-username/password admin authentication.
 *
 * @author tjquinn
 * @author Masoud Kalali
 */
@Service
@PerLookup
public class SecurityConfigUpgradeService implements ConfigurationUpgrade, PostConstruct {

    private static final String AUTHENTICATION_SERVICE_NAME = "adminAuth";
    private static final String FILE_REALM_PROVIDER_NAME = "filerealm";
    private static final String FILE_REALM_PROVIDER_PROVIDER_NAME = "adminFile";
    private static final String FILE_LOGIN_MODULE_NAME = "adminFileLM";
    private static final String FILE_LOGIN_MODULE_CLASS = FileLoginModule.class.getName();

    private static final String ADM_REALM_PROVIDER_NAME = "spcrealm";
    private static final String ADM_REALM_PROVIDER_PROVIDER_NAME = "adminSpc";
    private static final String ADM_LOGIN_MODULE_NAME = "adminSpecialLM";
    private static final String ADM_LOGIN_MODULE_CLASS = "com.sun.enterprise.admin.util.AdminLoginModule";

    private static final String LOGIN_MODULE_TYPE_NAME = "LoginModule";

    private static final String AUTHORIZATION_SERVICE_NAME = "authorizationService";
    private static final String SIMPLE_PROVIDER_PROVIDER_NAME = "simpleAuthorizationProvider";
    private static final String SIMPLE_PROVIDER_NAME = "simpleAuthorization";
    private static final String SIMPLE_PROVIDER_TYPE = "simple";
    private static final String SIMPLE_PROVIDER_CONFIG_NAME = "simpleAuthorizationProviderConfig";
    private static final String SIMPLE_PROVIDER_CLASS_NAME = "org.glassfish.security.services.provider.authorization.SimpleAuthorizationProviderImpl";
    private static final String SUPPORT_POLICY_DEPLOY = "false";

    private static final Logger logger = Logger.getAnonymousLogger();

    @Inject
    private Domain domain;

    @Override
    public void postConstruct() {
        if (domain.getExtensionByType(SecurityConfigurations.class) != null) {
            /*
             * The domain already contains a security-configurations setting,
             * so for now that's sufficient to conclude we don't need to upgrade.
             */
            logger.log(Level.INFO, "SecurityConfigUpgradeService bypassing - security-configurations already present");
            return;
        }
        Transaction t = null;
        try {
            t = new Transaction();
            final Domain domain_w = t.enroll(domain);

            /*
             * Create the security configurations element and add it to the domain.
             */
            final SecurityConfigurations sc_w = domain_w.createChild(SecurityConfigurations.class);
            domain_w.getExtensions().add(sc_w);

            /*
             * Create and add the authentication service.
             */
            final AuthenticationService as_w = addAuthenticationService(sc_w);

            /*
             * Next, add the two providers and their children.
             */
            addAdmRealmProvider(as_w);
            addFileRealmProvider(as_w);

            /**
             * Next add the authorization service
             */
            final AuthorizationService authorizationService = addAuthorizationService(sc_w);
            /**
             * Next add the authorization service provider
             */
            addSimpleAuthorizationProvider(authorizationService);

            t.commit();
            logger.log(Level.INFO, "SecurityConfigUpgradeService successfully completed the upgrade");

        } catch (Exception ex) {
            if (t != null) {
                t.rollback();
            }
            logger.log(Level.SEVERE, null, ex);
        }
    }

    private AuthenticationService addAuthenticationService(final SecurityConfigurations sc_w) throws TransactionFailure, PropertyVetoException {
        final AuthenticationService as_w = sc_w.createChild(AuthenticationService.class);
        sc_w.getSecurityServices().add(as_w);
        as_w.setDefault(true);
        as_w.setName(AUTHENTICATION_SERVICE_NAME);
        as_w.setUsePasswordCredential(true);
        return as_w;
    }

    private SecurityProvider addFileRealmProvider(final AuthenticationService as_w) throws PropertyVetoException, TransactionFailure {
        final SecurityProvider sp_w = addProvider(as_w, FILE_REALM_PROVIDER_NAME, FILE_REALM_PROVIDER_PROVIDER_NAME, LOGIN_MODULE_TYPE_NAME);
        addLoginModule(sp_w, FILE_LOGIN_MODULE_NAME, FILE_LOGIN_MODULE_CLASS);
        return sp_w;

    }

    private SecurityProvider addAdmRealmProvider(final AuthenticationService as_w) throws TransactionFailure, PropertyVetoException {
        final SecurityProvider sp_w = addProvider(as_w, ADM_REALM_PROVIDER_NAME, ADM_REALM_PROVIDER_PROVIDER_NAME, LOGIN_MODULE_TYPE_NAME);
        addLoginModule(sp_w, ADM_LOGIN_MODULE_NAME, ADM_LOGIN_MODULE_CLASS);
        return sp_w;
    }

    private SecurityProvider addProvider(final AuthenticationService as_w, final String providerName, final String providerProviderName,
                                         final String type) throws TransactionFailure, PropertyVetoException {

        final SecurityProvider sp_w = as_w.createChild(SecurityProvider.class);
        as_w.getSecurityProviders().add(sp_w);
        sp_w.setName(providerName);
        sp_w.setProviderName(providerProviderName);
        sp_w.setType(type);
        return sp_w;
    }

    private LoginModuleConfig addLoginModule(final SecurityProvider sp_w, final String name, final String className) throws TransactionFailure, PropertyVetoException {
        final LoginModuleConfig lm_w = sp_w.createChild(LoginModuleConfig.class);
        sp_w.getSecurityProviderConfig().add(lm_w);
        lm_w.setName(name);
        lm_w.setModuleClass(className);
        lm_w.setControlFlag("sufficient");

        final Property configProp = lm_w.createChild(Property.class);
        configProp.setName("config");
        configProp.setValue("server-config");

        final Property realmProp = lm_w.createChild(Property.class);
        realmProp.setName("auth-realm");
        realmProp.setValue("admin-realm");

        lm_w.getProperty().add(configProp);
        lm_w.getProperty().add(realmProp);
        return lm_w;

    }

    private AuthorizationService addAuthorizationService(final SecurityConfigurations sc_w) throws TransactionFailure, PropertyVetoException {
        final AuthorizationService as_w = sc_w.createChild(AuthorizationService.class);
        sc_w.getSecurityServices().add(as_w);
        as_w.setDefault(true);
        as_w.setName(AUTHORIZATION_SERVICE_NAME);
        return as_w;
    }

    private SecurityProvider addSimpleAuthorizationProvider(final AuthorizationService as_w) throws PropertyVetoException, TransactionFailure {
        final SecurityProvider sp_w = addAuthzProvider(as_w, SIMPLE_PROVIDER_NAME, SIMPLE_PROVIDER_PROVIDER_NAME, SIMPLE_PROVIDER_TYPE);
        addAuthorizationConfig(sp_w, SIMPLE_PROVIDER_CONFIG_NAME, SUPPORT_POLICY_DEPLOY);
        return sp_w;
    }

    private void addAuthorizationConfig(SecurityProvider sp_w, String configName, String supportPolicyDeploy) throws TransactionFailure, PropertyVetoException {
        final AuthorizationProviderConfig authorizationProviderConfig = sp_w.createChild(AuthorizationProviderConfig.class);
        authorizationProviderConfig.setName(configName);
        authorizationProviderConfig.setSupportPolicyDeploy(Boolean.valueOf(supportPolicyDeploy));
        authorizationProviderConfig.setProviderClass(SIMPLE_PROVIDER_CLASS_NAME);
        sp_w.getSecurityProviderConfig().add(authorizationProviderConfig);
    }

    private SecurityProvider addAuthzProvider(AuthorizationService as_w, String simpleProviderName, String simpleProviderProviderName, String simpleProviderType) throws TransactionFailure, PropertyVetoException {
        final SecurityProvider sp_w = as_w.createChild(SecurityProvider.class);
        sp_w.setName(simpleProviderName);
        sp_w.setProviderName(simpleProviderProviderName);
        sp_w.setType(simpleProviderType);
        as_w.getSecurityProviders().add(sp_w);
        return sp_w;
    }
}
