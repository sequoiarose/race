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

package gov.nasa.race.geo

import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom._

object GeoPosition {
  val zeroPos = new LatLonPos(Angle0, Angle0, Length0)
  val undefinedPos = new LatLonPos(UndefinedAngle, UndefinedAngle, UndefinedLength)

  def apply (φ: Angle, λ: Angle, alt: Length = Length0): GeoPosition = new LatLonPos(φ,λ,alt)

  def fromDegrees (latDeg: Double, lonDeg: Double): GeoPosition = new LatLonPos( Degrees(latDeg), Degrees(lonDeg), Length0)
  def fromDegreesAndMeters (latDeg: Double, lonDeg: Double, altMeters: Double) = {
    new LatLonPos(Degrees(latDeg), Degrees(lonDeg), Meters(altMeters))
  }
  def fromDegreesAndFeet (latDeg: Double, lonDeg: Double, altFeet: Double) = {
    new LatLonPos(Degrees(latDeg), Degrees(lonDeg), Feet(altFeet))
  }
}

/**
  * abstract position
  */
trait GeoPosition {
  def φ: Angle
  @inline def lat = φ  // just an alias

  def λ: Angle
  @inline def lon = λ  // just an alias

  def altitude: Length
  def hasDefinedAltitude: Boolean = altitude.isDefined

  @inline def =:= (other: GeoPosition): Boolean = (φ =:= other.φ) && (λ =:= other.λ) && (altitude =:= other.altitude)

  @inline def isDefined = φ.isDefined && λ.isDefined && altitude.isDefined

  @inline def latDeg = φ.toDegrees
  @inline def lonDeg = λ.toDegrees
  @inline def altMeters: Double = altitude.toMeters

}

/**
  * object that has a position
  */
trait GeoPositioned {
  def position: GeoPosition
}


/**
 * geographic position consisting of latitude and longitude
 *
 * @param φ latitude (positive north)
 * @param λ longitude (positive east)
 */
case class LatLonPos(val φ: Angle, val λ: Angle, val altitude: Length) extends GeoPosition {
  override def toString = {
    f"LatLonPos{φ=${φ.toDegrees}%+3.5f°,λ=${λ.toDegrees}%+3.5f°,alt=${altitude.toMeters}m"
  }
}
