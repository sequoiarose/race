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

package gov.nasa.race.archive

import gov.nasa.race.Dated
import gov.nasa.race.util.FileUtils
import gov.nasa.race.uom.DateTime

/*
 * support for archiving and replay of data
 *
 * unfortunately we can't do this typed since the Archive/ReplayActors are untyped
 * (instantiates ArchiveWriter/Reader via reflection) and its message handlers
 * would need the types. Besides, the archiver might actually accept different input formats
 * (text, objects). Type checks have to happen in the archiver implementation
 *
 * note that we DO NOT refer to any stream based implementation here and require
 * the concrete types to do so (e.g. by means of ConfigurableStreamCreator mixins). One of the
 * reasons is that most concrete classes use their specialized stream decorators which require
 * proper initialization and closing (e.g. to flush content)
 */

/**
  * type that archives time-stamped objects
  */
trait ArchiveWriter {
  val pathName: String

  if (!checkPathName) handlePathNameFailure // no point instantiating if we can't write

  def write (date: DateTime, obj: Any): Boolean
  def close: Unit

  def checkPathName: Boolean = FileUtils.ensureWritable(pathName).isDefined
  def handlePathNameFailure = throw new RuntimeException(s"not a writable pathname: $pathName")
}


/**
  * type that reads time-stamped ArchiveEntry objects from archives
  */
trait ArchiveReader extends DateAdjuster {
  class ArchiveEntry (var date: DateTime, var msg: Any) extends Dated

  //--- optimization to reduce heap pressure by avoiding a ton of short living Option objects

  // NOTE - this breaks immutability of the return value. Use only in a single threaded context
  // that does not store ArchiveEntries

  protected val nextEntry = new ArchiveEntry(DateTime.UndefinedDateTime,null)
  protected val someEntry = Some(nextEntry)

  protected def someEntry(d: DateTime, m: Any): Option[ArchiveEntry] = {
    nextEntry.date = d
    nextEntry.msg = m
    someEntry
  }

  //--- to be provided by subtypes
  def hasMoreData: Boolean
  def readNextEntry: Option[ArchiveEntry]
  def close: Unit

  val pathName: String
}

