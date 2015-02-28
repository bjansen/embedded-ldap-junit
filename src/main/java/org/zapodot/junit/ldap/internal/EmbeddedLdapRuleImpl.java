package org.zapodot.junit.ldap.internal;

import com.google.common.io.Resources;
import com.sun.jndi.ldap.DefaultResponseControlFactory;
import com.sun.jndi.ldap.LdapCtxFactory;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zapodot.junit.ldap.EmbeddedLdapRule;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.InitialDirContext;
import javax.naming.ldap.LdapContext;
import java.util.Hashtable;
import java.util.List;

public class EmbeddedLdapRuleImpl implements EmbeddedLdapRule {

    private static Logger logger = LoggerFactory.getLogger(EmbeddedLdapRuleImpl.class);
    private final InMemoryDirectoryServer inMemoryDirectoryServer;
    private final AuthenticationConfiguration authenticationConfiguration;
    private LDAPConnection ldapConnection;
    private InitialDirContext initialDirContext;
    private boolean isStarted = false;


    private EmbeddedLdapRuleImpl(final InMemoryDirectoryServer inMemoryDirectoryServer,
                                 final AuthenticationConfiguration authenticationConfiguration1) {
        this.inMemoryDirectoryServer = inMemoryDirectoryServer;
        this.authenticationConfiguration = authenticationConfiguration1;
    }

    public static EmbeddedLdapRule createForConfiguration(final InMemoryDirectoryServerConfig inMemoryDirectoryServerConfig,
                                                          final AuthenticationConfiguration authenticationConfiguration,
                                                          final List<String> ldifs) {
        try {
            return new EmbeddedLdapRuleImpl(createServer(inMemoryDirectoryServerConfig, ldifs),
                                            authenticationConfiguration);
        } catch (LDAPException e) {
            throw new IllegalStateException("Can not initiate in-memory LDAP server due to an exception", e);
        }
    }

    private static InMemoryDirectoryServer createServer(final InMemoryDirectoryServerConfig inMemoryDirectoryServerConfig, final List<String> ldifs) throws LDAPException {
        final InMemoryDirectoryServer ldapServer =
                new InMemoryDirectoryServer(inMemoryDirectoryServerConfig);
        if (ldifs != null && !ldifs.isEmpty()) {
            for (final String ldif : ldifs) {
                ldapServer.importFromLDIF(false, Resources.getResource(ldif).getPath());
            }
        }
        return ldapServer;
    }

    @Override
    public LDAPConnection ldapConnection() throws LDAPException {
        return createOrGetLdapConnection();
    }

    private LDAPConnection createOrGetLdapConnection() throws LDAPException {
        if (isStarted) {
            if (ldapConnection == null) {
                ldapConnection = inMemoryDirectoryServer.getConnection();
            }
            return ldapConnection;
        } else {
            throw new IllegalStateException(
                    "Can not get a LdapConnection before the embedded LDAP server has been started");
        }
    }

    @Override
    public InitialDirContext initialDirContext() throws NamingException {
        return createOrGetInitialDirContext();
    }

    @Override
    public Context context() throws NamingException {
        return initialDirContext();
    }

    private InitialDirContext createOrGetInitialDirContext() throws NamingException {
        if (isStarted) {
            if (initialDirContext == null) {
                initialDirContext = new InitialDirContext(createLdapEnvironment());
            }
            return initialDirContext;
        } else {
            throw new IllegalStateException(
                    "Can not get an InitialDirContext before the embedded LDAP server has been started");
        }
    }

    private Hashtable<String, String> createLdapEnvironment() {
        final Hashtable<String, String> environment = new Hashtable<>();
        environment.put(LdapContext.CONTROL_FACTORIES, DefaultResponseControlFactory.class.getName());
        environment.put(Context.PROVIDER_URL, String.format("ldap://%s:%s",
                                                            "localhost",
                                                            inMemoryDirectoryServer.getListenPort()));
        environment.put(Context.INITIAL_CONTEXT_FACTORY, LdapCtxFactory.class.getName());
        if (authenticationConfiguration != null) {
            environment.putAll(authenticationConfiguration.toAuthenticationEnvironment());
        }
        return environment;
    }

    @Override
    public Statement apply(final Statement base, final Description description) {
        return statement(base);
    }

    private Statement statement(final Statement base) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                startEmbeddedLdapServer();
                try {
                    base.evaluate();
                } finally {
                    takeDownEmbeddedLdapServer();
                }
            }
        };
    }

    private void startEmbeddedLdapServer() throws LDAPException {
        inMemoryDirectoryServer.startListening();
        isStarted = true;
    }

    private void takeDownEmbeddedLdapServer() {
        try {
            if (ldapConnection != null) {
                ldapConnection.close();
            }
            if (initialDirContext != null) {
                initialDirContext.close();
            }
        } catch (NamingException e) {
            logger.info("Could not close initial context, forcing server shutdown anyway", e);
        } finally {
            inMemoryDirectoryServer.shutDown(true);
        }

    }


}
