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

package org.hyperic.hq.ui.action.resource.common.monitor.visibility;

import java.util.List;

import javax.servlet.ServletException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tiles.AttributeContext;
import org.apache.tiles.context.TilesRequestContext;
import org.apache.tiles.preparer.ViewPreparer;
import org.hyperic.hq.appdef.shared.AppdefEntityID;
import org.hyperic.hq.appdef.shared.AppdefEntityNotFoundException;
import org.hyperic.hq.appdef.shared.AppdefResourceValue;
import org.hyperic.hq.auth.shared.SessionNotFoundException;
import org.hyperic.hq.auth.shared.SessionTimeoutException;
import org.hyperic.hq.authz.shared.PermissionException;
import org.hyperic.hq.bizapp.shared.MeasurementBoss;
import org.hyperic.hq.bizapp.shared.uibeans.ResourceDisplaySummary;
import org.hyperic.hq.grouping.shared.GroupNotCompatibleException;
import org.hyperic.hq.ui.Constants;
import org.hyperic.hq.ui.action.BaseActionNG;
import org.hyperic.hq.ui.util.RequestUtils;
import org.hyperic.util.timer.StopWatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 
 * Fetch the children resources for the server
 */
@Component("platformListVirtualsActionNG")
public class ListVirtualsActionNG extends BaseActionNG implements ViewPreparer {

	private MeasurementBoss measurementBoss;

	private final Log log = LogFactory.getLog(ListVirtualsActionNG.class);

	@Autowired
	public ListVirtualsActionNG(MeasurementBoss measurementBoss) {
		super();
		this.measurementBoss = measurementBoss;
	}

	public void execute(TilesRequestContext tilesContext,
			AttributeContext attributeContext) {
		StopWatch watch = new StopWatch();
		AppdefResourceValue resource = RequestUtils
				.getResource(getServletRequest());

		if (resource == null) {
			addActionError(Constants.ERR_RESOURCE_NOT_FOUND);
			return;
		}

		AppdefEntityID entityId = resource.getEntityId();

		try {
			int sessionId = RequestUtils.getSessionId(getServletRequest())
					.intValue();

			List<ResourceDisplaySummary> virtualHealths = measurementBoss
					.findVirtualsCurrentHealth(sessionId, entityId);

			tilesContext.getRequestScope().put(Constants.CTX_SUMMARIES,
					virtualHealths);

			if (log.isDebugEnabled()) {
				log.debug("ListVirtualsAction.execute: " + watch);
			}

		} catch (ServletException e) {
			log.error(e);
		} catch (SessionTimeoutException e) {
			log.error(e);
		} catch (SessionNotFoundException e) {
			log.error(e);
		} catch (AppdefEntityNotFoundException e) {
			log.error(e);
		} catch (GroupNotCompatibleException e) {
			log.error(e);
		} catch (PermissionException e) {
			log.error(e);
		}

	}
}
