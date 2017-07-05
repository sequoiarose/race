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

package gov.nasa.race.ww

import gov.nasa.race.air.{FlightPos, InFlightAircraft, AsdexTrack}
import gov.nasa.race.uom.Length._
import gov.nasa.race.geo.{DatedAltitudePositionable, LatLonPos}
import gov.nasa.worldwind.geom.Position

import scala.language.implicitConversions


/**
  * package `gov.nasa.race.ww.air` contains WorldWind specific airspace visualization
  */
package object air {

  implicit def toWWPosition (pos: LatLonPos): Position = Position.fromDegrees(pos.φ.toDegrees, pos.λ.toDegrees)

  implicit def toWWPosition (e: FlightPos): Position = wwPosition(e.position, e.altitude)
  implicit def toWWPosition (e: InFlightAircraft): Position = wwPosition(e.position, e.altitude)
  implicit def toWWPosition (e: DatedAltitudePositionable): Position = wwPosition(e.position, e.altitude)
  implicit def toWWPosition (t: AsdexTrack): Position = wwPosition(t.pos, t.altitude.getOrElse(Length0))
}
