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


package org.pentaho.big.data.impl.shim.mapreduce;

import com.google.common.annotations.VisibleForTesting;
import com.thoughtworks.xstream.XStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemConfigBuilder;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.security.UserGroupInformation;
import org.pentaho.hadoop.shim.ShimRuntimeException;
import org.pentaho.hadoop.shim.api.cluster.NamedCluster;
import org.pentaho.bigdata.api.mapreduce.MapReduceTransformations;
import org.pentaho.bigdata.api.mapreduce.PentahoMapReduceOutputStepMetaInterface;

import org.pentaho.di.core.CheckResultInterface;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.exception.KettleFileException;
import org.pentaho.di.core.logging.BaseLogTable;
import org.pentaho.di.core.logging.ChannelLogTable;
import org.pentaho.di.core.logging.LogChannelInterface;
import org.pentaho.di.core.logging.LogLevel;
import org.pentaho.di.core.logging.MetricsLogTable;
import org.pentaho.di.core.logging.PerformanceLogTable;
import org.pentaho.di.core.logging.StepLogTable;
import org.pentaho.di.core.logging.TransLogTable;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.util.Utils;
import org.pentaho.di.core.variables.VariableSpace;
import org.pentaho.di.core.variables.Variables;
import org.pentaho.di.core.vfs.KettleVFS;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransConfiguration;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.pentaho.di.version.BuildVersion;
import org.pentaho.hadoop.PluginPropertiesUtil;
import org.pentaho.hadoop.shim.ShimConfigsLoader;
import org.pentaho.hadoop.shim.api.internal.Configuration;
import org.pentaho.hadoop.shim.api.internal.fs.FileSystem;
import org.pentaho.hadoop.shim.api.internal.fs.Path;
import org.pentaho.hadoop.shim.api.mapreduce.MapReduceJobAdvanced;
import org.pentaho.hadoop.shim.api.mapreduce.PentahoMapReduceJobBuilder;
import org.pentaho.hadoop.shim.common.DistributedCacheUtilImpl;
import org.pentaho.hadoop.shim.spi.HadoopShim;
import org.pentaho.metastore.api.IMetaStore;
import org.pentaho.metastore.api.exceptions.MetaStoreException;
import org.pentaho.metastore.stores.xml.XmlMetaStore;
import org.pentaho.metastore.stores.xml.XmlUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Created by bryan on 1/8/16.
 */
public class PentahoMapReduceJobBuilderImpl extends MapReduceJobBuilderImpl implements PentahoMapReduceJobBuilder {
  public static final Class<?> PKG = PentahoMapReduceJobBuilderImpl.class;
  public static final String MAPREDUCE_APPLICATION_CLASSPATH = "mapreduce.application.classpath";
  public static final String DEFAULT_MAPREDUCE_APPLICATION_CLASSPATH =
    "$HADOOP_MAPRED_HOME/share/hadoop/mapreduce/*,$HADOOP_MAPRED_HOME/share/hadoop/mapreduce/lib/*";
  public static final String PENTAHO_MAPREDUCE_PROPERTY_USE_DISTRIBUTED_CACHE = "pmr.use.distributed.cache";
  public static final String PENTAHO_MAPREDUCE_PROPERTY_CREATE_UNIQUE_METASTORE_DIR = "pmr.create.unique.metastore.dir";
  public static final String PENTAHO_MAPREDUCE_PROPERTY_PMR_LIBRARIES_ARCHIVE_FILE = "pmr.libraries.archive.file";
  public static final String PENTAHO_MAPREDUCE_PROPERTY_KETTLE_HDFS_INSTALL_DIR = "pmr.kettle.dfs.install.dir";
  public static final String PENTAHO_MAPREDUCE_PROPERTY_KETTLE_INSTALLATION_ID = "pmr.kettle.installation.id";
  public static final String PENTAHO_MAPREDUCE_PROPERTY_ADDITIONAL_PLUGINS = "pmr.kettle.additional.plugins";
  public static final String PENTAHO_MAPREDUCE_PROPERTY_EXCLUDE_FILES = "pmr.kettle.exclude.plugin.files";
  public static final String PENTAHO_MAP_REDUCE_JOB_BUILDER_IMPL_INPUT_STEP_NOT_SPECIFIED =
    "PentahoMapReduceJobBuilderImpl.InputStepNotSpecified";
  public static final String PENTAHO_MAP_REDUCE_JOB_BUILDER_IMPL_INPUT_STEP_NOT_FOUND =
    "PentahoMapReduceJobBuilderImpl.InputStepNotFound";
  public static final String PENTAHO_MAP_REDUCE_JOB_BUILDER_IMPL_NO_KEY_ORDINAL =
    "PentahoMapReduceJobBuilderImpl.NoKeyOrdinal";
  public static final String PENTAHO_MAP_REDUCE_JOB_BUILDER_IMPL_NO_VALUE_ORDINAL =
    "PentahoMapReduceJobBuilderImpl.NoValueOrdinal";
  public static final String PENTAHO_MAP_REDUCE_JOB_BUILDER_IMPL_INPUT_HOP_DISABLED =
    "PentahoMapReduceJobBuilderImpl.InputHopDisabled";
  public static final String PENTAHO_MAP_REDUCE_JOB_BUILDER_IMPL_OUTPUT_STEP_NOT_SPECIFIED =
    "PentahoMapReduceJobBuilderImpl.OutputStepNotSpecified";
  public static final String PENTAHO_MAP_REDUCE_JOB_BUILDER_IMPL_OUTPUT_STEP_NOT_FOUND =
    "PentahoMapReduceJobBuilderImpl.OutputStepNotFound";
  public static final String ORG_PENTAHO_BIG_DATA_KETTLE_PLUGINS_MAPREDUCE_STEP_HADOOP_EXIT_META =
    "org.pentaho.big.data.kettle.plugins.mapreduce.step.HadoopExitMeta";
  public static final String PENTAHO_MAP_REDUCE_JOB_BUILDER_IMPL_VALIDATION_ERROR =
    "PentahoMapReduceJobBuilderImpl.ValidationError";
  public static final String PENTAHO_MAP_REDUCE_JOB_BUILDER_IMPL_NO_OUTPUT_KEY_ORDINAL =
    "PentahoMapReduceJobBuilderImpl.NoOutputKeyOrdinal";
  public static final String PENTAHO_MAP_REDUCE_JOB_BUILDER_IMPL_NO_OUTPUT_VALUE_ORDINAL =
    "PentahoMapReduceJobBuilderImpl.NoOutputValueOrdinal";
  public static final String TRANSFORMATION_MAP_XML = "transformation-map-xml";
  public static final String TRANSFORMATION_MAP_INPUT_STEPNAME = "transformation-map-input-stepname";
  public static final String TRANSFORMATION_MAP_OUTPUT_STEPNAME = "transformation-map-output-stepname";
  public static final String LOG_LEVEL = "logLevel";
  public static final String TRANSFORMATION_COMBINER_XML = "transformation-combiner-xml";
  public static final String TRANSFORMATION_COMBINER_INPUT_STEPNAME = "transformation-combiner-input-stepname";
  public static final String TRANSFORMATION_COMBINER_OUTPUT_STEPNAME = "transformation-combiner-output-stepname";
  public static final String TRANSFORMATION_REDUCE_XML = "transformation-reduce-xml";
  public static final String TRANSFORMATION_REDUCE_INPUT_STEPNAME = "transformation-reduce-input-stepname";
  public static final String TRANSFORMATION_REDUCE_OUTPUT_STEPNAME = "transformation-reduce-output-stepname";
  public static final String JOB_ENTRY_HADOOP_TRANS_JOB_EXECUTOR_CLEANING_OUTPUT_PATH =
    "JobEntryHadoopTransJobExecutor.CleaningOutputPath";
  public static final String JOB_ENTRY_HADOOP_TRANS_JOB_EXECUTOR_FAILED_TO_CLEAN_OUTPUT_PATH =
    "JobEntryHadoopTransJobExecutor.FailedToCleanOutputPath";
  public static final String JOB_ENTRY_HADOOP_TRANS_JOB_EXECUTOR_ERROR_CLEANING_OUTPUT_PATH =
    "JobEntryHadoopTransJobExecutor.ErrorCleaningOutputPath";
  public static final String JOB_ENTRY_HADOOP_TRANS_JOB_EXECUTOR_KETTLE_HDFS_INSTALL_DIR_MISSING =
    "JobEntryHadoopTransJobExecutor.KettleHdfsInstallDirMissing";
  public static final String JOB_ENTRY_HADOOP_TRANS_JOB_EXECUTOR_INSTALLATION_OF_KETTLE_FAILED =
    "JobEntryHadoopTransJobExecutor.InstallationOfKettleFailed";
  public static final String JOB_ENTRY_HADOOP_TRANS_JOB_EXECUTOR_CONFIGURING_JOB_WITH_KETTLE_AT =
    "JobEntryHadoopTransJobExecutor.ConfiguringJobWithKettleAt";
  public static final String CLASSES = "classes/,";
  public static final String JOB_ENTRY_HADOOP_TRANS_JOB_EXECUTOR_UNABLE_TO_LOCATE_ARCHIVE =
    "JobEntryHadoopTransJobExecutor.UnableToLocateArchive";
  public static final String JOB_ENTRY_HADOOP_TRANS_JOB_EXECUTOR_KETTLE_INSTALLATION_MISSING_FROM =
    "JobEntryHadoopTransJobExecutor.KettleInstallationMissingFrom";
  private static final String KEYTAB_AUTHENTICATION_LOCATION = "pentaho.authentication.default.kerberos.keytabLocation";
  private static final String KEYTAB_IMPERSONATION_LOCATION =
    "pentaho.authentication.default.mapping.server.credentials.kerberos.keytabLocation";
  private static final String MAPRED_MAP_JAVA_OPTS = "mapreduce.map.java.opts";
  private static final String MAPRED_REDUCE_JAVA_OPTS = "mapreduce.reduce.java.opts";
  public static final String VARIABLE_SPACE = "variableSpace";
  private final HadoopShim hadoopShim;
  private final LogChannelInterface log;
  private final FileObject vfsPluginDirectory;
  private final Properties pmrProperties;
  private final List<TransformationVisitorService> visitorServices;
  private final TransFactory transFactory;
  private final PMRArchiveGetter pmrArchiveGetter;
  private final String installId;
  private boolean cleanOutputPath;
  private LogLevel logLevel;
  private String mapperTransformationXml;
  private String mapperInputStep;
  private String mapperOutputStep;
  private String combinerTransformationXml;
  private String combinerInputStep;
  private String combinerOutputStep;
  private String reducerTransformationXml;
  private String reducerInputStep;
  private String reducerOutputStep;

  public PentahoMapReduceJobBuilderImpl( NamedCluster namedCluster,
                                         HadoopShim hadoopShim,
                                         LogChannelInterface log,
                                         VariableSpace variableSpace, PluginInterface pluginInterface,
                                         Properties pmrProperties,
                                         List<TransformationVisitorService> visitorServices )
    throws KettleFileException {
    this( namedCluster, hadoopShim, log, variableSpace, pluginInterface,
      KettleVFS.getFileObject( pluginInterface.getPluginDirectory().getPath() ), pmrProperties,
      new TransFactory(), new PMRArchiveGetter( pluginInterface, pmrProperties ), visitorServices );
  }

  @VisibleForTesting PentahoMapReduceJobBuilderImpl( NamedCluster namedCluster,
                                                     HadoopShim hadoopShim,
                                                     LogChannelInterface log,
                                                     VariableSpace variableSpace, PluginInterface pluginInterface,
                                                     FileObject vfsPluginDirectory,
                                                     Properties pmrProperties, TransFactory transFactory,
                                                     PMRArchiveGetter pmrArchiveGetter,
                                                     List<TransformationVisitorService> visitorServices ) {
    super( namedCluster, hadoopShim, log, variableSpace );
    this.hadoopShim = hadoopShim;
    this.log = log;
    this.vfsPluginDirectory = vfsPluginDirectory;
    this.pmrProperties = pmrProperties;
    this.transFactory = transFactory;
    this.installId = buildInstallIdBase( hadoopShim );
    this.pmrArchiveGetter = pmrArchiveGetter;
    this.visitorServices = addDefaultVisitors( visitorServices );
  }

  @VisibleForTesting List<TransformationVisitorService> addDefaultVisitors(
    List<TransformationVisitorService> visitorServices ) {
    String ignoreTableLogging =
      System.getProperty( Const.KETTLE_COMPATIBILITY_IGNORE_TABLE_LOGGING, "Y" );
    Boolean notIgnore = "N".equalsIgnoreCase( ignoreTableLogging );
    if ( notIgnore ) {
      return visitorServices;
    } else {
      List<TransformationVisitorService> editableList = new ArrayList<>( visitorServices );
      editableList.add( new TransformationVisitorService() {
        @Override public void visit( MapReduceTransformations transformations, NamedCluster namedCluster ) {
          //Delete logging into tables
          deleteLogging( transformations.getCombiner() );
          deleteLogging( transformations.getMapper() );
          deleteLogging( transformations.getReducer() );
        }
      } );
      return editableList;
    }
  }

  private void deleteLogging( Optional<TransConfiguration> transConfiguration ) {
    if ( !transConfiguration.isPresent() ) {
      return;
    }
    TransMeta meta = transConfiguration.get().getTransMeta();
    if ( meta == null ) {
      return;
    }
    BaseLogTable table = meta.getStepLogTable();
    table.setConnectionName( null );
    meta.setStepLogTable( (StepLogTable) table );

    table = meta.getMetricsLogTable();
    table.setConnectionName( null );
    meta.setMetricsLogTable( (MetricsLogTable) table );

    table = meta.getPerformanceLogTable();
    table.setConnectionName( null );
    meta.setPerformanceLogTable( (PerformanceLogTable) table );

    table = meta.getTransLogTable();
    table.setConnectionName( null );
    meta.setTransLogTable( (TransLogTable) table );

    table = meta.getChannelLogTable();
    table.setConnectionName( null );
    meta.setChannelLogTable( (ChannelLogTable) table );

  }

  private VariableSpace removeLogging( VariableSpace variableSpace ) {
    String ignoreTableLogging =
      System.getProperty( Const.KETTLE_COMPATIBILITY_IGNORE_TABLE_LOGGING, "Y" );
    Boolean notIgnore = "N".equalsIgnoreCase( ignoreTableLogging );
    if ( notIgnore ) {
      return variableSpace;
    } else {
      VariableSpace vs = new Variables();
      vs.copyVariablesFrom( variableSpace );
      vs.setVariable( Const.KETTLE_STEP_LOG_DB, null );
      vs.setVariable( Const.KETTLE_TRANS_LOG_DB, null );
      vs.setVariable( Const.KETTLE_JOB_LOG_DB, null );
      vs.setVariable( Const.KETTLE_TRANS_PERFORMANCE_LOG_DB, null );
      vs.setVariable( Const.KETTLE_JOBENTRY_LOG_DB, null );
      vs.setVariable( Const.KETTLE_CHANNEL_LOG_DB, null );
      vs.setVariable( Const.KETTLE_METRICS_LOG_DB, null );
      vs.setVariable( Const.KETTLE_CHECKPOINT_LOG_DB, null );
      return vs;
    }
  }


  private static String buildInstallIdBase( HadoopShim hadoopShim ) {
    String pluginVersion = new PluginPropertiesUtil().getVersion();

    String installId = BuildVersion.getInstance().getVersion();
    if ( pluginVersion != null ) {
      installId = installId + "-" + pluginVersion;
    }

    return installId + "-" + hadoopShim.getHadoopVersion();
  }

  /**
   * Gets a property from the configuration. If it is missing it will load it from the properties provided. If it cannot
   * be found there the default value provided will be used.
   *
   * @param conf         Configuration to check for property first.
   * @param properties   Properties to check for property second.
   * @param propertyName Name of the property to return
   * @param defaultValue Default value to use if no property by the given name could be found in {@code conf} or {@code
   *                     properties}
   * @return Value of {@code propertyName}
   */
  public static String getProperty( Configuration conf, Properties properties, String propertyName,
                                    String defaultValue ) {
    String fromConf = conf.get( propertyName );
    if ( Utils.isEmpty( fromConf ) ) {
      Object objectValue = properties.get( propertyName );
      if ( objectValue != null ) {
        if ( objectValue instanceof String ) {
          return objectValue.toString();
        } else if ( objectValue instanceof List ) {
          String strObjectValue = String.join( ",", (List) objectValue );
          if ( strObjectValue.equals( "" ) ) {
            return defaultValue;
          } else {
            return strObjectValue;
          }
        } else {
          // shouldn't happen
          return defaultValue;
        }
      } else {
        return defaultValue;
      }
    }
    return fromConf;
  }

  @Override
  public String getHadoopWritableCompatibleClassName( ValueMetaInterface valueMetaInterface ) {
    Class<?> hadoopWritableCompatibleClass = hadoopShim.getHadoopWritableCompatibleClass( valueMetaInterface );
    if ( hadoopWritableCompatibleClass == null ) {
      return null;
    }
    return hadoopWritableCompatibleClass.getCanonicalName();
  }

  @Override
  public void setLogLevel( LogLevel logLevel ) {
    this.logLevel = logLevel;
  }

  @Override
  public void setCleanOutputPath( boolean cleanOutputPath ) {
    this.cleanOutputPath = cleanOutputPath;
  }

  @Override
  public void verifyTransMeta( TransMeta transMeta, String inputStepName, String outputStepName )
    throws KettleException {
    // Verify the input step: see that the key/value fields are present...
    //
    if ( Utils.isEmpty( inputStepName ) ) {
      throw new KettleException(
        BaseMessages.getString( PKG, PENTAHO_MAP_REDUCE_JOB_BUILDER_IMPL_INPUT_STEP_NOT_SPECIFIED ) );
    }
    StepMeta inputStepMeta = transMeta.findStep( inputStepName );
    if ( inputStepMeta == null ) {
      throw new KettleException(
        BaseMessages.getString( PKG, PENTAHO_MAP_REDUCE_JOB_BUILDER_IMPL_INPUT_STEP_NOT_FOUND, inputStepName ) );
    }

    // Get the fields coming out of the input step...
    //
    RowMetaInterface injectorRowMeta = transMeta.getStepFields( inputStepMeta );

    // Verify that the key and value fields are found
    //
    if ( !containsCaseInsensitive( "key", Arrays.asList( injectorRowMeta.getFieldNames() ) ) ) {
      throw new KettleException(
        BaseMessages.getString( PKG, PENTAHO_MAP_REDUCE_JOB_BUILDER_IMPL_NO_KEY_ORDINAL, inputStepName ) );
    }
    if ( !containsCaseInsensitive( "value", Arrays.asList( injectorRowMeta.getFieldNames() ) ) ) {
      throw new KettleException(
        BaseMessages.getString( PKG, PENTAHO_MAP_REDUCE_JOB_BUILDER_IMPL_NO_VALUE_ORDINAL, inputStepName ) );
    }

    // make sure that the input step is enabled (i.e. its outgoing hop
    // hasn't been disabled)
    Trans t = transFactory.create( transMeta );
    t.prepareExecution( null );
    if ( t.getStepInterface( inputStepName, 0 ) == null ) {
      throw new KettleException(
        BaseMessages.getString( PKG, PENTAHO_MAP_REDUCE_JOB_BUILDER_IMPL_INPUT_HOP_DISABLED, inputStepName ) );
    }

    // Now verify the output step output of the reducer...
    //
    if ( Utils.isEmpty( outputStepName ) ) {
      throw new KettleException(
        BaseMessages.getString( PKG, PENTAHO_MAP_REDUCE_JOB_BUILDER_IMPL_OUTPUT_STEP_NOT_SPECIFIED ) );
    }

    StepMeta outputStepMeta = transMeta.findStep( outputStepName );
    if ( outputStepMeta == null ) {
      throw new KettleException(
        BaseMessages.getString( PKG, PENTAHO_MAP_REDUCE_JOB_BUILDER_IMPL_OUTPUT_STEP_NOT_FOUND, outputStepName ) );
    }

    // It's a special step designed to map the output key/value pair fields...
    //
    if ( outputStepMeta.getStepMetaInterface() instanceof PentahoMapReduceOutputStepMetaInterface ) {
      // Get the row fields entering the output step...
      //
      RowMetaInterface outputRowMeta = transMeta.getPrevStepFields( outputStepMeta );
      StepMetaInterface exitMeta = outputStepMeta.getStepMetaInterface();

      List<CheckResultInterface> remarks = new ArrayList<>();
      ( (PentahoMapReduceOutputStepMetaInterface) exitMeta )
        .checkPmr( remarks, transMeta, outputStepMeta, outputRowMeta );
      StringBuilder message = new StringBuilder();
      for ( CheckResultInterface remark : remarks ) {
        if ( remark.getType() == CheckResultInterface.TYPE_RESULT_ERROR ) {
          message.append( message.toString() ).append( Const.CR );
        }
      }
      if ( message.length() > 0 ) {
        throw new KettleException(
          BaseMessages.getString( PKG, PENTAHO_MAP_REDUCE_JOB_BUILDER_IMPL_VALIDATION_ERROR ) + Const.CR + message );
      }
    } else {
      // Any other step: verify that the outKey and outValue fields exist...
      //
      RowMetaInterface outputRowMeta = transMeta.getStepFields( outputStepMeta );
      List<String> fieldNames = Arrays.asList( outputRowMeta.getFieldNames() );
      if ( !containsCaseInsensitive( "outKey", fieldNames ) ) {
        throw new KettleException(
          BaseMessages.getString( PKG, PENTAHO_MAP_REDUCE_JOB_BUILDER_IMPL_NO_OUTPUT_KEY_ORDINAL, outputStepName ) );
      }
      if ( !containsCaseInsensitive( "outValue", fieldNames ) ) {
        throw new KettleException(
          BaseMessages.getString( PKG, PENTAHO_MAP_REDUCE_JOB_BUILDER_IMPL_NO_OUTPUT_VALUE_ORDINAL, outputStepName ) );
      }
    }
  }

  private boolean containsCaseInsensitive( String s, List<String> l ) {
    return l.stream().anyMatch( x -> x.equalsIgnoreCase( s ) );
  }

  @Override
  public void setCombinerInfo( String combinerTransformationXml, String combinerInputStep, String combinerOutputStep ) {
    this.combinerTransformationXml = combinerTransformationXml;
    this.combinerInputStep = combinerInputStep;
    this.combinerOutputStep = combinerOutputStep;
  }

  @Override
  public void setReducerInfo( String reducerTransformationXml, String reducerInputStep, String reducerOutputStep ) {
    this.reducerTransformationXml = reducerTransformationXml;
    this.reducerInputStep = reducerInputStep;
    this.reducerOutputStep = reducerOutputStep;
  }

  @Override
  public void setMapperInfo( String mapperTransformationXml, String mapperInputStep, String mapperOutputStep ) {
    this.mapperTransformationXml = mapperTransformationXml;
    this.mapperInputStep = mapperInputStep;
    this.mapperOutputStep = mapperOutputStep;
  }

  @Override
  protected void configure( Configuration conf ) throws Exception {
    callVisitors();

    setMapRunnerClass( hadoopShim.getPentahoMapReduceMapRunnerClass() );

    conf.set( TRANSFORMATION_MAP_XML, mapperTransformationXml );
    conf.set( TRANSFORMATION_MAP_INPUT_STEPNAME, mapperInputStep );
    conf.set( TRANSFORMATION_MAP_OUTPUT_STEPNAME, mapperOutputStep );

    if ( combinerTransformationXml != null ) {
      conf.set( TRANSFORMATION_COMBINER_XML, combinerTransformationXml );
      conf.set( TRANSFORMATION_COMBINER_INPUT_STEPNAME, combinerInputStep );
      conf.set( TRANSFORMATION_COMBINER_OUTPUT_STEPNAME, combinerOutputStep );
      setCombinerClass( hadoopShim.getPentahoMapReduceCombinerClass() );
    }
    if ( reducerTransformationXml != null ) {
      conf.set( TRANSFORMATION_REDUCE_XML, reducerTransformationXml );
      conf.set( TRANSFORMATION_REDUCE_INPUT_STEPNAME, reducerInputStep );
      conf.set( TRANSFORMATION_REDUCE_OUTPUT_STEPNAME, reducerOutputStep );
      setReducerClass( hadoopShim.getPentahoMapReduceReducerClass() );
    }
    conf.setJarByClass( Class.forName( "org.pentaho.hadoop.mapreduce.PentahoMapReduceJarMarker" ) );
    conf.set( LOG_LEVEL, logLevel.toString() );
    configureVariableSpace( conf );
    super.configure( conf );
  }

  @Override
  protected MapReduceJobAdvanced submit( Configuration conf, String shimIdentifier ) throws IOException {
    cleanOutputPath( conf );

    FileSystem fs = hadoopShim.getFileSystem( conf );

    if ( Boolean.parseBoolean( getProperty( conf, pmrProperties, PENTAHO_MAPREDUCE_PROPERTY_USE_DISTRIBUTED_CACHE,
      Boolean.toString( true ) ) ) ) {
      String installPath =
        getProperty( conf, pmrProperties, PENTAHO_MAPREDUCE_PROPERTY_KETTLE_HDFS_INSTALL_DIR, null );
      String mInstallId =
        getProperty( conf, pmrProperties, PENTAHO_MAPREDUCE_PROPERTY_KETTLE_INSTALLATION_ID, null );
      try {
        if ( Utils.isEmpty( installPath ) ) {
          throw new IllegalArgumentException( BaseMessages.getString( PKG,
            JOB_ENTRY_HADOOP_TRANS_JOB_EXECUTOR_KETTLE_HDFS_INSTALL_DIR_MISSING ) );
        }
        if ( Utils.isEmpty( mInstallId ) ) {
          mInstallId = this.installId;
        }
        if ( !installPath.endsWith( Const.FILE_SEPARATOR ) ) {
          installPath += Const.FILE_SEPARATOR;
        }

        Path kettleEnvInstallDir = fs.asPath( installPath, mInstallId );
        FileObject pmrLibArchive = pmrArchiveGetter.getPmrArchive( conf );

        // Make sure the version we're attempting to use is installed
        if ( hadoopShim.getDistributedCacheUtil().isKettleEnvironmentInstalledAt( fs, kettleEnvInstallDir ) ) {
          log.logDetailed( BaseMessages.getString( PKG, "JobEntryHadoopTransJobExecutor.UsingKettleInstallationFrom",
            kettleEnvInstallDir.toUri().getPath() ) );
        } else {
          // Load additional plugin folders as requested
          String additionalPluginNames =
            getProperty( conf, pmrProperties, PENTAHO_MAPREDUCE_PROPERTY_ADDITIONAL_PLUGINS, null );
          String excludePluginFileNames =
            getProperty( conf, pmrProperties, PENTAHO_MAPREDUCE_PROPERTY_EXCLUDE_FILES, null );
          if ( pmrLibArchive == null ) {
            throw new KettleException(
              BaseMessages.getString( PKG, JOB_ENTRY_HADOOP_TRANS_JOB_EXECUTOR_UNABLE_TO_LOCATE_ARCHIVE,
                pmrArchiveGetter.getVfsFilename( conf ) ) );
          }

          log.logBasic( BaseMessages.getString( PKG, "JobEntryHadoopTransJobExecutor.InstallingKettleAt",
            kettleEnvInstallDir ) );

          FileObject bigDataPluginFolder = vfsPluginDirectory;
          hadoopShim.getDistributedCacheUtil()
            .installKettleEnvironment( pmrLibArchive, fs, kettleEnvInstallDir, bigDataPluginFolder,
              additionalPluginNames, excludePluginFileNames, shimIdentifier );

          log.logBasic( BaseMessages
            .getString( PKG, "JobEntryHadoopTransJobExecutor.InstallationOfKettleSuccessful", kettleEnvInstallDir ) );
        }

        stageMetaStoreForHadoop( conf, fs, installPath );

        log.logBasic( BaseMessages.getString( PKG, JOB_ENTRY_HADOOP_TRANS_JOB_EXECUTOR_CONFIGURING_JOB_WITH_KETTLE_AT,
          kettleEnvInstallDir.toUri().getPath() ) );

        String mapreduceClasspath =
          conf.get( MAPREDUCE_APPLICATION_CLASSPATH, DEFAULT_MAPREDUCE_APPLICATION_CLASSPATH );
        conf.set( MAPREDUCE_APPLICATION_CLASSPATH, CLASSES + mapreduceClasspath );

        hadoopShim.getDistributedCacheUtil().configureWithKettleEnvironment( conf, fs, kettleEnvInstallDir );
        log.logBasic( MAPREDUCE_APPLICATION_CLASSPATH + ": " + conf.get( MAPREDUCE_APPLICATION_CLASSPATH ) );
      } catch ( Exception ex ) {
        throw new IOException(
          BaseMessages.getString( PKG, JOB_ENTRY_HADOOP_TRANS_JOB_EXECUTOR_INSTALLATION_OF_KETTLE_FAILED ), ex );
      }
    }
    JobConf jobConf = conf.getAsDelegateConf( JobConf.class );
    jobConf.getCredentials().addAll( UserGroupInformation.getCurrentUser().getCredentials() );
    return super.submit( conf, shimIdentifier );
  }

  protected void stageMetaStoreForHadoop( Configuration conf, FileSystem fs, String installPath )
    throws Exception {
    java.nio.file.Path localMetaStoreSnapshotDirPath;
    Path hdfsMetaStoreDirForCurrentJobPath;
    FileObject localMetaStoreSnapshotDirObject;
    boolean overwrite;

    // Create a temp folder on the local file system if it isn't already present in hdfs.
    localMetaStoreSnapshotDirPath = Files.createTempDirectory( XmlUtil.META_FOLDER_NAME );

    // Get the newly created metastore directory from the local file system
    localMetaStoreSnapshotDirObject = KettleVFS.getFileObject( localMetaStoreSnapshotDirPath.toString() );

    // Determine the folder name to use for hdfs based on the create.unique.metastore.dir property
    if ( Boolean.parseBoolean( getProperty( conf, pmrProperties, PENTAHO_MAPREDUCE_PROPERTY_CREATE_UNIQUE_METASTORE_DIR,
      Boolean.toString( true ) ) ) ) {
      hdfsMetaStoreDirForCurrentJobPath =
        fs.asPath( installPath, localMetaStoreSnapshotDirObject.getName().getBaseName() );
      overwrite = false;
    } else {
      hdfsMetaStoreDirForCurrentJobPath = fs.asPath( installPath, XmlUtil.META_FOLDER_NAME );
      overwrite = true;
    }

    // Copy the local metastore into the temp folder on the local file system
    snapshotMetaStore( localMetaStoreSnapshotDirPath.toString() );

    // Stage the local metastore to hdfs
    hadoopShim.getDistributedCacheUtil()
      .stageForCache( localMetaStoreSnapshotDirObject, fs, hdfsMetaStoreDirForCurrentJobPath, "", overwrite, true );
    hadoopShim.getDistributedCacheUtil().addCachedFiles( conf, fs, hdfsMetaStoreDirForCurrentJobPath, null );
  }

  private void snapshotMetaStore( String metaStoreSnapshotDir ) throws MetaStoreException {
    IMetaStore snapshot = new XmlMetaStore( metaStoreSnapshotDir );
    try {
      FileSystemConfigBuilder nc = KettleVFS.getInstance().getFileSystemManager().getFileSystemConfigBuilder( "hc" );
      Method snapshotMethod = nc.getClass().getMethod( "snapshotNamedClusterToMetaStore", IMetaStore.class );
      snapshotMethod.invoke( nc, snapshot );

      stageConfigurationFiles( metaStoreSnapshotDir );
    } catch ( FileSystemException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e ) {
      log.logError( "Error in snapshotNamedClusterToMetaStore.", e );
    }
  }

  private void stageConfigurationFiles( String metaStoreSnapshotDir ) {
    File configFilesStagingLocation = new File(
      metaStoreSnapshotDir + File.separator + ShimConfigsLoader.CONFIGS_DIR_PREFIX + File.separator
        + getClusterName() );
    ShimConfigsLoader.ClusterConfigNames[] configFilesNames = ShimConfigsLoader.ClusterConfigNames.values();
    Properties configProps = ShimConfigsLoader.loadConfigProperties( getClusterName() );
    copyConfigProperties( configProps, configFilesStagingLocation );

    String keytabAuthFilePath = configProps.getProperty( KEYTAB_AUTHENTICATION_LOCATION, "" );
    if ( !keytabAuthFilePath.isEmpty() ) {
      copyConfigFileToStaging( configFilesStagingLocation, Paths.get( keytabAuthFilePath ).getFileName().toString() );
    }
    String keytabImpersFilePath = configProps.getProperty( KEYTAB_IMPERSONATION_LOCATION, "" );
    if ( !keytabImpersFilePath.isEmpty() ) {
      copyConfigFileToStaging( configFilesStagingLocation, Paths.get( keytabImpersFilePath ).getFileName().toString() );
    }
  }

  private void copyConfigFileToStaging( File configFilesStagingLocation,
                                        String configFileName ) {
    URI configFileLocation;
    File configFileSource;
    boolean stagingExists;
    try {
      configFileLocation =
        ShimConfigsLoader.getURLToResourceFile( configFileName, getClusterName() ).toURI();
      configFileSource = new File( configFileLocation );
      stagingExists = true;
      if ( !configFilesStagingLocation.exists() ) {
        stagingExists = configFilesStagingLocation.mkdirs();
      }
      if ( configFileSource.exists() && stagingExists ) {
        FileUtils.copyFileToDirectory( configFileSource, configFilesStagingLocation );
      }
    } catch ( Exception e ) {
      //Do nothing
    }
  }

  protected void configureVariableSpace( Configuration conf ) {
    // get a reference to the variable space
    XStream xStream = new XStream();

    // this is optional - for human-readable xml file
    xStream.alias( VARIABLE_SPACE, VariableSpace.class );

    // serialize the variable space to XML
    String xmlVariableSpace = xStream.toXML( removeLogging( getVariableSpace() ) );

    // set a string in the job configuration as the serialized variablespace
    conf.setStrings( VARIABLE_SPACE, xmlVariableSpace );

    //For allowing protobuf compatibility
    conf.set( MAPRED_MAP_JAVA_OPTS, "-Dcom.google.protobuf.use_unsafe_pre22_gencode=true" );
    conf.set( MAPRED_REDUCE_JAVA_OPTS, "-Dcom.google.protobuf.use_unsafe_pre22_gencode=true" );
  }

  @VisibleForTesting
  void cleanOutputPath( Configuration conf ) throws IOException {

    if ( cleanOutputPath ) {
      FileSystem fs = hadoopShim.getFileSystem( conf );
      Path path = getOutputPath( conf, fs );
      String outputPath = path.toUri().toString();
      if ( log.isBasic() ) {
        log.logBasic(
          BaseMessages.getString( PKG, JOB_ENTRY_HADOOP_TRANS_JOB_EXECUTOR_CLEANING_OUTPUT_PATH, outputPath ) );
      }
      try {
        if ( !fs.exists( path ) ) {
          // If the path does not exist one could think of it as "already cleaned"
          return;
        }
        if ( !fs.delete( path, true ) && log.isBasic() ) {
          log.logBasic(
            BaseMessages
              .getString( PKG, JOB_ENTRY_HADOOP_TRANS_JOB_EXECUTOR_FAILED_TO_CLEAN_OUTPUT_PATH, outputPath ) );
        }
      } catch ( IOException ex ) {
        throw new IOException(
          BaseMessages.getString( PKG, JOB_ENTRY_HADOOP_TRANS_JOB_EXECUTOR_ERROR_CLEANING_OUTPUT_PATH, outputPath ),
          ex );
      }
    }
  }

  private void callVisitors() {
    MapReduceTransformations transformations = new MapReduceTransformations();
    transformations.setCombiner( convert( combinerTransformationXml ) );
    transformations.setMapper( convert( mapperTransformationXml ) );
    transformations.setReducer( convert( reducerTransformationXml ) );

    for ( TransformationVisitorService visitorService : visitorServices ) {
      visitorService.visit( transformations, this.getNamedCluster() );
    }

    combinerTransformationXml = convert( transformations.getCombiner() );
    mapperTransformationXml = convert( transformations.getMapper() );
    reducerTransformationXml = convert( transformations.getReducer() );
  }

  private Optional<TransConfiguration> convert( String xmlString ) {
    try {
      if ( xmlString == null ) {
        return Optional.empty();
      }
      TransConfiguration transConfiguration = TransConfiguration.fromXML( xmlString );
      return Optional.of( transConfiguration );
    } catch ( KettleException e ) {
      throw new ShimRuntimeException( "Unable to convert string to object", e );
    }
  }

  private String convert( Optional<TransConfiguration> transConfiguration ) {
    try {
      if ( transConfiguration.isPresent() ) {
        return transConfiguration.get().getXML();
      } else {
        return null;
      }
    } catch ( KettleException | IOException e ) {
      throw new ShimRuntimeException( "Unable to convert object to string.", e );
    }
  }


  @VisibleForTesting
  String getInstallId() {
    return installId;
  }

  @VisibleForTesting
  static class TransFactory {
    public Trans create( TransMeta transMeta ) {
      return new Trans( transMeta );
    }
  }

  @VisibleForTesting
  static class PMRArchiveGetter {
    private final PluginInterface pluginInterface;
    private final Properties pmrProperties;

    public PMRArchiveGetter( PluginInterface pluginInterface, Properties pmrProperties ) {
      this.pluginInterface = pluginInterface;
      this.pmrProperties = pmrProperties;
    }

    public FileObject getPmrArchive( Configuration conf ) throws KettleFileException {
      return KettleVFS.getFileObject( getVfsFilename( conf ) );
    }

    public String getVfsFilename( Configuration conf ) {
      return pluginInterface.getPluginDirectory().getPath() + Const.FILE_SEPARATOR
        + getProperty( conf, pmrProperties, PENTAHO_MAPREDUCE_PROPERTY_PMR_LIBRARIES_ARCHIVE_FILE, null );
    }
  }

  private void copyConfigProperties( Properties configProperties, File destFolder ) {
    //The config.properties file must be copied to the cluster side.  We found if the file is omitted,
    //some customers reported errors.  We could not reproduce but this logic fixed the problem.  See
    // BAD-1931.
    File configFile = new File( destFolder.toPath().toString() + File.separator + DistributedCacheUtilImpl.CONFIG_PROPERTIES );
    configFile.getParentFile().mkdirs();
    try ( FileOutputStream output = new FileOutputStream( configFile ) ) {
      for ( Map.Entry<Object, Object> e : configProperties.entrySet() ) {
        String key = (String) e.getKey();
        String value = (String) e.getValue();
        IOUtils.write( key + "=" + value, output );
        IOUtils.write( String.format( "%n" ), output );
      }
    } catch ( IOException e ) {
      //Should not happen
      throw new ShimRuntimeException( "Error copying modified version of config.properties", e );
    }

  }

}
