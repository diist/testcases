/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.coheigea.cxf.syncope.authentication;

import java.util.Collections;
import java.util.List;

import org.apache.cxf.clustering.FailoverFeature;
import org.apache.cxf.clustering.SequentialStrategy;
import org.apache.cxf.common.util.Base64Utility;
import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.syncope.common.to.UserTO;
import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.handler.RequestData;
import org.apache.wss4j.dom.message.token.UsernameToken;
import org.apache.wss4j.dom.validate.Credential;
import org.apache.wss4j.dom.validate.Validator;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;

/**
 * This is a custom Validator that authenticates to a Syncope IDM and checks to see whether the
 * supplied username and password are in the system. It takes in a list of addresses and uses the
 * CXF failover feature so that it can failover to other instances, if the first instance is down,
 * etc.
 */
public class SyncopeUTValidator implements Validator {
    
    private static org.apache.commons.logging.Log log = 
            org.apache.commons.logging.LogFactory.getLog(SyncopeUTValidator.class);
    
    private String primaryAddress;
    private List<String> alternativeAddresses;
    
    public Credential validate(Credential credential, RequestData data) throws WSSecurityException {
        if (credential == null || credential.getUsernametoken() == null) {
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "noCredential");
        }
        
        // Validate the UsernameToken
        UsernameToken usernameToken = credential.getUsernametoken();
        String pwType = usernameToken.getPasswordType();
        if (log.isDebugEnabled()) {
            log.debug("UsernameToken user " + usernameToken.getName());
            log.debug("UsernameToken password type " + pwType);
        }
        if (!WSConstants.PASSWORD_TEXT.equals(pwType)) {
            if (log.isDebugEnabled()) {
                log.debug("Auaddressesthentication failed - digest passwords are not accepted");
            }
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILED_AUTHENTICATION);
        }
        if (usernameToken.getPassword() == null) {
            if (log.isDebugEnabled()) {
                log.debug("Authentication failed - no password was provided");
            }
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILED_AUTHENTICATION);
        }
        
        // Set up failover feature
        FailoverFeature failoverFeature = new FailoverFeature();
        
        SequentialStrategy strategy = new SequentialStrategy();
        strategy.setAlternateAddresses(alternativeAddresses);
        failoverFeature.setStrategy(strategy);
         
        // Send it off to Syncope for validation
        WebClient client = 
            WebClient.create(primaryAddress, Collections.singletonList(new JacksonJsonProvider()),
                             Collections.singletonList(failoverFeature), null);
        
        String authorizationHeader = 
            "Basic " + Base64Utility.encode(
                (usernameToken.getName() + ":" + usernameToken.getPassword()).getBytes()
            );

        client.header("Authorization", authorizationHeader);
        if (log.isDebugEnabled()) {
            log.debug("Authenticating user " + usernameToken.getName() + " to Syncope server");
        }
        
        client = client.path("users/self");
        try {
            UserTO user = client.get(UserTO.class);
            if (user == null) {
                throw new WSSecurityException(WSSecurityException.ErrorCode.FAILED_AUTHENTICATION);
            }
        } catch (RuntimeException ex) {
            if (log.isDebugEnabled()) {
                log.debug(ex.getMessage(), ex);
            }
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILED_AUTHENTICATION);
        }
        
        return credential;
    }

    public String getPrimaryAddress() {
        return primaryAddress;
    }

    public void setPrimaryAddress(String primaryAddress) {
        this.primaryAddress = primaryAddress;
    }

    public List<String> getAlternativeAddresses() {
        return alternativeAddresses;
    }

    public void setAlternativeAddresses(List<String> alternativeAddresses) {
        this.alternativeAddresses = alternativeAddresses;
    }
    
    
}
