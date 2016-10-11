/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.eagle.app.service.impl;

import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.typesafe.config.Config;
import org.apache.eagle.alert.metadata.IMetadataDao;
import org.apache.eagle.app.Application;
import org.apache.eagle.app.service.ApplicationManagementService;
import org.apache.eagle.app.service.ApplicationOperations;
import org.apache.eagle.app.service.ApplicationOperationContext;
import org.apache.eagle.app.service.ApplicationProviderService;
import org.apache.eagle.app.spi.ApplicationProvider;
import org.apache.eagle.metadata.exceptions.ApplicationWrongStatusException;
import org.apache.eagle.metadata.exceptions.EntityNotFoundException;
import org.apache.eagle.metadata.model.*;
import org.apache.eagle.metadata.service.ApplicationEntityService;
import org.apache.eagle.metadata.service.SiteEntityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Singleton
public class ApplicationManagementServiceImpl implements ApplicationManagementService {
    private final SiteEntityService siteEntityService;
    private final ApplicationProviderService applicationProviderService;
    private final ApplicationEntityService applicationEntityService;
    private final IMetadataDao alertMetadataService;
    private final Config config;
    private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationManagementServiceImpl.class);

    @Inject
    public ApplicationManagementServiceImpl(
        Config config,
        SiteEntityService siteEntityService,
        ApplicationProviderService applicationProviderService,
        ApplicationEntityService applicationEntityService,
        IMetadataDao alertMetadataService) {
        this.config = config;
        this.siteEntityService = siteEntityService;
        this.applicationProviderService = applicationProviderService;
        this.applicationEntityService = applicationEntityService;
        this.alertMetadataService = alertMetadataService;
    }

    @Override
    public ApplicationEntity install(ApplicationOperations.InstallOperation operation) throws EntityNotFoundException {
        Preconditions.checkNotNull(operation.getSiteId(), "siteId is null");
        Preconditions.checkNotNull(operation.getAppType(), "appType is null");
        SiteEntity siteEntity = siteEntityService.getBySiteId(operation.getSiteId());
        Preconditions.checkNotNull(siteEntity, "Site with ID: " + operation.getSiteId() + " is not found");
        ApplicationDesc appDesc = applicationProviderService.getApplicationDescByType(operation.getAppType());
        Preconditions.checkNotNull("Application with TYPE: " + operation.getAppType() + " is not found");
        ApplicationEntity applicationEntity = new ApplicationEntity();
        applicationEntity.setDescriptor(appDesc);
        applicationEntity.setSite(siteEntity);
        applicationEntity.setMode(operation.getMode());
        applicationEntity.setJarPath(operation.getJarPath() == null ? appDesc.getJarPath() : operation.getJarPath());
        applicationEntity.ensureDefault();

        // Calculate application config based on:
        //
        //  1) default values in metadata.xml.
        //  2) user's config value override default configurations.
        //  3) fill runtime information, for example siteId, mode, appId in ApplicationOperationContext.

        Map<String, Object> appConfig = new HashMap<>();
        ApplicationProvider provider = applicationProviderService.getApplicationProviderByType(operation.getAppType());

        ApplicationDesc applicationDesc = provider.getApplicationDesc();

        if (applicationDesc.getConfiguration() != null) {
            List<Property> propertyList = provider.getApplicationDesc().getConfiguration().getProperties();
            for (Property p : propertyList) {
                appConfig.put(p.getName(), p.getValue());
            }
            if (operation.getConfiguration() != null) {
                appConfig.putAll(operation.getConfiguration());
            }
        }
        applicationEntity.setConfiguration(appConfig);

        validateDependingApplicationInstalled(applicationEntity);

        ApplicationOperationContext applicationOperationContext = new ApplicationOperationContext(
            applicationProviderService.getApplicationProviderByType(applicationEntity.getDescriptor().getType()).getApplication(),
            applicationEntity, config, alertMetadataService);
        applicationOperationContext.onInstall();
        return applicationEntityService.create(applicationEntity);
    }

    private void validateDependingApplicationInstalled(ApplicationEntity applicationEntity) {
        if (applicationEntity.getDescriptor().getDependencies() != null) {
            for (ApplicationDependency dependency : applicationEntity.getDescriptor().getDependencies()) {
                if (dependency.isRequired() && applicationEntityService.getBySiteIdAndAppType(applicationEntity.getSite().getSiteId(), dependency.getType()) == null) {
                    throw new IllegalStateException("Required dependency " + dependency.toString() + " of " + applicationEntity.getDescriptor().getType() + " was not installed");
                }
            }
        }
    }

    @Override
    public ApplicationEntity uninstall(ApplicationOperations.UninstallOperation operation) throws ApplicationWrongStatusException {
        ApplicationEntity applicationEntity = applicationEntityService.getByUUIDOrAppId(operation.getUuid(), operation.getAppId());
        ApplicationOperationContext applicationOperationContext = new ApplicationOperationContext(
            applicationProviderService.getApplicationProviderByType(applicationEntity.getDescriptor().getType()).getApplication(),
            applicationEntity, config, alertMetadataService);

        ApplicationEntity.Status currentStatus = applicationEntity.getStatus();
        try {
            if (currentStatus == ApplicationEntity.Status.INITIALIZED || currentStatus == ApplicationEntity.Status.STOPPED) {
                applicationOperationContext.onUninstall();
                return applicationEntityService.delete(applicationEntity);
            } else {
                throw new ApplicationWrongStatusException("App: " + applicationEntity.getAppId() + " status is" + currentStatus + ", uninstall operation is not allowed");
            }
        } catch (Throwable throwable) {
            LOGGER.error(throwable.getMessage(), throwable);
            throw throwable;
        }
    }

    @Override
    public ApplicationEntity start(ApplicationOperations.StartOperation operation) throws ApplicationWrongStatusException {
        ApplicationEntity applicationEntity = applicationEntityService.getByUUIDOrAppId(operation.getUuid(), operation.getAppId());
        Application application = applicationProviderService.getApplicationProviderByType(applicationEntity.getDescriptor().getType()).getApplication();
        Preconditions.checkArgument(application.isExecutable(), "Application is not executable");

        ApplicationEntity.Status currentStatus = applicationEntity.getStatus();
        try {
            if (currentStatus == ApplicationEntity.Status.INITIALIZED || currentStatus == ApplicationEntity.Status.STOPPED) {
                ApplicationOperationContext applicationOperationContext = new ApplicationOperationContext(
                        application, applicationEntity, config, alertMetadataService);

                applicationOperationContext.onStart();
                //Only when topology submitted successfully can the state change to STARTING
                applicationEntityService.delete(applicationEntity);
                applicationEntity.setStatus(ApplicationEntity.Status.STARTING);
                return applicationEntityService.create(applicationEntity);
            } else {
                throw new ApplicationWrongStatusException("App: " + applicationEntity.getAppId() + " status is " + currentStatus + " start operation is not allowed");
            }
        } catch (ApplicationWrongStatusException e) {
            LOGGER.error(e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            LOGGER.error("Failed to start app " + applicationEntity.getAppId(), e);
            throw e;
        } catch (Throwable throwable) {
            LOGGER.error(throwable.getMessage(), throwable);
            throw throwable;
        }

    }

    @Override
    public ApplicationEntity stop(ApplicationOperations.StopOperation operation) throws ApplicationWrongStatusException {
        ApplicationEntity applicationEntity = applicationEntityService.getByUUIDOrAppId(operation.getUuid(), operation.getAppId());
        Application application = applicationProviderService.getApplicationProviderByType(applicationEntity.getDescriptor().getType()).getApplication();
        Preconditions.checkArgument(application.isExecutable(), "Application is not executable");

        ApplicationOperationContext applicationOperationContext = new ApplicationOperationContext(
                application, applicationEntity, config, alertMetadataService);
        ApplicationEntity.Status currentStatus = applicationEntity.getStatus();
        try {
            if (currentStatus == ApplicationEntity.Status.RUNNING) {
                applicationOperationContext.onStop();
                //stop -> directly killed
                applicationEntityService.delete(applicationEntity);
                applicationEntity.setStatus(ApplicationEntity.Status.STOPPING);
                return applicationEntityService.create(applicationEntity);
            } else {
                throw new ApplicationWrongStatusException("App: " + applicationEntity.getAppId() + " status is " + currentStatus + ", stop operation is not allowed.");
            }
        } catch (ApplicationWrongStatusException e) {
            LOGGER.error(e.getMessage(), e);
            throw e;
        } catch (RuntimeException e) {
            LOGGER.error("Failed to stop app " + applicationEntity.getAppId(), e);
            throw e;
        } catch (Throwable throwable) {
            LOGGER.error(throwable.getMessage(), throwable);
            throw throwable;
        }
    }

    @Override
    public ApplicationEntity.Status getStatus(ApplicationOperations.CheckStatusOperation operation)  {
        try {
            ApplicationEntity applicationEntity = applicationEntityService.getByUUIDOrAppId(operation.getUuid(), operation.getAppId());
            Application application = applicationProviderService.getApplicationProviderByType(applicationEntity.getDescriptor().getType()).getApplication();
            Preconditions.checkArgument(application.isExecutable(), "Application is not executable");

            ApplicationOperationContext applicationOperationContext = new ApplicationOperationContext(
                    application, applicationEntity, config, alertMetadataService);
            ApplicationEntity.Status topologyStatus = applicationOperationContext.getStatus();
            return topologyStatus;
        } catch (IllegalArgumentException e) {
            LOGGER.error("application id not exist", e);
            throw e;
        }
    }

}
