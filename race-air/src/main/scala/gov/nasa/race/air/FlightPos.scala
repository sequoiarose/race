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

package gov.nasa.race.air

import java.io.{InputStream, OutputStream, PrintStream}

import com.github.nscala_time.time.Imports._
import com.typesafe.config.Config
import gov.nasa.race.archive._
import gov.nasa.race.common.ConfigurableStreamCreator._
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom._
import gov.nasa.race.util.InputStreamLineTokenizer


object FlightPos {
  def tempCS (flightId: String) = "?" + flightId
  def isTempCS (cs: String) = cs.charAt(0) == '?'

  // c/s changes only happen rarely, and if they do we want to preserve the changed value
  // for all downstream actors so we don't use a fixed field for it
  case class ChangedCS(oldCS: String)

  // since FlightPos is not a case class anymore we provide a unapply method for convenience
  // NOTE - don't select on floating point values (position, speed etc.) or date (which is a millisecond epoch)
  def unapply (o: FlightPos): Option[(String,String,GeoPosition,Speed,Angle,Speed,DateTime,Int)] = {
    Some((o.id,o.cs,o.position,o.speed,o.heading,o.vr,o.date,o.status))
  }
}

/**
  * in-flight state consisting of geographic position, altitude, speed and bearing
  * note that we intentionally don't use a case class here so that we can provide structural extensibility
  */
class FlightPos (val id: String,
                 val cs: String,
                 val position: GeoPosition,
                 val speed: Speed,
                 val heading: Angle,
                 val vr: Speed,
                 val date: DateTime,
                 val status: Int = 0
                ) extends TrackedAircraft {

  def this (id:String, pos: GeoPosition, spd: Speed, hdg: Angle, vr: Speed, dtg: DateTime) =
    this(id, FlightPos.tempCS(id), pos,spd,hdg,vr,dtg)

  def hasTempCS = FlightPos.isTempCS(cs)
  def tempCS = if (hasTempCS) cs else FlightPos.tempCS(id)
  def getOldCS: Option[String] = amendments.find(_.isInstanceOf[FlightPos.ChangedCS]).map(_.asInstanceOf[FlightPos.ChangedCS].oldCS)

  def copyWithCS (newCS: String) = new FlightPos(id, newCS, position,speed,heading,vr,date,status)
  def copyWithStatus (newStatus: Int) = new FlightPos(id, cs, position,speed,heading,vr,date,newStatus)

  override def toString = s"FlightPos($id,$cs,$position,${speed.toKnots.toInt}kn,${heading.toNormalizedDegrees.toInt}°,0x${status.toHexString},$date)"

}

/**
  * a FlightPos specialization that adds more dynamic vehicle state
  */
class ExtendedFlightPos(id: String,
                        cs: String,
                        position: GeoPosition,
                        speed: Speed,
                        heading: Angle,
                        vr: Speed,
                        date: DateTime,
                        status: Int = 0,
                        //--- additional fields
                        override val pitch: Angle,
                        override val roll: Angle,
                        override val acType: String
                        //.. and possibly more to follow
                       ) extends FlightPos(id, cs, position, speed, heading, vr, date, status) {
  override def toString = s"ExtendedFlightPos($id,$cs,$acType,$position,${speed.toKnots.toInt}kn,${heading.toNormalizedDegrees.toInt}°,${pitch.toDegrees.toInt}°,${roll.toDegrees.toInt}°,0x${status.toHexString},$date)"
  override def copyWithCS (newCS: String) = new ExtendedFlightPos(id, newCS, position,speed,heading,vr,date,status,pitch,roll,acType)
  override def copyWithStatus (newStatus: Int) = new ExtendedFlightPos(id, cs, position,speed,heading,vr,date,newStatus,pitch,roll,acType)
}


//-------------------------------------- supporting codecs
/**
  * a FlightPos archiver that writes/parses FlightPos objects to/from text lines
  */
class FlightPosArchiveWriter (val oStream: OutputStream, val pathName: String="<unknown>") extends ArchiveWriter {
  def this(conf: Config) = this(createOutputStream(conf), configuredPathName(conf))

  val ps = new PrintStream (oStream)
  override def close = ps.close

  protected def writeFlightPos(fpos: FlightPos): Unit = {
    ps.print(fpos.id); ps.print(',')
    ps.print(fpos.cs); ps.print(',')

    val pos = fpos.position
    ps.print(pos.φ.toDegrees); ps.print(',')
    ps.print(pos.λ.toDegrees); ps.print(',')
    ps.print(pos.altitude.toFeet); ps.print(',')

    ps.print(fpos.speed.toUsMilesPerHour); ps.print(',')
    ps.print(fpos.heading.toDegrees); ps.print(',')
    ps.print(fpos.vr.toFeetPerMinute); ps.print(',')
    ps.print(fpos.date.getMillis);  ps.print(',')
    ps.print(fpos.status)
  }

  override def write(date: DateTime, obj: Any): Boolean = {
    obj match {
      case fpos: FlightPos =>
        ps.print(date.getMillis)
        ps.print(',')
        writeFlightPos(fpos)
        ps.println()
        true
      case _ => false
    }
  }
}

class ExtendedFlightPosArchiveWriter (oStream: OutputStream, pathName: String) extends FlightPosArchiveWriter(oStream,pathName) {
  def this(conf: Config) = this(createOutputStream(conf), configuredPathName(conf))

  protected def writeExtendedFlightPos (xfpos: ExtendedFlightPos): Unit = {
    writeFlightPos(xfpos); ps.print(',')

    ps.print(xfpos.pitch.toDegrees); ps.print(',')
    ps.print(xfpos.roll.toDegrees); ps.print(',')
    ps.print(xfpos.acType)
  }

  override def write(date: DateTime, obj: Any): Boolean = {
    obj match {
      case xfpos: ExtendedFlightPos =>
        ps.print(date.getMillis); ps.print(',')
        writeExtendedFlightPos(xfpos); ps.println()
        true
      case _ => false
    }
  }
}

class FlightPosArchiveReader (val iStream: InputStream, val pathName: String="<unknown>")
                                                 extends ArchiveReader with InputStreamLineTokenizer {
  def this(conf: Config) = this(createInputStream(conf), configuredPathName(conf))

  def hasMoreData = iStream.available > 0
  def close = iStream.close

  override def readNextEntry: Option[ArchiveEntry] = {
    var fs = getLineFields(iStream)

    if (fs.size == 10) {
      try {
        val recDt = fs.head.toLong; fs = fs.tail
        val flightId = fs.head.intern; fs = fs.tail
        val cs = fs.head.intern; fs = fs.tail
        val phi = fs.head.toDouble; fs = fs.tail
        val lambda = fs.head.toDouble; fs = fs.tail
        val alt = fs.head.toDouble; fs = fs.tail
        val speed = fs.head.toDouble; fs = fs.tail
        val heading = fs.head.toDouble; fs = fs.tail
        val vr = fs.head.toDouble; fs = fs.tail

        val date = getDate(fs.head.toLong); fs = fs.tail  // we might adjust it on-the-fly
        val status = fs.head.toInt

        someEntry(date, new FlightPos(flightId, cs, GeoPosition(Degrees(phi), Degrees(lambda), Feet(alt)),
                                      UsMilesPerHour(speed), Degrees(heading), FeetPerMinute(vr), date,status))
      } catch {
        case x: Throwable => None
      }
    } else None
  }
}

class ExtendedFlightPosArchiveReader (iStream: InputStream, pathName: String) extends FlightPosArchiveReader(iStream,pathName) {
  def this(conf: Config) = this(createInputStream(conf), configuredPathName(conf))

  override def readNextEntry: Option[ArchiveEntry] = {
    var fs = getLineFields(iStream)

    if (fs.size == 13) {
      try {
        // bad - duplicated code, but avoiding temp objects is more important
        val recDt = fs.head.toLong; fs = fs.tail
        val flightId = fs.head.intern; fs = fs.tail
        val cs = fs.head.intern; fs = fs.tail
        val phi = fs.head.toDouble; fs = fs.tail
        val lambda = fs.head.toDouble; fs = fs.tail
        val alt = fs.head.toDouble; fs = fs.tail
        val speed = fs.head.toDouble; fs = fs.tail
        val heading = fs.head.toDouble; fs = fs.tail
        val vr = fs.head.toDouble; fs = fs.tail
        val date = getDate(fs.head.toLong); fs = fs.tail  // we might adjust it on-the-fly
        val status = fs.head.toInt

        val pitch = fs.head.toDouble; fs = fs.tail
        val roll = fs.head.toDouble; fs = fs.tail
        val acType = fs.head.intern

        someEntry(date, new ExtendedFlightPos(flightId, cs, GeoPosition(Degrees(phi), Degrees(lambda), Feet(alt)),
                                              UsMilesPerHour(speed), Degrees(heading), FeetPerMinute(vr), date,status,
                                              Degrees(pitch), Degrees(roll), acType))
      } catch {
        case x: Throwable => None
      }
    } else None
  }
}

case class FlightPosProblem(fpos: FlightPos, lastFpos: FlightPos, problem: String)

trait FlightPosChecker {
  // overide the ones you need
  def check (fpos: FlightPos): Option[FlightPosProblem] = None
  def checkPair (fpos: FlightPos, lastFPos: FlightPos): Option[FlightPosProblem] = None
}

object EmptyFlightPosChecker extends FlightPosChecker