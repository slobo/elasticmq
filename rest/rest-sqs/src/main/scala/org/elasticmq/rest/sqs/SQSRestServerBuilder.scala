package org.elasticmq.rest.sqs

import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer

import scala.util.control.Exception._
import xml._
import java.security.MessageDigest

import org.elasticmq.util.Logging

import collection.mutable.ArrayBuffer
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.util.Timeout

import scala.concurrent.{Await, Future}
import org.elasticmq.rest.sqs.directives.ElasticMQDirectives
import org.elasticmq.rest.sqs.Constants._

import scala.xml.EntityRef
import org.elasticmq._
import com.typesafe.config.ConfigFactory
import org.elasticmq.actor.QueueManagerActor
import org.elasticmq.util.NowProvider

import scala.concurrent.duration._
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream

import scala.collection.immutable.TreeMap
import java.util.concurrent.TimeUnit

import akka.http.scaladsl.server.{Directive1, Directives}

/**
 * By default:
 * <li>
 *  <ul>for `socketAddress`: when started, the server will bind to `localhost:9324`</ul>
 *  <ul>for `serverAddress`: returned queue addresses will use `http://localhost:9324` as the base address.</ul>
 *  <ul>for `sqsLimits`: relaxed
 * </li>
 */
object SQSRestServerBuilder extends TheSQSRestServerBuilder(None, None, "", 9324, NodeAddress(), SQSLimits.Strict)

case class TheSQSRestServerBuilder(providedActorSystem: Option[ActorSystem],
                                   providedQueueManagerActor: Option[ActorRef],
                                   interface: String,
                                   port: Int,
                                   serverAddress: NodeAddress,
                                   sqsLimits: SQSLimits.Value) extends Logging {

  /**
   * @param _actorSystem Optional actor system. If one is provided, it will be used to create ElasticMQ and Spray
   *                     actors, but its lifecycle (shutdown) will be not managed by the server. If one is not
   *                     provided, an actor system will be created, and its lifecycle will be bound to the server's
   *                     lifecycle.
   */
  def withActorSystem(_actorSystem: ActorSystem) = this.copy(providedActorSystem = Some(_actorSystem))

  /**
   * @param _queueManagerActor Optional "main" ElasticMQ actor.
   */
  def withQueueManagerActor(_queueManagerActor: ActorRef) = this.copy(providedQueueManagerActor = Some(_queueManagerActor))

  /**
   * @param _interface Hostname to which the server will bind.
   */
  def withInterface(_interface: String) = this.copy(interface = _interface)

  /**
   * @param _port Port to which the server will bind.
   */
  def withPort(_port: Int) = this.copy(port = _port)

  /**
   * @param _serverAddress Address which will be returned as the queue address. Requests to this address
   *                       should be routed to this server.
   */
  def withServerAddress(_serverAddress: NodeAddress) = this.copy(serverAddress = _serverAddress)

  /**
   * @param _sqsLimits Should "real" SQS limits be used (strict), or should they be relaxed where possible (regarding
   *                   e.g. message size).
   */
  def withSQSLimits(_sqsLimits: SQSLimits.Value) = this.copy(sqsLimits = _sqsLimits)

  def start(): SQSRestServer = {
    val (theActorSystem, stopActorSystem) = getOrCreateActorSystem
    val theQueueManagerActor = getOrCreateQueueManagerActor(theActorSystem)
    val theServerAddress = serverAddress
    val theLimits = sqsLimits

    implicit val implicitActorSystem = theActorSystem
    implicit val implicitMaterializer = ActorMaterializer()

    val env = new QueueManagerActorModule
      with QueueURLModule
      with SQSLimitsModule
      with BatchRequestsModule
      with ElasticMQDirectives
      with CreateQueueDirectives
      with DeleteQueueDirectives
      with QueueAttributesDirectives
      with ListQueuesDirectives
      with SendMessageDirectives
      with SendMessageBatchDirectives
      with ReceiveMessageDirectives
      with DeleteMessageDirectives
      with DeleteMessageBatchDirectives
      with ChangeMessageVisibilityDirectives
      with ChangeMessageVisibilityBatchDirectives
      with GetQueueUrlDirectives
      with PurgeQueueDirectives
      with AttributesModule {

      lazy val actorSystem = theActorSystem
      lazy val materializer = implicitMaterializer
      lazy val queueManagerActor = theQueueManagerActor
      lazy val serverAddress = theServerAddress
      lazy val sqsLimits = theLimits
      lazy val timeout = Timeout(21, TimeUnit.SECONDS) // see application.conf
    }

    import env._
    def rawRoutes(p: AnyParams) =
        // 1. Sending, receiving, deleting messages
        sendMessage(p) ~
        sendMessageBatch(p) ~
        receiveMessage(p) ~
        deleteMessage(p) ~
        deleteMessageBatch(p) ~
        // 2. Getting, creating queues
        getQueueUrl(p) ~
        createQueue(p) ~
        listQueues(p) ~
        purgeQueue(p) ~
        // 3. Other
        changeMessageVisibility(p) ~
        changeMessageVisibilityBatch(p) ~
        deleteQueue(p) ~
        getQueueAttributes(p) ~
        setQueueAttributes(p)

    val config = new ElasticMQConfig

    implicit val bindingTimeout = Timeout(10, TimeUnit.SECONDS)

    val routes =
      handleServerExceptions {
        handleRejectionsWithSQSError {
          anyParamsMap { p =>
            if (config.debug) {
              logRequestResult("") {
                rawRoutes(p)
              }
            } else rawRoutes(p)
          }
        }
      }

    val appStartFuture = Http().bindAndHandle(routes, interface, port)

    TheSQSRestServerBuilder.this.logger.info("Started SQS rest server, bind address %s:%d, visible server address %s"
      .format(interface, port, if (theServerAddress.isWildcard) "* (depends on incoming request path) "else theServerAddress.fullAddress))

    SQSRestServer(appStartFuture, () => {
      appStartFuture.flatMap { sb =>
        stopActorSystem()
        sb.unbind()
      }
    })
  }

  private def getOrCreateActorSystem = {
    providedActorSystem
      .map((_, () => ()))
      .getOrElse {
      val actorSystem = ActorSystem("elasticmq")
      (actorSystem, () => {
        actorSystem.shutdown()
        actorSystem.awaitTermination()
      })
    }
  }

  private def getOrCreateQueueManagerActor(actorSystem: ActorSystem) = {
    providedQueueManagerActor.getOrElse(actorSystem.actorOf(Props(new QueueManagerActor(new NowProvider()))))
  }
}

case class SQSRestServer(startFuture: Future[Http.ServerBinding], stopAndGetFuture: () => Future[Any]) {
  def waitUntilStarted() = {
    Await.result(startFuture, 1.minute)
  }

  def stopAndWait() = {
    val stopFuture = stopAndGetFuture()
    Await.result(stopFuture, 1.minute)
  }
}

object Constants {
  val EmptyRequestId = "00000000-0000-0000-0000-000000000000"
  val SqsDefaultVersion = "2012-11-05"
  val ReceiptHandleParameter = "ReceiptHandle"
  val VisibilityTimeoutParameter = "VisibilityTimeout"
  val DelaySecondsAttribute = "DelaySeconds"
  val ReceiveMessageWaitTimeSecondsAttribute = "ReceiveMessageWaitTimeSeconds"
  val QueueArnAttribute = "QueueArn"
  val IdSubParameter = "Id"
  val InvalidParameterValueErrorName = "InvalidParameterValue"
  val QueueUrlContext = "queue"
}

object ParametersUtil {
  implicit class ParametersParser(parameters: Map[String, String]) {
    def parseOptionalLong(name: String) = {
      val param = parameters.get(name)
      try {
        param.map(_.toLong)
      } catch {
        case e: NumberFormatException => throw SQSException.invalidParameterValue
      }
    }
  }
}

object MD5Util {
  def md5Digest(s: String) = {
    val md5 = MessageDigest.getInstance("MD5")
    md5.reset()
    md5.update(s.getBytes)
    md5.digest().map(0xFF & _).map { "%02x".format(_) }.foldLeft(""){_ + _}
  }

  def md5AttributeDigest(attributes: Map[String, MessageAttribute]): String = {
    def addEncodedString(b: ByteArrayOutputStream, s: String) = {
      val str = s.getBytes("UTF-8")
      b.write(ByteBuffer.allocate(4).putInt(s.length).array) // Sadly, we'll need ByteBuffer here to get a properly encoded 4-byte int (alternatively, we could encode by hand)
      b.write(str)
    }

    def addEncodedByteArray(b: ByteArrayOutputStream, a: Array[Byte]) = {
      b.write(ByteBuffer.allocate(4).putInt(a.length).array)
      b.write(a)
    }

    val byteStream = new ByteArrayOutputStream

    TreeMap(attributes.toSeq: _*).foreach { case (k, v) => {
      // TreeMap is for sorting, a requirement of algorithm
      addEncodedString(byteStream, k)
      addEncodedString(byteStream, v.getDataType())

      v match {
        case s: StringMessageAttribute => {
          byteStream.write(1)
          addEncodedString(byteStream, s.stringValue)
        }
        case n: NumberMessageAttribute => {
          byteStream.write(1)
          addEncodedString(byteStream, n.stringValue.toString)
        }
        case b: BinaryMessageAttribute => {
          byteStream.write(2)
          addEncodedByteArray(byteStream, b.binaryValue)
        }
        case _ => throw new IllegalArgumentException(s"Unsupported message attribute type: ${v.getClass.getName}")
      }
    }
    }

    val md5 = MessageDigest.getInstance("MD5")
    md5.reset()
    md5.update(byteStream.toByteArray)
    md5.digest().map(0xFF & _).map { "%02x".format(_) }.foldLeft(""){_ + _}
  }
}

object XmlUtil {
  private val CR = EntityRef("#13")

  def convertTexWithCRToNodeSeq(text: String): NodeSeq = {
    val parts = text.split("\r")
    val partsCount = parts.length
    if (partsCount == 1) {
      Text(text)
    } else {
      val combined = new ArrayBuffer[scala.xml.Node]()
      for (i <- 0 until partsCount) {
        combined += Text(parts(i))
        if (i != partsCount - 1) {
          combined += CR
        }
      }

      combined
    }
  }
}

trait QueueManagerActorModule {
  def queueManagerActor: ActorRef
}

trait QueueURLModule {
  def serverAddress: NodeAddress

  import Directives._

  def baseQueueURL: Directive1[String] = {
    val baseAddress = if (serverAddress.isWildcard) {
      extractRequest.map { req =>
        val incomingAddress = req.uri.copy(rawQueryString = None, fragment = None).toString

        val incomingAddressNoSlash = if (incomingAddress.endsWith("/")) {
          incomingAddress.substring(0, incomingAddress.length - 1)
        } else incomingAddress

        // removing the final /queue or /queue/ if present, it will be re-added later
        if (incomingAddressNoSlash.endsWith(QueueUrlContext)) {
          incomingAddressNoSlash.substring(0, incomingAddressNoSlash.length - QueueUrlContext.length - 1)
        } else incomingAddressNoSlash
      }
    } else {
      provide(serverAddress.fullAddress)
    }

    baseAddress.map(_ + "/" + QueueUrlContext)
  }


  def queueURL(queueData: QueueData): Directive1[String] = {
    baseQueueURL.map(base => base + "/" + queueData.name)
  }
}

object SQSLimits extends Enumeration {
  val Strict = Value
  val Relaxed = Value
}

trait SQSLimitsModule {

  val NUMBER_ATTR_MAX_VALUE = BigDecimal.valueOf(10).pow(126)
  val NUMBER_ATTR_MIN_VALUE = -BigDecimal.valueOf(10).pow(128)

  def sqsLimits: SQSLimits.Value

  def ifStrictLimits(condition: => Boolean)(exception: String) {
    if (sqsLimits == SQSLimits.Strict && condition) {
      throw new SQSException(exception)
    }
  }

  def verifyMessageNumberAttribute(strValue: String) {
    ifStrictLimits(allCatch.opt(BigDecimal(strValue))
      .filter(v => v >= NUMBER_ATTR_MIN_VALUE)
      .filter(v => v <= NUMBER_ATTR_MAX_VALUE).isEmpty) {
      s"Number attribute value $strValue should be in range (-10**128..10**126)"
    }
  }

  def verifyMessageWaitTime(messageWaitTimeOpt: Option[Long]) {
    messageWaitTimeOpt.foreach { messageWaitTime =>
      if (messageWaitTime < 0) {
        throw SQSException.invalidParameterValue
      }

      ifStrictLimits(messageWaitTime > 20 || messageWaitTime < 1) {
        InvalidParameterValueErrorName
      }
    }
  }
}

class ElasticMQConfig {
  private lazy val rootConfig = ConfigFactory.load()
  private lazy val elasticMQConfig = rootConfig.getConfig("elasticmq")

  lazy val debug = elasticMQConfig.getBoolean("debug")
}
