/*
 * Copyright (c) 2019, United States Government, as represented by the
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

import com.typesafe.config.Config
import gov.nasa.race.air.TFMTrack
import gov.nasa.race.common.StringXmlPullParser2
import gov.nasa.race.config.{ConfigurableTranslator, NoConfig}
import gov.nasa.race.common.inlined.Slice
import gov.nasa.race.config.ConfigUtils._
import gov.nasa.race.geo.GeoPosition
import gov.nasa.race.track.TrackedObject
import gov.nasa.race.uom.Angle.UndefinedAngle
import gov.nasa.race.uom.Length.UndefinedLength
import gov.nasa.race.uom.Speed.UndefinedSpeed
import gov.nasa.race.uom.{Angle, DateTime, Length, Speed}
import gov.nasa.race.uom.Angle._
import gov.nasa.race.uom.Length._
import gov.nasa.race.uom.Speed._

import scala.collection.mutable.ArrayBuffer
import scala.Double.NaN

/**
  * a translator for tfmDataService (tfmdata) SWIM messages. We only process fltdMessage/trackInformation yet
  *
  * TODO - check if fltdMessage attributes are mandatory since we don't parse the respective trackInformation sub-elements
  */
class TfmDataServiceParser (val config: Config=NoConfig)
  extends StringXmlPullParser2(config.getIntOrElse("buffer-size",200000)) with ConfigurableTranslator {

  val tfmDataService = Slice("ds:tfmDataService")
  val fltdMessage = Slice("fdm:fltdMessage")
  val trackInformation = Slice("fdm:trackInformation")
  val reportedAltitude = Slice("nxcm:reportedAltitude")
  val airlineData = Slice("nxcm:airlineData")
  val etaType = Slice("etaType")
  val ncsmTrackData = Slice("nxcm:ncsmTrackData")
  val ncsmRouteData = Slice("nxcm:ncsmRouteData")
  val actualValue = Slice("ACTUAL")
  val completedValue = Slice("COMPLETED")
  val westValue = Slice("WEST")
  val southValue = Slice("SOUTH")
  val timeValue = Slice("timeValue")

  override def translate(src: Any): Option[Any] = {
    src match {
      case s: String => parse(s)
      case Some(s: String) => parse(s)
      case _ => None // nothing else supported yet
    }
  }

  protected def parse (msg: String): Option[Any] = {
    if (initialize(msg)) {
      while (parseNextTag) {
        if (isStartTag) {
          if (tag == tfmDataService) return parseTfmDataService
        }
      }
    }
    None
  }

  protected def parseTfmDataService: Option[Any] = {
    val tracks = new ArrayBuffer[TFMTrack](20)

    while (parseNextTag) {
      if (isStartTag) {
        if (tag == fltdMessage) parseFltdMessage(tracks)
      } else { // end tag
        if (tag == tfmDataService) {
          if (tracks.nonEmpty) return Some(tracks) else None
        }
      }
    }
    None
  }

  protected def parseFltdMessage (tracks: ArrayBuffer[TFMTrack]): Unit = {

    var flightRef: String = "?"
    var cs: String = null
    var source: String = "?"
    var date: DateTime = DateTime.UndefinedDateTime
    var arrArpt: String = null
    var depArpt: String = null

    val data = this.data

    def processAttrs: Unit = {
      while (parseNextAttr) {
        val off = attrName.offset
        val len = attrName.length

        @inline def process_acid = cs = attrValue.intern
        @inline def process_arrArpt = arrArpt = attrValue.intern
        @inline def process_depArpt = depArpt = attrValue.intern
        @inline def process_flightRef = flightRef = attrValue.intern
        @inline def process_sourceTimeStamp = date = DateTime.parseYMDT(attrValue)
        @inline def process_sourceFacility = source = attrValue.intern

        @inline def match_a = { len>=1 && data(off)==97 }
        @inline def match_acid = { len==4 && data(off+1)==99 && data(off+2)==105 && data(off+3)==100 }
        @inline def match_arrArpt = { len==7 && data(off+1)==114 && data(off+2)==114 && data(off+3)==65 && data(off+4)==114 && data(off+5)==112 && data(off+6)==116 }
        @inline def match_depArpt = { len==7 && data(off)==100 && data(off+1)==101 && data(off+2)==112 && data(off+3)==65 && data(off+4)==114 && data(off+5)==112 && data(off+6)==116 }
        @inline def match_flightRef = { len==9 && data(off)==102 && data(off+1)==108 && data(off+2)==105 && data(off+3)==103 && data(off+4)==104 && data(off+5)==116 && data(off+6)==82 && data(off+7)==101 && data(off+8)==102 }
        @inline def match_source = { len>=6 && data(off)==115 && data(off+1)==111 && data(off+2)==117 && data(off+3)==114 && data(off+4)==99 && data(off+5)==101 }
        @inline def match_sourceTimeStamp = { len==15 && data(off+6)==84 && data(off+7)==105 && data(off+8)==109 && data(off+9)==101 && data(off+10)==83 && data(off+11)==116 &&data(off+12)==97 && data(off+13)==109 && data(off+14)==112 }
        @inline def match_sourceFacility = { len==14 && data(off+6)==70 && data(off+7)==97 && data(off+8)==99 && data(off+9)==105 && data(off+10)==108 && data(off+11)==105 && data(off+12)==116 && data(off+13)==121 }
        if (match_a) {
          if (match_acid) {
            process_acid
          } else if (match_arrArpt) {
            process_arrArpt
          }
        } else if (match_depArpt) {
          process_depArpt
        } else if (match_flightRef) {
          process_flightRef
        } else if (match_source) {
          if (match_sourceTimeStamp) {
            process_sourceTimeStamp
          } else if (match_sourceFacility) {
            process_sourceFacility
          }
        }
      }
    }

    def parseTrackInformation: Unit = {

      def readDMS: Angle = {
        var deg: Double = NaN
        var min: Double = NaN
        var sec: Double = 0
        var isNegative = false

        while (parseNextAttr) {
          val off = attrName.offset
          val len = attrName.length

          @inline def process_degrees = deg = attrValue.toInt
          @inline def process_direction = isNegative = (attrValue == westValue || attrValue == southValue)
          @inline def process_minutes = min = attrValue.toInt
          @inline def process_seconds = sec = attrValue.toInt

          @inline def match_d = { len>=1 && data(off)==100 }
          @inline def match_degrees = { len==7 && data(off+1)==101 && data(off+2)==103 && data(off+3)==114 && data(off+4)==101 && data(off+5)==101 && data(off+6)==115 }
          @inline def match_direction = { len==9 && data(off+1)==105 && data(off+2)==114 && data(off+3)==101 && data(off+4)==99 && data(off+5)==116 && data(off+6)==105 && data(off+7)==111 && data(off+8)==110 }
          @inline def match_minutes = { len==7 && data(off)==109 && data(off+1)==105 && data(off+2)==110 && data(off+3)==117 && data(off+4)==116 && data(off+5)==101 && data(off+6)==115 }
          @inline def match_seconds = { len==7 && data(off)==115 && data(off+1)==101 && data(off+2)==99 && data(off+3)==111 && data(off+4)==110 && data(off+5)==100 && data(off+6)==115 }
          if (match_d) {
            if (match_degrees) {
              process_degrees
            } else if (match_direction) {
              process_direction
            }
          } else if (match_minutes) {
            process_minutes
          } else if (match_seconds) {
            process_seconds
          }
        }

        val d = deg + min/60.0 + sec/3600.0
        if (isNegative) Degrees(-d) else Degrees(d)
      }

      def readAlt: Length = {
        val v = readSliceContent
        var factor = 100

        val bs = v.data
        var i = v.offset
        val iEnd = v.offset + v.length
        var d: Double = 0
        while (i<iEnd) {
          val b = bs(i)
          if (b >= '0' && b <= '9') d = d * 10 + (b - '0')
          else if (b == 'T') factor = 1000
          i += 1
        }
        Feet(d * factor)
      }

      def readNextWP: GeoPosition = {
        var lat: Double = NaN
        var lon: Double = NaN

        while (parseNextAttr) {
          val off = attrName.offset
          val len = attrName.length

          @inline def process_latitudeDecimal = lat = attrValue.toDouble
          @inline def process_longitudeDecimal = lon = attrValue.toDouble

          @inline def match_l = { len>=1 && data(off)==108 }
          @inline def match_latitudeDecimal = { len==15 && data(off+1)==97 && data(off+2)==116 && data(off+3)==105 && data(off+4)==116 && data(off+5)==117 && data(off+6)==100 && data(off+7)==101 && data(off+8)==68 && data(off+9)==101 && data(off+10)==99 && data(off+11)==105 && data(off+12)==109 && data(off+13)==97 && data(off+14)==108 }
          @inline def match_longitudeDecimal = { len==16 && data(off+1)==111 && data(off+2)==110 && data(off+3)==103 && data(off+4)==105 && data(off+5)==116 && data(off+6)==117 && data(off+7)==100 && data(off+8)==101 && data(off+9)==68 && data(off+10)==101 && data(off+11)==99 && data(off+12)==105 && data(off+13)==109 && data(off+14)==97 && data(off+15)==108 }
          if (match_l) {
            if (match_latitudeDecimal) {
              process_latitudeDecimal
            } else if (match_longitudeDecimal) {
              process_longitudeDecimal
            }
          }
        }

        GeoPosition.fromDegrees(lat,lon)
      }

      def readNextWPDate: DateTime = {
        if (parseAttr(timeValue)) {
          DateTime.parseYMDT(attrValue)
        } else DateTime.UndefinedDateTime
      }

      var lat: Angle = UndefinedAngle
      var lon: Angle = UndefinedAngle
      var speed: Speed = UndefinedSpeed
      var alt: Length = UndefinedLength
      var nextWP: GeoPosition = null
      var nextWPDate: DateTime = DateTime.UndefinedDateTime
      var completed: Boolean = false

      /*
         "nxcm:speed" "nxce:simpleAltitude" "nxce:latitudeDMS" "nxce:longitudeDMS" "nxcm:eta" "nxcm:flightStatus" "nxcm:nextEvent" "nxcm:nextPosition"
       */

      while (parseNextTag){
        val off = tag.offset
        val len = tag.length

        if (isStartTag) {
          // if eta refers to actual airlineData data we use it to set completed values // TODO check this logic
          @inline def process_nxcm$eta: Unit = {
            if (tagHasParent(ncsmTrackData) || tagHasParent(ncsmRouteData)) {
              nextWPDate = readNextWPDate

            } else if (tagHasParent(airlineData)) {
              while (parseNextAttr) {
                if (attrName == etaType){
                  if (!(attrValue == actualValue)) return
                } else if (attrName == timeValue) {
                  if (date.isUndefined) date =  DateTime.parseYMDT(attrValue)
                  if (lat.isUndefined) lat = Angle0
                  if (lon.isUndefined) lon = Angle0
                }
              }
            }
          }
          @inline def process_nxcm$nextEvent = if (tagHasParent(ncsmTrackData)) nextWP = readNextWP
          @inline def process_nxcm$nextPosition = if (tagHasParent(ncsmRouteData)) nextWP = readNextWP
          @inline def process_nxcm$speed = speed = UsMilesPerHour(readDoubleContent)
          @inline def process_nxcm$flightStatus = completed = (readSliceContent == completedValue)
          @inline def process_nxce$simpleAltitude = if (tagHasAncestor(reportedAltitude)) alt = readAlt
          @inline def process_nxce$latitudeDMS = lat = readDMS
          @inline def process_nxce$longitudeDMS = lon = readDMS

          @inline def match_nxc = { len>=3 && data(off)==110 && data(off+1)==120 && data(off+2)==99 }
          @inline def match_nxcm$ = { len>=5 && data(off+3)==109 && data(off+4)==58 }
          @inline def match_nxcm$speed = { len==10 && data(off+5)==115 && data(off+6)==112 && data(off+7)==101 && data(off+8)==101 && data(off+9)==100 }
          @inline def match_nxcm$eta = { len==8 && data(off+5)==101 && data(off+6)==116 && data(off+7)==97 }
          @inline def match_nxcm$flightStatus = { len==17 && data(off+5)==102 && data(off+6)==108 && data(off+7)==105 && data(off+8)==103 && data(off+9)==104 && data(off+10)==116 && data(off+11)==83 && data(off+12)==116 && data(off+13)==97 && data(off+14)==116 && data(off+15)==117 && data(off+16)==115 }
          @inline def match_nxcm$next = { len>=9 && data(off+5)==110 && data(off+6)==101 && data(off+7)==120 && data(off+8)==116 }
          @inline def match_nxcm$nextEvent = { len==14 && data(off+9)==69 && data(off+10)==118 && data(off+11)==101 && data(off+12)==110 && data(off+13)==116 }
          @inline def match_nxcm$nextPosition = { len==17 && data(off+9)==80 && data(off+10)==111 && data(off+11)==115 && data(off+12)==105 && data(off+13)==116 && data(off+14)==105 && data(off+15)==111 && data(off+16)==110 }
          @inline def match_nxce$ = { len>=5 && data(off+3)==101 && data(off+4)==58 }
          @inline def match_nxce$simpleAltitude = { len==19 && data(off+5)==115 && data(off+6)==105 && data(off+7)==109 && data(off+8)==112 && data(off+9)==108 && data(off+10)==101 && data(off+11)==65 && data(off+12)==108 && data(off+13)==116 && data(off+14)==105 && data(off+15)==116 && data(off+16)==117 && data(off+17)==100 && data(off+18)==101 }
          @inline def match_nxce$l = { len>=6 && data(off+5)==108 }
          @inline def match_nxce$latitudeDMS = { len==16 && data(off+6)==97 && data(off+7)==116 && data(off+8)==105 && data(off+9)==116 && data(off+10)==117 && data(off+11)==100 && data(off+12)==101 && data(off+13)==68 && data(off+14)==77 && data(off+15)==83 }
          @inline def match_nxce$longitudeDMS = { len==17 && data(off+6)==111 && data(off+7)==110 && data(off+8)==103 && data(off+9)==105 && data(off+10)==116 && data(off+11)==117 && data(off+12)==100 && data(off+13)==101 && data(off+14)==68 && data(off+15)==77 && data(off+16)==83 }
          if (match_nxc) {
            if (match_nxcm$) {
              if (match_nxcm$speed) {
                process_nxcm$speed
              } else if (match_nxcm$eta) {
                process_nxcm$eta
              } else if (match_nxcm$flightStatus) {
                process_nxcm$flightStatus
              } else if (match_nxcm$next) {
                if (match_nxcm$nextEvent) {
                  process_nxcm$nextEvent
                } else if (match_nxcm$nextPosition) {
                  process_nxcm$nextPosition
                }
              }
            } else if (match_nxce$) {
              if (match_nxce$simpleAltitude) {
                process_nxce$simpleAltitude
              } else if (match_nxce$l) {
                if (match_nxce$latitudeDMS) {
                  process_nxce$latitudeDMS
                } else if (match_nxce$longitudeDMS) {
                  process_nxce$longitudeDMS
                }
              }
            }
          }

        } else { // end tag
          if (tag == trackInformation) {
            //println(s"@@@ trackInformation $cs: $date $lat $lon $alt $nextWP")
            if (cs != null && (completed ||
              (date.isDefined && lat.isDefined && lon.isDefined && alt.isDefined && nextWP != null))) {
              val status = if (completed) TrackedObject.CompletedFlag else TrackedObject.TrackNoStatus
              val track = if (completed) {
                TFMTrack(flightRef,cs,GeoPosition(lat,lon,alt),speed,date,status,
                  source,None,DateTime.UndefinedDateTime)
              } else {
                TFMTrack(flightRef,cs,GeoPosition(lat,lon,alt),speed,date,status,
                  source,Some(nextWP),nextWPDate)
              }
              tracks += track
            } else {
              println(s"rejected $cs $date $lat $lon $alt $nextWP")
            }
            return
          }
        }
      }
    }

    processAttrs
    while (parseNextTag) {
      if (isStartTag) {
        if (tag == trackInformation) parseTrackInformation
      } else { // end tag
        if (tag == fltdMessage) return
      }
    }
  }

}
