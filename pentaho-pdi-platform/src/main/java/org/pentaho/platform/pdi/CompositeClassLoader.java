package org.pentaho.platform.pdi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * A class loader that looks up classes and resources in a primary class loader first and falls back to
 * a secondary one, merging the results of {@link #getResources(String)}.
 *
 * <p>[PDI-20686] OSGi-deployed Pentaho platform plugins need to see both their own bundle content and
 * what PDI ships in {@code lib/}, which is only reachable through the class loader that launched Karaf.</p>
 *
 * <p>Merging {@link #getResources(String)} is the important part. Spring discovers its XML namespace
 * handlers by enumerating <em>every</em> {@code META-INF/spring.handlers} and
 * {@code META-INF/spring.schemas} visible to the bean class loader, so a plugin's Spring XML using
 * {@code util:} or {@code context:} would fail to parse if only the bundle's own copies were visible.
 * Observed fall-throughs to the secondary loader in a running PDI include the Spring XSDs and
 * {@code META-INF/services/org.apache.commons.logging.LogFactory}.</p>
 *
 * <p>[PDI-20686] Either delegate may outlive the bundle revision it belongs to: an instance of this
 * loader can be captured as the context class loader of a pooled thread (Mondrian's segment loader pool,
 * for one) and survive an un/redeploy. Felix answers a request on a disposed revision with a
 * {@code NullPointerException} out of {@code BundleRevisionImpl.getResourcesLocal} rather than with an
 * empty result, which surfaces far from here — as {@code Unable to load file step-attributes.xml}, for
 * instance. A dead delegate is therefore treated as one that has nothing to contribute, so the other
 * one can still answer.</p>
 */
class CompositeClassLoader extends ClassLoader {

  private static final Logger LOGGER = LoggerFactory.getLogger( CompositeClassLoader.class );

  private final ClassLoader primary;
  private final ClassLoader secondary;

  CompositeClassLoader( ClassLoader primary, ClassLoader secondary ) {
    super( primary );
    this.primary = primary;
    this.secondary = secondary;
  }

  @Override
  protected Class<?> loadClass( String name, boolean resolve ) throws ClassNotFoundException {
    try {
      return super.loadClass( name, resolve );
    } catch ( NullPointerException e ) {
      // The primary is delegated to by the superclass before findClass() is reached, so this is the only
      // place a dead primary can be caught on the class path.
      logDisposedDelegate( primary, name, e );
      Class<?> loaded = secondary.loadClass( name );
      if ( resolve ) {
        resolveClass( loaded );
      }
      return loaded;
    }
  }

  @Override
  protected Class<?> findClass( String name ) throws ClassNotFoundException {
    try {
      return secondary.loadClass( name );
    } catch ( NullPointerException e ) {
      logDisposedDelegate( secondary, name, e );
      throw new ClassNotFoundException( name, e );
    }
  }

  @Override
  public URL getResource( String name ) {
    URL url = getResource( primary, name );
    return url != null ? url : getResource( secondary, name );
  }

  @Override
  public Enumeration<URL> getResources( String name ) throws IOException {
    List<URL> urls = new ArrayList<>();
    addAll( urls, getResources( primary, name ) );
    addAll( urls, getResources( secondary, name ) );
    return Collections.enumeration( urls );
  }

  private URL getResource( ClassLoader delegate, String name ) {
    try {
      return delegate.getResource( name );
    } catch ( NullPointerException e ) {
      logDisposedDelegate( delegate, name, e );
      return null;
    }
  }

  private Enumeration<URL> getResources( ClassLoader delegate, String name ) throws IOException {
    try {
      return delegate.getResources( name );
    } catch ( NullPointerException e ) {
      logDisposedDelegate( delegate, name, e );
      return null;
    }
  }

  private static void logDisposedDelegate( ClassLoader delegate, String name, NullPointerException e ) {
    LOGGER.debug( "Ignoring class loader {} while looking up '{}': its bundle revision appears to have been "
        + "disposed, most likely by an un/redeploy", delegate, name, e );
  }

  private static void addAll( List<URL> target, Enumeration<URL> source ) {
    while ( source != null && source.hasMoreElements() ) {
      URL url = source.nextElement();
      if ( !target.contains( url ) ) {
        target.add( url );
      }
    }
  }
}
