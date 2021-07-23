/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) [2017-2020] Payara Foundation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/master/LICENSE.txt
 * See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * The Payara Foundation designates this particular file as subject to the "Classpath"
 * exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package fish.payara.microprofile.jwtauth.jwt;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSADecrypter;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.EncryptedJWT;
import com.nimbusds.jwt.SignedJWT;
import org.eclipse.microprofile.jwt.Claims;

import javax.json.Json;
import javax.json.JsonNumber;
import javax.json.JsonReader;
import javax.json.JsonString;
import javax.json.JsonValue;
import java.io.StringReader;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

import static com.nimbusds.jose.JWEAlgorithm.RSA_OAEP;
import static com.nimbusds.jose.JWSAlgorithm.ES256;
import static com.nimbusds.jose.JWSAlgorithm.RS256;
import static java.util.Arrays.asList;
import static javax.json.Json.createObjectBuilder;
import static org.eclipse.microprofile.jwt.Claims.exp;
import static org.eclipse.microprofile.jwt.Claims.iat;
import static org.eclipse.microprofile.jwt.Claims.iss;
import static org.eclipse.microprofile.jwt.Claims.jti;
import static org.eclipse.microprofile.jwt.Claims.preferred_username;
import static org.eclipse.microprofile.jwt.Claims.raw_token;
import static org.eclipse.microprofile.jwt.Claims.sub;
import static org.eclipse.microprofile.jwt.Claims.upn;

/**
 * Parses a JWT bearer token and validates it according to the MP-JWT rules.
 *
 * @author Arjan Tijms
 */
public class JwtTokenParser {

    private final static String DEFAULT_NAMESPACE = "https://payara.fish/mp-jwt/";

    // Groups no longer required since 2.0
    private final List<Claims> requiredClaims = asList(iss, sub, exp, iat, jti);

    private final boolean enableNamespacedClaims;
    private final boolean disableTypeVerification;
    private final Optional<String> customNamespace;

    private String rawToken;
    private SignedJWT signedJWT;
    private EncryptedJWT encryptedJWT;

    public JwtTokenParser(Optional<Boolean> enableNamespacedClaims, Optional<String> customNamespace, Optional<Boolean> disableTypeVerification) {
        this.enableNamespacedClaims = enableNamespacedClaims.orElse(false);
        this.disableTypeVerification = disableTypeVerification.orElse(false);
        this.customNamespace = customNamespace;
    }

    public JwtTokenParser() {
        this(Optional.empty(), Optional.empty(), Optional.empty());
    }

    public void parse(String bearerToken) throws Exception {
        rawToken = bearerToken;

        try {
            signedJWT = SignedJWT.parse(rawToken);

            if (!checkIsJWT(signedJWT.getHeader())) {
                throw new IllegalStateException("Not JWT");
            }
        } catch (ParseException parseException) {
            encryptedJWT = EncryptedJWT.parse(rawToken);

            if (!checkIsJWT(encryptedJWT.getHeader())) {
                throw new IllegalStateException("Not JWT");
            }
        }
    }

    public JsonWebTokenImpl verify(String issuer, PublicKey publicKey) throws Exception {
        if (signedJWT == null) {
            throw new IllegalStateException("No parsed SignedJWT.");
        }

        // 1.0 4.1 alg + MP-JWT 1.0 6.1 1
        if (!signedJWT.getHeader().getAlgorithm().equals(RS256)
                && !signedJWT.getHeader().getAlgorithm().equals(ES256)) {
            throw new IllegalStateException("Not RS256 or ES256");
        }

        try (JsonReader reader = Json.createReader(new StringReader(signedJWT.getPayload().toString()))) {
            Map<String, JsonValue> rawClaims = new HashMap<>(reader.readObject());

            // Vendor - Process namespaced claims
            rawClaims = handleNamespacedClaims(rawClaims);

            // MP-JWT 1.0 4.1 Minimum MP-JWT Required Claims
            if (!checkRequiredClaimsPresent(rawClaims)) {
                throw new IllegalStateException("Not all required claims present");
            }

            // MP-JWT 1.0 4.1 upn - has fallbacks
            String callerPrincipalName = getCallerPrincipalName(rawClaims);
            if (callerPrincipalName == null) {
                throw new IllegalStateException("One of upn, preferred_username or sub is required to be non null");
            }

            // MP-JWT 1.0 6.1 2
            if (!checkIssuer(rawClaims, issuer)) {
                throw new IllegalStateException("Bad issuer");
            }

            if (!checkNotExpired(rawClaims)) {
                throw new IllegalStateException("Expired");
            }

            // MP-JWT 1.0 6.1 2
            if (signedJWT.getHeader().getAlgorithm().equals(RS256)) {
                if (!signedJWT.verify(new RSASSAVerifier((RSAPublicKey) publicKey))) {
                    throw new IllegalStateException("Signature invalid");
                }
            } else {
                if (!signedJWT.verify(new ECDSAVerifier((ECPublicKey) publicKey))) {
                    throw new IllegalStateException("Signature invalid");
                }
            }

            rawClaims.put(
                    raw_token.name(),
                    createObjectBuilder().add("token", rawToken).build().get("token"));

            return new JsonWebTokenImpl(callerPrincipalName, rawClaims);
        }
    }

    public JsonWebTokenImpl verify(String issuer, PublicKey publicKey, PrivateKey privateKey) throws Exception {
        if (signedJWT == null) {
            if (encryptedJWT == null) {
                throw new IllegalStateException("No parsed SignedJWT or EncryptedJWT.");
            }

            if (!encryptedJWT.getHeader().getAlgorithm().getName().equals(RSA_OAEP.getName())) {
                throw new IllegalStateException("Not RSA-OAEP");
            }

            encryptedJWT.decrypt(new RSADecrypter(privateKey));

            signedJWT = encryptedJWT.getPayload().toSignedJWT();

            if (signedJWT == null) {
                throw new IllegalStateException("No parsed SignedJWT.");
            }
        }

        return verify(issuer, publicKey);
    }

    public String getKeyID() {
        if (signedJWT == null && encryptedJWT == null) {
            throw new IllegalStateException("No parsed SignedJWT or EncryptedJWT.");
        }

        if (signedJWT != null) {
            return signedJWT.getHeader().getKeyID();
        } else {
            return encryptedJWT.getHeader().getKeyID();
        }
    }

    private Map<String, JsonValue> handleNamespacedClaims(Map<String, JsonValue> currentClaims) {
        if (this.enableNamespacedClaims) {
            final String namespace = customNamespace.orElse(DEFAULT_NAMESPACE);
            Map<String, JsonValue> processedClaims = new HashMap<>(currentClaims.size());
            for (Entry<String, JsonValue> entry : currentClaims.entrySet()) {
                String claimName = entry.getKey();
                JsonValue value = entry.getValue();
                if (claimName.startsWith(namespace)) {
                    claimName = claimName.substring(namespace.length());
                }
                processedClaims.put(claimName, value);
            }
            return processedClaims;
        } else {
            return currentClaims;
        }
    }

    private boolean checkRequiredClaimsPresent(Map<String, JsonValue> presentedClaims) {
        for (Claims requiredClaim : requiredClaims) {
            if (presentedClaims.get(requiredClaim.name()) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Will confirm the token has not expired if the current time and issue time
     * were both before the expiry time
     *
     * @param presentedClaims the claims from the JWT
     * @return if the JWT has expired
     */
    private boolean checkNotExpired(Map<String, JsonValue> presentedClaims) {
        final long currentTime = System.currentTimeMillis() / 1000;
        final long expiredTime = ((JsonNumber) presentedClaims.get(exp.name())).longValue();
        final long issueTime = ((JsonNumber) presentedClaims.get(iat.name())).longValue();

        return currentTime < expiredTime && issueTime < expiredTime;
    }

    private boolean checkIssuer(Map<String, JsonValue> presentedClaims, String acceptedIssuer) {
        if (!(presentedClaims.get(iss.name()) instanceof JsonString)) {
            return false;
        }

        String issuer = ((JsonString) presentedClaims.get(iss.name())).getString();

        // TODO: make acceptedIssuers (set)
        return acceptedIssuer.equals(issuer);
    }

    private boolean checkIsJWT(JWSHeader header) {
        return disableTypeVerification || Optional.ofNullable(header.getType())
                .map(JOSEObjectType::toString)
                .orElse("")
                .equals("JWT");
    }

    private boolean checkIsJWT(JWEHeader header) {
        return disableTypeVerification || Optional.ofNullable(header.getContentType())
                .orElse("")
                .equals("JWT");
    }

    private String getCallerPrincipalName(Map<String, JsonValue> rawClaims) {
        JsonString callerPrincipalClaim = (JsonString) rawClaims.get(upn.name());

        if (callerPrincipalClaim == null) {
            callerPrincipalClaim = (JsonString) rawClaims.get(preferred_username.name());
        }

        if (callerPrincipalClaim == null) {
            callerPrincipalClaim = (JsonString) rawClaims.get(sub.name());
        }

        if (callerPrincipalClaim == null) {
            return null;
        }

        return callerPrincipalClaim.getString();
    }

}
