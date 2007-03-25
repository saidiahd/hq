package org.hyperic.hq.ui.rendit

import org.apache.commons.lang.StringEscapeUtils

import java.io.OutputStreamWriter

import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory

import org.hyperic.hq.authz.server.session.AuthzSubject
import org.hyperic.hq.ui.util.ContextUtils
import org.hyperic.hq.ui.util.RequestUtils

import org.hyperic.hq.ui.rendit.helpers.LiveDataHelper
import org.hyperic.hq.ui.rendit.helpers.ResourceHelper
import org.hyperic.hq.ui.rendit.helpers.HQHelper

import groovy.text.SimpleTemplateEngine
import java.io.File

abstract class BaseController { 
    Log     log = LogFactory.getLog(this.getClass())
    String  action
    File    pluginDir

    private invokeArgs
    private AuthzSubject user
    
    private void setAction(String action) { 
        this.action = action
    }
    	
    protected setInvokeArgs(args) {
        this.invokeArgs = args
    }
    
    def getInvokeArgs() { invokeArgs }
    
    def setPluginDir(File pluginDir) {
        this.pluginDir = pluginDir
    }
    
    def getResourceHelper() { return new ResourceHelper(getUser()) }
    def getLiveDataHelper() { return new LiveDataHelper(getUser()) }
    def getHQHelper() { return new HQHelper(getUser()) }
    
    /**
     * Retreives the currently logged-in user
     */
    protected AuthzSubject getUser() {
        if (this.user != null)
            return this.user
        
        def sessId = RequestUtils.getSessionId(invokeArgs.request)
        def ctx    = invokeArgs.request.session.servletContext

        this.user = ContextUtils.getAuthzBoss(ctx).getCurrentSubject(sessId)
    }

    public String h(str) {
        StringEscapeUtils.escapeHtml(str)    
    }
        
    // TODO:  These need to be moved to a separate class
    public RENDER_BUILTINS = [
        link_to : { text, Object[] args ->
        	def url = ""
            def opts = (args.length > 0) ? args[0] : [:]
        	def htmlOpts = (args.length > 1) ? args[1] : [:]
            
            if (opts.containsKey('action')) { 
                url += h(opts['action'])
            } else if (opts.containsKey('resource')) {
                def entId = opts['resource'].entityId.appdefKey
                url = getHQHelper().serverURL + "Resource.do?eid=$entId"
            }
            
            url += '?'                
            for (o in htmlOpts) {
                url += URLEncoder.encode("" + o.key, "UTF-8") + "=" + 
                       URLEncoder.encode("" + o.value, "UTF-8") + "&"
            }

            if (url.length() > 0 && (url[-1] == '?' || url[-1] == '&')) 
                url = url[0..-2]
            "<a href=\"$url\">$text</a>"
        },
        
        avail_icon : { color -> "<img src=\"/images/icon_available_$color" + 
                                ".gif\"/>" },
        
        h : { str -> h(str) } 
    ]
    
    /**
     * Render a .gsp.
     *
     * This method takes a map of arguments.  Valid arguments include:
     *    file:  The file to render.  If not specified, the name of the
     *           current action will be used
     *    args:  A map of key/value pairs to send to the .gsp to use when
     *           rendering
     *
     * Examples:
     *    To render the file 'listView.gsp' to the browser         
     *    > render file:'listView'
     *
     *    To render the current action to the browser and pass in parameters 
     *    needed by the .gsp
     *    > render args:[userName:'Jeff', favouriteDrink:'Vodka']
     *                     
     */
    protected void render(args) {
        args = (args == null) ? [:] : args
        def gspArgs = args.get("args", [:])
        def gspFile = args.file
        def useAction
                
        if (gspFile == null)
            useAction = action
        else
            useAction = gspFile

        new File(pluginDir, useAction + '.gsp').withReader { reader ->
            def eng       = new SimpleTemplateEngine(false)
            def template  = eng.createTemplate(reader)
            def outStream = invokeArgs.response.outputStream
            def outWriter = new OutputStreamWriter(outStream)
            
            gspArgs.putAll(RENDER_BUILTINS)
            template.make(gspArgs).writeTo(outWriter)
        }
    }
}
