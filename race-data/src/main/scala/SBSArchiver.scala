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

package gov.nasa.race.data

import java.io.{InputStreamReader, BufferedReader, InputStream, OutputStream}

import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import scala.annotation.tailrec

/**
  * support for archive/replay of SBS messages, which are text lines that have their
  * own time field, i.e. don't have a message time prefix. Examples:
  *
  *   MSG,3,111,11111,780A15,111111,2016/03/18,13:02:41.121,2016/03/18,13:02:41.057,,37000,,,37.86242,-124.85699,,,,,,0
  *   MSG,4,111,11111,AB7E23,111111,2016/03/18,13:02:41.127,2016/03/18,13:02:41.122,,,110,0,,,832,,,,,0
  *   MSG,1,111,11111,A9C5C5,111111,2016/03/18,13:02:44.458,2016/03/18,13:02:44.400,JBU789  ,,,,,,,,,,,0
  *
  * the first dtg is date-generated, the second dtg is date-logged
  * we use the first as message time since it is the later one
  *
  * msg format see http://woodair.net/SBS/Article/Barebones42_Socket_Data.htm
  */

object SBSArchiver {
  val dtgPattern = DateTimeFormat.forPattern("yyyy/MM/dd,HH:mm:ss.SSS")
  final val dtgPatternLength = 23
}

class SBSArchiverWriter (ostream: OutputStream) extends TimedTextLineArchiver(ostream)

class SBSArchiveReader (val istream: InputStream)  extends TextLineArchiveReader(istream) {
  import SBSArchiver._

  def readDate (line: String) = {
    @tailrec def _skipToField (s: String, n: Int, sep: Char, i: Int): Int = {
      if (i < s.length) {
        if (s.charAt(i) == sep) {
          if (n == 1) return if (i < s.length-1) i + 1 else -1
          else _skipToField(s, n - 1, sep, i + 1)
        } else {
          _skipToField(s, n, sep, i + 1)
        }
      } else -1
    }

    val i0 = _skipToField(line,6,',',0)
    if (i0 > 0) DateTime.parse(line.substring(i0,i0+dtgPatternLength-1),dtgPattern) else null
  }

  override def read: Option[ArchiveEntry] = {
    try {
      val line = reader.readLine()
      if (line != null) {
        val date = readDate(line)
        if (date != null) Some(ArchiveEntry(date,line)) else None
      } else None
    } catch {
      case _:Throwable => None
    }
  }
}
