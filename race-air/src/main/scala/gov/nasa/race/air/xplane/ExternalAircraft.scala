/*
 * Copyright (c) 2018, United States Government, as represented by the
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
package gov.nasa.race.air.xplane

import gov.nasa.race.air.{ExtendedFlightPos, TrackedAircraft}
import gov.nasa.race.common.SmoothingVectorExtrapolator
import gov.nasa.race.geo.LatLonPos
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.{Angle, Speed}
import org.joda.time.DateTime

object ExternalAircraft {
  final val invisibleAltitude = Meters(100000.12345) // this is how we make aircraft disappear

  // use values that won't cause exceptions in X-Plane
  val noAircraft = new ExtendedFlightPos("?","?",LatLonPos.fromDegrees(0.1,0.1),
                                         invisibleAltitude,Knots(200),Degrees(90), Speed.Speed0,
                                         new DateTime(0),0,Angle.Angle0,Angle.Angle0)
}
import gov.nasa.race.air.xplane.ExternalAircraft._

/**
  * abstraction for external X-Plane aircraft (aircraft sent to X-Plane)
  */
trait ExternalAircraft {

  val idx: Int
  val acType: String
  val liveryIdx: Int

  var fpos: TrackedAircraft = noAircraft
  def isAssigned: Boolean = fpos ne noAircraft
  var hasChanged = false

  def cs = fpos.cs

  //--- the values we need to update X-Plane
  def latDeg: Double
  def lonDeg: Double
  def altMeters: Double

  def psiDeg: Float
  def thetaDeg: Float
  def phiDeg: Float

  def speedMsec: Double

  // those are not supported by XPlane 11 anymore
  val gear = 0f
  val flaps = 0f
  val throttle = 0f

  //--- interface used by XPlaneActor to update externals

  def updateObservation(newFPos: TrackedAircraft): Unit = {
    fpos = newFPos
    hasChanged = true
  }

  def updateEstimate(simTimeMillis: Long): Unit

  def hide: Unit = {
    fpos = noAircraft
    hasChanged = false
  }
}

/**
  * external X-Plane aircraft that does not extrapolate but only reports last set position/attitude
  */
class NonExtrapolatedAC (val idx: Int, val acType: String, val liveryIdx: Int) extends ExternalAircraft {

  def latDeg = fpos.position.latDeg
  def lonDeg = fpos.position.lonDeg
  def altMeters = fpos.altitude.toMeters
  def psiDeg = fpos.heading.toDegrees.toFloat
  def thetaDeg = fpos.pitch.toDegrees.toFloat
  def phiDeg = fpos.roll.toDegrees.toFloat
  def speedMsec = fpos.speed.toMetersPerSecond

  override def updateEstimate(simTimeMillis: Long) = {}
}

/**
  * external XPlane aircraft that can be extrapolated
  * can be used to up-sample live data with update frequencies which are too slow for X-Plane visualization
  *
  * NOTE - access and modification is NOT synchronized - the caller has to ensure this is threadsafe
  */
class ExtrapolatedAC (val idx: Int, val acType: String, val liveryIdx: Int) extends ExternalAircraft {

  val estimator = new SmoothingVectorExtrapolator(6)
  val state = Array[Double](6)


  def latDeg = state(0)
  def lonDeg = state(1)
  def altMeters = state(2)
  def psiDeg = state(3).toFloat
  def thetaDeg = state(4).toFloat
  def phiDeg = state(5).toFloat

  def speedMsec = fpos.speed.toMetersPerSecond // we don't estimate this yet


  override def updateObservation(newFPos: TrackedAircraft): Unit = {
    fpos = newFPos

    state(0) = newFPos.position.latDeg
    state(1) = newFPos.position.lonDeg
    state(2) = newFPos.altitude.toMeters
    state(3) = newFPos.heading.toDegrees
    state(4) = newFPos.pitch.toDegrees
    state(5) = newFPos.roll.toDegrees

    estimator.addObservation(state,newFPos.date.getMillis)
    hasChanged = true
  }

  override def updateEstimate(simTimeMillis: Long) = {
    estimator.extrapolate(simTimeMillis,state)
  }

  override def hide: Unit = {
    super.hide
    estimator.reset
  }
}
