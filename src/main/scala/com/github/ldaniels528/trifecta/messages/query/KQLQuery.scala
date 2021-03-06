package com.github.ldaniels528.trifecta.messages.query

import com.github.ldaniels528.trifecta.TxRuntimeContext
import com.github.ldaniels528.trifecta.io.IOCounter

import scala.concurrent.{ExecutionContext, Future}

/**
  * Represents a Kafka Query Language (KQL) Question
  * @author lawrence.daniels@gmail.com
  */
trait KQLQuery {

  /**
    * Executes the given query
    * @param rt the given [[TxRuntimeContext]]
    */
  def executeQuery(rt: TxRuntimeContext, counter: IOCounter)(implicit ec: ExecutionContext): Future[KQLResult]

}
