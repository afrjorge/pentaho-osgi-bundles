/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2026 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2028-08-13
 ******************************************************************************/

package org.pentaho.platform.pdi.minispring;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.util.tracker.BundleTracker;
import org.osgi.util.tracker.BundleTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;

/**
 * The Mini Spring DM Extender.
 *
 * <p>A single, generic, pre-installed bundle that takes over the one job Spring DM used to do for
 * Pentaho platform plugins: watch for {@code ACTIVE} bundles that ship {@code META-INF/spring/*.xml},
 * build a single Spring {@link ApplicationContext} for each (via {@link BundleApplicationContextFactory}),
 * and publish that context as an OSGi service with property {@code Bundle-SymbolicName=<bsn>}.</p>
 *
 * <p>Downstream, the plugin's own Blueprint (e.g. analyzer's shipped
 * {@code OSGI-INF/blueprint/analyzer_beans.xml}) consumes the context through
 * {@code <reference interface="org.springframework.context.ApplicationContext"
 * filter="(Bundle-SymbolicName=&lt;bsn&gt;)"/>} &mdash; the original 10.2.0.1 SpringDM contract.
 * {@code SpringFileHandler} only adds the per-bean content-generator servlet whiteboard services
 * (it does <em>not</em> emit a second {@code <reference id="spring">}, which would be a duplicate
 * Blueprint component id). The reference makes servlet registration wait for the context to be
 * ready &mdash; the natural cold-start ordering guarantee.</p>
 */
public class MiniSpringExtender implements BundleActivator {

  private static final Logger logger = LoggerFactory.getLogger( MiniSpringExtender.class );

  static final String SPRING_XML_DIR = "META-INF/spring";
  static final String SPRING_XML_PATTERN = "*.xml";
  static final String BSN_PROPERTY = "Bundle-SymbolicName";

  private BundleContext bundleContext;
  private BundleTracker<Registration> tracker;

  @Override
  public void start( BundleContext context ) {
    this.bundleContext = context;
    try {
      logger.info( "Mini Spring extender starting; tracking bundles for {}/{}", SPRING_XML_DIR, SPRING_XML_PATTERN );
      tracker = new BundleTracker<>( context, Bundle.ACTIVE, new Customizer() );
      tracker.open();
    } catch ( Throwable t ) {
      logger.error( "Mini Spring extender failed to start", t );
    }
  }

  @Override
  public void stop( BundleContext context ) {
    if ( tracker != null ) {
      tracker.close();
      tracker = null;
    }
    logger.info( "Mini Spring extender stopped" );
  }

  private final class Customizer implements BundleTrackerCustomizer<Registration> {

    @Override
    public Registration addingBundle( Bundle bundle, BundleEvent event ) {
      // Never process ourselves or the Spring framework bundles.
      if ( bundle.getBundleId() == bundleContext.getBundle().getBundleId() ) {
        return null;
      }
      List<String> springXmls = listSpringXmls( bundle );
      if ( springXmls.isEmpty() ) {
        return null;
      }
      try {
        List<ClassLoader> springClassLoaders = collectSpringClassLoaders();
        ConfigurableApplicationContext ctx = BundleApplicationContextFactory.createContext(
            bundle, springXmls.toArray( new String[ 0 ] ), springClassLoaders );

        Dictionary<String, Object> props = new Hashtable<>();
        props.put( BSN_PROPERTY, bundle.getSymbolicName() );
        ServiceRegistration<?> reg =
            bundle.getBundleContext().registerService( ApplicationContext.class.getName(), ctx, props );
        logger.info( "Mini Spring extender: published ApplicationContext for '{}' ({} configs)",
            bundle.getSymbolicName(), springXmls.size() );
        return new Registration( ctx, reg );
      } catch ( Throwable t ) {
        logger.error( "Mini Spring extender: failed to build ApplicationContext for bundle '{}'",
            bundle.getSymbolicName(), t );
        return null;
      }
    }

    @Override
    public void modifiedBundle( Bundle bundle, BundleEvent event, Registration object ) {
      // no-op
    }

    @Override
    public void removedBundle( Bundle bundle, BundleEvent event, Registration object ) {
      if ( object != null ) {
        object.close( bundle );
      }
    }
  }

  /** Holds the published service + its context so they can be torn down together. */
  private static final class Registration {
    private final ConfigurableApplicationContext context;
    private final ServiceRegistration<?> registration;

    Registration( ConfigurableApplicationContext context, ServiceRegistration<?> registration ) {
      this.context = context;
      this.registration = registration;
    }

    void close( Bundle bundle ) {
      try {
        registration.unregister();
      } catch ( Exception ignore ) {
        // already gone
      }
      try {
        context.close();
      } catch ( Exception ignore ) {
        // best effort
      }
      logger.info( "Mini Spring extender: tore down ApplicationContext for '{}'", bundle.getSymbolicName() );
    }
  }

  private static List<String> listSpringXmls( Bundle bundle ) {
    List<String> result = new ArrayList<>();
    Enumeration<URL> entries = bundle.findEntries( SPRING_XML_DIR, SPRING_XML_PATTERN, false );
    if ( entries != null ) {
      while ( entries.hasMoreElements() ) {
        URL u = entries.nextElement();
        String path = u.getPath();
        int idx = path.indexOf( SPRING_XML_DIR );
        // Use a classpath-relative location so the composite classloader resolves it from the bundle.
        result.add( idx >= 0 ? path.substring( idx ) : path );
      }
    }
    Collections.sort( result );
    return result;
  }

  /**
   * Classloaders of any Spring Framework OSGi bundles, so namespace handlers ({@code util:},
   * {@code context:}) resolve. In PDI 10.2.0.X Spring (5.3.39) is exported to OSGi via
   * {@code etc/custom.properties} system packages rather than as discrete bundles, so this list is
   * normally empty; in that case {@link BundleApplicationContextFactory} falls back to the classloader
   * that defines Spring core (which has {@code spring.handlers}/{@code spring.schemas} on its
   * classpath). The scan is kept so the extender also works if Spring is ever shipped as bundles.
   */
  private List<ClassLoader> collectSpringClassLoaders() {
    List<ClassLoader> cls = new ArrayList<>();
    for ( Bundle b : bundleContext.getBundles() ) {
      String bsn = b.getSymbolicName();
      if ( bsn == null ) {
        continue;
      }
      if ( bsn.contains( "spring-beans" ) || bsn.contains( "spring-context" )
          || bsn.contains( "spring-core" ) || bsn.contains( "spring-expression" )
          || bsn.contains( ".spring-" ) ) {
        BundleWiring wiring = b.adapt( BundleWiring.class );
        if ( wiring != null && wiring.getClassLoader() != null ) {
          cls.add( wiring.getClassLoader() );
        }
      }
    }
    return cls;
  }
}

