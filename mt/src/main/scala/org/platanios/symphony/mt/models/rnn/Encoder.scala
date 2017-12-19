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

import org.platanios.symphony.mt.data.Vocabulary
import org.platanios.symphony.mt.{Environment, Language}
import org.platanios.tensorflow.api.learn.Mode
import org.platanios.tensorflow.api._
import org.platanios.tensorflow.api.ops.control_flow.WhileLoopVariable
import org.platanios.tensorflow.api.ops.rnn.cell.Tuple

/**
  * @author Emmanouil Antonios Platanios
  */
abstract class Encoder[S, SS]()(implicit
    evS: WhileLoopVariable.Aux[S, SS],
    evSDropout: ops.rnn.cell.DropoutWrapper.Supported[S]
) {
  def create(inputSequences: Output, sequenceLengths: Output, mode: Mode): Encoder.Instance[S]
}

object Encoder {
  case class Instance[S](tuple: Tuple[Output, Seq[S]], trainableVars: Set[Variable], nonTrainableVars: Set[Variable])
}

class UnidirectionalEncoder[S, SS](
    val srcLanguage: Language,
    val srcVocabulary: Vocabulary,
    val env: Environment,
    val rnnConfig: RNNConfig,
    val cell: Cell[S, SS],
    val numUnits: Int,
    val numLayers: Int,
    val dataType: DataType = FLOAT32,
    val residual: Boolean = false,
    val dropout: Option[Float] = None,
    val residualFn: Option[(Output, Output) => Output] = Some((input: Output, output: Output) => input + output)
)(implicit
    evS: WhileLoopVariable.Aux[S, SS],
    evSDropout: ops.rnn.cell.DropoutWrapper.Supported[S]
) extends Encoder[S, SS]()(evS, evSDropout) {
  override def create(inputSequences: Output, sequenceLengths: Output, mode: Mode): Encoder.Instance[S] = {
    // Time-major transpose
    val transposedSequences = if (rnnConfig.timeMajor) inputSequences.transpose() else inputSequences

    // Embeddings
    val embeddings = Model.embeddings(dataType, srcVocabulary.size, numUnits, "Embeddings")
    val embeddedSequences = tf.embeddingLookup(embeddings, transposedSequences)

    // RNN
    val numResLayers = if (residual && numLayers > 1) numLayers - 1 else 0
    val uniCell = Model.multiCell(
      cell, numUnits, dataType, numLayers, numResLayers, dropout,
      residualFn, 0, env.numGPUs, env.randomSeed, "MultiUniCell")
    val uniCellInstance = uniCell.createCell(mode, embeddedSequences.shape)
    val uniTuple = tf.dynamicRNN(
      uniCellInstance.cell, embeddedSequences, null, rnnConfig.timeMajor, rnnConfig.parallelIterations,
      rnnConfig.swapMemory, sequenceLengths, "UnidirectionalLayers")
    Encoder.Instance(
      tuple = uniTuple,
      trainableVars = uniCellInstance.trainableVariables + embeddings,
      nonTrainableVars = uniCellInstance.nonTrainableVariables)
  }
}

class BidirectionalEncoder[S, SS](
    val srcLanguage: Language,
    val srcVocabulary: Vocabulary,
    val env: Environment,
    val rnnConfig: RNNConfig,
    val cell: Cell[S, SS],
    val numUnits: Int,
    val numLayers: Int,
    val dataType: DataType = FLOAT32,
    val residual: Boolean = false,
    val dropout: Option[Float] = None,
    val residualFn: Option[(Output, Output) => Output] = Some((input: Output, output: Output) => input + output)
)(implicit
    evS: WhileLoopVariable.Aux[S, SS],
    evSDropout: ops.rnn.cell.DropoutWrapper.Supported[S]
) extends Encoder[S, SS]()(evS, evSDropout) {
  override def create(inputSequences: Output, sequenceLengths: Output, mode: Mode): Encoder.Instance[S] = {
    // Time-major transpose
    val transposedSequences = if (rnnConfig.timeMajor) inputSequences.transpose() else inputSequences

    // Embeddings
    val embeddings = Model.embeddings(dataType, srcVocabulary.size, numUnits, "Embeddings")
    val embeddedSequences = tf.embeddingLookup(embeddings, transposedSequences)

    // RNN
    val numResLayers = if (residual && numLayers > 1) numLayers - 1 else 0
    val biCellFw = Model.multiCell(
      cell, numUnits, dataType, numLayers / 2, numResLayers / 2, dropout,
      residualFn, 0, env.numGPUs, env.randomSeed, "MultiBiCellFw")
    val biCellBw = Model.multiCell(
      cell, numUnits, dataType, numLayers / 2, numResLayers / 2, dropout,
      residualFn, numLayers / 2, env.numGPUs, env.randomSeed, "MultiBiCellBw")
    val biCellInstanceFw = biCellFw.createCell(mode, embeddedSequences.shape)
    val biCellInstanceBw = biCellBw.createCell(mode, embeddedSequences.shape)
    val unmergedBiTuple = tf.bidirectionalDynamicRNN(
      biCellInstanceFw.cell, biCellInstanceBw.cell, embeddedSequences, null, null, rnnConfig.timeMajor,
      rnnConfig.parallelIterations, rnnConfig.swapMemory, sequenceLengths, "BidirectionalLayers")
    val biTuple = Tuple(
      tf.concatenate(Seq(unmergedBiTuple._1.output, unmergedBiTuple._2.output), -1),
      unmergedBiTuple._1.state.map(List(_))
          .zipAll(unmergedBiTuple._2.state.map(List(_)), Nil, Nil)
          .flatMap(Function.tupled(_ ::: _)))
    Encoder.Instance(
      tuple = biTuple,
      trainableVars = biCellInstanceFw.trainableVariables ++ biCellInstanceBw.trainableVariables + embeddings,
      nonTrainableVars = biCellInstanceFw.nonTrainableVariables ++ biCellInstanceBw.nonTrainableVariables)
  }
}