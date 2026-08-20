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
import org.junit.Before;
import org.junit.Test;
import org.pentaho.requirejs.IRequireJsPackageConfiguration;
import org.pentaho.requirejs.impl.listeners.RequireJsBundleListener;
import org.pentaho.requirejs.impl.listeners.RequireJsPackageServiceTracker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.internal.verification.VerificationModeFactory.atLeastOnce;
import static org.mockito.internal.verification.VerificationModeFactory.times;

public class RequireJsConfigManagerTest {
  private String baseUrl;

  private RequireJsConfigManager requireJsConfigManager;
  private RequireJsPackageServiceTracker mockPackageConfigurationsTracker;

  private HashMap<String, String> packageConfigurationMapping;

  /**
   * {@code mock( Future.class )} and {@code mock( Callable.class )} can only produce a raw {@code Future}/
   * {@code Callable}, since {@code Future.class}/{@code Callable.class} are themselves raw {@code Class} literals -
   * assigning the result to a parameterized type is therefore an unchecked conversion no matter how it's written.
   * Centralizing the cast here keeps the suppression to a single spot instead of one per mock call.
   */
  @SuppressWarnings( "unchecked" )
  private static <T> T uncheckedMock( Class<?> rawClass ) {
    return (T) mock( rawClass );
  }

  @Before
  public void setup() {
    this.baseUrl = "/default/base/url/";

    this.mockPackageConfigurationsTracker = mock( RequireJsPackageServiceTracker.class );

    this.requireJsConfigManager = new RequireJsConfigManager();

    this.requireJsConfigManager.setPackageConfigurationsTracker( this.mockPackageConfigurationsTracker );
    this.requireJsConfigManager.setExternalResourcesScriptsTracker( mock( RequireJsBundleListener.class ) );
    this.requireJsConfigManager.setPlugins( new ArrayList<>() );
  }

  /**
   * [PDI-20686] A concurrent invalidation cancels the future this call is waiting on - routine while bundles are
   * still registering their packages, most visibly during a KAR hot deploy. Retrying has to pick up the newly
   * scheduled build instead of letting the unchecked exception escape into the servlet.
   */
  @Test
  public void testGetRequireJsConfigRetriesAfterACancellation() throws ExecutionException, InterruptedException {
    RequireJsConfigManager spyed = spy( this.requireJsConfigManager );

    Future<String> cancelled = uncheckedMock( Future.class );
    doThrow( CancellationException.class ).when( cancelled ).get();

    Future<String> rebuilt = uncheckedMock( Future.class );
    doReturn( "requireCfg" ).when( rebuilt ).get();

    doReturn( cancelled ).doReturn( rebuilt ).when( spyed ).getCachedConfiguration( this.baseUrl );

    String config = spyed.getRequireJsConfig( this.baseUrl );

    assertEquals( "requireCfg", config );

    // The whole cache must not be thrown away over one canceled entry, only that entry is dropped.
    verify( spyed, times( 0 ) ).invalidateCachedConfigurations();
  }

  @Test
  public void testGetRequireJsConfigReportsAnExhaustedCancellationRetry()
    throws ExecutionException, InterruptedException {
    RequireJsConfigManager spyed = spy( this.requireJsConfigManager );

    Future<String> cancelled = uncheckedMock( Future.class );
    doThrow( CancellationException.class ).when( cancelled ).get();
    doReturn( cancelled ).when( spyed ).getCachedConfiguration( this.baseUrl );

    String config = spyed.getRequireJsConfig( this.baseUrl );

    // A CancellationException has neither a cause nor a message, so without naming the exception itself the
    // served config would be an unattributable "unknown error" - and nothing is logged anywhere.
    assertTrue( config, config.contains( "Error computing RequireJS Config: " + CancellationException.class.getName() ) );

    verify( spyed, times( 3 ) ).getCachedConfiguration( this.baseUrl );
    verify( spyed, times( 0 ) ).invalidateCachedConfigurations();
  }

  @Test
  public void testGetRequireJsConfigWithoutAnyExceptionReportsAnUnknownError()
    throws ExecutionException, InterruptedException {
    RequireJsConfigManager spyed = spy( this.requireJsConfigManager );

    Future<String> mockFuture = uncheckedMock( Future.class );
    doReturn( null ).when( mockFuture ).get();
    doReturn( mockFuture ).when( spyed ).getCachedConfiguration( this.baseUrl );

    String config = spyed.getRequireJsConfig( this.baseUrl );

    assertEquals( "{}; // Error computing RequireJS Config: unknown error", config );
  }

  @Test
  public void testDestroy() {
    RequireJsConfigManager spyed = spy( this.requireJsConfigManager );

    spyed.destroy();

    // check that invalidateCachedConfigurations is called
    verify( spyed, times( 1 ) ).invalidateCachedConfigurations();
  }

  @Test
  public void testGetRequireJsConfigTimeout() throws ExecutionException, InterruptedException {
    RequireJsConfigManager spyed = spy( this.requireJsConfigManager );

    Future<String> mockFuture = uncheckedMock( Future.class );
    doThrow( InterruptedException.class ).when( mockFuture ).get();
    doReturn( mockFuture ).when( spyed ).getCachedConfiguration( this.baseUrl );

    String config = spyed.getRequireJsConfig( this.baseUrl );

    assertTrue( config.contains( "Error computing RequireJS Config" ) );

    // check that invalidateCachedConfigurations is not called
    verify( spyed, times( 0 ) ).invalidateCachedConfigurations();
  }

  @Test
  public void testGetRequireJsConfigException() throws ExecutionException, InterruptedException {
    RequireJsConfigManager spyed = spy( this.requireJsConfigManager );

    Future<String> mockFuture = uncheckedMock( Future.class );
    doThrow( ExecutionException.class ).when( mockFuture ).get();
    doReturn( mockFuture ).when( spyed ).getCachedConfiguration( this.baseUrl );

    String config = spyed.getRequireJsConfig( this.baseUrl );

    assertTrue( config.contains( "Error computing RequireJS Config" ) );

    // check that invalidateCachedConfigurations is called
    verify( spyed, atLeastOnce() ).invalidateCachedConfigurations();
  }

  @Test
  public void testGetRequireJsConfigExceptionWithCause() throws ExecutionException, InterruptedException {
    RequireJsConfigManager spyed = spy( this.requireJsConfigManager );

    Future<String> mockFuture = uncheckedMock( Future.class );
    ExecutionException executionException = new ExecutionException( new RuntimeException( "The cause" ) );
    doThrow( executionException ).when( mockFuture ).get();
    doReturn( mockFuture ).when( spyed ).getCachedConfiguration( this.baseUrl );

    String config = spyed.getRequireJsConfig( this.baseUrl );

    assertTrue( config.contains( "Error computing RequireJS Config: The cause" ) );

    // check that invalidateCachedConfigurations is called
    verify( spyed, atLeastOnce() ).invalidateCachedConfigurations();
  }

  @Test
  public void testGetRequireJsConfig() throws Exception {
    RequireJsConfigManager spyed = spy( this.requireJsConfigManager );

    Callable<String> mockCallable = uncheckedMock( Callable.class );
    doReturn( "The content of the requirejs configuration script" ).when( mockCallable ).call();

    doReturn( mockCallable ).when( spyed ).createRebuildCacheCallable( this.baseUrl );

    String config = spyed.getRequireJsConfig( this.baseUrl );

    assertEquals( "The content of the requirejs configuration script", config );

    verify( spyed, times( 1 ) ).createRebuildCacheCallable( this.baseUrl );
  }

  @Test
  public void testGetRequireJsConfigBaseUrlNormalization() {
    RequireJsConfigManager spyed = spy( this.requireJsConfigManager );

    spyed.getRequireJsConfig( "/base1/" );
    verify( spyed, times( 1 ) ).getCachedConfiguration( "/base1/" );

    spyed.getRequireJsConfig( "/base1" );
    verify( spyed, times( 2 ) ).getCachedConfiguration( "/base1/" );
  }

  @Test
  public void testGetRequireJsConfigCache() throws Exception {
    RequireJsConfigManager spyed = spy( this.requireJsConfigManager );

    Callable<String> mockCallable = uncheckedMock( Callable.class );
    doReturn( "The content of the requirejs configuration script" ).when( mockCallable ).call();

    doReturn( mockCallable ).when( spyed ).createRebuildCacheCallable( anyString() );

    spyed.getRequireJsConfig( "/base1/" );
    verify( spyed, times( 1 ) ).createRebuildCacheCallable( "/base1/" );
    verify( spyed, times( 0 ) ).createRebuildCacheCallable( "/base2/" );

    spyed.getRequireJsConfig( "/base2/" );
    verify( spyed, times( 1 ) ).createRebuildCacheCallable( "/base1/" );
    verify( spyed, times( 1 ) ).createRebuildCacheCallable( "/base2/" );

    spyed.getRequireJsConfig( "/base1/" );
    verify( spyed, times( 1 ) ).createRebuildCacheCallable( "/base1/" );
    verify( spyed, times( 1 ) ).createRebuildCacheCallable( "/base2/" );

    spyed.getRequireJsConfig( "/base2/" );
    verify( spyed, times( 1 ) ).createRebuildCacheCallable( "/base1/" );
    verify( spyed, times( 1 ) ).createRebuildCacheCallable( "/base2/" );
  }

  @Test
  public void testGetContextMappingKnownReferer() {
    Collection<IRequireJsPackageConfiguration> requireJsPackages = new ArrayList<>();
    requireJsPackages.add( createRequireJsPackageConfigurationMock( "package/1.0" ) );
    doReturn( requireJsPackages ).when( this.mockPackageConfigurationsTracker ).getPackages();

    String config = this.requireJsConfigManager.getContextMapping( this.baseUrl, "/default/base/url/package/1.0/index.html" );

    HashMap<String, Object> topMap = new HashMap<>();
    topMap.put( "*", packageConfigurationMapping );

    HashMap<String, Object> requireConfig = new HashMap<>();
    requireConfig.put( "map", topMap );

    assertEquals( "Configuration for context mapping should be the same of the package", JSONObject.toJSONString( requireConfig ), config );
  }

  @Test
  public void testGetContextMappingNullReferer() {
    String config = this.requireJsConfigManager.getContextMapping( this.baseUrl, null );

    assertNull( "No configuration for context mapping should be returned", config );
  }

  @Test
  public void testGetContextMappingEmptyReferer() {
    String config = this.requireJsConfigManager.getContextMapping( this.baseUrl, "" );

    assertNull( "No configuration for context mapping should be returned", config );
  }

  @Test
  public void testGetContextMappingUnknownReferer() {
    Collection<IRequireJsPackageConfiguration> requireJsPackages = new ArrayList<>();
    requireJsPackages.add( createRequireJsPackageConfigurationMock( "package/1.0" ) );
    doReturn( requireJsPackages ).when( this.mockPackageConfigurationsTracker ).getPackages();

    String config = this.requireJsConfigManager.getContextMapping( this.baseUrl, "/something/index.html" );

    assertNull( "No configuration for context mapping should be returned", config );
  }

  @Test
  public void testGetContextMappingCache() {
    Collection<IRequireJsPackageConfiguration> requireJsPackages = new ArrayList<>();
    IRequireJsPackageConfiguration packageA = createRequireJsPackageConfigurationMock( "packageA/1.0" );
    IRequireJsPackageConfiguration packageB = createRequireJsPackageConfigurationMock( "packageB/1.5" );
    requireJsPackages.add( packageA );
    requireJsPackages.add( packageB );
    doReturn( requireJsPackages ).when( this.mockPackageConfigurationsTracker ).getPackages();

    this.requireJsConfigManager.getContextMapping( this.baseUrl, "/default/base/url/packageA/1.0/index.html" );

    verify( packageA, times( 1 ) ).getModuleIdsMapping();
    verify( packageB, times( 0 ) ).getModuleIdsMapping();

    this.requireJsConfigManager.getContextMapping( this.baseUrl, "/default/base/url/packageB/1.5/index.html" );

    verify( packageA, times( 1 ) ).getModuleIdsMapping();
    verify( packageB, times( 1 ) ).getModuleIdsMapping();

    this.requireJsConfigManager.getContextMapping( this.baseUrl, "/default/base/url/packageA/1.0/index.html" );

    verify( packageA, times( 1 ) ).getModuleIdsMapping();
    verify( packageB, times( 1 ) ).getModuleIdsMapping();

    this.requireJsConfigManager.getContextMapping( this.baseUrl, "/default/base/url/packageB/1.5/index.html" );

    verify( packageA, times( 1 ) ).getModuleIdsMapping();
    verify( packageB, times( 1 ) ).getModuleIdsMapping();
  }

  private IRequireJsPackageConfiguration createRequireJsPackageConfigurationMock(String webRootPath ) {
    IRequireJsPackageConfiguration config = mock( IRequireJsPackageConfiguration.class );
    doReturn( webRootPath ).when( config ).getWebRootPath();

    packageConfigurationMapping = new HashMap<>();
    packageConfigurationMapping.put( "depA", "depA@1.0" );
    packageConfigurationMapping.put( "depA/hi", "depA@1.0/depA/hi" );
    packageConfigurationMapping.put( "depA/hello", "depA@1.0/depA/hello" );

    doReturn( packageConfigurationMapping ).when( config ).getModuleIdsMapping();

    return config;
  }
}
