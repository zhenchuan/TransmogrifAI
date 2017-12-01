/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 */

package org.apache.spark.ml

import com.salesforce.op.features.types.FeatureType
import com.salesforce.op.stages.OpPipelineStageBase
import com.salesforce.op.utils.reflection.ReflectionUtils
import org.apache.hadoop.fs.Path
import org.apache.spark.ml.OpPipelineStageReadWriteShared._
import org.apache.spark.ml.util.{DefaultParamsWriter, MLWriter}
import org.json4s.Extraction
import org.json4s.JsonAST.{JObject, JValue}
import org.json4s.jackson.JsonMethods.{compact, parse, render}

import scala.reflect.runtime.universe.TypeTag
import scala.util.{Failure, Success, Try}

/**
 * MLWriter class used to write Optimus Prime stages to disk
 *
 * @param stage a stage to save
 */
final class OpPipelineStageWriter(val stage: OpPipelineStageBase) extends MLWriter {

  override protected def saveImpl(path: String): Unit = {
    val metadataPath = new Path(path, "metadata").toString
    sc.parallelize(Seq(writeToJsonString), 1).saveAsTextFile(metadataPath)
  }

  /**
   * Stage metadata json string
   *
   * @return stage metadata json string
   */
  def writeToJsonString: String = compact(writeToJson)

  /**
   * Stage metadata json
   *
   * @return stage metadata json
   */
  def writeToJson: JObject = jsonSerialize(writeToMap).asInstanceOf[JObject]

  /**
   * Stage metadata map
   *
   * @return stage metadata map
   */
  def writeToMap: Map[String, Any] = {
    // We produce stage metadata for all the Spark params
    val metadataJson = DefaultParamsWriter.getMetadataToSave(stage, sc)
    // Add isModel indicator
    val metadata = parse(metadataJson).extract[Map[String, Any]] + (FieldNames.IsModel.entryName -> isModel)
    // In case we stumbled upon a model instance, we also include it's ctor argg
    // so we can reconstruct the model instance when loading
    if (isModel) metadata + (FieldNames.CtorArgs.entryName -> modelCtorArgs().toMap) else metadata
  }

  private def isModel: Boolean = stage.isInstanceOf[Model[_]]

  /**
   * Extract model ctor args values keyed by their names, so we can reconstruct the model instance when loading.
   * See [[OpPipelineStageReader]].
   *
   * @return model ctor args values by their names
   */
  private def modelCtorArgs(): Seq[(String, AnyValue)] = Try {
    // Reflect all model ctor args
    val (_, argsList) = ReflectionUtils.bestCtorWithArgs(stage)

    // Wrap all ctor args into AnyValue container
    for {(argName, argValue) <- argsList} yield {
      val anyValue = argValue match {
        // Special handling for Feature Type TypeTags
        case t: TypeTag[_] if FeatureType.isFeatureType(t) || FeatureType.isFeatureValueType(t) =>
          AnyValue(`type` = AnyValueTypes.TypeTag, value = t.tpe.dealias.toString)
        case t: TypeTag[_] =>
          throw new RuntimeException(
            s"Unknown type tag '${t.tpe.dealias.toString}'. " +
              "Only Feature and Feature Value type tags are supported for serialization."
          )

        // Spark wrapped stage is saved using [[SparkWrapperParams]], so we just writing it's uid here
        case v: Option[_] if v.isDefined && v.get.isInstanceOf[PipelineStage] =>
          AnyValue(AnyValueTypes.SparkWrappedStage, v.get.asInstanceOf[PipelineStage].uid)
        case v: PipelineStage =>
          AnyValue(AnyValueTypes.SparkWrappedStage, v.uid)

        // Everything else goes as is and is handled by json4s
        case v =>
          // try serialize value with json4s
          val av = AnyValue(AnyValueTypes.Value, v)
          Try(jsonSerialize(av)) match {
            case Success(_) => av
            case Failure(e) =>
              throw new RuntimeException(s"Failed to json4s serialize argument '$argName' with value '$v'", e)
          }

      }
      argName -> anyValue
    }
  } match {
    case Success(args) => args
    case Failure(error) =>
      throw new RuntimeException(s"Failed to extract constructor arguments for model stage '${stage.uid}'. " +
        "Make sure your model class is a concrete final class with json4s serializable arguments.", error
      )
  }

  private def jsonSerialize(v: Any): JValue = render(Extraction.decompose(v))
}
