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
import org.osgi.framework.wiring.BundleWiring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.core.io.UrlResource;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Builds a Spring {@link ConfigurableApplicationContext} for an OSGi bundle that ships
 * {@code META-INF/spring/*.xml}, reproducing what Spring DM (Gemini Blueprint) used to do.
 *
 * <p>Two pieces of legacy behaviour are restored here so that <strong>unmodified</strong> Pentaho
 * plugin Spring XML keeps working:</p>
 * <ol>
 *   <li>A <em>composite classloader</em> that delegates class/resource loading to the plugin bundle
 *       first and then to the classloader(s) that provide Spring. In PDI 10.2.0.X Spring (5.3.39) is
 *       exported to OSGi via {@code etc/custom.properties} system packages, so the effective "Spring
 *       classloader" is the one that defines Spring core (added as a fallback below); if Spring is ever
 *       shipped as discrete bundles their classloaders are passed in too. Crucially {@code getResources}
 *       is <em>merged</em> across all of them, so {@code META-INF/spring.handlers} /
 *       {@code spring.schemas} (e.g. {@code util:}, {@code context:}) are visible &mdash; this is what
 *       makes {@code <util:properties>} and {@code <context:annotation-config>} resolve without
 *       per-plugin rewrites.</li>
 *   <li>A {@code plugin:} resource protocol handler (re-implementing the old
 *       {@code PentahoOsgiBundleXmlApplicationContext.getResource()}), so legacy references such as
 *       {@code value="plugin:analyzer.properties"} resolve to the matching bundle entry.</li>
 * </ol>
 */
final class BundleApplicationContextFactory {

  private static final Logger logger = LoggerFactory.getLogger( BundleApplicationContextFactory.class );

  private static final String PLUGIN_PROTOCOL = "plugin:";

  private BundleApplicationContextFactory() {
  }

  /**
   * Create and refresh a Spring ApplicationContext from the given bundle's {@code META-INF/spring/*.xml}.
   *
   * @param bundle           the plugin bundle (must be resolved)
   * @param configLocations  classpath-relative locations, e.g. {@code META-INF/spring/beans.xml}
   * @param springClassLoaders classloaders of any Spring Framework OSGi bundles; may be empty when
   *                           Spring is provided via system packages (the Spring-core classloader is
   *                           always added as a fallback)
   * @return a refreshed, configurable application context
   */
  static ConfigurableApplicationContext createContext( final Bundle bundle,
                                                       final String[] configLocations,
                                                       final List<ClassLoader> springClassLoaders ) {
    BundleWiring wiring = bundle.adapt( BundleWiring.class );
    if ( wiring == null ) {
      throw new IllegalStateException( "Bundle " + bundle.getSymbolicName() + " has no wiring (not resolved)" );
    }
    final ClassLoader bundleClassLoader = wiring.getClassLoader();

    final List<ClassLoader> springCls = new ArrayList<>();
    if ( springClassLoaders != null ) {
      springCls.addAll( springClassLoaders );
    }
    // Always include the classloader that loaded Spring core in this extender as a fallback.
    springCls.add( ClassPathXmlApplicationContext.class.getClassLoader() );

    final ClassLoader compositeClassLoader = new ClassLoader( bundleClassLoader ) {
      @Override
      protected Class<?> findClass( String name ) throws ClassNotFoundException {
        try {
          return bundleClassLoader.loadClass( name );
        } catch ( ClassNotFoundException e ) {
          for ( ClassLoader cl : springCls ) {
            try {
              return cl.loadClass( name );
            } catch ( ClassNotFoundException ignore ) {
              // try next
            }
          }
          throw e;
        }
      }

      @Override
      public URL getResource( String name ) {
        URL url = bundleClassLoader.getResource( name );
        if ( url == null ) {
          for ( ClassLoader cl : springCls ) {
            url = cl.getResource( name );
            if ( url != null ) {
              break;
            }
          }
        }
        return url;
      }

      @Override
      public Enumeration<URL> getResources( String name ) throws IOException {
        List<URL> urls = new ArrayList<>();
        addAll( urls, bundleClassLoader.getResources( name ) );
        for ( ClassLoader cl : springCls ) {
          addAll( urls, cl.getResources( name ) );
        }
        return Collections.enumeration( urls );
      }

      private void addAll( List<URL> target, Enumeration<URL> e ) {
        if ( e == null ) {
          return;
        }
        while ( e.hasMoreElements() ) {
          URL u = e.nextElement();
          if ( !target.contains( u ) ) {
            target.add( u );
          }
        }
      }
    };

    logger.info( "Mini Spring extender: creating ApplicationContext for bundle '{}' from {}",
        bundle.getSymbolicName(), java.util.Arrays.toString( configLocations ) );

    final ClassLoader originalCL = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader( compositeClassLoader );

      ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext( configLocations, false );
      ctx.setClassLoader( compositeClassLoader );
      // Restore the legacy 'plugin:' resource protocol without subclassing the context (subclassing
      // triggers a LinkageError when the bundle's view of org.springframework.core.io.Resource and the
      // context superclass' view come from different classloaders). A ProtocolResolver is resolved
      // through the same classloader as the context, so it is loader-constraint safe.
      ctx.addProtocolResolver( ( location, resourceLoader ) -> {
        if ( location != null && location.startsWith( PLUGIN_PROTOCOL ) ) {
          String rel = location.substring( PLUGIN_PROTOCOL.length() );
          URL found = findBundleEntry( bundle, rel );
          if ( found != null ) {
            return new UrlResource( found );
          }
          logger.warn( "Mini Spring extender: could not resolve '{}' in bundle '{}'", location,
              bundle.getSymbolicName() );
        }
        return null;
      } );
      ctx.refresh();

      logger.info( "Mini Spring extender: ApplicationContext ready for bundle '{}'", bundle.getSymbolicName() );
      return ctx;
    } finally {
      Thread.currentThread().setContextClassLoader( originalCL );
    }
  }

  /**
   * Resolve a {@code plugin:}-relative resource by searching the bundle for an entry whose path ends
   * with the requested name (e.g. {@code analyzer.properties} -&gt; {@code /analyzer/analyzer.properties}).
   */
  private static URL findBundleEntry( Bundle bundle, String rel ) {
    String fileName = rel;
    int slash = fileName.lastIndexOf( '/' );
    if ( slash >= 0 ) {
      fileName = fileName.substring( slash + 1 );
    }
    Enumeration<URL> entries = bundle.findEntries( "/", fileName, true );
    if ( entries != null && entries.hasMoreElements() ) {
      return entries.nextElement();
    }
    // Fall back to a direct entry lookup.
    URL direct = bundle.getEntry( rel );
    if ( direct == null && !rel.startsWith( "/" ) ) {
      direct = bundle.getEntry( "/" + rel );
    }
    return direct;
  }
}

