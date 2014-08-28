package com.ldaniels528.verify.modules.avro

import com.twitter.bijection.Injection
import com.twitter.bijection.avro.GenericAvroCodecs
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord

import scala.util.Try

/**
 * Apache Avro Decoder
 * @author Lawrence Daniels <lawrence.daniels@gmail.com>
 */
case class AvroDecoder(schemaString: String) {
  val schema = new Schema.Parser().parse(schemaString)
  val converter: Injection[GenericRecord, Array[Byte]] = GenericAvroCodecs.toBinary(schema)

  /**
   * Decodes the byte array based on the avro schema
   */
  def decode(bytes: Array[Byte]): Try[GenericRecord] = converter.invert(bytes)

  override def toString = schemaString

}