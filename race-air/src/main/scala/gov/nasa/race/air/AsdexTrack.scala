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

import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.track.TrackedObject
import gov.nasa.race.uom._
import gov.nasa.race.util.DateTimeUtils._
import org.joda.time.DateTime

/**
  * track report for airports (e.g. generated from SWIM asdexMsg messages)
  */
class AsdexTracks(val airport: String, val tracks: Seq[AsdexTrack]) {
  override def toString = {
    val d = if (!tracks.isEmpty) hhmmssZ.print(tracks.head.date) else "?"
    s"AsdexTracks{$airport,date=$d,nTracks=${tracks.size}}"
  }
}

object AsdexTrack {
  // AsdexTrack specific status flags (>0xffff)
  final val DisplayFlag: Int  =  0x10000
  final val OnGroundFlag: Int =  0x20000
  final val VehicleFlag: Int  =  0x40000
  final val AircraftFlag: Int =  0x80000
  final val UpFlag: Int       = 0x100000
  final val DownFlag: Int     = 0x200000

  final val noVehicleFlags = AircraftFlag | UpFlag | DownFlag
}

case class AsdexTrack(id: String,
                      cs: String,
                      position: GeoPosition,
                      altitude: Length,
                      speed: Speed,
                      heading: Angle,
                      vr: Speed,
                      date: DateTime,
                      status: Int,

                      acType: Option[String]
                     ) extends TrackedObject {
  import AsdexTrack._

  def isAircraft = (status & AircraftFlag) != 0
  def isGroundAircraft = ((status & OnGroundFlag) != 0) && !altitude.isDefined
  def isAirborne = altitude.isDefined
  def isMovingGroundAircraft = isGroundAircraft && heading.isDefined
  def isAirborneAircraft = isAircraft && altitude.isDefined
  def isVehicle = (status & VehicleFlag) != 0
  def isUp = (status & UpFlag) != 0
  def isDown = (status & DownFlag) != 0

  // some airports are not setting flags appropriately
  def guessAircraft: Boolean = (status & noVehicleFlags) != 0 || vr.isDefined || acType.isDefined

  override def toShortString = s"Track{$id,0x${status.toHexString},$position,$date}"
}
