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

package org.pentaho.hadoop.hbase.factory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.BufferedMutator;
import org.apache.hadoop.hbase.client.BufferedMutatorParams;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.pentaho.hbase.factory.HBasePut;
import org.pentaho.hbase.factory.HBaseTable;

class HBase10Table implements HBaseTable {
  private final Table tab;
  private BufferedMutator mutator = null;
  private boolean autoFlush = true;
  private final Connection conn;

  HBase10Table( Connection conn, String tableName ) throws IOException {
    this.conn = conn;
    tab = conn.getTable( TableName.valueOf( tableName ) );
  }

  /**
   * Constructs read-only HBaseTable
   *
   * @param tab - HBase Table to wrap
   */
  HBase10Table( Table tab ) {
    this.tab = tab;
    conn = null;
  }

  private synchronized BufferedMutator getBufferedMutator() throws IOException {
    if ( conn != null ) {
      if ( mutator == null ) {
        mutator = conn.getBufferedMutator( tab.getName() );
      }
    } else {
      throw new IOException( "Can't mutate the table " + tab.getName() );
    }
    return mutator;
  }

  @Override
  public void setWriteBufferSize( long bufferSize ) throws IOException {
    try {
      tab.getClass().getMethod( "setWriteBufferSize", long.class ).invoke( tab, bufferSize );
    } catch ( NoSuchMethodException | IllegalAccessException | InvocationTargetException e ) {
      mutator = conn.getBufferedMutator( new BufferedMutatorParams( tab.getName() ).writeBufferSize( bufferSize ) );
    }
  }

  @Override
  public void setAutoFlush( boolean autoFlush ) throws IOException {
    this.autoFlush = autoFlush;
  }

  @Override
  public boolean isAutoFlush() throws IOException {
    return autoFlush;
  }

  @Override
  public ResultScanner getScanner( Scan s ) throws IOException {
    return tab.getScanner( s );
  }

  @Override
  public Result get( Get toGet ) throws IOException {
    return tab.get( toGet );
  }

  @Override
  public void flushCommits() throws IOException {
    getBufferedMutator().flush();
  }

  @Override
  public void delete( Delete toDel ) throws IOException {
    getBufferedMutator().mutate( toDel );
    if ( autoFlush ) {
      getBufferedMutator().flush();
    }
  }

  @Override
  public void close() throws IOException {
    tab.close();
    if ( mutator != null ) {
      mutator.close();
    }
  }

  @Override
  public void put( HBasePut put ) throws IOException {
    if ( put == null ) {
      throw new NullPointerException( "NULL Put passed" );
    }
    if ( put instanceof HBase10Put ) {
      HBase10Put p10 = (HBase10Put) put;
      put( p10.getPut() );
    } else {
      throw new IllegalArgumentException( "Unexpected backed HBasePut type passed:" + put.getClass() );
    }
  }

  void put( Put toPut ) throws IOException {
    getBufferedMutator().mutate( toPut );
    if ( autoFlush ) {
      getBufferedMutator().flush();
    }
  }

}
