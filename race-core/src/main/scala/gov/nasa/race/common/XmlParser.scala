/*
 * Copyright (c) 2016, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
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
package gov.nasa.race.common

import gov.nasa.race.util.XmlPullParser

/**
  * a XmlPullParser with scala specific support for the parse pattern
  * (it needs to be a class because we extend XmlPullParser)
  */
abstract class XmlParser[T] extends XmlPullParser {

  private var _stop = false
  protected def stopParsing = _stop = true

  //--- result management
  protected var _result: Option[T] = None // this is reset before starting to parse and can be set directly by subclasses
  protected def result: Option[T] = _result // NOTE - if this is overridden, make sure to handle result reset
  protected def setResult(res: T) = _result = Some(res) // usually called from concrete onEndElement

  //--- the (optional) subclass extension points (we need at least either onStartElement or onEndElement)
  protected def onStartElement: PartialFunction[String,Unit] = PartialFunction.empty
  protected def onEndElement: PartialFunction[String,Unit] = PartialFunction.empty
  protected def errorResult = None

  def parse (input: String): Option[T] = {
    initialize(input)
    parseLoop
  }
  def parse (input: Array[Char]): Option[T] = {
    initialize(input)
    parseLoop
  }

  def parseLoop: Option[T] = {
    _stop = false
    _result = None

    try {
      while (!_stop && parseNextElement()){
        if (isStartElement) onStartElement(tag) else onEndElement(tag)
      }
      result

    } catch {
      case t: Throwable =>
        //t.printStackTrace() // TODO store last error
        errorResult
    }
  }

  def parseAllAttributes (pf: PartialFunction[String,Unit]) = {
    while (parseNextAttribute()){
      pf(attr)
    }
  }
}

// returns the top level element name
class XmlMsgExtractor extends XmlParser[String] {
  override def onStartElement = {
    case e: String =>
      setResult(e)
      stopParsing
  }
}

// returns the value of a given attribute name for elements that match the provided function
class XmlAttrExtractor (attrName: String, elemMatcher: String=>Boolean) extends XmlParser[String] {
  override def onStartElement = {
    case e =>
      if (elemMatcher(e)) {
        if (parseAttribute(attrName)) setResult(value)
        stopParsing
      }
  }
}
