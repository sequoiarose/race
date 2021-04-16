/*
 * Copyright (c) 2020, United States Government, as represented by the
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
package gov.nasa.race.http.share

import akka.Done
import akka.actor.{ActorRef, Cancellable}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import com.typesafe.config.Config
import gov.nasa.race.common.{BufferedStringJsonPullParser, JsonWriter}
import gov.nasa.race.core.Messages.{BusEvent, SyncRequest}
import gov.nasa.race.core.{ContinuousTimeRaceActor, PeriodicRaceActor, Ping, Pong, PongParser}
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.http.{WSAdapterActor, WsConnectRequest, WsMessageReader, WsMessageWriter}
import gov.nasa.race.{ifNotNull, ifSome}
import gov.nasa.race.uom.DateTime

import scala.collection.immutable.SeqMap
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
  * the upstream websocket adapter that connects to the upstream server running a NodeServerRoute
  *
  * this actor implements the client side of the synchronization protocol, which has to guarantee
  *〖
  *   - minimal network configuration (only respective upstream address needs to be known a priori)
  *   - client node therefore initiates sync with server
  *   - client actively monitors server node connection to keep websocket alive and ensure proper server response
  * 〗
  */
class UpstreamConnectorActor (val config: Config) extends PeriodicRaceActor with WSAdapterActor with NodeDatesResponder {

  class ShareWriter extends WsMessageWriter {
    val jw = new JsonWriter

    override def write(o: Any): Option[Message] = {
      o match {
        case ss: NodeDates => Some(TextMessage.Strict(jw.toJson(ss)))
        case cdc: ColumnDataChange => Some(TextMessage.Strict(jw.toJson(cdc)))
        case ping: Ping => Some(TextMessage.Strict(jw.toJson(ping)))
        case _ => None // we don't send other messages upstream
      }
    }
  }

  class ShareReader extends WsMessageReader {
    override def read(m: Message): Option[Any] = {
      m match {
        case tm: TextMessage.Strict => state.parseIncomingMessage(tm.text)
        case _ => None // ignore
      }
    }
  }

  /**
    * state specific RaceActor/WSAdapterActor behavior
    */
  trait InternalConnectorState {

    //--- state specific RACE message handlers - default is to ignore everything
    def handleNode (newNode: Node): Unit = {}
    def handleColumnDataChange (cdc: ColumnDataChange): Unit = {}
    def handleConstraintChange (cc: ConstraintChange): Unit = {}
    def handleColumnReachabilityChange (crc: ColumnReachabilityChange): Unit = {}

    def connect(): Unit = {}

    def onRaceTick(): Unit = {}
    def onSyncResponse (responder: ActorRef, responderType: Class[_], tag: Any): Unit = {}
    def onConnect(): Unit = {}
    def onDisconnect(): Unit = {}

    //--- WSAdapterActor
    def processIncomingMessage (msg: Message): Unit // what we do with parsed incoming messages

    //--- UpstreamConnectorActor
    def parseIncomingMessage (msg: String): Option[Any]  // what incoming messages we parse

    def switchToState (newState: InternalConnectorState): Unit = {
      info(s"switching state from ${this.getClass.getSimpleName} to ${newState.getClass.getSimpleName}")
      state = newState
    }
  }

  /**
    * in this state we don't parse or process any messages to/from upstream but just wait for the
    * internal node message to arrive and periodically start to cycle through the upstreamNodes list until we
    * can connect
    */
  class InitialState extends InternalConnectorState {
    var node: Option[Node] = None

    var upstreamCandidates: SeqMap[String,NodeInfo] = SeqMap.empty
    var upstreamIterator: Iterator[NodeInfo] = upstreamCandidates.valuesIterator
    var candidate: Option[NodeInfo] = None

    override def handleNode (newNode: Node): Unit = {
      node = Some(newNode) // we are not switching state yet
      if (newNode.nodeList.upstreamNodes ne upstreamCandidates) {
        upstreamCandidates = newNode.nodeList.upstreamNodes
        upstreamIterator = upstreamCandidates.valuesIterator
        candidate = None
      }
    }

    override def onRaceTick(): Unit = connect()

    override def connect(): Unit = {
      if (upstreamCandidates.nonEmpty) {
        if (!upstreamIterator.hasNext) upstreamIterator = upstreamCandidates.valuesIterator // wrap around
        candidate = upstreamIterator.nextOption()
        candidate.flatMap(_.getUri) match {
          case Some(uri) =>
            UpstreamConnectorActor.this.connect( uri + '/' + NodeServerRoute.requestPrefix)
          case None =>
            warning(s"no uri for upstream candidate $candidate")
        }
      }
    }

    override def onConnect(): Unit = {
      for (n <- node; ni <- candidate) {
        val upstreamId = ni.id
        publish( SelectUpstream(Some(upstreamId)))
        switchToState( new SynchronizingState(upstreamId, n))
      }
    }

    override def onDisconnect(): Unit = {}
    override def processIncomingMessage (msg: Message): Unit = {}
    override def parseIncomingMessage (msg: String): Option[Any] = None
  }


  /**
    * a state in which we are connected and have a node. We monitor the connection and parse all incoming messages
    */
  trait ConnectedState extends InternalConnectorState {
    def upstreamId: String
    def node: Node

    // since this is run per message we need to cache and buffer to avoid allocation
    class IncomingMessageParser extends BufferedStringJsonPullParser
                                with ColumnDataChangeParser with NodeDatesParser with PongParser {
      override def rowList = node.rowList

      def parse (msg: String): Option[Any] = parseMessageSet[Option[Any]](msg, None) {
        case ColumnDataChange.COLUMN_DATA_CHANGE => parseColumnDataChange()
        case NodeDates.NODE_DATES => parseNodeDates()
        case Ping.PONG => parsePong()
        case other => warning(s"ignoring unknown message type '$other'"); None
      }
    }

    val parser = new IncomingMessageParser

    //--- liveness and QoS data (wall clock based)
    var nPings: Int = 0
    var lastPingTime: DateTime = DateTime.UndefinedDateTime
    var lastPongTime: DateTime = DateTime.UndefinedDateTime

    override def onDisconnect(): Unit = {
      publish( SelectUpstream(None))
      publish( NodeReachabilityChange.offline(upstreamId, node.currentDateTime))

      lastPingTime = DateTime.UndefinedDateTime
      lastPongTime = DateTime.UndefinedDateTime
      nPings = 0

      if (isTerminating) {
        switchToState(new TerminatedState)
      } else {
        val uri = connection.map(_.uri).get // we are in the onDisconnect notification so its still defined
        switchToState(new ReconnectingState(uri, upstreamId, node))
      }
    }

    override def onRaceTick(): Unit = pingUpstream

    override def parseIncomingMessage (msg: String): Option[Any] = parser.parse(msg)

    def processUpstreamColumnReachability (cr: ColumnReachabilityChange): Boolean = {
      if (cr.nodeId == upstreamId) {
        publish(cr)  // we just relay it to the UpdateActor
        true
      } else {
        warning(s"ignoring ColumnReachabilityChange of non-upstream node ${cr.nodeId}")
        false
      }
    }

    /**
      * we do active pings for the dual purpose of (1) keep the web socket alive w/o overly aggressive global config,
      * and (2) to ensure end-to-end connection with the corresponding upstream NodeServerRoute HttpServer actor
      */
    def pingUpstream: Unit = {
      if (lastPongTime < lastPingTime) { // means we have not gotten a reply during the last ping cycle
        warning(s"no response for last ping at $lastPingTime, disconnecting")
        disconnect() // this will result in a onDisconnect call, which causes a state transition

      } else {
        nPings += 1
        lastPingTime = DateTime.now // this is wall clock time
        val ping = Ping(node.id, upstreamId,nPings,lastPingTime)
        processOutgoingMessage(ping)
      }
    }

    /**
      * connection check response
      */
    def processUpstreamPong (pong: Pong): Unit = {
      val ping = pong.ping
      if (ping.request != nPings) warning(s"out of order pong: $pong")
      if (ping.sender != node.id) warning(s"not our request: $pong")
      if (ping.receiver != upstreamId) warning( s"not our upstream: $pong")
      lastPongTime = pong.date

      val dt = pong.date.timeSince(ping.date)
      if (dt.toMillis < 0) warning(s"negative round trip time: $pong")
      info(s"ping response time from upstream: $dt")
    }

    /**
      * this is a CDC we receive from the upstream socket
      */
    def processUpstreamColumnDataChange (rawCdc: ColumnDataChange): Unit = {
      for (upstreamId <- node.upstreamId) {
        node.columnList.get(rawCdc.columnId) match {
          case Some(col) =>
            if (col.receive.matches( upstreamId, col.owner, node)) {
              val cdc = rawCdc.filter { r =>
                node.rowList.get(r) match {
                  case Some(row) => row.receive.matches(rawCdc.changeNodeId, col.owner, node)
                  case None => false
                }
              }
              if (cdc.nonEmpty) publishFiltered(cdc)
            }
          case None => // ignore
        }
      }
    }
  }

  /**
    * this is what we enter once we are connected and have a node. This sends the own NodeDates and processes the upstream replies
    * We do not send out CDCs from the bus yet, which we shouldn't receive from the bus until we published that
    * the upstream node is connected and synced
    */
  class SynchronizingState (val upstreamId: String, var node: Node) extends ConnectedState {
    var syncStarted: Boolean = false

    info("entering upstream sync mode")
    checkProtocolStart()

    def checkProtocolStart(): Unit = {
      if (!syncStarted && node.upstreamId.isDefined && node.upstreamId.get == upstreamId) {
        syncStarted = true
        info(s"starting upstream synchronization with: $upstreamId")
        sendOwnNodeDates
      }
    }

    override def handleNode (newNode: Node): Unit =  {
      node = newNode
      checkProtocolStart()
    }

    def sendOwnNodeDates: Unit = {
      val roCDs = mutable.Buffer.empty[ColumnDatePair]
      val rwCDs = mutable.Buffer.empty[(String,Seq[RowDatePair])]

      node.columnList.orderedEntries(node.columnDatas).foreach { e=>
        val (colId,cd) = e
        val col = node.columnList(colId)

        // if both upstream and we as the owner are valid receive sources this is a RW column
        if (col.receive.matches(upstreamId,col.owner,node) && col.receive.matches(node.id,col.owner,node)) {
          rwCDs += (colId -> cd.orderedCellDates(node.rowList))  // both can write - we need row dates
        } else {
          roCDs += (colId -> cd.date) // only one can write, CD date is enough
        }
      }

      if (roCDs.nonEmpty || rwCDs.nonEmpty) {
        processOutgoingMessage( NodeDates(node,roCDs.toSeq,rwCDs.toSeq))
      }
    }

    override def processIncomingMessage(msg: Message): Unit = {
      reader.read(msg) match {
        case Some(nd: NodeDates) => processUpstreamNodeDates(nd)
        case Some(cdc: ColumnDataChange) => processUpstreamColumnDataChange(cdc)
        case Some(cr: ColumnReachabilityChange) => processUpstreamColumnReachability(cr)
        case Some(pong: Pong) => processUpstreamPong(pong)
        case Some(other) => warning(s"ignoring incoming upstream message $other")
        case None => // ignore
      }
    }

    /**
      * this is the upstream response to our own NodeDates.
      * Note that we get CDCs from upstream first, i.e. once we get its NS upstream has already sent us all its
      * newer data, but we might in turn have to send more CDCs in response to the NS
      */
    def processUpstreamNodeDates(nd: NodeDates): Boolean = {
      if (nd.nodeId == upstreamId) {
        // we need to make sure previous upstream CDCs have been processed and are reflected in the current Node object
        publishSyncRequest(nd, Some(classOf[UpdateActor]))
        true

      } else {
        warning(s"ignoring NodeDates of non-upstream node ${nd.nodeId}")
        false
      }
    }

    override def onSyncResponse (responder: ActorRef, responderType: Class[_], tag: Any): Unit = {
      tag match {
        case nd: NodeDates =>
          if (nd.readOnlyColumns.nonEmpty || nd.readWriteColumns.nonEmpty) {
            getNodeDatesReplies( node, nd.nodeId, nd,UpstreamConnectorActor.this).foreach {
              processOutgoingMessage
            }

          } else { // no more upstream diffs, indicate upstream is online and switch modes
            publish( NodeReachabilityChange.online(upstreamId, node.currentDateTime))
            switchToState( new SynchronizedState(upstreamId, node))
          }

        case _ => // ignore, not our sync request
      }
    }
  }

  /**
    * connected state after NodeDates sync. This is the mode in which we send and receive CDCs, CRCs and the like
    */
  class SynchronizedState (val upstreamId: String, var node: Node) extends ConnectedState {

    info(s"synchronized with upstream: $upstreamId")

    override def handleNode (newNode: Node): Unit = {
      node = newNode
    }

    // a locally originated CDC
    override def handleColumnDataChange (rawCdc: ColumnDataChange): Unit = {
      for (upstreamId <- node.upstreamId) {
        if (rawCdc.changeNodeId == node.id) { // we only send CDCs for columns we own and that are changed here
          node.columnList.get(rawCdc.columnId) match {
            case Some(col) =>
              if (node.isColumnOwner(col, node.id)) {
                val cdc = rawCdc.filter { r => // but we might have to filter rows
                  node.rowList.get(r) match {
                    case Some(row) => row.send.matches(upstreamId,col.owner,node)
                    case None => false
                  }
                }

                if (cdc.nonEmpty) processOutgoingMessage(cdc)

              } else warning(s"ignoring CDC for externally owned column: $rawCdc")
            case None => warning(s"ignoring CDC for unknown column: $rawCdc")
          }
        }
      }
    }

    override def handleColumnReachabilityChange (crc: ColumnReachabilityChange): Unit = {
      // only send if this didn't come from upstream and we report respective columns
      if (crc.nodeId != upstreamId) {
        // TODO - filter here
      }
    }

    override def processIncomingMessage (msg: Message): Unit = {
      reader.read(msg) match {
        case Some(cdc: ColumnDataChange) => processUpstreamColumnDataChange(cdc)
        case Some(cr: ColumnReachabilityChange) => processUpstreamColumnReachability(cr)
        case Some(pong: Pong) => processUpstreamPong(pong)
        case Some(other) => warning(s"ignoring incoming upstream message $other")
        case None => // ignore
      }
    }
  }

  /**
    * disconnected state in which we only keep track of node changes and periodically try to reconnect to the last
    * connected upstream up to a maximum number of attempts, after which we fall back into initMode
    *
    * This mode is mostly aimed at upstream reboots, which should not trigger a new upstream selection if the node
    * comes back online in a reasonable amount of time
    */
  class ReconnectingState (val upstreamUri: String, val upstreamId: String, var node: Node) extends InternalConnectorState {
    var remainingAttempts = 5

    info(s"entering reconnect state for upstream: $upstreamId $upstreamUri")

    override def onRaceTick(): Unit = connect()

    override def connect(): Unit = {
      if (remainingAttempts > 0) {
        remainingAttempts -= 1
        UpstreamConnectorActor.this.connect(upstreamUri)

      } else {
        warning(s"giving up to reconnect to $upstreamId, falling back to init mode")
        switchToState(new InitialState)
      }
    }

    override def handleNode (newNode: Node): Unit = node = newNode  // we just update the node

    override def onConnect(): Unit = switchToState( new SynchronizingState(upstreamId,node))

    //--- no incoming messages
    override def parseIncomingMessage(msg: String): Option[Any] = None
    override def processIncomingMessage(msg: Message): Unit = {}
  }

  /**
    * end state in which we don't try to reconnect and ignore incoming and outgoing messages
    */
  class TerminatedState extends InternalConnectorState {
    val isReadyToConnect = false

    info(s"connection disabled")

    override def onConnect(): Unit = {}
    override def onDisconnect(): Unit = {}
    override def processIncomingMessage(msg: Message): Unit = {}
    override def parseIncomingMessage(msg: String): Option[Any] = None
  }

  //--- begin actor implementation

  var state: InternalConnectorState = new InitialState

  //--- support for simulated connection loss
  val simMode = config.getBooleanOrElse("sim-mode", false)
  var cutCon: Boolean = false

  //--- those are hardwired
  override def createWriter: WsMessageWriter = new ShareWriter
  override def createReader: WsMessageReader = new ShareReader

  override def defaultTickInterval: FiniteDuration = 30.seconds
  override def defaultTickDelay: FiniteDuration = 30.seconds

  //--- standard RaceActor callbacks

  def handleUCMessage: Receive = {
    case BusEvent(_,newNode: Node,_) => state.handleNode(newNode)
    case BusEvent(_,cdc: ColumnDataChange,_) => state.handleColumnDataChange(cdc)
    case BusEvent(_,crc: ColumnReachabilityChange,_) => state.handleColumnReachabilityChange(crc)
    case BusEvent(_,cc:ConstraintChange,_) => state.handleConstraintChange(cc)

      //--- simulation and debug
    case "cut" => cutConnection
    case "restore" => restoreConnection
  }

  override def handleMessage: Receive = handleUCMessage.orElse( handleWsMessage)

  override def onRaceTick(): Unit = state.onRaceTick()

  //--- WSAdapterActor overrides

  override protected def handleSendFailure(msg: String): Unit = {
    warning(msg)

    if (isConnected) {
      disconnect()  // this will cause a onDisconnect notification, which is delegated to the state
    }
  }

  override def onStartRaceActor(originator: ActorRef): Boolean = {
    if (super.onStartRaceActor(originator)) {
      state.connect() // this is async, we will get a onConnect() once we are connected
      startScheduler  // periodically try to connect or check connection, depending on mode
      true
    } else false
  }

  override def onConnect(): Unit = state.onConnect()
  override def onDisconnect(): Unit = state.onDisconnect()

  override def onSyncResponse (responder: ActorRef, responderType: Class[_], tag: Any): Unit = {
    state.onSyncResponse(responder, responderType, tag)
  }

  override protected def processIncomingMessage (msg: Message): Unit = state.processIncomingMessage(msg)


  //--- sim / debug support

  def cutConnection: Unit = {
    info(s"simulating connection loss")
    cutCon = true
    if (isConnected) disconnect()
  }

  def restoreConnection: Unit = {
    info(s"simulating restored connection")
    cutCon = false
  }

  override def connect (uri: String): Unit = {
    if (cutCon) {
      onConnectFailed(uri, "simulated connection loss")
    } else {
      super.connect(uri)
    }
  }
}
