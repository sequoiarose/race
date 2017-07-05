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

package gov.nasa.race.air.translator

import java.io.File
import gov.nasa.race.util.FileUtils._
import gov.nasa.race.air.IdentifiableAircraft
import gov.nasa.race.test.RaceSpec
import org.scalatest.FlatSpec

class FIXM2FlightObjectSpec extends FlatSpec with RaceSpec {
  final val EPS = 0.000001
  val xmlMsg = fileContentsAsUTF8String(baseResourceFile("fixm.xml")).get

  val flightRE = "<flight ".r
  val nFlights = flightRE.findAllIn(xmlMsg).size


  behavior of "FIXM3Message2FlightObject translator"

  "translator" should "reproduce known values" in {
    val translator = new FIXM2FlightObject()
    val res = translator.translate(xmlMsg)
    res match {
      case Some(list:Seq[IdentifiableAircraft]) =>
        list.foreach { println }
        assert(list.size == nFlights)
        println(s"all $nFlights FlightObjects accounted for")
      case other => fail(s"failed to parse messages, result=$other")
    }
  }
}
