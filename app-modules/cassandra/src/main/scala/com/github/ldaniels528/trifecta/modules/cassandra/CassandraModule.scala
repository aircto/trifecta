package com.github.ldaniels528.trifecta.modules.cassandra

import java.io.File

import com.datastax.driver.core.{CodecRegistry, ColumnDefinitions, ConsistencyLevel, ResultSet, Row}
import com.github.ldaniels528.commons.helpers.OptionHelper._
import com.github.ldaniels528.commons.helpers.StringHelper._
import com.github.ldaniels528.trifecta.command.{Command, UnixLikeArgs, UnixLikeParams}
import com.github.ldaniels528.trifecta.messages.MessageInputSource
import com.github.ldaniels528.trifecta.modules.Module
import com.github.ldaniels528.trifecta.modules.Module.NameValuePair
import com.github.ldaniels528.trifecta.{TxConfig, TxRuntimeContext}

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * Apache Cassandra Module
  * @author lawrence.daniels@gmail.com
  */
class CassandraModule(config: TxConfig) extends Module {
  private var conn_? : Option[Casserole] = None
  private var session_? : Option[CasseroleSession] = None
  private val consistencyLevels = Map(ConsistencyLevel.values() map (c => (c.name(), c)): _*)

  /**
    * Deciphers the given module-specific value into an object that can
    * be represented at the console
    * @param value the given [[AnyRef value]]
    * @return the option of a deciphered value
    */
  override def decipher(value: Any): Option[AnyRef] = {
    value match {
      case rs: ResultSet =>
        val cds = rs.getColumnDefinitions.asList().toSeq
        Option(rs.all() map (decodeRow(_, cds)))
      case _ => None
    }
  }

  /**
    * Returns the commands that are bound to the module
    * @return the commands that are bound to the module
    */
  override def getCommands(implicit rt: TxRuntimeContext) = Seq(
    Command(this, "clusterinfo", clusterInfo, UnixLikeParams(), help = "Retrieves the cluster information"),
    Command(this, "cqconnect", connect, UnixLikeParams(Seq("host" -> false, "port" -> false), Seq("-k" -> "keySpace")), help = "Establishes a connection to Cassandra"),
    Command(this, "cql", cql, UnixLikeParams(Seq("query" -> false), Seq("-cl" -> "consistencyLevel")), help = "Executes a CQL query"),
    Command(this, "cqlexport", cqlExport, UnixLikeParams(Seq("table" -> true, "limit" -> false), Seq("-f" -> "file", "-cl" -> "consistencyLevel")), help = "Executes a CQL query"),
    Command(this, "columnfamilies", columnFamilies, UnixLikeParams(Seq("query" -> false), Seq("-cl" -> "consistencyLevel")), help = "Displays the list of column families for the current keyspace"),
    Command(this, "describe", describe, UnixLikeParams(Seq("tableName" -> false)), help = "Displays the creation CQL for a table"),
    Command(this, "keyspace", useKeySpace, UnixLikeParams(Seq("keySpaceName" -> false)), help = "Opens a session to a given Cassandra keyspace"),
    Command(this, "keyspaces", keySpaces, UnixLikeParams(), help = "Retrieves the key spaces for the cluster"))

  /**
    * Attempts to retrieve an input source for the given URL
    * @param url the given input URL
    * @return the option of an input source
    */
  override def getInputSource(url: String): Option[MessageInputSource] = None

  /**
    * Attempts to retrieve an output source for the given URL
    * @param url the given output URL
    * @return the option of a [[CassandraMessageOutputSource]]
    */
  override def getOutputSource(url: String): Option[CassandraMessageOutputSource] = {
    for {
      columnFamily <- url.extractProperty("cassandra:")
      keySpace <- getKeySpaceName
    } yield new CassandraMessageOutputSource(connection, keySpace, columnFamily, getDefaultConsistencyLevel)
  }

  /**
    * Returns the name of the prefix (e.g. Seq("file"))
    * @return the name of the prefix
    */
  override def supportedPrefixes = Nil

  /**
    * Returns the label of the module (e.g. "kafka")
    * @return the label of the module
    */
  override def moduleLabel = "cql"

  /**
    * Returns the name of the module (e.g. "kafka")
    * @return the name of the module
    */
  override def moduleName = "cassandra"

  /**
    * Returns the the information that is to be displayed while the module is active
    * @return the the information that is to be displayed while the module is active
    */
  override def prompt = {
    val myCluster = conn_? map (_.cluster.getClusterName) getOrElse "$"
    val mySession = getKeySpaceName getOrElse "/"
    s"$myCluster:$mySession"
  }

  /**
    * Called when the application is shutting down
    */
  override def shutdown(): Unit = {
    Try(session_?.foreach(_.close()))
    Try(conn_?.foreach(_.close()))
    ()
  }

  /**
    * Retrieves the server information for the currently connected cluster
    * @example clusterinfo
    */
  def clusterInfo(params: UnixLikeArgs): Seq[NameValuePair] = {
    val c = connection.cluster
    val conf = c.getConfiguration
    val meta = c.getMetadata
    val queryOps = conf.getQueryOptions

    NameValuePair("Cluster Name", c.getClusterName) ::
      NameValuePair("Partitioner", meta.getPartitioner) ::
      NameValuePair("Consistency Level", queryOps.getConsistencyLevel) ::
      NameValuePair("Fetch Size", queryOps.getFetchSize) ::
      NameValuePair("JMX Reporting Enabled", conf.getMetricsOptions.isJMXReportingEnabled) :: Nil
  }

  /**
    * Displays the list of column families for the current keyspace
    * @example columnfamilies
    */
  def columnFamilies(params: UnixLikeArgs) = {
    val c = connection.cluster
    val k = session.session.getLoggedKeyspace
    val meta = c.getMetadata
    Option(meta.getKeyspace(k)) map (_.getTables) map {
      _ map { table =>
        TableItem(
          name = table.getName,
          primaryKey = table.getPrimaryKey.map(_.getName).mkString(", "),
          partitionKey = table.getPartitionKey.map(_.getName).mkString(", "))
      }
    }
  }

  case class TableItem(name: String, primaryKey: String, partitionKey: String)

  /**
    * Establishes a connection to Zookeeper
    * @example cqconnect
    * @example cqconnect localhost
    * @example cqconnect localhost -k myKeySpace
    * @example cqconnect dev601,dev602,dev603
    */
  def connect(params: UnixLikeArgs): Unit = {
    val keySpace_? = params("-k")

    // determine the requested end-point
    val endPoints = params.args match {
      case path :: Nil => path.split(",")
      case _ => dieSyntax(params)
    }

    // connect to the remote peer
    conn_?.foreach(_.close())
    conn_? = Option(Casserole(endPoints))

    // optionally setup the session
    keySpace_?.foreach { _ =>
      session_?.foreach(_.close())
      session_? = keySpace_?.map(connection.getSession)
    }
  }

  /**
    * Executes a CQL query
    * @example cql "select * from quotes where exchange = 'NASDAQ'"
    */
  def cql(params: UnixLikeArgs)(implicit ec: ExecutionContext): Future[ResultSet] = {
    val cl = params("-cl") map getConsistencyLevelByName getOrElse getDefaultConsistencyLevel
    val query = params.args.headOption getOrElse dieSyntax(params)
    session.executeQuery(query)(cl)
  }

  /**
    * Exports the contents of a table to disk as CSV
    * @example cqlexport stockQuotes -f /tmp/stockQuotes.txt
    * @return a promise of a count of the number of records written
    */
  def cqlExport(params: UnixLikeArgs)(implicit ec: ExecutionContext) = {
    val file = params("-f") map (new File(_)) orDie "No file specified"
    val cl = params("-cl") map getConsistencyLevelByName getOrElse getDefaultConsistencyLevel

    val (tableName, limit) = params.args match {
      case List(aTable) => (aTable, None)
      case List(aTable, aLimit) => (aTable, Some(aLimit.toLong))
      case _ => dieSyntax(params)
    }

    session.export(file, s"SELECT * FROM $tableName LIMIT ${limit.getOrElse(10000L)}")(cl, ec)
  }

  /**
    *
    * Displays the creation CQL for a keyspace or table
    * @example describe columnfamily shocktrade
    * @example describe keyspace quotes
    */
  def describe(params: UnixLikeArgs): Option[String] = {
    params.args match {
      case List("columnfamily", name) => describeTable(name)
      case List("keyspace", name) => describeKeySpace(name)
      case _ => dieSyntax(params)
    }
  }

  private def describeKeySpace(name: String): Option[String] = {
    val c = connection.cluster
    val meta = c.getMetadata
    Option(meta.getKeyspace(name)) map (_.asCQLQuery())
  }

  private def describeTable(name: String): Option[String] = {
    val c = connection.cluster
    val meta = c.getMetadata

    for {
      keySpace <- getKeySpaceName
      ksMeta = meta.getKeyspace(keySpace)
      tblMeta <- Option(ksMeta.getTable(name))
    } yield tblMeta.asCQLQuery()
  }

  /**
    * Retrieves the keyspaces for the currently connected cluster
    * @example keyspaces
    */
  def keySpaces(params: UnixLikeArgs): Seq[KeySpaceItem] = {
    val c = connection.cluster
    val meta = c.getMetadata
    meta.getKeyspaces map { ks =>
      KeySpaceItem(
        name = ks.getName,
        userTypes = ks.getUserTypes mkString ", ",
        durableWrites = ks.isDurableWrites)
    }
  }

  case class KeySpaceItem(name: String, userTypes: String, durableWrites: Boolean)

  /**
    * Opens a session to a given Cassandra keyspace
    * @example keyspace shocktrade
    */
  def useKeySpace(params: UnixLikeArgs) = {
    val keySpace = params.args.headOption getOrElse dieSyntax(params)
    session_?.foreach(_.close())
    session_? = Option(connection.getSession(keySpace))
  }

  private def connection: Casserole = conn_? getOrElse die(s"No Cassandra connection. Use: cqconnect <host>")

  private def getConsistencyLevelByName(name: String): ConsistencyLevel = {
    consistencyLevels.getOrElse(name, die(s"Invalid consistency level (valid values are: ${consistencyLevels.values.mkString(", ")})"))
  }

  private def getDefaultConsistencyLevel: ConsistencyLevel = {
    connection.cluster.getConfiguration.getQueryOptions.getConsistencyLevel
  }

  private def getKeySpaceName: Option[String] = session_? map (_.session.getLoggedKeyspace)

  private def session: CasseroleSession = {
    connection
    session_? getOrElse die(s"No Cassandra session. Use: keyspace <keySpace>")
  }

  private def decodeRow(row: Row, cds: Seq[ColumnDefinitions.Definition]): Map[String, Any] = {
    Map(cds map { cd =>
      val name = cd.getName
      val javaType = CodecRegistry.DEFAULT_INSTANCE.codecFor(cd.getType).getJavaType.getRawType
      val value = javaType match {
        case c if c == classOf[Array[Byte]] => row.getBytes(name)
        case c if c == classOf[java.math.BigDecimal] => row.getDecimal(name)
        case c if c == classOf[java.math.BigInteger] => row.getVarint(name)
        case c if c == classOf[java.lang.Boolean] => row.getBool(name)
        case c if c == classOf[java.util.Date] => row.getDate(name)
        case c if c == classOf[java.lang.Double] => row.getDouble(name)
        case c if c == classOf[java.lang.Float] => row.getFloat(name)
        case c if c == classOf[java.lang.Integer] => row.getInt(name)
        case c if c == classOf[java.lang.Long] => row.getLong(name)
        case c if c == classOf[java.util.Map[_, _]] => row.getMap(name, classOf[String], classOf[Object])
        case c if c == classOf[java.util.Set[_]] => row.getSet(name, classOf[Object])
        case c if c == classOf[String] => row.getString(name)
        case c if c == classOf[java.util.UUID] => row.getUUID(name)
        case c =>
          throw new IllegalStateException(s"Unsupported class type ${javaType.getName} for column ${cd.getTable}.$name")
      }
      (name, value)
    }: _*)
  }

}
