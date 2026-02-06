/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation
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
package org.glassfish.main.test.app.mpjwt.webapp;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import jakarta.security.enterprise.AuthenticationException;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.authentication.mechanism.http.BasicAuthenticationMechanismDefinition;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanismHandler;
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.glassfish.api.security.jwt.MicroProfileJwtAuthenticationMechanism;

/**
 *
 * @author Ondro Mihalyi
 */
@BasicAuthenticationMechanismDefinition
@ApplicationScoped
@Alternative
@Priority(Interceptor.Priority.APPLICATION)
public class JwtCustomAuthMechanismHandler implements HttpAuthenticationMechanismHandler {

    @Inject
    @MicroProfileJwtAuthenticationMechanism
    HttpAuthenticationMechanism jwtAuthentication;

    @Inject
    @BasicAuthenticationMechanismDefinition.BasicAuthenticationMechanism
    HttpAuthenticationMechanism basicAuthentication;

    @Override
    public AuthenticationStatus validateRequest(HttpServletRequest request, HttpServletResponse response, HttpMessageContext messageContext) throws AuthenticationException {
        return jwtAuthentication.validateRequest(request, response, messageContext);
    }

}
