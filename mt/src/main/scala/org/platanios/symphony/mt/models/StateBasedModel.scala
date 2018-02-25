/* Copyright 2017-18, Emmanouil Antonios Platanios. All Rights Reserved.
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

package org.platanios.symphony.mt.models

import org.platanios.symphony.mt.{Environment, Language}
import org.platanios.symphony.mt.data._
import org.platanios.symphony.mt.models.rnn.{Cell, RNNDecoder, RNNEncoder}
import org.platanios.symphony.mt.vocabulary.Vocabulary
import org.platanios.tensorflow.api._
import org.platanios.tensorflow.api.learn.Mode
import org.platanios.tensorflow.api.learn.layers.rnn.cell._
import org.platanios.tensorflow.api.ops.control_flow.WhileLoopVariable
import org.platanios.tensorflow.api.ops.rnn.cell.Tuple

/**
  * @author Emmanouil Antonios Platanios
  */
class StateBasedModel[S, SS](
    override val name: String = "Model",
    override val srcLanguage: Language,
    override val srcVocabulary: Vocabulary,
    override val tgtLanguage: Language,
    override val tgtVocabulary: Vocabulary,
    override val dataConfig: DataConfig,
    override val config: StateBasedModel.Config[S, SS],
    override val optConfig: Model.OptConfig,
    override val logConfig : Model.LogConfig  = Model.LogConfig(),
    override val trainEvalDataset: () => TFBilingualDataset = null,
    override val devEvalDataset: () => TFBilingualDataset = null,
    override val testEvalDataset: () => TFBilingualDataset = null
)(implicit
    evS: WhileLoopVariable.Aux[S, SS],
    evSDropout: ops.rnn.cell.DropoutWrapper.Supported[S]
) extends Model[(Tuple[Output, Seq[S]], Output)](
  name, srcLanguage, srcVocabulary, tgtLanguage, tgtVocabulary, dataConfig, config, optConfig, logConfig,
  trainEvalDataset, devEvalDataset, testEvalDataset
) {
  // TODO: Make this use the parameters manager.

  override protected def encoder(input: (Output, Output), mode: Mode): (Tuple[Output, Seq[S]], Output) = {
    tf.learn.variableScope("Encoder") {
      (config.encoder.create(config.env, input._1, input._2, srcVocabulary, mode), input._2)
    }
  }

  override protected def decoder(
      input: Option[(Output, Output)],
      state: Option[(Tuple[Output, Seq[S]], Output)],
      mode: Mode
  ): (Output, Output) = tf.learn.variableScope("Decoder") {
    // TODO: What if the state is `None`?
    input match {
      case Some(inputSequences) =>
        // TODO: Handle this shift more efficiently.
        // Shift the target sequence one step forward so the decoder learns to output the next word.
        val tgtBosId = tgtVocabulary.lookupTable().lookup(tf.constant(dataConfig.beginOfSequenceToken)).cast(INT32)
        val tgtSequence = tf.concatenate(Seq(
          tf.fill(INT32, tf.stack(Seq(tf.shape(inputSequences._1)(0), 1)))(tgtBosId),
          inputSequences._1), axis = 1)
        val tgtSequenceLength = inputSequences._2 + 1
        val output = config.decoder.create(config.env, state.get._1, state.get._2, tgtVocabulary,
          dataConfig.tgtMaxLength, dataConfig.beginOfSequenceToken, dataConfig.endOfSequenceToken, tgtSequence,
          tgtSequenceLength, mode)
        (output.sequences, output.sequenceLengths)
      case None =>
        val output = config.decoder.create(
          config.env, state.get._1, state.get._2, tgtVocabulary, dataConfig.tgtMaxLength,
          dataConfig.beginOfSequenceToken, dataConfig.endOfSequenceToken, null, null, mode)
        // Make sure the outputs are of shape [batchSize, time] or [beamWidth, batchSize, time]
        // when using beam search.
        val outputSequence = {
          if (config.timeMajor)
            output.sequences.transpose()
          else if (output.sequences.rank == 3)
            output.sequences.transpose(Tensor(2, 0, 1))
          else
            output.sequences
        }
        (outputSequence(---, 0 :: -1), output.sequenceLengths - 1)
    }
  }
}

object StateBasedModel {
  def apply[S, SS](
      name: String = "Model",
      srcLanguage: Language,
      srcVocabulary: Vocabulary,
      tgtLanguage: Language,
      tgtVocabulary: Vocabulary,
      dataConfig: DataConfig,
      config: StateBasedModel.Config[S, SS],
      optConfig: Model.OptConfig,
      logConfig: Model.LogConfig,
      trainEvalDataset: () => TFBilingualDataset = null,
      devEvalDataset: () => TFBilingualDataset = null,
      testEvalDataset: () => TFBilingualDataset = null
  )(implicit
      evS: WhileLoopVariable.Aux[S, SS],
      evSDropout: ops.rnn.cell.DropoutWrapper.Supported[S]
  ): StateBasedModel[S, SS] = {
    new StateBasedModel[S, SS](
      name, srcLanguage, srcVocabulary, tgtLanguage, tgtVocabulary, dataConfig, config, optConfig, logConfig,
      trainEvalDataset, devEvalDataset, testEvalDataset)(evS, evSDropout)
  }

  class Config[S, SS] protected (
      override val env: Environment,
      override val labelSmoothing: Float,
      // Model
      val encoder: RNNEncoder[S, SS],
      val decoder: RNNDecoder[S, SS],
      override val timeMajor: Boolean = false,
      override val summarySteps: Int = 100,
      override val checkpointSteps: Int = 1000
  ) extends Model.Config(env, labelSmoothing, timeMajor, summarySteps, checkpointSteps)

  object Config {
    def apply[S, SS](
        env: Environment,
        // Model
        encoder: RNNEncoder[S, SS],
        decoder: RNNDecoder[S, SS],
        timeMajor: Boolean = false,
        labelSmoothing: Float = 0.1f,
        summarySteps: Int = 100,
        checkpointSteps: Int = 1000
    ): Config[S, SS] = {
      new Config[S, SS](env, labelSmoothing, encoder, decoder, timeMajor, summarySteps, checkpointSteps)
    }
  }

  private[models] def embeddings(
      dataType: DataType, srcSize: Int, numUnits: Int, name: String = "Embeddings"): Variable = {
    val embeddingsInitializer = tf.RandomUniformInitializer(-0.1f, 0.1f)
    tf.variable(name, dataType, Shape(srcSize, numUnits), embeddingsInitializer)
  }

  private[this] def device(layerIndex: Int, numGPUs: Int = 0, firstGPU: Int = 0): String = {
    if (numGPUs - firstGPU <= 0)
      "/device:CPU:0"
    else
      s"/device:GPU:${firstGPU + (layerIndex % (numGPUs - firstGPU))}"
  }

  private[models] def cell[S, SS](
      cellCreator: Cell[S, SS],
      numUnits: Int,
      dataType: DataType,
      dropout: Option[Float] = None,
      residualFn: Option[(Output, Output) => Output] = None,
      device: Option[String] = None,
      seed: Option[Int] = None,
      name: String
  )(implicit
      evS: WhileLoopVariable.Aux[S, SS],
      evSDropout: ops.rnn.cell.DropoutWrapper.Supported[S]
  ): tf.learn.RNNCell[Output, Shape, S, SS] = tf.learn.variableScope(name) {
    var createdCell = cellCreator.create(name, numUnits, dataType)
    createdCell = dropout.map(p => DropoutWrapper("Dropout", createdCell, 1.0f - p, seed = seed)).getOrElse(createdCell)
    createdCell = residualFn.map(ResidualWrapper("Residual", createdCell, _)).getOrElse(createdCell)
    createdCell = device.map(DeviceWrapper("Device", createdCell, _)).getOrElse(createdCell)
    createdCell
  }

  private[models] def cells[S, SS](
      cellCreator: Cell[S, SS],
      numUnits: Int,
      dataType: DataType,
      numLayers: Int,
      numResidualLayers: Int,
      dropout: Option[Float] = None,
      residualFn: Option[(Output, Output) => Output] = Some((input: Output, output: Output) => input + output),
      baseGPU: Int = 0,
      numGPUs: Int = 0,
      firstGPU: Int = 0,
      seed: Option[Int] = None,
      name: String
  )(implicit
      evS: WhileLoopVariable.Aux[S, SS],
      evSDropout: ops.rnn.cell.DropoutWrapper.Supported[S]
  ): Seq[tf.learn.RNNCell[Output, Shape, S, SS]] = tf.learn.variableScope(name) {
    (0 until numLayers).map(i => {
      cell(
        cellCreator, numUnits, dataType, dropout, if (i >= numLayers - numResidualLayers) residualFn else None,
        Some(device(i + baseGPU, numGPUs, firstGPU)), seed, s"Cell$i")
    })
  }

  private[models] def multiCell[S, SS](
      cellCreator: Cell[S, SS],
      numUnits: Int,
      dataType: DataType,
      numLayers: Int,
      numResidualLayers: Int,
      dropout: Option[Float] = None,
      residualFn: Option[(Output, Output) => Output] = Some((input: Output, output: Output) => input + output),
      baseGPU: Int = 0,
      numGPUs: Int = 0,
      firstGPU: Int = 0,
      seed: Option[Int] = None,
      name: String
  )(implicit
      evS: WhileLoopVariable.Aux[S, SS],
      evSDropout: ops.rnn.cell.DropoutWrapper.Supported[S]
  ): tf.learn.RNNCell[Output, Shape, Seq[S], Seq[SS]] = {
    MultiCell(name, cells(
      cellCreator, numUnits, dataType, numLayers, numResidualLayers, dropout,
      residualFn, baseGPU, numGPUs, firstGPU, seed, name))
  }
}
