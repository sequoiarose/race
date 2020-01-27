/*
 * Copyright (c) 2020, United States Government, as represented by the
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
package gov.nasa.race.common.inlined

import java.io.OutputStream

import gov.nasa.race.common.IntRange

/**
  * generic base type for an immutable byte array range object
  *
  * NOTE - the underlying array data can still change since we can't enforce immutability
  * without giving up on direct field access, which is performance critical
  *
  * note - this was meant to be a generic Slice[@specialized(Byte,..)T] trait but unfortunately
  * specialization is as of 2.13.1 still not reliable enough (caused runtime errors due
  * to missing methods). Since this is a performance critical trait that needs to inline
  * correctly/reliably we therefore skip specialization
  */
trait ByteSlice {

  def data: Array[Byte]
  def off: Int
  def len: Int  // can't use length as it clashes with CharSequence.length (in chars)

  @inline final def isEmpty: Boolean = len == 0
  @inline final def nonEmpty: Boolean = len > 0

  @inline final def limit: Int = off + len

  // index is absolute (relative to beginning of array)
  @inline final def at (i: Int): Byte = data(i)

  // note that index is relative to offset
  @inline final def apply (i: Int): Byte = {
    if (i < 0 || i >= len) throw new IndexOutOfBoundsException(s"$i outside [0..$len[")
    data(off+i)
  }

  // slices refer to the same underlying array object and range
  @inline final def =:= (other: ByteSlice): Boolean = (data eq other.data) && (off == other.off) && (len == other.len)
  @inline final def =!= (other: ByteSlice): Boolean = (data ne other.data) || (off != other.off) || (len != other.len)

  protected def equalsData (otherData: Array[Byte], otherOff: Int): Boolean = {
    val bs = this.data
    var i = off
    val iEnd = i + len
    var j = otherOff
    while (i < iEnd) {
      if (bs(i) != otherData(j)) return false  // this is where we need specialization
      i += 1
      j += 1
    }
    true
  }

  @inline final def equalsData (bs: Array[Byte], bsOff: Int, bsLen: Int): Boolean = {
    (len == bsLen) && equalsData(bs,bsOff)
  }

  // slice data values are the same (but not necessarily the array objects)
  // the important part is inlining the length comparison
  @inline final def == (other: ByteSlice): Boolean = (len == other.len) && equalsData(other.data,other.off)
  @inline final def != (other: ByteSlice): Boolean = (len != other.len) || !equalsData(other.data,other.off)


  // note this returns the absolute index
  def indexOf (v: Byte, fromIndex: Int): Int = {
    val data: Array[Byte] = this.data
    var i = fromIndex
    val lim = off + len
    while (i < lim) {
      if (data(i) == v) return i // this is where we need the specialization
      i += 1
    }
    -1
  }
  @inline final def indexOf(v: Byte): Int = indexOf(v,off)

  @inline final def relativeIndexOf (v: Byte) = {
    val i = indexOf(v,off)
    if (i >= 0) i-off else -1
  }

  def occurrencesOf (v: Byte): Int = {
    val data: Array[Byte] = this.data
    var n=0
    var i=off
    val lim = off + len
    while (i < lim){
      if (data(i) == v) n +=1
      i += 1
    }
    n
  }

  @inline def getIntRange: IntRange = IntRange(off,len)

  def copyDataRange (copyOff: Int, copyLen: Int): Array[Byte] = {
    val a = createNewArray(copyLen)
    System.arraycopy(data,copyOff,a,0,copyLen)
    a
  }

  @inline protected def createNewArray (size: Int): Array[Byte] = new Array[Byte](size)

  def writeTo(out: OutputStream): Unit = {
    out.write(data,off,len)
  }
}

/**
  * a generic slice whose fields can be modified externally
  */
trait MutByteSlice extends ByteSlice {
  var data: Array[Byte]
  var off: Int
  var len: Int

  @inline def clear: Unit = {
    data = null
    off = 0
    len = 0
  }

  @inline def clearRange: Unit = {
    off = 0
    len = 0
  }

  @inline def set (newData: Array[Byte], newOff: Int, newLen: Int): Unit = {
    data = newData
    off = newOff
    len = newLen
  }

  @inline def setRange (newOff: Int, newLen: Int): Unit = set(data,newOff,newLen)

  @inline def setFrom (other: ByteSlice): Unit = set(other.data,other.off,other.len)

  def trimSelf (v: Byte) = {
    var i = off
    val iMax = off + len
    val bs = this.data

    while (i < iMax && bs(i) == v) i += 1

    if (i == iMax) { // completely blank
      off = 0
      len = 0

    } else {
      off = i
      i = iMax-1
      while (i > off && bs(i) == v) i -= 1
      len = i - off + 1
    }
  }

  //--- generic implementations

  def cloneRange (cloneOff: Int, cloneLen: Int): this.type = {
    val a = copyDataRange(cloneOff,cloneLen)
    val o = super.clone.asInstanceOf[this.type]
    o.data = a
    o.off = 0
    o.len = cloneLen
    o
  }

  def subSlice (subOff: Int, subLen: Int): this.type = {
    val o = super.clone.asInstanceOf[this.type]
    o.off = subOff
    o.len = subLen
    o
  }
}


/**
  * concrete type for invariant raw byte slices
  */
class RawByteSlice (val data: Array[Byte], val off: Int, val len: Int) extends ByteSlice


object MutRawByteSlice {
  def empty = new MutRawByteSlice(null,0,0)
}
/**
  * concrete type for variant raw byte slices
  */
class MutRawByteSlice (var data: Array[Byte], var off: Int, var len: Int) extends MutByteSlice
