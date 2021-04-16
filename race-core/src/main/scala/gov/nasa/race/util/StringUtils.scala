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

package gov.nasa.race.util

import java.nio.ByteBuffer

import gov.nasa.race._

import scala.annotation.tailrec
import scala.collection.Seq
import scala.collection.immutable.ArraySeq
import scala.util.matching.Regex

/**
  * common String related functions
  */
object StringUtils {

  def stringTail(s: String, c: Char) = s.substring(Math.max(s.indexOf(c),0))

  def upToLast (s: String, c: Char) = s.substring(0,s.lastIndexOf(c))

  def trimmedSplit(s: String): Seq[String] = ArraySeq.unsafeWrapArray(s.trim.split("[ ,;]+"))

  final val hexChar = Array('0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f')

  // not worth using javax.xml.bind.DatatypeConverter et al.
  def toHexString (bs: Array[Byte]): String = {
    val len = bs.length
    val cs = new Array[Char](bs.length * 2)
    @tailrec def _byteToHex(i: Int): Unit = {
      if (i < len) {
        val b = bs(i)
        val j = i * 2
        cs(j) = hexChar((b >> 4) & 0xf)
        cs(j + 1) = hexChar(b & 0xf)
        _byteToHex(i + 1)
      }
    }
    _byteToHex(0)
    new String(cs)
  }


  private def pad (s: String, len: Int)(pf: => String): String = {
    if (s.length > len) s.substring(0, len - 1) + "…"
    else if (s.length == len) s
    else pf
  }

  def padLeft (s: String, len: Int, fill: Char): String = pad(s, len){
    val sb = new StringBuilder(len)
    repeat(len - s.length) { sb.append(fill) }
    sb.append(s)
    sb.toString
  }

  def padRight(s: String, len: Int, fill: Char): String = pad(s,len) {
    val sb = new StringBuilder(len)
    sb.append(s)
    repeat(len - s.length) { sb.append(fill) }
    sb.toString
  }

  def capLength(s: String)(implicit maxStringLength: Int = 30): String = {
    if (s.length < maxStringLength) s else s.substring(0, maxStringLength-1) + "…"
  }

  def mkString[E] (it: Iterable[E], prefix: String, sep: String, postfix: String)(f: E=>String = (e:E)=>{e.toString}): String = {
    val sb = new StringBuilder(prefix)
    var i = 0
    it.foreach { e =>
      if (i>0) sb.append(sep)
      i += 1
      sb.append(f(e))
    }
    sb.append(postfix)
    sb.toString
  }

  def append(s0: String, sep: String, s: String): String = {
    if (s0 == null || s0.isEmpty) s
    else s0 + sep + s
  }

  val IntRE = """(\d+)""".r
  val QuotedRE = """"(.*)"""".r

  val digitRE = """\d""".r
  val latinLetterRE = """[a-zA-Z]""".r
  def containsDigit(s: String) = digitRE.findFirstIn(s).isDefined
  def containsLatinLetter(s: String) = latinLetterRE.findFirstIn(s).isDefined

  def globToRegex (glob: String) = {
    // TODO - should also cover '?'
    new Regex("^" + glob.replace("*", ".*") + '$').anchored
  }

  def startsWith(cs: Array[Char], i0: Int, s: String): Boolean = {
    var i = 0
    var j = i0

    while (i < s.length && j < cs.length && cs(j) == s(i)){
      i += 1; j += 1
    }
    i == s.length
  }

  def matchesAll (cs: CharSequence, patterns: Seq[Regex]): Boolean = {
    patterns.isEmpty || !patterns.exists( _.findFirstIn(cs).isEmpty)
  }
  def matchesAny (cs: CharSequence, patterns: Seq[Regex]): Boolean = {
    patterns.nonEmpty && patterns.exists( _.findFirstIn(cs).nonEmpty)
  }

  def matches (cs: CharSequence, pattern: Regex): Boolean = pattern.findFirstIn(cs).isDefined
  def matchesIgnoreCase (cs: CharSequence, regex: String): Boolean = new Regex("(?i)" + regex).findFirstIn(cs).isDefined

  def caseInsensitiveRegex(pattern: Regex): Regex = new Regex("(?i)" + pattern.regex)
  def caseInsensitiveRegex(regex: String): Regex = {
    if (!regex.startsWith("(?i)")) new Regex("(?i)"+regex) else new Regex(regex)
  }

  val xmlMsgNameRE = """<([^\s?!][\w:-]*?)[\s>]""".r // NOTE - REs cannot skip over comments
  def getXmlMsgName (msg: String): Option[String] = xmlMsgNameRE.findFirstMatchIn(msg).map(_.group(1))

  //--- C-like String handling (0-terminated if byte buffer is larger than string requires)

  def cStringLength (bs: Array[Byte]): Int = {
    var i=0
    while (i < bs.length) {
      if (bs(i) == 0) return i+1
      i += 1
    }
    bs.length
  }

  def getCString (bs: Array[Byte]) = new String(bs, 0, cStringLength(bs))

  def getCString (buffer: ByteBuffer, pos: Int, length: Int) = {
    val bs = new Array[Byte](length)
    var i=0
    var j= pos
    while (i<length){
      bs(i) = buffer.get(j)
      i += 1
      j += 1
    }
    new String(bs,0,cStringLength(bs))
  }

  def putCString (s: String, bs: Array[Byte]): Unit = {
    val b = s.getBytes
    val len = Math.min(s.length, b.length)
    System.arraycopy(b,0,bs,0,len)
    if (len < bs.length) bs(len) = 0
  }

  def putCString (s: String, buffer: ByteBuffer, pos: Int, length: Int): Unit = {
    val bs = s.getBytes
    val len = Math.min(length,bs.length)
    buffer.position(pos)
    buffer.put(bs,0,len)
    if (len < length) buffer.put(pos + len, 0.toByte) // Scala 2.13.1 / JDK 13.0.1 problem resolving ByteBuffer methods
  }

  def lastPathElement (s: String): String = {
    val i = s.lastIndexOf('/')
    if (i < 0) s else s.substring(i+1)
  }

  def splitToSeq (s: String, delim: String): Seq[String] = {
    ArraySeq.unsafeWrapArray(s.split(delim))
  }

  def prefixLength (s1: String, s2: String): Int = {
    val m = Math.min(s1.length, s2.length)
    var i = 0
    while (i < m) {
      if (s1.charAt(i) != s2.charAt(i)) return i
      i += 1
    }
    i
  }

  def mkQuotedCharString (a: Array[Byte], maxLen: Int): String = {
    val sb = new StringBuffer
    val n = Math.min(a.length, maxLen)
    for (i <- 0 until n){
      if (i > 0) sb.append(',')
      sb.append('\'')
      sb.append(a(i).toChar)
      sb.append('\'')
    }
    sb.toString
  }

  def mkByteString (a: Array[Byte], maxLen: Int): String = {
    val sb = new StringBuffer
    val n = Math.min(a.length, maxLen)
    for (i <- 0 until n){
      if (i > 0) sb.append(',')
      sb.append(a(i))
    }
    sb.toString
  }

  def mkHexByteString (a: Array[Byte], maxLen: Int): String = {
    val sb = new StringBuffer
    val n = Math.min(a.length, maxLen)
    for (i <- 0 until n){
      if (i > 0) sb.append(',')
      val b = a(i) & 0xff
      sb.append(hexChars(b>>4))
      sb.append(hexChars(b & 0xf))
    }
    sb.toString
  }

  val hexChars = Array ('0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f')

  def countOccurrences (s: String, c: Char): Int = {
    val len = s.length
    var i = 0
    var n = 0
    while (i < len) {
      if (s.charAt(i) == c) n += 1
      i += 1
    }
    n
  }

  def maxSuffix (s: CharSequence, n: Int): String = {
    val len = s.length()
    if (len <= n) s.toString else s"…${s.subSequence(len-n,len)}"
  }

  def maxPrefix (s: CharSequence, n: Int): String = {
    val len = s.length()
    if (len <= n) s.toString else s"${s.subSequence(0,n)}…"
  }

  def getNonEmptyOrElse (s: String, f: =>String): String = if (s != null && s.nonEmpty) s else f
}
