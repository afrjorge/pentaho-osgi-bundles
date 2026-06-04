/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2028-08-13
 ******************************************************************************/

package org.pentaho.platform.pdi;

/**
 * Created by nbaker on 7/25/16.
 */

import org.pentaho.platform.api.engine.IContentGenerator;
import org.pentaho.platform.api.engine.IOutputHandler;
import org.pentaho.platform.api.engine.IParameterProvider;
import org.pentaho.platform.engine.core.output.SimpleOutputHandler;
import org.pentaho.platform.engine.core.solution.SimpleParameterProvider;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.StandaloneSession;
import org.springframework.context.ApplicationContext;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * Servlet that bridges OSGi-deployed Pentaho content generators (e.g., AnalyzerContentGenerator)
 * with PDI's Karaf HTTP service. This servlet is instantiated per content generator bean by the
 * analyzer Blueprint (analyzer_beans.xml) and registered as an HTTP Whiteboard servlet.
 *
 * <p>Key responsibilities for DET in PDI:</p>
 * <ul>
 *   <li>Creates a composite classloader (bundle CL + system CL) so both analyzer classes
 *       and Mondrian OLAP4J driver from lib/ are accessible</li>
 *   <li>Explicitly registers the Mondrian JDBC driver with DriverManager using the bundle's
 *       classloader to solve Class identity mismatch (system CL vs OSGi DynamicImport)</li>
 *   <li>Passes httprequest/httpresponse/cmd in pathParams to the content generator
 *       (prevents NPE in AnalyzerContentGenerator when extracting serverName)</li>
 * </ul>
 */
public class ContentGeneratorServlet extends HttpServlet {

  /**
   * 
   */
  private static final long serialVersionUID = 1L;
  private final ApplicationContext applicationContext;
  private final String beanId;

  public ContentGeneratorServlet( ApplicationContext applicationContext, String beanId ) {
    this.applicationContext = applicationContext;

    this.beanId = beanId;
  }

  @Override protected void service( HttpServletRequest req, HttpServletResponse resp )
      throws ServletException, IOException {

    // We are anonymous for now
    PentahoSessionHolder.setSession( new StandaloneSession( "bob" ) );

    IContentGenerator contentGenerator = (IContentGenerator) applicationContext.getBean( beanId );
    ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
    ClassLoader bundleClassLoader = applicationContext.getClassLoader();
    // Use a composite classloader so that both the bundle classes (e.g., analyzer plugin)
    // and system classpath classes (e.g., mondrian OLAP4J driver) are available
    Thread.currentThread().setContextClassLoader( new ClassLoader( bundleClassLoader ) {
      @Override
      protected Class<?> findClass( String name ) throws ClassNotFoundException {
        return originalClassLoader.loadClass( name );
      }
      @Override
      public java.net.URL getResource( String name ) {
        java.net.URL url = bundleClassLoader.getResource( name );
        return url != null ? url : originalClassLoader.getResource( name );
      }
      @Override
      public java.util.Enumeration<java.net.URL> getResources( String name ) throws java.io.IOException {
        java.util.Enumeration<java.net.URL> bundleResources = bundleClassLoader.getResources( name );
        java.util.Enumeration<java.net.URL> systemResources = originalClassLoader.getResources( name );
        java.util.List<java.net.URL> combined = new java.util.ArrayList<>();
        while ( bundleResources.hasMoreElements() ) {
          combined.add( bundleResources.nextElement() );
        }
        while ( systemResources.hasMoreElements() ) {
          combined.add( systemResources.nextElement() );
        }
        return java.util.Collections.enumeration( combined );
      }
    } );

    try {
      // Ensure the mondrian OLAP4J driver is registered with DriverManager using a Class
      // loaded by the BUNDLE classloader. DriverManager's isDriverAllowed() checks that
      // Class.forName(driverName, true, callerCL) returns the same Class as the registered
      // driver. Since the caller (MDXOlap4jConnection) is in this same bundle, the Class
      // identity will match only if the registered driver was loaded by the same classloader.
      try {
        Class<?> driverClass = bundleClassLoader.loadClass( "mondrian.olap4j.MondrianOlap4jDriver" );
        // Check if already registered by testing if DriverManager can find it
        boolean alreadyRegistered = false;
        try {
          java.sql.DriverManager.getDriver( "jdbc:mondrian:" );
          alreadyRegistered = true;
        } catch ( java.sql.SQLException ignored ) {
          // not registered yet
        }
        if ( !alreadyRegistered ) {
          java.sql.Driver driver = (java.sql.Driver) driverClass.getDeclaredConstructor().newInstance();
          java.sql.DriverManager.registerDriver( driver );
        }
      } catch ( Exception ignored ) {
        // mondrian not available - not all environments require it
      }

      OutputStream outputStream = resp.getOutputStream();

      // Use SimpleOutputHandler which only needs an OutputStream (no javax.servlet dependency
      // in its class hierarchy loaded from the system classpath)
      IOutputHandler outputHandler = new SimpleOutputHandler( outputStream, true );

      // Build parameter providers from request data without passing servlet objects
      // to system-classpath code
      Map<String, IParameterProvider> parameterProviders = new HashMap<>();

      // Request parameters
      SimpleParameterProvider requestParams = new SimpleParameterProvider();
      Enumeration<String> paramNames = req.getParameterNames();
      while ( paramNames.hasMoreElements() ) {
        String name = paramNames.nextElement();
        requestParams.setParameter( name, req.getParameter( name ) );
      }
      // Also set the input stream for POST body access
      requestParams.setParameter( "inputStream", req.getInputStream() );
      parameterProviders.put( IParameterProvider.SCOPE_REQUEST, requestParams );

      // Session parameters
      SimpleParameterProvider sessionParams = new SimpleParameterProvider();
      parameterProviders.put( IParameterProvider.SCOPE_SESSION, sessionParams );

      // Header parameters
      SimpleParameterProvider headerParams = new SimpleParameterProvider();
      Enumeration<String> headerNames = req.getHeaderNames();
      while ( headerNames.hasMoreElements() ) {
        String name = headerNames.nextElement();
        headerParams.setParameter( name, req.getHeader( name ) );
      }
      parameterProviders.put( "headers", headerParams );

      // Path parameters
      SimpleParameterProvider pathParams = new SimpleParameterProvider();
      pathParams.setParameter( "query", req.getQueryString() );
      pathParams.setParameter( "inputstream", req.getInputStream() );
      pathParams.setParameter( "httprequest", req );
      pathParams.setParameter( "httpresponse", resp );
      pathParams.setParameter( "remoteaddr", req.getRemoteAddr() );
      // pathInfo is provided directly by the servlet container (Pax Web 8 HTTP Whiteboard)
      // based on the registered servlet pattern (e.g., /content/analyzer/service/*)
      String pathInfo = req.getPathInfo();
      if ( pathInfo != null && pathInfo.startsWith( "/" ) ) {
        pathParams.setParameter( "cmd", pathInfo.substring( 1 ) );
      } else if ( pathInfo != null && !pathInfo.isEmpty() ) {
        pathParams.setParameter( "cmd", pathInfo );
      } else {
        pathParams.setParameter( "cmd", beanId.substring( beanId.lastIndexOf( "." ) + 1 ) );
      }
      parameterProviders.put( "path", pathParams );

      contentGenerator.setOutputHandler( outputHandler );
      contentGenerator.setParameterProviders( parameterProviders );
      contentGenerator.setSession( PentahoSessionHolder.getSession() );
      contentGenerator.createContent();

    } catch ( Exception e ) {
      throw new ServletException( "Error generating content", e );
    } finally {
      Thread.currentThread().setContextClassLoader( originalClassLoader );
    }
  }

  @Override protected void doGet( HttpServletRequest req, final HttpServletResponse resp )
      throws ServletException, IOException {
    service( req, resp );
  }

  @Override protected void doPost( HttpServletRequest req, HttpServletResponse resp )
      throws ServletException, IOException {
    service( req, resp );
  }
}
