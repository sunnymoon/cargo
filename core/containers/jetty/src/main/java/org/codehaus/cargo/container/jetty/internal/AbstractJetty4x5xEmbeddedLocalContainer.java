/*
 * ========================================================================
 *
 * Codehaus CARGO, copyright 2004-2011 Vincent Massol, 2012-2016 Ali Tokmen.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * ========================================================================
 */
package org.codehaus.cargo.container.jetty.internal;

import java.io.File;

import org.codehaus.cargo.container.ContainerException;
import org.codehaus.cargo.container.configuration.LocalConfiguration;
import org.codehaus.cargo.container.deployable.Deployable;
import org.codehaus.cargo.container.deployable.DeployableType;
import org.codehaus.cargo.container.deployable.WAR;
import org.codehaus.cargo.container.jetty.JettyPropertySet;
import org.codehaus.cargo.container.property.ServletPropertySet;
import org.codehaus.cargo.container.property.User;

/**
 * Common code for all Jetty 4.x and 5.x embedded container implementations.
 */
public abstract class AbstractJetty4x5xEmbeddedLocalContainer
    extends AbstractJettyEmbeddedLocalContainer
{
    /**
     * {@inheritDoc}
     * @see AbstractJettyEmbeddedLocalContainer#AbstractJettyEmbeddedLocalContainer(org.codehaus.cargo.container.configuration.LocalConfiguration)
     */
    public AbstractJetty4x5xEmbeddedLocalContainer(LocalConfiguration configuration)
    {
        super(configuration);
    }

    /**
     * {@inheritDoc}
     * @see AbstractJetty4x5xEmbeddedLocalContainer#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        createServerObject();

        // Configure a listener
        Class listenerClass = getClassLoader().loadClass("org.mortbay.http.SocketListener");
        Object listener = listenerClass.newInstance();

        listenerClass.getMethod("setPort", new Class[] {int.class}).invoke(listener,
            new Object[] {new Integer(getConfiguration().getPropertyValue(
                ServletPropertySet.PORT))});

        getServer().getClass().getMethod("addListener",
            new Class[] {getClassLoader().loadClass("org.mortbay.http.HttpListener")})
            .invoke(getServer(), new Object[] {listener});

        // Set up security realm
        setSecurityRealm();

        String webdefault =
            new File(getConfiguration().getHome(), "etc/webdefault.xml").toURI().toString();

        // Deploy WAR deployables
        for (Deployable deployable : getConfiguration().getDeployables())
        {
            // Only deploy WARs.
            if (deployable.getType() == DeployableType.WAR)
            {
                Object webapp = getServer().getClass().getMethod("addWebApplication",
                    new Class[] {String.class, String.class}).invoke(getServer(),
                        new Object[] {"/" + ((WAR) deployable).getContext(), deployable.getFile()});
                webapp.getClass().getMethod("setDefaultsDescriptor", String.class)
                    .invoke(webapp, webdefault);
                performExtraSetupOnDeployable(webapp);
            }
            else
            {
                throw new ContainerException("Only WAR archives are supported for deployment in "
                    + "Jetty. Got [" + deployable.getFile() + "]");
            }
        }

        // Deploy CPC. Note: The Jetty Server class offers a isStarted() method but there is no
        // isStopped() so until we find a better way, we need a CPC.
        getServer().getClass().getMethod("addWebApplication",
            new Class[] {String.class, String.class}).invoke(getServer(),
                new Object[] {"/cargocpc", new File(getConfiguration().getHome(),
                    "cargocpc.war").getPath()});

        JettyExecutorThread jettyRunner = new JettyExecutorThread(getServer(), true);
        jettyRunner.setLogger(getLogger());
        jettyRunner.start();
    }

    /**
     * Allow extending classes to perform extra setup on the web application object.
     * 
     * @param webapp the Jetty web application object representing the WAR that has been added to be
     * deployed
     * @throws Exception in case of error
     */
    protected abstract void performExtraSetupOnDeployable(Object webapp) throws Exception;

    /**
     * Defines a security realm and adds defined users to it. If a user has specified the standard
     * ServletPropertySet.USERS property, then we try and turn these into an in-memory default
     * realm, and then set that realm on all of the webapps.
     * 
     * TODO: this is not ideal. We need a way to specify N named realms to the server so that
     * individual webapps can find their appropriate realms by name.
     * 
     * @throws Exception in case of error
     */
    protected void setSecurityRealm() throws Exception
    {
        if (!getConfiguration().getUsers().isEmpty())
        {
            Class realmClass = getClassLoader().loadClass("org.mortbay.http.HashUserRealm");
            Object defaultRealm = realmClass.getConstructor(
                new Class[] {String.class}).newInstance(new Object[] {
                    getConfiguration().getPropertyValue(JettyPropertySet.REALM_NAME)});

            for (User user : getConfiguration().getUsers())
            {
                defaultRealm.getClass().getMethod("put",
                    new Class[] {Object.class, Object.class}).invoke(defaultRealm,
                        new Object[] {user.getName(), user.getPassword()});

                for (String role : user.getRoles())
                {
                    defaultRealm.getClass().getMethod("addUserToRole",
                        new Class[] {String.class, String.class}).invoke(
                            defaultRealm, new Object[] {user.getName(), role});
                }
            }

            // Add newly created realm to server
            getServer().getClass().getMethod("addRealm",
                new Class[] {getClassLoader().loadClass("org.mortbay.http.UserRealm")})
                    .invoke(getServer(), new Object[] {defaultRealm});
        }
    }
}
