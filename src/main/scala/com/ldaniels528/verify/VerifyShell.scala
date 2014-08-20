package com.ldaniels528.verify

import java.io.{ByteArrayOutputStream, PrintStream}

import com.ldaniels528.tabular.Tabular
import com.ldaniels528.tabular.formatters._
import com.ldaniels528.verify.io.avro._
import com.ldaniels528.verify.modules.Module
import com.ldaniels528.verify.modules.Module.Command
import com.ldaniels528.verify.modules.kafka._
import com.ldaniels528.verify.modules.unix.UnixModule
import com.ldaniels528.verify.modules.zookeeper._
import org.slf4j.LoggerFactory

import scala.collection.GenTraversableOnce
import scala.concurrent.duration._
import scala.io.Source
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

/**
 * Verify Console Shell Application
 * @author lawrence.daniels@gmail.com
 */
class VerifyShell(rt: VerifyShellRuntime) {
  // logger instance
  private val logger = LoggerFactory.getLogger(getClass)

  // define a custom tabular instance
  private val tabular = new Tabular() /*with NumberFormatHandler*/ with AvroTables

  // redirect standard output and error to my own buffers
  private val out = System.out
  private val err = System.err
  private val buffer = new ByteArrayOutputStream(16384)
  System.setOut(new PrintStream(buffer))


  // define the modules
  private val modules: Seq[Module] = Seq(
    new CoreModule(),
    new KafkaModule(rt, out),
    new UnixModule(rt, out),
    new ZookeeperModule(rt, out))

  // load the history, then schedule session history file updates
  SessionManagement.history.load(rt.historyFile)
  SessionManagement.setupHistoryUpdates(rt.historyFile, 60 seconds)

  // load the commands from the modules
  private def commandSet: Map[String, Command] = rt.moduleManager.commandSet

  // make sure we shutdown the ZooKeeper connection
  Runtime.getRuntime.addShutdownHook(new Thread {
    override def run() {
      // shutdown the ZooKeeper instance
      rt.zkProxy.close()

      // close each module
      rt.moduleManager.shutdown()
    }
  })

  /**
   * Interactive shell
   */
  def shell() {
    val userName = scala.util.Properties.userName

    do {
      // display the prompt, and get the next line of input
      out.print("%s@%s:%s> ".format(userName, rt.remoteHost, activeModule.prompt))
      val line = Console.readLine().trim

      if (line.nonEmpty) {
        interpret(line) match {
          case Success(result) =>
            handleResult(result)(out)
            if (line != "history" && !line.startsWith("!") && !line.startsWith("?")) SessionManagement.history += line
          case Failure(e: IllegalArgumentException) =>
            if (rt.debugOn) e.printStackTrace()
            err.println(s"Syntax error: ${e.getMessage}")
          case Failure(e) =>
            if (rt.debugOn) e.printStackTrace()
            err.println(s"Runtime error: ${e.getMessage}")
        }
      }
    } while (rt.alive)
  }

  private def checkArgs(command: Command, args: Seq[String]): Seq[String] = {
    // determine the minimum and maximum number of parameters
    val minimum = command.params._1.size
    val maximum = minimum + command.params._2.size

    // make sure the arguments are within bounds
    if (args.length < minimum || args.length > maximum) {
      throw new IllegalArgumentException(s"Usage: ${command.prototype}")
    }

    args
  }

  private def handleResult(result: Any) {
    result match {
      // handle lists and sequences of case classes
      case s: Seq[_] if !Tabular.isPrimitives(s) => tabular.transform(s) foreach out.println

      // handle Either cases
      case e: Either[_, _] => e match {
        case Left(v) => handleResult(v)
        case Right(v) => handleResult(v)
      }

      // handle Option cases
      case o: Option[_] => o match {
        case Some(v) => handleResult(v)
        case None =>
      }

      // handle Try cases
      case t: Try[_] => t match {
        case Success(v) => handleResult(v)
        case Failure(e) => throw e
      }

      // handle lists and sequences of primitives
      case g: GenTraversableOnce[_] => g foreach out.println

      // anything else ...
      case x => if (x != null && !x.isInstanceOf[Unit]) out.println(x)
    }
  }

  private def interpret(input: String): Try[Any] = {
    // parse & evaluate the user input
    Try(parseInput(input) match {
      case Some((cmd, args)) =>
        // match the command
        commandSet.get(cmd) match {
          case Some(command) =>
            checkArgs(command, args)
            command.fx(args)
          case _ =>
            throw new IllegalArgumentException(s"'$input' not recognized")
        }
      case _ =>
    })
  }

    }

  }

  /**
   * Parses a line of input into a tuple consisting of the command and its arguments
   * @param input the given line of input
   * @return an option of a tuple consisting of the command and its arguments
   */
  private def parseInput(input: String): Option[(String, Seq[String])] = {
    // parse the user input
    val pcs = CommandParser.parse(input)

    // return the command and arguments
    for {
      cmd <- pcs.headOption map (_.toLowerCase)
      args = pcs.tail
    } yield (cmd, args)
  }

}