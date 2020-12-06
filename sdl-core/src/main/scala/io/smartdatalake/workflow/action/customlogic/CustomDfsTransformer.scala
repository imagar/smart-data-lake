/*
 * Smart Data Lake - Build your data lake the smart way.
 *
 * Copyright © 2019-2020 ELCA Informatique SA (<https://www.elca.ch>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package io.smartdatalake.workflow.action.customlogic

import io.smartdatalake.config.SdlConfigObject.{ActionObjectId, DataObjectId}
import io.smartdatalake.util.hdfs.{HdfsUtil, PartitionValues}
import io.smartdatalake.util.misc.{CustomCodeUtil, DefaultExpressionData, SparkExpressionUtil}
import io.smartdatalake.workflow.ActionPipelineContext
import io.smartdatalake.workflow.action.ActionHelper
import io.smartdatalake.workflow.action.customlogic.CustomDfsTransformerConfig.fnTransformType
import org.apache.spark.sql.{DataFrame, SparkSession}

/**
 * Interface to define a custom Spark-DataFrame transformation (n:m)
 * Same trait as [[CustomDfTransformer]], but multiple input and outputs supported.
 */
trait CustomDfsTransformer extends Serializable {

  /**
   * Function be implemented to define the transformation between several input and output DataFrames (n:m)
   *
   * @param session Spark Session
   * @param options Options specified in the configuration for this transformation
   * @param dfs DataFrames to be transformed
   * @return Transformed DataFrame
   */
  def transform(session: SparkSession, options: Map[String,String], dfs: Map[String,DataFrame]) : Map[String,DataFrame]

}

/**
 * Configuration of a custom Spark-DataFrame transformation between several inputs and outputs (n:m).
 * Define a transform function which receives a map of input DataObjectIds with DataFrames and a map of options and as
 * to return a map of output DataObjectIds with DataFrames, see also trait [[CustomDfsTransformer]].
 *
 * @param className Optional class name implementing trait [[CustomDfsTransformer]]
 * @param scalaFile Optional file where scala code for transformation is loaded from. The scala code in the file needs to be a function of type [[fnTransformType]].
 * @param scalaCode Optional scala code for transformation. The scala code needs to be a function of type [[fnTransformType]].
 * @param sqlCode Optional map of DataObjectId and corresponding SQL Code.
 *                Use tokens %{<key>} to replace with runtimeOptions in SQL code.
 *                Example: "select * from test where run = %{runId}"
 * @param options Options to pass to the transformation
 * @param runtimeOptions optional tuples of [key, spark sql expression] to be added as additional options when executing transformation.
 *                       The spark sql expressions are evaluated against an instance of [[DefaultExpressionData]].
 */
case class CustomDfsTransformerConfig( className: Option[String] = None, scalaFile: Option[String] = None, scalaCode: Option[String] = None, sqlCode: Map[DataObjectId,String] = Map(), options: Map[String,String] = Map(), runtimeOptions: Map[String,String] = Map()) {
  require(className.isDefined || scalaFile.isDefined || scalaCode.isDefined || sqlCode.nonEmpty, "Either className, scalaFile, scalaCode or sqlCode must be defined for CustomDfsTransformer")

  // Load Transformer code from appropriate location
  val impl: Option[CustomDfsTransformer] = className.map {
    clazz => CustomCodeUtil.getClassInstanceByName[CustomDfsTransformer](clazz)
  }.orElse{
    scalaFile.map {
      file =>
        val fnTransform = CustomCodeUtil.compileCode[fnTransformType](HdfsUtil.readHadoopFile(file))
        new CustomDfsTransformerWrapper( fnTransform )
    }
  }.orElse{
    scalaCode.map {
      code =>
        val fnTransform = CustomCodeUtil.compileCode[fnTransformType](code)
        new CustomDfsTransformerWrapper( fnTransform )
    }
  }.orElse{
    if (sqlCode.nonEmpty) {
      def sqlTransform(session: SparkSession, options: Map[String,String], dfs: Map[String,DataFrame]): Map[String,DataFrame] = {
        // register all input DataObjects as temporary table
        dfs.foreach {
          case (dataObjectId,df) =>
            val objectId =  ActionHelper.replaceSpecialCharactersWithUnderscore(dataObjectId)
            // Using createTempView does not work because the same data object might be created more than once
            df.createOrReplaceTempView(objectId)
        }
        // execute all queries and return them under corresponding dataObjectId
        sqlCode.map {
          case (dataObjectId,sql) => {
            val df = try {
              val preparedSql = SparkExpressionUtil.substituteOptions(dataObjectId, Some("transformation.sqlCode"), sql, options)
              session.sql(preparedSql)
            } catch {
              case e : Throwable => throw new SQLTransformationException(s"(transformation for $dataObjectId) Could not execute SQL query. Check your query and remember that special characters are replaced by underscores. Error: ${e.getMessage}")
            }
            (dataObjectId.id, df)
          }
        }
      }
      Some(new CustomDfsTransformerWrapper( sqlTransform ))
    } else None
  }

  override def toString: String = {
    if(className.isDefined)       "className: "+className.get
    else if(scalaFile.isDefined)  "scalaFile: "+scalaFile.get
    else if(scalaCode.isDefined)  "scalaCode: "+scalaCode.get
    else                          "sqlCode: "+sqlCode
  }

  def transform(actionId: ActionObjectId, partitionValues: Seq[PartitionValues], dfs: Map[String,DataFrame])(implicit session: SparkSession, context: ActionPipelineContext) : Map[String,DataFrame] = {
    // replace runtime options
    lazy val data = DefaultExpressionData.from(context, partitionValues)
    val runtimeOptionsReplaced = runtimeOptions.mapValues {
      expr => SparkExpressionUtil.evaluateString(actionId, Some("transformation.runtimeObjects"), expr, data)
    }.filter(_._2.isDefined).mapValues(_.get)
    // transform
    impl.get.transform(session, options ++ runtimeOptionsReplaced, dfs)
  }
}

object CustomDfsTransformerConfig {
  type fnTransformType = (SparkSession, Map[String,String], Map[String,DataFrame]) => Map[String,DataFrame]
}