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

package gov.nasa.race.swing

import java.awt.Color

import scala.swing._
import gov.nasa.race.swing.Style._

/**
  * a fixed size label to display variable values
  */
class StringOutput(varName: String, editable: Boolean = false, align: Alignment.Value = Alignment.Center)
                  (implicit outputLength: Int=12,labelLength: Int=6)
                                                    extends BoxPanel(Orientation.Horizontal){
  val label = new Label(varName).styled("fieldLabel")

  val value = new TextField(outputLength)
  value.editable = editable
  value.horizontalAlignment = align
  value.styled("stringField")  // we have to style after setting editable

  val tfSize = value.preferredSize
  val lblSize = label.preferredSize
  preferredSize = (tfSize.width + lblSize.width, tfSize.height)

  contents ++= Seq(label,value)

  def setValue (s: String) = value.text = s
  def setForeground( color: Color) = value.foreground = color
}
