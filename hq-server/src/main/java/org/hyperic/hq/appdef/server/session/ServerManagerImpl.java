/*
 * NOTE: This copyright does *not* cover user programs that use HQ
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 * "derived work".
 *
 * Copyright (C) [2004-2009], Hyperic, Inc.
 * This file is part of HQ.
 *
 * HQ is free software; you can redistribute it and/or modify
 * it under the terms version 2 of the GNU General Public License as
 * published by the Free Software Foundation. This program is distributed
 * in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 * USA.
 */

package org.hyperic.hq.appdef.server.session;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.ObjectNotFoundException;
import org.hyperic.hq.appdef.AppService;
import org.hyperic.hq.appdef.shared.AppdefDuplicateNameException;
import org.hyperic.hq.appdef.shared.AppdefEntityID;
import org.hyperic.hq.appdef.shared.ApplicationNotFoundException;
import org.hyperic.hq.appdef.shared.CPropManager;
import org.hyperic.hq.appdef.shared.ConfigManager;
import org.hyperic.hq.appdef.shared.InvalidAppdefTypeException;
import org.hyperic.hq.appdef.shared.PlatformNotFoundException;
import org.hyperic.hq.appdef.shared.ServerManager;
import org.hyperic.hq.appdef.shared.ServerNotFoundException;
import org.hyperic.hq.appdef.shared.ServerTypeValue;
import org.hyperic.hq.appdef.shared.ServerValue;
import org.hyperic.hq.appdef.shared.ServiceManager;
import org.hyperic.hq.appdef.shared.ServiceNotFoundException;
import org.hyperic.hq.appdef.shared.UpdateException;
import org.hyperic.hq.appdef.shared.ValidationException;
import org.hyperic.hq.authz.server.session.AuthzSubject;
import org.hyperic.hq.authz.shared.AuthzConstants;
import org.hyperic.hq.authz.shared.AuthzSubjectManager;
import org.hyperic.hq.authz.shared.PermissionException;
import org.hyperic.hq.authz.shared.PermissionManager;
import org.hyperic.hq.authz.shared.ResourceGroupManager;
import org.hyperic.hq.authz.shared.ResourceManager;
import org.hyperic.hq.common.NotFoundException;
import org.hyperic.hq.common.SystemException;
import org.hyperic.hq.common.VetoException;
import org.hyperic.hq.common.server.session.Audit;
import org.hyperic.hq.common.server.session.ResourceAuditFactory;
import org.hyperic.hq.common.shared.AuditManager;
import org.hyperic.hq.inventory.domain.OperationType;
import org.hyperic.hq.inventory.domain.Resource;
import org.hyperic.hq.inventory.domain.ResourceGroup;
import org.hyperic.hq.inventory.domain.ResourceType;
import org.hyperic.hq.measurement.shared.MeasurementManager;
import org.hyperic.hq.product.ServerTypeInfo;
import org.hyperic.hq.reference.RelationshipTypes;
import org.hyperic.hq.zevents.ZeventEnqueuer;
import org.hyperic.util.ArrayUtil;
import org.hyperic.util.StringUtil;
import org.hyperic.util.pager.PageControl;
import org.hyperic.util.pager.PageList;
import org.hyperic.util.pager.Pager;
import org.hyperic.util.pager.SortAttribute;
import org.neo4j.graphdb.RelationshipType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * This class is responsible for managing Server objects in appdef and their
 * relationships
 */
@org.springframework.stereotype.Service
@Transactional
public class ServerManagerImpl implements ServerManager {

    private static final String AUTO_INVENTORY_IDENTIFIER = "autoInventoryIdentifier";

    private static final String INSTALL_PATH = "installPath";

    private final Log log = LogFactory.getLog(ServerManagerImpl.class);

    private static final String VALUE_PROCESSOR = "org.hyperic.hq.appdef.server.session.PagerProcessor_server";
    private Pager valuePager;
    

    private PermissionManager permissionManager;
    private ResourceManager resourceManager;
    private ServiceManager serviceManager;
    private CPropManager cpropManager;
    private ConfigManager configManager;
    private MeasurementManager measurementManager;
    private AuditManager auditManager;
    private AuthzSubjectManager authzSubjectManager;
    private ResourceGroupManager resourceGroupManager;
    private ZeventEnqueuer zeventManager;
    private ResourceAuditFactory resourceAuditFactory;

    @Autowired
    public ServerManagerImpl(PermissionManager permissionManager,  ResourceManager resourceManager,
                             ServiceManager serviceManager, CPropManager cpropManager, ConfigManager configManager,
                             MeasurementManager measurementManager, AuditManager auditManager,
                             AuthzSubjectManager authzSubjectManager, ResourceGroupManager resourceGroupManager,
                             ZeventEnqueuer zeventManager, ResourceAuditFactory resourceAuditFactory) {

        this.permissionManager = permissionManager;
        this.resourceManager = resourceManager;
        this.serviceManager = serviceManager;
        this.cpropManager = cpropManager;
        this.configManager = configManager;
        this.measurementManager = measurementManager;
        this.auditManager = auditManager;
        this.authzSubjectManager = authzSubjectManager;
        this.resourceGroupManager = resourceGroupManager;
        this.zeventManager = zeventManager;
        this.resourceAuditFactory = resourceAuditFactory;
    }

   
    /**
     * Validate a server value object which is to be created on this platform.
     * This method will check IP conflicts and any other special constraint
     * required to succesfully add a server instance to a platform
     */
    private void validateNewServer(Resource p, Resource server) throws ValidationException {
        //TODO this more or less gets done when creating the relationship b/w Resources
        // ensure the server value has a server type
        String msg = null;
        if (server.getType() == null) {
            msg = "Server has no ServerType";
        } else if (server.getId() != null) {
            msg = "This server is not new, it has ID:" + server.getId();
        }
        if (msg == null) {
            Integer id = server.getType().getId();
            Collection<ResourceType> stypes = p.getType().getResourceTypesFrom(RelationshipTypes.SERVER_TYPE);
            for (ResourceType sVal : stypes) {

                if (sVal.getId().equals(id))
                    return;
            }
            msg = "Servers of type '" + server.getType().getName() +
                  "' cannot be created on platforms of type '" + p.getType().getName() + "'";
        }
        if (msg != null) {
            throw new ValidationException(msg);
        }
    }

    /**
     * Filter a list of {@link Server}s by their viewability by the subject
     */
    protected List<Resource> filterViewableServers(Collection<Resource> servers, AuthzSubject who) {

        List<Resource> res = new ArrayList<Resource>();
        ResourceType type;
        OperationType op;

        try {
            //TODO model op?
            //type = resourceManager.findResourceTypeByName(AuthzConstants.serverResType);
            //op = getOperationByName(type, AuthzConstants.serverOpViewServer);
        } catch (Exception e) {
            throw new SystemException("Internal error", e);
        }

        Integer typeId = type.getId();

        for (Resource s : servers) {

            try {
                permissionManager.check(who.getId(), typeId, s.getId(), op.getId());
                res.add(s);
            } catch (PermissionException e) {
                // Ok
            }
        }
        return res;
    }

    /**
     * Validate a server value object which is to be created on this platform.
     * This method will check IP conflicts and any other special constraint
     * required to succesfully add a server instance to a platform
     */
    private void validateNewServer(Resource p, ServerValue sv) throws ValidationException {
        //TODO above validation already done when creating new Resource
        // ensure the server value has a server type
        String msg = null;
        if (sv.getServerType() == null) {
            msg = "Server has no ServiceType";
        } else if (sv.idHasBeenSet()) {
            msg = "This server is not new, it has ID:" + sv.getId();
        }
        if (msg == null) {
            Integer id = sv.getServerType().getId();
            Collection<ResourceType> stypes = p.getType().getResourceTypesFrom(RelationshipTypes.SERVER_TYPE);
            for (ResourceType sVal : stypes) {

                if (sVal.getId().equals(id)) {
                    return;
                }
            }
            msg = "Servers of type '" + sv.getServerType().getName() + "' cannot be created on platforms of type '" +
                  p.getType().getName() + "'";
        }
        if (msg != null) {
            throw new ValidationException(msg);
        }
    }

    /**
     * Construct the new name of the server to be cloned to the target platform
     */
    private String getTargetServerName(Resource targetPlatform, Resource serverToClone) {

        String prefix = serverToClone.getResourceTo(RelationshipTypes.PLATFORM).getName();
        String oldServerName = serverToClone.getName();
        String newServerName = StringUtil.removePrefix(oldServerName, prefix);

        if (newServerName.equals(oldServerName)) {
            // old server name may not contain the canonical host name
            // of the platform. try to get just the host name
            int dotIndex = prefix.indexOf(".");
            if (dotIndex > 0) {
                prefix = prefix.substring(0, dotIndex);
                newServerName = StringUtil.removePrefix(oldServerName, prefix);
            }
        }

        newServerName = targetPlatform.getName() + " " + newServerName;

        return newServerName;
    }

    /**
     * Clone a Server to a target Platform
     * 
     */
    public Resource cloneServer(AuthzSubject subject, Resource targetPlatform, Resource serverToClone)
        throws ValidationException, PermissionException, VetoException, NotFoundException {
        Resource s = null;
        // See if we already have this server type
        for (Resource server : targetPlatform.getResourcesTo(RelationshipTypes.SERVER)) {

            if (server.getType().equals(serverToClone.getType())) {
                // Do nothing if it's a Network server
                if (server.getType().getName().equals("NetworkServer")) {
                    return null;
                }
                // HQ-1657: virtual servers are not deleted. clone all other
                // servers
                //TODO seriously what is a virtual server?
                //if (server.getType().isVirtual()) {
                   // s = server;
                    //break;
                //}
            }
        }
        Resource resource = serverToClone;
        byte[] productResponse = configManager.toConfigResponse(resource.getProductConfig());
        byte[] measResponse = configManager.toConfigResponse(resource.getMeasurementConfig());
        byte[] controlResponse = configManager.toConfigResponse(resource.getControlConfig());
        byte[] rtResponse = configManager.toConfigResponse(resource.getResponseTimeConfig());

        if (s == null) {
            
            s = new Resource();
            s.setName(getTargetServerName(targetPlatform, serverToClone));
            s.setDescription(serverToClone.getDescription());
            s.setProperty(INSTALL_PATH,serverToClone.getProperty(INSTALL_PATH));
            String aiid = (String)serverToClone.getProperty(AUTO_INVENTORY_IDENTIFIER);
            if (aiid != null) {
                s.setProperty(AUTO_INVENTORY_IDENTIFIER,serverToClone.getProperty(AUTO_INVENTORY_IDENTIFIER));
            } else {
                // Server was created by hand, use a generated AIID. (This
                // matches
                // the behaviour in 2.7 and prior)
                aiid = serverToClone.getProperty(INSTALL_PATH) + "_" + System.currentTimeMillis() + "_" +
                       serverToClone.getName();
                s.setProperty(AUTO_INVENTORY_IDENTIFIER,aiid);
            }
            s.setServicesAutomanaged(serverToClone.isServicesAutomanaged());
            s.setRuntimeAutodiscovery(serverToClone.isRuntimeAutodiscovery());
            s.setWasAutodiscovered(serverToClone.isWasAutodiscovered());
            s.setAutodiscoveryZombie(false);
            s.setLocation(serverToClone.getLocation());
            s.setModifiedBy(serverToClone.getModifiedBy());
            configManager.createConfigResponse(s.getId(),productResponse, measResponse,
                controlResponse, rtResponse);
            

            Integer stid = serverToClone.getType().getId();

            ResourceType st = ResourceType.findResourceType(stid);
            s.setType(st);
            validateNewServer(targetPlatform, s);
            s.persist();
            // Add server to parent collection
            targetPlatform.relateTo(s,RelationshipTypes.SERVER);

            createAuthzServer(subject, s);

            // Send resource create event
            ResourceCreatedZevent zevent = new ResourceCreatedZevent(subject, s.getId());
            zeventManager.enqueueEventAfterCommit(zevent);
        } else {
            boolean wasUpdated = configManager.configureResponse(subject, s.getEntityId(),
                productResponse, measResponse,controlResponse, rtResponse,
                null, true);
            if (wasUpdated) {
                resourceManager.resourceHierarchyUpdated(subject, Collections.singletonList(s));
            }

            // Scrub the services
            Resource[] services = (Resource[]) s.getResourcesFrom(RelationshipTypes.SERVICE).toArray(new Resource[0]);
            for (int i = 0; i < services.length; i++) {
                Resource svc = services[i];

                if (!svc.getType().getName().equals("CPU")) {
                    serviceManager.removeService(subject, svc);
                }
            }
        }

        return s;
    }

    /**
     * Get the scope of viewable servers for a given user
     * @param whoami - the user
     * @return List of ServerPK's for which subject has
     *         AuthzConstants.serverOpViewServer
     */
    protected List<Integer> getViewableServers(AuthzSubject whoami) throws PermissionException, NotFoundException {
        if (log.isDebugEnabled()) {
            log.debug("Checking viewable servers for subject: " + whoami.getName());
        }

        OperationType op = getOperationByName(resourceManager.findResourceTypeByName(AuthzConstants.serverResType),
            AuthzConstants.serverOpViewServer);
        List<Integer> idList = permissionManager.findOperationScopeBySubject(whoami, op.getId());

        if (log.isDebugEnabled()) {
            log.debug("There are: " + idList.size() + " viewable servers");
        }
        List<Integer> keyList = new ArrayList<Integer>(idList.size());
        for (int i = 0; i < idList.size(); i++) {
            keyList.add(idList.get(i));
        }
        return keyList;
    }

    /**
     * Move a Server to the given Platform
     * 
     * @param subject The user initiating the move.
     * @param target The target
     *        {@link org.hyperic.hq.appdef.server.session.Server} to move.
     * @param destination The destination {@link Platform}.
     * 
     * @throws org.hyperic.hq.authz.shared.PermissionException If the passed
     *         user does not have permission to move the Server.
     * @throws org.hyperic.hq.common.VetoException If the operation canot be
     *         performed due to incompatible types.
     * 
     * 
     */
    public void moveServer(AuthzSubject subject, Resource target, Resource destination) throws VetoException,
        PermissionException {

        try {
            // Permission checking on destination

            permissionManager.checkPermission(subject, resourceManager
                .findResourceTypeByName(AuthzConstants.platformResType), destination.getId(),
                AuthzConstants.platformOpAddServer);

            // Permission check on target
            permissionManager.checkPermission(subject, resourceManager
                .findResourceTypeByName(AuthzConstants.serverResType), target.getId(),
                AuthzConstants.serverOpRemoveServer);
        } catch (NotFoundException e) {
            throw new VetoException("Caught NotFoundException checking permission: " + e.getMessage()); // notgonnahappen
        }

        // Ensure target can be moved to the destination
        if (!destination.getType().getResourceTypesFrom(RelationshipTypes.SERVER_TYPE).contains(target.getType())) {
            throw new VetoException("Incompatible resources passed to move(), " + "cannot move server of type " +
                                    target.getType().getName() + " to " + destination.getType().getName());

        }

        // Unschedule measurements
        measurementManager.disableMeasurements(subject, target);

        // Reset Server parent id
        target.removeRelationship(target.getResourceTo(RelationshipTypes.SERVER),RelationshipTypes.SERVER);
        destination.relateTo(target, RelationshipTypes.SERVER);

        // Move Authz resource.
        resourceManager.moveResource(subject, target, destination);

      

        // Reschedule metrics
        ResourceUpdatedZevent zevent = new ResourceUpdatedZevent(subject, target.getId());
        zeventManager.enqueueEventAfterCommit(zevent);

        // Must also move all dependent services so that ancestor edges are
        // rebuilt and that service metrics are re-scheduled
        ArrayList<Resource> services = new ArrayList<Resource>(); // copy list
        // since the
        // move will
        // modify the
        // server
        // collection.
        services.addAll(target.getResourcesFrom(RelationshipTypes.SERVICE));

        for (Resource s : services) {

            serviceManager.moveService(subject, s, target);
        }
    }
    
    private ServerTypeValue getServerTypeValue(ResourceType serverType) {
        //TODO
        return null;
    }
    
    private Resource create(ServerValue sv, Resource p) {
        //ConfigResponseDB configResponse = configResponseDAO.create();
        
        Resource s = new Resource();
        s.setName(sv.getName());
        s.setDescription(sv.getDescription());
        s.setInstallPath(sv.getInstallPath());
        String aiid = sv.getAutoinventoryIdentifier();
        if (aiid != null) {
            s.setAutoinventoryIdentifier(sv.getAutoinventoryIdentifier());
        } else {
            // Server was created by hand, use a generated AIID. (This matches
            // the behaviour in 2.7 and prior)
            aiid = sv.getInstallPath() + "_" + System.currentTimeMillis() + "_" + sv.getName();
            s.setAutoinventoryIdentifier(aiid);
        }
      
        s.setServicesAutomanaged(sv.getServicesAutomanaged());
        s.setRuntimeAutodiscovery(sv.getRuntimeAutodiscovery());
        s.setWasAutodiscovered(sv.getWasAutodiscovered());
        s.setAutodiscoveryZombie(false);
        s.setLocation(sv.getLocation());
        s.setModifiedBy(sv.getModifiedBy());
        s.persist();
        //TODO
        //s.setConfigResponse(configResponse);
        p.relateTo(s,RelationshipTypes.SERVER);
       
        Integer stid = sv.getServerType().getId();
        ResourceType st = ResourceType.findResourceType(stid);
        s.setType(st);
        return s;
   }

    /**
     * Create a Server on the given platform.
     * 
     * @return ServerValue - the saved value object
     * 
     * 
     */
    public Resource createServer(AuthzSubject subject, Integer platformId, Integer serverTypeId, ServerValue sValue)
        throws ValidationException, PermissionException, PlatformNotFoundException, AppdefDuplicateNameException,
        NotFoundException {
        try {
            trimStrings(sValue);
            
            permissionManager
            .checkPermission(subject, resourceManager.findResourceTypeByName(AuthzConstants.platformResType), platformId, 
                AuthzConstants.platformOpAddServer);

            Resource platform = Resource.findResource(platformId);
            ResourceType serverType = ResourceType.findResourceType(serverTypeId);

            sValue.setServerType(getServerTypeValue(serverType));
            sValue.setOwner(subject.getName());
            sValue.setModifiedBy(subject.getName());

            // validate the object
            validateNewServer(platform, sValue);

            // create it
            Resource server = create(sValue, platform);

            // Send resource create event
            ResourceCreatedZevent zevent = new ResourceCreatedZevent(subject, server.getId());
            zeventManager.enqueueEventAfterCommit(zevent);

            return server;
            // } catch (CreateException e) {
            // throw e;
        } catch (NotFoundException e) {
            throw new NotFoundException("Unable to find platform=" + platformId + " or server type=" + serverTypeId +
                                        ":" + e.getMessage());
        }
    }

  
    /**
     * A removeServer method that takes a ServerLocal. Used by
     * PlatformManager.removePlatform when cascading removal to servers.
     * 
     */
    public void removeServer(AuthzSubject subject, Resource server) throws PermissionException, VetoException {
        final Resource r = server;
        final Audit audit = resourceAuditFactory.deleteResource(resourceManager
            .findResourceById(AuthzConstants.authzHQSystem), subject, 0, 0);
        boolean pushed = false;

        try {
            auditManager.pushContainer(audit);
            pushed = true;
            //TODO virtual?
            //if (!server.getType().isVirtual()) {
              //  permissionManager.checkRemovePermission(subject, server.getEntityId());
            //}

            // Service manager will update the collection, so we need to copy

            Collection<Resource> services = server.getResourcesFrom(RelationshipTypes.SERVICE);
            synchronized (services) {
                for (final Iterator<Resource> i = services.iterator(); i.hasNext();) {
                    try {
                        Resource service = i.next();
                        final String currAiid = (String)service.getProperty(AUTO_INVENTORY_IDENTIFIER);
                        final Integer id = service.getId();
                        // ensure aiid remains unique
                        service.setProperty(AUTO_INVENTORY_IDENTIFIER,id + currAiid);
                        //TODO double check removing the service will remove relationship to server and ResourceType.resources()
                        service.remove();
                    } catch (ServiceNotFoundException e) {
                        log.warn(e);
                    }
                }
            }

           

            // Keep config response ID so it can be deleted later.
            //final ConfigResponseDB config = server.getConfigResponse();
            //TODO double check removing the service will remove relationship to server and ResourceType.resources()
            server.remove();

            //TODO?
            // Remove the config response
            //if (config != null) {
              //  configResponseDAO.remove(config);
           // }
            //cpropManager.deleteValues(aeid.getType(), aeid.getID());

            // TODO the below is only thing we need to do?Remove authz resource
            resourceManager.removeAuthzResource(subject, aeid, r);

           
        } finally {
            if (pushed) {
                auditManager.popContainer(true);
            }
        }
    }

   

    /**
     * Find all server types
     * @return list of serverTypeValues
     * 
     */
    @Transactional(readOnly=true)
    public PageList<ServerTypeValue> getAllServerTypes(AuthzSubject subject, PageControl pc) {
        // valuePager converts local/remote interfaces to value objects
        // as it pages through them.
        //TODO order by name
        return valuePager.seek(getAllServerTypes(), pc);
    }
    
    private Set<ResourceType> getAllServerTypes() {
        Set<ResourceType> serverTypes = new HashSet<ResourceType>();
        Collection<ResourceType> platformTypes = ResourceType.findRootResourceType().getResourceTypesFrom(RelationshipTypes.PLATFORM_TYPE);
        for(ResourceType platformType:platformTypes) {
            serverTypes.addAll(platformType.getResourceTypesFrom(RelationshipTypes.SERVER_TYPE));
        }
        return serverTypes;
    }

    /**
     * 
     */
    @Transactional(readOnly=true)
    public Resource getServerByName(Resource host, String name) {
         Collection<Resource> servers = host.getResourcesFrom(RelationshipTypes.SERVER);
         for(Resource server: servers) {
             if(server.getName().equals(name)) {
                 return server;
             }
         }
         return null;
    }
    
    private Collection<ResourceType> getServerTypes(final List serverIds, final boolean asc) {
        //TODO from ServerDAO
        return null;
    }

    /**
     * Find viewable server types
     * @return list of serverTypeValues
     * 
     */
    @Transactional(readOnly=true)
    public PageList<ServerTypeValue> getViewableServerTypes(AuthzSubject subject, PageControl pc)
        throws PermissionException, NotFoundException {
        // build the server types from the visible list of servers
        final List<Integer> authzPks = getViewableServers(subject);
        final Collection<ResourceType> serverTypes =getServerTypes(authzPks, true);
        // valuePager converts local/remote interfaces to value objects
        // as it pages through them.
        return valuePager.seek(serverTypes, pc);
    }

    /**
     * Find viewable server non-virtual types for a platform
     * @return list of serverTypeValues
     * 
     */
    @Transactional(readOnly=true)
    public PageList<ServerTypeValue> getServerTypesByPlatform(AuthzSubject subject, Integer platId, PageControl pc)
        throws PermissionException, PlatformNotFoundException, ServerNotFoundException {
        return getServerTypesByPlatform(subject, platId, true, pc);
    }

    /**
     * Find viewable server types for a platform
     * @return list of serverTypeValues
     * 
     */
    @Transactional(readOnly=true)
    public PageList<ServerTypeValue> getServerTypesByPlatform(AuthzSubject subject, Integer platId,
                                                              boolean excludeVirtual, PageControl pc)
        throws PermissionException, PlatformNotFoundException, ServerNotFoundException {

        // build the server types from the visible list of servers
        Collection<Resource> servers = getServersByPlatformImpl(subject, platId, APPDEF_RES_TYPE_UNDEFINED,
            excludeVirtual, pc);

        Collection<AppdefResourceType> serverTypes = filterResourceTypes(servers);

        // valuePager converts local/remote interfaces to value objects
        // as it pages through them.
        return valuePager.seek(serverTypes, pc);
    }

    /**
     * Find all ServerTypes for a givent PlatformType id.
     * 
     * This can go once we begin passing POJOs to the UI layer.
     * 
     * @return A list of ServerTypeValue objects for thie PlatformType.
     * 
     */
    @Transactional(readOnly=true)
    public PageList<ServerTypeValue> getServerTypesByPlatformType(AuthzSubject subject, Integer platformTypeId,
                                                                  PageControl pc) throws PlatformNotFoundException {
        ResourceType platType = ResourceType.findResourceType(platformTypeId);

        Collection<ResourceType> serverTypes = platType.getResourceTypesFrom(RelationshipTypes.SERVER_TYPE);

        return valuePager.seek(serverTypes, pc);
    }
    
    private Resource findServerByAIID(Resource platform, String aiid) {
        Collection<Resource> servers = platform.getResourcesFrom(RelationshipTypes.SERVER);
        for(Resource server: servers) {
            if(server.getProperty(AUTO_INVENTORY_IDENTIFIER).equals(aiid)) {
                return server;
            }
        }
        return null;
    }

    /**
     * 
     */
    @Transactional(readOnly=true)
    public Resource findServerByAIID(AuthzSubject subject, Resource platform, String aiid) throws PermissionException {
        permissionManager.checkViewPermission(subject, platform.getId());
        return findServerByAIID(platform, aiid);
    }

    /**
     * Find a Server by Id.
     * 
     */
    @Transactional(readOnly=true)
    public Resource findServerById(Integer id) throws ServerNotFoundException {
        Resource server = getServerById(id);

        if (server == null) {
            throw new ServerNotFoundException(id);
        }

        return server;
    }

    /**
     * Get a Server by Id.
     * 
     * @return The Server with the given id, or null if not found.
     */
    @Transactional(readOnly=true)
    public Resource getServerById(Integer id) {
        return Resource.findResource(id);
    }

    /**
     * Find a ServerType by id
     * 
     */
    @Transactional(readOnly=true)
    public ResourceType findServerType(Integer id) {
        return ResourceType.findResourceType(id);
    }

    /**
     * Find a server type by name
     * @param name - the name of the server
     * @return ServerTypeValue
     * 
     */
    @Transactional(readOnly=true)
    public ResourceType findServerTypeByName(String name) throws NotFoundException {
        ResourceType type = ResourceType.findResourceTypeByName(name);
        if (type == null) {
            throw new NotFoundException("name not found: " + name);
        }
        return type;
    }
    
    private List<Resource> findByPlatformAndTypeOrderName(Resource platform, ResourceType st) {
        List<Resource> servers = new ArrayList<Resource>();
        Collection<Resource> relatedServers = platform.getResourcesFrom(RelationshipTypes.SERVER);
        for(Resource server: relatedServers) {
            if(st.equals(server.getType())) {
                servers.add(server);
            }
        }
        return servers;
    }

    /**
     * 
     */
    @Transactional(readOnly=true)
    public List<Resource> findServersByType(Resource p, ResourceType st) {
        return findByPlatformAndTypeOrderName(p, st);
    }


    /**
     * Get server lite value by id. Does not check permission.
     * 
     */
    @Transactional(readOnly=true)
    public Resource getServerById(AuthzSubject subject, Integer id) throws ServerNotFoundException, PermissionException {
        Resource server = findServerById(id);
        permissionManager.checkViewPermission(subject, server.getId());
        return server;
    }

    /**
     * /** Get server IDs by server type.
     * 
     * 
     * @param subject The subject trying to list servers.
     * @param servTypeId server type id.
     * @return An array of Server IDs.
     */
    @Transactional(readOnly=true)
    public Integer[] getServerIds(AuthzSubject subject, Integer servTypeId) throws PermissionException {

        try {

            Collection<Resource> servers = ResourceType.findResourceType(servTypeId).getResources();
            if (servers.size() == 0) {
                return new Integer[0];
            }
            List<Integer> serverIds = new ArrayList<Integer>(servers.size());

            // now get the list of PKs
            Collection<Integer> viewable = getViewableServers(subject);
            // and iterate over the List to remove any item not in the
            // viewable list
            int i = 0;
            for (Iterator<Resource> it = servers.iterator(); it.hasNext(); i++) {
                Resource server = it.next();
                if (viewable.contains(server.getId())) {
                    // add the item, user can see it
                    serverIds.add(server.getId());
                }
            }

            return (Integer[]) serverIds.toArray(new Integer[0]);
        } catch (NotFoundException e) {
            // There are no viewable servers
            return new Integer[0];
        }
    }
    
    private ServerValue getServerValue(Resource server) {
        //TODO
        return null;
    }

    /**
     * Get server by service.
     * 
     */
    @Transactional(readOnly=true)
    public ServerValue getServerByService(AuthzSubject subject, Integer sID) throws ServerNotFoundException,
        ServiceNotFoundException, PermissionException {
        Resource svc = Resource.findResource(sID);
        Resource s = svc.getResourceTo(RelationshipTypes.SERVICE);
        permissionManager.checkViewPermission(subject, s.getId());
        return getServerValue(s);
    }

    /**
     * Get server by service. The virtual servers are not filtere out of
     * returned list.
     * 
     */
    @Transactional(readOnly=true)
    public PageList<ServerValue> getServersByServices(AuthzSubject subject, List<AppdefEntityID> sIDs)
        throws PermissionException, ServerNotFoundException {
        Set<Resource> servers = new HashSet<Resource>();
        for (AppdefEntityID svcId : sIDs) {

            Resource svc = Resource.findResource(svcId.getId());

            servers.add(svc.getResourceTo(RelationshipTypes.SERVICE));
        }

        return valuePager.seek(filterViewableServers(servers, subject), null);
    }

    /**
     * Get all servers.
     * 
     * 
     * @param subject The subject trying to list servers.
     * @return A List of ServerValue objects representing all of the servers
     *         that the given subject is allowed to view.
     */
    @Transactional(readOnly=true)
    public PageList<ServerValue> getAllServers(AuthzSubject subject, PageControl pc) throws PermissionException,
        NotFoundException {
        Collection<Resource> servers = getViewableServers(subject, pc);

        // valuePager converts local/remote interfaces to value objects
        // as it pages through them.
        return valuePager.seek(servers, pc);
    }

    /**
     * Get the scope of viewable servers for a given user
     * @param subject - the user
     * @return List of ServerLocals for which subject has
     *         AuthzConstants.serverOpViewServer
     */
    @Transactional(readOnly=true)
    private Collection<Resource> getViewableServers(AuthzSubject subject, PageControl pc) throws PermissionException,
        NotFoundException {
        Collection<Resource> servers;
        List<Integer> authzPks = getViewableServers(subject);
        int attr = -1;
        if (pc != null) {
            attr = pc.getSortattribute();
        }
        switch (attr) {
            case SortAttribute.RESOURCE_NAME:
                servers = getServersFromIds(authzPks, pc.isAscending());
                break;
            default:
                servers = getServersFromIds(authzPks, true);
                break;
        }
        return servers;
    }

    /**
     * @param serverIds {@link Collection} of {@link Server.getId}
     * @return {@link Collection} of {@link Server}
     */
    @Transactional(readOnly=true)
    private Collection<Resource> getServersFromIds(Collection<Integer> serverIds, boolean asc) {
        final List<Resource> rtn = new ArrayList<Resource>(serverIds.size());
        for (Integer id : serverIds) {

            try {
                final Resource server = findServerById(id);
                final Resource r = server;
                if (r == null) {
                    continue;
                }
                rtn.add(server);
            } catch (ServerNotFoundException e) {
                log.debug(e.getMessage(), e);
            }
        }
        Collections.sort(rtn, new AppdefNameComparator(asc));
        return rtn;
    }

    /**
     * 
     */
    @Transactional(readOnly=true)
    public Collection<Resource> getViewableServers(AuthzSubject subject, Resource platform) {
        return filterViewableServers(platform.getResourcesFrom(RelationshipTypes.SERVER), subject);
    }

    private Collection<Resource> getServersByPlatformImpl(AuthzSubject subject, Integer platId, Integer servTypeId,
                                                        boolean excludeVirtual, PageControl pc)
        throws PermissionException, ServerNotFoundException, PlatformNotFoundException {
        List<Integer> authzPks;
        try {
            authzPks = getViewableServers(subject);
        } catch (NotFoundException exc) {
            throw new ServerNotFoundException("No (viewable) servers associated with platform " + platId);
        }

        List<Resource> servers;
        // first, if they specified a server type, then filter on it
        if (!servTypeId.equals(APPDEF_RES_TYPE_UNDEFINED)) {
            if (!excludeVirtual) {
                servers = findByPlatformAndTypeOrderName(platId, servTypeId);
            } else {
                servers = findByPlatformAndTypeOrderName(platId, servTypeId, Boolean.FALSE);
            }
        } else {
            if (!excludeVirtual) {
                servers = findByPlatformOrderName(platId);
            } else {
                servers = findByPlatformOrderName(platId, Boolean.FALSE);
            }
        }
        for (Iterator<Resource> i = servers.iterator(); i.hasNext();) {
            Resource aServer = i.next();

            // Keep the virtual ones, we need them so that child services can be
            // added. Otherwise, no one except the super user will have access
            // to the virtual services
            //TODO virtual?
            //if (aServer.getType().isVirtual())
              //  continue;

            // Remove the server if its not viewable
            if (!authzPks.contains(aServer.getId())) {
                i.remove();
            }
        }

        // If sort descending, then reverse the list
        if (pc != null && pc.isDescending()) {
            Collections.reverse(servers);
        }

        return servers;
    }

    /**
     * Get servers by platform.
     * 
     * 
     * @param subject The subject trying to list servers.
     * @param platId platform id.
     * @param excludeVirtual true if you dont want virtual (fake container)
     *        servers in the returned list
     * @param pc The page control.
     * @return A PageList of ServerValue objects representing servers on the
     *         specified platform that the subject is allowed to view.
     */
    @Transactional(readOnly=true)
    public PageList<ServerValue> getServersByPlatform(AuthzSubject subject, Integer platId, boolean excludeVirtual,
                                                      PageControl pc) throws ServerNotFoundException,
        PlatformNotFoundException, PermissionException {
        return getServersByPlatform(subject, platId, APPDEF_RES_TYPE_UNDEFINED, excludeVirtual, pc);
    }

    /**
     * Get servers by server type and platform.
     * 
     * 
     * 
     * @param subject The subject trying to list servers.
     * @param servTypeId server type id.
     * @param platId platform id.
     * @param pc The page control.
     * @return A PageList of ServerValue objects representing servers on the
     *         specified platform that the subject is allowed to view.
     */
    @Transactional(readOnly=true)
    public PageList<ServerValue> getServersByPlatform(AuthzSubject subject, Integer platId, Integer servTypeId,
                                                      boolean excludeVirtual, PageControl pc)
        throws ServerNotFoundException, PlatformNotFoundException, PermissionException {
        Collection<Resource> servers = getServersByPlatformImpl(subject, platId, servTypeId, excludeVirtual, pc);

        // valuePager converts local/remote interfaces to value objects
        // as it pages through them.
        return valuePager.seek(servers, pc);
    }

    /**
     * Get servers by server type and platform.
     * 
     * 
     * @param subject The subject trying to list servers.
     * @param platId platform id.
     * @return A PageList of ServerValue objects representing servers on the
     *         specified platform that the subject is allowed to view.
     */
    @Transactional(readOnly=true)
    public PageList<ServerValue> getServersByPlatformServiceType(AuthzSubject subject, Integer platId, Integer svcTypeId)
        throws ServerNotFoundException, PlatformNotFoundException, PermissionException {
        PageControl pc = PageControl.PAGE_ALL;
        Integer servTypeId;
        try {
            ResourceType typeV = ResourceType.findResourceType(svcTypeId);
            servTypeId = typeV.getResourceTypeTo(RelationshipTypes.SERVICE_TYPE).getId();
        } catch (ObjectNotFoundException e) {
            throw new ServerNotFoundException("Service Type not found", e);
        }

        Collection<Resource> servers = getServersByPlatformImpl(subject, platId, servTypeId, false, pc);

        // valuePager converts local/remote interfaces to value objects
        // as it pages through them.
        return valuePager.seek(servers, pc);
    }

    /**
     * Get servers by server type and platform.
     * 
     * @param subject The subject trying to list servers.
     * @param typeId server type id.
     * 
     * @return A PageList of ServerValue objects representing servers on the
     *         specified platform that the subject is allowed to view.
     */
    @Transactional(readOnly=true)
    public List<ServerValue> getServersByType(AuthzSubject subject, String name) throws PermissionException,
        InvalidAppdefTypeException {
        try {
            ResourceType serverType = ResourceType.findResourceTypeByName(name);
            if (serverType == null) {
                return new PageList<ServerValue>();
            }

            Collection<Resource> servers = serverType.getResources();

            List<Integer> authzPks = getViewableServers(subject);
            for (Iterator<Resource> i = servers.iterator(); i.hasNext();) {
                Integer sPK = i.next().getId();
                // remove server if its not viewable
                if (!authzPks.contains(sPK))
                    i.remove();
            }

            // valuePager converts local/remote interfaces to value objects
            // as it pages through them.
            return valuePager.seek(servers, PageControl.PAGE_ALL);
        } catch (NotFoundException e) {
            return new ArrayList<ServerValue>(0);
        }
    }

    /**
     * Get non-virtual server IDs by server type and platform.
     * 
     * 
     * @param subject The subject trying to list servers.
     * @param platId platform id.
     * @return An array of Integer[] which represent the ServerIds specified
     *         platform that the subject is allowed to view.
     */
    @Transactional(readOnly=true)
    public Integer[] getServerIdsByPlatform(AuthzSubject subject, Integer platId) throws ServerNotFoundException,
        PlatformNotFoundException, PermissionException {
        return getServerIdsByPlatform(subject, platId, APPDEF_RES_TYPE_UNDEFINED, true);
    }

    /**
     * Get non-virtual server IDs by server type and platform.
     * 
     * 
     * @param subject The subject trying to list servers.
     * @param servTypeId server type id.
     * @param platId platform id.
     * @return An array of Integer[] which represent the ServerIds
     */
    @Transactional(readOnly=true)
    public Integer[] getServerIdsByPlatform(AuthzSubject subject, Integer platId, Integer servTypeId)
        throws ServerNotFoundException, PlatformNotFoundException, PermissionException {
        return getServerIdsByPlatform(subject, platId, servTypeId, true);
    }

    /**
     * Get server IDs by server type and platform.
     * 
     * 
     * @param subject The subject trying to list servers.
     * @param servTypeId server type id.
     * @param platId platform id.
     * @return A PageList of ServerValue objects representing servers on the
     *         specified platform that the subject is allowed to view.
     */
    @Transactional(readOnly=true)
    public Integer[] getServerIdsByPlatform(AuthzSubject subject, Integer platId, Integer servTypeId,
                                            boolean excludeVirtual) throws ServerNotFoundException,
        PlatformNotFoundException, PermissionException {
        Collection<Resource> servers = getServersByPlatformImpl(subject, platId, servTypeId, excludeVirtual, null);

        Integer[] ids = new Integer[servers.size()];
        Iterator<Resource> it = servers.iterator();
        for (int i = 0; it.hasNext(); i++) {
            Resource server = it.next();
            ids[i] = server.getId();
        }

        return ids;
    }

    /**
     * Get servers by application and serverType.
     * 
     * @param subject The subject trying to list servers.
     * @param appId Application id.
     * @return A List of ServerValue objects representing servers that support
     *         the given application that the subject is allowed to view.
     */
    @Transactional(readOnly=true)
    private Collection<Resource> getServersByApplicationImpl(AuthzSubject subject, Integer appId, Integer servTypeId)
        throws ServerNotFoundException, ApplicationNotFoundException, PermissionException {

        List<Integer> authzPks;
        ResourceGroup appLocal;

        try {
            appLocal = ResourceGroup.findResourceGroup(appId);
        } catch (ObjectNotFoundException exc) {
            throw new ApplicationNotFoundException(appId, exc);
        }

        try {
            authzPks = getViewableServers(subject);
        } catch (NotFoundException e) {
            throw new ServerNotFoundException("No (viewable) servers " + "associated with " + "application " + appId, e);
        }

        HashMap<Integer, Resource> serverCollection = new HashMap<Integer, Resource>();

        // XXX - a better solution is to control the viewable set returned by
        // ql finders. This will be forthcoming.

        Collection<Resource> appServiceCollection = appLocal.getMembers();
        Iterator<Resource> it = appServiceCollection.iterator();

        while (it.hasNext()) {

            Resource appService = it.next();

            if (appService.isIsGroup()) {
                Collection<Service> services = serviceManager.getServiceCluster(appService.getResourceGroup())
                    .getServices();

                Iterator<Service> serviceIterator = services.iterator();
                while (serviceIterator.hasNext()) {
                    Service service = serviceIterator.next();
                    Server server = service.getServer();

                    // Don't bother with entire cluster if type is platform svc
                    if (server.getServerType().isVirtual()) {
                        break;
                    }

                    Integer serverId = server.getId();

                    if (serverCollection.containsKey(serverId)) {
                        continue;
                    }

                    serverCollection.put(serverId, server);
                }
            } else {
                Server server = appService.getService().getServer();
                if (!server.getServerType().isVirtual()) {
                    Integer serverId = server.getId();

                    if (serverCollection.containsKey(serverId))
                        continue;

                    serverCollection.put(serverId, server);
                }
            }
        }

        for (Iterator<Map.Entry<Integer, Resource>> i = serverCollection.entrySet().iterator(); i.hasNext();) {
            Map.Entry<Integer, Resource> entry = i.next();
            Resource aServer = entry.getValue();

            // first, if they specified a server type, then filter on it
            if (servTypeId != APPDEF_RES_TYPE_UNDEFINED && !(aServer.getType().getId().equals(servTypeId))) {
                i.remove();
            }
            // otherwise, remove the server if its not viewable
            else if (!authzPks.contains(aServer.getId())) {
                i.remove();
            }
        }

        return serverCollection.values();
    }

    /**
     * Get servers by application.
     * 
     * 
     * @param subject The subject trying to list servers.
     * @param appId Application id.
     * @param pc The page control for this page list.
     * @return A List of ServerValue objects representing servers that support
     *         the given application that the subject is allowed to view.
     */
    @Transactional(readOnly=true)
    public PageList<ServerValue> getServersByApplication(AuthzSubject subject, Integer appId, PageControl pc)
        throws ServerNotFoundException, ApplicationNotFoundException, PermissionException {
        return getServersByApplication(subject, appId, APPDEF_RES_TYPE_UNDEFINED, pc);
    }

    /**
     * Get servers by application and serverType.
     * 
     * 
     * @param subject The subject trying to list servers.
     * @param appId Application id.
     * @param pc The page control for this page list.
     * @return A List of ServerValue objects representing servers that support
     *         the given application that the subject is allowed to view.
     */
    @Transactional(readOnly=true)
    public PageList<ServerValue> getServersByApplication(AuthzSubject subject, Integer appId, Integer servTypeId,
                                                         PageControl pc) throws ServerNotFoundException,
        ApplicationNotFoundException, PermissionException {
        Collection<Resource> serverCollection = getServersByApplicationImpl(subject, appId, servTypeId);

        // valuePager converts local/remote interfaces to value objects
        // as it pages through them.
        return valuePager.seek(serverCollection, pc);
    }

    /**
     * Get server IDs by application and serverType.
     * 
     * 
     * @param subject The subject trying to list servers.
     * @param appId Application id.
     * @return A List of ServerValue objects representing servers that support
     *         the given application that the subject is allowed to view.
     */
    @Transactional(readOnly=true)
    public Integer[] getServerIdsByApplication(AuthzSubject subject, Integer appId, Integer servTypeId)
        throws ServerNotFoundException, ApplicationNotFoundException, PermissionException {
        Collection<Resource> servers = getServersByApplicationImpl(subject, appId, servTypeId);

        Integer[] ids = new Integer[servers.size()];
        Iterator<Resource> it = servers.iterator();
        for (int i = 0; it.hasNext(); i++) {
            Resource server = it.next();
            ids[i] = server.getId();
        }

        return ids;
    }
    
    private boolean matchesValueObject(ServerValue existing, Resource server) {
        //TODO from Server
        return true;
    }
    
    private void updateServer(ServerValue existing, Resource server) {
        //TODO from Server
    }

    /**
     * Update a server
     * @param existing
     * 
     */
    public Resource updateServer(AuthzSubject subject, ServerValue existing) throws PermissionException, UpdateException,
        AppdefDuplicateNameException, ServerNotFoundException {
        try {
            Resource server = Resource.findResource(existing.getId());
            permissionManager.checkModifyPermission(subject, server.getId());
            existing.setModifiedBy(subject.getName());
            existing.setMTime(new Long(System.currentTimeMillis()));
            trimStrings(existing);

            if (matchesValueObject(existing,server)) {
                log.debug("No changes found between value object and entity");
            } else {
                if (!existing.getName().equals(server.getName())) {
                   
                    server.setName(existing.getName());
                }

                updateServer(existing,server);
            }
            return server;
        } catch (ObjectNotFoundException e) {
            throw new ServerNotFoundException(existing.getId(), e);
        }
    }

    /**
     * Update server types
     * 
     */
    public void updateServerTypes(String plugin, ServerTypeInfo[] infos) throws VetoException, NotFoundException {
        // First, put all of the infos into a Hash
        HashMap<String, ServerTypeInfo> infoMap = new HashMap<String, ServerTypeInfo>();
        for (int i = 0; i < infos.length; i++) {
            String name = infos[i].getName();
            ServerTypeInfo sinfo = infoMap.get(name);

            if (sinfo == null) {
                // first time we've seen this type
                // clone it incase we have to update the platforms
                infoMap.put(name, (ServerTypeInfo) infos[i].clone());
            } else {
                // already seen this type; just update the platforms.
                // this allows server types of the same name to support
                // different families of platforms in the plugins.
                String[] platforms = (String[]) ArrayUtil.merge(sinfo.getValidPlatformTypes(), infos[i]
                    .getValidPlatformTypes(), new String[0]);
                sinfo.setValidPlatformTypes(platforms);
            }
        }

        Collection<ResourceType> curServers = ResourceType.findByPlugin(plugin);

        AuthzSubject overlord = authzSubjectManager.getOverlordPojo();

        for (ResourceType serverType : curServers) {

            String serverName = serverType.getName();
            ServerTypeInfo sinfo = (ServerTypeInfo) infoMap.remove(serverName);

            if (sinfo == null) {
                deleteServerType(serverType, overlord, resourceGroupManager, resourceManager);
            } else {
                String curDesc = serverType.getDescription();
                Collection<ResourceType> curPlats = serverType.getResourceTypesTo(RelationshipTypes.SERVER_TYPE);
                String newDesc = sinfo.getDescription();
                String[] newPlats = sinfo.getValidPlatformTypes();
                boolean updatePlats;

                log.debug("Updating ServerType: " + serverName);

                if (!newDesc.equals(curDesc)) {
                    serverType.setDescription(newDesc);
                }

                // See if we need to update the supported platforms
                updatePlats = newPlats.length != curPlats.size();
                if (updatePlats == false) {
                    // Ensure that the lists are the same
                    for (ResourceType pLocal : curPlats) {

                        int j;

                        for (j = 0; j < newPlats.length; j++) {
                            if (newPlats[j].equals(pLocal.getName()))
                                break;
                        }
                        if (j == newPlats.length) {
                            updatePlats = true;
                            break;
                        }
                    }
                }

                if (updatePlats == true) {
                    findAndSetPlatformType(newPlats, serverType);
                }
            }
        }

       

        // Now create the left-overs
        for (ServerTypeInfo sinfo : infoMap.values()) {
            createServerType(sinfo,plugin);
        }
    }
    
    public ResourceType createServerType(ServerTypeInfo sinfo, String plugin) throws NotFoundException {
        ResourceType stype = new ResourceType();
        log.debug("Creating new ServerType: " + sinfo.getName());
        stype.setPlugin(plugin);
        stype.setName(sinfo.getName());
        stype.setDescription(sinfo.getDescription());
        stype.setVirtual(sinfo.isVirtual());
        String newPlats[] = sinfo.getValidPlatformTypes();
        findAndSetPlatformType(newPlats, stype);

        stype.persist();
        return stype;
    }

    /**
     * Find an operation by name inside a ResourcetypeValue object
     */
    protected OperationType getOperationByName(ResourceType rtV, String opName) throws PermissionException {
        OperationType op = rtV.getOperationType(opName);
        if(op == null) {
            throw new PermissionException("Operation: " + opName + " not valid for ResourceType: " + rtV.getName());
        }
        return op;
    }

    /**
     * builds a list of resource types from the list of resources
     * @param resources - {@link Collection} of {@link AppdefResource}
     * @param {@link Collection} of {@link AppdefResourceType}
     */
    protected Collection<AppdefResourceType> filterResourceTypes(Collection<? extends AppdefResource> resources) {
        final Set<AppdefResourceType> resTypes = new HashSet<AppdefResourceType>();
        for (AppdefResource o : resources) {

            if (o == null) {
                continue;
            }
            final AppdefResourceType rt = o.getAppdefResourceType();
            if (rt != null) {
                resTypes.add(rt);
            }
        }
        final List<AppdefResourceType> rtn = new ArrayList<AppdefResourceType>(resTypes);
        Collections.sort(rtn, new Comparator<AppdefResourceType>() {
            private String getName(Object obj) {
                if (obj instanceof AppdefResourceType) {
                    return ((AppdefResourceType) obj).getSortName();
                }
                return "";
            }

            public int compare(AppdefResourceType o1, AppdefResourceType o2) {
                return getName(o1).compareTo(getName(o2));
            }
        });
        return rtn;
    }

    /**
     * 
     */
    public void deleteServerType(ResourceType serverType, AuthzSubject overlord, ResourceGroupManager resGroupMan,
                                 ResourceManager resMan) throws VetoException {
        // Need to remove all service types

        ResourceType[] types = (ResourceType[]) serverType.getResourceTypesFrom(RelationshipTypes.SERVICE_TYPE).toArray(new ResourceType[0]);
        for (int i = 0; i < types.length; i++) {
            serviceManager.deleteServiceType(types[i], overlord, resGroupMan, resMan);
        }

        log.debug("Removing ServerType: " + serverType.getName());
        //Integer typeId = AuthzConstants.authzServerProto;
        //Resource proto = resMan.findResourceByInstanceId(typeId, serverType.getId());

        try {
            //TODO remove compat groups?
            //resGroupMan.removeGroupsCompatibleWith(proto);

            // Remove all servers done by removing server type
            
        } catch (PermissionException e) {
            assert false : "Overlord should not run into PermissionException";
        }

        serverType.remove();
    }

    /**
     * 
     */
    public void setAutodiscoveryZombie(Resource server, boolean zombie) {
        server.setAutodiscoveryZombie(zombie);
    }

    /**
     * Get a Set of PlatformTypeLocal objects which map to the names as given by
     * the argument.
     */
    private void findAndSetPlatformType(String[] platNames, ResourceType stype) throws NotFoundException {

        for (int i = 0; i < platNames.length; i++) {
            ResourceType pType = ResourceType.findResourceTypeByName(platNames[i]);
            if (pType == null) {
                throw new NotFoundException("Could not find platform type '" + platNames[i] + "'");
            }
           pType.relateTo(stype, RelationshipTypes.SERVER_TYPE);
        }
    }

   

    /**
     * Trim all string attributes
     */
    private void trimStrings(ServerValue server) {
        if (server.getDescription() != null)
            server.setDescription(server.getDescription().trim());
        if (server.getInstallPath() != null)
            server.setInstallPath(server.getInstallPath().trim());
        if (server.getAutoinventoryIdentifier() != null)
            server.setAutoinventoryIdentifier(server.getAutoinventoryIdentifier().trim());
        if (server.getLocation() != null)
            server.setLocation(server.getLocation().trim());
        if (server.getName() != null)
            server.setName(server.getName().trim());
    }

    /**
     * Returns a list of 2 element arrays. The first element is the name of the
     * server type, the second element is the # of servers of that type in the
     * inventory.
     * 
     * 
     */
    @Transactional(readOnly=true)
    public List<Object[]> getServerTypeCounts() {
        return serverDAO.getServerTypeCounts();
    }

    /**
     * Get the # of servers within HQ inventory. This method ingores virtual
     * server types.
     * 
     */
    @Transactional(readOnly=true)
    public Number getServerCount() {
        return serverDAO.getServerCount();
    }

    @PostConstruct
    public void afterPropertiesSet() throws Exception {

        valuePager = Pager.getPager(VALUE_PROCESSOR);

    }
}
