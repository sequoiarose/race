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
package gov.nasa.race.air.actor

import com.typesafe.config.Config
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.core.Messages.BusEvent
import gov.nasa.race.core.{SubscribingRaceActor,PublishingRaceActor}
import gov.nasa.race.geo.Datum
import gov.nasa.race.track._
import gov.nasa.race.uom.{Length,Angle}
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Angle._

import org.joda.time.DateTime
import scala.concurrent.duration._
import scala.collection.mutable.{HashMap => MHashMap, Set =>MSet}



/**
  * actor that detects proximity flights with parallel headings and reports flight combinations which
  * converge exceeding a max heading difference within a given radius, indicating that at least one
  * plane had to roll to an extend that it lost sight of the other
  */
class ParallelApproachAnalyzer (val config: Config) extends SubscribingRaceActor with PublishingRaceActor {

  //--- settings to detect parallel proximity flights
  val maxParallelDist = config.getLengthOrElse("max-par-dist", Meters(2000))
  val maxParallelAngle = config.getAngleOrElse("max-par-angle", Degrees(10))
  val maxParallelDalt = config.getLengthOrElse("max-par-dalt", Meters(50))

  //--- settings to analyze converging trajectories
  val maxConvergeDist = config.getLengthOrElse("max-conv-dist", Meters(6000))  // about 30 sec flight
  val maxConvergeAngle = config.getAngleOrElse("max-conv-angle", Degrees(30))
  val maxConvergeDuration = config.getFiniteDurationOrElse("max-conv-duration", 120.seconds).toMillis
  val convergeInterval = config.getFiniteDurationOrElse("conv-interval", 1.second).toMillis.toInt


  class Candidate {
    var track: TrackedObject = _
    val estimator = getConfigurableOrElse("estimator")(new TrackedObjectExtrapolator)
    val trajectory = new USTrajectoryTrace(config.getIntOrElse("max-path",50))
    val checked: MSet[String] = MSet.empty

    def update (newTrack: TrackedObject): Unit = {
      if (track == null || newTrack.date.getMillis > track.date.getMillis) {
        track = newTrack
        estimator.addObservation(newTrack)
        trajectory.add(newTrack)
      }
    }
  }

  val candidates: MHashMap[String,Candidate] = MHashMap.empty

  override def handleMessage = {
    case BusEvent(_,trackDropped:TrackDropped,_) => candidates.remove(trackDropped.id)
    case BusEvent(_,track:TrackedObject,_) => {
      if (track.isDroppedOrCompleted) {
        candidates.remove(track.id)
      } else {
        updateCandidates(track)
      }
    }
  }

  def updateCandidates(track: TrackedObject): Unit = {
    val ownEntry = candidates.getOrElseUpdate(track.id,new Candidate)
    ownEntry.update(track)

    candidates.foreach { e =>
      if (e._1 != track.id) {
        val otherEntry = e._2

        if (!otherEntry.checked.contains(track.id)) {
          val est = otherEntry.estimator
          est.estimateState(track.date.getMillis)
          val d = Datum.meanEuclidean2dDistance(track.position, est.estimatedPosition)
          if (d < maxParallelDist) {
            if (track.position.altitude - est.altitude < maxParallelDalt) {
              if (absDiff(track.heading, est.heading) < maxParallelAngle) {
                ownEntry.checked += e._1
                otherEntry.checked += track.id
                checkConvergence(ownEntry,otherEntry)
              }
            }
          }
        }
      }
    }
  }

  def checkConvergence (c1: Candidate, c2: Candidate): Unit = {
    val tr1 = c1.trajectory.interpolate
    val tr2 = c2.trajectory.interpolate

    // c1 was last updated, so we start backwards interpolation from last c2 entry

    val it2 = tr2.reverseTailDurationIterator(maxConvergeDuration, convergeInterval)
    val it1 = tr1.reverseIterator(tr2.tRight, tr2.tRight - maxConvergeDuration, convergeInterval)

    if (it1.hasNext && it2.hasNext){
      var p = it1.next
      var lastLat1 = Degrees(p._2)
      var lastLon1 = Degrees(p._3)

      p = it2.next
      var lastLat2 = Degrees(p._2)
      var lastLon2 = Degrees(p._3)

      var t = 0
      var dist = Length0
      var deltaHdg = Angle0
      val dt = convergeInterval/1000
      var stop = false

      while (!stop && it1.hasNext && it2.hasNext) {
        p = it1.next
        val lat1 = Degrees(p._2)
        val lon1 = Degrees(p._3)
        val hdg1 = Datum.euclideanHeading(lat1,lon1, lastLat1,lastLon1)
        lastLat1 = lat1
        lastLon1 = lon1

        p = it2.next
        val lat2 = Degrees(p._2)
        val lon2 = Degrees(p._3)
        val hdg2 = Datum.euclideanHeading(lat2,lon2, lastLat2,lastLon2)
        lastLat2 = lat2
        lastLon2 = lon2

        deltaHdg = Angle.absDiff(hdg1,hdg2)
        dist = Datum.meanEuclidean2dDistance(lat1,lon1, lat2,lon2)

        if (deltaHdg > maxConvergeAngle){ // Bingo - got one
          val date = new DateTime(p._1)
          publishEvent(c1, c2, date, deltaHdg, dist)
          //println(f"@@ $t%4d: ${dist.toMeters}%10.0f, ${hdg1.toDegrees}%3.0f, ${hdg2.toDegrees}%3.0f -> delta= ${deltaHdg.toDegrees}%3.0f")
          info(f"max angle-in exceeded: ${c1.track.cs},${c2.track.cs} at $date: Δhdg = ${deltaHdg.toDegrees}%.0f, dist = ${dist.toMeters}%.0mf")
          stop = true

        } else {
          t += dt
          stop = (dist > maxConvergeDist)
        }
      }
    }
  }

  def publishEvent(c1: Candidate, c2: Candidate, date: DateTime, deltaHdg: Angle, dist: Length): Unit = {

  }
}
