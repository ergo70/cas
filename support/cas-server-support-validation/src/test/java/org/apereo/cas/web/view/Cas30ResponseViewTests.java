package org.apereo.cas.web.view;

import com.google.common.base.Throwables;
import org.apereo.cas.authentication.CoreAuthenticationTestUtils;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.util.EncodingUtils;
import org.apereo.cas.CasProtocolConstants;
import org.apereo.cas.CasViewConstants;
import org.apereo.cas.authentication.UsernamePasswordCredential;
import org.apereo.cas.authentication.support.DefaultCasAttributeEncoder;
import org.apereo.cas.util.crypto.PrivateKeyFactoryBean;
import org.apereo.cas.web.AbstractServiceValidateControllerTests;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.support.RequestContext;

import javax.crypto.Cipher;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.security.PrivateKey;
import java.util.Map;

import static org.junit.Assert.*;


/**
 * Unit tests for {@link Cas30ResponseView}.
 * @author Misagh Moayyed
 * @since 4.0.0
 */
@DirtiesContext
@TestPropertySource(properties = "cas.clearpass.cacheCredential=true")
public class Cas30ResponseViewTests extends AbstractServiceValidateControllerTests {

    @Autowired
    @Qualifier("servicesManager")
    private ServicesManager servicesManager;

    @Autowired
    @Qualifier("cas3ServiceJsonView")
    private View cas3ServiceJsonView;

    @Autowired
    @Qualifier("cas3SuccessView")
    private View cas3SuccessView;

    @Autowired
    @Qualifier("cas3ServiceFailureView")
    private View cas3ServiceFailureView;

    @Before
    public void setup() {
        this.serviceValidateController.setFailureView(cas3ServiceFailureView);
        this.serviceValidateController.setSuccessView(cas3SuccessView);
        this.serviceValidateController.setJsonView(cas3ServiceJsonView);
    }
    
    private Map<?, ?> renderView() throws Exception{
        final ModelAndView modelAndView = this.getModelAndViewUponServiceValidationWithSecurePgtUrl();
        final MockHttpServletRequest req = new MockHttpServletRequest(new MockServletContext());
        req.setAttribute(RequestContext.WEB_APPLICATION_CONTEXT_ATTRIBUTE, new GenericWebApplicationContext(req.getServletContext()));

        final Cas30ResponseView view = new Cas30ResponseView();
        view.setServicesManager(this.servicesManager);
        view.setCasAttributeEncoder(new DefaultCasAttributeEncoder(this.servicesManager));
        view.setView(new View() {
            @Override
            public String getContentType() {
                return "text/html";
            }

            @Override
            public void render(final Map<String, ?> map, final HttpServletRequest request, final HttpServletResponse response) throws Exception {
                map.forEach(request::setAttribute);
            }
        });

        final MockHttpServletResponse resp = new MockHttpServletResponse();
        view.render(modelAndView.getModel(), req, resp);
        return (Map<?, ?>) req.getAttribute(CasProtocolConstants.VALIDATION_CAS_MODEL_ATTRIBUTE_NAME_ATTRIBUTES);
    }

    @Test
    public void verifyViewAuthnAttributes() throws Exception {
        final Map<?, ?> attributes = renderView();
        assertTrue(attributes.containsKey(CasProtocolConstants.VALIDATION_CAS_MODEL_ATTRIBUTE_NAME_AUTHENTICATION_DATE));
        assertTrue(attributes.containsKey(CasProtocolConstants.VALIDATION_CAS_MODEL_ATTRIBUTE_NAME_FROM_NEW_LOGIN));
        assertTrue(attributes.containsKey(CasProtocolConstants.VALIDATION_REMEMBER_ME_ATTRIBUTE_NAME));
    }

    @Test
    public void verifyPasswordAsAuthenticationAttributeCanDecrypt() throws Exception {
        final Map<?, ?> attributes = renderView();
        assertTrue(attributes.containsKey(CasViewConstants.MODEL_ATTRIBUTE_NAME_PRINCIPAL_CREDENTIAL));

        final String encodedPsw = (String) attributes.get(CasViewConstants.MODEL_ATTRIBUTE_NAME_PRINCIPAL_CREDENTIAL);
        final String password = decryptCredential(encodedPsw);
        final UsernamePasswordCredential creds = CoreAuthenticationTestUtils.getCredentialsWithSameUsernameAndPassword();
        assertEquals(password, creds.getPassword());
    }

    @Test
    public void verifyProxyGrantingTicketAsAuthenticationAttributeCanDecrypt() throws Exception {
        final Map<?, ?> attributes = renderView();
        assertTrue(attributes.containsKey(CasViewConstants.MODEL_ATTRIBUTE_NAME_PROXY_GRANTING_TICKET));

        final String encodedPgt = (String) attributes.get(CasViewConstants.MODEL_ATTRIBUTE_NAME_PROXY_GRANTING_TICKET);
        final String pgt = decryptCredential(encodedPgt);
        assertNotNull(pgt);
    }

    private String decryptCredential(final String cred) {
        try {
            final PrivateKeyFactoryBean factory = new PrivateKeyFactoryBean();
            factory.setAlgorithm("RSA");
            factory.setLocation(new ClassPathResource("RSA1024Private.p8"));
            factory.setSingleton(false);
            final PrivateKey privateKey = factory.getObject();

            logger.debug("Initializing cipher based on [{}]", privateKey.getAlgorithm());
            final Cipher cipher = Cipher.getInstance(privateKey.getAlgorithm());

            logger.debug("Decoding value [{}]", cred);
            final byte[] cred64 = EncodingUtils.decodeBase64(cred);

            logger.debug("Initializing decrypt-mode via private key [{}]", privateKey.getAlgorithm());
            cipher.init(Cipher.DECRYPT_MODE, privateKey);

            final byte[] cipherData = cipher.doFinal(cred64);
            return new String(cipherData);
        } catch (final Exception e) {
            throw Throwables.propagate(e);
        }
    }
}
