/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSource, Inc.  All rights reserved.  http://www.mulesource.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.ra;

import org.mule.RegistryContext;
import org.mule.config.ConfigurationBuilder;
import org.mule.config.ConfigurationException;
import org.mule.config.spring.parsers.specific.URIBuilder;
import org.mule.impl.endpoint.EndpointURIEndpointBuilder;
import org.mule.impl.endpoint.MuleEndpointURI;
import org.mule.impl.model.ModelFactory;
import org.mule.umo.UMOComponent;
import org.mule.umo.UMOException;
import org.mule.umo.UMOManagementContext;
import org.mule.umo.endpoint.UMOEndpointBuilder;
import org.mule.umo.endpoint.UMOEndpointURI;
import org.mule.umo.endpoint.UMOImmutableEndpoint;
import org.mule.umo.model.UMOModel;
import org.mule.util.ClassUtils;
import org.mule.util.object.SingletonObjectFactory;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import javax.resource.NotSupportedException;
import javax.resource.ResourceException;
import javax.resource.spi.ActivationSpec;
import javax.resource.spi.BootstrapContext;
import javax.resource.spi.ResourceAdapter;
import javax.resource.spi.ResourceAdapterInternalException;
import javax.resource.spi.endpoint.MessageEndpointFactory;
import javax.transaction.xa.XAResource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * <code>MuleResourceAdapter</code> TODO
 */
public class MuleResourceAdapter implements ResourceAdapter, Serializable
{
    /**
     * Serial version
     */
    private static final long serialVersionUID = 5727648958127416509L;

    /**
     * logger used by this class
     */
    protected transient Log logger = LogFactory.getLog(this.getClass());

    protected transient UMOManagementContext managementContext;

    protected transient BootstrapContext bootstrapContext;
    protected MuleConnectionRequestInfo info = new MuleConnectionRequestInfo();
    protected final Map endpoints = new HashMap();
    protected String defaultJcaModelName;

    public MuleResourceAdapter()
    {
        RegistryContext.getOrCreateRegistry().getConfiguration().setSystemModelType(JcaModel.JCA_MODEL_TYPE);
    }

    private void readObject(ObjectInputStream ois) throws ClassNotFoundException, IOException
    {
        ois.defaultReadObject();
        this.logger = LogFactory.getLog(this.getClass());
    }

    /**
     * @see javax.resource.spi.ResourceAdapter#start(javax.resource.spi.BootstrapContext)
     */
    public void start(BootstrapContext bootstrapContext) throws ResourceAdapterInternalException
    {
        this.bootstrapContext = bootstrapContext;
        if (info.getConfigurations() != null)
        {

            ConfigurationBuilder builder = null;
            try
            {
                builder = (ConfigurationBuilder) ClassUtils.instanciateClass(info.getConfigurationBuilder(),
                    ClassUtils.NO_ARGS);

            }
            catch (Exception e)
            {
                throw new ResourceAdapterInternalException("Failed to instanciate configurationBuilder class: "
                                                           + info.getConfigurationBuilder(), e);
            }

            try
            {
                managementContext = builder.configure(info.getConfigurations());
            }
            catch (ConfigurationException e)
            {
                logger.error(e);
                throw new ResourceAdapterInternalException(
                    "Failed to load configurations: " + info.getConfigurations(), e);
            }
        }
    }

    /**
     * @see javax.resource.spi.ResourceAdapter#stop()
     */
    public void stop()
    {
        managementContext.dispose();
        managementContext = null;
        bootstrapContext = null;
    }

    /**
     * @return the bootstrap context for this adapter
     */
    public BootstrapContext getBootstrapContext()
    {
        return bootstrapContext;
    }

    /**
     * @see javax.resource.spi.ResourceAdapter#endpointActivation(javax.resource.spi.endpoint.MessageEndpointFactory,
     *      javax.resource.spi.ActivationSpec)
     */
    public void endpointActivation(MessageEndpointFactory endpointFactory, ActivationSpec activationSpec)
        throws ResourceException
    {
        if (activationSpec.getResourceAdapter() != this)
        {
            throw new ResourceException("ActivationSpec not initialized with this ResourceAdapter instance");
        }

        if (activationSpec.getClass().equals(MuleActivationSpec.class))
        {
            MuleActivationSpec muleActivationSpec = (MuleActivationSpec) activationSpec;
            try
            {
                // Resolve modelName
                String modelName = resolveModelName(muleActivationSpec);

                // Lookup/create JCA Model
                JcaModel model = getJcaModel(modelName);

                // Create Endpoint
                UMOImmutableEndpoint endpoint = createMessageInflowEndpoint(muleActivationSpec);

                // Create Component
                UMOComponent component = createJcaComponent(endpointFactory, model, endpoint);

                // Keep reference to JcaComponent descriptor for endpointDeactivation
                MuleEndpointKey key = new MuleEndpointKey(endpointFactory, muleActivationSpec);
                endpoints.put(key, component);
            }
            catch (Exception e)
            {
                logger.error(e.getMessage(), e);
            }
        }
        else
        {
            throw new NotSupportedException("That type of ActicationSpec not supported: " + activationSpec.getClass());
        }

    }

    /**
     * @see javax.resource.spi.ResourceAdapter#endpointDeactivation(javax.resource.spi.endpoint.MessageEndpointFactory,
     *      javax.resource.spi.ActivationSpec)
     */
    public void endpointDeactivation(MessageEndpointFactory endpointFactory, ActivationSpec activationSpec)
    {

        if (activationSpec.getClass().equals(MuleActivationSpec.class))
        {
            MuleActivationSpec muleActivationSpec = (MuleActivationSpec) activationSpec;
            MuleEndpointKey key = new MuleEndpointKey(endpointFactory, (MuleActivationSpec) activationSpec);
            UMOComponent component = (UMOComponent) endpoints.remove(key);
            if (component == null)
            {
                logger.warn("No endpoint was registered with key: " + key);
                return;
            }

            // Resolve modelName
            String modelName = null;
            try
            {
                modelName = resolveModelName(muleActivationSpec);
            }
            catch (ResourceException e)
            {
                logger.error(e.getMessage(), e);
            }

            try
            {
                managementContext.getRegistry().unregisterComponent(component.getName());
            }
            catch (UMOException e)
            {
                logger.error(e.getMessage(), e);
            }
        }
    }

    protected String resolveModelName(MuleActivationSpec activationSpec) throws ResourceException
    {
        // JCA specification mentions activationSpec properties inheriting
        // resourceAdaptor properties, but this doesn't seem to work, at
        // least with JBOSS, so do it manually.
        String modelName = activationSpec.getModelName();
        if (modelName == null)
        {
            modelName = defaultJcaModelName;
        }
        if (modelName == null)
        {
            throw new ResourceException(
                "The 'modelName' property has not been configured for either the MuleResourceAdaptor or MuleActicationSpec.");
        }
        return modelName;
    }

    protected JcaModel getJcaModel(String modelName) throws UMOException, ResourceException
    {
        UMOModel model = managementContext.getRegistry().lookupModel(modelName);
        if (model != null)
        {
            if (model instanceof JcaModel)
            {
                return (JcaModel) model;
            }
            else
            {
                throw new ResourceException("Model:-" + modelName + "  is not compatible with JCA type");
            }
        }
        else
        {
            JcaModel jcaModel = (JcaModel) ModelFactory.createModel(JcaModel.JCA_MODEL_TYPE);
            jcaModel.setName(modelName);
            managementContext.getRegistry().registerModel(jcaModel);
            return jcaModel;
        }
    }

    protected UMOComponent createJcaComponent(MessageEndpointFactory endpointFactory,
                                              JcaModel model,
                                              UMOImmutableEndpoint endpoint) throws UMOException
    {
        String name = "JcaComponent#" + endpointFactory.hashCode();
        UMOComponent component = new JcaComponent(new DelegateWorkManager(bootstrapContext.getWorkManager()));
        component.setName(name);
        component.getInboundRouter().addEndpoint(endpoint);

        // Set endpointFactory rather than endpoint here, so we can obtain a
        // new endpoint instance from factory for each incoming message in
        // JcaComponet as reccomended by JCA specification
        component.setServiceFactory(new SingletonObjectFactory(endpointFactory));
        component.setModel(model);
        managementContext.getRegistry().registerComponent(component);
        return component;
    }

    protected UMOImmutableEndpoint createMessageInflowEndpoint(MuleActivationSpec muleActivationSpec)
        throws UMOException
    {
        UMOEndpointURI uri = new MuleEndpointURI(muleActivationSpec.getEndpoint());
        UMOEndpointBuilder endpointBuilder = new EndpointURIEndpointBuilder(new URIBuilder(
            muleActivationSpec.getEndpoint()), managementContext);

        // Use asynchronous endpoint as we need to dispatch to component
        // rather than send.
        endpointBuilder.setSynchronous(false);

        return managementContext.getRegistry().lookupEndpointFactory().getInboundEndpoint(endpointBuilder);
    }

    /**
     * We only connect to one resource manager per ResourceAdapter instance, so any ActivationSpec will return
     * the same XAResource.
     * 
     * @see javax.resource.spi.ResourceAdapter#getXAResources(javax.resource.spi.ActivationSpec[])
     */
    public XAResource[] getXAResources(ActivationSpec[] activationSpecs) throws ResourceException
    {
        return new XAResource[]{};
    }

    /**
     * @return
     */
    public String getPassword()
    {
        return info.getPassword();
    }

    /**
     * @return
     */
    public String getConfigurations()
    {
        return info.getConfigurations();
    }

    /**
     * @return
     */
    public String getUserName()
    {
        return info.getUserName();
    }

    /**
     * @param password
     */
    public void setPassword(String password)
    {
        info.setPassword(password);
    }

    /**
     * @param configurations
     */
    public void setConfigurations(String configurations)
    {
        info.setConfigurations(configurations);
    }

    /**
     * @param userid
     */
    public void setUserName(String userid)
    {
        info.setUserName(userid);
    }

    public String getConfigurationBuilder()
    {
        return info.getConfigurationBuilder();
    }

    public void setConfigurationBuilder(String configbuilder)
    {
        info.setConfigurationBuilder(configbuilder);
    }

    /**
     * @return Returns the info.
     */
    public MuleConnectionRequestInfo getInfo()
    {
        return info;
    }

    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof MuleResourceAdapter))
        {
            return false;
        }

        final MuleResourceAdapter muleResourceAdapter = (MuleResourceAdapter) o;

        if (info != null ? !info.equals(muleResourceAdapter.info) : muleResourceAdapter.info != null)
        {
            return false;
        }

        return true;
    }

    public int hashCode()
    {
        return (info != null ? info.hashCode() : 0);
    }

    public String getModelName()
    {
        return defaultJcaModelName;
    }

    public void setModelName(String modelName)
    {
        this.defaultJcaModelName = modelName;
    }

}
