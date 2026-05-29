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

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.wiring.BundleWiring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Factory that creates a Spring ApplicationContext using a bundle's classloader.
 * This replaces the need for Spring DM (Gemini Blueprint extender) which was removed
 * from PDI in 10.2.0.0-SNAPSHOT. Instead of having an extender watch for META-INF/spring/
 * files, we create the context directly in Blueprint via this factory.
 */
public class BundleApplicationContextFactory {

  private static final Logger logger = LoggerFactory.getLogger( BundleApplicationContextFactory.class );

  /**
   * Creates a Spring ApplicationContext using the given bundle's classloader.
   * Uses a composite classloader that delegates to:
   * 1. The target bundle (for bean classes and resources like beans.xml)
   * 2. The Spring Framework classloader (for META-INF/spring.handlers, spring.schemas)
   *
   * @param bundle the OSGi bundle whose classloader will be used for resource and class loading
   * @param configLocations Spring XML configuration locations (e.g., "META-INF/spring/beans.xml")
   * @return a fully initialized ApplicationContext
   */
  public static ApplicationContext createContext( Bundle bundle, String... configLocations ) {
    BundleWiring wiring = bundle.adapt( BundleWiring.class );
    if ( wiring == null ) {
      throw new IllegalStateException( "Bundle " + bundle.getSymbolicName() + " has no wiring (not resolved)" );
    }
    ClassLoader bundleClassLoader = wiring.getClassLoader();
    // Get the Spring Framework classloader (from the spring-beans bundle which has spring.handlers)
    ClassLoader springClassLoader = ClassPathXmlApplicationContext.class.getClassLoader();

    // Create a composite classloader that checks bundle first, then Spring
    ClassLoader compositeClassLoader = new ClassLoader( bundleClassLoader ) {
      @Override
      protected Class<?> findClass( String name ) throws ClassNotFoundException {
        try {
          return bundleClassLoader.loadClass( name );
        } catch ( ClassNotFoundException e ) {
          return springClassLoader.loadClass( name );
        }
      }

      @Override
      public URL getResource( String name ) {
        URL url = bundleClassLoader.getResource( name );
        if ( url == null ) {
          url = springClassLoader.getResource( name );
        }
        return url;
      }

      @Override
      public Enumeration<URL> getResources( String name ) throws IOException {
        List<URL> urls = new ArrayList<>();
        Enumeration<URL> bundleUrls = bundleClassLoader.getResources( name );
        while ( bundleUrls.hasMoreElements() ) {
          urls.add( bundleUrls.nextElement() );
        }
        Enumeration<URL> springUrls = springClassLoader.getResources( name );
        while ( springUrls.hasMoreElements() ) {
          urls.add( springUrls.nextElement() );
        }
        return Collections.enumeration( urls );
      }
    };

    logger.info( "Creating Spring ApplicationContext for bundle '{}' with configs: {}",
        bundle.getSymbolicName(), java.util.Arrays.toString( configLocations ) );

    ClassLoader originalCL = Thread.currentThread().getContextClassLoader();
    try {
      // Set composite classloader as TCCL so Spring uses it for:
      // 1. Loading bean classes (com.pentaho.analyzer.*)
      // 2. Resolving classpath: resources (analyzer/analyzer.properties)
      // 3. Loading Spring XML schema handlers (META-INF/spring.handlers)
      Thread.currentThread().setContextClassLoader( compositeClassLoader );

      ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext( configLocations, false );
      ctx.setClassLoader( compositeClassLoader );
      ctx.refresh();

      logger.info( "Successfully created Spring ApplicationContext for bundle '{}'", bundle.getSymbolicName() );
      return ctx;
    } catch ( Exception e ) {
      logger.error( "Failed to create Spring ApplicationContext for bundle '{}'", bundle.getSymbolicName(), e );
      throw new RuntimeException( "Failed to create ApplicationContext for " + bundle.getSymbolicName(), e );
    } finally {
      Thread.currentThread().setContextClassLoader( originalCL );
    }
  }
}

