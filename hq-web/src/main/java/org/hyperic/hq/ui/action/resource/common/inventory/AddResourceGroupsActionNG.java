/*
 * NOTE: This copyright does *not* cover user programs that use HQ
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 * "derived work".
 * 
 * Copyright (C) [2004, 2005, 2006], Hyperic, Inc.
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


package org.hyperic.hq.ui.action.resource.common.inventory;

import java.util.Arrays;
import java.util.HashMap;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.struts2.interceptor.validation.SkipValidation;
import org.hyperic.hq.appdef.shared.AppSvcClustDuplicateAssignException;
import org.hyperic.hq.appdef.shared.AppdefEntityConstants;
import org.hyperic.hq.appdef.shared.AppdefEntityID;
import org.hyperic.hq.bizapp.shared.AppdefBoss;
import org.hyperic.hq.common.VetoException;
import org.hyperic.hq.ui.Constants;
import org.hyperic.hq.ui.action.BaseActionNG;
import org.hyperic.hq.ui.util.RequestUtils;
import org.hyperic.hq.ui.util.SessionUtils;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.opensymphony.xwork2.ModelDriven;

@Component("addResourceGroupsActionNG")
@Scope("prototype")
public class AddResourceGroupsActionNG extends BaseActionNG implements ModelDriven<AddResourceGroupsFormNG>{

	@Resource
    private AppdefBoss appdefBoss;
    private final Log log = LogFactory.getLog(AddResourceGroupsActionNG.class);
    

    private AddResourceGroupsFormNG addForm = new AddResourceGroupsFormNG();
	
	private Integer internalRid;
	private Integer internalType;
	private String internalEid;
	
	public String execute() throws Exception {
        HttpSession session = request.getSession();
        
        AppdefEntityID aeid = new AppdefEntityID(addForm.getType().intValue(), addForm.getRid());

        request.setAttribute(Constants.ENTITY_ID_PARAM, aeid.getAppdefKey());
        internalRid = addForm.getRid() ;
        internalType = addForm.getType().intValue();
        internalEid = aeid.toString();

        switch (aeid.getType()) {
            case AppdefEntityConstants.APPDEF_TYPE_PLATFORM:
            	request.setAttribute(Constants.ACCORDION_PARAM, "4");
                break;
            case AppdefEntityConstants.APPDEF_TYPE_SERVER:
            case AppdefEntityConstants.APPDEF_TYPE_APPLICATION:
            	request.setAttribute(Constants.ACCORDION_PARAM, "2");
                break;
            case AppdefEntityConstants.APPDEF_TYPE_SERVICE:
            	request.setAttribute(Constants.ACCORDION_PARAM, "1");
                break;
        }

        try {
        	String forward = checkSubmit(addForm);
    		
            if (forward != null) {
                

                if (forward.equalsIgnoreCase("CANCEL") || forward.equalsIgnoreCase("CANCEL")) {
                    log.trace("removing pending group list");
                    SessionUtils.removeList(session, Constants.PENDING_RESGRPS_SES_ATTR);
                } else if (forward.equalsIgnoreCase("ADDED")) {
                    log.trace("adding to pending group list");
                    SessionUtils.addToList(session, Constants.PENDING_RESGRPS_SES_ATTR, addForm.getAvailableGroups());
                } else if (forward.equalsIgnoreCase("REMOVED")) {
                    log.trace("removing from pending group list");
                    SessionUtils
                        .removeFromList(session, Constants.PENDING_RESGRPS_SES_ATTR, addForm.getPendingGroups());
                }

                return forward;
            }

            Integer sessionId = RequestUtils.getSessionId(request);

            log.trace("getting pending group list");
            Integer[] pendingGroupIds = SessionUtils.getList(session, Constants.PENDING_RESGRPS_SES_ATTR);

            if (log.isTraceEnabled())
                log.trace("adding groups " + Arrays.asList(pendingGroupIds) + " for resource [" + aeid + "]");
            appdefBoss.batchGroupAdd(sessionId.intValue(), aeid, pendingGroupIds);

            log.trace("removing pending group list");
            SessionUtils.removeList(session, Constants.PENDING_RESGRPS_SES_ATTR);

            addActionMessage(getText( "resource.common.inventory.confirm.AddResourceGroups" ) );
            return SUCCESS;
        } catch (AppSvcClustDuplicateAssignException e1) {
            addActionError("resource.common.inventory.error.DuplicateClusterAssignment");
            return INPUT;
        } catch (VetoException ve) {
        	addActionError(getText( "resource.group.inventory.error.UpdateResourceListVetoed", new String[] {ve.getMessage()} ) );
           return INPUT;
        }		
	}
	
	@SkipValidation
	public String cancel() throws Exception {
		setHeaderResources();
		clearErrorsAndMessages();
		HttpSession session = request.getSession();
		SessionUtils.removeList(session, Constants.PENDING_RESGRPS_SES_ATTR);
		AppdefEntityID aeid = RequestUtils.getEntityId(request);
		if (aeid!= null) {
			internalEid = aeid.toString();
		}
		return "cancel";
	}

	@SkipValidation
	public String reset() throws Exception {
		setHeaderResources();
		addForm.reset();
		clearErrorsAndMessages();
		AppdefEntityID aeid = RequestUtils.getEntityId(request);
		if (aeid!= null) {
			setEntityRequestParams(aeid);
		}
		return "reset";
	}

	public AddResourceGroupsFormNG getModel() {
		return addForm;
	}
	
    public AddResourceGroupsFormNG getAddForm() {
		return addForm;
	}

	public void setAddForm(AddResourceGroupsFormNG addForm) {
		this.addForm = addForm;
	}

	public Integer getInternalRid() {
		return internalRid;
	}

	public void setInternalRid(Integer internalRid) {
		this.internalRid = internalRid;
	}

	public Integer getInternalType() {
		return internalType;
	}

	public void setInternalType(Integer internalType) {
		this.internalType = internalType;
	}

	public String getInternalEid() {
		return internalEid;
	}

	public void setInternalEid(String internalEid) {
		this.internalEid = internalEid;
	}

	private void setEntityRequestParams (AppdefEntityID eid) {
		this.internalEid = eid.toString();
		this.internalRid = eid.getId();
		this.internalType = eid.getType();
	}
}
