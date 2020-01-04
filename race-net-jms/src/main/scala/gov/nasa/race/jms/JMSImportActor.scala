/*
 * Copyright (c) 2016, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gov.nasa.race.jms

import javax.jms.{BytesMessage, Connection, JMSException, Message, MessageConsumer, MessageListener, Session, TextMessage, Topic => JMSTopic}
import akka.actor.ActorRef
import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.actor.FilteringPublisher
import gov.nasa.race.archive.TaggedASCIIArchiveWriter
import gov.nasa.race.common.{ASCIIBuffer, StringDataBuffer, StringSlicer}
import gov.nasa.race.common.inlined.Slice
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.{ContinuousTimeRaceActor, RaceContext}
import gov.nasa.race.core.RaceActorCapabilities._
import org.apache.activemq.ActiveMQConnectionFactory
import org.apache.activemq.command.{Message => AMQMessage}
import org.apache.activemq.util.ByteSequence

import scala.language.postfixOps
import scala.collection.Seq

object JMSImportActor {
  // connection objects are shared, this is where the runtime cost is
  // NOTE - RACE is supposed to run as ONE logical user, i.e. we only need valid credentials
  // for the first connection. We do assume all components distributed with RACE share the same
  // protection domain
  case class ConnectionEntry(val connection: Connection, val clients: Set[JMSImportActor]) {
    def + (client: JMSImportActor) = copy(clients = clients + client)
    def - (client: JMSImportActor) = copy(clients = clients - client)
  }

  private var connections: Map[String,ConnectionEntry] = Map.empty
  private var factories: Map[String,ActiveMQConnectionFactory] = Map.empty

  def requestConnection (importer: JMSImportActor): Option[Connection] = synchronized {
    val brokerURI = importer.brokerURI
    val config = importer.config

    ifSome(connections.get(brokerURI)){ e =>
      connections = connections + (brokerURI -> (e + importer))
      return Some(e.connection)
    }

    importer.info(s"attempting to connect to $brokerURI")
    val factory = factories.get(brokerURI) match {
      case Some(f) => f
      case None =>
        val factory = new ActiveMQConnectionFactory(brokerURI)
        factories = factories + (brokerURI -> factory)
        factory
    }

    implicit val client = importer.getClass
    ifNotNull(factory.createConnection(config.getVaultableStringOrElse("user", null),
                                       config.getVaultableStringOrElse("pw", null))) { c =>
      c.setClientID(importer.jmsId)
      c.start()
      connections = connections + (brokerURI -> ConnectionEntry(c, Set(importer)))
      return Some(c)
    }

    None
  }

  // the last session closes the connection
  def releaseConnection(importer: JMSImportActor, connection: Connection) = synchronized {
    val brokerURI = importer.brokerURI

    ifSome(connections.get(brokerURI)) { e =>
      val clients = e.clients

      if (clients.contains(importer)) {
        if (clients.size == 1) {
          connection.stop()
          connection.close()
          importer.info(s"closed connection to $brokerURI")
          connections = connections - brokerURI
        } else {
          connections = connections + (brokerURI -> (e - importer))
        }
      }
    }
  }
}
import JMSImportActor._


/**
  * actor that imports messages from a JMS server and publishes them to the bus
  *
  * Server URI, JMS topic and bus channel are configured. We assume a JMSImportActor
  * instance per import topic so that we don't have to add additional mapping of
  * topics to bus channels.
  *
  * <todo> - reconnection and timeouts
  */
class JMSImportActor(val config: Config) extends FilteringPublisher {

  override def getCapabilities = super.getCapabilities - SupportsPauseResume - SupportsSimTimeReset

  // NOTE - the listener executes in non-Akka threads (multiple!)
  private[this] class Listener(val topic: JMSTopic) extends MessageListener {
    override def onMessage(msg: Message): Unit = {
      try {
        processMessage(msg)
      } catch {
        case ex: JMSException => error(s"exception publishing JMS message: $ex")
      }
    }
  }

  val flushOnStart = config.getBooleanOrElse("flush-on-start", false)
  val brokerURI = config.getVaultableStringOrElse("broker-uri", "tcp://localhost:61616")
  val jmsId = config.getStringOrElse("jms-id", self.path.name + System.currentTimeMillis.toHexString)
  val jmsTopic = config.getString("jms-topic")

  val publishRaw = config.getBooleanOrElse("publish-raw", false)

  //--- our state data
  var connection: Option[Connection] = None
  var session: Option[Session] = None
  var consumer: Option[MessageConsumer] = None

  //--- end initialization

  // override to process messages differently than just publishing the content as slice or string
  protected def processMessage (msg: Message): Unit = {
    if (publishRaw) {
      processMessage(getContentSlice(msg))
    } else {
      processMessage(getContentString(msg))
    }
  }

  protected def processMessage (slice: Slice): Unit = {
    publishFiltered(slice)
  }

  protected def processMessage (s: String): Unit = {
    publishFiltered(s)
  }

  // override if we only (re-)use the slice sync (e.g. for translating JMSImporters)
  protected def getContentSlice(s: String): Slice = Slice(s)

  // this is a ActiveMQ optimization that lets us avoid copying the message content (UTF8 bytes)
  // into a string, just to copy it again into a Array[Byte] in respective parsers. JMS messages
  // can be quite large
  protected def getContentSlice (msg: Message): Slice = {
    msg match {
      //--- optimized ActiveMQ messages

      case amqMsg: AMQMessage =>
        val byteSeq: ByteSequence = amqMsg.getContent
        val data: Array[Byte] = byteSeq.data
        val utfLen: Int = ((data(0) & 0xff)<<24) | ((data(1) & 0xff)<<16) | ((data(2) & 0xff)<<8) | (data(3) & 0xff)
        Slice(data,byteSeq.offset+4,utfLen)

      //--- generic JMS messages

      case txtMsg: TextMessage =>
          getContentSlice(txtMsg.getText)

      case byteMsg: BytesMessage =>
        val len = byteMsg.getBodyLength.toInt
        val bs = new Array[Byte](len)
        byteMsg.readBytes(bs)
        Slice(bs)

      case _ => Slice.empty
    }
  }

  protected def getContentString (msg: Message): String = {
    msg match {
      case txtMsg: TextMessage => txtMsg.getText
      case byteMsg: BytesMessage => byteMsg.readUTF
      case _ => ""
    }
  }


  // TODO - maybe we should reject if JMS connection or session/consumer init fails

  override def onInitializeRaceActor(raceContext: RaceContext, actorConf: Config) = {
    if (super.onInitializeRaceActor(raceContext,actorConf)) {
      try {
        connection = requestConnection(this)
        info(s"connected to $brokerURI")
        true

      } catch {
        case ex: Throwable =>
          warning(s"failed to open connection: $ex")
          false
      }
    } else false
  }

  override def onStartRaceActor(originator: ActorRef) = {
    session = createSession(connection)
    if (session.isDefined){
      consumer = createConsumer(session)
      if (consumer.isDefined) info(s"opened session on jms topic '$jmsTopic'")
      else warning(s"failed to create JMS consumer for jms topic '$jmsTopic'")
    } else warning(s"failed to create JMS session for connection '$brokerURI'")

    super.onStartRaceActor(originator)
  }

  override def handleMessage = handleFilteringPublisherMessage

  def createSession (connection: Option[Connection]) = {
    tryWithSome(connection)( _.createSession(false, Session.AUTO_ACKNOWLEDGE))
  }
  def createConsumer (session: Option[Session]) = {
    tryWithSome(session)( s => {
      val topic = s.createTopic(jmsTopic)
      if (topic != null) {
        val c = s.createConsumer(topic)
        if (c != null) {
          if (flushOnStart) {
            // the idea is that we always see peak flow at start, which is probably due
            // to a combination of server backlog and slower initial processing because of
            // extra class loading & JIT effort. Here we try to eliminate server backlog
            while (c.receiveNoWait() != null){}
          }

          c.setMessageListener(new Listener(topic))
          c
        } else null // no consumer
      } else null // no topic
    })
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    info(s"closing session")
    try {
      session.foreach(_.close())
      connection.foreach( releaseConnection(this,_))
    } catch {
      case ex: Throwable => warning(s"failed to close session: $ex")
    }

    super.onTerminateRaceActor(originator)
  }
}

/**
  * a JMSImportActor that does not have to keep persistent message text since it only publishes the
  * products of translating this text
  */
trait TranslatingJMSImportActor extends JMSImportActor {

  val contentSlicer = new StringSlicer(new ASCIIBuffer(120000)) // this is only executed when processing String messages
  override def getContentSlice (s: String): Slice = contentSlicer.slice(s)

  // to be provided by concrete type, e.g. to call parser
  protected def translate (msg: Message): Any

  override protected def processMessage (msg: Message): Unit = {
    publishFiltered(translate(msg))
  }
}

/**
  * trait that can be mixed in to add archiving of received messages using a
  * TaggedTextArchiver
  */
trait ArchivingJMSImportActor extends JMSImportActor with ContinuousTimeRaceActor {
  val writer = new TaggedASCIIArchiveWriter(config)
  val archiveOnly = config.getBooleanOrElse("archive-only", true)

  var cachedContent: Slice = Slice.empty
  var stopArchiving = false

  override def getContentSlice(msg: Message): Slice = {
    if (cachedContent.isEmpty) cachedContent = super.getContentSlice(msg)
    cachedContent
  }

  override protected def processMessage(msg: Message): Unit = {
    val content = getContentSlice(msg)
    if (content.nonEmpty) {
      if (!stopArchiving) writer.write(currentSimTime, content)

      // NOTE - this should not be used in combination with a supertype that copies data in processMessage
      if (!archiveOnly) super.processMessage(msg)
    }

    cachedContent.clear // make sure we re-init cache on next call
  }

  override def onStartRaceActor (originator: ActorRef) = {
    writer.open(baseSimTime)
    super.onStartRaceActor(originator)
  }

  override def onTerminateRaceActor(originator: ActorRef) = {
    stopArchiving = true
    writer.close
    super.onTerminateRaceActor(originator)
  }
}

/**
  * a JMSImportActor that is solely used for archiving received messages
  */
class JMSArchiveActor (config: Config) extends JMSImportActor(config) with ArchivingJMSImportActor