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

package org.pentaho.requirejs.impl;

import org.json.simple.JSONObject;
import org.pentaho.requirejs.IRequireJsPackageConfiguration;
import org.pentaho.requirejs.IRequireJsPackageConfigurationPlugin;
import org.pentaho.requirejs.impl.listeners.RequireJsBundleListener;
import org.pentaho.requirejs.impl.listeners.RequireJsPackageServiceTracker;
import org.pentaho.requirejs.impl.servlet.RebuildCacheCallable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RequireJsConfigManager {
  private static final ScheduledExecutorService executorService =
      Executors.newScheduledThreadPool( 2, r -> {
        Thread thread = Executors.defaultThreadFactory().newThread( r );
        thread.setDaemon( true );
        thread.setName( "RequireJSConfigManager pool" );
        return thread;
      } );

  private RequireJsPackageServiceTracker packageConfigurationsTracker;
  private RequireJsBundleListener externalResourcesScriptsTracker;

  /**
   * Plugins that can customize each package's requirejs configuration.
   */
  private List<IRequireJsPackageConfigurationPlugin> plugins;

  // setting initial capacity to three (relative url and absolute http/https url scenarios)
  private final ConcurrentHashMap<String, Future<String>> cachedConfigurations = new ConcurrentHashMap<>( 3 );
  private final ConcurrentHashMap<String, String> cachedContextMapping = new ConcurrentHashMap<>();

  public void setPackageConfigurationsTracker( RequireJsPackageServiceTracker packageConfigurationsTracker ) {
    this.packageConfigurationsTracker = packageConfigurationsTracker;
  }

  public void setExternalResourcesScriptsTracker( RequireJsBundleListener externalResourcesScriptsTracker ) {
    this.externalResourcesScriptsTracker = externalResourcesScriptsTracker;
  }

  public void setPlugins( List<IRequireJsPackageConfigurationPlugin> plugins ) {
    this.plugins = plugins;
  }

  public void destroy() {
    this.invalidateCachedConfigurations();
  }

  public String getRequireJsConfig( String baseUrl ) {
    // Make sure the baseUrl ends in a slash.
    // like https://github.com/requirejs/requirejs/blob/14526943c937aab3c022235335f20e260395fe15/require.js#L1145
    baseUrl = baseUrl.endsWith( "/" ) ? baseUrl : baseUrl + "/";

    String result = null;
    int tries = 3;
    Exception lastException = null;
    while ( tries-- > 0 && result == null ) {
      Future<String> cache = this.getCachedConfiguration( baseUrl );

      try {
        result = cache.get();
      } catch ( InterruptedException e ) {
        // ignore
      } catch ( CancellationException e ) {
        // [PDI-20686] A concurrent invalidateCachedConfigurations() canceled the future this call was
        // waiting on. That is routine whenever bundles are still registering their RequireJS packages -
        // most visibly while a KAR is being hot deployed - and is not an error: the configuration simply
        // changed while it was being computed.
        //
        // CancellationException is unchecked, so before this catch existed it escaped the retry loop and
        // propagated out of RequireJsConfigServlet.doGet(). By then the servlet has already written ~88 KB
        // of boilerplate to the response, so it is committed and the container cannot turn the failure into
        // an HTTP 500: the client receives HTTP 200 with a truncated, syntactically invalid script.
        //
        // Retrying instead picks up the newly scheduled build. The canceled entry is dropped first, in case
        // this thread observed it between invalidateCachedConfigurations()'s cancel() and its clear().
        lastException = e;

        this.cachedConfigurations.remove( baseUrl, cache );
      } catch ( ExecutionException e ) {
        lastException = e;

        this.invalidateCachedConfigurations();
      }
    }

    if ( result == null ) {
      result = "{}; // Error computing RequireJS Config: ";
      if ( lastException != null && lastException.getCause() != null ) {
        result += lastException.getCause().getMessage();
      } else if ( lastException != null ) {
        // [PDI-20686] A CancellationException carries neither a cause nor a message, so the previous
        // "unknown error" left an exhausted retry indistinguishable from any other failure - and since
        // this class has no logger and the exception is no longer propagated, that was the only trace
        // left anywhere. toString() at least names the exception type in the served response.
        result += lastException.toString();
      } else {
        result += "unknown error";
      }
    }

    return result;
  }

  public String getContextMapping( String baseUrl, String referer ) {
    return this.getCachedContextMapping( baseUrl, referer );
  }

  public void invalidateCachedConfigurations() {
    this.cachedConfigurations.forEach( ( s, stringFuture ) -> stringFuture.cancel( true ) );
    this.cachedConfigurations.clear();

    this.cachedContextMapping.clear();
  }

  Future<String> getCachedConfiguration( String baseUrl ) {
    return this.cachedConfigurations.computeIfAbsent( baseUrl, key -> executorService.schedule( createRebuildCacheCallable( key ), 250, TimeUnit.MILLISECONDS ) );
  }

  private String getCachedContextMapping( String baseUrl, String referer ) {
    if ( referer != null ) {
      return this.cachedContextMapping.computeIfAbsent( referer, k -> {
        List<IRequireJsPackageConfiguration> requireJsPackageConfigurations = this.packageConfigurationsTracker.getPackages();

        for ( IRequireJsPackageConfiguration requireJsPackage : requireJsPackageConfigurations ) {
          String webRootPath = requireJsPackage.getWebRootPath();
          // Compare values in lowercase to ensure that contains works well, because some http clients force requests to lowercase
          if ( webRootPath != null && !webRootPath.isEmpty() && referer.toLowerCase().contains( (baseUrl + webRootPath).toLowerCase() ) ) {
            Map<String, Object> contextConfig = new HashMap<>();
            Map<String, Map<String, String>> topMap = new HashMap<>();
            Map<String, String> map = new HashMap<>( requireJsPackage.getModuleIdsMapping() );

            topMap.put( "*", map );
            contextConfig.put( "map", topMap );

            return JSONObject.toJSONString( contextConfig );
          }
        }

        return null;
      } );
    }

    return null;
  }

  // region package-private factory methods for unit testing
  Callable<String> createRebuildCacheCallable( String baseUrl ) {
    return new RebuildCacheCallable( baseUrl, this.packageConfigurationsTracker.getPackages(), this.externalResourcesScriptsTracker.getScripts(), this.plugins );
  }
  // endregion
}
