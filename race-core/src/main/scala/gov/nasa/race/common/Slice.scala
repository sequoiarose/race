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
package gov.nasa.race.common

import java.io.OutputStream

object Slice {

  def apply (s: String): Slice = {
    val bs = s.getBytes()
    new Slice(bs,0,bs.length)
  }

  def apply (bs: Array[Byte], off: Int, len: Int): Slice = new Slice(bs,off,len)

  def empty: Slice = new Slice(Array.empty[Byte],0,0)

  final val TruePattern = "true".getBytes
  final val FalsePattern = "false".getBytes
  final val YesPattern = "yes".getBytes
  final val NoPattern = "no".getBytes
}

/**
  * a sub-range into a byte array representing strings/chars
  *
  * the trait does not allow to modify the internals
  */
case class Slice (var bs: Array[Byte], var offset: Int, var length: Int) {

  final def isEmpty: Boolean = length == 0
  final def nonEmpty: Boolean = length > 0

  override def toString: String = if (length > 0) new String(bs,offset,length) else ""

  @inline final def apply (i: Int): Byte = bs(offset+i)

  @inline final def clear: Unit = {
    bs = Array.empty[Byte]
    offset = 0
    length = 0
  }

  @inline final def set (bsNew: Array[Byte], offNew: Int, lenNew: Int): Unit = {
    bs = bsNew
    offset = offNew
    length = lenNew
  }

  @inline final def setRange (offNew: Int, lenNew: Int): Unit = {
    offset = offNew
    length = lenNew
  }

  @inline final def setFrom (other: Slice): Unit = set(other.bs,other.offset,other.length)

  @inline final def equals(otherBs: Array[Byte], otherOffset: Int, otherLength: Int): Boolean = {
    val bs = this.bs
    if (length == otherLength) {
      var i = offset
      val iEnd = i + length
      var j = otherOffset
      while (i < iEnd) {
        if (bs(i) != otherBs(j)) return false
        i += 1
        j += 1
      }
      true
    } else false
  }

  @inline final def equalsString (s: String): Boolean = {
    val sbs = s.getBytes // BAD - this is allocating
    equals(sbs,0,sbs.length)
  }

  @inline final def equalsString (s: String, buf: UTF8Buffer): Boolean = {
    val len = buf.encode(s)
    equals(buf.data,0,len)
  }

  @inline final def equalsBuffer (buf: UTF8Buffer): Boolean = {
    equals(buf.data,0,buf.length)
  }

  override def equals (o: Any): Boolean = {
    o match {
      case slice: Slice =>
        equals(slice.bs, slice.offset, slice.length)

      case s: String =>
        equalsString(s)

      case _ => false
    }
  }

  @inline final def == (other: Slice): Boolean = equals(other.bs,other.offset,other.length)

  // todo - add string comparison based on utf-8 encoding

  def equalsIgnoreCase(otherBs: Array[Byte], otherOffset: Int, otherLength: Int): Boolean = {
    if (length != otherLength) return false
    var i = offset
    val iEnd = i + length
    var j = otherOffset
    while (i < iEnd) {
      if ((bs(i)|32) != (otherBs(j)|32)) return false
      i += 1
      j += 1
    }
    true
  }

  def equalsIgnoreCase (otherBs: Array[Byte]): Boolean = equalsIgnoreCase(otherBs,0,otherBs.length)

  def intern: String = {
    if (length > 8) {
      Internalizer.get(bs, offset, length)
    } else {
      ASCII8Internalizer.get(bs,offset,length)
    }
  }

  def writeTo(out: OutputStream): Unit = {
    out.write(bs,offset,length)
  }

  //--- type conversion

  def toDouble: Double = {

    @inline def isDigit(b: Byte): Boolean = b >= '0' && b <= '9'
    @inline def digitValue(b: Byte): Int = b - '0'

    var i = offset
    val iMax = i + length
    val bs = this.bs
    var n: Long = 0
    var d: Double = 0.0
    var e: Long = 1
    var b: Byte = 0

    if (i >= iMax) throw new NumberFormatException(this.toString)
    val sig = if (bs(i)=='-') {i+= 1;  -1 } else 1

    //--- integer part
    while (i < iMax && {b=bs(i); isDigit(b)}){
      n = (n*10) + digitValue(b)
      i += 1
    }

    //--- fractional part
    if (b == '.') {
      i += 1
      var m: Long = 1
      var frac: Int = 0
      while (i < iMax && {b=bs(i); isDigit(b)}){
        frac = (frac*10) + digitValue(b)
        m *= 10
        i += 1
      }
      d = frac/m.toDouble
    }

    //--- exponent part
    if ((b|32) == 'e'){
      i += 1
      if (i >= iMax) throw new NumberFormatException(this.toString)

      e = if ({b=bs(i); b == '-'}){
        i += 1
        -1
      } else if (b == '+'){
        i += 1
        1
      } else 1
      var exp: Int = 0
      while (i < iMax && {b=bs(i); isDigit(b)}){
        exp = (exp*10) + digitValue(b)
        i += 1
      }

      var j = 0
      while (j < exp) { e *= 10; j += 1 }
    }

    if (i < iMax) throw new NumberFormatException(this.toString)

    if (e < 0){
      sig * -(n + d) / e
    } else {
      sig * (n + d) * e
    }
  }

  def toLong: Long = {

    @inline def isDigit(b: Byte): Boolean = b >= '0' && b <= '9'
    @inline def digitValue(b: Byte): Int = b - '0'

    var i = offset
    val iMax = i + length
    val bs = this.bs
    var n: Long = 0
    var b: Byte = 0

    if (i >= iMax) throw new NumberFormatException(this.toString)
    val sig = if (bs(i)=='-') {i+= 1;  -1 } else 1

    //--- integer part
    while (i < iMax && {b=bs(i); isDigit(b)}){
      n = (n*10) + digitValue(b)
      i += 1
    }

    if (i < iMax) throw new NumberFormatException(this.toString)

    sig * n
  }

  def toInt: Int = {
    val l = toLong
    if (l > Int.MaxValue || l < Int.MinValue) throw new NumberFormatException(this.toString) // todo - not the standard behavior, which silently trucates
    l.toInt
  }

  def toBoolean: Boolean = {
    // todo - more precise than Boolean.parseBoolean, but do we want to differ?
    if (length == 1){
      if (bs(offset) == '1') true
      else if (bs(offset) == '0') false
      else throw new RuntimeException(s"not a boolean: $this")
    } else {
      if (equalsIgnoreCase(Slice.TruePattern)) true
      else if (equalsIgnoreCase(Slice.FalsePattern)) false
      else if (equalsIgnoreCase(Slice.YesPattern)) true
      else if (equalsIgnoreCase(Slice.NoPattern)) false
      else  throw new RuntimeException(s"not a boolean: $this")
    }

  }
}


/**
  * a SliceImpl that also computes a 64bit hash value. Can be used for efficient keyword comparison
  *
  * TODO - check if this actually buys much over SliceImpl (runtime type check, cast and super call might nix gains)
  */
/*
class HashedSliceImpl (_bs: Array[Byte], _offset: Int, _length: Int) extends Slice(_bs,_offset,_length) {
  var hash: Long = computeHash

  def this (bs: Array[Byte]) = this (bs,0,bs.length)
  def this (s: String) = this(s.getBytes,0,s.length)

  override def clear: Unit = {
    hash = 0L
    offset = 0
    length = 0
  }

  private def computeHash = {
    if (length == 0) 0L
    else if (length <= 8) ASCII8Hash64.hashBytes(bs,offset,length)
    else MurmurHash64.hashBytes(bs,offset,length)
  }

  def getHash: Long = hash

  def setAndRehash( bsNew: Array[Byte], offNew: Int, lenNew: Int): Unit = {
    bs = bsNew
    offset = offNew
    length = lenNew
    hash = computeHash
  }

  def setAndRehash( offNew: Int, lenNew: Int): Unit = {
    offset = offNew
    length = lenNew
    hash = computeHash
  }

  @inline override def set (bsNew: Array[Byte], offNew: Int, lenNew: Int) = setAndRehash(bsNew,offNew,lenNew)

  @inline final def equals (other: HashedSliceImpl): Boolean = equals(other.bs, other.offset, other.length, other.getHash)

  @inline final def equals (otherBs: Array[Byte], otherOffset: Int, otherLength: Int, otherHash: Long): Boolean = {
    otherHash == hash && equals(otherBs,otherOffset,otherLength)
  }

  override def equals (o: Any): Boolean = {
    o match {
      case slice: HashedSliceImpl => equals(slice.bs,slice.offset,slice.length,slice.hash)
      case slice: Slice => equals(slice.bs, slice.offset, slice.length)
      case _ => false
    }
  }

  @inline final def =:= (other: HashedSliceImpl): Boolean = {
    other.hash == hash && equals(other.bs,other.offset,other.length)
  }

  override def intern: String = {
    if (length > 8) {
      Internalizer.getMurmurHashed( hash, bs, offset, length)
    } else {
      ASCII8Internalizer.getASCII8Hashed( hash, bs,offset,length)
    }
  }
}

 */
