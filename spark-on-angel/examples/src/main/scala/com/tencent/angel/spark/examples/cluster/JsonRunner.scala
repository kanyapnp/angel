/*
 * Tencent is pleased to support the open source community by making Angel available.
 *
 * Copyright (C) 2017-2018 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in 
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/Apache-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */


package com.tencent.angel.spark.examples.cluster

import com.tencent.angel.RunningMode
import com.tencent.angel.conf.AngelConf
import com.tencent.angel.exception.AngelException
import com.tencent.angel.ml.core.conf.{MLConf, SharedConf}
import com.tencent.angel.ml.core.utils.DataParser
import com.tencent.angel.ml.core.utils.paramsutils.JsonUtils
import com.tencent.angel.spark.client.PSClient
import com.tencent.angel.spark.context.PSContext
import com.tencent.angel.spark.examples.util.SparkUtils
import com.tencent.angel.spark.ml.core.{ArgsUtil, GraphModel, OfflineLearner}
import com.tencent.angel.spark.ml.util.{Features, ModelLoader, ModelSaver}
import org.apache.spark.{SparkConf, SparkContext}

object JsonRunner {

  def main(args: Array[String]): Unit = {
    val params  = ArgsUtil.parse(args)
    val input   = params.getOrElse("input", "")
    val output  = params.getOrElse("output", "")
    val modelPath  = params.getOrElse("model", "")
    val actionType = params.getOrElse("action.type", "train")

    SharedConf.addMap(params)
    JsonUtils.init()

    // set running mode, use angel_ps mode for spark
    SharedConf.get().set(AngelConf.ANGEL_RUNNING_MODE, RunningMode.ANGEL_PS.toString)

    // load data
    val conf = new SparkConf()
    val sc   = new SparkContext(conf)
    val parser = DataParser(SharedConf.get())
    val data = sc.textFile(input)
      .repartition(SparkUtils.getNumExecutors(conf))
      .map(f => parser.parse(f))

    // start PS
    PSContext.getOrCreate(sc)

    val model = new GraphModel
    val learner = new OfflineLearner

    // first, build sparseToDense and denseToSparse index
    // and change the feature index from sparse to dense
    val (denseToSparseMatrixId, denseDim, sparseToDenseMatrixId, sparseDim, denseData) = Features.featureSparseToDense(data)
    SharedConf.get().setLong(MLConf.ML_FEATURE_INDEX_RANGE, denseDim)

    // init model here
    model.init(denseData.getNumPartitions)

    denseData.mapPartitions({it =>
      PSClient.instance().context.refreshMatrix
      Iterator.single(0)}).count()

    actionType match {
      case "train" =>
        learner.train(denseData, model)
      case "incTrain" =>
        if (modelPath.length == 0)
          throw new AngelException("Should set model path for increment training!")

        ModelLoader.load(modelPath, model, sparseToDenseMatrixId, denseDim)
        learner.train(denseData, model)
    }

    if (output.length > 0)
      ModelSaver.save(output, model, denseToSparseMatrixId, denseDim)
  }

}