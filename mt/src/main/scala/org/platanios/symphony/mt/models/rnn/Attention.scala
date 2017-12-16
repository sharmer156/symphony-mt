/* Copyright 2017, Emmanouil Antonios Platanios. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.platanios.symphony.mt.models.rnn

import org.platanios.tensorflow.api._
import org.platanios.tensorflow.api.ops.variables.OnesInitializer

/**
  * @author Emmanouil Antonios Platanios
  */
trait Attention {
  def create(
      memory: Output,
      memoryWeights: Output,
      memorySequenceLengths: Output = null,
      variableFn: (String, DataType, Shape, tf.VariableInitializer) => Variable,
      name: String = "Attention"
  ): tf.Attention
}

case class LuongAttention(
    scaled: Boolean = false,
    probabilityFn: (Output) => Output = tf.softmax(_, name = "Probability"),
    scoreMask: Float = Float.NegativeInfinity
) extends Attention {
  def create(
      memory: Output,
      memoryWeights: Output,
      memorySequenceLengths: Output = null,
      variableFn: (String, DataType, Shape, tf.VariableInitializer) => Variable,
      name: String = "LuongAttention"
  ): tf.Attention = {
    val scale = if (scaled) variableFn("LuongFactor", memory.dataType, Shape.scalar(), OnesInitializer) else null
    tf.LuongAttention(memory, memoryWeights, memorySequenceLengths, scale, probabilityFn, scoreMask, name)
  }
}