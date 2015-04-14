//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.jsr356.server.deploy;

import java.util.HashSet;
import java.util.Set;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.HandlesTypes;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.server.ServerApplicationConfig;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebAppFeature;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;

public class WebSocketFeature extends WebAppFeature
{
    public static final String ENABLE_KEY = "org.eclipse.jetty.websocket.jsr356";
    public static final String CLASSES_KEY = "org.eclipse.jetty.websocket.jsr356.classes";
    private static final Logger LOG = Log.getLogger(WebSocketFeature.class);

    public WebSocketFeature()
    {
        this(true);
    }
    
    public WebSocketFeature(boolean defaultEnable )
    {
        super(ENABLE_KEY,defaultEnable,
              WebSocketServerContainerInitializer.class.getName(),
              new String[]{"org.eclipse.jetty.websocket."},
              new String[]{"-org.eclipse.jetty.websocket."});
    }
    
    @Override
    protected boolean doEnableWebApp(WebAppContext webapp, boolean forceStart) throws Exception
    {
        ServletContext context = webapp.getServletContext();
        
        Set<Class<?>> c=(Set<Class<?>>)context.getAttribute(CLASSES_KEY);
        context.removeAttribute(CLASSES_KEY);
        
        if (!forceStart && (c==null || c.isEmpty()))
            return false;
        
        ContextHandler handler = ContextHandler.getContextHandler(context);

        if (handler == null)
        {
            throw new ServletException("Not running on Jetty, JSR-356 support unavailable");
        }

        if (!(handler instanceof ServletContextHandler))
        {
            throw new ServletException("Not running in Jetty ServletContextHandler, JSR-356 support unavailable");
        }

        ServletContextHandler jettyContext = (ServletContextHandler)handler;

        // Create the Jetty ServerContainer implementation
        ServerContainer jettyContainer = WebSocketFeature.configureContext(context,jettyContext);

        // Store a reference to the ServerContainer per javax.websocket spec 1.0 final section 6.4 Programmatic Server Deployment
        context.setAttribute(javax.websocket.server.ServerContainer.class.getName(),jettyContainer);

        // Establish the DecoratedObjectFactory thread local 
        // for various ServiceLoader initiated components to use.
        DecoratedObjectFactory instantiator = (DecoratedObjectFactory)context.getAttribute(DecoratedObjectFactory.ATTR);
        if (instantiator == null)
        {
            LOG.info("Using WebSocket local DecoratedObjectFactory - none found in ServletContext");
            instantiator = new DecoratedObjectFactory();
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("Found {} classes",c.size());
        }

        // Now process the incoming classes
        Set<Class<? extends Endpoint>> discoveredExtendedEndpoints = new HashSet<>();
        Set<Class<?>> discoveredAnnotatedEndpoints = new HashSet<>();
        Set<Class<? extends ServerApplicationConfig>> serverAppConfigs = new HashSet<>();

        filterClasses(c,discoveredExtendedEndpoints,discoveredAnnotatedEndpoints,serverAppConfigs);

        if (LOG.isDebugEnabled())
        {
            LOG.debug("Discovered {} extends Endpoint classes",discoveredExtendedEndpoints.size());
            LOG.debug("Discovered {} @ServerEndpoint classes",discoveredAnnotatedEndpoints.size());
            LOG.debug("Discovered {} ServerApplicationConfig classes",serverAppConfigs.size());
        }

        // Process the server app configs to determine endpoint filtering
        boolean wasFiltered = false;
        Set<ServerEndpointConfig> deployableExtendedEndpointConfigs = new HashSet<>();
        Set<Class<?>> deployableAnnotatedEndpoints = new HashSet<>();

        for (Class<? extends ServerApplicationConfig> clazz : serverAppConfigs)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("Found ServerApplicationConfig: {}",clazz);
            }
            try
            {
                ServerApplicationConfig config = clazz.newInstance();

                Set<ServerEndpointConfig> seconfigs = config.getEndpointConfigs(discoveredExtendedEndpoints);
                if (seconfigs != null)
                {
                    wasFiltered = true;
                    deployableExtendedEndpointConfigs.addAll(seconfigs);
                }

                Set<Class<?>> annotatedClasses = config.getAnnotatedEndpointClasses(discoveredAnnotatedEndpoints);
                if (annotatedClasses != null)
                {
                    wasFiltered = true;
                    deployableAnnotatedEndpoints.addAll(annotatedClasses);
                }
            }
            catch (InstantiationException | IllegalAccessException e)
            {
                throw new ServletException("Unable to instantiate: " + clazz.getName(),e);
            }
        }

        // Default behavior if nothing filtered
        if (!wasFiltered)
        {
            deployableAnnotatedEndpoints.addAll(discoveredAnnotatedEndpoints);
            // Note: it is impossible to determine path of "extends Endpoint" discovered classes
            deployableExtendedEndpointConfigs = new HashSet<>();
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("Deploying {} ServerEndpointConfig(s)",deployableExtendedEndpointConfigs.size());
        }
        // Deploy what should be deployed.
        for (ServerEndpointConfig config : deployableExtendedEndpointConfigs)
        {
            try
            {
                jettyContainer.addEndpoint(config);
            }
            catch (DeploymentException e)
            {
                throw new ServletException(e);
            }
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("Deploying {} @ServerEndpoint(s)",deployableAnnotatedEndpoints.size());
        }
        for (Class<?> annotatedClass : deployableAnnotatedEndpoints)
        {
            try
            {
                jettyContainer.addEndpoint(annotatedClass);
            }
            catch (DeploymentException e)
            {
                throw new ServletException(e);
            }
        }
        
        return true;
    }

    @SuppressWarnings("unchecked")
    private void filterClasses(Set<Class<?>> c, Set<Class<? extends Endpoint>> discoveredExtendedEndpoints, Set<Class<?>> discoveredAnnotatedEndpoints,
            Set<Class<? extends ServerApplicationConfig>> serverAppConfigs)
    {
        for (Class<?> clazz : c)
        {
            if (ServerApplicationConfig.class.isAssignableFrom(clazz))
            {
                serverAppConfigs.add((Class<? extends ServerApplicationConfig>)clazz);
            }

            if (Endpoint.class.isAssignableFrom(clazz))
            {
                discoveredExtendedEndpoints.add((Class<? extends Endpoint>)clazz);
            }
            
            ServerEndpoint endpoint = clazz.getAnnotation(ServerEndpoint.class);

            if (endpoint != null)
            {
                discoveredAnnotatedEndpoints.add(clazz);
            }
        }
    }

    /**
     * Jetty Native approach.
     * <p>
     * Note: this will add the Upgrade filter to the existing list, with no regard for order.  It will just be tacked onto the end of the list.
     */
    public static ServerContainer configureContext(ServletContextHandler context) throws ServletException
    {
        // Create Filter
        WebSocketUpgradeFilter filter = WebSocketUpgradeFilter.configureContext(context);
    
        // Create the Jetty ServerContainer implementation
        ServerContainer jettyContainer = new ServerContainer(filter,filter.getFactory(),context.getServer().getThreadPool());
        context.addBean(jettyContainer);
    
        // Store a reference to the ServerContainer per javax.websocket spec 1.0 final section 6.4 Programmatic Server Deployment
        context.setAttribute(javax.websocket.server.ServerContainer.class.getName(),jettyContainer);
    
        return jettyContainer;
    }

    /**
     * Servlet 3.1 approach.
     * <p>
     * This will use Servlet 3.1 techniques on the {@link ServletContext} to add a filter at the start of the filter chain.
     */
    public static ServerContainer configureContext(ServletContext context, ServletContextHandler jettyContext) throws ServletException
    {
        // Create Filter
        WebSocketUpgradeFilter filter = WebSocketUpgradeFilter.configureContext(context);
    
        // Create the Jetty ServerContainer implementation
        ServerContainer jettyContainer = new ServerContainer(filter,filter.getFactory(),jettyContext.getServer().getThreadPool());
        jettyContext.addBean(jettyContainer);
    
        // Store a reference to the ServerContainer per javax.websocket spec 1.0 final section 6.4 Programmatic Server Deployment
        context.setAttribute(javax.websocket.server.ServerContainer.class.getName(),jettyContainer);
    
        return jettyContainer;
    }
}
