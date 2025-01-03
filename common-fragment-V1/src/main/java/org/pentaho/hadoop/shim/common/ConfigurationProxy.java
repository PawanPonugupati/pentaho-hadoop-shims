/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2029-07-20
 ******************************************************************************/


package org.pentaho.hadoop.shim.common;

import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputFormat;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.security.UserGroupInformation;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.hadoop.shim.api.internal.mapred.RunningJob;
import org.pentaho.hadoop.shim.common.mapred.RunningJobProxy;
import org.pentaho.hadoop.shim.ShimConfigsLoader;

import java.io.IOException;

/**
 * A common configuration object representing org.apache.hadoop.conf.Configuration. <p> This has been un-deprecated in
 * future version of Hadoop and thus the deprecation warning can be safely ignored. </p>
 */
@SuppressWarnings( { "unchecked", "rawtypes" } )
public class ConfigurationProxy extends org.apache.hadoop.mapred.JobConf implements
  org.pentaho.hadoop.shim.api.internal.Configuration {

  public ConfigurationProxy() {
    super();
    addResource( "hdfs-site.xml" );
  }

  public ConfigurationProxy( String namedCluster ) {
    super();
    addConfigsAsResources( namedCluster );
  }

  @VisibleForTesting
  void addConfigsAsResources( String additionalPath ) {
    ShimConfigsLoader.addConfigsAsResources( additionalPath, this::addResource,
      ShimConfigsLoader.ClusterConfigNames.CORE_SITE,
      ShimConfigsLoader.ClusterConfigNames.MAPRED_SITE,
      ShimConfigsLoader.ClusterConfigNames.HDFS_SITE,
      ShimConfigsLoader.ClusterConfigNames.YARN_SITE );
  }

  /*
   * Wrap the call to {@link super#setMapperClass(Class)} to avoid generic type
   * mismatches. We do not expose {@link org.apache.hadoop.mapred.*} classes through
   * the API or provide proxies for them. This pattern is used for many of the
   * class setter methods in this implementation.
   */

  @Override
  public void setMapperClass( Class c ) {
    super.setMapperClass( (Class<? extends Mapper>) c );
  }

  @Override
  public void setCombinerClass( Class c ) {
    super.setCombinerClass( (Class<? extends Reducer>) c );
  }

  @Override
  public void setReducerClass( Class c ) {
    super.setReducerClass( (Class<? extends Reducer>) c );
  }

  @Override
  public void setMapRunnerClass( String className ) {
    super.set( "mapred.map.runner.class", className );
  }

  @Override
  public void setInputFormat( Class c ) {
    super.setInputFormat( (Class<? extends InputFormat>) c );
  }

  @Override
  public void setOutputFormat( Class c ) {
    super.setOutputFormat( (Class<? extends OutputFormat>) c );
  }

  @Override
  public String getDefaultFileSystemURL() {
    return get( "fs.default.name", "" );
  }

  /**
   * Hack Return this configuration as was asked with provided delegate class (If it is possible).
   *
   * @param delegate class of desired return object
   * @return this configuration delegate object if possible
   */
  @Override
  public <T> T getAsDelegateConf( Class<T> delegate ) {
    if ( delegate.isAssignableFrom( this.getClass() ) ) {
      return (T) this;
    } else {
      return null;
    }
  }

  /**
   * Submit job for the current configuration provided by this implementation.
   *
   * @return RunningJob implementation
   */
  @Override public RunningJob submit() throws IOException, ClassNotFoundException, InterruptedException {
    JobClient jobClient = createJobClient();
    if ( YarnQueueAclsVerifier.verify( jobClient.getQueueAclsForCurrentUser() ) ) {
      return new RunningJobProxy( jobClient.submitJob( this ) );
    } else {
      throw new YarnQueueAclsException( BaseMessages.getString( ConfigurationProxy.class,
        "ConfigurationProxy.UserHasNoPermissions", UserGroupInformation.getCurrentUser().getUserName() ) );
    }
  }

  JobClient createJobClient() throws IOException {
    return new JobClient( this );
  }

  @Override
  public void setInputPaths( org.pentaho.hadoop.shim.api.internal.fs.Path... paths ) {
    if ( paths == null ) {
      return;
    }
    Path[] actualPaths = new Path[ paths.length ];
    for ( int i = 0; i < paths.length; i++ ) {
      actualPaths[ i ] = ShimUtils.asPath( paths[ i ] );
    }
    FileInputFormat.setInputPaths( this, actualPaths );
  }

  @Override
  public void setOutputPath( org.pentaho.hadoop.shim.api.internal.fs.Path path ) {
    FileOutputFormat.setOutputPath( this, ShimUtils.asPath( path ) );
  }
}
