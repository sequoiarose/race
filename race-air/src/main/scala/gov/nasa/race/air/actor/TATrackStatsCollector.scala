/*
 * Copyright (c) 2017, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
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
package gov.nasa.race.air.actor

import java.io.PrintWriter

import com.typesafe.config.Config
import gov.nasa.race._
import gov.nasa.race.actor.{OptionalLogger, StatsCollectorActor}
import gov.nasa.race.air.TATrack
import gov.nasa.race.common.TSStatsData.{Ambiguous, Duplicate, Extension, Sameness}
import gov.nasa.race.common._
import gov.nasa.race.core.ClockAdjuster
import gov.nasa.race.core.Messages.{BusEvent, RaceTick}
import gov.nasa.race.http.{HtmlArtifacts, HtmlStats, HtmlStatsFormatter}

import scala.collection.mutable.{HashMap => MHashMap}
import scalatags.Text.all._

/**
  * actor that collects statistics for TATrack objects
  * We keep stats per tracon, hence this is not directly a TSStatsCollectorActor
  */
class TATrackStatsCollector (val config: Config) extends StatsCollectorActor with ClockAdjuster with OptionalLogger {

  class TACollector (val config: Config, val src: String)
         extends ConfiguredTSStatsCollector[Int,TATrack,TATrackEntryData,TATrackStatsData] {
    val statsData = new TATrackStatsData(src)
    statsData.buckets = createBuckets

    if (hasLogChannel){
      //statsData.duplicateAction = Some(logDuplicate)
      statsData.ambiguousAction = Some(logAmbiguous)
      statsData.outOfOrderAction = Some(logOutOfOrder)
    }

    def createTSEntryData (t: Long, track: TATrack) = new TATrackEntryData(t,track)
    def currentSimTimeMillisSinceStart = TATrackStatsCollector.this.currentSimTimeMillisSinceStart
    def currentSimTimeMillis = TATrackStatsCollector.this.updatedSimTimeMillis

    override def dataSnapshot: TATrackStatsData = {
      processEntryData
      super.dataSnapshot
    }
  }

  val tracons = MHashMap.empty[String, TACollector]

  override def handleMessage = {
    case BusEvent(_, track: TATrack, _) =>
      try {
        if (track.date != null) {
          checkClockReset(track.date)
          val tracon = tracons.getOrElseUpdate(track.src, new TACollector(config, track.src))
          if (track.isDrop) {
            tracon.removeActive(track.trackNum)
          } else {
            tracon.updateActive(track.trackNum, track)
          }
        }
      } catch {
        case t: Throwable => t.printStackTrace
      }

    case RaceTick =>
      tracons.foreach { e => e._2.checkDropped }
      publish(snapshot)
  }

  def snapshot: Stats = {
    val traconStats = tracons.toSeq.sortBy(_._1).map( e=> e._2.dataSnapshot)
    new TATrackStats(title, channels, updatedSimTimeMillis, elapsedSimTimeMillisSinceStart, traconStats)
  }

  //--- problem logging (only called if there is a log channel)

  def appendTrack (n: Int, t: TATrack, sb: StringBuilder) = {
    sb.append("track " ); sb.append(n); sb.append(" ["); sb.append(objRef(t)); sb.append("]: ");
    sb.append(t); sb.append('\n')
    ifSome(t.getFirstAmendmentOfType[Src[String]]) { s =>
      sb.append( "source "); sb.append(n); sb.append(" ["); sb.append(objRef(s.src)); sb.append("]: ");
      sb.append(s.src); sb.append('\n')
    }
  }

  def logTracks (t1: TATrack, t2: TATrack, problem: String): Unit = {
    val sb = new StringBuilder
    sb.append("================ "); sb.append(problem); sb.append('\n')

    appendTrack(1, t1, sb)
    sb.append("----------------\n")
    appendTrack(2, t2, sb)

    publishToLogChannel(sb.toString)
  }

  def logDuplicate (t1: TATrack, t2: TATrack): Unit = logTracks(t1,t2,"duplicate")
  def logAmbiguous (t1: TATrack, t2: TATrack, reason: Option[String]): Unit = {
    val problem = reason match {
      case Some(r) => s"ambiguous ($r)"
      case None => "ambiguous"
    }
    logTracks(t1,t2,problem)
  }
  def logOutOfOrder (t1: TATrack, t2: TATrack): Unit = logTracks(t1,t2,"out of order")
}

class TATrackEntryData (tLast: Long, track: TATrack) extends TSEntryData[TATrack](tLast,track) {
  var nFlightPlan = if (track.hasFlightPlan) 1 else 0 // messages with flight plan

  override def update (obj: TATrack, isSettled: Boolean) = {
    super.update(obj,isSettled)
    if (obj.hasFlightPlan) nFlightPlan += 1
  }
  // add consistency status
}

class TATrackStatsData  (val src: String) extends TSStatsData[TATrack,TATrackEntryData] {
  var nNoTime = 0 // number of track positions without time stamps
  var nFlightPlans = 0 // number of active entries with flight plan
  var stddsV2 = 0
  var stddsV3 = 0

  def updateTATrackStats (obj: TATrack) = {
    obj.stddsRev match {
      case 2 => stddsV2 += 1
      case 3 => stddsV3 += 1
    }
    if (obj.date == null) nNoTime += 1
  }

  override def update (obj: TATrack, e: TATrackEntryData, isSettled: Boolean): Unit = {
    super.update(obj,e,isSettled) // standard TSStatsData collection
    updateTATrackStats(obj)
  }

  override def add (obj: TATrack, isStale: Boolean, isSettled: Boolean) = {
    super.add(obj,isStale,isSettled)
    updateTATrackStats(obj)
  }

  override def rateSameness (t1: TATrack, t2: TATrack): Sameness = {
    // we don't need to compare src and trackNum since those are used to look up the respective entries
    if (t1.xyPos != t2.xyPos) Ambiguous(Some("xyPos"))
    else if (t1.altitude != t2.altitude) Ambiguous(Some("altitude"))
    else if (t1.speed != t2.speed) Ambiguous(Some("speed"))
    else if (t1.heading != t2.heading) Ambiguous(Some("heading"))
    else if (t1.vVert != t2.vVert) Ambiguous(Some("vVert"))
    else if (t1.beaconCode != t2.beaconCode) Ambiguous(Some("beaconCode"))
    else if (t1.hasFlightPlan != t2.hasFlightPlan) Extension  // we treat flight plans as accumulating
    else Duplicate
  }


  override def resetEntryData = {
    nFlightPlans = 0
  }

  // called on all active entries before the Stats snapshot is created
  override def processEntryData (e: TATrackEntryData) = {
    if (e.nFlightPlan > 0) nFlightPlans += 1
  }

  def stddsRev = {
    if (stddsV2 > 0){
      if (stddsV3 > 0) "2/3" else "2"
    } else {
      if (stddsV3 > 0) "3" else "?"
    }
  }

  override def toXML = <center src={src}>{xmlBasicTSStatsData ++ xmlBasicTSStatsProblems ++ xmlSamples}</center>
}

class TATrackStats(val topic: String, val source: String, val takeMillis: Long, val elapsedMillis: Long,
                   val traconStats: Seq[TATrackStatsData]) extends PrintStats {

  val nTracons = traconStats.size
  var nActive = 0
  var nCompleted = 0
  var nFlightPlans = 0
  var nStale = 0
  var nDropped = 0
  var nOutOfOrder = 0
  var nDuplicates = 0
  var nAmbiguous = 0
  var stddsV2 = 0
  var stddsV3 = 0
  var nNoTime = 0

  traconStats.foreach { ts =>
    nActive += ts.nActive
    nFlightPlans += ts.nFlightPlans
    nCompleted += ts.completed

    nStale += ts.stale
    nDropped += ts.dropped
    nOutOfOrder += ts.outOfOrder
    nDuplicates += ts.duplicate
    nAmbiguous += ts.ambiguous
    if (ts.stddsV2 > 0) stddsV2 += 1
    if (ts.stddsV3 > 0) stddsV3 += 1
    nNoTime += ts.nNoTime
  }

  // the default is to print only stats for all centers
  override def printWith (pw: PrintWriter): Unit = {
    pw.println("tracons  v2  v3    tracks   fplan   cmplt   dropped   order     dup   ambig   no-time")
    pw.println("------- --- ---   ------- ------- -------   ------- ------- ------- -------   -------")
    pw.print(f"$nTracons%7d $stddsV2%3d $stddsV3%3d   $nActive%7d $nFlightPlans%7d $nCompleted%7d")
    pw.print(f"   $nDropped%7d $nOutOfOrder%7d $nDuplicates%7d $nAmbiguous%7d   $nNoTime%7d")
  }

  override def xmlData = <taTrackStats>{traconStats.map( _.toXML)}</taTrackStats>
}

class TATrackStatsFormatter (conf: Config) extends PrintStatsFormatter {

  def printWith (pw: PrintWriter, stats: Stats) = {
    stats match {
      case s: TATrackStats => printTATrackStats(pw,s)
      case other => false
    }
  }

  def printTATrackStats (pw: PrintWriter, s: TATrackStats): Boolean = {
    import gov.nasa.race.util.DateTimeUtils.{durationMillisToCompactTime => dur}
    import s._

    //--- totals
    s.printWith(pw)
    pw.print("\n\n")

    //--- per tracon data
    pw.println(" tracon     rev    tracks   fplan   cmplt   dropped   order     dup   ambig   no-time         n dtMin dtMax dtAvg")
    pw.println("------- -------   ------- ------- -------   ------- ------- ------- -------   -------   ------- ----- ----- -----")
    traconStats.foreach { ts =>
      pw.print(f"${ts.src}%7s ${ts.stddsRev}%7s   ${ts.nActive}%7d ${ts.nFlightPlans}%7d ${ts.completed}%7d   ")
      pw.print(f"${ts.dropped}%7d ${ts.outOfOrder}%7d ${ts.duplicate}%7d ${ts.ambiguous}%7d   ${ts.nNoTime}%7d")
      ifSome(ts.buckets) { bc =>
        pw.print(f"   ${bc.nSamples}%7d ${dur(bc.min)}%5s ${dur(bc.max)}%5s ${dur(bc.mean)}%5s")
      }
      pw.println
    }

    true
  }
}

class HtmlTATrackStatsFormatter (config: Config) extends HtmlStatsFormatter {

  override def toHtml(stats: Stats): Option[HtmlArtifacts] = {
    stats match {
      case stats: TATrackStats => Some(HtmlArtifacts(statsToHtml(stats), HtmlStats.noResources))
      case _ => None
    }
  }

  def statsToHtml (s: TATrackStats) = {
    import gov.nasa.race.util.DateTimeUtils.{durationMillisToCompactTime => dur}
    import s._

    div(
      HtmlStats.htmlTopicHeader(topic,source,elapsedMillis),
      table(cls:="noBorder")(
        tr(cls:="border")(
          th("src"),th("v2"),th("v3"),th("tracks"),th("fPlan"),th("min"),th("max"),th("cmplt"),th(""),
          th("n"),th("Δt min"),th("Δt max"),th("Δt avg"),th(""),
          th("stale"),th("drop"),th("order"),th("dup"),th("amb"),th("no-t")
        ),

        //--- sum row
        tr(cls:="border")(
          td(nTracons),td(stddsV2),td(stddsV3),td(nActive),td(nFlightPlans),td(""),td(""),td(""),td(""),
          td(""),td(""),td(""),td(""),td(""),
          td(nStale),td(nDropped),td(nOutOfOrder),td(nDuplicates),td(nAmbiguous),td(nNoTime)
        ),

        //--- tracon rows
        for (t <- traconStats) yield tr(cls:="value top")(
          td(t.src),td(t.stddsV2),td(t.stddsV3),td(t.nActive),td(t.nFlightPlans),td(t.minActive),td(t.maxActive),td(t.completed),td(""),
          t.buckets match {
            case Some(bc) if bc.nSamples > 0 => Seq( td(bc.nSamples),td(dur(bc.min)),td(dur(bc.max)),td(dur(bc.mean)))
            case _ => Seq( td("-"),td("-"),td("-"),td("-"))
          },td(""),
          td(t.stale),td(t.dropped),td(t.outOfOrder),td(t.duplicate),td(t.ambiguous),td(t.nNoTime)
        )
      )
    )
  }
}