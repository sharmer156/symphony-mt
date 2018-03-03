///* Copyright 2017-18, Emmanouil Antonios Platanios. All Rights Reserved.
// *
// * Licensed under the Apache License, Version 2.0 (the "License"); you may not
// * use this file except in compliance with the License. You may obtain a copy of
// * the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// * License for the specific language governing permissions and limitations under
// * the License.
// */
//
//package org.platanios.symphony.mt.experiments
//
//import org.platanios.symphony.mt.{Environment, Language}
//import org.platanios.symphony.mt.Language.{english, vietnamese}
//import org.platanios.symphony.mt.data._
//import org.platanios.symphony.mt.data.loaders.IWSLT15DatasetLoader
//import org.platanios.symphony.mt.evaluation.{BLEU, BilingualEvaluator}
//import org.platanios.symphony.mt.models.{Model, Transformer}
//import org.platanios.symphony.mt.models.attention._
//import org.platanios.symphony.mt.models.helpers.NoamSchedule
//import org.platanios.tensorflow.api._
//
//import java.nio.file.{Path, Paths}
//
///**
//  * @author Emmanouil Antonios Platanios
//  */
//object TransformerIWSLT15 extends App {
//  val workingDir: Path = Paths.get("temp").resolve("transformer")
//
//  val srcLanguage: Language = english
//  val tgtLanguage: Language = vietnamese
//
//  val dataConfig = DataConfig(
//    workingDir = Paths.get("temp").resolve("data"),
//    numBuckets = 5,
//    srcMaxLength = 50,
//    tgtMaxLength = 50,
//    trainBatchSize = 1024)
//
//  val dataset: FileParallelDataset = IWSLT15DatasetLoader(srcLanguage, tgtLanguage, dataConfig).load()
//
//  val env = Environment(
//    workingDir = workingDir.resolve(s"${srcLanguage.abbreviation}-${tgtLanguage.abbreviation}"),
//    numGPUs = 4,
//    parallelIterations = 32,
//    swapMemory = true,
//    randomSeed = Some(10))
//
//  val optConfig = Model.OptConfig(
//    optimizer = tf.train.Adam(
//      0.0002, NoamSchedule(warmUpSteps = 4000, hiddenSize = 256),
//      beta1 = 0.9, beta2 = 0.98, learningRateSummaryTag = "LearningRate"))
//
//  val logConfig = Model.LogConfig(
//    logLossSteps = 100,
//    logTrainEvalSteps = -1)
//
//  val model = Transformer(
//    name = "Model",
//    srcLanguage = srcLanguage,
//    srcVocabulary = dataset.vocabulary(srcLanguage),
//    tgtLanguage = tgtLanguage,
//    tgtVocabulary = dataset.vocabulary(tgtLanguage),
//    dataConfig = dataConfig,
//    config = Transformer.Config(
//      env = env,
//      labelSmoothing = 0.1f,
//      useSelfAttentionProximityBias = false,
//      positionalEmbeddings = FixedSinusoidPositionalEmbeddings(1.0f, 1e4f),
//      postPositionEmbeddingsDropout = 0.1f,
//      layerPreprocessors = Seq(
//        Normalize(LayerNormalization(), 1e-6f)),
//      layerPostprocessors = Seq(
//        Dropout(0.1f, broadcastAxes = Set(1)),
//        AddResidualConnection),
//      hiddenSize = 256,
//      encoderNumLayers = 2,
//      encoderSelfAttention = DotProductAttention(0.1f, Set.empty, "EncoderSelfAttention"),
//      encoderFeedForwardLayer = DenseReLUDenseFeedForwardLayer(1024, 256, 0.0f, Set.empty, "EncoderFeedForward"),
//      decoderNumLayers = 2,
//      decoderSelfAttention = DotProductAttention(0.1f, Set.empty, "DecoderSelfAttention"),
//      attentionKeysDepth = 256,
//      attentionValuesDepth = 256,
//      attentionNumHeads = 4,
//      attentionDropoutRate = 0.1f,
//      attentionDropoutBroadcastAxes = Set.empty,
//      attentionPrependMode = AttentionPrependInputsMaskedAttention,
//      usePadRemover = false),
//    optConfig = optConfig,
//    logConfig = logConfig,
//    // TODO: !!! Find a way to set the number of buckets to 1.
//    trainEvalDataset = () => dataset.filterTypes(Train).toTFBilingual(srcLanguage, tgtLanguage, repeat = false, isEval = true),
//    devEvalDataset = () => dataset.filterTypes(Dev).toTFBilingual(srcLanguage, tgtLanguage, repeat = false, isEval = true),
//    testEvalDataset = () => dataset.filterTypes(Test).toTFBilingual(srcLanguage, tgtLanguage, repeat = false, isEval = true))
//
//  model.train(dataset, tf.learn.StopCriteria.steps(12000))
//
//  val evaluator = BilingualEvaluator(Seq(BLEU()), srcLanguage, tgtLanguage, dataset.filterTypes(Test))
//  println(evaluator.evaluate(model).values.head.scalar.asInstanceOf[Float])
//}