package com.gravity.hbase.schema

import org.apache.hadoop.hbase.client._
import org.apache.hadoop.hbase.util._
import scala.collection.JavaConversions._
import org.apache.hadoop.conf.Configuration
import java.io._
import org.apache.hadoop.io.{BytesWritable, Writable}
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp
import scala.collection._
import java.util.NavigableSet
import scala.collection.mutable.Buffer
import org.joda.time.DateTime
import org.apache.hadoop.hbase.filter._
import org.apache.hadoop.hbase.filter.FilterList.Operator
/*             )\._.,--....,'``.
 .b--.        /;   _.. \   _\  (`._ ,.
`=,-,-'~~~   `----(,_..'--(,_..'`-.;.'  */



class Query2[T <: HbaseTable[T,R],R](table:HbaseTable[T,R]) {
  val keys = Buffer[Array[Byte]]()
  val families = Buffer[Array[Byte]]()
  val columns = Buffer[(Array[Byte], Array[Byte])]()
  var currentFilter : FilterList = _ // new FilterList(Operator.MUST_PASS_ALL)
  var startRowBytes : Array[Byte] = null
  var endRowBytes : Array[Byte] = null

  def withKey(key: R)(implicit c: ByteConverter[R]) = {
    keys += c.toBytes(key)
    this
  }

  def withKeys(keys: Set[R])(implicit c: ByteConverter[R]) = {
    for (key <- keys) {
      withKey(key)(c)
    }
    this
  }


  def and = {
    if(currentFilter == null) {
      currentFilter = new FilterList(Operator.MUST_PASS_ALL)
    }else {
      val encompassingFilter = new FilterList(Operator.MUST_PASS_ALL)
      encompassingFilter.addFilter(currentFilter)
      currentFilter = encompassingFilter
    }
    this
  }

  def or = {
    if(currentFilter == null) {
      currentFilter = new FilterList(Operator.MUST_PASS_ONE)
    }else {
      val encompassingFilter = new FilterList(Operator.MUST_PASS_ONE)
      encompassingFilter.addFilter(currentFilter)
      currentFilter = encompassingFilter
    }
    this
  }

  def lessThanColumnKey[F,K,V](family: (T) => ColumnFamily[T,R,F,K,V], value:K)(implicit k:ByteConverter[K]) = {
    val valueFilter = new QualifierFilter(CompareOp.LESS_OR_EQUAL, new BinaryComparator(k.toBytes(value)))
    val familyFilter = new FamilyFilter(CompareOp.EQUAL, new BinaryComparator(family(table.pops).familyBytes))
    val andFilter = new FilterList(Operator.MUST_PASS_ALL)
    andFilter.addFilter(familyFilter)
    andFilter.addFilter(valueFilter)
    currentFilter.addFilter(andFilter)
    this
  }

  def greaterThanColumnKey[F,K,V](family: (T) => ColumnFamily[T,R,F,K,V], value:K)(implicit k:ByteConverter[K]) = {
    val andFilter = new FilterList(Operator.MUST_PASS_ALL)
    val familyFilter = new FamilyFilter(CompareOp.EQUAL, new BinaryComparator(family(table.pops).familyBytes))
    val valueFilter = new QualifierFilter(CompareOp.GREATER_OR_EQUAL, new BinaryComparator(k.toBytes(value)))
    andFilter.addFilter(familyFilter)
    andFilter.addFilter(valueFilter)
    currentFilter.addFilter(andFilter)
    this
  }

//  def columnFamily[F,K,V](family: (T) => ColumnFamily[T,R,F,K,V])(implicit c: ByteConverter[F]): Query[T,R] = {
//    val familyFilter = new FamilyFilter(CompareOp.EQUAL, new BinaryComparator(family(table.pops).familyBytes))
//    currentFilter.addFilter(familyFilter)
//    this
//  }


  def betweenColumnKeys[F,K,V](family: (T) => ColumnFamily[T,R,F,K,V], lower: K, upper: K)(implicit f:ByteConverter[K]) = {
    val familyFilter = new FamilyFilter(CompareOp.EQUAL, new BinaryComparator(family(table.pops).familyBytes))
    val begin = new QualifierFilter(CompareOp.GREATER_OR_EQUAL, new BinaryComparator(f.toBytes(lower)))
    val end = new QualifierFilter(CompareOp.LESS_OR_EQUAL, new BinaryComparator(f.toBytes(upper)))
    val filterList = new FilterList(Operator.MUST_PASS_ALL)
    filterList.addFilter(familyFilter)
    filterList.addFilter(begin)
    filterList.addFilter(end)
    currentFilter.addFilter(filterList)

    this
  }

  def allInFamilies[F](familyList: ((T) => ColumnFamily[T,R,F,_,_])*) = {
    val filterList = new FilterList(Operator.MUST_PASS_ONE)
    for(family <- familyList) {
      val familyFilter = new FamilyFilter(CompareOp.EQUAL, new BinaryComparator(family(table.pops).familyBytes))
      filterList.addFilter(familyFilter)
    }
    currentFilter.addFilter(filterList)
    this
  }

  def withFamilies[F](familyList: ((T) => ColumnFamily[T, R, F, _, _])*) = {
    for(family <- familyList) {
      val fam = family(table.pops)
      families += fam.familyBytes
    }
    this
  }

  def withColumn[F, K, V](family: (T) => ColumnFamily[T, R, F, K, V], columnName: K)(implicit c: ByteConverter[F], d: ByteConverter[K]) = {
    val fam = family(table.pops)
    columns += (fam.familyBytes -> d.toBytes(columnName))
    this
  }

  def withColumn[F, K, V](column: (T) => Column[T, R, F, K, V])(implicit c: ByteConverter[K]) = {
    val col = column(table.pops)
    columns += (col.familyBytes -> col.columnBytes)
    this
  }

  def single(tableName: String = table.tableName, ttl: Int = 30, skipCache: Boolean = true) = singleOption(tableName, ttl, skipCache, false).get

  def singleOption(tableName: String = table.tableName, ttl: Int = 30, skipCache: Boolean = true, noneOnEmpty: Boolean = true): Option[QueryResult[T, R]] = {
    require(keys.size == 1, "Calling single() with more than one key")
    require(keys.size >= 1, "Calling a Get operation with no keys specified")
    val get = new Get(keys.head)
    get.setMaxVersions(1)


    for (family <- families) {
      get.addFamily(family)
    }
    for ((columnFamily, column) <- columns) {
      get.addColumn(columnFamily, column)
    }
    if(currentFilter != null && currentFilter.getFilters.size() > 0) {
      get.setFilter(currentFilter)
    }

    val fromCache = if (skipCache) None else table.cache.getResult(get)

    fromCache match {
      case Some(result) => Some(result)
      case None => {
        table.withTableOption(tableName) {
          case Some(htable) => {
            val result = htable.get(get)
            if (noneOnEmpty && result.isEmpty) {
              None
            } else {
              val qr = new QueryResult(result, table, tableName)
              if (!skipCache && !result.isEmpty) table.cache.putResult(get, qr, ttl)
              Some(qr)
            }
          }
          case None => None
        }
      }
    }

  }

  def execute(tableName: String = table.tableName, ttl: Int = 30, skipCache: Boolean = true): Seq[QueryResult[T, R]] = {
    if (keys.isEmpty) return Seq.empty[QueryResult[T, R]] // no keys..? nothing to see here... move along... move along.

    val results = Buffer[QueryResult[T, R]]() // buffer for storing all results retrieved

    // if we are utilizing cache, we'll need to be able to recall the `Get' later to use as the cache key
    val getsByKey = if (skipCache) mutable.Map.empty[String, Get] else mutable.Map[String, Get]()

    if (!skipCache) getsByKey.sizeHint(keys.size) // perf optimization

    // buffer for all `Get's that really need to be gotten
    val cacheMisses = Buffer[Get]()

    val gets = buildGetsAndCheckCache(skipCache) {
      case (get: Get, key: Array[Byte]) => if (!skipCache) getsByKey.put(new String(key), get)
    } {
      case (qropt: Option[QueryResult[T, R]], get: Get) => if (!skipCache) {
        qropt match {
          case Some(result) => results += result // got it! place it in our result buffer
          case None => cacheMisses += get // missed it! place the get in the buffer
        }
      }
    }

    // identify what still needs to be `Get'ed ;-}
    val hbaseGets = if (skipCache) gets else cacheMisses

    if (!hbaseGets.isEmpty) {
      // only do this if we have something to do
      table.withTable(tableName) {
        htable =>
          htable.get(hbaseGets).foreach(res => {
            if (res != null && !res.isEmpty) {
              // ignore empty results
              val qr = new QueryResult[T, R](res, table, tableName) // construct query result

              // now is where we need to retrive the 'get' used for this result so that we can
              // pass this 'get' as the key for our local cache
              if (!skipCache) table.cache.putResult(getsByKey(new String(res.getRow)), qr, ttl)
              results += qr // place it in our result buffer
            }
          })
      }
    }

    results.toSeq // DONE!
  }

  def executeMap(tableName: String = table.tableName, ttl: Int = 30, skipCache: Boolean = true)(implicit c: ByteConverter[R]): Map[R, QueryResult[T, R]] = {
    if (keys.isEmpty) return Map.empty[R, QueryResult[T, R]] // don't get all started with nothing to do

    // init our result map and give it a hint of the # of keys we have
    val resultMap = mutable.Map[R, QueryResult[T, R]]()
    resultMap.sizeHint(keys.size) // perf optimization

    // if we are utilizing cache, we'll need to be able to recall the `Get' later to use as the cache key
    val getsByKey = if (skipCache) mutable.Map.empty[String, Get] else mutable.Map[String, Get]()

    if (!skipCache) getsByKey.sizeHint(keys.size) // perf optimization

    // buffer for all `Get's that really need to be gotten
    val cacheMisses = Buffer[Get]()

    val gets = buildGetsAndCheckCache(skipCache) {
      case (get: Get, key: Array[Byte]) => if (!skipCache) getsByKey.put(new String(key), get)
    } {
      case (qropt: Option[QueryResult[T, R]], get: Get) => if (!skipCache) {
        qropt match {
          case Some(result) => resultMap.put(result.rowid, result) // got it! place it in our result map
          case None => cacheMisses += get // missed it! place the get in the buffer
        }
      }
    }

    // identify what still needs to be `Get'ed ;-}
    val hbaseGets = if (skipCache) gets else cacheMisses

    if (!hbaseGets.isEmpty) {
      // only do this if we have something to do
      table.withTable(tableName) {
        htable =>
          htable.get(hbaseGets).foreach(res => {
            if (res != null && !res.isEmpty) {
              // ignore empty results
              val qr = new QueryResult[T, R](res, table, tableName) // construct query result

              // now is where we need to retrive the 'get' used for this result so that we can
              // pass this 'get' as the key for our local cache
              if (!skipCache) table.cache.putResult(getsByKey(new String(res.getRow)), qr, ttl)
              resultMap(qr.rowid) = qr // place it in our result map
            }
          })
      }
    }

    resultMap // DONE!
  }

  private def buildGetsAndCheckCache(skipCache: Boolean)(receiveGetAndKey: (Get, Array[Byte]) => Unit = (get, key) => {})(receiveCachedResult: (Option[QueryResult[T, R]], Get) => Unit = (qr, get) => {}): Seq[Get] = {
    if (keys.isEmpty) return Seq.empty[Get] // no keys..? nothing to see here... move along... move along.

    val gets = Buffer[Get]() // buffer for the raw `Get's

    for (key <- keys) {
      val get = new Get(key)
      gets += get
      receiveGetAndKey(get, key)
    }

    // since the families and columns will be identical for all `Get's, only build them once
    val firstGet = gets(0)

    // add all families to the first `Get'
    for (family <- families) {
      firstGet.addFamily(family)
    }
    // add all columns to the first `Get'
    for ((columnFamily, column) <- columns) {
      firstGet.addColumn(columnFamily, column)
    }
    if(currentFilter != null && currentFilter.getFilters.size() > 0) {
      firstGet.setFilter(currentFilter)
    }


    var pastFirst = false
    for (get <- gets) {
      if (pastFirst) {
        // we want to skip the first `Get' as it already has families/columns

        // for all subsequent `Get's, we will build their familyMap from the first `Get'
        firstGet.getFamilyMap.foreach((kv: (Array[Byte], NavigableSet[Array[Byte]])) => {
          get.getFamilyMap.put(kv._1, kv._2)
        })
        if(currentFilter != null && currentFilter.getFilters.size() > 0) {
          get.setFilter(currentFilter)
        }
      } else {
        pastFirst = true
      }

      // try the cache with this filled in get
      if (!skipCache) receiveCachedResult(table.cache.getResult(get), get)
    }

    gets
  }



  def withStartRow(row:R)(implicit r:ByteConverter[R]) = {
    startRowBytes = r.toBytes(row)
    this
  }

  def withEndRow(row:R)(implicit r:ByteConverter[R]) = {
    endRowBytes = r.toBytes(row)
    this
  }

  def makeScanner(maxVersions:Int =1, cacheBlocks:Boolean=false, cacheSize: Int = 100) = {
    require(keys.size == 0, "A scanner should not specify keys, use singleOption or execute or executeMap")
    val scan = new Scan()
    scan.setMaxVersions(maxVersions)
    scan.setCaching(cacheSize)
    scan.setCacheBlocks(cacheBlocks)

    if(startRowBytes != null)
      scan.setStartRow(startRowBytes)
    if(endRowBytes != null)
     scan.setStopRow(endRowBytes)

    for(family <- families) scan.addFamily(family)
    for(column <- columns) scan.addColumn(column._1, column._2)

    if(currentFilter.getFilters.size > 0) {
      scan.setFilter(currentFilter)
    }

    scan
  }

  def scan(handler: (QueryResult[T, R]) => Unit, maxVersions:Int = 1, cacheBlocks:Boolean = false, cacheSize:Int = 100) {
    table.withTable() {
      htable =>
        val scan = makeScanner(maxVersions,cacheBlocks,cacheSize)
        val scanner = htable.getScanner(scan)

        try {
          for (result <- scanner) {
            handler(new QueryResult[T, R](result, table, table.tableName))
          }
        } finally {
          scanner.close()
        }
    }
  }

  def scanToIterable[I](handler:(QueryResult[T,R]) => I, maxVersions:Int = 1, cacheBlocks:Boolean = false, cacheSize:Int = 100) = {
    val results2 = table.withTable() {
      htable => 
        val scan = makeScanner(maxVersions,cacheBlocks, cacheSize)
        val scanner = htable.getScanner(scan)

      for(result <- scanner; if(result != null)) yield {
        handler(new QueryResult[T,R](result,table,table.tableName))
      }
    }
    results2
  }

}