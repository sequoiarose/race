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

import gov.nasa.race.util.FileUtils

/**
  * manual TFMTrackInfoParser test
  */
object TFMTrackInfoParserTest {
  def main (args: Array[String]) = {
    val parser = new TfmTrackInfoParser

    FileUtils.fileContentsAsUTF8String(args(0)) match {
      case Some(xmlMsg) =>
        val res = parser.parse(xmlMsg)
        res match {
          case Some(list) => list.foreach(println)
          case None => println("no TrackInfos parsed")
        }
      case None => println(s"file not found: ${args(0)}")
    }
  }
}